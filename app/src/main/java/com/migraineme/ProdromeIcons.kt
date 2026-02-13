package com.migraineme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Custom prodrome icons for MigraineMe.
 * Style: 24dp viewport, 2dp stroke, rounded caps/joins, no fill.
 * Each prodrome's icon_key maps to one ImageVector.
 *
 * Manual prodromes (18 unique icon keys):
 *   Autonomic:   yawning, nasal_congestion, tearing
 *   Cognitive:    brainfog  (Difficulty focusing, Word-finding trouble)
 *   Digestive:    food_cravings, loss_appetite
 *   Mood:         mood_change, irritability, euphoria, depression
 *   Physical:     neck (Muscle tension), frequent_urination
 *   Sensitivity:  light, sound, smell
 *   Sensory:      tingling (Tingling, Numbness)
 *   Sleep:        difficulty_sleeping, excessive_sleeping
 *
 * Auto-metric prodromes (7 icon keys):
 *   duration, score, disturbances, recovery, spo2, skin_temp, resp_rate
 */
object ProdromeIcons {

    private val stroke = SolidColor(Color.White)
    private const val W = 24f
    private const val H = 24f

    // ─── AUTONOMIC ───────────────────────────────────────────

    /** yawning — open mouth face */
    val Yawning: ImageVector by lazy {
        ImageVector.Builder("Yawning", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(19f, 12f)
                arcTo(7f, 7f, 0f, isMoreThanHalf = true, isPositiveArc = true, 5f, 12f)
                arcTo(7f, 7f, 0f, isMoreThanHalf = true, isPositiveArc = true, 19f, 12f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(15f, 14f)
                arcTo(3f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9f, 14f)
                arcTo(3f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 15f, 14f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(8f, 10f); lineTo(10f, 10f)
                moveTo(14f, 10f); lineTo(16f, 10f)
            }
        }.build()
    }

    /** nasal_congestion — stuffy nose */
    val NasalCongestion: ImageVector by lazy {
        ImageVector.Builder("NasalCongestion", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3f); lineTo(12f, 12f)
                curveTo(12f, 16f, 8f, 17f, 7f, 15f)
                moveTo(12f, 12f); curveTo(12f, 16f, 16f, 17f, 17f, 15f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(4f, 10f); lineTo(7f, 10f)
                moveTo(4f, 13f); lineTo(6f, 13f)
                moveTo(17f, 10f); lineTo(20f, 10f)
                moveTo(18f, 13f); lineTo(20f, 13f)
            }
        }.build()
    }

    /** tearing — eye with tear drop */
    val Tearing: ImageVector by lazy {
        ImageVector.Builder("Tearing", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 12f); curveTo(5f, 7f, 9f, 5f, 12f, 5f)
                curveTo(15f, 5f, 19f, 7f, 22f, 12f)
                curveTo(19f, 17f, 15f, 19f, 12f, 19f)
                curveTo(9f, 19f, 5f, 17f, 2f, 12f)
                moveTo(14f, 12f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 10f, 12f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 14f, 12f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(17f, 17f); curveTo(17f, 17f, 15f, 20f, 17f, 22f)
                curveTo(19f, 20f, 17f, 17f, 17f, 17f)
            }
        }.build()
    }

    // ─── COGNITIVE ───────────────────────────────────────────

    /** brainfog — cloud over head */
    val BrainFog: ImageVector by lazy {
        ImageVector.Builder("BrainFog", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6f, 22f); lineTo(6f, 16f)
                arcTo(6f, 6f, 0f, isMoreThanHalf = true, isPositiveArc = true, 18f, 16f)
                lineTo(18f, 22f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6f, 11f); lineTo(18f, 11f)
                curveTo(20f, 11f, 21f, 9f, 20f, 7f); curveTo(19f, 5f, 17f, 5f, 16f, 6f)
                curveTo(16f, 4f, 14f, 3f, 12f, 3f); curveTo(10f, 3f, 8f, 4f, 8f, 6f)
                curveTo(7f, 5f, 5f, 5f, 4f, 7f); curveTo(3f, 9f, 4f, 11f, 6f, 11f)
            }
        }.build()
    }

    // ─── DIGESTIVE ───────────────────────────────────────────

    /** food_cravings — fork and knife with heart */
    val FoodCravings: ImageVector by lazy {
        ImageVector.Builder("FoodCravings", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(7f, 2f); lineTo(7f, 10f)
                moveTo(5f, 2f); lineTo(5f, 6f); curveTo(5f, 8f, 7f, 10f, 7f, 10f)
                moveTo(9f, 2f); lineTo(9f, 6f); curveTo(9f, 8f, 7f, 10f, 7f, 10f)
                moveTo(7f, 10f); lineTo(7f, 22f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(17f, 2f); curveTo(19f, 2f, 20f, 5f, 20f, 8f)
                curveTo(20f, 10f, 18f, 11f, 17f, 11f); lineTo(17f, 22f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 18f)
                curveTo(10f, 16f, 10f, 14f, 11f, 13.5f); curveTo(11.5f, 13f, 12f, 13.5f, 12f, 14f)
                curveTo(12f, 13.5f, 12.5f, 13f, 13f, 13.5f); curveTo(14f, 14f, 14f, 16f, 12f, 18f)
            }
        }.build()
    }

    /** loss_appetite — plate with minus */
    val LossAppetite: ImageVector by lazy {
        ImageVector.Builder("LossAppetite", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(20f, 12f)
                arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4f, 12f)
                arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 20f, 12f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(8f, 12f); lineTo(16f, 12f)
            }
        }.build()
    }

    // ─── MOOD ────────────────────────────────────────────────

    /** mood_change — two-face (happy/sad) */
    val MoodChange: ImageVector by lazy {
        ImageVector.Builder("MoodChange", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(20f, 12f)
                arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4f, 12f)
                arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 20f, 12f)
                moveTo(12f, 4f); lineTo(12f, 20f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(8f, 9f); lineTo(8.1f, 9f)
                moveTo(7f, 14f); curveTo(8f, 16f, 10f, 16f, 11f, 14f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(16f, 9f); lineTo(16.1f, 9f)
                moveTo(13f, 16f); curveTo(14f, 14f, 16f, 14f, 17f, 16f)
            }
        }.build()
    }

    /** irritability — face with anger lines */
    val Irritability: ImageVector by lazy {
        ImageVector.Builder("Irritability", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(17f, 12f)
                arcTo(5f, 5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 7f, 12f)
                arcTo(5f, 5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 17f, 12f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(10f, 11f); lineTo(10.1f, 11f)
                moveTo(14f, 11f); lineTo(14.1f, 11f)
                moveTo(10f, 15f); curveTo(11f, 14f, 13f, 14f, 14f, 15f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(3f, 4f); lineTo(6f, 7f)
                moveTo(21f, 4f); lineTo(18f, 7f)
                moveTo(12f, 1f); lineTo(12f, 4f)
                moveTo(3f, 20f); lineTo(6f, 17f)
                moveTo(21f, 20f); lineTo(18f, 17f)
            }
        }.build()
    }

    /** euphoria — face with stars */
    val Euphoria: ImageVector by lazy {
        ImageVector.Builder("Euphoria", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(19f, 12f)
                arcTo(7f, 7f, 0f, isMoreThanHalf = true, isPositiveArc = true, 5f, 12f)
                arcTo(7f, 7f, 0f, isMoreThanHalf = true, isPositiveArc = true, 19f, 12f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9f, 8f); lineTo(9.5f, 10f); lineTo(8f, 11f); lineTo(10f, 11f); lineTo(9f, 8f)
                moveTo(15f, 8f); lineTo(15.5f, 10f); lineTo(14f, 11f); lineTo(16f, 11f); lineTo(15f, 8f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(8f, 14f); curveTo(9f, 17f, 15f, 17f, 16f, 14f)
            }
        }.build()
    }

    /** depression — sad face with tear */
    val Depression: ImageVector by lazy {
        ImageVector.Builder("Depression", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(19f, 12f)
                arcTo(7f, 7f, 0f, isMoreThanHalf = true, isPositiveArc = true, 5f, 12f)
                arcTo(7f, 7f, 0f, isMoreThanHalf = true, isPositiveArc = true, 19f, 12f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9f, 10f); lineTo(9.1f, 10f)
                moveTo(15f, 10f); lineTo(15.1f, 10f)
                moveTo(9f, 16f); curveTo(10f, 14f, 14f, 14f, 15f, 16f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9f, 11f); lineTo(9f, 13f)
            }
        }.build()
    }

    // ─── PHYSICAL ────────────────────────────────────────────

    /** neck — neck/shoulder with pain lines */
    val Neck: ImageVector by lazy {
        ImageVector.Builder("Neck", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(16f, 7f)
                arcTo(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 8f, 7f)
                arcTo(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 16f, 7f)
                moveTo(10f, 11f); lineTo(10f, 14f)
                moveTo(14f, 11f); lineTo(14f, 14f)
                moveTo(10f, 14f); curveTo(6f, 14f, 3f, 17f, 3f, 20f)
                moveTo(14f, 14f); curveTo(18f, 14f, 21f, 17f, 21f, 20f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(5f, 12f); lineTo(7f, 13f)
                moveTo(19f, 12f); lineTo(17f, 13f)
            }
        }.build()
    }

    /** frequent_urination — droplet with repeat arrows */
    val FrequentUrination: ImageVector by lazy {
        ImageVector.Builder("FrequentUrination", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(10f, 3f); curveTo(10f, 3f, 4f, 10f, 4f, 14f)
                arcTo(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = false, 16f, 14f)
                curveTo(16f, 10f, 10f, 3f, 10f, 3f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(18f, 8f); lineTo(21f, 8f); lineTo(21f, 11f)
                moveTo(21f, 16f); lineTo(18f, 16f); lineTo(18f, 13f)
            }
        }.build()
    }

    // ─── SENSITIVITY ─────────────────────────────────────────

    /** light — eye with brightness rays */
    val Light: ImageVector by lazy {
        ImageVector.Builder("Light", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 12f); curveTo(5f, 7f, 9f, 5f, 12f, 5f)
                curveTo(15f, 5f, 19f, 7f, 22f, 12f)
                curveTo(19f, 17f, 15f, 19f, 12f, 19f)
                curveTo(9f, 19f, 5f, 17f, 2f, 12f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 1f); lineTo(12f, 3f)
                moveTo(4f, 4f); lineTo(6f, 6f)
                moveTo(20f, 4f); lineTo(18f, 6f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(14f, 12f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 10f, 12f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 14f, 12f)
            }
        }.build()
    }

    /** sound — ear with waves */
    val Sound: ImageVector by lazy {
        ImageVector.Builder("Sound", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 2f); curveTo(17f, 2f, 20f, 6f, 20f, 10f)
                curveTo(20f, 13f, 18f, 14f, 16f, 14f)
                curveTo(14f, 14f, 14f, 16f, 14f, 18f)
                curveTo(14f, 20f, 12f, 22f, 10f, 22f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 8f); curveTo(2f, 10f, 2f, 14f, 4f, 16f)
                moveTo(7f, 9f); curveTo(5.5f, 11f, 5.5f, 13f, 7f, 15f)
            }
        }.build()
    }

    /** smell — nose with wavy lines */
    val Smell: ImageVector by lazy {
        ImageVector.Builder("Smell", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3f); lineTo(12f, 12f); curveTo(12f, 16f, 8f, 17f, 7f, 15f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(15f, 10f); curveTo(15f, 8f, 17f, 8f, 17f, 10f); curveTo(17f, 12f, 15f, 12f, 15f, 14f)
                moveTo(18f, 8f); curveTo(18f, 6f, 20f, 6f, 20f, 8f); curveTo(20f, 10f, 18f, 10f, 18f, 12f)
                moveTo(21f, 6f); curveTo(21f, 4f, 23f, 4f, 23f, 6f); curveTo(23f, 8f, 21f, 8f, 21f, 10f)
            }
        }.build()
    }

    // ─── SENSORY ─────────────────────────────────────────────

    /** tingling — hand with dots */
    val Tingling: ImageVector by lazy {
        ImageVector.Builder("Tingling", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 22f); lineTo(12f, 14f)
                moveTo(8f, 14f); lineTo(8f, 6f)
                moveTo(12f, 14f); lineTo(12f, 4f)
                moveTo(16f, 14f); lineTo(16f, 6f)
                moveTo(20f, 14f); lineTo(20f, 10f)
                moveTo(8f, 14f); curveTo(6f, 14f, 4f, 16f, 4f, 18f)
                lineTo(4f, 20f); lineTo(12f, 22f)
                moveTo(20f, 14f); lineTo(20f, 18f); lineTo(12f, 22f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(6f, 3f); lineTo(6.1f, 3f)
                moveTo(3f, 8f); lineTo(3.1f, 8f)
                moveTo(22f, 6f); lineTo(22.1f, 6f)
            }
        }.build()
    }

    // ─── SLEEP ───────────────────────────────────────────────

    /** difficulty_sleeping — bed with X */
    val DifficultySleeping: ImageVector by lazy {
        ImageVector.Builder("DifficultySleeping", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 18f); lineTo(22f, 18f)
                moveTo(2f, 12f); lineTo(22f, 12f); lineTo(22f, 18f)
                moveTo(2f, 12f); lineTo(2f, 18f)
                moveTo(4f, 12f); lineTo(4f, 10f); lineTo(8f, 10f); lineTo(8f, 12f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(14f, 3f); lineTo(20f, 9f)
                moveTo(20f, 3f); lineTo(14f, 9f)
            }
        }.build()
    }

    /** excessive_sleeping — bed with ZZZ */
    val ExcessiveSleeping: ImageVector by lazy {
        ImageVector.Builder("ExcessiveSleeping", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 18f); lineTo(22f, 18f)
                moveTo(2f, 12f); lineTo(22f, 12f); lineTo(22f, 18f)
                moveTo(2f, 12f); lineTo(2f, 18f)
                moveTo(4f, 12f); lineTo(4f, 10f); lineTo(8f, 10f); lineTo(8f, 12f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(14f, 3f); lineTo(18f, 3f); lineTo(14f, 7f); lineTo(18f, 7f)
                moveTo(19f, 1f); lineTo(22f, 1f); lineTo(19f, 4f); lineTo(22f, 4f)
            }
        }.build()
    }

    // ─── AUTO-METRIC ICONS ───────────────────────────────────

    /** duration — hourglass */
    val Duration: ImageVector by lazy {
        ImageVector.Builder("Duration", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6f, 2f); lineTo(18f, 2f)
                moveTo(6f, 22f); lineTo(18f, 22f)
                moveTo(7f, 2f); curveTo(7f, 8f, 12f, 10f, 12f, 12f)
                curveTo(12f, 14f, 7f, 16f, 7f, 22f)
                moveTo(17f, 2f); curveTo(17f, 8f, 12f, 10f, 12f, 12f)
                curveTo(12f, 14f, 17f, 16f, 17f, 22f)
            }
        }.build()
    }

    /** score — star */
    val Score: ImageVector by lazy {
        ImageVector.Builder("Score", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 2f); lineTo(14.5f, 9f); lineTo(22f, 9f); lineTo(16f, 13.5f)
                lineTo(18f, 21f); lineTo(12f, 16.5f); lineTo(6f, 21f); lineTo(8f, 13.5f)
                lineTo(2f, 9f); lineTo(9.5f, 9f); close()
            }
        }.build()
    }

    /** disturbances — zigzag */
    val Disturbances: ImageVector by lazy {
        ImageVector.Builder("Disturbances", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 4f); lineTo(10f, 10f); lineTo(6f, 14f); lineTo(12f, 20f)
                moveTo(14f, 4f); lineTo(20f, 10f); lineTo(16f, 14f); lineTo(20f, 20f)
            }
        }.build()
    }

    /** recovery — battery with plus */
    val Recovery: ImageVector by lazy {
        ImageVector.Builder("Recovery", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 6f); lineTo(18f, 6f); lineTo(18f, 18f); lineTo(4f, 18f); close()
                moveTo(18f, 10f); lineTo(20f, 10f); lineTo(20f, 14f); lineTo(18f, 14f)
                moveTo(11f, 9f); lineTo(11f, 15f)
                moveTo(8f, 12f); lineTo(14f, 12f)
            }
        }.build()
    }

    /** spo2 — O2 molecule */
    val SpO2: ImageVector by lazy {
        ImageVector.Builder("SpO2", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(10f, 8f)
                arcTo(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 2f, 8f)
                arcTo(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 10f, 8f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 16f); curveTo(12f, 14f, 16f, 14f, 16f, 16f); curveTo(16f, 18f, 12f, 20f, 12f, 20f); lineTo(16f, 20f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(18f, 4f); curveTo(18f, 4f, 14f, 8f, 18f, 11f); curveTo(22f, 8f, 18f, 4f, 18f, 4f)
            }
        }.build()
    }

    /** skin_temp — thermometer */
    val SkinTemp: ImageVector by lazy {
        ImageVector.Builder("SkinTemp", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(10f, 3f); lineTo(14f, 3f); lineTo(14f, 14f)
                arcTo(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 10f, 14f)
                lineTo(10f, 3f)
                moveTo(13f, 17f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 11f, 17f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 13f, 17f)
                moveTo(12f, 15f); lineTo(12f, 7f)
            }
        }.build()
    }

    /** resp_rate — lungs */
    val RespRate: ImageVector by lazy {
        ImageVector.Builder("RespRate", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 2f); lineTo(12f, 8f)
                moveTo(12f, 8f); curveTo(8f, 8f, 4f, 12f, 4f, 16f); curveTo(4f, 20f, 8f, 20f, 12f, 18f)
                moveTo(12f, 8f); curveTo(16f, 8f, 20f, 12f, 20f, 16f); curveTo(20f, 20f, 16f, 20f, 12f, 18f)
            }
        }.build()
    }

    // ─── LOOKUP ──────────────────────────────────────────────

    data class ProdromePickerIcon(val key: String, val label: String, val icon: ImageVector)

    val ALL_ICONS: List<ProdromePickerIcon> by lazy { listOf(
        // Autonomic
        ProdromePickerIcon("yawning", "Yawning", Yawning),
        ProdromePickerIcon("nasal_congestion", "Nasal congestion", NasalCongestion),
        ProdromePickerIcon("tearing", "Tearing", Tearing),
        // Cognitive
        ProdromePickerIcon("brainfog", "Brain fog", BrainFog),
        // Digestive
        ProdromePickerIcon("food_cravings", "Food cravings", FoodCravings),
        ProdromePickerIcon("loss_appetite", "Loss of appetite", LossAppetite),
        // Mood
        ProdromePickerIcon("mood_change", "Mood change", MoodChange),
        ProdromePickerIcon("irritability", "Irritability", Irritability),
        ProdromePickerIcon("euphoria", "Euphoria", Euphoria),
        ProdromePickerIcon("depression", "Depression", Depression),
        // Physical
        ProdromePickerIcon("neck", "Neck / tension", Neck),
        ProdromePickerIcon("frequent_urination", "Frequent urination", FrequentUrination),
        // Sensitivity
        ProdromePickerIcon("light", "Light sensitivity", Light),
        ProdromePickerIcon("sound", "Sound sensitivity", Sound),
        ProdromePickerIcon("smell", "Smell sensitivity", Smell),
        // Sensory
        ProdromePickerIcon("tingling", "Tingling", Tingling),
        // Sleep
        ProdromePickerIcon("difficulty_sleeping", "Difficulty sleeping", DifficultySleeping),
        ProdromePickerIcon("excessive_sleeping", "Excessive sleeping", ExcessiveSleeping),
        // Auto-metric
        ProdromePickerIcon("duration", "Duration", Duration),
        ProdromePickerIcon("score", "Score", Score),
        ProdromePickerIcon("disturbances", "Disturbances", Disturbances),
        ProdromePickerIcon("recovery", "Recovery", Recovery),
        ProdromePickerIcon("spo2", "SpO2", SpO2),
        ProdromePickerIcon("skin_temp", "Skin temp", SkinTemp),
        ProdromePickerIcon("resp_rate", "Resp rate", RespRate),
    ) }

    fun forKey(key: String?): ImageVector? = ALL_ICONS.find { it.key == key }?.icon
}
