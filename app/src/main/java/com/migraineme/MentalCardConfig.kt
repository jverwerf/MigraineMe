package com.migraineme

import androidx.compose.ui.graphics.Color

/**
 * Human-readable bucket for the log-RMS noise value stored in
 * ambient_noise_index_daily. Calibrated against NIOSH/CDC everyday-sound
 * references via the dB SPL ↔ log-RMS map ln(10^((dB-90)/20)*32767+1):
 *   <50 dB (logRms<6)  Quiet      library, quiet home
 *   50-70 dB (6-8)     Moderate   conversation, office
 *   70-85 dB (8-10)    Loud       vacuum, busy street
 *   >=85 dB (>=10)     Very loud  NIOSH hazard threshold, migraine-relevant
 */
fun noiseLabel(logRms: Double): String = when {
    logRms < 6.0  -> "Quiet"
    logRms < 8.0  -> "Moderate"
    logRms < 10.0 -> "Loud"
    else          -> "Very loud"
}

object MentalCardConfig {
    // Metric keys
    const val METRIC_STRESS = "stress"
    const val METRIC_SCREEN_TIME = "screen_time"
    const val METRIC_LATE_SCREEN_TIME = "late_screen_time"
    const val METRIC_NOISE_HIGH = "noise_high"
    const val METRIC_NOISE_AVG = "noise_avg"
    const val METRIC_NOISE_LOW = "noise_low"
    const val METRIC_BRIGHTNESS = "brightness"
    const val METRIC_VOLUME = "volume"
    const val METRIC_DARK_MODE = "dark_mode"
    const val METRIC_UNLOCKS = "unlocks"

    val ALL_MENTAL_METRICS: List<String> = listOf(
        METRIC_STRESS, METRIC_SCREEN_TIME, METRIC_LATE_SCREEN_TIME,
        METRIC_NOISE_AVG, METRIC_NOISE_HIGH, METRIC_NOISE_LOW,
        METRIC_BRIGHTNESS, METRIC_VOLUME,
        METRIC_DARK_MODE, METRIC_UNLOCKS
    )

    val DEFAULT_DISPLAY_METRICS: List<String> = listOf(
        METRIC_STRESS, METRIC_SCREEN_TIME, METRIC_NOISE_AVG
    )

    val GRAPHABLE_METRICS: List<String> = listOf(
        METRIC_STRESS, METRIC_SCREEN_TIME, METRIC_LATE_SCREEN_TIME,
        METRIC_NOISE_HIGH, METRIC_NOISE_AVG, METRIC_NOISE_LOW,
        METRIC_BRIGHTNESS, METRIC_VOLUME,
        METRIC_DARK_MODE, METRIC_UNLOCKS
    )

    fun labelFor(metric: String): String = when (metric) {
        METRIC_STRESS -> "Stress"
        METRIC_SCREEN_TIME -> "Screen Time"
        METRIC_LATE_SCREEN_TIME -> "Late Screen"
        METRIC_NOISE_HIGH -> "Noise High"
        METRIC_NOISE_AVG -> "Noise Avg"
        METRIC_NOISE_LOW -> "Noise Low"
        METRIC_BRIGHTNESS -> "Brightness"
        METRIC_VOLUME -> "Volume"
        METRIC_DARK_MODE -> "Dark Mode"
        METRIC_UNLOCKS -> "Unlocks"
        else -> metric
    }

    fun unitFor(metric: String): String = when (metric) {
        METRIC_STRESS -> ""
        METRIC_SCREEN_TIME -> "h"
        METRIC_LATE_SCREEN_TIME -> "h"
        METRIC_NOISE_HIGH, METRIC_NOISE_AVG, METRIC_NOISE_LOW -> ""
        METRIC_BRIGHTNESS -> ""
        METRIC_VOLUME -> "%"
        METRIC_DARK_MODE -> "h"
        METRIC_UNLOCKS -> ""
        else -> ""
    }

    fun colorFor(metric: String): Color = when (metric) {
        METRIC_STRESS -> Color(0xFFE57373)
        METRIC_SCREEN_TIME -> Color(0xFF4FC3F7)
        METRIC_LATE_SCREEN_TIME -> Color(0xFF7986CB)
        METRIC_NOISE_HIGH -> Color(0xFFFF7043)
        METRIC_NOISE_AVG -> Color(0xFFFFB74D)
        METRIC_NOISE_LOW -> Color(0xFF66BB6A)
        METRIC_BRIGHTNESS -> Color(0xFFFFD54F)
        METRIC_VOLUME -> Color(0xFF9575CD)
        METRIC_DARK_MODE -> Color(0xFF78909C)
        METRIC_UNLOCKS -> Color(0xFFFF8A65)
        else -> Color(0xFFBA68C8)
    }

    /** Maps config metric key to Supabase table name for metric_settings lookup */
    fun metricToTable(metric: String): String = when (metric) {
        METRIC_STRESS -> "stress_index_daily"
        METRIC_SCREEN_TIME -> "screen_time_daily"
        METRIC_LATE_SCREEN_TIME -> "screen_time_late_night"
        METRIC_NOISE_HIGH, METRIC_NOISE_AVG, METRIC_NOISE_LOW -> "ambient_noise_index_daily"
        METRIC_BRIGHTNESS -> "phone_brightness_daily"
        METRIC_VOLUME -> "phone_volume_daily"
        METRIC_DARK_MODE -> "phone_dark_mode_daily"
        METRIC_UNLOCKS -> "phone_unlock_daily"
        else -> ""
    }
}
