package com.migraineme

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

/**
 * Dialog components for DataSettings screen.
 *
 * NOTE: ScreenTimePermissionDialog and BatteryOptimizationDialog already exist
 * elsewhere in the codebase, so we just use those directly.
 * 
 * This file contains ONLY dialogs that don't exist elsewhere.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Background Location Dialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun BackgroundLocationDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Background Location Required") },
        text = {
            Text(
                "You'll be taken to the app permissions page where you'll see all permissions (Location, Health Connect, etc.).\n\n" +
                "To enable background location:\n" +
                "1. Tap \"Permissions\"\n" +
                "2. Tap \"Location\"\n" +
                "3. Select \"Allow all the time\"\n\n" +
                "This allows hourly location updates for accurate weather-based migraine insights."
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text("Open Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Microphone Permission Dialog (if not already defined elsewhere)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MicrophonePermissionDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Microphone Permission Required") },
        text = {
            Text(
                "To record ambient noise levels, MigraineMe needs microphone access.\n\n" +
                "The app only records noise levels (decibels), not conversations or identifiable audio."
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text("Open Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Stress Index Dependency Dialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun StressDependencyDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Missing Dependencies") },
        text = {
            Text(
                "Stress Index requires both HRV and Resting Heart Rate to be enabled.\n\n" +
                "Please enable these metrics first, then you can enable Stress Index."
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Generic Error Dialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun DataSettingsErrorDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Dialog Actions Helper
// ─────────────────────────────────────────────────────────────────────────────

object DataSettingsDialogActions {

    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openUsageAccessSettings(context: Context) {
        ScreenTimePermissionHelper.openUsageAccessSettings(context)
    }

    fun requestBatteryOptimizationExemption(context: Context) {
        BatteryOptimizationHelper.requestBatteryOptimizationExemption(context)
    }
}
