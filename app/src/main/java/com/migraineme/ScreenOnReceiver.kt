// FILE: ScreenOnReceiver.kt
package com.migraineme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Triggers ambient noise sampling when user unlocks the phone,
 * but only if last successful (non-zero) sample was >30 min ago.
 * 
 * Register in AndroidManifest.xml:
 * <receiver android:name=".ScreenOnReceiver" android:exported="false">
 *     <intent-filter>
 *         <action android:name="android.intent.action.USER_PRESENT" />
 *     </intent-filter>
 * </receiver>
 */
class ScreenOnReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenOnReceiver"
        private const val MIN_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_PRESENT) return

        Log.d(TAG, "User unlocked phone")

        // Check if noise sampling is enabled
        if (!AmbientNoisePrefs.isEnabled(context)) {
            Log.d(TAG, "Noise sampling not enabled — skip")
            return
        }

        // Check last successful sample time
        val lastSampleTime = AmbientNoisePrefs.getLastSuccessfulSampleTime(context)
        val now = System.currentTimeMillis()
        val elapsed = now - lastSampleTime

        if (elapsed < MIN_INTERVAL_MS) {
            Log.d(TAG, "Last sample was ${elapsed / 1000 / 60}min ago — skip (need 30min)")
            return
        }

        Log.d(TAG, "Last sample was ${elapsed / 1000 / 60}min ago — triggering new sample")
        AmbientNoiseSampleWorker.scheduleImmediate(context)
    }
}
