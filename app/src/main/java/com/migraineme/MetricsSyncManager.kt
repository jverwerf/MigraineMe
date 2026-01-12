package com.migraineme

import android.content.Context
import android.util.Log
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Called from LoginScreen after successful auth.
 *
 * Responsibilities:
 * - Best-effort WHOOP token refresh (does not change auth approach)
 * - Schedule WHOOP daily workers (sleep + physical) if enabled + connected
 * - Schedule Location worker if enabled
 *
 * Does NOT:
 * - Change Supabase auth
 * - Change Whoop auth structure
 */
object MetricsSyncManager {

    suspend fun onLogin(
        context: Context,
        token: String,
        snackbarHostState: SnackbarHostState
    ) {
        val appCtx = context.applicationContext

        withContext(Dispatchers.IO) {
            try {
                // WHOOP connection = token exists locally
                val whoopConnected = runCatching { WhoopTokenStore(appCtx).load() != null }.getOrDefault(false)

                // Determine if ANY WHOOP tables are enabled (sleep / physical)
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

                // If WHOOP connected, refresh Whoop token once (best-effort).
                // If it fails, we still schedule; workers will log failures and retry next day.
                if (whoopConnected && (whoopSleepEnabled || whoopPhysicalEnabled)) {
                    runCatching { WhoopAuthService().refresh(appCtx) }.onFailure {
                        Log.w("MetricsSyncManager", "WHOOP refresh failed: ${it.message}")
                    }

                    // Schedule + run once now (only if the category is enabled)
                    if (whoopSleepEnabled) {
                        WhoopDailySyncWorkerSleepFields.runOnceNow(appCtx)
                        WhoopDailySyncWorkerSleepFields.scheduleNext(appCtx)
                    }

                    if (whoopPhysicalEnabled) {
                        WhoopDailyPhysicalHealthWorker.runOnceNow(appCtx)
                        WhoopDailyPhysicalHealthWorker.scheduleNext(appCtx)
                    }
                } else if (!whoopConnected && (whoopSleepEnabled || whoopPhysicalEnabled)) {
                    // User has WHOOP collection enabled but no connection.
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(
                            message = "Whoop not connected — connect Whoop to collect data.",
                            duration = SnackbarDuration.Short
                        )
                    }
                }

                // Location (NOT Whoop-specific)
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
}
