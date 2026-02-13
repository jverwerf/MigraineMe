package com.migraineme

import androidx.compose.ui.graphics.Color

/**
 * Color scheme for food risk metrics (tyramine, alcohol, gluten).
 * Light → dark intensity maps to low → high risk.
 * Designed for dark purple background.
 */
object RiskColors {
    // Tyramine — warm reds
    val TyramineHigh = Color(0xFFEF5350)
    val TyramineMedium = Color(0xFFEF9A9A)
    val TyramineLow = Color(0xFFFFCDD2)

    // Alcohol — ambers/oranges
    val AlcoholHigh = Color(0xFFFF8F00)
    val AlcoholMedium = Color(0xFFFFB74D)
    val AlcoholLow = Color(0xFFFFE0B2)

    // Gluten — blues
    val GlutenHigh = Color(0xFF1E88E5)
    val GlutenMedium = Color(0xFF64B5F6)
    val GlutenLow = Color(0xFFBBDEFB)

    /** Get the color for a risk metric at a given level (0-3) */
    fun colorFor(metric: String, level: Int): Color = when (metric) {
        MonitorCardConfig.METRIC_TYRAMINE_EXPOSURE -> when (level) {
            3 -> TyramineHigh; 2 -> TyramineMedium; 1 -> TyramineLow; else -> Color.Unspecified
        }
        MonitorCardConfig.METRIC_ALCOHOL_EXPOSURE -> when (level) {
            3 -> AlcoholHigh; 2 -> AlcoholMedium; 1 -> AlcoholLow; else -> Color.Unspecified
        }
        MonitorCardConfig.METRIC_GLUTEN_EXPOSURE -> when (level) {
            3 -> GlutenHigh; 2 -> GlutenMedium; 1 -> GlutenLow; else -> Color.Unspecified
        }
        else -> Color.Unspecified
    }

    /** Format a risk level (0-3) to display text + color */
    fun formatRiskLevel(metric: String, level: Int): Pair<String, Color> {
        val label = when (level) { 3 -> "High"; 2 -> "Medium"; 1 -> "Low"; else -> "None" }
        val color = colorFor(metric, level).takeIf { it != Color.Unspecified } ?: AppTheme.SubtleTextColor
        return label to color
    }
}
