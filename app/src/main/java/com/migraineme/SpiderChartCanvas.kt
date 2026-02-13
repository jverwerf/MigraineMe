package com.migraineme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Data for one axis of the spider chart.
 * @param label  The category name shown at the tip
 * @param value  Actual count / score
 * @param maxValue  Max value for normalisation (defaults to global max if null)
 */
data class SpiderAxis(
    val label: String,
    val value: Float,
    val maxValue: Float? = null
)

/**
 * A beautiful radar / spider chart.
 *
 * @param axes        List of 3+ axes to render
 * @param accentColor Fill & stroke colour for the data polygon
 * @param gridColor   Colour of the concentric grid rings
 * @param labelColor  Colour of the axis labels
 * @param gridRings   Number of concentric rings (default 4)
 * @param size        Canvas size
 * @param fillAlpha   Alpha of the filled polygon
 */
@Composable
fun SpiderChart(
    axes: List<SpiderAxis>,
    accentColor: Color = AppTheme.AccentPurple,
    gridColor: Color = Color.White.copy(alpha = 0.35f),
    labelColor: Color = Color.White.copy(alpha = 0.85f),
    gridRings: Int = 4,
    size: Dp = 180.dp,
    fillAlpha: Float = 0.25f,
    secondAxes: List<SpiderAxis>? = null,
    secondColor: Color = Color(0xFF81C784)
) {
    if (axes.size < 3) return

    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            val radius = (minOf(this.size.width, this.size.height) / 2f) * 0.58f
            val n = axes.size
            val globalMax = axes.maxOf { it.maxValue ?: it.value }.coerceAtLeast(1f)

            // Angle per axis (start at top, -90°)
            fun angle(i: Int): Float = (2f * PI.toFloat() * i / n) - (PI.toFloat() / 2f)

            fun pointOnAxis(i: Int, fraction: Float): Offset {
                val a = angle(i)
                return Offset(cx + radius * fraction * cos(a), cy + radius * fraction * sin(a))
            }

            // Draw concentric grid rings
            for (ring in 1..gridRings) {
                val frac = ring.toFloat() / gridRings
                val path = Path().apply {
                    for (i in 0 until n) {
                        val p = pointOnAxis(i, frac)
                        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                    }
                    close()
                }
                drawPath(path, gridColor, style = Stroke(width = 1f))
            }

            // Draw axis lines from centre to each tip
            for (i in 0 until n) {
                val tip = pointOnAxis(i, 1f)
                drawLine(gridColor.copy(alpha = 0.5f), Offset(cx, cy), tip, strokeWidth = 1f)
            }

            // Draw primary data polygon
            val dataPath = Path().apply {
                for (i in 0 until n) {
                    val maxVal = axes[i].maxValue ?: globalMax
                    val norm = (axes[i].value / maxVal).coerceIn(0f, 1f)
                    val frac = if (axes[i].value > 0f && norm < 0.08f) 0.08f else norm
                    val p = pointOnAxis(i, frac)
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            }
            drawPath(dataPath, accentColor.copy(alpha = fillAlpha), style = Fill)
            drawPath(dataPath, accentColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

            // Draw dots on primary vertices
            for (i in 0 until n) {
                val maxVal = axes[i].maxValue ?: globalMax
                val norm = (axes[i].value / maxVal).coerceIn(0f, 1f)
                val frac = if (axes[i].value > 0f && norm < 0.08f) 0.08f else norm
                val p = pointOnAxis(i, frac)

                // Relief effectiveness circles (behind dot)
                if (secondAxes != null && secondAxes.size == n) {
                    val reliefVal = secondAxes[i].value // 0-3 scale
                    val maxRelief = secondAxes[i].maxValue ?: 3f
                    val reliefFrac = (reliefVal / maxRelief).coerceIn(0f, 1f)
                    if (reliefFrac > 0f) {
                        val maxCircleRadius = radius * 0.12f
                        val circleRadius = maxCircleRadius * reliefFrac
                        drawCircle(secondColor.copy(alpha = 0.3f), circleRadius, p)
                        drawCircle(secondColor.copy(alpha = 0.6f), circleRadius, p, style = Stroke(width = 1.5f))
                    }
                }

                drawCircle(accentColor, 4f, p)
                drawCircle(Color.White, 2f, p)
            }

            // Draw labels — pass accentColor and secondAxes for relief labels
            drawLabels(axes, cx, cy, radius, n, accentColor, secondAxes) { angle(it) }
        }
    }
}

private fun DrawScope.drawLabels(
    axes: List<SpiderAxis>,
    cx: Float, cy: Float, radius: Float, n: Int,
    accentColor: Color,
    secondAxes: List<SpiderAxis>? = null,
    angle: (Int) -> Float
) {
    val density = this.size.width / 180f
    val textSize = (11f * density).coerceIn(20f, 30f)
    val reliefTextSize = (9f * density).coerceIn(16f, 24f)

    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(
            (accentColor.alpha * 255).toInt(),
            (accentColor.red * 255).toInt(),
            (accentColor.green * 255).toInt(),
            (accentColor.blue * 255).toInt()
        )
        this.textSize = textSize
        isAntiAlias = true
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    for (i in 0 until n) {
        val a = angle(i)
        val labelRadius = radius * 1.38f
        val lx = cx + labelRadius * cos(a)
        val ly = cy + labelRadius * sin(a)

        // Combined label: "Category (count)"
        val fullLabel = "${axes[i].label} (${axes[i].value.toInt()})"
        val textWidth = paint.measureText(fullLabel)

        // Truncate if needed
        val maxLabelWidth = radius * 1.1f
        val displayLabel = if (textWidth > maxLabelWidth) {
            val countSuffix = " (${axes[i].value.toInt()})"
            val availableWidth = maxLabelWidth - paint.measureText(countSuffix)
            var truncated = axes[i].label
            while (paint.measureText("$truncated…") > availableWidth && truncated.length > 2) {
                truncated = truncated.dropLast(1)
            }
            "$truncated…$countSuffix"
        } else fullLabel

        val finalWidth = paint.measureText(displayLabel)

        // Horizontal alignment
        val tx = when {
            cos(a) > 0.3f -> lx + 4f
            cos(a) < -0.3f -> lx - finalWidth - 4f
            else -> lx - finalWidth / 2f
        }
        // Vertical alignment
        val ty = when {
            sin(a) > 0.3f -> ly + textSize + 4f
            sin(a) < -0.3f -> ly - 4f
            else -> ly + textSize / 3f
        }

        drawContext.canvas.nativeCanvas.drawText(displayLabel, tx, ty, paint)

        // Draw relief effectiveness label below if secondAxes provided
        if (secondAxes != null && i < secondAxes.size) {
            val reliefVal = secondAxes[i].value
            val reliefLabel = when {
                reliefVal >= 2.5f -> "High"
                reliefVal >= 1.5f -> "Mild"
                reliefVal >= 0.5f -> "Low"
                else -> "None"
            }
            val reliefColor = when {
                reliefVal >= 2.5f -> android.graphics.Color.argb(200, 129, 199, 132)  // green
                reliefVal >= 1.5f -> android.graphics.Color.argb(200, 255, 183, 77)   // orange
                reliefVal >= 0.5f -> android.graphics.Color.argb(200, 229, 115, 115)  // red
                else -> android.graphics.Color.argb(150, 150, 150, 150)               // grey
            }
            val reliefPaint = android.graphics.Paint(paint).apply {
                this.textSize = reliefTextSize
                color = reliefColor
            }
            val rw = reliefPaint.measureText(reliefLabel)
            val rx = when {
                cos(a) > 0.3f -> tx + (finalWidth - rw) / 2f
                cos(a) < -0.3f -> tx + (finalWidth - rw) / 2f
                else -> lx - rw / 2f
            }
            drawContext.canvas.nativeCanvas.drawText(reliefLabel, rx, ty + reliefTextSize + 2f, reliefPaint)
        }
    }
}

/**
 * Stacked proportional bar for 1–2 category display (fallback when < 3 axes).
 */
@Composable
fun StackedProportionalBar(
    axes: List<SpiderAxis>,
    colors: List<Color> = listOf(Color(0xFFFF8A65), Color(0xFFBA68C8), Color(0xFF4FC3F7), Color(0xFF81C784)),
    accentColor: Color = AppTheme.AccentPurple
) {
    if (axes.isEmpty()) return
    val total = axes.sumOf { it.value.toDouble() }.toFloat().coerceAtLeast(1f)

    // Single category — stat card
    if (axes.size == 1) {
        val axis = axes[0]
        val color = colors.getOrElse(0) { accentColor }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.04f))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${axis.value.toInt()}",
                    color = color,
                    style = androidx.compose.material3.MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    axis.label,
                    color = AppTheme.BodyTextColor,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                    )
                )
                Text(
                    "100% of total",
                    color = AppTheme.SubtleTextColor,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                )
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        // Labels row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (axes.size == 1) Arrangement.Center else Arrangement.SpaceBetween
        ) {
            axes.forEachIndexed { i, axis ->
                val color = colors.getOrElse(i) { accentColor }
                Column(
                    horizontalAlignment = when {
                        axes.size == 1 -> Alignment.CenterHorizontally
                        i == 0 -> Alignment.Start
                        else -> Alignment.End
                    }
                ) {
                    Text(
                        axis.label,
                        color = color,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )
                    )
                    Text(
                        "${axis.value.toInt()} (${((axis.value / total) * 100).toInt()}%)",
                        color = AppTheme.SubtleTextColor,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Stacked bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.04f))
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                axes.forEachIndexed { i, axis ->
                    val fraction = axis.value / total
                    val color = colors.getOrElse(i) { accentColor }
                    val shape = when {
                        axes.size == 1 -> androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                        i == 0 -> androidx.compose.foundation.shape.RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
                        i == axes.lastIndex -> androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
                        else -> androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
                    }
                    Box(
                        modifier = Modifier
                            .weight(fraction.coerceAtLeast(0.01f))
                            .fillMaxHeight()
                            .clip(shape)
                            .background(color)
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Total
        Text(
            "${total.toInt()} total logged",
            color = AppTheme.SubtleTextColor,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
