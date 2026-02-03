package com.migraineme

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data Settings Screen with tabs:
 * - Tab 0: Data Collection
 * - Tab 1: Auto Triggers
 */
@Composable
fun DataSettingsScreen(
    onOpenMenstruationSettings: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Data Collection", "Auto Triggers")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTab) {
            0 -> DataCollectionTab(onOpenMenstruationSettings = onOpenMenstruationSettings)
            1 -> AutoTriggersTab()
        }
    }
}

@Composable
private fun DataCollectionTab(
    onOpenMenstruationSettings: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val edge = remember { EdgeFunctionsService() }

    val metricSettingsMap =
        remember { mutableStateOf<Map<String, EdgeFunctionsService.MetricSettingResponse>>(emptyMap()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { edge.getMetricSettings(context) }
                .onSuccess { metricSettingsMap.value = MetricSettingsMapHelper.toMap(it) }
                .onFailure { e ->
                    android.util.Log.e(
                        "DataSettings",
                        "Failed to load metric settings from Supabase: ${e.message}",
                        e
                    )
                }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    val whoopConnected = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        whoopConnected.value = WhoopTokenStore(context).load() != null
    }

    // Health Connect wearables connection check
    val healthConnectWearablesConnected = remember { mutableStateOf(false) }
    var permissionRefreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(permissionRefreshTick) {
        healthConnectWearablesConnected.value = withContext(Dispatchers.IO) {
            try {
                if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
                    return@withContext false
                }
                val hc = HealthConnectClient.getOrCreate(context)
                val granted = hc.permissionController.getGrantedPermissions()
                val wearablePermissions = setOf(
                    HealthPermission.getReadPermission(SleepSessionRecord::class),
                    HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
                    HealthPermission.getReadPermission(RestingHeartRateRecord::class),
                    HealthPermission.getReadPermission(StepsRecord::class)
                )
                wearablePermissions.any { it in granted }
            } catch (e: Exception) {
                false
            }
        }
    }

    // Include both WHOOP and Health Connect in connected wearables
    val connectedWearables = remember(whoopConnected.value, healthConnectWearablesConnected.value) {
        buildList {
            if (whoopConnected.value) add(WearableSource.WHOOP)
            if (healthConnectWearablesConnected.value) add(WearableSource.HEALTH_CONNECT)
        }
    }
    val hasAnyWearable = connectedWearables.isNotEmpty()

    val store = remember { DataSettingsStore(context) }

    var refreshTick by remember { mutableIntStateOf(0) }
    fun bumpRefresh() {
        refreshTick += 1
    }

    LaunchedEffect(refreshTick) {
        if (refreshTick > 0) {
            withContext(Dispatchers.IO) {
                runCatching { edge.getMetricSettings(context) }
                    .onSuccess { metricSettingsMap.value = MetricSettingsMapHelper.toMap(it) }
                    .onFailure { e ->
                        android.util.Log.e(
                            "DataSettings",
                            "Failed to reload metric settings from Supabase: ${e.message}",
                            e
                        )
                    }
            }
        }
    }

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

    val healthConnectNutritionConnected = remember { mutableStateOf(false) }
    val healthConnectMenstruationConnected = remember { mutableStateOf(false) }
    LaunchedEffect(permissionRefreshTick) {
        healthConnectNutritionConnected.value =
            HealthConnectPermissionHelper.hasNutritionPermission(context)
        healthConnectMenstruationConnected.value =
            HealthConnectPermissionHelper.hasMenstruationPermission(context)
    }

    val menstruationSettings = remember { mutableStateOf<MenstruationSettings?>(null) }
    var menstruationRefreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(permissionRefreshTick, menstruationRefreshTick) {
        val accessToken = SessionStore.getValidAccessToken(context)
        if (accessToken != null) {
            menstruationSettings.value =
                runCatching { SupabaseMenstruationService(context).getSettings(accessToken) }.getOrNull()
        } else {
            menstruationSettings.value = null
        }
    }

    LaunchedEffect(permissionRefreshTick) {
        if (permissionRefreshTick > 0) {
            if (ScreenTimePermissionHelper.hasPermission(context)) {
                val screenTimeOn = MetricSettingsMapHelper.isEnabled(
                    map = metricSettingsMap.value,
                    metric = "screen_time_daily",
                    preferredSource = null,
                    defaultValue = false
                )

                if (!screenTimeOn) {
                    android.util.Log.d("DataSettings", "Auto-enabling screen time after permission grant")

                    edge.upsertMetricSetting(
                        context = context,
                        metric = "screen_time_daily",
                        enabled = true
                    )

                    bumpRefresh()
                }
            }
        }
    }

    LaunchedEffect(metricSettingsMap.value, permissionRefreshTick) {
        val result = DataSettingsWorkerSyncHelper.syncFromSupabaseTruth(
            context = context,
            edge = edge,
            metricSettingsMap = metricSettingsMap.value
        )
        if (result.changedSupabase) {
            bumpRefresh()
        }
    }

    // Weather metrics that depend on location
    val weatherMetrics = remember {
        setOf(
            "temperature_daily",
            "pressure_daily",
            "humidity_daily",
            "wind_daily",
            "uv_daily",
            "thunderstorm_daily"
        )
    }

    // Check if location is enabled
    val locationEnabled = remember(metricSettingsMap.value, refreshTick) {
        MetricSettingsMapHelper.isEnabled(
            map = metricSettingsMap.value,
            metric = "user_location_daily",
            preferredSource = null,
            defaultValue = false
        )
    }

    val sections = remember {
        listOf(
            DataSection(
                title = "Sleep",
                rows = listOf(
                    wearableRow("sleep_duration_daily", "Sleep duration"),
                    wearableRow("sleep_score_daily", "Sleep score"),
                    wearableRow("sleep_efficiency_daily", "Sleep efficiency"),
                    wearableRow("sleep_stages_daily", "Sleep stages"),
                    wearableRow("sleep_disturbances_daily", "Sleep disturbances"),
                    wearableRow("fell_asleep_time_daily", "Fell asleep time"),
                    wearableRow("woke_up_time_daily", "Woke up time")
                )
            ),
            DataSection(
                title = "Physical Health",
                rows = listOf(
                    wearableRow("recovery_score_daily", "Recovery score"),
                    wearableRow("resting_hr_daily", "Resting heart rate"),
                    wearableRow("hrv_daily", "HRV"),
                    wearableRow("skin_temp_daily", "Skin temperature"),
                    wearableRow("spo2_daily", "Blood oxygen (SpO2)"),
                    wearableRow("time_in_high_hr_zones_daily", "Time in high HR zones"),
                    wearableRow("activity_hr_zones_sessions", "Workout HR zones"),
                    wearableRow("steps_daily", "Steps")
                )
            ),
            DataSection(
                title = "Mental Health",
                rows = listOf(
                    referenceRow("stress_index_daily", "Stress index"),
                    phoneRow("screen_time_daily", "Phone screen time tracking")
                )
            ),
            DataSection(
                title = "Environment",
                rows = listOf(
                    phoneRow("user_location_daily", "Location"),
                    referenceRow("temperature_daily", "Temperature"),
                    referenceRow("pressure_daily", "Pressure"),
                    referenceRow("humidity_daily", "Humidity"),
                    referenceRow("wind_daily", "Wind"),
                    referenceRow("uv_daily", "UV index"),
                    referenceRow("thunderstorm_daily", "Thunderstorms"),
                    phoneRow("ambient_noise_samples", "Noise Sampling")
                )
            ),
            DataSection(
                title = "Diet",
                rows = listOf(
                    phoneRow("nutrition", "Nutrition")
                )
            ),
            DataSection(
                title = "Menstruation",
                rows = listOf(
                    phoneRow("menstruation", "Menstruation")
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
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "Connect WHOOP or Health Connect in the Connections section to enable wearable data. Some toggles require phone permissions.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        for (section in sections) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(section.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))

                    for ((idx, row) in section.rows.withIndex()) {
                        val greyOutWearableRow =
                            (row.collectedByKind == CollectedByKind.WEARABLE && !hasAnyWearable)

                        val greyOutDietRow =
                            (row.table == "nutrition" && !healthConnectNutritionConnected.value)

                        // Weather metrics depend on location being enabled
                        val greyOutWeatherRow = row.table in weatherMetrics && !locationEnabled

                        DataRowUi(
                            row = row,
                            store = store,
                            connectedWearables = connectedWearables,
                            metricSettingsMap = metricSettingsMap.value,
                            enabled = true,
                            greyOut = greyOutWearableRow || greyOutDietRow || greyOutWeatherRow,
                            refreshTick = refreshTick,
                            micPermissionLauncher = micPermissionLauncher,
                            locationPermissionLauncher = locationPermissionLauncher,
                            permissionRefreshTick = permissionRefreshTick,
                            onAnyChange = { bumpRefresh() },
                            menstruationSettingsState = menstruationSettings,
                            nutritionPermissionGranted = healthConnectNutritionConnected.value,
                            menstruationPermissionGranted = healthConnectMenstruationConnected.value,
                            onOpenMenstruationSettings = onOpenMenstruationSettings,
                            weatherMetrics = weatherMetrics
                        )

                        if (idx != section.rows.lastIndex) {
                            Spacer(Modifier.height(10.dp))
                            Divider()
                        }
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
    metricSettingsMap: Map<String, EdgeFunctionsService.MetricSettingResponse>,
    enabled: Boolean,
    greyOut: Boolean,
    refreshTick: Int,
    micPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    locationPermissionLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    permissionRefreshTick: Int,
    onAnyChange: () -> Unit,
    menstruationSettingsState: androidx.compose.runtime.MutableState<MenstruationSettings?>,
    nutritionPermissionGranted: Boolean,
    menstruationPermissionGranted: Boolean,
    onOpenMenstruationSettings: () -> Unit,
    weatherMetrics: Set<String> = emptySet()
) {
    val appContext = LocalContext.current.applicationContext
    val activityContext = LocalContext.current
    val activity = activityContext as? Activity

    val scope = rememberCoroutineScope()
    val edge = remember { EdgeFunctionsService() }

    val menstruationSettings = menstruationSettingsState.value

    var showBatteryOptDialog by remember { mutableStateOf(false) }

    val isScreenTimeRow =
        row.table == "screen_time_daily" && row.collectedByKind == CollectedByKind.PHONE
    var showScreenTimePermissionDialog by remember { mutableStateOf(false) }
    val screenTimePermissionGranted = remember(refreshTick, permissionRefreshTick) {
        if (isScreenTimeRow) ScreenTimePermissionHelper.hasPermission(appContext) else true
    }

    val isLocationRow =
        row.table == "user_location_daily" && row.collectedByKind == CollectedByKind.PHONE
    val locationPermissionGranted = remember(refreshTick, permissionRefreshTick) {
        val fine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        fine || coarse
    }

    val isAmbientNoiseRow =
        row.table == "ambient_noise_samples" && row.collectedByKind == CollectedByKind.PHONE
    val micPermissionGranted = remember(refreshTick, permissionRefreshTick) {
        if (isAmbientNoiseRow) MicrophonePermissionHelper.hasPermission(appContext) else true
    }
    val batteryOptimizationExempt = remember(refreshTick, permissionRefreshTick) {
        if (isAmbientNoiseRow) isBatteryOptimizationExempt(appContext) else true
    }

    val isNutritionRow = row.table == "nutrition" && row.collectedByKind == CollectedByKind.PHONE
    val isMenstruationRow =
        row.table == "menstruation" && row.collectedByKind == CollectedByKind.PHONE
    val isWearableRow = row.collectedByKind == CollectedByKind.WEARABLE
    val isStressRow = row.table == "stress_index_daily" && isWearableRow

    val menstruationProviderOptions = remember(menstruationPermissionGranted) {
        buildList {
            add(PhoneSource.PHONE)
            if (menstruationPermissionGranted) add(PhoneSource.HEALTH_CONNECT)
        }
    }

    var selectedMenstruationSource by remember(row.table, refreshTick, metricSettingsMap, menstruationPermissionGranted) {
        val supabaseSourceKey = metricSettingsMap.values
            .firstOrNull { it.metric == "menstruation" && it.preferredSource != null }
            ?.preferredSource

        val fromSupabase = supabaseSourceKey?.let { key ->
            PhoneSource.values().firstOrNull { it.key == key }
        }

        val initial = when {
            fromSupabase == PhoneSource.HEALTH_CONNECT && !menstruationPermissionGranted -> PhoneSource.PHONE
            fromSupabase != null -> fromSupabase
            else -> PhoneSource.PHONE
        }

        mutableStateOf(initial)
    }

    var selectedWearable by remember(row.table, refreshTick, metricSettingsMap) {
        val supabaseSourceKey = metricSettingsMap.values
            .firstOrNull { it.metric == row.table && it.preferredSource != null }
            ?.preferredSource

        val fromSupabase = supabaseSourceKey?.let { key ->
            WearableSource.values().firstOrNull { it.key == key }
        }

        mutableStateOf(fromSupabase ?: row.defaultWearable ?: WearableSource.WHOOP)
    }

    val activeKey = when {
        isWearableRow -> "${row.table}_${selectedWearable.key}"
        isMenstruationRow -> "${row.table}_${selectedMenstruationSource.key}"
        else -> "${row.table}_null"
    }

    val defaultEnabledForRow = remember(row.table, row.collectedByKind) {
        when (row.table) {
            "screen_time_daily" -> false
            "menstruation" -> false
            else -> defaultActiveFor(row)
        }
    }

    val enabledBySupabase = metricSettingsMap[activeKey]?.enabled ?: defaultEnabledForRow

    val depsOkForStress = if (!isStressRow) {
        true
    } else {
        val hrvKey = "hrv_daily_${selectedWearable.key}"
        val rrKey = "resting_hr_daily_${selectedWearable.key}"
        val hrvOn = metricSettingsMap[hrvKey]?.enabled ?: true
        val rrOn = metricSettingsMap[rrKey]?.enabled ?: true
        hrvOn && rrOn
    }

    LaunchedEffect(isStressRow, depsOkForStress, enabledBySupabase, selectedWearable, metricSettingsMap) {
        if (isStressRow && !depsOkForStress && enabledBySupabase) {
            withContext(Dispatchers.IO) {
                runCatching {
                    edge.upsertMetricSetting(
                        context = appContext,
                        metric = row.table,
                        enabled = false,
                        preferredSource = selectedWearable.key
                    )
                }
            }
            onAnyChange()
        }
    }

    LaunchedEffect(isAmbientNoiseRow, micPermissionGranted, batteryOptimizationExempt, enabledBySupabase) {
        if (isAmbientNoiseRow && enabledBySupabase && (!micPermissionGranted || !batteryOptimizationExempt)) {
            withContext(Dispatchers.IO) {
                runCatching {
                    edge.upsertMetricSetting(
                        context = appContext,
                        metric = row.table,
                        enabled = false,
                        preferredSource = null
                    )
                }
            }
            onAnyChange()
        }
    }

    val effectiveGreyOut = greyOut || (isStressRow && !depsOkForStress)
    val alpha = if (effectiveGreyOut) 0.55f else 1.0f

    if (isWearableRow) {
        val allowed = connectedWearables
        if (allowed.isNotEmpty() && !allowed.contains(selectedWearable)) {
            val newSel = allowed.first()
            selectedWearable = newSel
            scope.launch(Dispatchers.IO) {
                runCatching {
                    edge.upsertMetricSetting(
                        context = appContext,
                        metric = row.table,
                        enabled = enabledBySupabase,
                        preferredSource = newSel.key
                    )
                }
                withContext(Dispatchers.Main) { onAnyChange() }
            }
        }
    }

    val canToggleAmbient = !isAmbientNoiseRow ||
            (micPermissionGranted && batteryOptimizationExempt) ||
            !enabledBySupabase

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", appContext.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    val providerColWidth = 120.dp
    val toggleColWidth = 56.dp

    // Check if menstruation needs setup
    val menstruationNeedsSetup = isMenstruationRow && enabledBySupabase &&
            (menstruationSettings == null || menstruationSettings.lastMenstruationDate == null)

    Column {
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
                when {
                    isAmbientNoiseRow -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.collectedByLabel, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Phone",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.alpha(0.75f)
                            )
                        }
                    }

                    isMenstruationRow -> {
                        Text(row.collectedByLabel, style = MaterialTheme.typography.bodyMedium)
                    }

                    else -> {
                        Text(row.collectedByLabel, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (isMenstruationRow && enabledBySupabase && menstruationSettings != null && menstruationSettings.lastMenstruationDate != null) {
                    Text(
                        "Last: ${menstruationSettings.lastMenstruationDate} • Avg: ${menstruationSettings.avgCycleLength} days",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.alpha(0.7f)
                    )
                }

                // Show setup hint when enabled but not configured
                if (menstruationNeedsSetup) {
                    Text(
                        "Please go to Monitor to complete setup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.alpha(0.9f)
                    )
                }

                if (isStressRow && !depsOkForStress) {
                    Text(
                        "Enable HRV and Resting HR to use Stress.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.alpha(0.7f)
                    )
                }

                // Show hint for weather metrics when location is off
                if (row.table in weatherMetrics && effectiveGreyOut) {
                    Text(
                        "Enable Location to use weather data.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.alpha(0.7f)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .width(providerColWidth)
                    .padding(end = 10.dp),
                horizontalAlignment = Alignment.Start
            ) {
                when {
                    isWearableRow && connectedWearables.isNotEmpty() -> {
                        WearableSelector(
                            options = connectedWearables,
                            selected = selectedWearable,
                            enabled = enabled && !effectiveGreyOut,
                            onSelected = { newSel ->
                                if (newSel == selectedWearable) return@WearableSelector
                                selectedWearable = newSel
                                scope.launch(Dispatchers.IO) {
                                    runCatching {
                                        edge.upsertMetricSetting(
                                            context = appContext,
                                            metric = row.table,
                                            enabled = enabledBySupabase,
                                            preferredSource = newSel.key
                                        )
                                    }
                                    withContext(Dispatchers.Main) { onAnyChange() }
                                }
                            }
                        )
                    }

                    isMenstruationRow -> {
                        ProviderSelector(
                            options = menstruationProviderOptions,
                            selected = selectedMenstruationSource,
                            enabled = enabled && !effectiveGreyOut,
                            onSelected = { newSel ->
                                if (newSel == selectedMenstruationSource) return@ProviderSelector
                                selectedMenstruationSource = newSel
                                scope.launch(Dispatchers.IO) {
                                    runCatching {
                                        edge.upsertMetricSetting(
                                            context = appContext,
                                            metric = "menstruation",
                                            enabled = enabledBySupabase,
                                            preferredSource = newSel.key
                                        )
                                    }
                                    withContext(Dispatchers.Main) { onAnyChange() }
                                }
                            }
                        )
                    }

                    isAmbientNoiseRow -> {
                    }

                    else -> {
                        val providerLabel = when (row.collectedByKind) {
                            CollectedByKind.WEARABLE -> ""
                            CollectedByKind.PHONE -> {
                                when {
                                    isNutritionRow -> "Health Connect"
                                    else -> "Phone"
                                }
                            }
                            CollectedByKind.MANUAL -> "Manual"
                            CollectedByKind.REFERENCE -> ""
                        }

                        Text(
                            providerLabel,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.alpha(0.8f)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.width(toggleColWidth),
                horizontalAlignment = Alignment.End
            ) {
                Switch(
                    checked = enabledBySupabase,
                    enabled = enabled && !effectiveGreyOut && canToggleAmbient,
                    onCheckedChange = { newValue ->
                        if (effectiveGreyOut) return@Switch
                        if (isStressRow && !depsOkForStress) return@Switch

                        if (isScreenTimeRow && newValue && !screenTimePermissionGranted) {
                            showScreenTimePermissionDialog = true
                            return@Switch
                        }

                        if (isLocationRow && newValue && !locationPermissionGranted) {
                            val askedBefore = hasAskedLocationPermission(appContext)
                            val canRationale = activity?.let {
                                ActivityCompat.shouldShowRequestPermissionRationale(
                                    it,
                                    Manifest.permission.ACCESS_FINE_LOCATION
                                )
                            } ?: true

                            if (askedBefore && !canRationale) {
                                openAppSettings()
                            } else {
                                markAskedLocationPermission(appContext)
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                            return@Switch
                        }

                        if (isAmbientNoiseRow && newValue) {
                            if (!micPermissionGranted) {
                                val askedBefore = hasAskedMicPermission(appContext)
                                val canRationale = activity?.let {
                                    ActivityCompat.shouldShowRequestPermissionRationale(
                                        it,
                                        Manifest.permission.RECORD_AUDIO
                                    )
                                } ?: true

                                if (askedBefore && !canRationale) {
                                    openAppSettings()
                                } else {
                                    markAskedMicPermission(appContext)
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                                return@Switch
                            }
                            if (!batteryOptimizationExempt &&
                                BatteryOptimizationHelper.shouldRequestBatteryOptimization(appContext)
                            ) {
                                showBatteryOptDialog = true
                                return@Switch
                            }
                        }

                        if (isNutritionRow && newValue && !nutritionPermissionGranted) return@Switch

                        if (isMenstruationRow) {
                            val preferredSource = selectedMenstruationSource.key
                            scope.launch(Dispatchers.IO) {
                                try {
                                    if (newValue) {
                                        // Enable menstruation
                                        PredictedMenstruationHelper.ensureExists(appContext)
                                        MetricToggleHelper.toggle(appContext, "menstruation", true)
                                        MenstruationSyncScheduler.schedule(appContext)

                                        edge.upsertMetricSetting(
                                            context = appContext,
                                            metric = "menstruation",
                                            enabled = true,
                                            preferredSource = preferredSource
                                        )
                                    } else {
                                        PredictedMenstruationHelper.delete(appContext)
                                        MetricToggleHelper.toggle(appContext, "menstruation", false)
                                        edge.upsertMetricSetting(
                                            context = appContext,
                                            metric = "menstruation",
                                            enabled = false,
                                            preferredSource = preferredSource
                                        )
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e(
                                        "DataSettings",
                                        "Menstruation toggle failed: ${e.message}",
                                        e
                                    )
                                } finally {
                                    withContext(Dispatchers.Main) { onAnyChange() }
                                }
                            }
                            return@Switch
                        }

                        // Location toggle: control worker + weather metrics
                        if (isLocationRow) {
                            if (newValue) {
                                // Turning ON: run worker now and schedule for 9 AM
                                LocationDailySyncWorker.runOnceNow(appContext)
                                LocationDailySyncWorker.scheduleNext(appContext)
                            } else {
                                // Turning OFF: cancel workers and disable weather metrics
                                LocationDailySyncWorker.cancelAll(appContext)
                                scope.launch(Dispatchers.IO) {
                                    for (metric in weatherMetrics) {
                                        edge.upsertMetricSetting(
                                            context = appContext,
                                            metric = metric,
                                            enabled = false,
                                            preferredSource = null
                                        )
                                    }
                                }
                            }
                        }

                        val preferredSource = if (isWearableRow) selectedWearable.key else null

                        scope.launch(Dispatchers.IO) {
                            edge.upsertMetricSetting(
                                context = appContext,
                                metric = row.table,
                                enabled = newValue,
                                preferredSource = preferredSource
                            )

                            if (!newValue && isWearableRow &&
                                (row.table == "hrv_daily" || row.table == "resting_hr_daily")
                            ) {
                                val stressKey = "stress_index_daily_${selectedWearable.key}"
                                val stressOn = metricSettingsMap[stressKey]?.enabled ?: false
                                if (stressOn) {
                                    edge.upsertMetricSetting(
                                        context = appContext,
                                        metric = "stress_index_daily",
                                        enabled = false,
                                        preferredSource = selectedWearable.key
                                    )
                                }
                            }
                        }

                        onAnyChange()
                    }
                )
            }
        }

        if (isAmbientNoiseRow) {
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
                        "Microphone permission",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.alpha(0.75f)
                    )
                }

                Column(
                    modifier = Modifier
                        .width(providerColWidth)
                        .padding(end = 10.dp)
                ) {
                }

                Column(
                    modifier = Modifier.width(toggleColWidth),
                    horizontalAlignment = Alignment.End
                ) {
                    Switch(
                        checked = micPermissionGranted,
                        onCheckedChange = { newVal ->
                            if (newVal && !micPermissionGranted) {
                                val askedBefore = hasAskedMicPermission(appContext)
                                val canRationale = activity?.let {
                                    ActivityCompat.shouldShowRequestPermissionRationale(
                                        it,
                                        Manifest.permission.RECORD_AUDIO
                                    )
                                } ?: true

                                if (askedBefore && !canRationale) {
                                    openAppSettings()
                                } else {
                                    markAskedMicPermission(appContext)
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        enabled = !micPermissionGranted
                    )
                }
            }

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
                        "Battery optimization exemption",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.alpha(0.75f)
                    )
                }

                Column(
                    modifier = Modifier
                        .width(providerColWidth)
                        .padding(end = 10.dp)
                ) {
                }

                Column(
                    modifier = Modifier.width(toggleColWidth),
                    horizontalAlignment = Alignment.End
                ) {
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
            }
        }

        if (isMenstruationRow && enabledBySupabase && menstruationSettings != null && menstruationSettings.lastMenstruationDate != null) {
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
                                menstruationSettings.lastMenstruationDate.toString(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        TextButton(onClick = onOpenMenstruationSettings) {
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
                        TextButton(onClick = onOpenMenstruationSettings) {
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
                                        val accessToken = SessionStore.getValidAccessToken(appContext)
                                        if (accessToken != null) {
                                            val service = SupabaseMenstruationService(appContext)

                                            service.updateSettings(
                                                accessToken = accessToken,
                                                lastMenstruationDate = menstruationSettings.lastMenstruationDate,
                                                avgCycleLength = menstruationSettings.avgCycleLength,
                                                autoUpdateAverage = newValue
                                            )

                                            menstruationSettingsState.value = menstruationSettings.copy(
                                                autoUpdateAverage = newValue
                                            )

                                            onAnyChange()
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("DataSettings", "Failed to update auto-update: ${e.message}", e)
                                    }
                                }
                            }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    val nextPeriod = menstruationSettings.lastMenstruationDate.plusDays(
                        menstruationSettings.avgCycleLength.toLong()
                    )

                    Text(
                        "Next expected: $nextPeriod",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }

    if (showScreenTimePermissionDialog) {
        ScreenTimePermissionDialog(
            onDismiss = { showScreenTimePermissionDialog = false },
            onOpenSettings = {
                ScreenTimePermissionHelper.openUsageAccessSettings(appContext)
                showScreenTimePermissionDialog = false
            }
        )
    }

    if (showBatteryOptDialog) {
        BatteryOptimizationDialog(
            onDismiss = {
                showBatteryOptDialog = false
                BatteryOptimizationHelper.markAsAsked(appContext)
            },
            onOpenSettings = {
                BatteryOptimizationHelper.requestBatteryOptimizationExemption(appContext)
                BatteryOptimizationHelper.markAsAsked(appContext)
                showBatteryOptDialog = false
            }
        )
    }
}

@Composable
private fun WearableSelector(
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

@Composable
private fun ProviderSelector(
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