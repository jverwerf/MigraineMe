package com.migraineme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restarts workers after device boot/reboot or app update.
 *
 * This ensures data collection continues even after:
 * - Phone restart
 * - Phone dies and reboots
 * - App is updated
 * 
 * NOTE: Location scheduling is handled by LocationWatchdogWorker, not here.
 * This avoids duplicate scheduling issues.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "Received broadcast: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                rescheduleWorkersIfNeeded(context)
            }
        }
    }

    private fun rescheduleWorkersIfNeeded(context: Context) {
        val appContext = context.applicationContext

        // Use coroutine since we need to call Supabase
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val edge = EdgeFunctionsService()
                val settings = edge.getMetricSettings(appContext)
                val settingsMap = settings.associateBy { it.metric }

                rescheduleAmbientNoiseSampling(appContext, settingsMap)
                rescheduleScreenTimeSync(appContext, settingsMap)
                // Location is handled by LocationWatchdogWorker - just schedule the watchdog
                scheduleLocationWatchdog(appContext, settingsMap)

                Log.d(TAG, "Worker rescheduling complete")
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching settings for rescheduling: ${e.message}", e)
                // Fallback: schedule workers if we can't reach Supabase (rely on worker to check)
                rescheduleAllFallback(appContext)
            }
        }
    }

    private fun rescheduleAmbientNoiseSampling(
        context: Context,
        settings: Map<String, EdgeFunctionsService.MetricSettingResponse>
    ) {
        try {
            val enabled = settings["ambient_noise_samples"]?.enabled ?: false
            val hasMic = MicrophonePermissionHelper.hasPermission(context)
            val hasBattery = isBatteryOptimizationExempt(context)

            if (enabled && hasMic && hasBattery) {
                Log.d(TAG, "Ambient noise sampling is enabled - rescheduling worker")
                AmbientNoiseSampleWorker.schedule(context)
                AmbientNoiseWatchdogWorker.schedule(context)
            } else {
                Log.d(TAG, "Ambient noise sampling is disabled - cancelling (enabled=$enabled, mic=$hasMic, battery=$hasBattery)")
                AmbientNoiseSampleWorker.cancel(context)
                AmbientNoiseWatchdogWorker.cancel(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rescheduling ambient noise sampling: ${e.message}", e)
        }
    }

    private fun rescheduleScreenTimeSync(
        context: Context,
        settings: Map<String, EdgeFunctionsService.MetricSettingResponse>
    ) {
        try {
            val enabled = settings["screen_time_daily"]?.enabled ?: false
            val hasPerm = ScreenTimePermissionHelper.hasPermission(context)

            if (enabled && hasPerm) {
                Log.d(TAG, "Screen time sync is enabled - rescheduling worker")
                ScreenTimeDailySyncWorker.scheduleNext(context)
                ScreenTimeWatchdogWorker.schedule(context)
            } else {
                Log.d(TAG, "Screen time sync is disabled - cancelling (enabled=$enabled, perm=$hasPerm)")
                ScreenTimeDailySyncWorker.cancel(context)
                ScreenTimeWatchdogWorker.cancel(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rescheduling screen time sync: ${e.message}", e)
        }
    }

    /**
     * Only schedule the watchdog - it will handle checking/scheduling the actual worker.
     */
    private fun scheduleLocationWatchdog(
        context: Context,
        settings: Map<String, EdgeFunctionsService.MetricSettingResponse>
    ) {
        try {
            val enabled = settings["user_location_daily"]?.enabled ?: false
            
            if (enabled) {
                Log.d(TAG, "Location sync is enabled - scheduling watchdog only")
                LocationWatchdogWorker.schedule(context)
            } else {
                Log.d(TAG, "Location sync is disabled - cancelling all location workers")
                LocationDailySyncWorker.cancelAll(context)
                LocationWatchdogWorker.cancel(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling location watchdog: ${e.message}", e)
        }
    }

    private fun rescheduleAllFallback(context: Context) {
        Log.d(TAG, "Fallback: Scheduling workers that have permissions (couldn't reach Supabase)")
        
        // Ambient noise
        if (MicrophonePermissionHelper.hasPermission(context) && isBatteryOptimizationExempt(context)) {
            AmbientNoiseSampleWorker.schedule(context)
            AmbientNoiseWatchdogWorker.schedule(context)
        }
        
        // Screen time
        if (ScreenTimePermissionHelper.hasPermission(context)) {
            ScreenTimeDailySyncWorker.scheduleNext(context)
            ScreenTimeWatchdogWorker.schedule(context)
        }
        
        // Location - only schedule watchdog, it will handle the rest
        LocationWatchdogWorker.schedule(context)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
