package com.migraineme

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Helper for managing RECORD_AUDIO permission for ambient noise sampling.
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
     * Check if we should show rationale (user previously denied).
     * Note: This requires an Activity context, so we return false here.
     * Actual rationale handling should be in the Activity/Fragment.
     */
    fun shouldShowRationale(context: Context): Boolean {
        // Can't check shouldShowRequestPermissionRationale without Activity
        return false
    }
}
