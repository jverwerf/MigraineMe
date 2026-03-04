package com.migraineme

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data Settings Screen - Data Collection settings only.
 *
 * This is the main entry point. It delegates to:
 * - DataSettingsState: manages all state
 * - DataSettingsSections: defines what rows appear
 * - DataSettingsToggleHandler: handles toggle logic
 * - DataSettingsRowComponents: UI components
 * - DataSettingsDialogs: dialog components
 */
@Composable
fun DataSettingsScreen(
    onBack: () -> Unit = {},
    onOpenMenstruationSettings: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // State management
    val state = remember { DataSettingsState(appContext) }
    val metricSettings by state.metricSettings.collectAsState()
    val connectedWearables by state.connectedWearables.collectAsState()
    val menstruationSettings by state.menstruationSettings.collectAsState()
    val nutritionPermissionGranted by state.nutritionPermissionGranted.collectAsState()
    val menstruationPermissionGranted by state.menstruationPermissionGranted.collectAsState()

    // Dialog states
    var showScreenTimePermissionDialog by remember { mutableStateOf(false) }
    var showBatteryOptDialog by remember { mutableStateOf(false) }
    var showBackgroundLocationDialog by remember { mutableStateOf(false) }

    // Refresh trigger
    var refreshTick by remember { mutableIntStateOf(0) }

    // ─────────────────────────────────────────────────────────────────────────
    // Permission Launchers
    // ─────────────────────────────────────────────────────────────────────────

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshTick++ }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshTick++ }

    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshTick++ }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if ((fineGranted || coarseGranted) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!DataSettingsPermissionHelper.hasBackgroundLocationPermission(appContext)) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        refreshTick++
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle & Initial Load
    // ─────────────────────────────────────────────────────────────────────────

    // Initial load
    LaunchedEffect(Unit) {
        state.loadAll()
    }

    // Refresh on resume
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Handle refresh
    LaunchedEffect(refreshTick) {
        if (refreshTick > 0) {
            state.refreshMetricSettings()
            state.refreshWearableConnections()
            state.refreshHealthConnectPermissions()
            state.autoEnableScreenTimeIfPermissionGranted()
            state.autoEnableLocationIfPermissionGranted()
            state.autoEnableAmbientNoiseIfPermissionGranted()
            state.autoEnableNutritionIfHcPermissionGranted()

            // Sync workers and refresh if Supabase changed
            if (state.syncWorkers()) {
                state.refreshMetricSettings()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI
    // ─────────────────────────────────────────────────────────────────────────

    val sections = remember { DataSettingsSections.getAllSections() }
    val weatherMetrics = remember { DataSettingsSections.weatherMetrics }
    val hasAnyWearable = connectedWearables.isNotEmpty()
    val locationEnabled = state.isLocationEnabled()

    // Derive counts reactively from metricSettings
    // Only count metrics that are defined in sections (Supabase may contain extras
    // from Health Connect / Polar / Garmin that aren't displayed in the UI)
    val sectionMetricKeys = remember(sections) {
        sections.flatMap { section -> section.rows.map { it.table } }.toSet()
    }
    val enabledCount = metricSettings.count { it.key in sectionMetricKeys && it.value.enabled }
    val totalCount = sectionMetricKeys.size
    val sourceCount = metricSettings.values.mapNotNull { it.preferredSource }.toSet().size

    val scrollState = rememberScrollState()

    // Report scroll position for setup coach overlay
    LaunchedEffect(scrollState.value, scrollState.maxValue) {
        if (TourManager.isActive() && TourManager.currentPhase() == CoachPhase.SETUP) {
            if (scrollState.maxValue > 0 && scrollState.value >= scrollState.maxValue - 50) {
                SetupScrollState.scrollPosition = -1
            } else {
                SetupScrollState.scrollPosition = scrollState.value
            }
        }
    }

    // Section icon mapping
    val sectionIcons = mapOf(
        "Sleep" to Icons.Outlined.Bedtime,
        "Physical Health" to Icons.Outlined.FitnessCenter,
        "Mental Health" to Icons.Outlined.Psychology,
        "Environment" to Icons.Outlined.Cloud,
        "Diet" to Icons.Outlined.Restaurant,
        "Menstruation" to Icons.Outlined.FavoriteBorder
    )

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            // ═══════════════════════════════════════════════════════════
            // HERO — Data collection summary
            // ═══════════════════════════════════════════════════════════
            HeroCard {
                Text("Data Collection", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                Text("$enabledCount / $totalCount", color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
                Text("metrics active", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    DStatColumn("Sections", "${sections.size}")
                    DStatColumn("Sources", if (sourceCount > 0) "$sourceCount" else "—")
                    DStatColumn("Wearables", if (connectedWearables.any { it == WearableSource.WHOOP }) "1" else "0")
                }
            }

            // ═══════════════════════════════════════════════════════════
            // HOW IT WORKS — purple accent card
            // ═══════════════════════════════════════════════════════════
            Card(
                colors = CardDefaults.cardColors(containerColor = AppTheme.AccentPurple.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Info, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp))
                    Text(
                        "Toggle the metrics MigraineMe collects each day. " +
                        "Enabled metrics feed into your daily risk score and AI insights. " +
                        "Some metrics need a wearable (WHOOP / Health Connect) or phone permissions — the row will guide you.",
                        color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ═══════════════════════════════════════════════════════════
            // SECTIONS — one BaseCard per category
            // ═══════════════════════════════════════════════════════════
            for (section in sections) {
                BaseCard {
                    // Section header
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sectionIcons[section.title]?.let { icon ->
                            Icon(icon, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(18.dp))
                        }
                        Text(section.title, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))

                        Spacer(Modifier.weight(1f))

                        // Enabled count badge
                        val sectionEnabled = section.rows.count { row -> metricSettings[row.table]?.enabled == true }
                        Text(
                            "$sectionEnabled/${section.rows.size}",
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 4.dp))

                    for ((idx, row) in section.rows.withIndex()) {
                        val greyOutWearableRow = row.collectedByKind == CollectedByKind.WEARABLE && !hasAnyWearable
                        val greyOutWeatherRow = row.table in weatherMetrics && !locationEnabled

                        DataSettingsRow(
                            row = row,
                            metricSettings = metricSettings,
                            connectedWearables = connectedWearables,
                            menstruationSettings = menstruationSettings,
                            nutritionPermissionGranted = nutritionPermissionGranted,
                            menstruationPermissionGranted = menstruationPermissionGranted,
                            greyOut = greyOutWearableRow || greyOutWeatherRow,
                            weatherMetrics = weatherMetrics,
                            onToggle = { metric, enabled, source ->
                                state.updateMetricLocally(metric, enabled, source)
                                scope.launch {
                                    val result = DataSettingsToggleHandler.toggleMetric(
                                        context = appContext,
                                        metric = metric,
                                        enabled = enabled,
                                        preferredSource = source,
                                        metricSettingsMap = metricSettings
                                    )
                                    handleToggleResult(
                                        result = result,
                                        metric = metric,
                                        state = state,
                                        activity = activity,
                                        appContext = appContext,
                                        micPermissionLauncher = micPermissionLauncher,
                                        locationPermissionLauncher = locationPermissionLauncher,
                                        onShowScreenTimeDialog = { showScreenTimePermissionDialog = true },
                                        onShowBatteryDialog = { showBatteryOptDialog = true }
                                    )
                                    refreshTick++
                                }
                            },
                            onSourceChange = { metric, newSource, currentEnabled ->
                                scope.launch {
                                    DataSettingsToggleHandler.changeMetricSource(
                                        context = appContext,
                                        metric = metric,
                                        newSource = newSource,
                                        currentEnabled = currentEnabled
                                    )
                                    refreshTick++
                                }
                            },
                            onRequestMicPermission = {
                                requestMicPermission(activity, appContext, micPermissionLauncher)
                            },
                            onRequestBatteryExemption = { showBatteryOptDialog = true },
                            onRequestBackgroundLocation = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                }
                            },
                            onRequestScreenTimePermission = {
                                ScreenTimePermissionHelper.openUsageAccessSettings(appContext)
                            },
                            onRequestLocationPermission = {
                                requestLocationPermission(activity, appContext, locationPermissionLauncher)
                            }
                        )

                        if (idx != section.rows.lastIndex) {
                            HorizontalDivider(
                                color = Color.White.copy(alpha = 0.06f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════
            // NOTIFICATIONS — BaseCard
            // ═══════════════════════════════════════════════════════════
            NotificationsCard(
                onRequestNotificationPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val askedBefore = hasAskedNotificationPermission(appContext)
                        val canRationale = activity?.let {
                            ActivityCompat.shouldShowRequestPermissionRationale(
                                it, Manifest.permission.POST_NOTIFICATIONS
                            )
                        } ?: true

                        if (askedBefore && !canRationale) {
                            openAppSettings(appContext)
                        } else {
                            markAskedNotificationPermission(appContext)
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                },
                refreshTick = refreshTick
            )

            Spacer(Modifier.height(16.dp))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Dialogs
    // ─────────────────────────────────────────────────────────────────────────

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

    if (showBackgroundLocationDialog) {
        BackgroundLocationDialog(
            onDismiss = { showBackgroundLocationDialog = false },
            onOpenSettings = {
                showBackgroundLocationDialog = false
                openAppSettings(appContext)
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helper Composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DStatColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helper Functions
// ─────────────────────────────────────────────────────────────────────────────

private fun handleToggleResult(
    result: DataSettingsToggleHandler.ToggleResult,
    metric: String,
    state: DataSettingsState,
    activity: Activity?,
    appContext: android.content.Context,
    micPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    locationPermissionLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    onShowScreenTimeDialog: () -> Unit,
    onShowBatteryDialog: () -> Unit
) {
    when (result) {
        is DataSettingsToggleHandler.ToggleResult.Success -> { /* UI will refresh */ }
        is DataSettingsToggleHandler.ToggleResult.NeedsPermission -> {
            // Permission was missing — revert the optimistic update
            state.updateMetricLocally(metric, false)
            state.markPendingEnable(metric)
            when (result.permissionType) {
                DataSettingsToggleHandler.PermissionType.SCREEN_TIME -> onShowScreenTimeDialog()
                DataSettingsToggleHandler.PermissionType.LOCATION -> {
                    requestLocationPermission(activity, appContext, locationPermissionLauncher)
                }
                DataSettingsToggleHandler.PermissionType.MICROPHONE -> {
                    requestMicPermission(activity, appContext, micPermissionLauncher)
                }
                DataSettingsToggleHandler.PermissionType.BATTERY_OPTIMIZATION -> onShowBatteryDialog()
                else -> { /* Handle other permission types */ }
            }
        }
        is DataSettingsToggleHandler.ToggleResult.Error -> {
            // Toggle failed — the refreshTick will reconcile with server state
            android.util.Log.e("DataSettings", "Toggle error: ${result.message}")
        }
    }
}

private fun requestMicPermission(
    activity: Activity?,
    appContext: android.content.Context,
    launcher: androidx.activity.result.ActivityResultLauncher<String>
) {
    val askedBefore = hasAskedMicPermission(appContext)
    val canRationale = activity?.let {
        ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
    } ?: true

    if (askedBefore && !canRationale) {
        openAppSettings(appContext)
    } else {
        markAskedMicPermission(appContext)
        launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
}

private fun requestLocationPermission(
    activity: Activity?,
    appContext: android.content.Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    val askedBefore = hasAskedLocationPermission(appContext)
    val canRationale = activity?.let {
        ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_FINE_LOCATION)
    } ?: true

    if (askedBefore && !canRationale) {
        openAppSettings(appContext)
    } else {
        markAskedLocationPermission(appContext)
        launcher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }
}

private fun hasAskedNotificationPermission(context: android.content.Context): Boolean {
    return context.getSharedPreferences("data_settings", android.content.Context.MODE_PRIVATE)
        .getBoolean("notification_permission_asked", false)
}

private fun markAskedNotificationPermission(context: android.content.Context) {
    context.getSharedPreferences("data_settings", android.content.Context.MODE_PRIVATE)
        .edit()
        .putBoolean("notification_permission_asked", true)
        .apply()
}
