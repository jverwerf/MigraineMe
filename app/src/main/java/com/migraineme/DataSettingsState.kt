package com.migraineme

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Manages all state for the DataSettings screen.
 *
 * This class holds:
 * - Metric settings from Supabase
 * - Permission states
 * - Connected wearables
 * - Menstruation settings
 *
 * The UI layer observes these flows and reacts to changes.
 */
class DataSettingsState(private val context: Context) {

    private val appContext = context.applicationContext
    private val edge = EdgeFunctionsService()

    companion object {
        private const val TAG = "DataSettingsState"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Metric Settings
    // ─────────────────────────────────────────────────────────────────────────

    private val _metricSettings = MutableStateFlow<Map<String, EdgeFunctionsService.MetricSettingResponse>>(emptyMap())
    val metricSettings: StateFlow<Map<String, EdgeFunctionsService.MetricSettingResponse>> = _metricSettings.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────
    // Connected Wearables
    // ─────────────────────────────────────────────────────────────────────────

    private val _connectedWearables = MutableStateFlow<List<WearableSource>>(emptyList())
    val connectedWearables: StateFlow<List<WearableSource>> = _connectedWearables.asStateFlow()

    private val _whoopConnected = MutableStateFlow(false)
    val whoopConnected: StateFlow<Boolean> = _whoopConnected.asStateFlow()

    private val _healthConnectWearablesConnected = MutableStateFlow(false)
    val healthConnectWearablesConnected: StateFlow<Boolean> = _healthConnectWearablesConnected.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────
    // Health Connect Specific Permissions
    // ─────────────────────────────────────────────────────────────────────────

    private val _nutritionPermissionGranted = MutableStateFlow(false)
    val nutritionPermissionGranted: StateFlow<Boolean> = _nutritionPermissionGranted.asStateFlow()

    private val _menstruationPermissionGranted = MutableStateFlow(false)
    val menstruationPermissionGranted: StateFlow<Boolean> = _menstruationPermissionGranted.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────
    // Menstruation Settings
    // ─────────────────────────────────────────────────────────────────────────

    private val _menstruationSettings = MutableStateFlow<MenstruationSettings?>(null)
    val menstruationSettings: StateFlow<MenstruationSettings?> = _menstruationSettings.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────
    // Pending Toggles (metrics that were toggled but awaiting permission)
    // ─────────────────────────────────────────────────────────────────────────

    private val _pendingEnables = MutableStateFlow<Set<String>>(emptySet())

    fun markPendingEnable(metric: String) {
        _pendingEnables.value = _pendingEnables.value + metric
    }

    private fun clearPendingEnable(metric: String) {
        _pendingEnables.value = _pendingEnables.value - metric
    }

    fun isPendingEnable(metric: String): Boolean = metric in _pendingEnables.value

    // ─────────────────────────────────────────────────────────────────────────
    // Derived State
    // ─────────────────────────────────────────────────────────────────────────

    fun hasAnyWearable(): Boolean = _connectedWearables.value.isNotEmpty()

    /**
     * Check if location metric is enabled.
     * Looks up directly by metric name in the settings map.
     */
    fun isLocationEnabled(): Boolean {
        return _metricSettings.value["user_location_daily"]?.enabled ?: false
    }

    fun isMetricEnabled(metric: String): Boolean {
        return _metricSettings.value[metric]?.enabled ?: false
    }

    fun getPreferredSource(metric: String): String? {
        return _metricSettings.value[metric]?.preferredSource
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data Loading
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Load all settings from Supabase and refresh permission states.
     */
    suspend fun loadAll() {
        _isLoading.value = true
        try {
            // Load in parallel
            loadMetricSettings()
            refreshWearableConnections()
            refreshHealthConnectPermissions()
            loadMenstruationSettings()
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Refresh only the metric settings from Supabase.
     */
    suspend fun refreshMetricSettings() {
        loadMetricSettings()
    }

    private suspend fun loadMetricSettings() {
        withContext(Dispatchers.IO) {
            try {
                val settings = edge.getMetricSettings(appContext)
                _metricSettings.value = settings.associateBy { it.metric }
                Log.d(TAG, "Loaded ${settings.size} metric settings")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load metric settings: ${e.message}", e)
            }
        }
    }

    /**
     * Refresh wearable connection states.
     */
    suspend fun refreshWearableConnections() {
        withContext(Dispatchers.IO) {
            // Check WHOOP
            _whoopConnected.value = WhoopTokenStore(appContext).load() != null

            // Check Health Connect wearables
            _healthConnectWearablesConnected.value =
                DataSettingsPermissionHelper.hasHealthConnectWearablesPermission(appContext)

            // Update combined list
            _connectedWearables.value = buildList {
                if (_whoopConnected.value) add(WearableSource.WHOOP)
                if (_healthConnectWearablesConnected.value) add(WearableSource.HEALTH_CONNECT)
            }
        }
    }

    /**
     * Refresh Health Connect specific permissions.
     */
    suspend fun refreshHealthConnectPermissions() {
        withContext(Dispatchers.IO) {
            _nutritionPermissionGranted.value =
                DataSettingsPermissionHelper.hasHealthConnectNutritionPermission(appContext)
            _menstruationPermissionGranted.value =
                DataSettingsPermissionHelper.hasHealthConnectMenstruationPermission(appContext)
        }
    }

    /**
     * Load menstruation settings from Supabase.
     */
    suspend fun loadMenstruationSettings() {
        withContext(Dispatchers.IO) {
            try {
                val accessToken = SessionStore.getValidAccessToken(appContext)
                if (accessToken != null) {
                    _menstruationSettings.value =
                        SupabaseMenstruationService(appContext).getSettings(accessToken)
                } else {
                    _menstruationSettings.value = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load menstruation settings: ${e.message}", e)
                _menstruationSettings.value = null
            }
        }
    }

    /**
     * Update menstruation settings (local state only - Supabase update handled separately).
     */
    fun updateMenstruationSettings(settings: MenstruationSettings) {
        _menstruationSettings.value = settings
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Worker Sync
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sync workers with current Supabase settings.
     * Returns true if any Supabase settings were changed (requiring UI refresh).
     */
    suspend fun syncWorkers(): Boolean {
        return withContext(Dispatchers.IO) {
            val result = DataSettingsWorkerSyncHelper.syncFromSupabaseTruth(
                context = appContext,
                edge = edge,
                metricSettingsMap = _metricSettings.value
            )
            result.changedSupabase
        }
    }

    /**
     * Auto-enable screen time if permission was just granted.
     */
    suspend fun autoEnableScreenTimeIfPermissionGranted() {
        withContext(Dispatchers.IO) {
            if (DataSettingsPermissionHelper.hasScreenTimePermission(appContext)) {
                val screenTimeEnabled = _metricSettings.value["screen_time_daily"]?.enabled ?: false

                if (!screenTimeEnabled) {
                    Log.d(TAG, "Auto-enabling screen time after permission grant")
                    edge.upsertMetricSetting(
                        context = appContext,
                        metric = "screen_time_daily",
                        enabled = true
                    )
                    loadMetricSettings() // Refresh
                }
            }
        }
    }

    /**
     * Auto-enable location if permission was just granted and toggle was pending.
     */
    suspend fun autoEnableLocationIfPermissionGranted() {
        if (!isPendingEnable("user_location_daily")) return
        withContext(Dispatchers.IO) {
            if (DataSettingsPermissionHelper.hasLocationPermission(appContext)) {
                Log.d(TAG, "Auto-enabling location after permission grant")
                edge.upsertMetricSetting(
                    context = appContext,
                    metric = "user_location_daily",
                    enabled = true
                )
                LocationDailySyncWorker.runOnce(appContext)
                clearPendingEnable("user_location_daily")
                loadMetricSettings()
            }
        }
    }

    /**
     * Auto-enable ambient noise if permissions granted and toggle was pending.
     */
    suspend fun autoEnableAmbientNoiseIfPermissionGranted() {
        if (!isPendingEnable("ambient_noise_samples")) return
        withContext(Dispatchers.IO) {
            if (DataSettingsPermissionHelper.hasMicrophonePermission(appContext) &&
                DataSettingsPermissionHelper.isBatteryOptimizationExempt(appContext)) {
                Log.d(TAG, "Auto-enabling ambient noise after permission grant")
                edge.upsertMetricSetting(
                    context = appContext,
                    metric = "ambient_noise_samples",
                    enabled = true
                )
                MetricToggleHelper.toggle(appContext, "ambient_noise_samples", true)
                clearPendingEnable("ambient_noise_samples")
                loadMetricSettings()
            }
        }
    }

    /**
     * Auto-enable nutrition with HC source when HC nutrition permission is granted
     * but nutrition is not yet enabled. This handles the "HC doesn't auto-link" case.
     */
    suspend fun autoEnableNutritionIfHcPermissionGranted() {
        withContext(Dispatchers.IO) {
            if (DataSettingsPermissionHelper.hasHealthConnectNutritionPermission(appContext)) {
                val nutritionSetting = _metricSettings.value["nutrition"]
                val nutritionEnabled = nutritionSetting?.enabled ?: false

                if (!nutritionEnabled) {
                    Log.d(TAG, "Auto-enabling nutrition after HC nutrition permission detected")
                    edge.upsertMetricSetting(
                        context = appContext,
                        metric = "nutrition",
                        enabled = true,
                        preferredSource = PhoneSource.HEALTH_CONNECT.key
                    )
                    MetricToggleHelper.toggle(appContext, "nutrition", true)
                    NutritionSyncScheduler.schedule(appContext)
                    loadMetricSettings()
                }
            }
        }
    }
}

