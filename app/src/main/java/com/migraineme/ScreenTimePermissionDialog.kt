package com.migraineme

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Dialog to explain Screen Time / Usage Access permission and direct user to settings.
 *
 * Intended for features that rely on Android Usage Stats (screen time/app usage).
 */
@Composable
fun ScreenTimePermissionDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Screen Time Access")) },
        text = {
            Text(
                t("To show screen time insights, MigraineMe needs Usage Access permission.\n\n") +
                        t("This lets Android share app usage totals with MigraineMe. ") +
                        t("MigraineMe does not read your content.\n\n") +
                        t("You'll be taken to Settings where you can grant this permission.")
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(t("Open Settings"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(t("Not Now"))
            }
        }
    )
}
