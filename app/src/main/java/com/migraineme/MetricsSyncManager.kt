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
    ) = withContext(Dispatchers.IO) {
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

            if (hasWhoop) {
                WhoopDailySyncWorkerSleepFields.runOnceNow(context)
                WhoopDailySyncWorkerSleepFields.scheduleNext(context)
                WhoopDailySyncWorkerSleepFields.backfillUpToToday(context, token)

                WhoopDailyPhysicalHealthWorker.runOnceNow(context)
                WhoopDailyPhysicalHealthWorker.scheduleNext(context)
                WhoopDailyPhysicalHealthWorker.backfillUpToToday(context, token)
            }

            // LOCATION — same 3-step WHOOP pattern
            LocationDailySyncWorker.runOnceNow(context)
            LocationDailySyncWorker.scheduleNext(context)
            LocationDailySyncWorker.backfillUpToToday(context, token)

        } catch (t: Throwable) {
            Log.w("MetricsSyncManager", "onLogin error: ${t.message}")
        }
    }
}
