package com.migraineme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
