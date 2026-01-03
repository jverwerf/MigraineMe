package com.migraineme

import android.content.Context
import android.util.Log
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MetricsSyncManager {

    suspend fun onLogin(
        context: Context,
        token: String,
        snackbarHostState: SnackbarHostState
    ) {
        withContext(Dispatchers.IO) {
            try {
                val hasWhoop = try {
                    WhoopAuthService().refresh(context)
                    WhoopTokenStore(context).load() != null
                } catch (t: Throwable) {
                    false
                }

                if (!hasWhoop) {
                    snackbarHostState.showSnackbar(
                        message = "WhoopSyncFailed – Refresh Connection.",
                        duration = SnackbarDuration.Short
                    )
                }

                val whoopSleepEnabled =
                    DataCollectionSettings.isEnabledForWhoop(context, "sleep_duration_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(context, "sleep_score_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(context, "sleep_efficiency_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(context, "sleep_stages_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(context, "sleep_disturbances_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(context, "fell_asleep_time_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(context, "woke_up_time_daily")

                val whoopPhysicalEnabled =
                    DataCollectionSettings.isEnabledForWhoop(context, "recovery_score_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(context, "resting_hr_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(context, "hrv_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(context, "skin_temp_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(context, "spo2_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(context, "time_in_high_hr_zones_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(context, "steps_daily")

                if (hasWhoop && whoopSleepEnabled) {
                    WhoopDailySyncWorkerSleepFields.runOnceNow(context)
                    WhoopDailySyncWorkerSleepFields.scheduleNext(context)
                    WhoopDailySyncWorkerSleepFields.backfillUpToToday(context, token)
                }

                if (hasWhoop && whoopPhysicalEnabled) {
                    WhoopDailyPhysicalHealthWorker.runOnceNow(context)
                    WhoopDailyPhysicalHealthWorker.scheduleNext(context)
                    WhoopDailyPhysicalHealthWorker.backfillUpToToday(context, token)
                }

                val locationEnabled =
                    DataCollectionSettings.isActive(
                        context = context,
                        table = "user_location_daily",
                        wearable = null,
                        defaultValue = true
                    )

                if (locationEnabled) {
                    LocationDailySyncWorker.runOnceNow(context)
                    LocationDailySyncWorker.scheduleNext(context)
                    LocationDailySyncWorker.backfillUpToToday(context, token)
                }

            } catch (t: Throwable) {
                Log.w("MetricsSyncManager", "onLogin error: ${t.message}")
            }

            // Force the lambda to be Unit-returning (prevents "if as expression" edge cases)
            Unit
        }
    }
}
