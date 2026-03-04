package com.migraineme

import androidx.compose.ui.graphics.Color

/**
 * MetricFormatter — Formats metric values for display based on their unit.
 *
 * Replaces all per-config displayValue() when blocks, formatPhysicalValue(),
 * and similar formatting logic scattered across Monitor screens and graphs.
 *
 * The template `unit` field (after normalisation in MetricRegistry) drives formatting.
 */
object MetricFormatter {

    /**
     * Format a numeric value for display on Monitor cards and data screens.
     *
     * @param value    The numeric value (for time-type metrics, decimal hours)
     * @param unit     The normalised unit from MetricRegistry (e.g. "h", "%", "ms", "time")
     * @param column   The metric_column name, used for exposure metric detection
     * @return         Formatted string like "7.5h", "62%", "11:30 PM", "High"
     */
    fun format(value: Double, unit: String, column: String = ""): String = when {
        // Exposure metrics (0–3 categorical): tyramine, alcohol, gluten
        isExposureColumn(column) -> formatExposureLevel(value.toInt())

        // Time-of-day (decimal hours, may be >24 for post-midnight bedtimes)
        unit == "time" -> formatTimeOfDay(value)

        // Percentage
        unit == "%" -> "${value.toInt()}%"

        // Hours (durations)
        unit == "h" -> formatHoursMinutes(value)

        // Milliseconds (HRV)
        unit == "ms" -> "${value.toInt()} ms"

        // Beats/breaths per minute
        unit == "bpm" -> "${value.toInt()} bpm"

        // Minutes
        unit == "min" -> "${value.toInt()} min"

        // Temperature
        unit == "°C" -> "%.1f°C".format(value)

        // Pressure
        unit == "hPa" -> "%.0f hPa".format(value)

        // Weight
        unit == "kg" -> "%.1f kg".format(value)

        // Blood pressure / glucose
        unit == "mmHg" -> "${value.toInt()} mmHg"
        unit == "mg/dL" -> "${value.toInt()} mg/dL"

        // Speed
        unit == "m/s" -> "%.1f m/s".format(value)

        // Distance / altitude
        unit == "m" -> "%.0f m".format(value)

        // Decibels
        unit == "dB" -> "%.0f dB".format(value)

        // Mass units (nutrition)
        unit == "g" -> "%.0f g".format(value)
        unit == "mg" -> "%.0f mg".format(value)
        unit == "mcg" -> "%.0f mcg".format(value)

        // Dimensionless with large values (steps, etc.)
        unit.isBlank() && value >= 1000 -> "%,.0f".format(value)

        // Dimensionless with small values (stress index, etc.)
        unit.isBlank() -> "%.0f".format(value)

        // Anything else
        else -> "%.1f $unit".format(value)
    }

    /**
     * Format a value for graph Y-axis labels (shorter, no units).
     */
    fun formatShort(value: Float, unit: String): String = when {
        unit == "%" -> "${value.toInt()}"
        unit == "h" -> "%.1f".format(value)
        unit == "time" -> {
            var h = value.toInt()
            if (h >= 24) h -= 24
            val m = ((value - value.toInt()) * 60).toInt()
            "%d:%02d".format(h, m)
        }
        unit == "hPa" -> "%.0f".format(value)
        unit in listOf("m", "mg", "g", "mcg", "dB") -> "%.0f".format(value)
        unit.isBlank() && value >= 1000 -> "%,.0f".format(value)
        unit.isBlank() -> "%.0f".format(value)
        else -> "%.0f".format(value)
    }

    /**
     * Format a value for graph tooltips (with unit).
     */
    fun formatTooltip(value: Float, unit: String, column: String = ""): String =
        format(value.toDouble(), unit, column)

    // ── Exposure metrics ─────────────────────────────────────────────────────

    fun isExposureColumn(column: String): Boolean =
        column.startsWith("max_") && column.endsWith("_exposure")

    fun formatExposureLevel(level: Int): String = when (level) {
        3 -> "High"
        2 -> "Medium"
        1 -> "Low"
        else -> "None"
    }

    /**
     * Get the color for a risk/exposure level.
     * Preserves existing RiskColors behavior.
     */
    fun exposureLevelColor(column: String, level: Int): Color = when {
        column.contains("tyramine") -> when (level) {
            3 -> Color(0xFFEF5350); 2 -> Color(0xFFEF9A9A); 1 -> Color(0xFFFFCDD2)
            else -> Color.Unspecified
        }
        column.contains("alcohol") -> when (level) {
            3 -> Color(0xFFFF8F00); 2 -> Color(0xFFFFB74D); 1 -> Color(0xFFFFE0B2)
            else -> Color.Unspecified
        }
        column.contains("gluten") -> when (level) {
            3 -> Color(0xFF1E88E5); 2 -> Color(0xFF64B5F6); 1 -> Color(0xFFBBDEFB)
            else -> Color.Unspecified
        }
        else -> Color.Unspecified
    }

    // ── Time formatting ──────────────────────────────────────────────────────

    /**
     * Format decimal hours as time of day (e.g. 23.5 → "11:30 PM").
     * Values >24 are wrapped (e.g. 25.5 → "1:30 AM", for post-midnight bedtimes).
     */
    private fun formatTimeOfDay(decimalHours: Double): String {
        var h = decimalHours.toInt()
        val m = ((decimalHours - h) * 60).toInt()
        if (h >= 24) h -= 24
        val ampm = if (h < 12) "AM" else "PM"
        val h12 = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        return "$h12:%02d $ampm".format(m)
    }

    /**
     * Format decimal hours as duration (e.g. 7.75 → "7h 45m").
     * For values where "Xh Ym" is more readable than "7.8h".
     */
    fun formatHoursMinutes(hours: Double): String {
        val totalMinutes = (hours * 60).toInt()
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0 && m > 0) "${h}h ${m}m"
        else if (h > 0) "${h}h"
        else "${m}m"
    }

    // ── Blood pressure special formatting ────────────────────────────────────

    /**
     * Format blood pressure as "systolic/diastolic" (e.g. "120/80").
     * Used when both values are available.
     */
    fun formatBloodPressure(systolic: Double?, diastolic: Double?): String {
        if (systolic == null) return "—"
        return if (diastolic != null) {
            "${systolic.toInt()}/${diastolic.toInt()}"
        } else {
            "${systolic.toInt()} mmHg"
        }
    }
}
