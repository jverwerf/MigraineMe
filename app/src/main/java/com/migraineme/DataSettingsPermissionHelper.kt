package com.migraineme

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Centralized permission checking for DataSettings.
 *
 * SINGLE SOURCE OF TRUTH for permission checks.
 * All permission logic should go through this helper.
 */
object DataSettingsPermissionHelper {

    // ─────────────────────────────────────────────────────────────────────────
    // Location Permissions
    // ─────────────────────────────────────────────────────────────────────────

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    fun hasBackgroundLocationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not needed on Android 9 and below
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Microphone Permission
    // ─────────────────────────────────────────────────────────────────────────

    fun hasMicrophonePermission(context: Context): Boolean {
        return MicrophonePermissionHelper.hasPermission(context)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Battery Optimization
    // ─────────────────────────────────────────────────────────────────────────

    fun isBatteryOptimizationExempt(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Throwable) {
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Screen Time Permission
    // ─────────────────────────────────────────────────────────────────────────

    fun hasScreenTimePermission(context: Context): Boolean {
        return ScreenTimePermissionHelper.hasPermission(context)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Health Connect Permissions
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun hasHealthConnectWearablesPermission(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
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

    suspend fun hasHealthConnectNutritionPermission(context: Context): Boolean {
        return HealthConnectPermissionHelper.hasNutritionPermission(context)
    }

    suspend fun hasHealthConnectMenstruationPermission(context: Context): Boolean {
        return HealthConnectPermissionHelper.hasMenstruationPermission(context)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connected Wearables Helper
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getConnectedWearables(context: Context): List<WearableSource> {
        return buildList {
            // Check WHOOP
            if (WhoopTokenStore(context).load() != null) {
                add(WearableSource.WHOOP)
            }
            // Check Health Connect
            if (hasHealthConnectWearablesPermission(context)) {
                add(WearableSource.HEALTH_CONNECT)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Metric-specific permission checks
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Check if all required permissions are granted for a specific metric.
     * Returns true if the metric can be enabled.
     */
    fun canEnableMetric(context: Context, metric: String): Boolean {
        return when (metric) {
            "screen_time_daily" -> hasScreenTimePermission(context)
            "user_location_daily" -> hasLocationPermission(context)
            "ambient_noise_samples" -> hasMicrophonePermission(context) && isBatteryOptimizationExempt(context)
            else -> true
        }
    }

    /**
     * Get list of missing permissions for a metric.
     * Returns empty list if all permissions are granted.
     */
    fun getMissingPermissions(context: Context, metric: String): List<String> {
        return buildList {
            when (metric) {
                "screen_time_daily" -> {
                    if (!hasScreenTimePermission(context)) add("Usage access")
                }
                "user_location_daily" -> {
                    if (!hasLocationPermission(context)) add("Location")
                    if (!hasBackgroundLocationPermission(context)) add("Background location")
                }
                "ambient_noise_samples" -> {
                    if (!hasMicrophonePermission(context)) add("Microphone")
                    if (!isBatteryOptimizationExempt(context)) add("Battery optimization exemption")
                }
            }
        }
    }
}
