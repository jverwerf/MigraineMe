package com.migraineme

import java.time.LocalDate

/**
 * Data class for menstruation settings
 *
 * Stored in Supabase menstruation_settings table
 */
data class MenstruationSettings(
    val lastMenstruationDate: LocalDate?,
    val avgCycleLength: Int,
    val autoUpdateAverage: Boolean
)