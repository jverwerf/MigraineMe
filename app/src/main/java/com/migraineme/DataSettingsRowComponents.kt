package com.migraineme

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Row components for DataSettings screen.
 *
 * Each row type is a separate composable for easier maintenance.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Wearable Selector
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun WearableSourceSelector(
    options: List<WearableSource>,
    selected: WearableSource,
    enabled: Boolean,
    onSelected: (WearableSource) -> Unit
) {
    var expanded by remember(options, selected) { mutableStateOf(false) }

    Box {
        TextButton(
            onClick = { if (enabled && options.size > 1) expanded = true },
            enabled = enabled
        ) {
            Text(selected.label, color = Color.White, style = MaterialTheme.typography.bodySmall)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF1E0A2E))
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.label, color = Color.White) },
                    onClick = {
                        expanded = false
                        onSelected(opt)
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Phone Source Selector
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PhoneSourceSelector(
    options: List<PhoneSource>,
    selected: PhoneSource,
    enabled: Boolean,
    onSelected: (PhoneSource) -> Unit
) {
    var expanded by remember(options, selected) { mutableStateOf(false) }

    Box {
        TextButton(
            onClick = { if (enabled && options.size > 1) expanded = true },
            enabled = enabled
        ) {
            Text(selected.label, color = Color.White, style = MaterialTheme.typography.bodySmall)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF1E0A2E))
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.label, color = Color.White) },
                    onClick = {
                        expanded = false
                        onSelected(opt)
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Hybrid Source Selector (Phone + Wearable options)
// Used by PHONE_OR_WEARABLE rows (e.g. sleep duration, fell asleep, woke up)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun HybridSourceSelector(
    options: List<Pair<String, String>>,
    selectedKey: String,
    enabled: Boolean,
    onSelected: (String) -> Unit
) {
    var expanded by remember(options, selectedKey) { mutableStateOf(false) }

    val selectedLabel = options.firstOrNull { it.first == selectedKey }?.second ?: "Phone"

    Box {
        TextButton(
            onClick = { if (enabled && options.size > 1) expanded = true },
            enabled = enabled
        ) {
            Text(selectedLabel, color = Color.White, style = MaterialTheme.typography.bodySmall)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF1E0A2E))
        ) {
            options.forEach { (key: String, label: String) ->
                DropdownMenuItem(
                    text = { Text(label, color = Color.White) },
                    onClick = {
                        expanded = false
                        onSelected(key)
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Permission Sub-Row (for ambient noise, location, screen time)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PermissionSubRow(
    label: String,
    isGranted: Boolean,
    alpha: Float,
    providerColWidth: androidx.compose.ui.unit.Dp,
    toggleColWidth: androidx.compose.ui.unit.Dp,
    onRequestPermission: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp)
        ) {
            Text(
                label,
                color = AppTheme.BodyTextColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.alpha(0.75f)
            )
        }

        Column(
            modifier = Modifier
                .width(providerColWidth)
                .padding(end = 10.dp)
        ) {
            // Empty spacer column for alignment
        }

        Column(
            modifier = Modifier.width(toggleColWidth),
            horizontalAlignment = Alignment.End
        ) {
            Switch(
                checked = isGranted,
                onCheckedChange = { newVal ->
                    if (newVal && !isGranted) {
                        onRequestPermission()
                    }
                },
                enabled = !isGranted
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Ambient Noise Permission Rows
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AmbientNoisePermissionRows(
    alpha: Float,
    micPermissionGranted: Boolean,
    batteryOptimizationExempt: Boolean,
    providerColWidth: androidx.compose.ui.unit.Dp,
    toggleColWidth: androidx.compose.ui.unit.Dp,
    onRequestMicPermission: () -> Unit,
    onRequestBatteryExemption: () -> Unit
) {
    PermissionSubRow(
        label = "Microphone permission",
        isGranted = micPermissionGranted,
        alpha = alpha,
        providerColWidth = providerColWidth,
        toggleColWidth = toggleColWidth,
        onRequestPermission = onRequestMicPermission
    )

    PermissionSubRow(
        label = "Battery optimization exemption",
        isGranted = batteryOptimizationExempt,
        alpha = alpha,
        providerColWidth = providerColWidth,
        toggleColWidth = toggleColWidth,
        onRequestPermission = onRequestBatteryExemption
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Location Permission Row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun LocationPermissionRow(
    alpha: Float,
    backgroundLocationGranted: Boolean,
    providerColWidth: androidx.compose.ui.unit.Dp,
    toggleColWidth: androidx.compose.ui.unit.Dp,
    onRequestBackgroundLocation: () -> Unit
) {
    PermissionSubRow(
        label = "Background location (Allow all the time)",
        isGranted = backgroundLocationGranted,
        alpha = alpha,
        providerColWidth = providerColWidth,
        toggleColWidth = toggleColWidth,
        onRequestPermission = onRequestBackgroundLocation
    )

    // Warning text when not granted
    if (!backgroundLocationGranted) {
        Text(
            "Required: Enable \"Allow all the time\" for the app to collect weather data and work properly in the background.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen Time Permission Row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ScreenTimePermissionRow(
    alpha: Float,
    screenTimePermissionGranted: Boolean,
    providerColWidth: androidx.compose.ui.unit.Dp,
    toggleColWidth: androidx.compose.ui.unit.Dp,
    onRequestPermission: () -> Unit
) {
    PermissionSubRow(
        label = "Usage access permission",
        isGranted = screenTimePermissionGranted,
        alpha = alpha,
        providerColWidth = providerColWidth,
        toggleColWidth = toggleColWidth,
        onRequestPermission = onRequestPermission
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Menstruation Detail Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MenstruationDetailCard(
    settings: MenstruationSettings,
    onEdit: () -> Unit,
    onAutoUpdateToggle: (Boolean) -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Last Period Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Last Period",
                        style = MaterialTheme.typography.labelMedium,
                        color = AppTheme.SubtleTextColor
                    )
                    Text(
                        settings.lastMenstruationDate?.toString() ?: "Not set",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppTheme.AccentPurple
                    )
                }
                TextButton(onClick = onEdit) {
                    Text("Edit", color = AppTheme.AccentPurple)
                }
            }

            Spacer(Modifier.height(8.dp))
            Divider()
            Spacer(Modifier.height(8.dp))

            // Average Cycle Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Average Cycle",
                        style = MaterialTheme.typography.labelMedium,
                        color = AppTheme.SubtleTextColor
                    )
                    Text(
                        "${settings.avgCycleLength} days",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppTheme.AccentPurple
                    )
                    Text(
                        "Weighted average of last 6 cycles",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.SubtleTextColor,
                        modifier = Modifier.alpha(0.7f)
                    )
                }
                TextButton(onClick = onEdit) {
                    Text("Edit", color = AppTheme.AccentPurple)
                }
            }

            Spacer(Modifier.height(8.dp))
            Divider()
            Spacer(Modifier.height(8.dp))

            // Auto-update Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Auto-update average",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.BodyTextColor
                    )
                    Text(
                        "Recalculate when new periods logged",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.SubtleTextColor,
                        modifier = Modifier.alpha(0.7f)
                    )
                }
                Switch(
                    checked = settings.autoUpdateAverage,
                    onCheckedChange = onAutoUpdateToggle
                )
            }

            Spacer(Modifier.height(8.dp))

            // Next expected (only show if lastMenstruationDate is not null)
            settings.lastMenstruationDate?.let { lastDate ->
                val nextPeriod = lastDate.plusDays(settings.avgCycleLength.toLong())
                Text(
                    "Next expected: $nextPeriod",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.AccentPurple
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helper: Open App Settings
// ─────────────────────────────────────────────────────────────────────────────

fun openAppSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

// ─────────────────────────────────────────────────────────────────────────────
// Notifications Card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NotificationsCard(
    onRequestNotificationPermission: () -> Unit,
    refreshTick: Int
) {
    val context = LocalContext.current
    val appContext = context.applicationContext

    // Check notification permission (Android 13+)
    val notificationPermissionGranted = remember(refreshTick) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true // Not needed pre-Android 13
        }
    }

    // Check if evening check-in channel is enabled
    val eveningCheckinEnabled = remember(refreshTick) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
            val channel = nm.getNotificationChannel("evening_checkin")
            channel == null || channel.importance != android.app.NotificationManager.IMPORTANCE_NONE
        } else {
            true
        }
    }

    val providerColWidth = 120.dp
    val toggleColWidth = 56.dp

    HeroCard {
        Text(
            "Notifications",
            color = AppTheme.TitleColor,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
        )
        Spacer(Modifier.height(12.dp))

        // Evening check-in row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp)
            ) {
                Text(
                    "Evening check-in",
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Daily reminder at 8pm to log your day",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Column(
                modifier = Modifier.width(toggleColWidth),
                horizontalAlignment = Alignment.End
            ) {
                Switch(
                    checked = notificationPermissionGranted && eveningCheckinEnabled,
                    onCheckedChange = { newValue ->
                        if (newValue) {
                            if (!notificationPermissionGranted) {
                                onRequestNotificationPermission()
                            } else if (!eveningCheckinEnabled) {
                                // Channel disabled by user — open app notification settings
                                openNotificationSettings(appContext)
                            }
                        } else {
                            // Turn off — open notification settings so user can disable the channel
                            openNotificationSettings(appContext)
                        }
                    }
                )
            }
        }

        // Permission sub-row (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Spacer(Modifier.height(4.dp))
            PermissionSubRow(
                label = "Notification permission",
                isGranted = notificationPermissionGranted,
                alpha = 1.0f,
                providerColWidth = providerColWidth,
                toggleColWidth = toggleColWidth,
                onRequestPermission = onRequestNotificationPermission
            )
        }

        // Channel warning
        if (notificationPermissionGranted && !eveningCheckinEnabled) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Evening Check-in notifications are disabled in system settings. Tap the toggle to open settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private fun openNotificationSettings(context: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    context.startActivity(intent)
}

