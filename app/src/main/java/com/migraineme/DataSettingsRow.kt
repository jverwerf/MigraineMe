package com.migraineme

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
            else -> "Phone"
        }
    }

    val hybridSourceOptions = remember(connectedWearables, row.table) {
        buildList {
            add("phone" to "Phone")
            for (w in connectedWearables) {
                if (MetricSourceSupport.supportsMetric(w, row.table)) {
                    add(w.key to w.label)
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

    // Stress dependency check
    val depsOkForStress = if (isStressRow) {
        DataSettingsToggleHandler.areStressDependenciesMet(metricSettings)
    } else true

    // Effective grey out state
    // PHONE_OR_WEARABLE rows are NEVER greyed out (phone is always a valid fallback)
    // COMPUTED rows (stress) depend on their prerequisites
    val effectiveGreyOut = when {
        isPhoneOrWearableRow -> false
        isStressRow -> !depsOkForStress
        else -> greyOut
    }
    val alpha = if (effectiveGreyOut) 0.55f else 1.0f

    // Can toggle ambient noise
    val canToggleAmbient = !isAmbientNoiseRow ||
            (micPermissionGranted && batteryOptimizationExempt) ||
            !enabledBySupabase

    // Layout dimensions
    val providerColWidth = 120.dp
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

    Column {
        // Main row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(alpha),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Label column
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

                // Additional info based on row type
                if (isAmbientNoiseRow) {
                    Text(
                        "Phone",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (isMenstruationRow && enabledBySupabase && menstruationSettings?.lastMenstruationDate != null) {
                    Text(
                        "Last: ${menstruationSettings.lastMenstruationDate} • Avg: ${menstruationSettings.avgCycleLength} days",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Setup hint for menstruation
                if (isMenstruationRow && enabledBySupabase &&
                    (menstruationSettings == null || menstruationSettings.lastMenstruationDate == null)
                ) {
                    Text(
                        "Please go to Monitor to complete setup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.AccentPurple
                    )
                }

                // Health Connect limitation note
                if (isMenstruationRow) {
                    Text(
                        "Note: Many apps no longer share menstruation data with Health Connect.",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Stress dependency hint
                if (isStressRow && !depsOkForStress) {
                    Text(
                        "Enable HRV and Resting HR to use Stress.",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Weather dependency hint
                if (row.table in weatherMetrics && effectiveGreyOut) {
                    Text(
                        "Enable Location to use weather data.",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Phone sleep hint when phone source and no permission
                if (isHybridPhoneSource && !usageStatsPermissionGranted) {
                    Text(
                        "Usage access permission needed for phone sleep.",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Source selector column
            Column(
                modifier = Modifier
                    .width(providerColWidth)
                    .padding(end = 10.dp),
                horizontalAlignment = Alignment.Start
            ) {
                when {
                    // COMPUTED rows — show "Computed" label, no selector
                    isComputedRow -> {
                        Text(
                            "Computed",
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // PHONE_OR_WEARABLE rows — hybrid source selector
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
                            enabled = !effectiveGreyOut,
                            onSelected = { newSel ->
                                if (newSel != selectedWearable) {
                                    selectedWearable = newSel
                                    onSourceChange(row.table, newSel.key, enabledBySupabase)
                                }
                            }
                        )
                    }

                    isWearableRow && allowedWearables.isEmpty() -> {
                        Text(
                            "Not available",
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    isMenstruationRow -> {
                        PhoneSourceSelector(
                            options = menstruationProviderOptions,
                            selected = selectedMenstruationSource,
                            enabled = !effectiveGreyOut,
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
                            enabled = !effectiveGreyOut,
                            onSelected = { newSel ->
                                if (newSel != selectedNutritionSource) {
                                    selectedNutritionSource = newSel
                                    onSourceChange("nutrition", newSel.key, enabledBySupabase)
                                }
                            }
                        )
                    }

                    isAmbientNoiseRow -> {
                        // No selector for ambient noise
                    }

                    else -> {
                        val providerLabel = when (row.collectedByKind) {
                            CollectedByKind.PHONE -> "Phone"
                            CollectedByKind.MANUAL -> "Manual"
                            else -> ""
                        }
                        if (providerLabel.isNotEmpty()) {
                            Text(
                                providerLabel,
                                color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
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
                    enabled = !effectiveGreyOut && canToggleAmbient,
                    onCheckedChange = { newValue ->
                        if (effectiveGreyOut) return@Switch
                        if (isStressRow && !depsOkForStress) return@Switch

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
                    }
                )
            }
        }

        // Permission sub-rows
        if (isAmbientNoiseRow) {
            AmbientNoisePermissionRows(
                alpha = alpha,
                micPermissionGranted = micPermissionGranted,
                batteryOptimizationExempt = batteryOptimizationExempt,
                providerColWidth = providerColWidth,
                toggleColWidth = toggleColWidth,
                onRequestMicPermission = onRequestMicPermission,
                onRequestBatteryExemption = onRequestBatteryExemption
            )
        }

        if (isLocationRow) {
            LocationPermissionRow(
                alpha = alpha,
                backgroundLocationGranted = backgroundLocationGranted,
                providerColWidth = providerColWidth,
                toggleColWidth = toggleColWidth,
                onRequestBackgroundLocation = onRequestBackgroundLocation
            )
        }

        if (isScreenTimeRow || isLateNightScreenTimeRow) {
            ScreenTimePermissionRow(
                alpha = alpha,
                screenTimePermissionGranted = screenTimePermissionGranted,
                providerColWidth = providerColWidth,
                toggleColWidth = toggleColWidth,
                onRequestPermission = onRequestScreenTimePermission
            )
        }

        // Phone sleep permission sub-row (only when phone source is selected)
        if (isHybridPhoneSource) {
            ScreenTimePermissionRow(
                alpha = 1.0f,
                screenTimePermissionGranted = usageStatsPermissionGranted,
                providerColWidth = providerColWidth,
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



