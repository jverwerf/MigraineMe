package com.migraineme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path

/**
 * The app's curve.
 *
 * The Migraine Timeline has always drawn its metric lines as Catmull-Rom
 * cubics rather than straight segments, and that is the look the history
 * graphs now share, so a line means the same thing wherever it appears.
 * Both helpers used to live privately in InsightsReportScreen.kt; they sit
 * here so the timeline and the Monitor graphs draw from one definition
 * instead of drifting apart.
 */

/**
 * Height of the SMOOTHED curve at x — the curve that is actually drawn, not the
 * straight line between readings.
 *
 * smoothPath lays a cubic through each pair of points, so between two readings
 * the drawn line bulges away from their straight interpolation. A marker placed
 * with straight-line maths therefore sits beside the curve rather than on it,
 * which is exactly what made the label markers look like they pointed at
 * nothing. This walks the same cubic and solves it for x, so the marker lands on
 * the line the user can see.
 */
internal fun curveYAt(points: List<Offset>, targetX: Float): Float {
    if (points.isEmpty()) return 0f
    if (points.size == 1 || targetX <= points.first().x) return points.first().y
    if (targetX >= points.last().x) return points.last().y
    for (i in 0 until points.size - 1) {
        val p1 = points[i]
        val p2 = points[i + 1]
        if (targetX < p1.x || targetX > p2.x) continue
        if (points.size == 2) {
            val t = if (p2.x == p1.x) 0f else (targetX - p1.x) / (p2.x - p1.x)
            return p1.y + (p2.y - p1.y) * t
        }
        val p0 = points.getOrElse(i - 1) { p1 }
        val p3 = points.getOrElse(i + 2) { p2 }
        val c1x = p1.x + (p2.x - p0.x) / 6f
        val c1y = p1.y + (p2.y - p0.y) / 6f
        val c2x = p2.x - (p3.x - p1.x) / 6f
        val c2y = p2.y - (p3.y - p1.y) / 6f
        fun at(t: Float, a: Float, b: Float, c: Float, d: Float): Float {
            val u = 1f - t
            return u * u * u * a + 3f * u * u * t * b + 3f * u * t * t * c + t * t * t * d
        }
        // x is monotonic across the segment, so a bisection converges.
        var lo = 0f
        var hi = 1f
        repeat(24) {
            val mid = (lo + hi) / 2f
            if (at(mid, p1.x, c1x, c2x, p2.x) < targetX) lo = mid else hi = mid
        }
        return at((lo + hi) / 2f, p1.y, c1y, c2y, p2.y)
    }
    return points.last().y
}

internal fun smoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 2) {
        path.lineTo(points[1].x, points[1].y)
        return path
    }
    for (i in 0 until points.size - 1) {
        val p0 = points.getOrElse(i - 1) { points[i] }
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points.getOrElse(i + 2) { p2 }
        path.cubicTo(
            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
            p2.x, p2.y,
        )
    }
    return path
}

