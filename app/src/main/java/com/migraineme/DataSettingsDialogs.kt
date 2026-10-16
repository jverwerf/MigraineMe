package com.migraineme

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Dialog components for DataSettings screen.
 *
 * NOTE: ScreenTimePermissionDialog and BatteryOptimizationDialog already exist
 * elsewhere in the codebase, so we just use those directly.
 * 
 * This file contains ONLY dialogs that don't exist elsewhere.
 */

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
        title = { Text(t("Microphone Permission Required")) },
        text = {
            Text(
                t("To record ambient noise levels, MigraineMe needs microphone access.\n\n") +
                t("The app only records noise levels (decibels), not conversations or identifiable audio.")
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(t("Open Settings"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(t("Cancel"))
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
        title = { Text(t("Missing Dependencies")) },
        text = {
            Text(
                t("Stress Index requires both HRV and Resting Heart Rate to be enabled.\n\n") +
                t("Please enable these metrics first, then you can enable Stress Index.")
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(t("OK"))
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
                Text(t("OK"))
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Goal Reminder Times Dialog
// ─────────────────────────────────────────────────────────────────────────────

private const val MAX_GOAL_REMINDER_TIMES = 6

/**
 * The times one goal's reminders go out, edited in a draft list and saved in one go
 * (rpc set_goal_reminder_times). Saving none also switches the goal's reminders off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalReminderTimesDialog(
    goal: SupabasePractitionerService.GoalRow,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // "HH:mm:ss", sorted, no duplicates
    var times by remember(goal.id) { mutableStateOf(goal.reminder_times.distinct().sorted()) }
    // Index into times being changed, -1 for a new one, null when the picker is closed
    var pickerIndex by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = Color(0xFF1E0A2E),
        titleContentColor = Color.White,
        title = { Text(t(goal.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (times.isEmpty()) {
                    Text(t("No reminders"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
                times.forEachIndexed { i, time ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            time.take(5),
                            color = AppTheme.BodyTextColor,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = !saving) { pickerIndex = i }
                                .padding(vertical = 8.dp)
                        )
                        TextButton(onClick = { times = times - time }, enabled = !saving) {
                            Text("×", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                if (times.size < MAX_GOAL_REMINDER_TIMES) {
                    TextButton(onClick = { pickerIndex = -1 }, enabled = !saving) {
                        Text(t("Add time"), color = AppTheme.AccentPurple)
                    }
                }
                if (failed) {
                    Text(t("Could not save. Try again."), color = AppTheme.AccentPink, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    saving = true
                    failed = false
                    scope.launch {
                        val ok = PractitionerGoalsStore.setReminderTimes(context, goal.id, times)
                        saving = false
                        if (ok) onDismiss() else failed = true
                    }
                },
                enabled = !saving
            ) { Text(t("Save"), color = if (saving) AppTheme.SubtleTextColor else AppTheme.AccentPurple) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(t("Cancel"), color = AppTheme.SubtleTextColor)
            }
        }
    )

    pickerIndex?.let { index ->
        val start = times.getOrNull(index) ?: "09:00:00"
        val state = rememberTimePickerState(
            initialHour = start.take(2).toIntOrNull() ?: 9,
            initialMinute = start.drop(3).take(2).toIntOrNull() ?: 0,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { pickerIndex = null },
            containerColor = Color(0xFF1E0A2E),
            titleContentColor = Color.White,
            confirmButton = {
                TextButton(onClick = {
                    val picked = "%02d:%02d:00".format(state.hour, state.minute)
                    val rest = if (index >= 0) times.filterIndexed { i, _ -> i != index } else times
                    times = (rest + picked).distinct().sorted()
                    pickerIndex = null
                }) { Text(t("Done"), color = AppTheme.AccentPurple) }
            },
            dismissButton = {
                TextButton(onClick = { pickerIndex = null }) {
                    Text(t("Cancel"), color = AppTheme.SubtleTextColor)
                }
            },
            title = { Text(t("Select time")) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TimePicker(state = state, colors = appTimePickerColors())
                }
            }
        )
    }
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
