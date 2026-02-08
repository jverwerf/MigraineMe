package com.migraineme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Custom symptom icons for MigraineMe.
 * Style: 24dp viewport, 2dp stroke, rounded caps/joins, no fill.
 */
object SymptomIcons {

    private val stroke = SolidColor(Color.White)
    private const val W = 24f
    private const val H = 24f

    // ─── PAIN CHARACTER ───────────────────────────────────────

    /** Throbbing / pulsing — heartbeat pulse wave */
    val Throbbing: ImageVector by lazy {
        ImageVector.Builder("Throbbing", W.dp, H.dp, W, H).apply {
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(2f, 12f)
                lineTo(6f, 12f)
                lineTo(8f, 6f)
                lineTo(10f, 18f)
                lineTo(12f, 4f)
                lineTo(14f, 16f)
                lineTo(16f, 10f)
                lineTo(18f, 12f)
                lineTo(22f, 12f)
            }
        }.build()
    }

    /** Pressure / squeezing — two arrows pressing inward on a circle */
    val Pressure: ImageVector by lazy {
        ImageVector.Builder("Pressure", W.dp, H.dp, W, H).apply {
            // Center circle
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                // Small circle
                moveTo(15f, 12f)
                arcTo(3f, 3f, 0f, true, true, 9f, 12f)
                arcTo(3f, 3f, 0f, true, true, 15f, 12f)
            }
            // Left arrow pressing in
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(2f, 12f)
                lineTo(6f, 12f)
                moveTo(4.5f, 10f)
                lineTo(6f, 12f)
                lineTo(4.5f, 14f)
            }
            // Right arrow pressing in
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(22f, 12f)
                lineTo(18f, 12f)
                moveTo(19.5f, 10f)
                lineTo(18f, 12f)
                lineTo(19.5f, 14f)
            }
        }.build()
    }

    /** Stabbing / sharp — lightning bolt */
    val Stabbing: ImageVector by lazy {
        ImageVector.Builder("Stabbing", W.dp, H.dp, W, H).apply {
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(13f, 2f)
                lineTo(6f, 13f)
                lineTo(12f, 13f)
                lineTo(11f, 22f)
                lineTo(18f, 11f)
                lineTo(12f, 11f)
                close()
            }
        }.build()
    }

    /** Dull ache — slow wave, gentle */
    val DullAche: ImageVector by lazy {
        ImageVector.Builder("DullAche", W.dp, H.dp, W, H).apply {
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(2f, 12f)
                curveTo(5f, 8f, 7f, 8f, 9f, 12f)
                curveTo(11f, 16f, 13f, 16f, 15f, 12f)
                curveTo(17f, 8f, 19f, 8f, 22f, 12f)
            }
        }.build()
    }

    /** Burning — flame */
    val Burning: ImageVector by lazy {
        ImageVector.Builder("Burning", W.dp, H.dp, W, H).apply {
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 22f)
                curveTo(7f, 22f, 4f, 18f, 4f, 14f)
                curveTo(4f, 10f, 8f, 6f, 12f, 2f)
                curveTo(16f, 6f, 20f, 10f, 20f, 14f)
                curveTo(20f, 18f, 17f, 22f, 12f, 22f)
            }
            // Inner flame
            path(
                stroke = stroke, strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 20f)
                curveTo(10f, 20f, 8.5f, 18.5f, 8.5f, 16.5f)
                curveTo(8.5f, 14.5f, 10f, 13f, 12f, 11f)
                curveTo(14f, 13f, 15.5f, 14.5f, 15.5f, 16.5f)
                curveTo(15.5f, 18.5f, 14f, 20f, 12f, 20f)
            }
        }.build()
    }

    // ─── ACCOMPANYING EXPERIENCE ──────────────────────────────

    /** With aura — starburst / sparkle */
    val Aura: ImageVector by lazy {
        ImageVector.Builder("Aura", W.dp, H.dp, W, H).apply {
            // Main star
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 2f); lineTo(12f, 6f)
                moveTo(12f, 18f); lineTo(12f, 22f)
                moveTo(2f, 12f); lineTo(6f, 12f)
                moveTo(18f, 12f); lineTo(22f, 12f)
            }
            // Diagonal rays
            path(
                stroke = stroke, strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4.9f, 4.9f); lineTo(7.8f, 7.8f)
                moveTo(16.2f, 16.2f); lineTo(19.1f, 19.1f)
                moveTo(4.9f, 19.1f); lineTo(7.8f, 16.2f)
                moveTo(16.2f, 7.8f); lineTo(19.1f, 4.9f)
            }
        }.build()
    }

    /** Nausea — wavy stomach / queasy swirl */
    val Nausea: ImageVector by lazy {
        ImageVector.Builder("Nausea", W.dp, H.dp, W, H).apply {
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 8f)
                curveTo(6f, 5f, 10f, 5f, 12f, 8f)
                curveTo(14f, 11f, 18f, 11f, 20f, 8f)
            }
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 13f)
                curveTo(6f, 10f, 10f, 10f, 12f, 13f)
                curveTo(14f, 16f, 18f, 16f, 20f, 13f)
            }
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 18f)
                curveTo(6f, 15f, 10f, 15f, 12f, 18f)
                curveTo(14f, 21f, 18f, 21f, 20f, 18f)
            }
        }.build()
    }

    /** Light sensitivity — sun with rays and a slash */
    val LightSensitivity: ImageVector by lazy {
        ImageVector.Builder("LightSensitivity", W.dp, H.dp, W, H).apply {
            // Sun circle
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(16f, 12f)
                arcTo(4f, 4f, 0f, true, true, 8f, 12f)
                arcTo(4f, 4f, 0f, true, true, 16f, 12f)
            }
            // Rays
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(12f, 2f); lineTo(12f, 4f)
                moveTo(12f, 20f); lineTo(12f, 22f)
                moveTo(4f, 12f); lineTo(2f, 12f)
                moveTo(20f, 12f); lineTo(22f, 12f)
            }
            // Slash through
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(4f, 4f)
                lineTo(20f, 20f)
            }
        }.build()
    }

    /** Sound sensitivity — speaker with waves and a slash */
    val SoundSensitivity: ImageVector by lazy {
        ImageVector.Builder("SoundSensitivity", W.dp, H.dp, W, H).apply {
            // Speaker body
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 9f)
                lineTo(7f, 9f)
                lineTo(11f, 5f)
                lineTo(11f, 19f)
                lineTo(7f, 15f)
                lineTo(3f, 15f)
                close()
            }
            // Sound waves
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(15f, 9f)
                curveTo(16.5f, 10f, 16.5f, 14f, 15f, 15f)
                moveTo(18f, 7f)
                curveTo(20.5f, 9f, 20.5f, 15f, 18f, 17f)
            }
            // Slash
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(4f, 4f)
                lineTo(20f, 20f)
            }
        }.build()
    }

    /** Dizziness / vertigo — spiral */
    val Dizziness: ImageVector by lazy {
        ImageVector.Builder("Dizziness", W.dp, H.dp, W, H).apply {
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 12f)
                curveTo(12f, 10f, 14f, 8f, 16f, 8f)
                curveTo(19f, 8f, 20f, 10f, 20f, 12f)
                curveTo(20f, 16f, 16f, 18f, 12f, 18f)
                curveTo(7f, 18f, 4f, 15f, 4f, 12f)
                curveTo(4f, 7f, 8f, 4f, 12f, 4f)
                curveTo(18f, 4f, 22f, 8f, 22f, 12f)
            }
        }.build()
    }

    /** Neck stiffness — neck with lock/stiff indicator */
    val NeckStiffness: ImageVector by lazy {
        ImageVector.Builder("NeckStiffness", W.dp, H.dp, W, H).apply {
            // Head circle
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(16f, 7f)
                arcTo(4f, 4f, 0f, true, false, 8f, 7f)
                arcTo(4f, 4f, 0f, true, false, 16f, 7f)
            }
            // Neck
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(9.5f, 10.5f)
                lineTo(9.5f, 16f)
                lineTo(5f, 21f)
                moveTo(14.5f, 10.5f)
                lineTo(14.5f, 16f)
                lineTo(19f, 21f)
            }
            // Stiff zigzag on neck
            path(
                stroke = stroke, strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(10.5f, 12f)
                lineTo(11.5f, 13f)
                lineTo(12.5f, 12f)
                lineTo(13.5f, 13f)
            }
        }.build()
    }

    /** Brain fog — cloud */
    val BrainFog: ImageVector by lazy {
        ImageVector.Builder("BrainFog", W.dp, H.dp, W, H).apply {
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6f, 18f)
                curveTo(3f, 18f, 2f, 16f, 2f, 14f)
                curveTo(2f, 12f, 3.5f, 10.5f, 5.5f, 10.5f)
                curveTo(5.5f, 7.5f, 8f, 5f, 11f, 5f)
                curveTo(13.5f, 5f, 15.5f, 6.5f, 16.5f, 8.5f)
                curveTo(17f, 8.2f, 17.5f, 8f, 18f, 8f)
                curveTo(20.2f, 8f, 22f, 9.8f, 22f, 12f)
                curveTo(22f, 14.2f, 20.2f, 16f, 18f, 16f)
                lineTo(18f, 18f)
                close()
            }
            // Fog lines
            path(
                stroke = stroke, strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(8f, 14f); lineTo(10f, 14f)
                moveTo(12f, 14f); lineTo(16f, 14f)
            }
        }.build()
    }

    /** Fatigue — drooping eyelid / heavy eye */
    val Fatigue: ImageVector by lazy {
        ImageVector.Builder("Fatigue", W.dp, H.dp, W, H).apply {
            // Open eye shape
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(2f, 12f)
                curveTo(5f, 7f, 9f, 5f, 12f, 5f)
                curveTo(15f, 5f, 19f, 7f, 22f, 12f)
            }
            // Drooping lid
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(2f, 12f)
                curveTo(5f, 15f, 9f, 16f, 12f, 14f)
                curveTo(15f, 12f, 19f, 13f, 22f, 12f)
            }
            // Z Z
            path(
                stroke = stroke, strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(17f, 3f); lineTo(20f, 3f); lineTo(17f, 6f); lineTo(20f, 6f)
            }
        }.build()
    }

    /** Blurred vision — eye with blur lines */
    val BlurredVision: ImageVector by lazy {
        ImageVector.Builder("BlurredVision", W.dp, H.dp, W, H).apply {
            // Eye outline
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(2f, 12f)
                curveTo(5f, 7f, 9f, 5f, 12f, 5f)
                curveTo(15f, 5f, 19f, 7f, 22f, 12f)
                curveTo(19f, 17f, 15f, 19f, 12f, 19f)
                curveTo(9f, 19f, 5f, 17f, 2f, 12f)
            }
            // Pupil
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(14.5f, 12f)
                arcTo(2.5f, 2.5f, 0f, true, true, 9.5f, 12f)
                arcTo(2.5f, 2.5f, 0f, true, true, 14.5f, 12f)
            }
            // Blur streaks
            path(
                stroke = stroke, strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(1f, 9f); lineTo(4f, 9f)
                moveTo(20f, 15f); lineTo(23f, 15f)
                moveTo(1f, 15f); lineTo(4f, 15f)
            }
        }.build()
    }

    /** Tingling / numbness — dots radiating from fingertips */
    val Tingling: ImageVector by lazy {
        ImageVector.Builder("Tingling", W.dp, H.dp, W, H).apply {
            // Hand outline (simplified)
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(8f, 22f)
                lineTo(8f, 14f)
                curveTo(8f, 13f, 6f, 13f, 6f, 14f)
                lineTo(6f, 16f)
                moveTo(8f, 14f)
                lineTo(8f, 8f)
                moveTo(8f, 9f)
                lineTo(12f, 9f)
                lineTo(12f, 14f)
                moveTo(12f, 10f)
                lineTo(16f, 10f)
                lineTo(16f, 14f)
                lineTo(12f, 14f)
                lineTo(12f, 22f)
            }
            // Tingle dots
            path(
                fill = SolidColor(Color.White),
                stroke = null
            ) {
                // Small circles as dots
                moveTo(19f, 6f); arcTo(1f, 1f, 0f, true, true, 17f, 6f); arcTo(1f, 1f, 0f, true, true, 19f, 6f)
                moveTo(21f, 9f); arcTo(1f, 1f, 0f, true, true, 19f, 9f); arcTo(1f, 1f, 0f, true, true, 21f, 9f)
                moveTo(20f, 3f); arcTo(0.8f, 0.8f, 0f, true, true, 18.4f, 3f); arcTo(0.8f, 0.8f, 0f, true, true, 20f, 3f)
                moveTo(22f, 6f); arcTo(0.8f, 0.8f, 0f, true, true, 20.4f, 6f); arcTo(0.8f, 0.8f, 0f, true, true, 22f, 6f)
            }
        }.build()
    }

    // ─── GENERIC / PICKER ICONS ─────────────────────────────

    /** Generic circle — default for custom symptoms */
    val GenericCircle: ImageVector by lazy {
        ImageVector.Builder("GenericCircle", W.dp, H.dp, W, H).apply {
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(19f, 12f)
                arcTo(7f, 7f, 0f, true, true, 5f, 12f)
                arcTo(7f, 7f, 0f, true, true, 19f, 12f)
            }
        }.build()
    }

    /** Diamond */
    val Diamond: ImageVector by lazy {
        ImageVector.Builder("Diamond", W.dp, H.dp, W, H).apply {
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 2f)
                lineTo(22f, 12f)
                lineTo(12f, 22f)
                lineTo(2f, 12f)
                close()
            }
        }.build()
    }

    /** Triangle */
    val Triangle: ImageVector by lazy {
        ImageVector.Builder("Triangle", W.dp, H.dp, W, H).apply {
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 3f)
                lineTo(21f, 21f)
                lineTo(3f, 21f)
                close()
            }
        }.build()
    }

    /** Cross / plus */
    val Cross: ImageVector by lazy {
        ImageVector.Builder("Cross", W.dp, H.dp, W, H).apply {
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(12f, 4f); lineTo(12f, 20f)
                moveTo(4f, 12f); lineTo(20f, 12f)
            }
        }.build()
    }

    /** Droplet */
    val Droplet: ImageVector by lazy {
        ImageVector.Builder("Droplet", W.dp, H.dp, W, H).apply {
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 3f)
                curveTo(8f, 8f, 5f, 12f, 5f, 15f)
                curveTo(5f, 19f, 8f, 22f, 12f, 22f)
                curveTo(16f, 22f, 19f, 19f, 19f, 15f)
                curveTo(19f, 12f, 16f, 8f, 12f, 3f)
            }
        }.build()
    }

    /** Heart */
    val Heart: ImageVector by lazy {
        ImageVector.Builder("Heart", W.dp, H.dp, W, H).apply {
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 21f)
                curveTo(6f, 16f, 2f, 12f, 2f, 8.5f)
                curveTo(2f, 5.4f, 4.4f, 3f, 7f, 3f)
                curveTo(9f, 3f, 11f, 4f, 12f, 6f)
                curveTo(13f, 4f, 15f, 3f, 17f, 3f)
                curveTo(19.6f, 3f, 22f, 5.4f, 22f, 8.5f)
                curveTo(22f, 12f, 18f, 16f, 12f, 21f)
            }
        }.build()
    }

    /** Hexagon */
    val Hexagon: ImageVector by lazy {
        ImageVector.Builder("Hexagon", W.dp, H.dp, W, H).apply {
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 2f)
                lineTo(20f, 7f)
                lineTo(20f, 17f)
                lineTo(12f, 22f)
                lineTo(4f, 17f)
                lineTo(4f, 7f)
                close()
            }
        }.build()
    }

    /** Moon / crescent */
    val Moon: ImageVector by lazy {
        ImageVector.Builder("Moon", W.dp, H.dp, W, H).apply {
            path(
                stroke = stroke, strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(20f, 12f)
                curveTo(20f, 16.4f, 16.4f, 20f, 12f, 20f)
                curveTo(7.6f, 20f, 4f, 16.4f, 4f, 12f)
                curveTo(4f, 7.6f, 7.6f, 4f, 12f, 4f)
                curveTo(10f, 6f, 9f, 9f, 9f, 12f)
                curveTo(9f, 15f, 10f, 18f, 12f, 20f)
            }
        }.build()
    }

    /** All icons available for the picker */
    data class PickerIcon(val key: String, val label: String, val icon: ImageVector)

    val PICKER_ICONS: List<PickerIcon> by lazy { listOf(
        PickerIcon("circle", "Circle", GenericCircle),
        PickerIcon("diamond", "Diamond", Diamond),
        PickerIcon("triangle", "Triangle", Triangle),
        PickerIcon("cross", "Cross", Cross),
        PickerIcon("droplet", "Droplet", Droplet),
        PickerIcon("heart", "Heart", Heart),
        PickerIcon("hexagon", "Hexagon", Hexagon),
        PickerIcon("moon", "Moon", Moon),
        PickerIcon("throbbing", "Pulse", Throbbing),
        PickerIcon("pressure", "Pressure", Pressure),
        PickerIcon("stabbing", "Bolt", Stabbing),
        PickerIcon("dullache", "Wave", DullAche),
        PickerIcon("burning", "Flame", Burning),
        PickerIcon("aura", "Sparkle", Aura),
        PickerIcon("nausea", "Nausea", Nausea),
        PickerIcon("light", "Light", LightSensitivity),
        PickerIcon("sound", "Sound", SoundSensitivity),
        PickerIcon("dizziness", "Spiral", Dizziness),
        PickerIcon("neck", "Neck", NeckStiffness),
        PickerIcon("brainfog", "Cloud", BrainFog),
        PickerIcon("fatigue", "Fatigue", Fatigue),
        PickerIcon("blur", "Blur", BlurredVision),
        PickerIcon("tingling", "Tingle", Tingling),
    ) }

    /** Look up icon by key (for DB-stored custom icon choices) */
    fun forKey(key: String?): ImageVector? = PICKER_ICONS.find { it.key == key }?.icon

    // ─── LOOKUP ───────────────────────────────────────────────

    /** Map symptom label → icon. Checks iconKey first, then fuzzy-matches label. Returns null for unknown. */
    fun forLabel(label: String, iconKey: String? = null): ImageVector? {
        // Check explicit icon key first (from DB)
        if (iconKey != null) return forKey(iconKey)
        // Fuzzy match by label
        return when {
        label.contains("Throb", ignoreCase = true) || label.contains("puls", ignoreCase = true) -> Throbbing
        label.contains("Pressure", ignoreCase = true) || label.contains("squeez", ignoreCase = true) -> Pressure
        label.contains("Stab", ignoreCase = true) || label.contains("sharp", ignoreCase = true) -> Stabbing
        label.contains("Dull", ignoreCase = true) || label.contains("ache", ignoreCase = true) -> DullAche
        label.contains("Burn", ignoreCase = true) -> Burning
        label.contains("aura", ignoreCase = true) -> Aura
        label.contains("Nausea", ignoreCase = true) -> Nausea
        label.contains("Light", ignoreCase = true) -> LightSensitivity
        label.contains("Sound", ignoreCase = true) -> SoundSensitivity
        label.contains("Dizz", ignoreCase = true) || label.contains("vertigo", ignoreCase = true) -> Dizziness
        label.contains("Neck", ignoreCase = true) || label.contains("stiff", ignoreCase = true) -> NeckStiffness
        label.contains("fog", ignoreCase = true) || label.contains("Brain", ignoreCase = true) -> BrainFog
        label.contains("Fatigue", ignoreCase = true) -> Fatigue
        label.contains("Blur", ignoreCase = true) || label.contains("vision", ignoreCase = true) -> BlurredVision
        label.contains("Tingl", ignoreCase = true) || label.contains("numb", ignoreCase = true) -> Tingling
        else -> null
        }
    }
}
