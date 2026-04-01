package com.migraineme

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Individual data settings row component.
 *
 * Handles rendering of a single metric row including:
 * - Label and description
 * - Source selector (wearable, phone, hybrid, or computed)
 * - Toggle switch
 * - Permission sub-rows (for ambient noise, location, screen time, phone sleep)
 */
@Composable
fun DataSettingsRow(
    row: DataRow,
    metricSettings: Map<String, EdgeFunctionsService.MetricSettingResponse>,
    connectedWearables: List<WearableSource>,
    menstruationSettings: MenstruationSettings?,
    nutritionPermissionGranted: Boolean,
    menstruationPermissionGranted: Boolean,
    greyOut: Boolean,
    weatherMetrics: Set<String>,
    onToggle: (metric: String, enabled: Boolean, source: String?) -> Unit,
    onSourceChange: (metric: String, newSource: String, currentEnabled: Boolean) -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onRequestScreenTimePermission: () -> Unit,
    onRequestLocationPermission: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext

    // Row type flags
    val isWearableRow = row.collectedByKind == CollectedByKind.WEARABLE
    val isPhoneOrWearableRow = row.collectedByKind == CollectedByKind.PHONE_OR_WEARABLE
    val isComputedRow = row.collectedByKind == CollectedByKind.COMPUTED
    val isScreenTimeRow = row.table == "screen_time_daily" && row.collectedByKind == CollectedByKind.PHONE
    val isLateNightScreenTimeRow = row.table == "screen_time_late_night" && row.collectedByKind == CollectedByKind.PHONE
    val isLocationRow = row.table == "user_location_daily" && row.collectedByKind == CollectedByKind.PHONE
    val isAmbientNoiseRow = row.table == "ambient_noise_samples" && row.collectedByKind == CollectedByKind.PHONE
    val isNutritionRow = row.table == "nutrition" && row.collectedByKind == CollectedByKind.PHONE
    val isMenstruationRow = row.table == "menstruation" && row.collectedByKind == CollectedByKind.PHONE
    val isStressRow = row.table == "stress_index_daily"

    // Permission states — refresh on lifecycle resume so granting permission is reflected immediately
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val screenTimePermissionGranted = remember(permissionTick) {
        if (isScreenTimeRow || isLateNightScreenTimeRow) DataSettingsPermissionHelper.hasScreenTimePermission(appContext) else true
    }
    val usageStatsPermissionGranted = remember(permissionTick) {
        if (isPhoneOrWearableRow) DataSettingsPermissionHelper.hasScreenTimePermission(appContext) else true
    }
    val locationPermissionGranted = remember(permissionTick) {
        DataSettingsPermissionHelper.hasLocationPermission(appContext)
    }
    val backgroundLocationGranted = remember(permissionTick) {
        DataSettingsPermissionHelper.hasBackgroundLocationPermission(appContext)
    }
    val micPermissionGranted = remember(permissionTick) {
        if (isAmbientNoiseRow) DataSettingsPermissionHelper.hasMicrophonePermission(appContext) else true
    }
    val batteryOptimizationExempt = remember(permissionTick) {
        if (isAmbientNoiseRow) DataSettingsPermissionHelper.isBatteryOptimizationExempt(appContext) else true
    }

    // Source selection state for standard wearable rows
    var selectedWearable by remember(row.table, metricSettings) {
        val supabaseSource = metricSettings[row.table]?.preferredSource
        val fromSupabase = supabaseSource?.let { key ->
            WearableSource.values().firstOrNull { it.key == key }
        }
        mutableStateOf(fromSupabase ?: row.defaultWearable ?: WearableSource.WHOOP)
    }

    // Source selection state for PHONE_OR_WEARABLE rows
    // Sources: "phone", "whoop", "health_connect"
    var selectedHybridSource by remember(row.table, metricSettings, connectedWearables) {
        val supabaseSource = metricSettings[row.table]?.preferredSource ?: "phone"
        mutableStateOf(supabaseSource)
    }

    val hybridSourceLabel = remember(selectedHybridSource) {
        when (selectedHybridSource) {
            "phone" -> "Phone"
            "whoop" -> "WHOOP"
            "health_connect" -> "Health Connect"
            "garmin" -> GarminDeviceNameProvider.getLabel(context)
            else -> "Phone"
        }
    }

    val hybridSourceOptions = remember(connectedWearables, row.table) {
        buildList {
            add("phone" to "Phone")
            for (w in connectedWearables) {
                if (MetricSourceSupport.supportsMetric(w, row.table)) {
                    val displayLabel = if (w == WearableSource.GARMIN) {
                        GarminDeviceNameProvider.getLabel(context)
                    } else {
                        w.label
                    }
                    add(w.key to displayLabel)
                }
            }
        }
    }

    // Is the current hybrid source using phone?
    val isHybridPhoneSource = isPhoneOrWearableRow && selectedHybridSource == "phone"

    var selectedMenstruationSource by remember(row.table, metricSettings, menstruationPermissionGranted) {
        val supabaseSource = metricSettings["menstruation"]?.preferredSource
        val fromSupabase = supabaseSource?.let { key ->
            PhoneSource.values().firstOrNull { it.key == key }
        }
        val initial = when {
            fromSupabase == PhoneSource.HEALTH_CONNECT && !menstruationPermissionGranted -> PhoneSource.PHONE
            fromSupabase != null -> fromSupabase
            else -> PhoneSource.PHONE
        }
        mutableStateOf(initial)
    }

    var selectedNutritionSource by remember(row.table, metricSettings, nutritionPermissionGranted) {
        val supabaseSource = metricSettings["nutrition"]?.preferredSource
        val fromSupabase = supabaseSource?.let { key ->
            PhoneSource.values().firstOrNull { it.key == key }
        }
        val initial = when {
            fromSupabase == PhoneSource.HEALTH_CONNECT && !nutritionPermissionGranted -> PhoneSource.PHONE
            fromSupabase != null -> fromSupabase
            else -> PhoneSource.PHONE
        }
        mutableStateOf(initial)
    }

    // Enabled state from Supabase
    val enabledBySupabase = metricSettings[row.table]?.enabled ?: getDefaultEnabled(row)

    // Stress dependency check — only relevant when stress is computed (no wearable selected)
    // Note: isStressWithWearableSource is computed below after allowedWearables is defined
    val depsOkForStress = if (isStressRow) {
        DataSettingsToggleHandler.areStressDependenciesMet(metricSettings)
    } else true

    // Effective grey out state
    // PHONE_OR_WEARABLE rows are NEVER greyed out (phone is always a valid fallback)
    // Stress with no wearable source depends on its prerequisites
    // Note: for stress, grey-out is re-evaluated below after allowedWearables is known
    val effectiveGreyOut = when {
        isPhoneOrWearableRow -> false
        isStressRow -> !depsOkForStress
        else -> greyOut
    }

    // Can toggle ambient noise
    val canToggleAmbient = !isAmbientNoiseRow ||
            (micPermissionGranted && batteryOptimizationExempt) ||
            !enabledBySupabase

    // Layout dimensions
    val toggleColWidth = 56.dp

    // Provider options
    val menstruationProviderOptions = remember(menstruationPermissionGranted) {
        buildList {
            add(PhoneSource.PHONE)
            if (menstruationPermissionGranted) add(PhoneSource.HEALTH_CONNECT)
        }
    }

    val nutritionProviderOptions = remember(nutritionPermissionGranted) {
        buildList {
            add(PhoneSource.PHONE)
            if (nutritionPermissionGranted) add(PhoneSource.HEALTH_CONNECT)
        }
    }

    // Filter wearables to only sources that support this metric
    val allowedWearables = remember(connectedWearables, row.table) {
        connectedWearables.filter { source ->
            MetricSourceSupport.supportsMetric(source, row.table)
        }
    }

    // Stress with a wearable source (Oura, WHOOP) doesn't need HRV/RHR deps
    val isStressWithWearableSource = isStressRow && allowedWearables.isNotEmpty()
    val finalGreyOut = if (isStressWithWearableSource) false else effectiveGreyOut
    val alpha = if (finalGreyOut) 0.55f else 1.0f

    Column {
        // Main row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(alpha),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Label + source badge column
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp)
            ) {
                Text(
                    row.collectedByLabel,
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )

                // Source badge below label
                Spacer(Modifier.height(3.dp))
                when {
                    isComputedRow -> {
                        SourceBadge(label = "Computed", hasMultiple = false, enabled = false, onClick = {})
                    }
                    isPhoneOrWearableRow -> {
                        HybridSourceSelector(
                            options = hybridSourceOptions,
                            selectedKey = selectedHybridSource,
                            enabled = true,
                            onSelected = { newKey ->
                                if (newKey != selectedHybridSource) {
                                    selectedHybridSource = newKey
                                    onSourceChange(row.table, newKey, enabledBySupabase)
                                }
                            }
                        )
                    }
                    isWearableRow && allowedWearables.isNotEmpty() -> {
                        val currentSelection = if (allowedWearables.contains(selectedWearable)) {
                            selectedWearable
                        } else {
                            allowedWearables.first()
                        }
                        WearableSourceSelector(
                            options = allowedWearables,
                            selected = currentSelection,
                            enabled = !finalGreyOut,
                            onSelected = { newSel ->
                                if (newSel != selectedWearable) {
                                    selectedWearable = newSel
                                    onSourceChange(row.table, newSel.key, enabledBySupabase)
                                }
                            }
                        )
                    }
                    isWearableRow && allowedWearables.isEmpty() -> {
                        SourceBadge(label = "Not available", hasMultiple = false, enabled = false, onClick = {})
                    }
                    isMenstruationRow -> {
                        PhoneSourceSelector(
                            options = menstruationProviderOptions,
                            selected = selectedMenstruationSource,
                            enabled = !finalGreyOut,
                            onSelected = { newSel ->
                                if (newSel != selectedMenstruationSource) {
                                    selectedMenstruationSource = newSel
                                    onSourceChange("menstruation", newSel.key, enabledBySupabase)
                                }
                            }
                        )
                    }
                    isNutritionRow -> {
                        PhoneSourceSelector(
                            options = nutritionProviderOptions,
                            selected = selectedNutritionSource,
                            enabled = !finalGreyOut,
                            onSelected = { newSel ->
                                if (newSel != selectedNutritionSource) {
                                    selectedNutritionSource = newSel
                                    onSourceChange("nutrition", newSel.key, enabledBySupabase)
                                }
                            }
                        )
                    }
                    isAmbientNoiseRow -> {
                        SourceBadge(label = "Phone", hasMultiple = false, enabled = false, onClick = {})
                    }
                    else -> {
                        val providerLabel = when (row.collectedByKind) {
                            CollectedByKind.PHONE -> "Phone"
                            CollectedByKind.MANUAL -> "Manual"
                            else -> ""
                        }
                        if (providerLabel.isNotEmpty()) {
                            SourceBadge(label = providerLabel, hasMultiple = false, enabled = false, onClick = {})
                        }
                    }
                }

                // Additional info hints
                if (isMenstruationRow && enabledBySupabase && menstruationSettings?.lastMenstruationDate != null) {
                    Text(
                        "Last: ${menstruationSettings.lastMenstruationDate} • Avg: ${menstruationSettings.avgCycleLength} days",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (isMenstruationRow && enabledBySupabase &&
                    (menstruationSettings == null || menstruationSettings.lastMenstruationDate == null)
                ) {
                    Text("Please go to Monitor to complete setup.", style = MaterialTheme.typography.bodySmall, color = AppTheme.AccentPurple)
                }
                if (isMenstruationRow) {
                    Text("Note: Many apps no longer share menstruation data with Health Connect.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
                if (isStressRow && !isStressWithWearableSource && !depsOkForStress) {
                    Text("Enable HRV and Resting HR to compute Stress, or select a wearable source.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
                if (row.table in weatherMetrics && finalGreyOut) {
                    Text("Enable Location to use weather data.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
                if (isHybridPhoneSource && !usageStatsPermissionGranted) {
                    Text("Usage access permission needed for phone sleep.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }

                // Oura conversion info cards — shown when Oura is selected for metrics that involve data mapping
                if (selectedWearable == WearableSource.OURA || (isPhoneOrWearableRow && selectedHybridSource == "oura")) {
                    val conversionNote = ouraConversionNote(row.table)
                    if (conversionNote != null) {
                        OuraConversionCard(note = conversionNote)
                    }
                }

                // Polar conversion info cards — shown when Polar is selected
                if (selectedWearable == WearableSource.POLAR || (isPhoneOrWearableRow && selectedHybridSource == "polar")) {
                    val conversionNote = polarConversionNote(row.table)
                    if (conversionNote != null) {
                        OuraConversionCard(note = conversionNote) // Reuse same card style
                    }
                }

                // Garmin conversion info cards — shown when Garmin is selected
                if (selectedWearable == WearableSource.GARMIN || (isPhoneOrWearableRow && selectedHybridSource == "garmin")) {
                    val conversionNote = garminConversionNote(row.table)
                    if (conversionNote != null) {
                        OuraConversionCard(note = conversionNote)
                    }
                }
            }

            // Toggle column
            Column(
                modifier = Modifier.width(toggleColWidth),
                horizontalAlignment = Alignment.End
            ) {
                Switch(
                    checked = enabledBySupabase,
                    enabled = !finalGreyOut && canToggleAmbient && !(isWearableRow && allowedWearables.isEmpty()),
                    modifier = Modifier.scale(0.8f),
                    onCheckedChange = { newValue ->
                        if (finalGreyOut) return@Switch
                        if (isStressRow && !isStressWithWearableSource && !depsOkForStress) return@Switch

                        // Wearable-only rows: block toggle-on if no wearable source is connected
                        if (isWearableRow && newValue && allowedWearables.isEmpty()) return@Switch

                        // Permission checks that block toggle
                        if ((isScreenTimeRow || isLateNightScreenTimeRow) && newValue && !screenTimePermissionGranted) {
                            onRequestScreenTimePermission()
                            return@Switch
                        }

                        // Phone sleep with phone source needs usage stats permission
                        if (isPhoneOrWearableRow && newValue && selectedHybridSource == "phone" && !usageStatsPermissionGranted) {
                            onRequestScreenTimePermission()
                            return@Switch
                        }

                        if (isLocationRow && newValue && !locationPermissionGranted) {
                            onRequestLocationPermission()
                            return@Switch
                        }

                        if (isAmbientNoiseRow && newValue) {
                            if (!micPermissionGranted) {
                                onRequestMicPermission()
                                return@Switch
                            }
                            if (!batteryOptimizationExempt) {
                                onRequestBatteryExemption()
                                return@Switch
                            }
                        }

                        if (isNutritionRow && newValue &&
                            selectedNutritionSource == PhoneSource.HEALTH_CONNECT &&
                            !nutritionPermissionGranted
                        ) {
                            return@Switch
                        }

                        // Determine preferred source
                        val preferredSource = when {
                            isPhoneOrWearableRow -> selectedHybridSource
                            isWearableRow -> selectedWearable.key
                            isMenstruationRow -> selectedMenstruationSource.key
                            isNutritionRow -> selectedNutritionSource.key
                            else -> null
                        }

                        onToggle(row.table, newValue, preferredSource)
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppTheme.AccentPurple)
                )
            }
        }

        // Permission sub-rows
        if (isAmbientNoiseRow) {
            AmbientNoisePermissionRows(
                alpha = alpha,
                micPermissionGranted = micPermissionGranted,
                batteryOptimizationExempt = batteryOptimizationExempt,
                providerColWidth = 0.dp,
                toggleColWidth = toggleColWidth,
                onRequestMicPermission = onRequestMicPermission,
                onRequestBatteryExemption = onRequestBatteryExemption
            )
        }

        if (isLocationRow) {
            LocationPermissionRow(
                alpha = alpha,
                backgroundLocationGranted = backgroundLocationGranted,
                providerColWidth = 0.dp,
                toggleColWidth = toggleColWidth,
                onRequestBackgroundLocation = onRequestBackgroundLocation
            )
        }

        if (isScreenTimeRow || isLateNightScreenTimeRow) {
            ScreenTimePermissionRow(
                alpha = alpha,
                screenTimePermissionGranted = screenTimePermissionGranted,
                providerColWidth = 0.dp,
                toggleColWidth = toggleColWidth,
                onRequestPermission = onRequestScreenTimePermission
            )
        }

        // Phone sleep permission sub-row (only when phone source is selected)
        if (isHybridPhoneSource) {
            ScreenTimePermissionRow(
                alpha = 1.0f,
                screenTimePermissionGranted = usageStatsPermissionGranted,
                providerColWidth = 0.dp,
                toggleColWidth = toggleColWidth,
                onRequestPermission = onRequestScreenTimePermission
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helper
// ─────────────────────────────────────────────────────────────────────────────

private fun getDefaultEnabled(row: DataRow): Boolean {
    return when (row.table) {
        "screen_time_daily" -> false
        "screen_time_late_night" -> false
        "menstruation" -> false
        "nutrition" -> false
        else -> defaultActiveFor(row)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Oura conversion info
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Returns a human-readable note explaining how Oura data is converted
 * for metrics where the mapping isn't 1:1, or null if no conversion applies.
 */
private fun ouraConversionNote(table: String): String? = when (table) {
    "strain_daily" ->
        "Active calories from Oura's daily activity are converted to kilojoules (×4.184). Heart rate data is not available from Oura for this metric."

    "stress_index_daily" ->
        "When Oura is selected, stress is written directly from Oura's stress data: stress_high ÷ (stress_high + recovery_high) × 100. This replaces the default computed stress (from HRV + RHR z-scores)."

    "time_in_high_hr_zones_daily" ->
        "Oura workouts don't include HR zone breakdowns. Duration is mapped by intensity: hard → zone 5, moderate → zone 3, easy → zone 1."

    "respiratory_rate_daily" ->
        "Average breathing rate (breaths/min) measured during your longest sleep period."

    "recovery_score_daily" ->
        "Oura's Readiness Score (0–100) is used as recovery score."

    "skin_temp_daily" ->
        "Oura reports temperature as a deviation from your baseline in °C (e.g. +0.3 or −0.1), not absolute. Trigger thresholds are adjusted automatically: ±0.8°C deviation flags a significant change."

    "sleep_disturbances_daily" ->
        "Mapped from Oura's restless_periods count during sleep."

    else -> null
}

private fun polarConversionNote(table: String): String? = when (table) {
    "sleep_efficiency_daily" ->
        "Polar's sleep continuity (1.0–5.0 scale) is converted to a percentage: (continuity − 1) ÷ 4 × 100."

    "sleep_disturbances_daily" ->
        "Polar reports total interruption duration. Converted to an approximate disturbance count: 1 per 5 interruption minutes."

    "recovery_score_daily" ->
        "Polar's Nightly Recharge ANS charge score is used as recovery. Scale varies; normalized to 0–100 where possible."

    "strain_daily" ->
        "Active calories from Polar's daily activity are converted to kilojoules (×4.184)."

    "time_in_high_hr_zones_daily" ->
        "Duration and HR zones from Polar exercise sessions. Mapped to zone 1–5 based on Polar's zone definitions."

    "skin_temp_daily" ->
        "Polar Elixir™ records absolute skin temperature during sleep. Raw values are stored internally and deviation from the previous night is computed daily. Requires compatible device (Vantage V3, Grit X2 Pro, etc.)."

    "spo2_daily" ->
        "Polar Elixir™ SpO2 spot-check result. Requires compatible device with SpO2 sensor."

    else -> null
}

private fun garminConversionNote(table: String): String? = when (table) {
    "sleep_efficiency_daily" ->
        "Computed from Garmin sleep stages: (total − awake) ÷ total × 100."

    "sleep_disturbances_daily" ->
        "Garmin reports total awake duration during sleep. Converted to an approximate disturbance count: 1 per 5 awake minutes."

    "recovery_score_daily" ->
        "Garmin's Body Battery overnight recharge is used as recovery (0–100). Requires a compatible device."

    "strain_daily" ->
        "Active calories from Garmin dailies converted to kilojoules (×4.184)."

    "stress_index_daily" ->
        "Garmin provides direct stress scores (1–100) from HRV-based algorithms. No proxy needed."

    "skin_temp_daily" ->
        "Garmin sleep skin temperature variation. Requires devices with skin temp sensor (e.g., Venu 3, Fenix 8)."

    "spo2_daily" ->
        "Garmin Pulse Ox reading during sleep. Requires SpO2-compatible device."

    "hrv_daily" ->
        "Garmin HRV status from overnight sleep window. Reports 7-day average and last-night values."

    else -> null
}

/**
 */
@Composable
private fun OuraConversionCard(note: String) {
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                AppTheme.AccentPurple.copy(alpha = 0.10f),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "ℹ",
            color = AppTheme.AccentPurple,
            style = MaterialTheme.typography.labelMedium
        )
        Text(
            note,
            color = AppTheme.AccentPurple.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}



