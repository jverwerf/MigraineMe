package com.migraineme

import android.content.Context
import android.util.Log
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MetricsSyncManager {

    suspend fun onLogin(
        context: Context,
        token: String,
        snackbarHostState: SnackbarHostState
    ) {
        val appCtx = context.applicationContext

        withContext(Dispatchers.IO) {
            try {
                val whoopConnected = runCatching { WhoopTokenStore(appCtx).load() != null }.getOrDefault(false)

                val whoopSleepEnabled =
                    DataCollectionSettings.isEnabledForWhoop(appCtx, "sleep_duration_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "sleep_score_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "sleep_efficiency_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "sleep_stages_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "sleep_disturbances_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "fell_asleep_time_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "woke_up_time_daily")

                val whoopPhysicalEnabled =
                    DataCollectionSettings.isEnabledForWhoop(appCtx, "recovery_score_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "resting_hr_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "hrv_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "skin_temp_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "spo2_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "time_in_high_hr_zones_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "steps_daily")

                // Mirror local metric settings into Supabase (public.metric_settings) so backend jobs
                // have the full per-metric matrix (including device metrics like location).
                runCatching {
                    val svc = SupabaseDataCollectionSettingsService(appCtx)

                    val wearableAllowedSources = if (whoopConnected) listOf("whoop") else emptyList()
                    val wearablePreferredSource = if (whoopConnected) "whoop" else null

                    val rows = buildList {
                        // WHOOP sleep metrics
                        add(metricRowWearable(appCtx, "sleep_duration_daily", wearablePreferredSource, wearableAllowedSources))
                        add(metricRowWearable(appCtx, "sleep_score_daily", wearablePreferredSource, wearableAllowedSources))
                        add(metricRowWearable(appCtx, "sleep_efficiency_daily", wearablePreferredSource, wearableAllowedSources))
                        add(metricRowWearable(appCtx, "sleep_stages_daily", wearablePreferredSource, wearableAllowedSources))
                        add(metricRowWearable(appCtx, "sleep_disturbances_daily", wearablePreferredSource, wearableAllowedSources))
                        add(metricRowWearable(appCtx, "fell_asleep_time_daily", wearablePreferredSource, wearableAllowedSources))
                        add(metricRowWearable(appCtx, "woke_up_time_daily", wearablePreferredSource, wearableAllowedSources))

                        // WHOOP physical metrics
                        add(metricRowWearable(appCtx, "recovery_score_daily", wearablePreferredSource, wearableAllowedSources))
                        add(metricRowWearable(appCtx, "resting_hr_daily", wearablePreferredSource, wearableAllowedSources))
                        add(metricRowWearable(appCtx, "hrv_daily", wearablePreferredSource, wearableAllowedSources))
                        add(metricRowWearable(appCtx, "skin_temp_daily", wearablePreferredSource, wearableAllowedSources))
                        add(metricRowWearable(appCtx, "spo2_daily", wearablePreferredSource, wearableAllowedSources))
                        add(metricRowWearable(appCtx, "time_in_high_hr_zones_daily", wearablePreferredSource, wearableAllowedSources))
                        add(metricRowWearable(appCtx, "steps_daily", wearablePreferredSource, wearableAllowedSources))

                        // Device metric: location
                        add(metricRowDevice(appCtx, "user_location_daily"))
                    }

                    svc.upsertMetricSettingsBatch(
                        supabaseAccessToken = token,
                        rows = rows
                    )
                }.onFailure {
                    Log.w("MetricsSyncManager", "Settings sync to Supabase failed: ${it.message}")
                }

                if (whoopConnected && (whoopSleepEnabled || whoopPhysicalEnabled)) {
                    runCatching { WhoopAuthService().refresh(appCtx) }.onFailure {
                        Log.w("MetricsSyncManager", "WHOOP refresh failed: ${it.message}")
                    }

                    // Backend owns the daily scheduled runs.
                    // Cancel any previously-scheduled WorkManager daily chains to avoid double-sync.
                    runCatching {
                        WorkManager.getInstance(appCtx).cancelUniqueWork("whoop_daily_sync_sleep_fields_9am")
                        WorkManager.getInstance(appCtx).cancelUniqueWork("whoop_daily_physical_health_9am")
                    }

                    // Keep login/new-client behavior: run once now.
                    if (whoopSleepEnabled) {
                        WhoopDailySyncWorkerSleepFields.runOnceNow(appCtx)
                    }
                    if (whoopPhysicalEnabled) {
                        WhoopDailyPhysicalHealthWorker.runOnceNow(appCtx)
                    }
                } else if (!whoopConnected && (whoopSleepEnabled || whoopPhysicalEnabled)) {
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(
                            message = "Whoop not connected — connect Whoop to collect data.",
                            duration = SnackbarDuration.Short
                        )
                    }
                }

                // Location (unchanged; still device-scheduled)
                val locationEnabled =
                    DataCollectionSettings.isActive(
                        context = appCtx,
                        table = "user_location_daily",
                        wearable = null,
                        defaultValue = true
                    )

                if (locationEnabled) {
                    LocationDailySyncWorker.runOnceNow(appCtx)
                    LocationDailySyncWorker.scheduleNext(appCtx)
                    LocationDailySyncWorker.backfillUpToToday(appCtx, token)
                }

            } catch (t: Throwable) {
                Log.w("MetricsSyncManager", "onLogin error: ${t.message}")
            }

            Unit
        }
    }

    private fun metricRowWearable(
        ctx: Context,
        metric: String,
        preferredSource: String?,
        allowedSources: List<String>
    ): SupabaseDataCollectionSettingsService.MetricSettingRow {
        val enabled = DataCollectionSettings.isEnabledForWhoop(ctx, metric)
        return SupabaseDataCollectionSettingsService.MetricSettingRow(
            metric = metric,
            enabled = enabled,
            preferredSource = preferredSource,
            allowedSources = allowedSources
        )
    }

    private fun metricRowDevice(
        ctx: Context,
        metric: String
    ): SupabaseDataCollectionSettingsService.MetricSettingRow {
        val enabled = DataCollectionSettings.isActive(
            context = ctx,
            table = metric,
            wearable = null,
            defaultValue = true
        )
        return SupabaseDataCollectionSettingsService.MetricSettingRow(
            metric = metric,
            enabled = enabled,
            preferredSource = "device",
            allowedSources = listOf("device")
        )
    }
}
