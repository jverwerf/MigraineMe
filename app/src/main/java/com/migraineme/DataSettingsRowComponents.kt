package com.migraineme

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
            Text(selected.label, style = MaterialTheme.typography.bodySmall)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.label) },
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
            Text(selected.label, style = MaterialTheme.typography.bodySmall)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.label) },
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
            Text(selectedLabel, style = MaterialTheme.typography.bodySmall)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (key: String, label: String) ->
                DropdownMenuItem(
                    text = { Text(label) },
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        settings.lastMenstruationDate?.toString() ?: "Not set",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                TextButton(onClick = onEdit) {
                    Text("Edit")
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${settings.avgCycleLength} days",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Weighted average of last 6 cycles",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alpha(0.7f)
                    )
                }
                TextButton(onClick = onEdit) {
                    Text("Edit")
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
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Recalculate when new periods logged",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    color = MaterialTheme.colorScheme.secondary
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
