package com.migraineme

import androidx.compose.ui.graphics.Color

object SleepCardConfig {
    const val METRIC_DURATION = "duration"
    const val METRIC_FELL_ASLEEP = "fell_asleep"
    const val METRIC_WOKE_UP = "woke_up"
    const val METRIC_SCORE = "score"
    const val METRIC_EFFICIENCY = "efficiency"
    const val METRIC_DISTURBANCES = "disturbances"
    const val METRIC_STAGES_DEEP = "stages_deep"
    const val METRIC_STAGES_REM = "stages_rem"
    const val METRIC_STAGES_LIGHT = "stages_light"

    val ALL_SLEEP_METRICS: List<String> = listOf(
        METRIC_DURATION, METRIC_FELL_ASLEEP, METRIC_WOKE_UP,
        METRIC_SCORE, METRIC_EFFICIENCY, METRIC_DISTURBANCES,
        METRIC_STAGES_DEEP, METRIC_STAGES_REM, METRIC_STAGES_LIGHT
    )

    val DEFAULT_DISPLAY_METRICS: List<String> = listOf(
        METRIC_DURATION, METRIC_FELL_ASLEEP, METRIC_WOKE_UP
    )

    val GRAPHABLE_METRICS: List<String> = listOf(
        METRIC_DURATION, METRIC_SCORE, METRIC_EFFICIENCY,
        METRIC_DISTURBANCES, METRIC_STAGES_DEEP, METRIC_STAGES_REM, METRIC_STAGES_LIGHT
    )

    val WEARABLE_ONLY_METRICS: Set<String> = setOf(
        METRIC_SCORE, METRIC_EFFICIENCY, METRIC_DISTURBANCES,
        METRIC_STAGES_DEEP, METRIC_STAGES_REM, METRIC_STAGES_LIGHT
    )

    fun labelFor(metric: String): String = when (metric) {
        METRIC_DURATION -> "Duration"
        METRIC_FELL_ASLEEP -> "Fell Asleep"
        METRIC_WOKE_UP -> "Woke Up"
        METRIC_SCORE -> "Score"
        METRIC_EFFICIENCY -> "Efficiency"
        METRIC_DISTURBANCES -> "Disturbances"
        METRIC_STAGES_DEEP -> "Deep Sleep"
        METRIC_STAGES_REM -> "REM Sleep"
        METRIC_STAGES_LIGHT -> "Light Sleep"
        else -> metric
    }

    fun unitFor(metric: String): String = when (metric) {
        METRIC_DURATION -> "h"
        METRIC_SCORE -> "%"
        METRIC_EFFICIENCY -> "%"
        METRIC_STAGES_DEEP -> "h"
        METRIC_STAGES_REM -> "h"
        METRIC_STAGES_LIGHT -> "h"
        else -> ""
    }

    fun colorFor(metric: String): Color = when (metric) {
        METRIC_DURATION -> Color(0xFF7986CB)
        METRIC_FELL_ASLEEP -> Color(0xFF9575CD)
        METRIC_WOKE_UP -> Color(0xFF64B5F6)
        METRIC_SCORE -> Color(0xFF4FC3F7)
        METRIC_EFFICIENCY -> Color(0xFF81C784)
        METRIC_DISTURBANCES -> Color(0xFFFF8A65)
        METRIC_STAGES_DEEP -> Color(0xFF3F51B5)
        METRIC_STAGES_REM -> Color(0xFF7986CB)
        METRIC_STAGES_LIGHT -> Color(0xFFB0BEC5)
        else -> Color(0xFF7986CB)
    }
}
