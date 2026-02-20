package com.migraineme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Shared custom hand-drawn icons used across MigraineHub, wizard screens, and quick-log screens.
 */
object HubIcons {

    fun DrawScope.drawMigraineStarburst(color: Color) {
        val w = size.width; val h = size.height
        val stroke = Stroke(w * 0.035f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(color.copy(alpha = 0.6f), radius = w * 0.08f, center = Offset(w * 0.5f, h * 0.5f), style = Fill)
        drawCircle(color, radius = w * 0.15f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(w * 0.03f, cap = StrokeCap.Round))
        drawLine(color, Offset(w * 0.50f, h * 0.30f), Offset(w * 0.50f, h * 0.08f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.50f, h * 0.70f), Offset(w * 0.50f, h * 0.92f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.30f, h * 0.50f), Offset(w * 0.08f, h * 0.50f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.70f, h * 0.50f), Offset(w * 0.92f, h * 0.50f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.36f, h * 0.36f), Offset(w * 0.20f, h * 0.20f), strokeWidth = w * 0.03f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.64f, h * 0.36f), Offset(w * 0.80f, h * 0.20f), strokeWidth = w * 0.03f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.36f, h * 0.64f), Offset(w * 0.20f, h * 0.80f), strokeWidth = w * 0.03f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.64f, h * 0.64f), Offset(w * 0.80f, h * 0.80f), strokeWidth = w * 0.03f, cap = StrokeCap.Round)
        drawCircle(color, radius = w * 0.24f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
    }

    fun DrawScope.drawTriggerBolt(color: Color) {
        val w = size.width; val h = size.height
        val bolt = Path().apply {
            moveTo(w * 0.55f, h * 0.05f)
            lineTo(w * 0.30f, h * 0.45f)
            lineTo(w * 0.50f, h * 0.45f)
            lineTo(w * 0.28f, h * 0.95f)
            lineTo(w * 0.70f, h * 0.40f)
            lineTo(w * 0.50f, h * 0.40f)
            lineTo(w * 0.70f, h * 0.05f)
            close()
        }
        drawPath(bolt, color, style = Fill)
    }

    fun DrawScope.drawMedicinePill(color: Color) {
        val w = size.width; val h = size.height
        val stroke = Stroke(w * 0.07f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val r = w * 0.18f
        drawRoundRect(color, topLeft = Offset(w * 0.18f, h * 0.15f),
            size = androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.70f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r), style = stroke)
        drawLine(color, Offset(w * 0.18f, h * 0.50f), Offset(w * 0.82f, h * 0.50f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.50f, h * 0.25f), Offset(w * 0.50f, h * 0.40f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.42f, h * 0.325f), Offset(w * 0.58f, h * 0.325f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
    }

    fun DrawScope.drawReliefLeaf(color: Color) {
        val w = size.width; val h = size.height
        val leaf = Path().apply {
            moveTo(w * 0.50f, h * 0.08f)
            cubicTo(w * 0.85f, h * 0.15f, w * 0.90f, h * 0.55f, w * 0.50f, h * 0.75f)
            cubicTo(w * 0.10f, h * 0.55f, w * 0.15f, h * 0.15f, w * 0.50f, h * 0.08f)
            close()
        }
        drawPath(leaf, color, style = Stroke(w * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(color, Offset(w * 0.50f, h * 0.18f), Offset(w * 0.50f, h * 0.68f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.50f, h * 0.35f), Offset(w * 0.34f, h * 0.26f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.50f, h * 0.35f), Offset(w * 0.66f, h * 0.26f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.50f, h * 0.50f), Offset(w * 0.30f, h * 0.42f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.50f, h * 0.50f), Offset(w * 0.70f, h * 0.42f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
        val drop = Path().apply {
            moveTo(w * 0.50f, h * 0.78f)
            cubicTo(w * 0.44f, h * 0.85f, w * 0.38f, h * 0.92f, w * 0.50f, h * 0.98f)
            cubicTo(w * 0.62f, h * 0.92f, w * 0.56f, h * 0.85f, w * 0.50f, h * 0.78f)
            close()
        }
        drawPath(drop, color, style = Fill)
    }

    fun DrawScope.drawProdromeEye(color: Color) {
        val w = size.width; val h = size.height
        val stroke = Stroke(w * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val top = Path().apply {
            moveTo(w * 0.05f, h * 0.50f)
            cubicTo(w * 0.25f, h * 0.15f, w * 0.75f, h * 0.15f, w * 0.95f, h * 0.50f)
        }
        val bottom = Path().apply {
            moveTo(w * 0.05f, h * 0.50f)
            cubicTo(w * 0.25f, h * 0.85f, w * 0.75f, h * 0.85f, w * 0.95f, h * 0.50f)
        }
        drawPath(top, color, style = stroke)
        drawPath(bottom, color, style = stroke)
        drawCircle(color, radius = w * 0.14f, center = Offset(w * 0.50f, h * 0.50f), style = stroke)
        drawCircle(color, radius = w * 0.06f, center = Offset(w * 0.50f, h * 0.50f), style = Fill)
        drawLine(color, Offset(w * 0.80f, h * 0.15f), Offset(w * 0.88f, h * 0.08f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.90f, h * 0.22f), Offset(w * 0.97f, h * 0.18f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.85f, h * 0.08f), Offset(w * 0.92f, h * 0.12f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
    }

    fun DrawScope.drawNotesIcon(color: Color) {
        val w = size.width; val h = size.height
        val stroke = Stroke(w * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Notepad outline
        drawRoundRect(color, topLeft = Offset(w * 0.15f, h * 0.08f),
            size = androidx.compose.ui.geometry.Size(w * 0.70f, h * 0.84f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f, w * 0.08f), style = stroke)
        // Lines
        drawLine(color, Offset(w * 0.28f, h * 0.30f), Offset(w * 0.72f, h * 0.30f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.28f, h * 0.45f), Offset(w * 0.72f, h * 0.45f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.28f, h * 0.60f), Offset(w * 0.55f, h * 0.60f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
        // Pencil
        drawLine(color, Offset(w * 0.68f, h * 0.62f), Offset(w * 0.82f, h * 0.78f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.82f, h * 0.78f), Offset(w * 0.78f, h * 0.85f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
    }

    fun DrawScope.drawReviewCheck(color: Color) {
        val w = size.width; val h = size.height
        val stroke = Stroke(w * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Circle
        drawCircle(color, radius = w * 0.40f, center = Offset(w * 0.50f, h * 0.50f), style = stroke)
        // Checkmark
        val check = Path().apply {
            moveTo(w * 0.30f, h * 0.50f)
            lineTo(w * 0.45f, h * 0.65f)
            lineTo(w * 0.72f, h * 0.35f)
        }
        drawPath(check, color, style = Stroke(w * 0.07f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    /** Location pin icon – teardrop with inner circle */
    fun DrawScope.drawLocationPin(color: Color) {
        val w = size.width; val h = size.height
        val stroke = Stroke(w * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Teardrop shape
        val pin = Path().apply {
            moveTo(w * 0.50f, h * 0.92f)
            cubicTo(w * 0.35f, h * 0.70f, w * 0.12f, h * 0.48f, w * 0.12f, h * 0.35f)
            cubicTo(w * 0.12f, h * 0.10f, w * 0.30f, h * 0.05f, w * 0.50f, h * 0.05f)
            cubicTo(w * 0.70f, h * 0.05f, w * 0.88f, h * 0.10f, w * 0.88f, h * 0.35f)
            cubicTo(w * 0.88f, h * 0.48f, w * 0.65f, h * 0.70f, w * 0.50f, h * 0.92f)
            close()
        }
        drawPath(pin, color, style = stroke)
        // Inner dot
        drawCircle(color, radius = w * 0.12f, center = Offset(w * 0.50f, h * 0.34f), style = Fill)
    }

    /** Activity pulse / heartbeat icon */
    fun DrawScope.drawActivityPulse(color: Color) {
        val w = size.width; val h = size.height
        val stroke = Stroke(w * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Running figure simplified as a pulse line
        val pulse = Path().apply {
            moveTo(w * 0.04f, h * 0.55f)
            lineTo(w * 0.25f, h * 0.55f)
            lineTo(w * 0.32f, h * 0.30f)
            lineTo(w * 0.42f, h * 0.75f)
            lineTo(w * 0.52f, h * 0.15f)
            lineTo(w * 0.62f, h * 0.65f)
            lineTo(w * 0.70f, h * 0.45f)
            lineTo(w * 0.78f, h * 0.55f)
            lineTo(w * 0.96f, h * 0.55f)
        }
        drawPath(pulse, color, style = stroke)
        // Small circle at peak
        drawCircle(color, radius = w * 0.05f, center = Offset(w * 0.52f, h * 0.15f), style = Fill)
    }

    /** Missed activity icon – cancelled/crossed-out circle */
    fun DrawScope.drawMissedActivity(color: Color) {
        val w = size.width; val h = size.height
        val stroke = Stroke(w * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Circle
        drawCircle(color, radius = w * 0.38f, center = Offset(w * 0.50f, h * 0.50f), style = stroke)
        // Diagonal cross
        drawLine(color, Offset(w * 0.28f, h * 0.28f), Offset(w * 0.72f, h * 0.72f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
        // Small dash marks for "missed" feel
        drawLine(color, Offset(w * 0.38f, h * 0.50f), Offset(w * 0.62f, h * 0.50f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
    }

    // ── Forum pinned topic icons ──

    /** Lightbulb – ideas / what works */
    fun DrawScope.drawLightbulb(color: Color) {
        val w = size.width; val h = size.height
        val stroke = Stroke(w * 0.055f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Bulb body (circle)
        drawCircle(color, radius = w * 0.28f, center = Offset(w * 0.50f, h * 0.35f), style = stroke)
        // Filament rays
        drawLine(color, Offset(w * 0.50f, h * 0.08f), Offset(w * 0.50f, h * 0.15f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.24f, h * 0.20f), Offset(w * 0.30f, h * 0.26f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.76f, h * 0.20f), Offset(w * 0.70f, h * 0.26f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
        // Neck
        drawLine(color, Offset(w * 0.38f, h * 0.60f), Offset(w * 0.38f, h * 0.72f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.62f, h * 0.60f), Offset(w * 0.62f, h * 0.72f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
        // Base lines
        drawLine(color, Offset(w * 0.36f, h * 0.76f), Offset(w * 0.64f, h * 0.76f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.40f, h * 0.84f), Offset(w * 0.60f, h * 0.84f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
        // Dot at center of bulb
        drawCircle(color, radius = w * 0.04f, center = Offset(w * 0.50f, h * 0.35f))
    }

    /** Lightning with question mark – surprising trigger */
    fun DrawScope.drawSurpriseBolt(color: Color) {
        val w = size.width; val h = size.height
        val stroke = Stroke(w * 0.055f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Bold zigzag bolt
        val bolt = Path().apply {
            moveTo(w * 0.55f, h * 0.08f)
            lineTo(w * 0.30f, h * 0.42f)
            lineTo(w * 0.48f, h * 0.42f)
            lineTo(w * 0.38f, h * 0.70f)
            lineTo(w * 0.72f, h * 0.30f)
            lineTo(w * 0.52f, h * 0.30f)
            lineTo(w * 0.65f, h * 0.08f)
            close()
        }
        drawPath(bolt, color, style = stroke)
        // Question mark dot below
        drawCircle(color, radius = w * 0.04f, center = Offset(w * 0.50f, h * 0.82f))
        // Question curve
        val q = Path().apply {
            moveTo(w * 0.40f, h * 0.88f)
            cubicTo(w * 0.40f, h * 0.84f, w * 0.60f, h * 0.84f, w * 0.60f, h * 0.88f)
            cubicTo(w * 0.60f, h * 0.92f, w * 0.50f, h * 0.94f, w * 0.50f, h * 0.96f)
        }
        drawPath(q, color, style = Stroke(w * 0.04f, cap = StrokeCap.Round))
    }

    /** Briefcase – work / career */
    fun DrawScope.drawBriefcase(color: Color) {
        val w = size.width; val h = size.height
        val stroke = Stroke(w * 0.055f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val r = w * 0.06f
        // Main bag body
        drawRoundRect(color, topLeft = Offset(w * 0.10f, h * 0.35f),
            size = androidx.compose.ui.geometry.Size(w * 0.80f, h * 0.50f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r), style = stroke)
        // Handle
        val handle = Path().apply {
            moveTo(w * 0.35f, h * 0.35f)
            lineTo(w * 0.35f, h * 0.22f)
            cubicTo(w * 0.35f, h * 0.16f, w * 0.65f, h * 0.16f, w * 0.65f, h * 0.22f)
            lineTo(w * 0.65f, h * 0.35f)
        }
        drawPath(handle, color, style = stroke)
        // Middle clasp line
        drawLine(color, Offset(w * 0.10f, h * 0.58f), Offset(w * 0.90f, h * 0.58f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
        // Clasp dot
        drawCircle(color, radius = w * 0.04f, center = Offset(w * 0.50f, h * 0.58f))
    }

    /** Moon with stars – sleep */
    fun DrawScope.drawMoonSleep(color: Color) {
        val w = size.width; val h = size.height
        val stroke = Stroke(w * 0.055f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Crescent moon
        val moon = Path().apply {
            moveTo(w * 0.55f, h * 0.12f)
            cubicTo(w * 0.20f, h * 0.15f, w * 0.20f, h * 0.80f, w * 0.55f, h * 0.85f)
            cubicTo(w * 0.35f, h * 0.72f, w * 0.35f, h * 0.25f, w * 0.55f, h * 0.12f)
        }
        drawPath(moon, color, style = stroke)
        // Stars — small crosses
        drawLine(color, Offset(w * 0.70f, h * 0.20f), Offset(w * 0.70f, h * 0.30f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.65f, h * 0.25f), Offset(w * 0.75f, h * 0.25f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
        // Small star
        drawLine(color, Offset(w * 0.82f, h * 0.38f), Offset(w * 0.82f, h * 0.45f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.785f, h * 0.415f), Offset(w * 0.855f, h * 0.415f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
    }

    /** Capsule with plus – medication experience */
    fun DrawScope.drawCapsulePlus(color: Color) {
        val w = size.width; val h = size.height
        val stroke = Stroke(w * 0.055f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Capsule outline (vertical)
        drawRoundRect(color, topLeft = Offset(w * 0.30f, h * 0.12f),
            size = androidx.compose.ui.geometry.Size(w * 0.40f, h * 0.70f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.20f, w * 0.20f), style = stroke)
        // Divider line across middle
        drawLine(color, Offset(w * 0.30f, h * 0.47f), Offset(w * 0.70f, h * 0.47f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
        // Fill bottom half with dots pattern to distinguish halves
        drawCircle(color, radius = w * 0.03f, center = Offset(w * 0.43f, h * 0.58f))
        drawCircle(color, radius = w * 0.03f, center = Offset(w * 0.57f, h * 0.58f))
        drawCircle(color, radius = w * 0.03f, center = Offset(w * 0.50f, h * 0.66f))
        // Plus sign below capsule
        drawLine(color, Offset(w * 0.50f, h * 0.86f), Offset(w * 0.50f, h * 0.96f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.45f, h * 0.91f), Offset(w * 0.55f, h * 0.91f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
    }

    /** Fork and leaf – food and diet */
    fun DrawScope.drawForkLeaf(color: Color) {
        val w = size.width; val h = size.height
        val stroke = Stroke(w * 0.055f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Fork (left side)
        drawLine(color, Offset(w * 0.30f, h * 0.15f), Offset(w * 0.30f, h * 0.45f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.20f, h * 0.15f), Offset(w * 0.20f, h * 0.35f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.40f, h * 0.15f), Offset(w * 0.40f, h * 0.35f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
        // Fork base
        drawLine(color, Offset(w * 0.20f, h * 0.35f), Offset(w * 0.40f, h * 0.35f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.30f, h * 0.45f), Offset(w * 0.30f, h * 0.88f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
        // Leaf (right side)
        val leaf = Path().apply {
            moveTo(w * 0.65f, h * 0.20f)
            cubicTo(w * 0.85f, h * 0.25f, w * 0.85f, h * 0.55f, w * 0.65f, h * 0.60f)
            cubicTo(w * 0.50f, h * 0.55f, w * 0.50f, h * 0.25f, w * 0.65f, h * 0.20f)
        }
        drawPath(leaf, color, style = stroke)
        // Leaf vein
        drawLine(color, Offset(w * 0.65f, h * 0.25f), Offset(w * 0.65f, h * 0.55f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
        // Leaf stem
        drawLine(color, Offset(w * 0.65f, h * 0.60f), Offset(w * 0.65f, h * 0.88f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
    }
}

