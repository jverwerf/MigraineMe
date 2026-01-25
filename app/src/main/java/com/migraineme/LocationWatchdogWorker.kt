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
 * Watchdog worker that periodically checks if location sync worker is scheduled,
 * and restarts it if needed.
 * 
 * Uses PeriodicWorkRequest which SURVIVES phone death/reboot automatically.
 * Runs every 6 hours (daily worker, so less frequent checks are fine).
 */
class LocationWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Watchdog: Checking location sync worker status")
        
        try {
            // Check if location sync is enabled in settings
            val enabled = DataCollectionSettings.isActive(
                context = applicationContext,
                table = "user_location_daily",
                wearable = null,
                defaultValue = true
            )
            
            if (!enabled) {
                Log.d(TAG, "Watchdog: Location sync disabled - skipping check")
                return@withContext Result.success()
            }
            
            // Check if the worker is actually scheduled
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
                Log.w(TAG, "⚠️ Watchdog: Location sync worker is NOT scheduled! Restarting...")
                LocationDailySyncWorker.scheduleNext(applicationContext)
                Log.d(TAG, "✅ Watchdog: Location worker restarted successfully")
            } else {
                val state = workInfos.first().state
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
        
        /**
         * Schedule the watchdog as PERIODIC work.
         * Runs every 6 hours (less frequent than ambient noise since this is daily sync).
         */
        fun schedule(context: Context) {
            try {
                Log.d(TAG, "Scheduling watchdog worker (periodic, every 6 hours)")
                
                val request = PeriodicWorkRequestBuilder<LocationWatchdogWorker>(
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
