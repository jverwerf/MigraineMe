package com.migraineme

/**
 * Defines which wearable sources support which metrics.
 * Used to filter the source picker options in DataSettingsScreen.
 *
 * SINGLE SOURCE OF TRUTH for metric-to-source mapping.
 * Update this file when adding new metrics or sources.
 */
object MetricSourceSupport {

    // Metrics that WHOOP provides
    private val whoopMetrics = setOf(
        "sleep_duration_daily",
        "sleep_score_daily",
        "sleep_efficiency_daily",
        "sleep_stages_daily",
        "sleep_disturbances_daily",
        "fell_asleep_time_daily",
        "woke_up_time_daily",
        "recovery_score_daily",
        "resting_hr_daily",
        "hrv_daily",
        "skin_temp_daily",
        "spo2_daily",
        "time_in_high_hr_zones_daily",
        "activity_hr_zones_sessions",
        "stress_index_daily"
    )

    // Metrics that Health Connect provides
    private val healthConnectMetrics = setOf(
        "sleep_duration_daily",
        "sleep_stages_daily",
        "fell_asleep_time_daily",
        "woke_up_time_daily",
        "resting_hr_daily",
        "hrv_daily",
        "skin_temp_daily",
        "spo2_daily",
        "time_in_high_hr_zones_daily",
        "steps_daily",
        "weight_daily",
        "body_fat_daily",
        "hydration_daily",
        "blood_pressure_daily",
        "blood_glucose_daily",
        "respiratory_rate_daily",
        "stress_index_daily"
    )

    // Metrics that Oura provides
    private val ouraMetrics = setOf(
        "sleep_duration_daily",
        "sleep_score_daily",
        "sleep_efficiency_daily",
        "sleep_stages_daily",
        "sleep_disturbances_daily",
        "fell_asleep_time_daily",
        "woke_up_time_daily",
        "recovery_score_daily",
        "resting_hr_daily",
        "hrv_daily",
        "skin_temp_daily",
        "spo2_daily",
        "steps_daily",
        "stress_index_daily",
        "respiratory_rate_daily",
        "strain_daily",
        "time_in_high_hr_zones_daily"
    )

    // Metrics that Polar provides
    private val polarMetrics = setOf(
        "sleep_duration_daily",
        "sleep_score_daily",
        "sleep_efficiency_daily",
        "sleep_stages_daily",
        "sleep_disturbances_daily",
        "fell_asleep_time_daily",
        "woke_up_time_daily",
        "recovery_score_daily",
        "resting_hr_daily",
        "hrv_daily",
        "respiratory_rate_daily",
        "steps_daily",
        "strain_daily",
        "time_in_high_hr_zones_daily",
        "skin_temp_daily",
        "spo2_daily"
    )

    private val garminMetrics = setOf(
        "sleep_duration_daily",
        "sleep_score_daily",
        "sleep_efficiency_daily",
        "sleep_stages_daily",
        "sleep_disturbances_daily",
        "fell_asleep_time_daily",
        "woke_up_time_daily",
        "recovery_score_daily",
        "resting_hr_daily",
        "hrv_daily",
        "respiratory_rate_daily",
        "spo2_daily",
        "steps_daily",
        "strain_daily",
        "stress_index_daily",
        "skin_temp_daily"
    )

    fun supportsMetric(source: WearableSource, metric: String): Boolean {
        return when (source) {
            WearableSource.WHOOP -> metric in whoopMetrics
            WearableSource.HEALTH_CONNECT -> metric in healthConnectMetrics
            WearableSource.OURA -> metric in ouraMetrics
            WearableSource.POLAR -> metric in polarMetrics
            WearableSource.GARMIN -> metric in garminMetrics
        }
    }

    fun getSupportedSources(metric: String): List<WearableSource> {
        return buildList {
            if (metric in whoopMetrics) add(WearableSource.WHOOP)
            if (metric in healthConnectMetrics) add(WearableSource.HEALTH_CONNECT)
            if (metric in ouraMetrics) add(WearableSource.OURA)
            if (metric in polarMetrics) add(WearableSource.POLAR)
            if (metric in garminMetrics) add(WearableSource.GARMIN)
        }
    }
}
