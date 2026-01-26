package com.migraineme

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Helper for managing RECORD_AUDIO and POST_NOTIFICATIONS permissions for ambient noise sampling.
 *
 * Android 13+ requires POST_NOTIFICATIONS to show the foreground notification needed for microphone access.
 */
object MicrophonePermissionHelper {

    /**
     * Check if RECORD_AUDIO permission is granted.
     */
    fun hasPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if POST_NOTIFICATIONS permission is granted (Android 13+).
     * Required to show foreground notification for microphone access.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        // Only required on Android 13+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }

        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if BOTH microphone AND notification permissions are granted.
     * Both are required for ambient noise sampling to work in background.
     */
    fun hasAllPermissions(context: Context): Boolean {
        return hasPermission(context) && hasNotificationPermission(context)
    }

    /**
     * Get the list of permissions that need to be requested.
     */
    fun getRequiredPermissions(): List<String> {
        return buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}