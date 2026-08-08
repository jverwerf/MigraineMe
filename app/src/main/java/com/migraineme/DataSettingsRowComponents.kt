package com.migraineme

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    val context = LocalContext.current

    Box {
        SourceBadge(
            label = if (selected == WearableSource.GARMIN) GarminDeviceNameProvider.getLabel(context) else selected.label,
            hasMultiple = options.size > 1,
            enabled = enabled,
            onClick = { if (enabled && options.size > 1) expanded = true }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF1E0A2E))
        ) {
            options.forEach { opt ->
                val optLabel = if (opt == WearableSource.GARMIN) GarminDeviceNameProvider.getLabel(context) else opt.label
                DropdownMenuItem(
                    text = { Text(optLabel, color = Color.White) },
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
// Gauge Alert Level Selector
// ─────────────────────────────────────────────────────────────────────────────

/**
 * How high today's risk has to climb before we push a notification.
 *
 * Mirrors the RiskZone names used on the gauge itself, plus an OFF option, so
 * the setting reads in the same language as the thing it controls. Stored in
 * notification_settings as enabled + threshold; an absent row means MILD.
 */
enum class GaugeAlertLevel(
    val label: String,
    val dbValue: String?,
    val description: String
) {
    OFF("Off", null, "No alerts when your risk changes"),
    LOW("Low", "LOW", "Alert me on any day above negligible risk"),
    MILD("Mild", "MILD", "Alert me on mild and high risk days"),
    HIGH("High", "HIGH", "Alert me on high risk days only");

    companion object {
        /** Interprets a notification_settings row. Null row = never configured = MILD. */
        fun from(enabled: Boolean?, threshold: String?): GaugeAlertLevel {
            if (enabled == false) return OFF
            return entries.firstOrNull { it.dbValue == threshold?.uppercase() } ?: MILD
        }
    }
}

@Composable
fun GaugeAlertLevelSelector(
    selected: GaugeAlertLevel,
    enabled: Boolean,
    onSelected: (GaugeAlertLevel) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        SourceBadge(
            label = selected.label,
            hasMultiple = true,
            enabled = enabled,
            onClick = { if (enabled) expanded = true },
            tint = AppTheme.AccentPurple
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF1E0A2E))
        ) {
            GaugeAlertLevel.entries.forEach { level ->
                DropdownMenuItem(
                    text = { Text(level.label, color = Color.White) },
                    onClick = {
                        expanded = false
                        onSelected(level)
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Ongoing Migraine Reminder Selector
// ─────────────────────────────────────────────────────────────────────────────

/**
 * How often to be reminded that a logged migraine still has no end time.
 *
 * Stored in notification_settings as enabled + interval_days. Absent row means
 * ON at 3 days. The backend stops after a few unanswered reminders, so picking
 * a short interval can't turn into endless nagging.
 */
enum class OngoingReminderInterval(val label: String, val days: Int?) {
    OFF("Off", null),
    ONE("1 day", 1),
    TWO("2 days", 2),
    THREE("3 days", 3),
    FIVE("5 days", 5),
    SEVEN("7 days", 7);

    val description: String
        get() = when (this) {
            OFF -> "No reminders about migraines you haven't closed"
            else -> "Remind me every $label while a migraine is still open"
        }

    companion object {
        val DEFAULT = THREE

        /** Interprets a notification_settings row. Null row = never configured = 3 days. */
        fun from(enabled: Boolean?, intervalDays: Int?): OngoingReminderInterval {
            if (enabled == false) return OFF
            return entries.firstOrNull { it.days != null && it.days == intervalDays } ?: DEFAULT
        }
    }
}

@Composable
fun OngoingReminderSelector(
    selected: OngoingReminderInterval,
    enabled: Boolean,
    onSelected: (OngoingReminderInterval) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        SourceBadge(
            label = selected.label,
            hasMultiple = true,
            enabled = enabled,
            onClick = { if (enabled) expanded = true },
            tint = AppTheme.AccentPurple
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF1E0A2E))
        ) {
            OngoingReminderInterval.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label, color = Color.White) },
                    onClick = {
                        expanded = false
                        onSelected(option)
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
        SourceBadge(
            label = selected.label,
            hasMultiple = options.size > 1,
            enabled = enabled,
            onClick = { if (enabled && options.size > 1) expanded = true }
        )

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
        SourceBadge(
            label = selectedLabel,
            hasMultiple = options.size > 1,
            enabled = enabled,
            onClick = { if (enabled && options.size > 1) expanded = true }
        )

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
// Source Badge — tappable pill showing current source
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SourceBadge(
    label: String,
    hasMultiple: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    // Green by default — the data-source pills across the rest of the screen.
    // The Notifications card passes the purple accent so its pickers read in
    // the same colour language as the switches sitting next to them.
    tint: Color = Color(0xFF81C784)
) {
    val badgeColor = tint
    Row(
        modifier = Modifier
            .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .then(if (hasMultiple && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(badgeColor, RoundedCornerShape(3.dp))
        )
        Text(label, color = badgeColor, style = MaterialTheme.typography.labelSmall)
        if (hasMultiple) {
            Icon(
                Icons.Filled.KeyboardArrowDown, null,
                tint = badgeColor,
                modifier = Modifier.size(12.dp)
            )
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
                modifier = Modifier.scale(0.8f),
                onCheckedChange = { newVal ->
                    if (newVal && !isGranted) {
                        onRequestPermission()
                    }
                },
                enabled = !isGranted,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppTheme.AccentPurple)
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

            Spacer(Modifier.height(4.dp))
            Divider()
            Spacer(Modifier.height(4.dp))

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

            Spacer(Modifier.height(4.dp))
            Divider()
            Spacer(Modifier.height(4.dp))

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
                    modifier = Modifier.scale(0.8f),
                    onCheckedChange = onAutoUpdateToggle,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppTheme.AccentPurple)
                )
            }

            Spacer(Modifier.height(4.dp))

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
    refreshTick: Int,
    // Non-blank while the user is searching the Data screen: each row survives
    // only if its label or description contains the query, and the whole card
    // disappears when nothing in it matches.
    searchQuery: String = ""
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

    val toggleColWidth = 56.dp

    // Search filtering. A blank query shows the whole card; a query matching the
    // card title keeps every row; otherwise each row is tested on its own text.
    val q = searchQuery.trim()
    val titleHit = q.isBlank() || "Notifications".contains(q, ignoreCase = true)
    fun hit(vararg text: String) = titleHit || text.any { it.contains(q, ignoreCase = true) }

    val showEvening = hit("Evening check-in", "Daily reminder at 8pm to log your day")
    val showGauge = hit("Daily risk alerts", "risk changes", "risk days")
    val showOngoing = hit("Ongoing migraine reminder", "migraine is still open")
    val showTriggerAlerts = hit("Trigger alerts", "When an item you follow becomes a trigger for the day")
    val showRecs = hit("Recommendation alerts", "Get notified each morning when your new recommendations are ready")
    val showCompanion = hit("AI companion", "Get notified when an AI companion adds to your community feed")
    val showPermission = hit("Notification permission")
    if (!showEvening && !showGauge && !showOngoing && !showTriggerAlerts && !showRecs && !showCompanion && !showPermission) return

    // Dividers only make sense on the full card — a filtered result list would
    // otherwise start or end with a stray rule.
    val showDividers = q.isBlank()

    BaseCard {
        // Section header — same style as other data sections
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Outlined.Notifications, null,
                tint = AppTheme.AccentPurple,
                modifier = Modifier.size(18.dp)
            )
            Text(
                "Notifications",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold
                )
            )
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 4.dp))

        // Evening check-in row
        if (showEvening) {
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
                        modifier = Modifier.scale(0.8f),
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
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppTheme.AccentPurple)
                    )
                }
            }
        }

        if (showDividers) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 4.dp))
        }

        val scope = rememberCoroutineScope()
        val edge = remember { EdgeFunctionsService() }

        // Daily risk gauge row — DB-backed (notification_settings.daily_gauge).
        // Unlike the other rows this is not a toggle but a threshold: the backend
        // pushes when today's gauge first reaches the chosen zone, and again if it
        // climbs higher during the day. Absent row = ON at MILD.
        var gaugeLoaded by remember { mutableStateOf(false) }
        var gaugeThreshold by remember { mutableStateOf(GaugeAlertLevel.MILD) }
        LaunchedEffect(Unit) {
            val row = withContext(Dispatchers.IO) {
                edge.getNotificationSetting(appContext, "daily_gauge")
            }
            gaugeThreshold = GaugeAlertLevel.from(row?.enabled, row?.threshold)
            gaugeLoaded = true
        }

        if (showGauge) {
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
                        "Daily risk alerts",
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        gaugeThreshold.description,
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                GaugeAlertLevelSelector(
                    selected = gaugeThreshold,
                    enabled = gaugeLoaded,
                    onSelected = { level ->
                        val previous = gaugeThreshold
                        gaugeThreshold = level
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                edge.upsertNotificationSetting(
                                    appContext,
                                    "daily_gauge",
                                    enabled = level != GaugeAlertLevel.OFF,
                                    threshold = level.dbValue
                                )
                            }
                            if (!ok) gaugeThreshold = previous // revert on failure
                        }
                    }
                )
            }
        }

        if (showDividers) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 4.dp))
        }

        // Ongoing migraine reminder row — DB-backed (notification_settings.ongoing_migraine).
        // Interval picker, not a toggle: the backend nudges every N days while a
        // logged migraine still has no end time. Absent row = ON at 3 days.
        var ongoingLoaded by remember { mutableStateOf(false) }
        var ongoingInterval by remember { mutableStateOf(OngoingReminderInterval.DEFAULT) }
        LaunchedEffect(Unit) {
            val row = withContext(Dispatchers.IO) {
                edge.getNotificationSetting(appContext, "ongoing_migraine")
            }
            ongoingInterval = OngoingReminderInterval.from(row?.enabled, row?.intervalDays)
            ongoingLoaded = true
        }

        if (showOngoing) {
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
                        "Ongoing migraine reminder",
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        ongoingInterval.description,
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                OngoingReminderSelector(
                    selected = ongoingInterval,
                    enabled = ongoingLoaded,
                    onSelected = { option ->
                        val previous = ongoingInterval
                        ongoingInterval = option
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                edge.upsertNotificationSetting(
                                    appContext,
                                    "ongoing_migraine",
                                    enabled = option != OngoingReminderInterval.OFF,
                                    intervalDays = option.days
                                )
                            }
                            if (!ok) ongoingInterval = previous // revert on failure
                        }
                    }
                )
            }
        }

        if (showDividers) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 4.dp))
        }

        // Trigger alerts row — DB-backed preference (notification_settings.trigger_alert).
        // Master toggle for the per-item bells on the trigger/prodrome pools.
        // Default ON; toggling writes to Supabase so the backend skips the push when off.
        var triggerAlertLoaded by remember { mutableStateOf(false) }
        var triggerAlertEnabled by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            val enabled = withContext(Dispatchers.IO) {
                edge.getNotificationEnabled(appContext, "trigger_alert", default = true)
            }
            triggerAlertEnabled = enabled
            triggerAlertLoaded = true
        }

        if (showTriggerAlerts) {
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
                        "Trigger alerts",
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "When an item you follow becomes a trigger for the day",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Column(
                    modifier = Modifier.width(toggleColWidth),
                    horizontalAlignment = Alignment.End
                ) {
                    Switch(
                        checked = triggerAlertEnabled,
                        enabled = triggerAlertLoaded,
                        modifier = Modifier.scale(0.8f),
                        onCheckedChange = { newValue ->
                            triggerAlertEnabled = newValue
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    edge.upsertNotificationSetting(appContext, "trigger_alert", newValue)
                                }
                                if (!ok) triggerAlertEnabled = !newValue // revert on failure
                            }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppTheme.AccentPurple)
                    )
                }
            }
        }

        if (showDividers) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 4.dp))
        }

        // Recommendation alerts row — DB-backed preference (notification_settings.new_insight).
        // Default ON; toggling writes to Supabase so the backend skips the push when off.
        var newInsightLoaded by remember { mutableStateOf(false) }
        var newInsightEnabled by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            val enabled = withContext(Dispatchers.IO) {
                edge.getNotificationEnabled(appContext, "new_insight", default = true)
            }
            newInsightEnabled = enabled
            newInsightLoaded = true
        }

        if (showRecs) {
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
                        "Recommendation alerts",
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Get notified each morning when your new recommendations are ready",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Column(
                    modifier = Modifier.width(toggleColWidth),
                    horizontalAlignment = Alignment.End
                ) {
                    Switch(
                        checked = newInsightEnabled,
                        enabled = newInsightLoaded,
                        modifier = Modifier.scale(0.8f),
                        onCheckedChange = { newValue ->
                            newInsightEnabled = newValue
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    edge.upsertNotificationSetting(appContext, "new_insight", newValue)
                                }
                                if (!ok) newInsightEnabled = !newValue // revert on failure
                            }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppTheme.AccentPurple)
                    )
                }
            }
        }

        // Device check-ins — DB-backed preference (notification_settings.device_relief_outcome).
        // Default ON; the 2h outcome worker checks this before posting.
        var deviceOutcomeLoaded by remember { mutableStateOf(false) }
        var deviceOutcomeEnabled by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            val enabled = withContext(Dispatchers.IO) {
                edge.getNotificationEnabled(appContext, "device_relief_outcome", default = true)
            }
            deviceOutcomeEnabled = enabled
            deviceOutcomeLoaded = true
        }

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
                    "Device check-ins",
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Ask how a neuromodulation device session went, 2 hours after you log it",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Column(
                modifier = Modifier.width(toggleColWidth),
                horizontalAlignment = Alignment.End
            ) {
                Switch(
                    checked = deviceOutcomeEnabled,
                    enabled = deviceOutcomeLoaded,
                    modifier = Modifier.scale(0.8f),
                    onCheckedChange = { newValue ->
                        deviceOutcomeEnabled = newValue
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                edge.upsertNotificationSetting(appContext, "device_relief_outcome", newValue)
                            }
                            if (!ok) deviceOutcomeEnabled = !newValue // revert on failure
                        }
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppTheme.AccentPurple)
                )
            }
        }

        // AI companion / community activity push — DB-backed (notification_settings.community).
        // Default ON; toggling writes to Supabase so the backend trigger skips the push when off.
        var communityLoaded by remember { mutableStateOf(false) }
        var communityEnabled by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            val enabled = withContext(Dispatchers.IO) {
                edge.getNotificationEnabled(appContext, "community", default = true)
            }
            communityEnabled = enabled
            communityLoaded = true
        }

        if (showCompanion) {
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
                        "AI companion",
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Get notified when an AI companion adds to your community feed",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Column(
                    modifier = Modifier.width(toggleColWidth),
                    horizontalAlignment = Alignment.End
                ) {
                    Switch(
                        checked = communityEnabled,
                        enabled = communityLoaded,
                        modifier = Modifier.scale(0.8f),
                        onCheckedChange = { newValue ->
                            communityEnabled = newValue
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    edge.upsertNotificationSetting(appContext, "community", newValue)
                                }
                                if (!ok) communityEnabled = !newValue // revert on failure
                            }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppTheme.AccentPurple)
                    )
                }
            }
        }

        // Permission sub-row (Android 13+)
        if (showPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionSubRow(
                label = "Notification permission",
                isGranted = notificationPermissionGranted,
                alpha = 1.0f,
                providerColWidth = 0.dp,
                toggleColWidth = toggleColWidth,
                onRequestPermission = onRequestNotificationPermission
            )
        }

        // Channel warning
        if (showEvening && notificationPermissionGranted && !eveningCheckinEnabled) {
            Text(
                "Evening Check-in notifications are disabled in system settings. Tap the toggle to open settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun CalendarCard(
    metricSettings: Map<String, EdgeFunctionsService.MetricSettingResponse>,
    onRequestCalendarPermission: () -> Unit,
    onToggleData: (enabled: Boolean) -> Unit,
    refreshTick: Int,
    // See NotificationsCard — non-blank while searching the Data screen.
    searchQuery: String = "",
) {
    val context = LocalContext.current
    val appContext = context.applicationContext

    val permissionGranted = remember(refreshTick) {
        DataSettingsPermissionHelper.hasCalendarPermission(appContext)
    }
    val dataEnabled = metricSettings["calendar_events"]?.enabled == true

    val toggleColWidth = 56.dp

    val q = searchQuery.trim()
    val titleHit = q.isBlank() || "Calendar".contains(q, ignoreCase = true)
    fun hit(vararg text: String) = titleHit || text.any { it.contains(q, ignoreCase = true) }

    val showEvents = hit("Calendar events", "Map your events to activities, reliefs, and triggers")
    val showPermission = hit("Calendar permission")
    if (!showEvents && !showPermission) return

    BaseCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Outlined.DateRange, null,
                tint = AppTheme.AccentPurple,
                modifier = Modifier.size(18.dp)
            )
            Text(
                "Calendar",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 4.dp))

        // Data toggle row
        if (showEvents) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text("Calendar events", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Map your events to activities, reliefs, and triggers",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Column(modifier = Modifier.width(toggleColWidth), horizontalAlignment = Alignment.End) {
                    Switch(
                        checked = dataEnabled && permissionGranted,
                        enabled = permissionGranted,
                        modifier = Modifier.scale(0.8f),
                        onCheckedChange = { newValue -> onToggleData(newValue) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppTheme.AccentPurple)
                    )
                }
            }
        }

        // Permission sub-row
        if (showPermission) {
            PermissionSubRow(
                label = "Calendar permission",
                isGranted = permissionGranted,
                alpha = 1.0f,
                providerColWidth = 0.dp,
                toggleColWidth = toggleColWidth,
                onRequestPermission = onRequestCalendarPermission
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

