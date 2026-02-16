package com.migraineme

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

            // Sync workers and refresh if Supabase changed
            if (state.syncWorkers()) {
                state.refreshMetricSettings()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI
    // ─────────────────────────────────────────────────────────────────────────

    val scrollState = rememberScrollState()

    // Report scroll position for setup coach overlay
    LaunchedEffect(scrollState.value) {
        if (TourManager.isActive() && TourManager.currentPhase() == CoachPhase.SETUP) {
            SetupScrollState.scrollPosition = scrollState.value
        }
    }
    val sections = remember { DataSettingsSections.getAllSections() }
    val weatherMetrics = remember { DataSettingsSections.weatherMetrics }
    val hasAnyWearable = connectedWearables.isNotEmpty()
    val locationEnabled = state.isLocationEnabled()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // Info card
        HeroCard {
            Text(
                "Data Collection",
                color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Connect WHOOP or Health Connect in the Connections section to enable wearable data. Some toggles require phone permissions.",
                color = AppTheme.BodyTextColor,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // Render sections
        for (section in sections) {
            HeroCard {
                Text(
                    section.title,
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(12.dp))

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
                        // Permission request handlers
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
                        Spacer(Modifier.height(12.dp))
                        Divider(color = AppTheme.SubtleTextColor.copy(alpha = 0.2f))
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
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
// Helper Functions
// ─────────────────────────────────────────────────────────────────────────────

private fun handleToggleResult(
    result: DataSettingsToggleHandler.ToggleResult,
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

