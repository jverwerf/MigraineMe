package com.migraineme

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles metric toggle logic for DataSettings.
 *
 * SINGLE SOURCE OF TRUTH for what happens when a metric is toggled.
 * Separates the toggle logic from the UI layer.
 */
object DataSettingsToggleHandler {

    private const val TAG = "DataSettingsToggle"

    /**
     * Metrics that support phone-based sleep tracking.
     * These can be sourced from either a wearable or phone usage stats.
     */
    private val phoneSleepMetrics = setOf(
        "sleep_duration_daily",
        "fell_asleep_time_daily",
        "woke_up_time_daily"
    )

    /**
     * Phone behavior metrics collected by PhoneBehaviorSyncWorker.
     * These are triggered via FCM sync_hourly — no dedicated worker scheduling needed.
     */
    private val phoneBehaviorMetrics = setOf(
        "phone_brightness_daily",
        "phone_volume_daily",
        "phone_dark_mode_daily",
        "phone_unlock_daily"
    )

    /**
     * Result of a toggle operation
     */
    sealed class ToggleResult {
        object Success : ToggleResult()
        data class NeedsPermission(val permissionType: PermissionType) : ToggleResult()
        data class Error(val message: String) : ToggleResult()
    }

    enum class PermissionType {
        SCREEN_TIME,
        LOCATION,
        BACKGROUND_LOCATION,
        MICROPHONE,
        BATTERY_OPTIMIZATION,
        HEALTH_CONNECT_NUTRITION,
        HEALTH_CONNECT_MENSTRUATION
    }

    /**
     * Toggle a metric on/off.
     *
     * This method:
     * 1. Checks permissions (returns NeedsPermission if missing)
     * 2. Updates Supabase via EdgeFunctionsService
     * 3. Schedules/cancels workers as needed
     * 4. Handles metric dependencies (e.g., stress requires HRV + resting HR)
     *
     * @return ToggleResult indicating success, permission needed, or error
     */
    suspend fun toggleMetric(
        context: Context,
        metric: String,
        enabled: Boolean,
        preferredSource: String?,
        metricSettingsMap: Map<String, EdgeFunctionsService.MetricSettingResponse>
    ): ToggleResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val edge = EdgeFunctionsService()

        try {
            // Check permissions before enabling
            if (enabled) {
                val permissionResult = checkPermissionsForMetric(appContext, metric, preferredSource)
                if (permissionResult != null) {
                    return@withContext permissionResult
                }
            }

            // Handle metric-specific toggle logic
            when {
                metric == "menstruation" -> return@withContext toggleMenstruation(appContext, edge, enabled, preferredSource)
                metric == "nutrition" -> return@withContext toggleNutrition(appContext, edge, enabled, preferredSource)
                metric == "user_location_daily" -> return@withContext toggleLocation(appContext, edge, enabled)
                metric == "ambient_noise_samples" -> return@withContext toggleAmbientNoise(appContext, edge, enabled)
                metric == "screen_time_daily" -> return@withContext toggleScreenTime(appContext, edge, enabled)
                metric == "screen_time_late_night" -> return@withContext toggleScreenTimeLateNight(appContext, edge, enabled)
                metric in phoneBehaviorMetrics -> return@withContext togglePhoneBehavior(appContext, edge, metric, enabled)
                metric in phoneSleepMetrics -> return@withContext togglePhoneSleepMetric(appContext, edge, metric, enabled, preferredSource)
                else -> {
                    // Standard metric toggle
                    val success = edge.upsertMetricSetting(
                        context = appContext,
                        metric = metric,
                        enabled = enabled,
                        preferredSource = preferredSource
                    )

                    if (!success) {
                        return@withContext ToggleResult.Error("Failed to update setting")
                    }

                    // Handle stress index dependency
                    if (!enabled && (metric == "hrv_daily" || metric == "resting_hr_daily")) {
                        disableStressIfNeeded(appContext, edge, metricSettingsMap, preferredSource)
                    }

                    return@withContext ToggleResult.Success
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Toggle failed for $metric: ${e.message}", e)
            return@withContext ToggleResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Check if permissions are granted for enabling a metric.
     * Returns null if all permissions are granted, or ToggleResult.NeedsPermission if not.
     */
    private suspend fun checkPermissionsForMetric(
        context: Context,
        metric: String,
        preferredSource: String?
    ): ToggleResult.NeedsPermission? {
        return when {
            metric == "screen_time_daily" || metric == "screen_time_late_night" -> {
                if (!DataSettingsPermissionHelper.hasScreenTimePermission(context)) {
                    ToggleResult.NeedsPermission(PermissionType.SCREEN_TIME)
                } else null
            }
            metric in phoneSleepMetrics && preferredSource == "phone" -> {
                if (!DataSettingsPermissionHelper.hasScreenTimePermission(context)) {
                    ToggleResult.NeedsPermission(PermissionType.SCREEN_TIME)
                } else null
            }
            // Phone unlock metric needs PACKAGE_USAGE_STATS (same as screen time)
            metric == "phone_unlock_daily" -> {
                if (!DataSettingsPermissionHelper.hasScreenTimePermission(context)) {
                    ToggleResult.NeedsPermission(PermissionType.SCREEN_TIME)
                } else null
            }
            metric == "user_location_daily" -> {
                if (!DataSettingsPermissionHelper.hasLocationPermission(context)) {
                    ToggleResult.NeedsPermission(PermissionType.LOCATION)
                } else null
            }
            metric == "ambient_noise_samples" -> {
                when {
                    !DataSettingsPermissionHelper.hasMicrophonePermission(context) ->
                        ToggleResult.NeedsPermission(PermissionType.MICROPHONE)
                    !DataSettingsPermissionHelper.isBatteryOptimizationExempt(context) ->
                        ToggleResult.NeedsPermission(PermissionType.BATTERY_OPTIMIZATION)
                    else -> null
                }
            }
            metric == "nutrition" -> {
                // HC nutrition permission is managed via Connections screen.
                // If HC is connected, auto-enable handles the rest.
                null
            }
            else -> null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Metric-specific toggle handlers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Toggle a phone-or-wearable sleep metric.
     * When source is "phone", triggers PhoneSleepSyncWorker.
     * When source is a wearable, behaves like a standard wearable toggle.
     */
    private suspend fun togglePhoneSleepMetric(
        context: Context,
        edge: EdgeFunctionsService,
        metric: String,
        enabled: Boolean,
        preferredSource: String?
    ): ToggleResult {
        return try {
            val success = edge.upsertMetricSetting(
                context = context,
                metric = metric,
                enabled = enabled,
                preferredSource = preferredSource
            )

            if (!success) {
                return ToggleResult.Error("Failed to update setting")
            }

            // Trigger phone sleep worker when enabling with phone source
            if (enabled && preferredSource == "phone") {
                PhoneSleepSyncWorker.runOnce(context)
            }

            ToggleResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Phone sleep metric toggle failed for $metric: ${e.message}", e)
            ToggleResult.Error(e.message ?: "Failed to toggle $metric")
        }
    }

    private suspend fun toggleMenstruation(
        context: Context,
        edge: EdgeFunctionsService,
        enabled: Boolean,
        preferredSource: String?
    ): ToggleResult {
        return try {
            if (enabled) {
                PredictedMenstruationHelper.ensureExists(context)
                MetricToggleHelper.toggle(context, "menstruation", true)
                MenstruationSyncScheduler.schedule(context)
            } else {
                PredictedMenstruationHelper.delete(context)
                MetricToggleHelper.toggle(context, "menstruation", false)
            }

            edge.upsertMetricSetting(
                context = context,
                metric = "menstruation",
                enabled = enabled,
                preferredSource = preferredSource
            )

            ToggleResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Menstruation toggle failed: ${e.message}", e)
            ToggleResult.Error(e.message ?: "Failed to toggle menstruation")
        }
    }

    private suspend fun toggleNutrition(
        context: Context,
        edge: EdgeFunctionsService,
        enabled: Boolean,
        preferredSource: String?
    ): ToggleResult {
        return try {
            edge.upsertMetricSetting(
                context = context,
                metric = "nutrition",
                enabled = enabled,
                preferredSource = preferredSource
            )

            if (enabled) {
                MetricToggleHelper.toggle(context, "nutrition", true)
                if (preferredSource == PhoneSource.HEALTH_CONNECT.key) {
                    NutritionSyncScheduler.schedule(context)
                }
            } else {
                MetricToggleHelper.toggle(context, "nutrition", false)
                NutritionSyncScheduler.cancel(context)
            }

            ToggleResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Nutrition toggle failed: ${e.message}", e)
            ToggleResult.Error(e.message ?: "Failed to toggle nutrition")
        }
    }

    private suspend fun toggleLocation(
        context: Context,
        edge: EdgeFunctionsService,
        enabled: Boolean
    ): ToggleResult {
        return try {
            edge.upsertMetricSetting(
                context = context,
                metric = "user_location_daily",
                enabled = enabled,
                preferredSource = null
            )

            if (enabled) {
                LocationDailySyncWorker.runOnce(context)
            }

            ToggleResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Location toggle failed: ${e.message}", e)
            ToggleResult.Error(e.message ?: "Failed to toggle location")
        }
    }

    private suspend fun toggleAmbientNoise(
        context: Context,
        edge: EdgeFunctionsService,
        enabled: Boolean
    ): ToggleResult {
        return try {
            edge.upsertMetricSetting(
                context = context,
                metric = "ambient_noise_samples",
                enabled = enabled,
                preferredSource = null
            )

            MetricToggleHelper.toggle(context, "ambient_noise_samples", enabled)

            ToggleResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Ambient noise toggle failed: ${e.message}", e)
            ToggleResult.Error(e.message ?: "Failed to toggle ambient noise")
        }
    }

    private suspend fun toggleScreenTime(
        context: Context,
        edge: EdgeFunctionsService,
        enabled: Boolean
    ): ToggleResult {
        return try {
            edge.upsertMetricSetting(
                context = context,
                metric = "screen_time_daily",
                enabled = enabled,
                preferredSource = null
            )

            MetricToggleHelper.toggle(context, "screen_time_daily", enabled)

            ToggleResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Screen time toggle failed: ${e.message}", e)
            ToggleResult.Error(e.message ?: "Failed to toggle screen time")
        }
    }

    private suspend fun toggleScreenTimeLateNight(
        context: Context,
        edge: EdgeFunctionsService,
        enabled: Boolean
    ): ToggleResult {
        return try {
            edge.upsertMetricSetting(
                context = context,
                metric = "screen_time_late_night",
                enabled = enabled,
                preferredSource = null
            )

            MetricToggleHelper.toggle(context, "screen_time_late_night", enabled)

            ToggleResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Late night screen time toggle failed: ${e.message}", e)
            ToggleResult.Error(e.message ?: "Failed to toggle late night screen time")
        }
    }

    /**
     * Toggle a phone behavior metric (brightness, volume, dark mode, unlocks).
     * These are collected by PhoneBehaviorSyncWorker which runs on FCM sync_hourly.
     * No dedicated worker scheduling needed — the worker checks metric_settings each run.
     */
    private suspend fun togglePhoneBehavior(
        context: Context,
        edge: EdgeFunctionsService,
        metric: String,
        enabled: Boolean
    ): ToggleResult {
        return try {
            edge.upsertMetricSetting(
                context = context,
                metric = metric,
                enabled = enabled,
                preferredSource = null
            )

            MetricToggleHelper.toggle(context, metric, enabled)

            ToggleResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Phone behavior toggle failed for $metric: ${e.message}", e)
            ToggleResult.Error(e.message ?: "Failed to toggle $metric")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Dependency handlers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Disable stress index if its dependencies (HRV or Resting HR) are disabled.
     */
    private suspend fun disableStressIfNeeded(
        context: Context,
        edge: EdgeFunctionsService,
        metricSettingsMap: Map<String, EdgeFunctionsService.MetricSettingResponse>,
        preferredSource: String?
    ) {
        val stressEnabled = metricSettingsMap["stress_index_daily"]?.enabled ?: false
        if (stressEnabled) {
            Log.d(TAG, "Auto-disabling stress_index_daily due to missing dependency")
            edge.upsertMetricSetting(
                context = context,
                metric = "stress_index_daily",
                enabled = false,
                preferredSource = preferredSource
            )
        }
    }

    /**
     * Check if stress index dependencies are met.
     */
    fun areStressDependenciesMet(
        metricSettingsMap: Map<String, EdgeFunctionsService.MetricSettingResponse>
    ): Boolean {
        val hrvEnabled = metricSettingsMap["hrv_daily"]?.enabled ?: true
        val restingHrEnabled = metricSettingsMap["resting_hr_daily"]?.enabled ?: true
        return hrvEnabled && restingHrEnabled
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Source change handler
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Change the preferred source for a metric.
     */
    suspend fun changeMetricSource(
        context: Context,
        metric: String,
        newSource: String,
        currentEnabled: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val success = EdgeFunctionsService().upsertMetricSetting(
                context = context,
                metric = metric,
                enabled = currentEnabled,
                preferredSource = newSource
            )

            // If switching to phone source and metric is enabled, trigger phone sleep worker
            if (success && currentEnabled && metric in phoneSleepMetrics && newSource == "phone") {
                PhoneSleepSyncWorker.runOnce(context)
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to change source for $metric: ${e.message}", e)
            false
        }
    }
}

