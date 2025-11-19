// FILE: app/src/main/java/com/migraineme/MetricsSyncManager.kt
package com.migraineme

import android.content.Context
import android.util.Log
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Central coordinator called after a successful login.
 *
 * Responsibilities:
 * - Refresh WHOOP token.
 * - If WHOOP connected:
 *      - Run today's WHOOP sleep sync once.
 *      - Schedule the daily 09:00 WHOOP sleep worker.
 *      - Run backfill via WhoopDailySyncWorkerSleepFields.backfillUpToToday.
 * - If WHOOP NOT connected:
 *      - Show a Snackbar in LoginScreen: "WhoopSyncFailed – Refresh Connection."
 * - Always:
 *      - Run today's Location sync once.
 *      - Schedule the daily 09:00 Location worker.
 *
 * No Supabase/auth wiring changed. Uses existing worker APIs only.
 */
object MetricsSyncManager {

    suspend fun onLogin(
        context: Context,
        token: String,
        snackbarHostState: SnackbarHostState
    ) = withContext(Dispatchers.IO) {
        try {
            // 1. Refresh WHOOP token and check if we are connected
            val hasWhoop = try {
                WhoopAuthService().refresh(context)
                WhoopTokenStore(context).load() != null
            } catch (t: Throwable) {
                Log.w("MetricsSyncManager", "WHOOP refresh failed: ${t.message}")
                false
            }

            if (hasWhoop) {
                // 2a. WHOOP is connected → run today's sync + schedule + backfill
                try {
                    WhoopDailySyncWorkerSleepFields.runOnceNow(context)
                    WhoopDailySyncWorkerSleepFields.scheduleNext(context)
                    WhoopDailySyncWorkerSleepFields.backfillUpToToday(context, token)
                } catch (t: Throwable) {
                    Log.w("MetricsSyncManager", "WHOOP sync error: ${t.message}")
                }
            } else {
                // 2b. WHOOP not connected → inform user in LoginScreen
                snackbarHostState.showSnackbar(
                    message = "WhoopSyncFailed – Refresh Connection.",
                    duration = SnackbarDuration.Short
                )
            }

            // 3. Location: always run today + schedule 09:00
            try {
                LocationDailySyncWorker.runOnceNow(context)
                LocationDailySyncWorker.scheduleNext(context)
            } catch (t: Throwable) {
                Log.w("MetricsSyncManager", "Location sync error: ${t.message}")
            }
        } catch (t: Throwable) {
            Log.w("MetricsSyncManager", "onLogin error: ${t.message}")
        }
    }
}
