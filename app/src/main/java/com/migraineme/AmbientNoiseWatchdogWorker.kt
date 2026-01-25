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
 * Watchdog worker that periodically checks if ambient noise worker is scheduled,
 * and restarts it if needed.
 * 
 * Uses PeriodicWorkRequest which SURVIVES phone death/reboot automatically.
 * Android minimum is 15 minutes, so we use that.
 * 
 * This ensures ambient noise continues working even if:
 * - Battery optimization kills it
 * - Worker fails and doesn't reschedule
 * - Phone dies and BootReceiver fails
 */
class AmbientNoiseWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Watchdog: Checking ambient noise worker status")
        
        try {
            // Check if ambient noise is enabled in settings
            val enabled = DataCollectionSettings.isActive(
                context = applicationContext,
                table = "ambient_noise_samples",
                wearable = null,
                defaultValue = true
            )
            
            if (!enabled) {
                Log.d(TAG, "Watchdog: Ambient noise disabled - skipping check (watchdog stays alive)")
                return@withContext Result.success()
            }
            
            // Check if the worker is actually scheduled
            val workManager = WorkManager.getInstance(applicationContext)
            val workInfos = workManager
                .getWorkInfosForUniqueWork("ambient_noise_samples_loop")
                .get()
            
            val isScheduled = workInfos.isNotEmpty() && 
                              workInfos.any { 
                                  it.state == WorkInfo.State.ENQUEUED || 
                                  it.state == WorkInfo.State.RUNNING 
                              }
            
            if (!isScheduled) {
                Log.w(TAG, "⚠️ Watchdog: Ambient noise worker is NOT scheduled! Restarting...")
                AmbientNoiseSampleWorker.schedule(applicationContext)
                Log.d(TAG, "✅ Watchdog: Worker restarted successfully")
            } else {
                val state = workInfos.first().state
                Log.d(TAG, "✅ Watchdog: Worker is scheduled (state: $state)")
            }
            
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Watchdog error: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "NoiseWatchdog"
        private const val UNIQUE_NAME = "ambient_noise_watchdog"
        
        /**
         * Schedule the watchdog as PERIODIC work.
         * This survives phone death automatically - Android reschedules it after reboot.
         * 
         * Minimum interval is 15 minutes (Android restriction).
         */
        fun schedule(context: Context) {
            try {
                Log.d(TAG, "Scheduling watchdog worker (periodic, every 15 minutes)")
                
                val request = PeriodicWorkRequestBuilder<AmbientNoiseWatchdogWorker>(
                    repeatInterval = 15,
                    repeatIntervalTimeUnit = TimeUnit.MINUTES
                ).build()
                
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP, // Keep existing, don't restart
                    request
                )
                
                Log.d(TAG, "✅ Watchdog scheduled successfully (survives phone death)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error scheduling watchdog: ${e.message}", e)
            }
        }
        
        /**
         * Cancel the watchdog worker.
         */
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
