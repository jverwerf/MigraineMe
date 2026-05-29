package com.migraineme

import androidx.compose.ui.graphics.Color

object PhysicalCardConfig {
    // Metric keys
    const val METRIC_RECOVERY = "recovery"
    const val METRIC_HRV = "hrv"
    const val METRIC_RESTING_HR = "resting_hr"
    const val METRIC_SPO2 = "spo2"
    const val METRIC_SKIN_TEMP = "skin_temp"
    const val METRIC_RESPIRATORY_RATE = "respiratory_rate"
    const val METRIC_STRAIN = "strain"
    const val METRIC_HIGH_HR_ZONES = "high_hr_zones"
    const val METRIC_STEPS = "steps"

    val ALL_PHYSICAL_METRICS: List<String> = listOf(
        METRIC_RECOVERY, METRIC_HRV, METRIC_RESTING_HR,
        METRIC_SPO2, METRIC_SKIN_TEMP, METRIC_RESPIRATORY_RATE,
        METRIC_STRAIN, METRIC_HIGH_HR_ZONES, METRIC_STEPS
    )

    val DEFAULT_DISPLAY_METRICS: List<String> = listOf(
        METRIC_RECOVERY, METRIC_HRV, METRIC_RESTING_HR
    )

    val GRAPHABLE_METRICS: List<String> = listOf(
        METRIC_RECOVERY, METRIC_HRV, METRIC_RESTING_HR,
        METRIC_SPO2, METRIC_SKIN_TEMP, METRIC_RESPIRATORY_RATE,
        METRIC_STRAIN, METRIC_HIGH_HR_ZONES, METRIC_STEPS
    )

    fun labelFor(metric: String): String = when (metric) {
        METRIC_RECOVERY -> "Recovery"
        METRIC_HRV -> "HRV"
        METRIC_RESTING_HR -> "Resting HR"
        METRIC_SPO2 -> "SpO2"
        METRIC_SKIN_TEMP -> "Skin Temp"
        METRIC_RESPIRATORY_RATE -> "Resp. Rate"
        METRIC_STRAIN -> "Strain"
        METRIC_HIGH_HR_ZONES -> "High HR Zones"
        METRIC_STEPS -> "Steps"
        else -> metric
    }

    fun unitFor(metric: String): String = when (metric) {
        METRIC_RECOVERY -> "%"
        METRIC_HRV -> "ms"
        METRIC_RESTING_HR -> "bpm"
        METRIC_SPO2 -> "%"
        METRIC_SKIN_TEMP -> "°C"
        METRIC_RESPIRATORY_RATE -> "bpm"
        METRIC_STRAIN -> "kJ"
        METRIC_HIGH_HR_ZONES -> "min"
        METRIC_STEPS -> ""
        else -> ""
    }

    fun colorFor(metric: String): Color = when (metric) {
        METRIC_RECOVERY -> Color(0xFF81C784)
        METRIC_HRV -> Color(0xFF4FC3F7)
        METRIC_RESTING_HR -> Color(0xFFFF8A65)
        METRIC_SPO2 -> Color(0xFF7986CB)
        METRIC_SKIN_TEMP -> Color(0xFFFFB74D)
        METRIC_RESPIRATORY_RATE -> Color(0xFF9575CD)
        METRIC_STRAIN -> Color(0xFFFF8A80)
        METRIC_HIGH_HR_ZONES -> Color(0xFFFF7043)
        METRIC_STEPS -> Color(0xFF66BB6A)
        else -> Color(0xFF81C784)
    }

    /** Maps config metric key to Supabase table name for metric_settings lookup */
    fun metricToTable(metric: String): String = when (metric) {
        METRIC_RECOVERY -> "recovery_score_daily"
        METRIC_HRV -> "hrv_daily"
        METRIC_RESTING_HR -> "resting_hr_daily"
        METRIC_SPO2 -> "spo2_daily"
        METRIC_SKIN_TEMP -> "skin_temp_daily"
        METRIC_RESPIRATORY_RATE -> "respiratory_rate_daily"
        METRIC_STRAIN -> "strain_daily"
        METRIC_HIGH_HR_ZONES -> "time_in_high_hr_zones_daily"
        METRIC_STEPS -> "steps_daily"
        else -> ""
    }
}
