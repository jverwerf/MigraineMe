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
            "What the icons mean",
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendCompound("Tyramine") { CheeseIcon(AppTheme.BodyTextColor, 12.dp) }
            LegendCompound("Alcohol") { WineGlassIcon(AppTheme.BodyTextColor, 12.dp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendCompound("Gluten") { WheatIcon(AppTheme.BodyTextColor, 12.dp) }
            LegendCompound("Histamine") { FlaskIcon(AppTheme.BodyTextColor, 12.dp) }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Level", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            LegendLevel("low", "Low")
            LegendLevel("medium", "Medium")
            LegendLevel("high", "High")
        }
        Text(
            "A taller, warmer bar means more of that compound in this food.",
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
        Text(label, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
    }
}

/** Colour by severity level, matching iOS: green = low, amber = medium, red = high. */
fun riskLevelColor(level: String): Color = when (level) {
    "high" -> Color(0xFFE57373)
    "medium" -> Color(0xFFFFB74D)
    "low" -> Color(0xFF81C784)
    else -> Color.Unspecified
}
