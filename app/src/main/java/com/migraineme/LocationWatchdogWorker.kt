package com.migraineme

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Watchdog worker that periodically checks if location sync worker is scheduled,
 * and schedules it if needed.
 * 
 * This is the SAFETY NET for location scheduling:
 * - Login schedules the 9AM worker
 * - Toggle ON schedules the 9AM worker
 * - If something goes wrong, watchdog fixes it
 * 
 * Uses PeriodicWorkRequest which SURVIVES phone death/reboot automatically.
 * Runs every 1 hour.
 */
class LocationWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Watchdog: Checking location sync worker status")
        
        try {
            // Check Supabase metric_settings for location enabled
            val enabled = try {
                val edge = EdgeFunctionsService()
                val settings = edge.getMetricSettings(applicationContext)
                settings.find { it.metric == "user_location_daily" }?.enabled ?: false
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check Supabase settings: ${e.message}")
                // If we can't reach Supabase, assume enabled and let worker check
                true
            }
            
            if (!enabled) {
                Log.d(TAG, "Watchdog: Location sync disabled in Supabase - skipping check")
                return@withContext Result.success()
            }
            
            // Check if the 9AM worker is scheduled
            val workManager = WorkManager.getInstance(applicationContext)
            val workInfos = workManager
                .getWorkInfosForUniqueWork("location_daily_worker")
                .get()
            
            val isScheduled = workInfos.isNotEmpty() && 
                              workInfos.any { 
                                  it.state == WorkInfo.State.ENQUEUED || 
                                  it.state == WorkInfo.State.RUNNING 
                              }
            
            if (!isScheduled) {
                Log.w(TAG, "⚠️ Watchdog: Location sync worker is NOT scheduled! Scheduling now...")
                LocationDailySyncWorker.scheduleNext(applicationContext)
                Log.d(TAG, "✅ Watchdog: Location worker scheduled for next 9AM")
            } else {
                val state = workInfos.firstOrNull { 
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING 
                }?.state
                Log.d(TAG, "✅ Watchdog: Location worker is scheduled (state: $state)")
            }
            
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Watchdog error: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "LocationWatchdog"
        private const val UNIQUE_NAME = "location_watchdog"
        private const val UNIQUE_NAME_ONCE = "location_watchdog_once"
        
        /**
         * Schedule the watchdog as PERIODIC work.
         * Runs every 1 hour.
         */
        fun schedule(context: Context) {
            try {
                Log.d(TAG, "Scheduling watchdog worker (periodic, every 1 hour)")
                
                val request = PeriodicWorkRequestBuilder<LocationWatchdogWorker>(
                    repeatInterval = 1,
                    repeatIntervalTimeUnit = TimeUnit.HOURS
                ).build()
                
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
                
                Log.d(TAG, "✅ Watchdog scheduled successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error scheduling watchdog: ${e.message}", e)
            }
        }
        
        /**
         * Run watchdog check once immediately (e.g., on app resume).
         */
        fun runOnce(context: Context) {
            try {
                Log.d(TAG, "Running watchdog once (immediate)")
                
                val request = OneTimeWorkRequestBuilder<LocationWatchdogWorker>()
                    .build()
                
                WorkManager.getInstance(context).enqueue(request)
                
                Log.d(TAG, "✅ Watchdog one-time check enqueued")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error running watchdog once: ${e.message}", e)
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
