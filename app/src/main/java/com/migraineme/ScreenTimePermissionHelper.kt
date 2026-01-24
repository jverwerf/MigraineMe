package com.migraineme

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Helper for managing PACKAGE_USAGE_STATS permission requests.
 * 
 * Unlike normal runtime permissions, PACKAGE_USAGE_STATS cannot be requested via
 * ActivityResultContracts - the user must grant it in Settings.
 * 
 * This helper:
 * 1. Checks if permission is granted
 * 2. Opens Settings to the Usage Access page if needed
 * 3. Tracks whether we've requested permission (to avoid annoying users repeatedly)
 */
object ScreenTimePermissionHelper {
    
    private const val PREFS = "screen_time_permission"
    private const val KEY_REQUESTED = "requested_once"
    
    /**
     * Check if PACKAGE_USAGE_STATS permission is granted.
     */
    fun hasPermission(context: Context): Boolean {
        return ScreenTimeCollector.hasUsageStatsPermission(context)
    }
    
    /**
     * Check if we've already requested permission once (to avoid annoying users).
     */
    fun hasRequestedBefore(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_REQUESTED, false)
    }
    
    /**
     * Mark that we've requested permission (so we don't keep asking).
     */
    fun markAsRequested(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REQUESTED, true)
            .apply()
    }
    
    /**
     * Open Android Settings to the Usage Access page where user can grant permission.
     * This is the only way to request PACKAGE_USAGE_STATS permission.
     */
    fun openUsageAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
        markAsRequested(context)
    }
    
    /**
     * Should we request permission now?
     * Returns true if:
     * - Permission is not granted AND
     * - We haven't requested before (or force is true)
     */
    fun shouldRequestPermission(context: Context, force: Boolean = false): Boolean {
        if (hasPermission(context)) return false
        if (force) return true
        return !hasRequestedBefore(context)
    }
}
