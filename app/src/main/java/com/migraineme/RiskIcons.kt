package com.migraineme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.dp

/**
 * Geometric cheese wedge — triangle with holes. Represents tyramine.
 */
@Composable
fun CheeseIcon(color: Color, size: Dp = 13.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val holeBg = Color(0xFF1E0A2E)
        val triangle = Path().apply {
            moveTo(w * 0.0625f, h * 0.8125f)
            lineTo(w * 0.5f, h * 0.125f)
            lineTo(w * 0.9375f, h * 0.8125f)
            close()
        }
        drawPath(triangle, color)
        drawCircle(holeBg, radius = w * 0.08f, center = Offset(w * 0.4375f, h * 0.5625f))
        drawCircle(holeBg, radius = w * 0.0625f, center = Offset(w * 0.6563f, h * 0.7188f))
    }
}

/**
 * Geometric wine glass — goblet silhouette. Represents alcohol.
 */
@Composable
fun WineGlassIcon(color: Color, size: Dp = 13.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        // Bowl
        val bowl = Path().apply {
            moveTo(w * 0.2813f, h * 0.0938f)
            lineTo(w * 0.7188f, h * 0.0938f)
            lineTo(w * 0.5938f, h * 0.4688f)
            quadraticBezierTo(w * 0.5f, h * 0.5625f, w * 0.4063f, h * 0.4688f)
            close()
        }
        drawPath(bowl, color)
        // Stem
        drawRect(color, topLeft = Offset(w * 0.4375f, h * 0.5313f), size = Size(w * 0.125f, h * 0.2188f))
        // Base
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.3125f, h * 0.7813f),
            size = Size(w * 0.375f, h * 0.0938f),
            cornerRadius = CornerRadius(w * 0.05f)
        )
    }
}

/**
 * Geometric wheat stalk — stem with grain kernels. Represents gluten.
 */
@Composable
fun WheatIcon(color: Color, size: Dp = 13.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        // Main stem
        drawLine(color, Offset(w * 0.5f, h * 0.1875f), Offset(w * 0.5f, h * 0.9063f), strokeWidth = w * 0.075f)
        // Grain kernels — ellipses on each side
        val kernelYs = listOf(0.2188f, 0.375f, 0.5313f)
        kernelYs.forEach { yFrac ->
            val cy = h * yFrac
            // Left kernel
            drawOval(color, topLeft = Offset(w * 0.12f, cy - h * 0.045f), size = Size(w * 0.32f, h * 0.09f))
            // Right kernel
            drawOval(color, topLeft = Offset(w * 0.56f, cy - h * 0.045f), size = Size(w * 0.32f, h * 0.09f))
        }
    }
}

/**
 * Geometric flask — Erlenmeyer silhouette. Represents histamine.
 */
@Composable
fun FlaskIcon(color: Color, size: Dp = 13.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        // Neck
        drawRect(color, topLeft = Offset(w * 0.40f, h * 0.10f), size = Size(w * 0.20f, h * 0.30f))
        // Body (triangle flask)
        val body = Path().apply {
            moveTo(w * 0.40f, h * 0.40f)
            lineTo(w * 0.60f, h * 0.40f)
            lineTo(w * 0.88f, h * 0.88f)
            lineTo(w * 0.12f, h * 0.88f)
            close()
        }
        drawPath(body, color)
        // Rim cap
        drawRect(color, topLeft = Offset(w * 0.34f, h * 0.06f), size = Size(w * 0.32f, h * 0.08f))
    }
}

/**
 * Vertical risk bar — height indicates level.
 */
@Composable
fun RiskBar(color: Color, level: String, maxHeight: Dp = 12.dp) {
    val fraction = when (level) {
        "high" -> 1f
        "medium" -> 0.67f
        "low" -> 0.33f
        else -> 0f
    }
    if (fraction > 0f) {
        val barHeight = maxHeight * fraction
        Canvas(modifier = Modifier.width(3.dp).height(barHeight)) {
            drawRoundRect(color, cornerRadius = CornerRadius(this.size.width / 2f))
        }
    }
}

/**
 * Combined icon + vertical bar badge for search results.
 * Shows nothing if level is "none".
 */
@Composable
fun TyramineRiskBadge(color: Color, level: String) {
    if (level == "none") return
    Row(verticalAlignment = Alignment.Bottom) {
        Spacer(Modifier.width(5.dp))
        CheeseIcon(color, 13.dp)
        Spacer(Modifier.width(1.dp))
        RiskBar(color, level)
    }
}

@Composable
fun AlcoholRiskBadge(color: Color, level: String) {
    if (level == "none") return
    Row(verticalAlignment = Alignment.Bottom) {
        Spacer(Modifier.width(5.dp))
        WineGlassIcon(color, 13.dp)
        Spacer(Modifier.width(1.dp))
        RiskBar(color, level)
    }
}

@Composable
fun GlutenRiskBadge(color: Color, level: String) {
    if (level == "none") return
    Row(verticalAlignment = Alignment.Bottom) {
        Spacer(Modifier.width(5.dp))
        WheatIcon(color, 13.dp)
        Spacer(Modifier.width(1.dp))
        RiskBar(color, level)
    }
}

@Composable
fun HistamineRiskBadge(color: Color, level: String) {
    if (level == "none") return
    Row(verticalAlignment = Alignment.Bottom) {
        Spacer(Modifier.width(5.dp))
        FlaskIcon(color, 13.dp)
        Spacer(Modifier.width(1.dp))
        RiskBar(color, level)
    }
}

/**
 * Legend shown above food search results: explains the compound icons
 * (tyramine, alcohol, gluten, histamine) and what the bar heights mean.
 */
@Composable
fun FoodRiskLegend() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(AppTheme.AccentPurple.copy(alpha = 0.07f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            t("What the icons mean"),
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendCompound(t("Tyramine")) { CheeseIcon(AppTheme.BodyTextColor, 12.dp) }
            LegendCompound(t("Alcohol")) { WineGlassIcon(AppTheme.BodyTextColor, 12.dp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendCompound(t("Gluten")) { WheatIcon(AppTheme.BodyTextColor, 12.dp) }
            LegendCompound(t("Histamine")) { FlaskIcon(AppTheme.BodyTextColor, 12.dp) }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(t("Level"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            LegendLevel("low", "Low")
            LegendLevel("medium", "Medium")
            LegendLevel("high", "High")
        }
        Text(
            t("A taller, warmer bar means more of that compound in this food."),
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun LegendCompound(name: String, icon: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        icon()
        Text(name, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LegendLevel(level: String, label: String) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        RiskBar(riskLevelColor(level), level, 11.dp)
        Text(t(label), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * All four exposures in one row, ALWAYS drawn. The search-result badges hide
 * themselves at "none", which reads as "we did not check" rather than "this is
 * clean" — in a list of foods you are about to log, the absence of a flag has
 * to be visible. A "none" icon is dimmed and carries no bar.
 */
@Composable
fun AllRiskIcons(risks: FoodRiskResult?, isClassifying: Boolean = false) {
    if (risks == null) {
        if (isClassifying) {
            Spacer(Modifier.width(6.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                color = AppTheme.AccentPurple,
                strokeWidth = 1.5.dp
            )
        }
        return
    }
    Row(verticalAlignment = Alignment.Bottom) {
        AlwaysRiskBadge(risks.tyramine) { c, sz -> CheeseIcon(c, sz) }
        AlwaysRiskBadge(risks.alcohol) { c, sz -> WineGlassIcon(c, sz) }
        AlwaysRiskBadge(risks.gluten) { c, sz -> WheatIcon(c, sz) }
        AlwaysRiskBadge(risks.histamine) { c, sz -> FlaskIcon(c, sz) }
    }
}

@Composable
private fun AlwaysRiskBadge(level: String, icon: @Composable (Color, Dp) -> Unit) {
    val color = if (level == "none") AppTheme.SubtleTextColor.copy(alpha = 0.35f)
                else riskLevelColor(level)
    Row(verticalAlignment = Alignment.Bottom) {
        Spacer(Modifier.width(5.dp))
        icon(color, 13.dp)
        Spacer(Modifier.width(1.dp))
        RiskBar(color, level)
    }
}

/** Colour by severity level, matching iOS: green = low, amber = medium, red = high. */
fun riskLevelColor(level: String): Color = when (level) {
    "high" -> Color(0xFFE57373)
    "medium" -> Color(0xFFFFB74D)
    "low" -> Color(0xFF81C784)
    else -> Color.Unspecified
}


// ---------------------------------------------------------------------------
// Exposure meters: the one way the four trigger flags are shown on every Diet
// surface. Each flag is its object icon from brainy-icons, a word label, the
// level in words, and the app's 4 dp track filled a third / two thirds / fully.
// Colour is severity only (the Home screen's red / amber / green), so "high" is
// the same red on every screen. All four are always drawn: "None" is dimmed,
// never hidden, so a clean food reads as checked rather than unchecked.
// ---------------------------------------------------------------------------

private data class ExposureSpec(val label: String, val drawable: Int)

private val EXPOSURE_SPECS = listOf(
    ExposureSpec("Tyramine", R.drawable.brainy_risk_tyramine),
    ExposureSpec("Alcohol", R.drawable.brainy_risk_alcohol),
    ExposureSpec("Gluten", R.drawable.brainy_risk_gluten),
    ExposureSpec("Histamine", R.drawable.brainy_risk_histamine),
)

/** Rank 0..3 as stored on nutrition rows and rollups, back to its level word. */
fun exposureLevelFromRank(rank: Int): String = when (rank) {
    3 -> "high"; 2 -> "medium"; 1 -> "low"; else -> "none"
}

@Composable
fun RiskExposureMeters(
    risks: FoodRiskResult?,
    isClassifying: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (risks == null) {
        if (isClassifying) {
            Row(modifier = modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), AppTheme.AccentPurple, strokeWidth = 1.5.dp)
                Spacer(Modifier.width(6.dp))
                Text(t("Checking trigger flags…"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            }
        }
        return
    }
    RiskExposureMeters(risks.tyramine, risks.alcohol, risks.gluten, risks.histamine, modifier)
}

@Composable
fun RiskExposureMeters(
    tyramine: String,
    alcohol: String,
    gluten: String,
    histamine: String,
    modifier: Modifier = Modifier
) {
    val levels = listOf(tyramine, alcohol, gluten, histamine)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (rowIdx in 0 until 2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (colIdx in 0 until 2) {
                    val i = rowIdx * 2 + colIdx
                    ExposureMeter(EXPOSURE_SPECS[i], levels[i], Modifier.weight(1f))
                }
            }
        }
    }
}

private val GREYSCALE = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
private fun ExposureMeter(spec: ExposureSpec, level: String, modifier: Modifier) {
    val none = level != "high" && level != "medium" && level != "low"
    val color = if (none) AppTheme.SubtleTextColor.copy(alpha = 0.45f) else riskLevelColor(level)
    val fraction = when (level) { "high" -> 1f; "medium" -> 0.67f; "low" -> 0.33f; else -> 0f }
    val levelWord = when (level) { "high" -> "High"; "medium" -> "Medium"; "low" -> "Low"; else -> "None" }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(spec.drawable),
                contentDescription = null,
                colorFilter = if (none) GREYSCALE else null,
                alpha = if (none) 0.4f else 1f,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                t(spec.label),
                color = if (none) AppTheme.SubtleTextColor.copy(alpha = 0.6f) else AppTheme.BodyTextColor,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                t(levelWord),
                color = color,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (none) FontWeight.Normal else FontWeight.Bold
                ),
                maxLines = 1
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AppTheme.TrackColor)
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(color)
                )
            }
        }
    }
}
