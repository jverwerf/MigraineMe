package com.migraineme

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Helper for managing battery optimization exemption.
 *
 * Battery optimization can kill background workers, causing ambient noise
 * sampling to stop. This helper requests exemption to keep workers running.
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptimization"

    /**
     * Check if app is exempt from battery optimization.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true // Not applicable on older versions
        }

        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = context.packageName
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking battery optimization: ${e.message}", e)
            false
        }
    }

    /**
     * Open settings to request battery optimization exemption.
     * User must manually grant this - we can't do it programmatically.
     */
    fun requestBatteryOptimizationExemption(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return // Not applicable
        }

        try {
            if (!isIgnoringBatteryOptimizations(context)) {
                Log.d(TAG, "Requesting battery optimization exemption")

                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                context.startActivity(intent)
            } else {
                Log.d(TAG, "Already exempt from battery optimization")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting battery optimization exemption: ${e.message}", e)

            // Fallback: Open battery settings page
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Error opening battery settings: ${e2.message}", e2)
            }
        }
    }

    /**
     * Check if we should show battery optimization dialog.
     * Returns true ONLY if:
     * - App is battery optimized (not exempt)
     * - We have NEVER asked before
     *
     * Once granted (or user dismisses), we never ask again.
     */
    fun shouldRequestBatteryOptimization(context: Context): Boolean {
        if (isIgnoringBatteryOptimizations(context)) {
            return false // Already granted - never ask again
        }

        // Check if we've EVER asked before
        val prefs = context.getSharedPreferences("battery_opt", Context.MODE_PRIVATE)
        val hasAskedBefore = prefs.getBoolean("has_asked_before", false)

        return !hasAskedBefore // Only ask if we've never asked
    }

    /**
     * Mark that we asked for battery optimization exemption.
     * After this, we NEVER ask again (even if user didn't grant).
     */
    fun markAsAsked(context: Context) {
        val prefs = context.getSharedPreferences("battery_opt", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("has_asked_before", true)
            .apply()
    }
}
