package com.migraineme

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Dialog to explain battery optimization and request exemption.
 * 
 * Shows when user enables ambient noise and app is battery optimized.
 */
@Composable
fun BatteryOptimizationDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Battery Optimization") },
        text = {
            Text(
                "To ensure ambient noise sampling works reliably (even after phone restarts), " +
                "MigraineMe needs to be exempt from battery optimization.\n\n" +
                "This allows the app to run background tasks without being killed by Android.\n\n" +
                "You'll be taken to Settings where you can grant this permission."
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
