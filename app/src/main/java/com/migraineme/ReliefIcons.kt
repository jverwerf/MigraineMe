package com.migraineme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object ReliefIcons {

    private val stroke = SolidColor(Color.White)
    private const val W = 24f
    private const val H = 24f

    val Breathing: ImageVector by lazy {
        ImageVector.Builder("Breathing", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 8f); curveTo(4f, 8f, 8f, 6f, 12f, 8f); curveTo(16f, 10f, 20f, 8f, 20f, 8f)
                moveTo(4f, 12f); curveTo(4f, 12f, 8f, 10f, 12f, 12f); curveTo(16f, 14f, 20f, 12f, 20f, 12f)
                moveTo(4f, 16f); curveTo(4f, 16f, 8f, 14f, 12f, 16f); curveTo(16f, 18f, 20f, 16f, 20f, 16f)
            }
        }.build()
    }

    val BoxBreathing: ImageVector by lazy {
        ImageVector.Builder("BoxBreathing", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6f, 6f); lineTo(18f, 6f); lineTo(18f, 18f); lineTo(6f, 18f); close()
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(11f, 4f); lineTo(13f, 6f); lineTo(11f, 8f)
                moveTo(16f, 11f); lineTo(18f, 13f); lineTo(20f, 11f)
                moveTo(13f, 16f); lineTo(11f, 18f); lineTo(13f, 20f)
                moveTo(8f, 13f); lineTo(6f, 11f); lineTo(4f, 13f)
            }
        }.build()
    }

    val Ice: ImageVector by lazy {
        ImageVector.Builder("Ice", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 2f); lineTo(12f, 22f); moveTo(4.5f, 7.5f); lineTo(19.5f, 16.5f); moveTo(19.5f, 7.5f); lineTo(4.5f, 16.5f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 2f); lineTo(10f, 4f); moveTo(12f, 2f); lineTo(14f, 4f)
                moveTo(12f, 22f); lineTo(10f, 20f); moveTo(12f, 22f); lineTo(14f, 20f)
            }
        }.build()
    }

    val Heat: ImageVector by lazy {
        ImageVector.Builder("Heat", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 22f); curveTo(8f, 22f, 5f, 18f, 5f, 14f); curveTo(5f, 10f, 8f, 8f, 9f, 5f)
                curveTo(10f, 2f, 12f, 2f, 12f, 2f); curveTo(12f, 2f, 14f, 2f, 15f, 5f)
                curveTo(16f, 8f, 19f, 10f, 19f, 14f); curveTo(19f, 18f, 16f, 22f, 12f, 22f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 22f); curveTo(10f, 22f, 9f, 19f, 9f, 17f); curveTo(9f, 15f, 12f, 13f, 12f, 13f)
                curveTo(12f, 13f, 15f, 15f, 15f, 17f); curveTo(15f, 19f, 14f, 22f, 12f, 22f)
            }
        }.build()
    }

    val Darkness: ImageVector by lazy {
        ImageVector.Builder("Darkness", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(20f, 12f); curveTo(20f, 16.4f, 16.4f, 20f, 12f, 20f); curveTo(7.6f, 20f, 4f, 16.4f, 4f, 12f)
                curveTo(4f, 7.6f, 7.6f, 4f, 12f, 4f); curveTo(10f, 6f, 9f, 10f, 11f, 14f); curveTo(13f, 18f, 17f, 18f, 20f, 12f)
            }
        }.build()
    }

    val EyeMask: ImageVector by lazy {
        ImageVector.Builder("EyeMask", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 12f); curveTo(2f, 9f, 5f, 7f, 8f, 7f); curveTo(10f, 7f, 11f, 9f, 12f, 9f)
                curveTo(13f, 9f, 14f, 7f, 16f, 7f); curveTo(19f, 7f, 22f, 9f, 22f, 12f)
                curveTo(22f, 15f, 19f, 17f, 16f, 17f); curveTo(14f, 17f, 13f, 15f, 12f, 15f)
                curveTo(11f, 15f, 10f, 17f, 8f, 17f); curveTo(5f, 17f, 2f, 15f, 2f, 12f)
            }
        }.build()
    }

    val Sunglasses: ImageVector by lazy {
        ImageVector.Builder("Sunglasses", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 10f); lineTo(22f, 10f)
                moveTo(3f, 10f); curveTo(3f, 10f, 3f, 16f, 7f, 16f); curveTo(11f, 16f, 11f, 10f, 11f, 10f)
                moveTo(13f, 10f); curveTo(13f, 10f, 13f, 16f, 17f, 16f); curveTo(21f, 16f, 21f, 10f, 21f, 10f)
                moveTo(11f, 11f); curveTo(11f, 12f, 13f, 12f, 13f, 11f)
            }
        }.build()
    }

    val Water: ImageVector by lazy {
        ImageVector.Builder("Water", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 2f); curveTo(12f, 2f, 5f, 10f, 5f, 15f); curveTo(5f, 19f, 8f, 22f, 12f, 22f)
                curveTo(16f, 22f, 19f, 19f, 19f, 15f); curveTo(19f, 10f, 12f, 2f, 12f, 2f)
            }
        }.build()
    }

    val Electrolytes: ImageVector by lazy {
        ImageVector.Builder("Electrolytes", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 2f); curveTo(12f, 2f, 5f, 10f, 5f, 15f); curveTo(5f, 19f, 8f, 22f, 12f, 22f)
                curveTo(16f, 22f, 19f, 19f, 19f, 15f); curveTo(19f, 10f, 12f, 2f, 12f, 2f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 12f); lineTo(12f, 18f); moveTo(9f, 15f); lineTo(15f, 15f)
            }
        }.build()
    }

    val Massage: ImageVector by lazy {
        ImageVector.Builder("Massage", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6f, 20f); curveTo(6f, 20f, 4f, 16f, 4f, 13f); curveTo(4f, 11f, 5f, 10f, 7f, 10f); lineTo(11f, 10f)
                moveTo(8f, 10f); lineTo(8f, 5f); moveTo(11f, 10f); lineTo(11f, 4f); moveTo(14f, 10f); lineTo(14f, 5f)
                moveTo(14f, 10f); curveTo(16f, 10f, 17f, 11f, 17f, 13f); lineTo(17f, 14f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(19f, 8f); lineTo(21f, 7f); moveTo(19f, 11f); lineTo(21f, 11f); moveTo(19f, 14f); lineTo(21f, 15f)
            }
        }.build()
    }

    val Acupressure: ImageVector by lazy {
        ImageVector.Builder("Acupressure", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 2f); lineTo(12f, 13f)
                moveTo(9f, 4f); curveTo(9f, 4f, 9f, 2f, 12f, 2f); curveTo(15f, 2f, 15f, 4f, 15f, 4f)
                moveTo(9f, 4f); lineTo(9f, 10f); moveTo(15f, 4f); lineTo(15f, 10f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(8f, 16f); curveTo(8f, 16f, 10f, 14f, 12f, 14f); curveTo(14f, 14f, 16f, 16f, 16f, 16f)
                moveTo(6f, 19f); curveTo(6f, 19f, 9f, 17f, 12f, 17f); curveTo(15f, 17f, 18f, 19f, 18f, 19f)
                moveTo(4f, 22f); curveTo(4f, 22f, 8f, 20f, 12f, 20f); curveTo(16f, 20f, 20f, 22f, 20f, 22f)
            }
        }.build()
    }

    val Meditation: ImageVector by lazy {
        ImageVector.Builder("Meditation", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(14f, 5f); arcTo(2f, 2f, 0f, true, true, 10f, 5f); arcTo(2f, 2f, 0f, true, true, 14f, 5f)
                moveTo(12f, 7f); lineTo(12f, 14f)
                moveTo(12f, 14f); curveTo(12f, 14f, 7f, 14f, 6f, 18f)
                moveTo(12f, 14f); curveTo(12f, 14f, 17f, 14f, 18f, 18f)
                moveTo(5f, 20f); lineTo(19f, 20f)
                moveTo(12f, 10f); lineTo(6f, 12f); moveTo(12f, 10f); lineTo(18f, 12f)
            }
        }.build()
    }

    val Progressive: ImageVector by lazy {
        ImageVector.Builder("Progressive", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 12f); curveTo(3f, 6f, 5f, 18f, 7f, 12f); curveTo(8f, 8f, 10f, 16f, 12f, 12f)
                curveTo(13f, 10f, 15f, 14f, 17f, 12f); curveTo(18f, 11f, 20f, 13f, 22f, 12f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(4f, 20f); lineTo(20f, 20f); moveTo(8f, 18f); lineTo(16f, 18f)
            }
        }.build()
    }

    val Walk: ImageVector by lazy {
        ImageVector.Builder("Walk", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(14f, 4f); arcTo(2f, 2f, 0f, true, true, 10f, 4f); arcTo(2f, 2f, 0f, true, true, 14f, 4f)
                moveTo(12f, 6f); lineTo(12f, 14f)
                moveTo(12f, 9f); lineTo(7f, 12f); moveTo(12f, 9f); lineTo(17f, 11f)
                moveTo(12f, 14f); lineTo(8f, 22f); moveTo(12f, 14f); lineTo(16f, 22f)
            }
        }.build()
    }

    val Stretch: ImageVector by lazy {
        ImageVector.Builder("Stretch", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(14f, 4f); arcTo(2f, 2f, 0f, true, true, 10f, 4f); arcTo(2f, 2f, 0f, true, true, 14f, 4f)
                moveTo(12f, 6f); lineTo(12f, 14f)
                moveTo(12f, 9f); lineTo(5f, 5f); moveTo(12f, 9f); lineTo(19f, 5f)
                moveTo(12f, 14f); lineTo(8f, 22f); moveTo(12f, 14f); lineTo(16f, 22f)
            }
        }.build()
    }

    val Yoga: ImageVector by lazy {
        ImageVector.Builder("Yoga", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(14f, 4f); arcTo(2f, 2f, 0f, true, true, 10f, 4f); arcTo(2f, 2f, 0f, true, true, 14f, 4f)
                moveTo(12f, 6f); lineTo(12f, 16f)
                moveTo(12f, 9f); lineTo(7f, 4f); moveTo(12f, 9f); lineTo(17f, 4f)
                moveTo(12f, 16f); lineTo(12f, 22f)
                moveTo(12f, 14f); lineTo(15f, 16f); lineTo(12f, 18f)
            }
        }.build()
    }

    val Rest: ImageVector by lazy {
        ImageVector.Builder("Rest", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3f, 20f); lineTo(3f, 12f); lineTo(21f, 12f); lineTo(21f, 20f)
                moveTo(3f, 12f); curveTo(3f, 12f, 5f, 9f, 8f, 9f); curveTo(10f, 9f, 10f, 12f, 10f, 12f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(15f, 3f); lineTo(19f, 3f); lineTo(15f, 7f); lineTo(19f, 7f)
            }
        }.build()
    }

    val Coffee: ImageVector by lazy {
        ImageVector.Builder("Coffee", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 10f); lineTo(4f, 18f); curveTo(4f, 20f, 6f, 21f, 8f, 21f); lineTo(14f, 21f)
                curveTo(16f, 21f, 18f, 20f, 18f, 18f); lineTo(18f, 10f); close()
                moveTo(18f, 12f); curveTo(20f, 12f, 21f, 13f, 21f, 14.5f); curveTo(21f, 16f, 20f, 17f, 18f, 17f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(8f, 7f); curveTo(8f, 5f, 9f, 4f, 8f, 3f)
                moveTo(11f, 7f); curveTo(11f, 5f, 12f, 4f, 11f, 3f)
                moveTo(14f, 7f); curveTo(14f, 5f, 15f, 4f, 14f, 3f)
            }
        }.build()
    }

    val Ginger: ImageVector by lazy {
        ImageVector.Builder("Ginger", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 11f); lineTo(4f, 18f); curveTo(4f, 20f, 6f, 21f, 8f, 21f); lineTo(14f, 21f)
                curveTo(16f, 21f, 18f, 20f, 18f, 18f); lineTo(18f, 11f); close()
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(11f, 9f); curveTo(11f, 9f, 13f, 4f, 17f, 3f); curveTo(17f, 3f, 15f, 7f, 11f, 9f)
                moveTo(14f, 5f); lineTo(11f, 9f)
            }
        }.build()
    }

    val Peppermint: ImageVector by lazy {
        ImageVector.Builder("Peppermint", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 22f); curveTo(12f, 22f, 4f, 16f, 4f, 10f); curveTo(4f, 4f, 12f, 2f, 12f, 2f)
                curveTo(12f, 2f, 20f, 4f, 20f, 10f); curveTo(20f, 16f, 12f, 22f, 12f, 22f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 6f); lineTo(12f, 18f)
                moveTo(8f, 10f); lineTo(12f, 12f); moveTo(16f, 10f); lineTo(12f, 12f)
            }
        }.build()
    }

    val Bath: ImageVector by lazy {
        ImageVector.Builder("Bath", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 12f); lineTo(22f, 12f)
                moveTo(4f, 12f); lineTo(4f, 17f); curveTo(4f, 20f, 7f, 21f, 12f, 21f)
                curveTo(17f, 21f, 20f, 20f, 20f, 17f); lineTo(20f, 12f)
                moveTo(4f, 12f); lineTo(4f, 5f); curveTo(4f, 4f, 5f, 3f, 6f, 3f); lineTo(7f, 3f)
            }
        }.build()
    }

    val Shower: ImageVector by lazy {
        ImageVector.Builder("Shower", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 4f); lineTo(16f, 4f); lineTo(16f, 8f)
                moveTo(12f, 8f); lineTo(20f, 8f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(13f, 11f); lineTo(13f, 14f)
                moveTo(16f, 11f); lineTo(16f, 15f)
                moveTo(19f, 11f); lineTo(19f, 14f)
                moveTo(14.5f, 16f); lineTo(14.5f, 19f)
                moveTo(17.5f, 16f); lineTo(17.5f, 19f)
                moveTo(16f, 20f); lineTo(16f, 22f)
            }
        }.build()
    }

    val FreshAir: ImageVector by lazy {
        ImageVector.Builder("FreshAir", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3f, 8f); curveTo(3f, 8f, 8f, 8f, 10f, 8f); curveTo(13f, 8f, 13f, 5f, 10f, 5f)
                moveTo(3f, 12f); curveTo(3f, 12f, 12f, 12f, 15f, 12f); curveTo(18f, 12f, 18f, 9f, 15f, 9f)
                moveTo(3f, 16f); curveTo(3f, 16f, 9f, 16f, 11f, 16f); curveTo(14f, 16f, 14f, 19f, 11f, 19f)
            }
        }.build()
    }

    val Quiet: ImageVector by lazy {
        ImageVector.Builder("Quiet", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 4f); curveTo(16f, 4f, 19f, 7f, 19f, 11f); curveTo(19f, 14f, 17f, 15f, 15f, 15f)
                curveTo(13f, 15f, 13f, 13f, 13f, 12f); curveTo(13f, 10f, 15f, 9f, 15f, 11f)
                moveTo(12f, 4f); curveTo(8f, 4f, 6f, 7f, 6f, 11f); curveTo(6f, 16f, 8f, 18f, 10f, 20f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(4f, 4f); lineTo(20f, 20f)
            }
        }.build()
    }

    val Other: ImageVector by lazy {
        ImageVector.Builder("ReliefOther", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 21f); curveTo(12f, 21f, 3f, 15f, 3f, 9f); curveTo(3f, 6f, 5f, 4f, 7.5f, 4f)
                curveTo(9f, 4f, 11f, 5f, 12f, 7f); curveTo(13f, 5f, 15f, 4f, 16.5f, 4f)
                curveTo(19f, 4f, 21f, 6f, 21f, 9f); curveTo(21f, 15f, 12f, 21f, 12f, 21f)
            }
        }.build()
    }

    data class ReliefPickerIcon(val key: String, val label: String, val icon: ImageVector)

    val ALL_ICONS: List<ReliefPickerIcon> by lazy { listOf(
        ReliefPickerIcon("breathing", "Breathing", Breathing),
        ReliefPickerIcon("box_breathing", "Box breathing", BoxBreathing),
        ReliefPickerIcon("ice", "Cold / Ice", Ice),
        ReliefPickerIcon("heat", "Heat", Heat),
        ReliefPickerIcon("darkness", "Dark room", Darkness),
        ReliefPickerIcon("eye_mask", "Eye mask", EyeMask),
        ReliefPickerIcon("sunglasses", "Sunglasses", Sunglasses),
        ReliefPickerIcon("water", "Water", Water),
        ReliefPickerIcon("electrolytes", "Electrolytes", Electrolytes),
        ReliefPickerIcon("massage", "Massage", Massage),
        ReliefPickerIcon("acupressure", "Acupressure", Acupressure),
        ReliefPickerIcon("meditation", "Meditation", Meditation),
        ReliefPickerIcon("progressive", "Relaxation", Progressive),
        ReliefPickerIcon("walk", "Walk", Walk),
        ReliefPickerIcon("stretch", "Stretch", Stretch),
        ReliefPickerIcon("yoga", "Yoga", Yoga),
        ReliefPickerIcon("rest", "Rest / Nap", Rest),
        ReliefPickerIcon("coffee", "Caffeine", Coffee),
        ReliefPickerIcon("ginger", "Ginger tea", Ginger),
        ReliefPickerIcon("peppermint", "Peppermint", Peppermint),
        ReliefPickerIcon("bath", "Bath", Bath),
        ReliefPickerIcon("shower", "Shower", Shower),
        ReliefPickerIcon("fresh_air", "Fresh air", FreshAir),
        ReliefPickerIcon("quiet", "Quiet", Quiet),
        ReliefPickerIcon("other", "Other", Other),
    ) }

    fun forKey(key: String?): ImageVector? = ALL_ICONS.find { it.key == key }?.icon
}
