package com.migraineme

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.core.content.ContextCompat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch

/**
 * Data Settings Screen
 *
 * Displays the 5 requested sections and lets the user toggle per-table collection.
 * - Phone-collected tables: Collected By fixed to "Phone"
 * - Wearable-collected tables: Collected By is a dropdown, limited to connected wearables
 * - If no wearable is connected, wearable rows are greyed out + disabled
 *
 * Settings are persisted locally (SharedPreferences). Workers will be gated in the next step.
 *
 * CHANGE:
 * - When a toggle is switched, also upsert metric_settings in Supabase via EdgeFunctionsService
 *   (client-side PostgREST; no edge function needed).
 *
 * ADDITION (Stress):
 * - Added a wearable metric row for `stress_index_daily`
 * - Stress toggle is only available when BOTH `hrv_daily` and `resting_hr_daily` are enabled
 *   for the same selected wearable source (e.g., WHOOP). If either is off, Stress is forced off.
 *
 * ADDITION (Refresh):
 * - After any toggle or wearable dropdown change, we force a screen refresh (repull) by re-reading
 *   stored preferences into Compose state.
 *
 * ADDITION (Ambient noise):
 * - Added phone metric row for `ambient_noise_samples`
 * - When enabled, schedules AmbientNoiseSampleWorker
 * - When disabled, cancels AmbientNoiseSampleWorker
 * - Defaults to ON (unless user explicitly turned it off)
 *
 * ADDITION (Screen time):
 * - Added phone metric row for `screen_time_daily`
 * - When enabled, schedules ScreenTimeDailySyncWorker
 * - When disabled, cancels ScreenTimeDailySyncWorker
 * - Requests PACKAGE_USAGE_STATS permission when user toggles ON
 * - Defaults to ON (unless user explicitly turned it off)
 */
@Composable
fun DataSettingsScreen() {
    val context = LocalContext.current.applicationContext
    val scrollState = rememberScrollState()

    // Microphone permission launcher for ambient noise
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            // Permission denied - user can't enable ambient noise without it
            // The toggle will remain OFF
        }
        // If granted, the DataRowUi will detect it and proceed
    }

    // Connected wearables (only WHOOP is implemented in the codebase right now)
    val whoopConnected = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        whoopConnected.value = WhoopTokenStore(context).load() != null
    }
    val connectedWearables = remember(whoopConnected.value) {
        buildList {
            if (whoopConnected.value) add(WearableSource.WHOOP)
        }
    }
    val hasAnyWearable = connectedWearables.isNotEmpty()

    val store = remember { DataSettingsStore(context) }

    // Increment to force "repull" (reload) from prefs after any change.
    var refreshTick by remember { mutableIntStateOf(0) }
    fun bumpRefresh() {
        refreshTick += 1
    }

    // Used to refresh permission-related UI state when user returns from Android Settings.
    var permissionRefreshTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionRefreshTick += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Ensure the ambient noise worker scheduling matches current setting (including default-on).
    // IMPORTANT: do not re-run this on refreshTick; rescheduling periodic work can keep pushing out the next run.
    LaunchedEffect(Unit) {
        val ambientOn = store.getActive(
            table = "ambient_noise_samples",
            wearable = null,
            defaultValue = true
        )

        val micGranted = MicrophonePermissionHelper.hasPermission(context)
        val batteryExempt = isBatteryOptimizationExempt(context)

        if (ambientOn && micGranted && batteryExempt) {
            AmbientNoiseSampleWorker.schedule(context)
            AmbientNoiseWatchdogWorker.schedule(context) // Ensure watchdog is running
        } else {
            AmbientNoiseSampleWorker.cancel(context)
            AmbientNoiseWatchdogWorker.cancel(context)
        }
    }

    // Screen time worker scheduling
    LaunchedEffect(Unit) {
        val screenTimeOn = store.getActive(
            table = "screen_time_daily",
            wearable = null,
            defaultValue = true
        )
        if (screenTimeOn && ScreenTimePermissionHelper.hasPermission(context)) {
            ScreenTimeDailySyncWorker.scheduleNext(context)
            ScreenTimeWatchdogWorker.schedule(context) // Ensure watchdog is running
        } else {
            ScreenTimeDailySyncWorker.cancel(context)
            ScreenTimeWatchdogWorker.cancel(context)
        }
    }

    // Location worker scheduling - always on
    LaunchedEffect(Unit) {
        LocationDailySyncWorker.scheduleNext(context)
        LocationWatchdogWorker.schedule(context)
    }

    // Define all rows (tables) grouped into the 5 sections you requested.
    val sections = remember {
        listOf(
            DataSection(
                title = "Sleep",
                rows = listOf(
                    wearableRow("sleep_duration_daily", "Wearable sleep sync"),
                    wearableRow("sleep_score_daily", "Wearable sleep sync"),
                    wearableRow("sleep_efficiency_daily", "Wearable sleep sync"),
                    wearableRow("sleep_stages_daily", "Wearable sleep sync"),
                    wearableRow("sleep_disturbances_daily", "Wearable sleep sync"),
                    wearableRow("fell_asleep_time_daily", "Wearable sleep sync"),
                    wearableRow("woke_up_time_daily", "Wearable sleep sync")
                )
            ),
            DataSection(
                title = "Physical Health",
                rows = listOf(
                    wearableRow("recovery_score_daily", "Wearable recovery sync"),
                    wearableRow("resting_hr_daily", "Wearable recovery sync"),
                    wearableRow("hrv_daily", "Wearable recovery sync"),
                    wearableRow("skin_temp_daily", "Wearable recovery sync"),
                    wearableRow("spo2_daily", "Wearable recovery sync"),
                    wearableRow("time_in_high_hr_zones_daily", "Wearable workout sync"),
                    // Present in schema; no collector in provided Kotlin yet.
                    DataRow(
                        table = "steps_daily",
                        collectedByKind = CollectedByKind.WEARABLE,
                        collectedByLabel = "Wearable steps sync (not implemented)",
                        defaultWearable = WearableSource.WHOOP
                    )
                )
            ),
            DataSection(
                title = "Mental Health",
                rows = listOf(
                    // Wearable-derived computed metric (standalone), gated by HRV + Resting HR.
                    wearableRow("stress_index_daily", "Computed mental stress (requires HRV + Resting HR)"),

                    // Phone-collected screen time
                    phoneRow("screen_time_daily", "Phone screen time tracking")
                )
            ),
            DataSection(
                title = "Environment",
                rows = listOf(
                    phoneRow("user_location_daily", "Phone GPS/location"),
                    // Read-only reference tables; shown for completeness.
                    referenceRow("cities", "Reference"),
                    referenceRow("weather_daily", "Reference"),
                    referenceRow("aqi_daily", "Reference"),
                    referenceRow("pollen_daily", "Reference"),
                    phoneRow("ambient_noise_samples", "Phone ambient noise samples")
                )
            ),
            DataSection(
                title = "Misc",
                rows = listOf(
                    // Placeholder for any future tables; keep section to satisfy the 5-section requirement.
                    referenceRow("app_config", "Reference (internal)")
                )
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text("Data Settings", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        for (section in sections) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(section.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))

                    for ((idx, row) in section.rows.withIndex()) {
                        val greyOutWearableRow = row.collectedByKind == CollectedByKind.WEARABLE && !hasAnyWearable
                        DataRowUi(
                            row = row,
                            store = store,
                            connectedWearables = connectedWearables,
                            enabled = true,
                            greyOut = greyOutWearableRow,
                            refreshTick = refreshTick,
                            micPermissionLauncher = micPermissionLauncher,
                            permissionRefreshTick = permissionRefreshTick,
                            onAnyChange = { bumpRefresh() }
                        )

                        if (idx != section.rows.lastIndex) {
                            Spacer(Modifier.height(10.dp))
                            Divider()
                        }
                    }

                    // Wearable hint per section if none connected and section contains wearable rows
                    val containsWearable = section.rows.any { it.collectedByKind == CollectedByKind.WEARABLE }
                    if (containsWearable && !hasAnyWearable) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Connect a wearable to enable these settings.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.alpha(0.7f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun DataRowUi(
    row: DataRow,
    store: DataSettingsStore,
    connectedWearables: List<WearableSource>,
    enabled: Boolean,
    greyOut: Boolean,
    refreshTick: Int,
    micPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    permissionRefreshTick: Int,
    onAnyChange: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val edge = remember { EdgeFunctionsService() }

    // Battery optimization dialog state
    var showBatteryOptDialog by remember { mutableStateOf(false) }

    // Screen time permission handling
    val isScreenTimeRow = row.table == "screen_time_daily" && row.collectedByKind == CollectedByKind.PHONE
    var showScreenTimePermissionDialog by remember { mutableStateOf(false) }
    val screenTimePermissionGranted = remember(refreshTick) {
        if (isScreenTimeRow) ScreenTimePermissionHelper.hasPermission(context) else true
    }

    val isLocationRow = row.table == "user_location_daily" && row.collectedByKind == CollectedByKind.PHONE
    val locationPermissionGranted = remember(refreshTick) {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        fine || coarse
    }

    val isAmbientNoiseRow = row.table == "ambient_noise_samples" && row.collectedByKind == CollectedByKind.PHONE
    val micPermissionGranted = remember(refreshTick, permissionRefreshTick) {
        if (isAmbientNoiseRow) MicrophonePermissionHelper.hasPermission(context) else true
    }
    val batteryOptimizationExempt = remember(refreshTick, permissionRefreshTick) {
        if (isAmbientNoiseRow) isBatteryOptimizationExempt(context) else true
    }

    // Wearable selection (dropdown) is stored per-table.
    // Phone/manual/reference rows don't use it.
    var selectedWearable by remember(row.table, refreshTick) {
        mutableStateOf(store.getSelectedWearable(row.table, row.defaultWearable))
    }

    // Active is stored per table + (wearable if applicable)
    var active by remember(row.table, refreshTick, selectedWearable) {
        mutableStateOf(
            if (isLocationRow) {
                true
            } else {
                store.getActive(
                    table = row.table,
                    wearable = if (row.collectedByKind == CollectedByKind.WEARABLE) selectedWearable else null,
                    defaultValue = defaultActiveFor(row)
                )
            }
        )
    }

    // Ambient noise requires BOTH prerequisites before it can remain ON.
    // If it was previously enabled (or defaulted ON) but prerequisites are missing, force it OFF to keep state consistent.
    LaunchedEffect(isAmbientNoiseRow, micPermissionGranted, batteryOptimizationExempt, refreshTick) {
        if (isAmbientNoiseRow) {
            val stored = store.getActive(
                table = row.table,
                wearable = null,
                defaultValue = defaultActiveFor(row)
            )
            if (stored && (!micPermissionGranted || !batteryOptimizationExempt)) {
                active = false
                store.setActive(table = row.table, wearable = null, value = false)

                scope.launch {
                    edge.upsertMetricSetting(
                        context = context,
                        metric = row.table,
                        enabled = false
                    )
                    AmbientNoiseSampleWorker.cancel(context)
                    AmbientNoiseWatchdogWorker.cancel(context)
                    onAnyChange()
                }
            }
        }
    }

    // If connected wearables change and current selection is no longer allowed, snap to first available.
    if (row.collectedByKind == CollectedByKind.WEARABLE) {
        val allowed = connectedWearables
        if (allowed.isEmpty()) {
            // keep selection but row disabled; no action needed
        } else if (!allowed.contains(selectedWearable)) {
            selectedWearable = allowed.first()
            store.setSelectedWearable(row.table, selectedWearable)
            active = store.getActive(
                table = row.table,
                wearable = selectedWearable,
                defaultValue = defaultActiveFor(row)
            )
        }
    }

    // Location is a prerequisite (Option A): ensure it cannot be turned off.
    // If an older preference stored it as OFF, force it back ON (local + Supabase) once.
    LaunchedEffect(isLocationRow, refreshTick) {
        if (isLocationRow) {
            val stored = store.getActive(
                table = row.table,
                wearable = null,
                defaultValue = true
            )
            if (!stored) {
                store.setActive(table = row.table, wearable = null, value = true)
                active = true
                scope.launch {
                    edge.upsertMetricSetting(
                        context = context,
                        metric = row.table,
                        enabled = true
                    )
                    onAnyChange()
                }
            }
        }
    }

    // Stress gating: only available if BOTH HRV + Resting HR are enabled for the same wearable source.
    val isStressRow = row.table == "stress_index_daily" && row.collectedByKind == CollectedByKind.WEARABLE
    val depsOkForStress =
        if (!isStressRow) {
            true
        } else {
            val hrvOn = store.getActive(
                table = "hrv_daily",
                wearable = selectedWearable,
                defaultValue = true
            )
            val rrOn = store.getActive(
                table = "resting_hr_daily",
                wearable = selectedWearable,
                defaultValue = true
            )
            hrvOn && rrOn
        }

    // If Stress is ON but dependencies turn OFF, force Stress OFF (local + Supabase).
    LaunchedEffect(isStressRow, depsOkForStress, selectedWearable, refreshTick) {
        if (isStressRow && !depsOkForStress && active) {
            active = false

            store.setActive(
                table = row.table,
                wearable = selectedWearable,
                value = false
            )

            scope.launch {
                edge.upsertMetricSetting(
                    context = context,
                    metric = row.table,
                    enabled = false
                )
                onAnyChange()
            }
        }
    }

    // Grey out Stress when deps are not satisfied.
    val effectiveGreyOut = greyOut || (isStressRow && !depsOkForStress)
    val alpha = if (effectiveGreyOut) 0.55f else 1.0f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Type column
        Column(modifier = Modifier.weight(0.46f)) {
            Text(row.table, style = MaterialTheme.typography.bodyMedium)
            if (row.collectedByLabel.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    row.collectedByLabel,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.alpha(0.75f)
                )
            }
            if (isLocationRow) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Permission: " + if (locationPermissionGranted) "Granted" else "Not granted",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.alpha(0.75f)
                )
            }
            if (isScreenTimeRow) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Permission: " + if (screenTimePermissionGranted) "Granted" else "Not granted",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (screenTimePermissionGranted)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.error,
                    modifier = Modifier.alpha(0.85f)
                )
            }
            if (isStressRow && !depsOkForStress) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Enable hrv_daily and resting_hr_daily to use stress.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.alpha(0.70f)
                )
            }

            if (isAmbientNoiseRow) {
                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Microphone permission",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.alpha(0.75f)
                    )
                    Switch(
                        checked = micPermissionGranted,
                        onCheckedChange = { newVal ->
                            if (newVal && !micPermissionGranted) {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !micPermissionGranted
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Battery optimization exemption",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.alpha(0.75f)
                    )
                    Switch(
                        checked = batteryOptimizationExempt,
                        onCheckedChange = { newVal ->
                            if (newVal && !batteryOptimizationExempt) {
                                showBatteryOptDialog = true
                            }
                        },
                        enabled = !batteryOptimizationExempt
                    )
                }

                if (!micPermissionGranted || !batteryOptimizationExempt) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Enable both switches to turn on ambient noise.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.alpha(0.70f)
                    )
                }
            }
        }

        // Collected By column
        Column(modifier = Modifier.weight(0.34f)) {
            when (row.collectedByKind) {
                CollectedByKind.PHONE -> {
                    Text("Phone", style = MaterialTheme.typography.bodyMedium)
                }

                CollectedByKind.MANUAL -> {
                    Text("User", style = MaterialTheme.typography.bodyMedium)
                }

                CollectedByKind.REFERENCE -> {
                    Text("Reference", style = MaterialTheme.typography.bodyMedium)
                }

                CollectedByKind.WEARABLE -> {
                    val options = connectedWearables.map { it.label }
                    val selectedIndex = options.indexOf(selectedWearable.label).coerceAtLeast(0)

                    if (options.isEmpty()) {
                        Text("No wearable", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        // Reuse existing dropdown helper to stay consistent with the codebase.
                        AppDropdown(
                            options = options,
                            selectedIndex = selectedIndex,
                            onSelected = { idx ->
                                val newSel = connectedWearables.getOrNull(idx) ?: return@AppDropdown
                                selectedWearable = newSel
                                store.setSelectedWearable(row.table, newSel)

                                // Reload active for the new wearable/source
                                active = store.getActive(
                                    table = row.table,
                                    wearable = newSel,
                                    defaultValue = defaultActiveFor(row)
                                )

                                onAnyChange()
                            }
                        )
                    }
                }
            }
        }

        // Active column
        Column(modifier = Modifier.weight(0.20f)) {
            val canToggleBase = enabled && row.collectedByKind != CollectedByKind.REFERENCE && !isLocationRow
            val canToggle =
                canToggleBase &&
                        (!isStressRow || depsOkForStress) &&
                        (!isAmbientNoiseRow || (micPermissionGranted && batteryOptimizationExempt))

            Row {
                Switch(
                    checked = active,
                    onCheckedChange = { new ->
                        if (!canToggle) return@Switch

                        if (isAmbientNoiseRow && new && (!micPermissionGranted || !batteryOptimizationExempt)) return@Switch

                        active = new

                        // Local persistence (existing)
                        store.setActive(
                            table = row.table,
                            wearable = if (row.collectedByKind == CollectedByKind.WEARABLE) selectedWearable else null,
                            value = new
                        )

                        scope.launch {
                            if (isAmbientNoiseRow) {
                                if (new) {
                                    // Prerequisites (mic + battery) are required before enabling ambient noise.
                                    // At this point the main switch is only enabled when both are satisfied.
                                    edge.upsertMetricSetting(
                                        context = context,
                                        metric = row.table,
                                        enabled = true
                                    )
                                    AmbientNoiseSampleWorker.schedule(context)
                                    AmbientNoiseWatchdogWorker.schedule(context)
                                } else {
                                    edge.upsertMetricSetting(
                                        context = context,
                                        metric = row.table,
                                        enabled = false
                                    )
                                    AmbientNoiseSampleWorker.cancel(context)
                                    AmbientNoiseWatchdogWorker.cancel(context)
                                }

                                onAnyChange()
                                return@launch
                            }

                            edge.upsertMetricSetting(
                                context = context,
                                metric = row.table,
                                enabled = new
                            )

                            // If HRV or Resting HR is turned OFF, force stress_index_daily OFF (local + Supabase),
                            // for the same selected wearable source as the stress row.
                            if (!new && row.collectedByKind == CollectedByKind.WEARABLE &&
                                (row.table == "hrv_daily" || row.table == "resting_hr_daily")
                            ) {
                                val stressWearable = store.getSelectedWearable("stress_index_daily", WearableSource.WHOOP)
                                if (stressWearable == selectedWearable) {
                                    val stressCurrentlyOn = store.getActive(
                                        table = "stress_index_daily",
                                        wearable = stressWearable,
                                        defaultValue = true
                                    )
                                    if (stressCurrentlyOn) {
                                        store.setActive(
                                            table = "stress_index_daily",
                                            wearable = stressWearable,
                                            value = false
                                        )
                                        edge.upsertMetricSetting(
                                            context = context,
                                            metric = "stress_index_daily",
                                            enabled = false
                                        )
                                    }
                                }
                            }

                            // Screen time worker scheduling
                            if (row.collectedByKind == CollectedByKind.PHONE && row.table == "screen_time_daily") {
                                if (new) {
                                    // Check permission before enabling
                                    if (!ScreenTimePermissionHelper.hasPermission(context)) {
                                        // Show permission dialog
                                        showScreenTimePermissionDialog = true
                                        // Don't enable yet - wait for permission
                                        active = false
                                        store.setActive(table = row.table, wearable = null, value = false)
                                    } else {
                                        // Permission granted - schedule worker
                                        ScreenTimeDailySyncWorker.scheduleNext(context)
                                        ScreenTimeWatchdogWorker.schedule(context) // Start watchdog!
                                    }
                                } else {
                                    ScreenTimeDailySyncWorker.cancel(context)
                                    ScreenTimeWatchdogWorker.cancel(context) // Stop watchdog!
                                }
                            }

                            onAnyChange()
                        }
                    },
                    enabled = canToggle
                )
                Spacer(Modifier.width(2.dp))
            }
        }
    }

    // Screen time permission dialog
    if (showScreenTimePermissionDialog) {
        ScreenTimePermissionDialog(
            onDismiss = { showScreenTimePermissionDialog = false },
            onOpenSettings = {
                ScreenTimePermissionHelper.openUsageAccessSettings(context)
                showScreenTimePermissionDialog = false
            }
        )
    }

    // Battery optimization dialog
    if (showBatteryOptDialog) {
        BatteryOptimizationDialog(
            onDismiss = {
                showBatteryOptDialog = false
                BatteryOptimizationHelper.markAsAsked(context)
            },
            onOpenSettings = {
                BatteryOptimizationHelper.requestBatteryOptimizationExemption(context)
                BatteryOptimizationHelper.markAsAsked(context)
                showBatteryOptDialog = false
            }
        )
    }
}

@Composable
private fun ScreenTimePermissionDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Screen Time Permission") },
        text = {
            Text(
                "To track your screen time, MigraineMe needs access to usage statistics.\n\n" +
                        "This permission allows the app to see how much time you spend on your phone each day.\n\n" +
                        "You'll be taken to Android Settings where you can grant this permission."
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

private fun defaultActiveFor(row: DataRow): Boolean {
    return when (row.collectedByKind) {
        CollectedByKind.REFERENCE -> false
        else -> true
    }
}

/** Helpers to build rows consistently */
private fun wearableRow(table: String, label: String): DataRow =
    DataRow(
        table = table,
        collectedByKind = CollectedByKind.WEARABLE,
        collectedByLabel = label,
        defaultWearable = WearableSource.WHOOP
    )

private fun phoneRow(table: String, label: String): DataRow =
    DataRow(
        table = table,
        collectedByKind = CollectedByKind.PHONE,
        collectedByLabel = label
    )

private fun manualRow(table: String, label: String): DataRow =
    DataRow(
        table = table,
        collectedByKind = CollectedByKind.MANUAL,
        collectedByLabel = label
    )

private fun referenceRow(table: String, label: String): DataRow =
    DataRow(
        table = table,
        collectedByKind = CollectedByKind.REFERENCE,
        collectedByLabel = label
    )

/** Data model */
private data class DataSection(
    val title: String,
    val rows: List<DataRow>
)

private data class DataRow(
    val table: String,
    val collectedByKind: CollectedByKind,
    val collectedByLabel: String,
    val defaultWearable: WearableSource? = null
)

private enum class CollectedByKind {
    PHONE,
    WEARABLE,
    MANUAL,
    REFERENCE
}

private enum class WearableSource(val key: String, val label: String) {
    WHOOP("whoop", "WHOOP")
}

private fun isBatteryOptimizationExempt(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Local persistence for Data settings.
 * Stores:
 * - Selected wearable per table: data_source_<table> = whoop
 * - Active per table:
 *    - phone/manual/reference: data_active_<table>
 *    - wearable: data_active_<table>_<source>
 */
private class DataSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("data_settings", Context.MODE_PRIVATE)

    fun getSelectedWearable(table: String, fallback: WearableSource?): WearableSource {
        val key = "data_source_$table"
        val raw = prefs.getString(key, null) ?: fallback?.key ?: WearableSource.WHOOP.key
        return WearableSource.values().firstOrNull { it.key == raw } ?: WearableSource.WHOOP
    }

    fun setSelectedWearable(table: String, wearable: WearableSource) {
        val key = "data_source_$table"
        prefs.edit().putString(key, wearable.key).apply()
    }

    fun getActive(table: String, wearable: WearableSource?, defaultValue: Boolean): Boolean {
        val key = activeKey(table, wearable)
        return prefs.getBoolean(key, defaultValue)
    }

    fun setActive(table: String, wearable: WearableSource?, value: Boolean) {
        val key = activeKey(table, wearable)
        prefs.edit().putBoolean(key, value).apply()
    }

    private fun activeKey(table: String, wearable: WearableSource?): String {
        return if (wearable == null) {
            "data_active_$table"
        } else {
            "data_active_${table}_${wearable.key}"
        }
    }
}
