package com.migraineme

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DataSettingsWorkerSyncHelper {

    data class SyncResult(
        val changedSupabase: Boolean
    )

    suspend fun syncFromSupabaseTruth(
        context: Context,
        edge: EdgeFunctionsService,
        metricSettingsMap: Map<String, EdgeFunctionsService.MetricSettingResponse>
    ): SyncResult = withContext(Dispatchers.IO) {

        // ===== Screen Time workers =====
        val screenTimeEnabled = MetricSettingsMapHelper.isEnabled(
            map = metricSettingsMap,
            metric = "screen_time_daily",
            preferredSource = null,
            defaultValue = true
        )

        val hasUsageAccess = ScreenTimePermissionHelper.hasPermission(context)

        if (screenTimeEnabled && hasUsageAccess) {
            ScreenTimeDailySyncWorker.scheduleNext(context)
            ScreenTimeWatchdogWorker.schedule(context)
        } else {
            ScreenTimeDailySyncWorker.cancel(context)
            ScreenTimeWatchdogWorker.cancel(context)
        }

        // ===== Ambient Noise workers =====
        val ambientEnabled = MetricSettingsMapHelper.isEnabled(
            map = metricSettingsMap,
            metric = "ambient_noise_samples",
            preferredSource = null,
            defaultValue = true
        )

        val micGranted = MicrophonePermissionHelper.hasPermission(context)
        val batteryExempt = BatteryOptimizationExemptHelper.isExempt(context)

        if (ambientEnabled && micGranted && batteryExempt) {
            AmbientNoiseSampleWorker.schedule(context)
            AmbientNoiseWatchdogWorker.schedule(context)
        } else {
            AmbientNoiseSampleWorker.cancel(context)
            AmbientNoiseWatchdogWorker.cancel(context)
        }

        // ===== Stress dependency enforcement =====
        // stress_index_daily is treated as WHOOP-derived/computed in your settings model.
        val whoopKey = "whoop"

        val stressEnabled = MetricSettingsMapHelper.isEnabled(
            map = metricSettingsMap,
            metric = "stress_index_daily",
            preferredSource = whoopKey,
            defaultValue = true
        )

        val hrvEnabled = MetricSettingsMapHelper.isEnabled(
            map = metricSettingsMap,
            metric = "hrv_daily",
            preferredSource = whoopKey,
            defaultValue = true
        )

        val restingHrEnabled = MetricSettingsMapHelper.isEnabled(
            map = metricSettingsMap,
            metric = "resting_hr_daily",
            preferredSource = whoopKey,
            defaultValue = true
        )

        var changedSupabase = false

        if (stressEnabled && (!hrvEnabled || !restingHrEnabled)) {
            // Auto-disable stress in Supabase since it cannot compute without both inputs.
            runCatching {
                edge.upsertMetricSetting(
                    context = context,
                    metric = "stress_index_daily",
                    enabled = false,
                    preferredSource = whoopKey
                )
                changedSupabase = true
            }
        }

        SyncResult(changedSupabase = changedSupabase)
    }
}

/**
 * Kept as a tiny helper so we don't spread battery-exemption details across screens.
 * Uses existing platform logic only (no Supabase changes).
 */
object BatteryOptimizationExemptHelper {
    fun isExempt(context: Context): Boolean {
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
    }
}
