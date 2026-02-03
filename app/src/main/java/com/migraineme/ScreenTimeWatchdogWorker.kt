package com.migraineme

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Watchdog worker that periodically checks if screen time sync worker is scheduled,
 * and restarts it if needed.
 * 
 * Uses PeriodicWorkRequest which SURVIVES phone death/reboot automatically.
 * Runs every 6 hours (daily worker, so less frequent checks are fine).
 */
class ScreenTimeWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Watchdog: Checking screen time sync worker status")
        
        try {
            // Check Supabase metric_settings for screen time enabled
            val enabled = try {
                val edge = EdgeFunctionsService()
                val settings = edge.getMetricSettings(applicationContext)
                settings.find { it.metric == "screen_time_daily" }?.enabled ?: false
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check Supabase settings: ${e.message}")
                false
            }
            
            if (!enabled) {
                Log.d(TAG, "Watchdog: Screen time disabled in Supabase - skipping check")
                return@withContext Result.success()
            }
            
            // Check permission
            if (!ScreenTimePermissionHelper.hasPermission(applicationContext)) {
                Log.d(TAG, "Watchdog: Screen time permission not granted - skipping")
                return@withContext Result.success()
            }
            
            // Check if the worker is actually scheduled
            val workManager = WorkManager.getInstance(applicationContext)
            val workInfos = workManager
                .getWorkInfosForUniqueWork("screen_time_daily_worker")
                .get()
            
            val isScheduled = workInfos.isNotEmpty() && 
                              workInfos.any { 
                                  it.state == WorkInfo.State.ENQUEUED || 
                                  it.state == WorkInfo.State.RUNNING 
                              }
            
            if (!isScheduled) {
                Log.w(TAG, "⚠️ Watchdog: Screen time worker is NOT scheduled! Restarting...")
                ScreenTimeDailySyncWorker.scheduleNext(applicationContext)
                Log.d(TAG, "✅ Watchdog: Screen time worker restarted successfully")
            } else {
                val state = workInfos.first().state
                Log.d(TAG, "✅ Watchdog: Screen time worker is scheduled (state: $state)")
            }
            
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Watchdog error: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ScreenTimeWatchdog"
        private const val UNIQUE_NAME = "screen_time_watchdog"
        
        /**
         * Schedule the watchdog as PERIODIC work.
         * Runs every 6 hours (less frequent than ambient noise since this is daily sync).
         */
        fun schedule(context: Context) {
            try {
                Log.d(TAG, "Scheduling watchdog worker (periodic, every 6 hours)")
                
                val request = PeriodicWorkRequestBuilder<ScreenTimeWatchdogWorker>(
                    repeatInterval = 6,
                    repeatIntervalTimeUnit = TimeUnit.HOURS
                ).build()
                
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
                
                Log.d(TAG, "✅ Watchdog scheduled successfully (survives phone death)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error scheduling watchdog: ${e.message}", e)
            }
        }
        
        fun cancel(context: Context) {
            try {
                Log.d(TAG, "Cancelling watchdog worker")
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling watchdog: ${e.message}", e)
            }
        }
    }
}
