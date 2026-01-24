package com.migraineme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Reschedules background workers after device boot, app update, or process death.
 *
 * This ensures that ambient noise sampling and other workers continue running
 * even after the phone restarts or the app is killed.
 *
 * Registered in AndroidManifest.xml to receive:
 * - BOOT_COMPLETED (after device fully boots)
 * - LOCKED_BOOT_COMPLETED (direct boot mode on Android 7+)
 * - MY_PACKAGE_REPLACED (after app update)
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

        // Reschedule ambient noise sampling if enabled
        rescheduleAmbientNoiseSampling(appContext)

        // Add other workers here as needed in the future

        Log.d(TAG, "Worker rescheduling complete")
    }

    private fun rescheduleAmbientNoiseSampling(context: Context) {
        try {
            // Check if ambient noise sampling is enabled
            val enabled = DataCollectionSettings.isActive(
                context = context,
                table = "ambient_noise_samples",
                wearable = null,
                defaultValue = true
            )

            if (enabled) {
                Log.d(TAG, "Ambient noise sampling is enabled - rescheduling worker")
                AmbientNoiseSampleWorker.schedule(context)
            } else {
                Log.d(TAG, "Ambient noise sampling is disabled - ensuring worker is cancelled")
                AmbientNoiseSampleWorker.cancel(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rescheduling ambient noise sampling: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"

        /**
         * Call this method to manually trigger worker rescheduling.
         * Useful for testing or when you need to ensure workers are scheduled
         * outside of boot events.
         */
        fun rescheduleAllWorkers(context: Context) {
            val receiver = BootReceiver()
            receiver.rescheduleWorkersIfNeeded(context)
        }
    }
}