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
 * NOTE: Location scheduling is now handled by FCM push notifications from the server.
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

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val edge = EdgeFunctionsService()
                val settings = edge.getMetricSettings(appContext)
                val settingsMap = settings.associateBy { it.metric }

                rescheduleAmbientNoiseSampling(appContext, settingsMap)
                rescheduleScreenTimeSync(appContext, settingsMap)
                reschedulePhoneSleepSync(appContext, settingsMap)
                // Location is handled by FCM - just run once to catch up
                runLocationSyncIfEnabled(appContext, settingsMap)

                Log.d(TAG, "Worker rescheduling complete")
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching settings for rescheduling: ${e.message}", e)
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
                Log.d(TAG, "Ambient noise sampling is disabled - cancelling")
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
            } else {
                Log.d(TAG, "Screen time sync is disabled - cancelling")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rescheduling screen time sync: ${e.message}", e)
        }
    }

    /**
     * Run phone sleep sync once on boot if enabled.
     * Checks if sleep_duration_daily is enabled with phone as source.
     */
    private fun reschedulePhoneSleepSync(
        context: Context,
        settings: Map<String, EdgeFunctionsService.MetricSettingResponse>
    ) {
        try {
            val sleepSetting = settings["sleep_duration_daily"]
            val enabled = sleepSetting?.enabled ?: false
            val hasPerm = ScreenTimeCollector.hasUsageStatsPermission(context)

            val preferred = sleepSetting?.preferredSource?.lowercase() ?: ""
            val allowed = sleepSetting?.allowedSources?.map { it.lowercase() } ?: emptyList()
            val isPhoneSource = preferred == "phone" || allowed.contains("phone")

            if (enabled && hasPerm && isPhoneSource) {
                Log.d(TAG, "Phone sleep sync is enabled - running once to catch up")
                PhoneSleepSyncWorker.runOnce(context)
            } else {
                Log.d(TAG, "Phone sleep sync is disabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rescheduling phone sleep sync: ${e.message}", e)
        }
    }

    /**
     * Run location sync once on boot if enabled (FCM handles regular scheduling)
     */
    private fun runLocationSyncIfEnabled(
        context: Context,
        settings: Map<String, EdgeFunctionsService.MetricSettingResponse>
    ) {
        try {
            val enabled = settings["user_location_daily"]?.enabled ?: false

            if (enabled) {
                Log.d(TAG, "Location sync is enabled - running once to catch up")
                LocationDailySyncWorker.runOnceNow(context)
            } else {
                Log.d(TAG, "Location sync is disabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error running location sync: ${e.message}", e)
        }
    }

    private fun rescheduleAllFallback(context: Context) {
        Log.d(TAG, "Fallback: Scheduling workers that have permissions")

        if (MicrophonePermissionHelper.hasPermission(context) && isBatteryOptimizationExempt(context)) {
            AmbientNoiseSampleWorker.schedule(context)
            AmbientNoiseWatchdogWorker.schedule(context)
        }

        if (ScreenTimePermissionHelper.hasPermission(context)) {
        }

        // Phone sleep - run once on fallback if usage stats permission exists
        if (ScreenTimeCollector.hasUsageStatsPermission(context)) {
            PhoneSleepSyncWorker.runOnce(context)
        }

        // Location - run once, FCM handles scheduling
        LocationDailySyncWorker.runOnceNow(context)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}