package com.migraineme

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// ---------- Stats helpers (public) ----------
fun List<Double>.mean(): Double? = if (isEmpty()) null else sum() / size
fun List<Double>.std(): Double? {
    if (size < 2) return 0.0
    val m = mean() ?: return 0.0
    val v = map { (it - m) * (it - m) }.sum() / (size - 1)
    return sqrt(v)
}

/**
 * Rolling mean/std over a trailing window (inclusive).
 * For i < window-1, we use the slice [0..i] (shorter warm-up window).
 */
private fun rollingStats(points: List<Double>, window: Int): Pair<List<Double?>, List<Double?>> {
    if (points.isEmpty()) return emptyList<Double?>() to emptyList<Double?>()
    val means = MutableList<Double?>(points.size) { null }
    val stds  = MutableList<Double?>(points.size) { null }

    var sum = 0.0
    var sumSq = 0.0
    val q: ArrayDeque<Double> = ArrayDeque()

    for (i in points.indices) {
        val v = points[i]
        q.addLast(v)
        sum += v
        sumSq += v * v
        if (q.size > window) {
            val old = q.removeFirst()
            sum -= old
            sumSq -= old * old
        }
        val n = q.size
        val mean = sum / n
        val variance = if (n > 1) (sumSq - (sum * sum) / n) / (n - 1) else 0.0
        val std = sqrt(max(variance, 0.0))
        means[i] = mean
        stds[i] = std
    }
    return means to stds
}

// Format a y-value nicely (0 or 1 decimal depending on range)
private fun formatY(v: Double, range: Double): String =
    if (range >= 50) String.format("%.0f", v) else String.format("%.1f", v)

// Extract a compact day label from ISO ("2025-09-06T13:00") -> "06"
private fun dayLabel(iso: String): String = try { iso.substring(8, 10) } catch (_: Exception) { "" }

/**
 * BandLineChart with:
 *  - Purple line
 *  - Rolling per-point band (mean ± std, window=168)
 *  - Faint grid
 *  - X axis day-of-month labels (from timeIso if provided)
 *  - Y axis numeric ticks
 *  - NO axis lines (only labels)
 */
@Composable
fun BandLineChart(
    modifier: Modifier,
    points: List<Double>,
    timeIso: List<String>? = null,   // optional timestamps for x-labels
) {
    if (points.isEmpty()) { Box(modifier) {}; return }

    val purpleLine = Color(0xFF6200EE) // Material Purple 500

    // 7 days * 24 hours = 168 points; clamp to list size if shorter
    val window = min(168, points.size)
    val (means, stds) = rollingStats(points, window)

    // y-range should include all band edges and series
    var yMin = Double.POSITIVE_INFINITY
    var yMax = Double.NEGATIVE_INFINITY
    for (i in points.indices) {
        val m = means[i] ?: points[i]
        val s = stds[i] ?: 0.0
        yMin = min(yMin, min(points[i], m - s))
        yMax = max(yMax, max(points[i], m + s))
    }
    if (yMin == Double.POSITIVE_INFINITY) { yMin = 0.0; yMax = 1.0 }
    if (yMax - yMin < 1e-6) { yMax += 1.0; yMin -= 1.0 }

    // layout paddings (extra left for y labels, bottom for x labels)
    val padLeft = 48f
    val padRight = 10f
    val padTop = 6f
    val padBottom = 22f

    // grid/axis/text paints (very light) — bigger font sizes now
    val gridColor = Color(0x14000000)   // very faint grid
    val textColor = Color(0x99000000)   // darker for readability
    val textSizePx = 14f                // ↑ bigger labels
    val textPaint = Paint().apply {
        isAntiAlias = true
        color = textColor.toArgb()
        textAlign = Paint.Align.CENTER
        textSize = textSizePx
    }
    val yTextPaint = Paint().apply {
        isAntiAlias = true
        color = textColor.toArgb()
        textAlign = Paint.Align.RIGHT
        textSize = textSizePx
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cw = w - padLeft - padRight
        val ch = h - padTop - padBottom

        fun x(i: Int): Float {
            return if (points.size == 1) padLeft + cw / 2f
            else padLeft + (i.toFloat() / (points.size - 1).toFloat()) * cw
        }
        fun y(v: Double): Float {
            val t = ((v - yMin) / (yMax - yMin)).coerceIn(0.0, 1.0)
            return (padTop + ch * (1f - t.toFloat()))
        }

        // --- grid (no axis lines) ---
        // Y ticks at 0%, 25%, 50%, 75%, 100%
        val yTicks = listOf(0.0, 0.25, 0.5, 0.75, 1.0).map { yMin + (yMax - yMin) * it }
        for (v in yTicks) {
            val yy = y(v)
            drawLine(color = gridColor, start = Offset(padLeft, yy), end = Offset(padLeft + cw, yy), strokeWidth = 1f)
            // y labels (right aligned near left padding)
            drawContext.canvas.nativeCanvas.drawText(
                formatY(v, yMax - yMin),
                padLeft - 6f,
                yy + 4f,
                yTextPaint
            )
        }

        // X ticks — 5 evenly spaced labels using provided times if available
        val xTickCount = 5
        for (t in 0 until xTickCount) {
            val idx = ((points.size - 1) * t / (xTickCount - 1).toFloat()).toInt().coerceIn(0, points.lastIndex)
            val xx = x(idx)
            drawLine(color = gridColor, start = Offset(xx, padTop), end = Offset(xx, padTop + ch), strokeWidth = 1f)

            val label = timeIso?.getOrNull(idx)?.let { dayLabel(it) } ?: idx.toString()
            // place under axis
            drawContext.canvas.nativeCanvas.drawText(label, xx, padTop + ch + 16f, textPaint)
        }

        // --- draw rolling band as colored vertical strips per step ---
        for (i in 1 until points.size) {
            val m = means[i] ?: continue
            val s = stds[i] ?: 0.0
            val z = if (s > 0.0) (points[i] - m) / s else 0.0
            val bandColor = when {
                abs(z) <= 1.0 -> Color(0x334CAF50) // green translucent
                abs(z) <= 3.0 -> Color(0x33FFC107) // amber
                else          -> Color(0x33F44336) // red
            }
            val x0 = x(i - 1)
            val x1 = x(i)
            val upper = y(m + s)
            val lower = y(m - s)

            val path = Path().apply {
                moveTo(x0, upper)
                lineTo(x1, upper)
                lineTo(x1, lower)
                lineTo(x0, lower)
                close()
            }
            drawPath(path = path, color = bandColor)
        }

        // Optional: faint guide at latest rolling mean
        means.lastOrNull()?.let { latestMean ->
            val yMean = y(latestMean)
            drawLine(
                color = Color(0x22000000),
                start = Offset(padLeft, yMean),
                end = Offset(padLeft + cw, yMean),
                strokeWidth = 1f
            )
        }

        // --- series line (purple) ---
        val path = Path()
        path.moveTo(x(0), y(points[0]))
        for (i in 1 until points.size) {
            path.lineTo(x(i), y(points[i]))
        }
        drawPath(path = path, color = purpleLine, style = Stroke(width = 3f))

        // last point marker
        val lastX = x(points.lastIndex)
        val lastY = y(points.last())
        drawCircle(color = purpleLine, radius = 4f, center = Offset(lastX, lastY))
    }
}
