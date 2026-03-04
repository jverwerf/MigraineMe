package com.migraineme

import java.time.LocalDate

data class MenstruationSettings(
    val lastMenstruationDate: LocalDate?,
    val avgCycleLength: Int,
    val autoUpdateAverage: Boolean
)

/**
 * Centered decay curve for predicted menstruation.
 * day_m7…day_m1 = days BEFORE predicted date
 * day_0          = predicted date
 * day_p1…day_p7 = days AFTER predicted date
 */
data class MenstruationDecayWeights(
    val dayM7: Double = 0.0,
    val dayM6: Double = 0.0,
    val dayM5: Double = 0.0,
    val dayM4: Double = 0.0,
    val dayM3: Double = 0.0,
    val dayM2: Double = 3.0,
    val dayM1: Double = 4.5,
    val day0: Double = 6.0,
    val dayP1: Double = 3.0,
    val dayP2: Double = 1.5,
    val dayP3: Double = 0.0,
    val dayP4: Double = 0.0,
    val dayP5: Double = 0.0,
    val dayP6: Double = 0.0,
    val dayP7: Double = 0.0,
) {
    companion object {
        val DEFAULT = MenstruationDecayWeights()
    }
}
