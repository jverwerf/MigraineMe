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
    const val METRIC_STRESS = "stress"
    const val METRIC_HIGH_HR_ZONES = "high_hr_zones"
    const val METRIC_STEPS = "steps"
    const val METRIC_WEIGHT = "weight"
    const val METRIC_BODY_FAT = "body_fat"
    const val METRIC_BLOOD_PRESSURE = "blood_pressure"
    const val METRIC_BLOOD_GLUCOSE = "blood_glucose"

    val ALL_PHYSICAL_METRICS: List<String> = listOf(
        METRIC_RECOVERY, METRIC_HRV, METRIC_RESTING_HR,
        METRIC_SPO2, METRIC_SKIN_TEMP, METRIC_RESPIRATORY_RATE,
        METRIC_STRESS, METRIC_HIGH_HR_ZONES, METRIC_STEPS,
        METRIC_WEIGHT, METRIC_BODY_FAT,
        METRIC_BLOOD_PRESSURE, METRIC_BLOOD_GLUCOSE
    )

    val DEFAULT_DISPLAY_METRICS: List<String> = listOf(
        METRIC_RECOVERY, METRIC_HRV, METRIC_RESTING_HR
    )

    val GRAPHABLE_METRICS: List<String> = listOf(
        METRIC_RECOVERY, METRIC_HRV, METRIC_RESTING_HR,
        METRIC_SPO2, METRIC_SKIN_TEMP, METRIC_RESPIRATORY_RATE,
        METRIC_STRESS, METRIC_HIGH_HR_ZONES, METRIC_STEPS,
        METRIC_WEIGHT, METRIC_BODY_FAT, METRIC_BLOOD_GLUCOSE
    )

    fun labelFor(metric: String): String = when (metric) {
        METRIC_RECOVERY -> "Recovery"
        METRIC_HRV -> "HRV"
        METRIC_RESTING_HR -> "Resting HR"
        METRIC_SPO2 -> "SpO2"
        METRIC_SKIN_TEMP -> "Skin Temp"
        METRIC_RESPIRATORY_RATE -> "Resp. Rate"
        METRIC_STRESS -> "Stress"
        METRIC_HIGH_HR_ZONES -> "High HR Zones"
        METRIC_STEPS -> "Steps"
        METRIC_WEIGHT -> "Weight"
        METRIC_BODY_FAT -> "Body Fat"
        METRIC_BLOOD_PRESSURE -> "Blood Pressure"
        METRIC_BLOOD_GLUCOSE -> "Blood Glucose"
        else -> metric
    }

    fun unitFor(metric: String): String = when (metric) {
        METRIC_RECOVERY -> "%"
        METRIC_HRV -> "ms"
        METRIC_RESTING_HR -> "bpm"
        METRIC_SPO2 -> "%"
        METRIC_SKIN_TEMP -> "°C"
        METRIC_RESPIRATORY_RATE -> "bpm"
        METRIC_STRESS -> ""
        METRIC_HIGH_HR_ZONES -> "min"
        METRIC_STEPS -> ""
        METRIC_WEIGHT -> "kg"
        METRIC_BODY_FAT -> "%"
        METRIC_BLOOD_PRESSURE -> "mmHg"
        METRIC_BLOOD_GLUCOSE -> "mg/dL"
        else -> ""
    }

    fun colorFor(metric: String): Color = when (metric) {
        METRIC_RECOVERY -> Color(0xFF81C784)
        METRIC_HRV -> Color(0xFF4FC3F7)
        METRIC_RESTING_HR -> Color(0xFFFF8A65)
        METRIC_SPO2 -> Color(0xFF7986CB)
        METRIC_SKIN_TEMP -> Color(0xFFFFB74D)
        METRIC_RESPIRATORY_RATE -> Color(0xFF9575CD)
        METRIC_STRESS -> Color(0xFFE57373)
        METRIC_HIGH_HR_ZONES -> Color(0xFFFF7043)
        METRIC_STEPS -> Color(0xFF66BB6A)
        METRIC_WEIGHT -> Color(0xFF78909C)
        METRIC_BODY_FAT -> Color(0xFFBA68C8)
        METRIC_BLOOD_PRESSURE -> Color(0xFFEF5350)
        METRIC_BLOOD_GLUCOSE -> Color(0xFFFFCA28)
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
        METRIC_STRESS -> "stress_index_daily"
        METRIC_HIGH_HR_ZONES -> "time_in_high_hr_zones_daily"
        METRIC_STEPS -> "steps_daily"
        METRIC_WEIGHT -> "weight_daily"
        METRIC_BODY_FAT -> "body_fat_daily"
        METRIC_BLOOD_PRESSURE -> "blood_pressure_daily"
        METRIC_BLOOD_GLUCOSE -> "blood_glucose_daily"
        else -> ""
    }
}
