package com.migraineme

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Helper object for background location permission handling.
 * 
 * On Android 10+ (API 29+), background location requires:
 * 1. First grant ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION
 * 2. Then separately grant ACCESS_BACKGROUND_LOCATION
 * 
 * The background permission CANNOT be requested via the normal permission dialog.
 * User must go to Settings and select "Allow all the time".
 */
object BackgroundLocationPermissionHelper {
    
    /**
     * Check if foreground location permission is granted
     */
    fun hasForegroundLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }
    
    /**
     * Check if background location permission is granted
     */
    fun hasBackgroundLocationPermission(context: Context): Boolean {
        // Background location permission only exists on Android 10+
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // On Android 9 and below, foreground permission is sufficient
            hasForegroundLocationPermission(context)
        }
    }
    
    /**
     * Check if we have both foreground and background location permissions
     */
    fun hasFullLocationPermission(context: Context): Boolean {
        return hasForegroundLocationPermission(context) && hasBackgroundLocationPermission(context)
    }
    
    /**
     * Open the app's settings page where user can change location permission to "Allow all the time"
     */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
    
    /**
     * Check if we need to show the background location dialog
     * (foreground granted but background not granted)
     */
    fun needsBackgroundPermissionPrompt(context: Context): Boolean {
        return hasForegroundLocationPermission(context) && !hasBackgroundLocationPermission(context)
    }
}

/**
 * Dialog explaining why background location is needed and directing user to Settings
 */
@Composable
fun BackgroundLocationPermissionDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Background Location Required") },
        text = {
            Text(
                "To track your location for weather data while the app is closed, " +
                "please select \"Allow all the time\" in Settings.\n\n" +
                "This allows the app to update your location hourly for accurate weather-based migraine insights."
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text("Open Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not Now")
            }
        }
    )
}
