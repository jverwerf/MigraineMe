package com.migraineme

import java.time.LocalDate

data class MenstruationSettings(
    val lastMenstruationDate: LocalDate?,
    val avgCycleLength: Int,
    val autoUpdateAverage: Boolean,
    /** Adds a smaller risk bump around predicted ovulation (last period + ovulationCycleDay − 1). */
    val predictOvulation: Boolean = false,
    /** Cycle day of ovulation, day 1 = first day of the period. Server allows 5..40. */
    val ovulationCycleDay: Int = 14
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

        /** Default curve for ovulation_predicted (ovulation_decay_weights): a small mid-cycle bump. */
        val OVULATION_DEFAULT = MenstruationDecayWeights(
            dayM2 = 0.0, dayM1 = 1.5, day0 = 3.0, dayP1 = 1.5, dayP2 = 0.0
        )
    }
}
