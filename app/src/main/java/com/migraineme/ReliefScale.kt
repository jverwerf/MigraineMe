package com.migraineme

import androidx.compose.ui.graphics.Color

/** How much relief a medicine or relief method provided — per-log value, not a pool characteristic. */
enum class ReliefScale(val display: String, val color: Color) {
    NONE("None",  Color(0xFF666666)),
    LOW("Low",    Color(0xFFE57373)),
    MILD("Mild",  Color(0xFFFFB74D)),
    HIGH("High",  Color(0xFF81C784));

    companion object {
        fun fromString(s: String?): ReliefScale =
            entries.find { it.name.equals(s, ignoreCase = true) } ?: NONE
    }
}
