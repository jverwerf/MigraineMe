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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data Settings Screen - Uses Supabase as source of truth
 */
@Composable
fun DataSettingsScreen() {
    val context = LocalContext.current.applicationContext
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val edge = remember { EdgeFunctionsService() }

    // Supabase metric settings (source of truth)
    val metricSettingsMap = remember { mutableStateOf<Map<String, EdgeFunctionsService.MetricSettingResponse>>(emptyMap()) }
    val settingsLoading = remember { mutableStateOf(true) }

    // Load settings from Supabase on start
    LaunchedEffect(Unit) {
        settingsLoading.value = true
        withContext(Dispatchers.IO) {
            try {
                val settings = edge.getMetricSettings(context)
                android.util.Log.d("DataSettings", "=== RAW SETTINGS FROM SUPABASE ===")
                settings.forEach { setting ->
                    android.util.Log.d("DataSettings", "  metric=${setting.metric}, enabled=${setting.enabled}, preferredSource=${setting.preferredSource}")
                }

                // Create map with key format: "metric_preferredSource"
                metricSettingsMap.value = settings.associateBy {
                    val key = if (it.preferredSource != null) {
                        "${it.metric}_${it.preferredSource}"
                    } else {
                        "${it.metric}_null"
                    }
                    android.util.Log.d("DataSettings", "  Created key: $key for metric ${it.metric}")
                    key
                }
                android.util.Log.d("DataSettings", "=== FINAL MAP ===")
                metricSettingsMap.value.forEach { (key, value) ->
                    android.util.Log.d("DataSettings", "  $key -> enabled=${value.enabled}")
                }
                android.util.Log.d("DataSettings", "Loaded ${settings.size} settings from Supabase")
            } catch (e: Exception) {
                android.util.Log.e("DataSettings", "Failed to load settings from Supabase: ${e.message}", e)
            }
        }
        settingsLoading.value = false
    }

    // Microphone permission launcher for ambient noise
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            // Permission denied - user can't enable ambient noise without it
        }
    }

    // Connected wearables
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

    var refreshTick by remember { mutableIntStateOf(0) }
    fun bumpRefresh() {
        refreshTick += 1
    }

    // Reload settings when refreshTick changes
    LaunchedEffect(refreshTick) {
        if (refreshTick > 0) {
            withContext(Dispatchers.IO) {
                try {
                    val settings = edge.getMetricSettings(context)
                    metricSettingsMap.value = settings.associateBy {
                        if (it.preferredSource != null) {
                            "${it.metric}_${it.preferredSource}"
                        } else {
                            "${it.metric}_null"
                        }
                    }
                    android.util.Log.d("DataSettings", "Reloaded ${settings.size} settings from Supabase")
                } catch (e: Exception) {
                    android.util.Log.e("DataSettings", "Failed to reload settings: ${e.message}")
                }
            }
        }
    }

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

    // Reload settings from Supabase when screen resumes
    LaunchedEffect(permissionRefreshTick) {
        if (permissionRefreshTick > 0) {
            withContext(Dispatchers.IO) {
                try {
                    val settings = edge.getMetricSettings(context)
                    metricSettingsMap.value = settings.associateBy {
                        if (it.preferredSource != null) {
                            "${it.metric}_${it.preferredSource}"
                        } else {
                            "${it.metric}_null"
                        }
                    }
                    android.util.Log.d("DataSettings", "Reloaded on resume: ${settings.size} settings from Supabase")
                } catch (e: Exception) {
                    android.util.Log.e("DataSettings", "Failed to reload on resume: ${e.message}")
                }
            }
        }
    }

    // Health Connect connection state (Diet permissions are tracked separately)
    val healthConnectNutritionConnected = remember { mutableStateOf(false) }
    val healthConnectMenstruationConnected = remember { mutableStateOf(false) }
    LaunchedEffect(permissionRefreshTick) {
        healthConnectNutritionConnected.value = hasHealthConnectNutritionPermission(context)
        healthConnectMenstruationConnected.value = hasHealthConnectMenstruationPermission(context)
    }

    // ===== MENSTRUATION SETTINGS STATE =====
    val menstruationSettings = remember { mutableStateOf<MenstruationSettings?>(null) }
    val showMenstruationSetupDialog = remember { mutableStateOf(false) }
    val showMenstruationEditDialog = remember { mutableStateOf(false) }
    var menstruationRefreshTick by remember { mutableIntStateOf(0) }

    // Load menstruation settings when Menstruation permission is granted
    LaunchedEffect(healthConnectMenstruationConnected.value, menstruationRefreshTick) {
        if (healthConnectMenstruationConnected.value) {
            withContext(Dispatchers.IO) {
                try {
                    val accessToken = SessionStore.getValidAccessToken(context)
                    if (accessToken != null) {
                        val service = SupabaseMenstruationService(context)
                        menstruationSettings.value = service.getSettings(accessToken)
                        android.util.Log.d("DataSettings", "Loaded menstruation settings: ${menstruationSettings.value}")
                    } else {
                        android.util.Log.w("DataSettings", "No access token available")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DataSettings", "Failed to load menstruation settings: ${e.message}")
                }
            }
        }
    }

    // Auto-enable screen time when permission is granted
    LaunchedEffect(permissionRefreshTick) {
        if (permissionRefreshTick > 0) {
            if (ScreenTimePermissionHelper.hasPermission(context)) {
                // Check Supabase state
                val settingKey = "screen_time_daily_null"
                val screenTimeOn = metricSettingsMap.value[settingKey]?.enabled ?: true

                if (!screenTimeOn) {
                    android.util.Log.d("DataSettings", "Auto-enabling screen time after permission grant")

                    ScreenTimeDailySyncWorker.scheduleNext(context)
                    ScreenTimeWatchdogWorker.schedule(context)

                    val accessToken = SessionStore.getValidAccessToken(context)
                    if (accessToken != null) {
                        scope.launch(Dispatchers.IO) {
                            try {
                                edge.upsertMetricSetting(
                                    context = context,
                                    metric = "screen_time_daily",
                                    enabled = true,
                                    preferredSource = null
                                )
                            } catch (e: Exception) {
                                android.util.Log.e("DataSettings", "Supabase sync failed: ${e.message}")
                            }
                        }
                    }

                    bumpRefresh()

                    android.widget.Toast.makeText(
                        context,
                        "Screen time tracking enabled!",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // Ambient noise worker scheduling
    LaunchedEffect(metricSettingsMap.value) {
        val ambientKey = "ambient_noise_samples_null"
        val ambientOn = metricSettingsMap.value[ambientKey]?.enabled ?: true

        val micGranted = MicrophonePermissionHelper.hasPermission(context)
        val batteryExempt = isBatteryOptimizationExempt(context)

        if (ambientOn && micGranted && batteryExempt) {
            AmbientNoiseSampleWorker.schedule(context)
            AmbientNoiseWatchdogWorker.schedule(context)
        } else {
            AmbientNoiseSampleWorker.cancel(context)
            AmbientNoiseWatchdogWorker.cancel(context)
        }
    }

    // Screen time worker scheduling
    LaunchedEffect(metricSettingsMap.value) {
        val screenTimeKey = "screen_time_daily_null"
        val screenTimeOn = metricSettingsMap.value[screenTimeKey]?.enabled ?: true

        if (screenTimeOn && ScreenTimePermissionHelper.hasPermission(context)) {
            ScreenTimeDailySyncWorker.scheduleNext(context)
            ScreenTimeWatchdogWorker.schedule(context)
        } else {
            ScreenTimeDailySyncWorker.cancel(context)
            ScreenTimeWatchdogWorker.cancel(context)
        }
    }

    // Location worker scheduling
    LaunchedEffect(Unit) {
        LocationDailySyncWorker.scheduleNext(context)
        LocationWatchdogWorker.schedule(context)
    }

    val sections = remember(healthConnectNutritionConnected.value, healthConnectMenstruationConnected.value) {
        buildList {
            add(
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
                )
            )
            add(
                DataSection(
                    title = "Physical Health",
                    rows = listOf(
                        wearableRow("recovery_score_daily", "Wearable recovery sync"),
                        wearableRow("resting_hr_daily", "Wearable recovery sync"),
                        wearableRow("hrv_daily", "Wearable recovery sync"),
                        wearableRow("skin_temp_daily", "Wearable recovery sync"),
                        wearableRow("spo2_daily", "Wearable recovery sync"),
                        wearableRow("time_in_high_hr_zones_daily", "Wearable workout sync"),
                        DataRow(
                            table = "steps_daily",
                            collectedByKind = CollectedByKind.WEARABLE,
                            collectedByLabel = "Wearable steps sync (not implemented)",
                            defaultWearable = WearableSource.WHOOP
                        )
                    )
                )
            )
            add(
                DataSection(
                    title = "Mental Health",
                    rows = listOf(
                        wearableRow("stress_index_daily", "Computed mental stress (requires HRV + Resting HR)"),
                        phoneRow("screen_time_daily", "Phone screen time tracking")
                    )
                )
            )
            add(
                DataSection(
                    title = "Environment",
                    rows = listOf(
                        phoneRow("user_location_daily", "Phone GPS/location"),
                        referenceRow("cities", "Reference"),
                        referenceRow("weather_daily", "Reference"),
                        referenceRow("aqi_daily", "Reference"),
                        referenceRow("pollen_daily", "Reference"),
                        phoneRow("ambient_noise_samples", "Phone ambient noise samples")
                    )
                )
            )

            add(
                DataSection(
                    title = "Diet",
                    rows = listOf(
                        phoneRow("nutrition", "Nutrition (Health Connect)"),
                        phoneRow("menstruation", "Menstruation (Health Connect)")
                    )
                )
            )

            add(
                DataSection(
                    title = "Misc",
                    rows = listOf(
                        referenceRow("app_config", "Reference (internal)")
                    )
                )
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text("Data Settings", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        if (settingsLoading.value) {
            Text("Loading settings...", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
        }

        for (section in sections) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(section.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))

                    for ((idx, row) in section.rows.withIndex()) {
                        val greyOutWearableRow = row.collectedByKind == CollectedByKind.WEARABLE && !hasAnyWearable
                        val greyOutDietRow =
                            row.collectedByKind == CollectedByKind.PHONE && (
                                    (row.table == "nutrition" && !healthConnectNutritionConnected.value) ||
                                            (row.table == "menstruation" && !healthConnectMenstruationConnected.value)
                                    )

                        DataRowUi(
                            row = row,
                            store = store,
                            connectedWearables = connectedWearables,
                            metricSettingsMap = metricSettingsMap.value,
                            enabled = true,
                            greyOut = greyOutWearableRow || greyOutDietRow,
                            refreshTick = refreshTick,
                            micPermissionLauncher = micPermissionLauncher,
                            permissionRefreshTick = permissionRefreshTick,
                            onAnyChange = { bumpRefresh() },
                            menstruationSettings = menstruationSettings.value,
                            showMenstruationSetupDialog = showMenstruationSetupDialog,
                            showMenstruationEditDialog = showMenstruationEditDialog
                        )

                        if (idx != section.rows.lastIndex) {
                            Spacer(Modifier.height(10.dp))
                            Divider()
                        }
                    }

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

    // ===== MENSTRUATION DIALOGS =====

    // Setup dialog (first time)
    if (showMenstruationSetupDialog.value) {
        MenstruationSetupDialog(
            onConfirm = { lastDate, avgCycle, autoUpdate ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val accessToken = SessionStore.getValidAccessToken(context)
                        if (accessToken != null) {
                            val service = SupabaseMenstruationService(context)

                            service.updateSettings(
                                accessToken = accessToken,
                                lastMenstruationDate = lastDate,
                                avgCycleLength = avgCycle,
                                autoUpdateAverage = autoUpdate
                            )

                            menstruationSettings.value = MenstruationSettings(
                                lastMenstruationDate = lastDate,
                                avgCycleLength = avgCycle,
                                autoUpdateAverage = autoUpdate
                            )

                            // Toggle ON via MetricToggleHelper
                            MetricToggleHelper.toggle(context, "menstruation", true)

                            edge.upsertMetricSetting(
                                context = context,
                                metric = "menstruation",
                                enabled = true,
                                preferredSource = null
                            )

                            withContext(Dispatchers.Main) {
                                showMenstruationSetupDialog.value = false
                                menstruationRefreshTick++
                                bumpRefresh()

                                android.widget.Toast.makeText(
                                    context,
                                    "Menstruation tracking enabled!",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("DataSettings", "Failed to save menstruation settings: ${e.message}", e)
                    }
                }
            },
            onDismiss = {
                showMenstruationSetupDialog.value = false
                bumpRefresh()
            }
        )
    }

    // Edit dialog
    if (showMenstruationEditDialog.value && menstruationSettings.value != null) {
        MenstruationEditDialog(
            currentSettings = menstruationSettings.value!!,
            onConfirm = { newLastDate, newAvgCycle ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val accessToken = SessionStore.getValidAccessToken(context)
                        if (accessToken != null) {
                            val service = SupabaseMenstruationService(context)
                            val current = menstruationSettings.value!!

                            service.updateSettings(
                                accessToken = accessToken,
                                lastMenstruationDate = newLastDate,
                                avgCycleLength = newAvgCycle,
                                autoUpdateAverage = current.autoUpdateAverage
                            )

                            menstruationSettings.value = current.copy(
                                lastMenstruationDate = newLastDate,
                                avgCycleLength = newAvgCycle
                            )

                            withContext(Dispatchers.Main) {
                                showMenstruationEditDialog.value = false
                                menstruationRefreshTick++

                                android.widget.Toast.makeText(
                                    context,
                                    "Settings updated!",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("DataSettings", "Failed to update settings: ${e.message}", e)
                    }
                }
            },
            onDismiss = {
                showMenstruationEditDialog.value = false
            }
        )
    }
}

@Composable
private fun DataRowUi(
    row: DataRow,
    store: DataSettingsStore,
    connectedWearables: List<WearableSource>,
    metricSettingsMap: Map<String, EdgeFunctionsService.MetricSettingResponse>,
    enabled: Boolean,
    greyOut: Boolean,
    refreshTick: Int,
    micPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    permissionRefreshTick: Int,
    onAnyChange: () -> Unit,
    menstruationSettings: MenstruationSettings? = null,
    showMenstruationSetupDialog: androidx.compose.runtime.MutableState<Boolean>? = null,
    showMenstruationEditDialog: androidx.compose.runtime.MutableState<Boolean>? = null
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val edge = remember { EdgeFunctionsService() }

    var showBatteryOptDialog by remember { mutableStateOf(false) }

    val isScreenTimeRow = row.table == "screen_time_daily" && row.collectedByKind == CollectedByKind.PHONE
    var showScreenTimePermissionDialog by remember { mutableStateOf(false) }
    val screenTimePermissionGranted = remember(refreshTick, permissionRefreshTick) {
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

    val isNutritionRow = row.table == "nutrition" && row.collectedByKind == CollectedByKind.PHONE
    val isMenstruationRow = row.table == "menstruation" && row.collectedByKind == CollectedByKind.PHONE

    var nutritionPermissionGranted by remember { mutableStateOf(false) }
    var menstruationPermissionGranted by remember { mutableStateOf(false) }

    LaunchedEffect(isNutritionRow, isMenstruationRow, permissionRefreshTick) {
        if (isNutritionRow) {
            nutritionPermissionGranted = hasHealthConnectNutritionPermission(context)
        }
        if (isMenstruationRow) {
            menstruationPermissionGranted = hasHealthConnectMenstruationPermission(context)
        }
    }

    val dietPermissionGranted =
        when {
            isNutritionRow -> nutritionPermissionGranted
            isMenstruationRow -> menstruationPermissionGranted
            else -> true
        }

    // Read selected wearable from Supabase first, fallback to local
    var selectedWearable by remember(row.table, refreshTick, metricSettingsMap) {
        // First check Supabase for preferred_source
        val supabaseSource = metricSettingsMap.values
            .firstOrNull { it.metric == row.table && it.preferredSource != null }
            ?.preferredSource
            ?.let { sourceKey ->
                WearableSource.values().firstOrNull { it.key == sourceKey }
            }

        mutableStateOf(supabaseSource ?: row.defaultWearable ?: WearableSource.WHOOP)
    }

    // Read active state from Supabase
    var active by remember(row.table, refreshTick, selectedWearable, metricSettingsMap) {
        mutableStateOf(
            if (isLocationRow) {
                true
            } else {
                // Get from Supabase
                val settingKey = if (row.collectedByKind == CollectedByKind.WEARABLE) {
                    "${row.table}_${selectedWearable.key}"
                } else {
                    "${row.table}_null"
                }

                val value = metricSettingsMap[settingKey]?.enabled ?: defaultActiveFor(row)
                android.util.Log.d("DataSettings", "Row ${row.table}: looking for key='$settingKey', found=${metricSettingsMap[settingKey]}, using value=$value")
                value
            }
        )
    }

    // Auto-disable if permissions lost
    LaunchedEffect(isAmbientNoiseRow, micPermissionGranted, batteryOptimizationExempt, refreshTick) {
        if (isAmbientNoiseRow) {
            val settingKey = "ambient_noise_samples_null"
            val stored = metricSettingsMap[settingKey]?.enabled ?: true

            if (stored && (!micPermissionGranted || !batteryOptimizationExempt)) {
                active = false

                scope.launch(Dispatchers.IO) {
                    edge.upsertMetricSetting(
                        context = context,
                        metric = row.table,
                        enabled = false,
                        preferredSource = null
                    )
                    AmbientNoiseSampleWorker.cancel(context)
                    AmbientNoiseWatchdogWorker.cancel(context)
                    onAnyChange()
                }
            }
        }
    }

    // Auto-disable if diet permission lost
    // REMOVED: This was causing a race condition where toggles would be set to false
    // before permission check completed. Grey-out is sufficient.

    // Update selected wearable if not in allowed list
    if (row.collectedByKind == CollectedByKind.WEARABLE) {
        val allowed = connectedWearables
        if (allowed.isEmpty()) {
            // keep selection but row disabled
        } else if (!allowed.contains(selectedWearable)) {
            selectedWearable = allowed.first()

            // Update Supabase with new wearable
            scope.launch(Dispatchers.IO) {
                edge.upsertMetricSetting(
                    context = context,
                    metric = row.table,
                    enabled = active,
                    preferredSource = selectedWearable.key
                )
            }
        }
    }

    // Location row always ON
    LaunchedEffect(isLocationRow, refreshTick) {
        if (isLocationRow) {
            val settingKey = "user_location_daily_null"
            val stored = metricSettingsMap[settingKey]?.enabled ?: true

            if (!stored) {
                active = true
                scope.launch(Dispatchers.IO) {
                    edge.upsertMetricSetting(
                        context = context,
                        metric = row.table,
                        enabled = true,
                        preferredSource = null
                    )
                    onAnyChange()
                }
            }
        }
    }

    // Stress dependency check
    val isStressRow = row.table == "stress_index_daily" && row.collectedByKind == CollectedByKind.WEARABLE
    val depsOkForStress =
        if (!isStressRow) {
            true
        } else {
            val hrvKey = "hrv_daily_${selectedWearable.key}"
            val rrKey = "resting_hr_daily_${selectedWearable.key}"
            val hrvOn = metricSettingsMap[hrvKey]?.enabled ?: true
            val rrOn = metricSettingsMap[rrKey]?.enabled ?: true
            hrvOn && rrOn
        }

    LaunchedEffect(isStressRow, depsOkForStress, selectedWearable, refreshTick) {
        if (isStressRow && !depsOkForStress && active) {
            active = false

            scope.launch(Dispatchers.IO) {
                edge.upsertMetricSetting(
                    context = context,
                    metric = row.table,
                    enabled = false,
                    preferredSource = selectedWearable.key
                )
                onAnyChange()
            }
        }
    }

    val effectiveGreyOut = greyOut || (isStressRow && !depsOkForStress)
    val alpha = if (effectiveGreyOut) 0.55f else 1.0f

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(alpha),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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

                if (isNutritionRow || isMenstruationRow) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Health Connect: " + if (dietPermissionGranted) "Connected" else "Not connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (dietPermissionGranted)
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

            Column(modifier = Modifier.weight(0.34f)) {
                when (row.collectedByKind) {
                    CollectedByKind.PHONE -> {
                        if (isNutritionRow || isMenstruationRow) {
                            Text("Health Connect", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text("Phone", style = MaterialTheme.typography.bodyMedium)
                        }
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
                            AppDropdown(
                                options = options,
                                selectedIndex = selectedIndex,
                                onSelected = { idx ->
                                    val newSel = connectedWearables.getOrNull(idx) ?: return@AppDropdown
                                    selectedWearable = newSel

                                    // Update Supabase with new preferred source
                                    scope.launch(Dispatchers.IO) {
                                        edge.upsertMetricSetting(
                                            context = context,
                                            metric = row.table,
                                            enabled = active,
                                            preferredSource = newSel.key
                                        )
                                    }

                                    onAnyChange()
                                }
                            )
                        }
                    }
                }
            }

            Column(modifier = Modifier.weight(0.20f)) {
                val canToggleBase = enabled && row.collectedByKind != CollectedByKind.REFERENCE && !isLocationRow
                val canToggle =
                    canToggleBase &&
                            !effectiveGreyOut &&
                            (!isAmbientNoiseRow || (micPermissionGranted && batteryOptimizationExempt))

                Row {
                    Switch(
                        checked = active,
                        onCheckedChange = { new ->
                            if (!canToggle) return@Switch

                            if (isAmbientNoiseRow && new && (!micPermissionGranted || !batteryOptimizationExempt)) return@Switch

                            active = new

                            scope.launch(Dispatchers.IO) {
                                if (isAmbientNoiseRow) {
                                    MetricToggleHelper.toggle(context, row.table, new)

                                    edge.upsertMetricSetting(
                                        context = context,
                                        metric = row.table,
                                        enabled = new,
                                        preferredSource = null
                                    )

                                    onAnyChange()
                                    return@launch
                                }

                                if (isNutritionRow) {
                                    MetricToggleHelper.toggle(context, row.table, new)

                                    edge.upsertMetricSetting(
                                        context = context,
                                        metric = row.table,
                                        enabled = new,
                                        preferredSource = null
                                    )

                                    onAnyChange()
                                    return@launch
                                }

                                if (isMenstruationRow) {
                                    if (new) {
                                        if (menstruationSettings == null && showMenstruationSetupDialog != null) {
                                            showMenstruationSetupDialog.value = true
                                        } else {
                                            MetricToggleHelper.toggle(context, row.table, true)

                                            edge.upsertMetricSetting(
                                                context = context,
                                                metric = row.table,
                                                enabled = true,
                                                preferredSource = null
                                            )
                                        }
                                    } else {
                                        MetricToggleHelper.toggle(context, row.table, false)

                                        edge.upsertMetricSetting(
                                            context = context,
                                            metric = row.table,
                                            enabled = false,
                                            preferredSource = null
                                        )
                                    }

                                    onAnyChange()
                                    return@launch
                                }

                                // Generic metric (wearable or other phone metrics)
                                edge.upsertMetricSetting(
                                    context = context,
                                    metric = row.table,
                                    enabled = new,
                                    preferredSource = if (row.collectedByKind == CollectedByKind.WEARABLE) selectedWearable.key else null
                                )

                                // Stress dependency auto-disable
                                if (!new && row.collectedByKind == CollectedByKind.WEARABLE &&
                                    (row.table == "hrv_daily" || row.table == "resting_hr_daily")
                                ) {
                                    val stressKey = "stress_index_daily_${selectedWearable.key}"
                                    val stressCurrentlyOn = metricSettingsMap[stressKey]?.enabled ?: false

                                    if (stressCurrentlyOn) {
                                        edge.upsertMetricSetting(
                                            context = context,
                                            metric = "stress_index_daily",
                                            enabled = false,
                                            preferredSource = selectedWearable.key
                                        )
                                    }
                                }

                                // Screen time special handling
                                if (row.collectedByKind == CollectedByKind.PHONE && row.table == "screen_time_daily") {
                                    if (new) {
                                        if (!ScreenTimePermissionHelper.hasPermission(context)) {
                                            showScreenTimePermissionDialog = true
                                            active = false
                                        } else {
                                            ScreenTimeDailySyncWorker.scheduleNext(context)
                                            ScreenTimeWatchdogWorker.schedule(context)
                                        }
                                    } else {
                                        ScreenTimeDailySyncWorker.cancel(context)
                                        ScreenTimeWatchdogWorker.cancel(context)
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

        // ===== MENSTRUATION EXPANDED SETTINGS =====
        if (isMenstruationRow && active && menstruationSettings != null && showMenstruationEditDialog != null) {
            Spacer(Modifier.height(12.dp))

            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {

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
                                menstruationSettings.lastMenstruationDate?.toString() ?: "Not set",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        TextButton(onClick = { showMenstruationEditDialog.value = true }) {
                            Text("Edit")
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Divider()
                    Spacer(Modifier.height(8.dp))

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
                                "${menstruationSettings.avgCycleLength} days",
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
                        TextButton(onClick = { showMenstruationEditDialog.value = true }) {
                            Text("Edit")
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Divider()
                    Spacer(Modifier.height(8.dp))

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
                            checked = menstruationSettings.autoUpdateAverage,
                            onCheckedChange = { newValue ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val accessToken = SessionStore.getValidAccessToken(context)
                                        if (accessToken != null) {
                                            val service = SupabaseMenstruationService(context)
                                            service.updateSettings(
                                                accessToken = accessToken,
                                                lastMenstruationDate = menstruationSettings.lastMenstruationDate,
                                                avgCycleLength = menstruationSettings.avgCycleLength,
                                                autoUpdateAverage = newValue
                                            )
                                            android.util.Log.d("DataSettings", "Auto-update set to: $newValue")
                                            onAnyChange()
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("DataSettings", "Failed to update auto-update: ${e.message}")
                                    }
                                }
                            }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    val nextPeriod = menstruationSettings.lastMenstruationDate?.plusDays(
                        menstruationSettings.avgCycleLength.toLong()
                    )

                    if (nextPeriod != null) {
                        Text(
                            "Next expected: $nextPeriod",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    }

    if (showScreenTimePermissionDialog) {
        ScreenTimePermissionDialog(
            onDismiss = { showScreenTimePermissionDialog = false },
            onOpenSettings = {
                ScreenTimePermissionHelper.openUsageAccessSettings(context)
                showScreenTimePermissionDialog = false
            }
        )
    }

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

private fun referenceRow(table: String, label: String): DataRow =
    DataRow(
        table = table,
        collectedByKind = CollectedByKind.REFERENCE,
        collectedByLabel = label
    )

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

internal enum class WearableSource(val key: String, val label: String) {
    WHOOP("whoop", "WHOOP")
}

private suspend fun hasHealthConnectNutritionPermission(context: Context): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val status = HealthConnectClient.getSdkStatus(context)
            if (status != HealthConnectClient.SDK_AVAILABLE) return@withContext false

            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            HealthPermission.getReadPermission(NutritionRecord::class) in granted
        } catch (_: Exception) {
            false
        }
    }
}

private suspend fun hasHealthConnectMenstruationPermission(context: Context): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val status = HealthConnectClient.getSdkStatus(context)
            if (status != HealthConnectClient.SDK_AVAILABLE) return@withContext false

            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            HealthPermission.getReadPermission(MenstruationPeriodRecord::class) in granted
        } catch (_: Exception) {
            false
        }
    }
}

private fun isBatteryOptimizationExempt(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

internal class DataSettingsStore(context: Context) {
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