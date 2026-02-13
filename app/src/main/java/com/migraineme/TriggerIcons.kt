package com.migraineme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Custom trigger icons for MigraineMe.
 * Style: 24dp viewport, 2dp stroke, rounded caps/joins, no fill.
 * Each trigger's icon_key maps to one ImageVector.
 * The trigger-worker now checks both fixed threshold AND ±2 SD from
 * the user's personal baseline automatically for every trigger.
 */
object TriggerIcons {

    private val stroke = SolidColor(Color.White)
    private const val W = 24f
    private const val H = 24f

    // ─── SLEEP ───────────────────────────────────────────────

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

    /** efficiency — gauge/speedometer */
    val Efficiency: ImageVector by lazy {
        ImageVector.Builder("Efficiency", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Arc
                moveTo(4f, 18f)
                arcTo(10f, 10f, 0f, isMoreThanHalf = true, isPositiveArc = true, 20f, 18f)
                // Needle
                moveTo(12f, 12f); lineTo(16f, 7f)
                // Center dot
                moveTo(13f, 12f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 11f, 12f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 13f, 12f)
            }
        }.build()
    }

    /** disturbances — zigzag crack */
    val Disturbances: ImageVector by lazy {
        ImageVector.Builder("Disturbances", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 4f); lineTo(10f, 10f); lineTo(6f, 14f); lineTo(12f, 20f)
                moveTo(14f, 4f); lineTo(20f, 10f); lineTo(16f, 14f); lineTo(20f, 20f)
            }
        }.build()
    }

    /** bedtime — moon with zzz */
    val Bedtime: ImageVector by lazy {
        ImageVector.Builder("Bedtime", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Crescent moon
                moveTo(18f, 12f)
                arcTo(7f, 7f, 0f, isMoreThanHalf = true, isPositiveArc = true, 8f, 4f)
                curveTo(14f, 4f, 18f, 7f, 18f, 12f)
            }
            // Z
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(17f, 6f); lineTo(21f, 6f); lineTo(17f, 10f); lineTo(21f, 10f)
            }
        }.build()
    }

    /** wake_time — sunrise */
    val WakeTime: ImageVector by lazy {
        ImageVector.Builder("WakeTime", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Horizon line
                moveTo(2f, 18f); lineTo(22f, 18f)
                // Half sun
                moveTo(17f, 18f)
                arcTo(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 7f, 18f)
                // Rays
                moveTo(12f, 8f); lineTo(12f, 5f)
                moveTo(5.6f, 12.4f); lineTo(3.5f, 10.3f)
                moveTo(18.4f, 12.4f); lineTo(20.5f, 10.3f)
            }
        }.build()
    }

    /** deep_sleep — layered waves (deep) */
    val DeepSleep: ImageVector by lazy {
        ImageVector.Builder("DeepSleep", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 8f); curveTo(6f, 4f, 10f, 4f, 12f, 8f); curveTo(14f, 12f, 18f, 12f, 22f, 8f)
                moveTo(2f, 14f); curveTo(6f, 10f, 10f, 10f, 12f, 14f); curveTo(14f, 18f, 18f, 18f, 22f, 14f)
                moveTo(2f, 20f); curveTo(6f, 16f, 10f, 16f, 12f, 20f); curveTo(14f, 24f, 18f, 24f, 22f, 20f)
            }
        }.build()
    }

    /** rem_sleep — closed eye with movement lines */
    val RemSleep: ImageVector by lazy {
        ImageVector.Builder("RemSleep", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Closed eye
                moveTo(2f, 12f); curveTo(6f, 16f, 18f, 16f, 22f, 12f)
                // Lashes
                moveTo(12f, 16f); lineTo(12f, 19f)
                moveTo(8f, 15.5f); lineTo(7f, 18f)
                moveTo(16f, 15.5f); lineTo(17f, 18f)
            }
        }.build()
    }

    /** light_sleep — single gentle wave */
    val LightSleep: ImageVector by lazy {
        ImageVector.Builder("LightSleep", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 12f); curveTo(6f, 8f, 10f, 8f, 12f, 12f); curveTo(14f, 16f, 18f, 16f, 22f, 12f)
            }
            // Zzz small
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(16f, 4f); lineTo(20f, 4f); lineTo(16f, 8f); lineTo(20f, 8f)
            }
        }.build()
    }

    /** jet_lag — airplane + clock */
    val JetLag: ImageVector by lazy {
        ImageVector.Builder("JetLag", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Simple plane shape
                moveTo(2f, 14f); lineTo(8f, 12f); lineTo(12f, 4f); lineTo(14f, 12f); lineTo(22f, 10f)
                moveTo(12f, 12f); lineTo(12f, 18f)
                moveTo(9f, 18f); lineTo(15f, 18f)
            }
        }.build()
    }

    /** sleep_apnea — lungs with pause */
    val SleepApnea: ImageVector by lazy {
        ImageVector.Builder("SleepApnea", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Nose/airway
                moveTo(12f, 2f); lineTo(12f, 8f)
                // Left lung
                moveTo(12f, 8f); curveTo(8f, 8f, 4f, 12f, 4f, 16f); curveTo(4f, 20f, 8f, 20f, 12f, 18f)
                // Right lung
                moveTo(12f, 8f); curveTo(16f, 8f, 20f, 12f, 20f, 16f); curveTo(20f, 20f, 16f, 20f, 12f, 18f)
            }
            // X mark (blocked)
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(10f, 5f); lineTo(14f, 9f)
                moveTo(14f, 5f); lineTo(10f, 9f)
            }
        }.build()
    }

    // ─── PHYSICAL ────────────────────────────────────────────

    /** recovery — battery with plus */
    val Recovery: ImageVector by lazy {
        ImageVector.Builder("Recovery", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Battery body
                moveTo(4f, 6f); lineTo(18f, 6f); lineTo(18f, 18f); lineTo(4f, 18f); close()
                // Battery nub
                moveTo(18f, 10f); lineTo(20f, 10f); lineTo(20f, 14f); lineTo(18f, 14f)
                // Plus
                moveTo(11f, 9f); lineTo(11f, 15f)
                moveTo(8f, 12f); lineTo(14f, 12f)
            }
        }.build()
    }

    /** spo2 — O2 molecule */
    val SpO2: ImageVector by lazy {
        ImageVector.Builder("SpO2", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // O
                moveTo(10f, 8f)
                arcTo(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 2f, 8f)
                arcTo(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 10f, 8f)
            }
            // 2 subscript
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 16f); curveTo(12f, 14f, 16f, 14f, 16f, 16f); curveTo(16f, 18f, 12f, 20f, 12f, 20f); lineTo(16f, 20f)
            }
            // Droplet
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(18f, 4f); curveTo(18f, 4f, 14f, 8f, 18f, 11f); curveTo(22f, 8f, 18f, 4f, 18f, 4f)
            }
        }.build()
    }

    /** skin_temp — thermometer */
    val SkinTemp: ImageVector by lazy {
        ImageVector.Builder("SkinTemp", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Thermometer body
                moveTo(10f, 3f); lineTo(14f, 3f); lineTo(14f, 14f)
                arcTo(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 10f, 14f)
                lineTo(10f, 3f)
                // Bulb
                moveTo(13f, 17f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 11f, 17f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 13f, 17f)
                // Mercury line
                moveTo(12f, 15f); lineTo(12f, 7f)
            }
        }.build()
    }

    /** resp_rate — lungs breathing */
    val RespRate: ImageVector by lazy {
        ImageVector.Builder("RespRate", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 2f); lineTo(12f, 8f)
                moveTo(12f, 8f); curveTo(8f, 8f, 4f, 12f, 4f, 16f); curveTo(4f, 20f, 8f, 20f, 12f, 18f)
                moveTo(12f, 8f); curveTo(16f, 8f, 20f, 12f, 20f, 16f); curveTo(20f, 20f, 16f, 20f, 12f, 18f)
            }
        }.build()
    }

    /** hr_zones — heart with pulse */
    val HrZones: ImageVector by lazy {
        ImageVector.Builder("HrZones", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Heart
                moveTo(12f, 20f)
                curveTo(4f, 14f, 2f, 10f, 2f, 7f); curveTo(2f, 4f, 4.5f, 2f, 7f, 2f); curveTo(9f, 2f, 11f, 3f, 12f, 5f)
                curveTo(13f, 3f, 15f, 2f, 17f, 2f); curveTo(19.5f, 2f, 22f, 4f, 22f, 7f); curveTo(22f, 10f, 20f, 14f, 12f, 20f)
            }
            // Pulse inside
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6f, 11f); lineTo(9f, 11f); lineTo(10.5f, 8f); lineTo(13f, 14f); lineTo(14.5f, 11f); lineTo(18f, 11f)
            }
        }.build()
    }

    /** steps — footprint */
    val Steps: ImageVector by lazy {
        ImageVector.Builder("Steps", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Left foot
                moveTo(8f, 4f); curveTo(6f, 4f, 5f, 6f, 5f, 8f); curveTo(5f, 10f, 6f, 11f, 8f, 11f); curveTo(10f, 11f, 11f, 10f, 11f, 8f); curveTo(11f, 6f, 10f, 4f, 8f, 4f)
                // Right foot
                moveTo(16f, 13f); curveTo(14f, 13f, 13f, 15f, 13f, 17f); curveTo(13f, 19f, 14f, 20f, 16f, 20f); curveTo(18f, 20f, 19f, 19f, 19f, 17f); curveTo(19f, 15f, 18f, 13f, 16f, 13f)
            }
        }.build()
    }

    /** body_fat — scale */
    val BodyFat: ImageVector by lazy {
        ImageVector.Builder("BodyFat", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Scale platform
                moveTo(4f, 6f); lineTo(20f, 6f); lineTo(20f, 20f); lineTo(4f, 20f); close()
                // Display circle
                moveTo(16f, 13f)
                arcTo(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 8f, 13f)
                arcTo(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 16f, 13f)
                // Needle
                moveTo(12f, 13f); lineTo(14.5f, 10.5f)
            }
        }.build()
    }

    /** weight — dumbbell */
    val Weight: ImageVector by lazy {
        ImageVector.Builder("Weight", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Bar
                moveTo(7f, 12f); lineTo(17f, 12f)
                // Left weight
                moveTo(3f, 8f); lineTo(7f, 8f); lineTo(7f, 16f); lineTo(3f, 16f); close()
                // Right weight
                moveTo(17f, 8f); lineTo(21f, 8f); lineTo(21f, 16f); lineTo(17f, 16f); close()
            }
        }.build()
    }

    /** bp — blood pressure cuff arm */
    val Bp: ImageVector by lazy {
        ImageVector.Builder("Bp", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Heart
                moveTo(12f, 18f)
                curveTo(7f, 14f, 5f, 11f, 5f, 8.5f); curveTo(5f, 6f, 7f, 4f, 8.5f, 4f); curveTo(10f, 4f, 11f, 5f, 12f, 6.5f)
                curveTo(13f, 5f, 14f, 4f, 15.5f, 4f); curveTo(17f, 4f, 19f, 6f, 19f, 8.5f); curveTo(19f, 11f, 17f, 14f, 12f, 18f)
            }
            // Arrow up
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(19f, 14f); lineTo(19f, 21f)
                moveTo(17f, 16f); lineTo(19f, 14f); lineTo(21f, 16f)
            }
        }.build()
    }

    /** blood_glucose — droplet with wave */
    val BloodGlucose: ImageVector by lazy {
        ImageVector.Builder("BloodGlucose", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Droplet
                moveTo(12f, 3f); curveTo(12f, 3f, 5f, 11f, 5f, 15f)
                arcTo(7f, 7f, 0f, isMoreThanHalf = false, isPositiveArc = false, 19f, 15f)
                curveTo(19f, 11f, 12f, 3f, 12f, 3f)
            }
            // Wave inside
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(8f, 15f); curveTo(9f, 13f, 11f, 13f, 12f, 15f); curveTo(13f, 17f, 15f, 17f, 16f, 15f)
            }
        }.build()
    }

    /** strain — flexed arm / exertion */
    val Strain: ImageVector by lazy {
        ImageVector.Builder("Strain", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Flexed bicep
                moveTo(4f, 18f); lineTo(4f, 12f); curveTo(4f, 8f, 8f, 4f, 12f, 4f)
                curveTo(14f, 4f, 16f, 6f, 16f, 8f); curveTo(16f, 10f, 14f, 10f, 14f, 12f)
                lineTo(20f, 12f); lineTo(20f, 18f)
            }
            // Effort lines
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(8f, 2f); lineTo(8f, 4f)
                moveTo(11f, 1f); lineTo(11f, 3f)
            }
        }.build()
    }

    /** hydration — water glass */
    val Hydration: ImageVector by lazy {
        ImageVector.Builder("Hydration", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Glass shape
                moveTo(6f, 2f); lineTo(18f, 2f); lineTo(16f, 22f); lineTo(8f, 22f); close()
                // Water level
                moveTo(7.5f, 10f); lineTo(16.5f, 10f)
                // Wave
                moveTo(8f, 14f); curveTo(9.5f, 12.5f, 10.5f, 12.5f, 12f, 14f); curveTo(13.5f, 15.5f, 14.5f, 15.5f, 16f, 14f)
            }
        }.build()
    }

    /** allergies — flower pollen */
    val Allergies: ImageVector by lazy {
        ImageVector.Builder("Allergies", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Center
                moveTo(14f, 12f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 10f, 12f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 14f, 12f)
                // Petals
                moveTo(12f, 6f); curveTo(10f, 8f, 10f, 10f, 12f, 10f); curveTo(14f, 10f, 14f, 8f, 12f, 6f)
                moveTo(12f, 18f); curveTo(10f, 16f, 10f, 14f, 12f, 14f); curveTo(14f, 14f, 14f, 16f, 12f, 18f)
                moveTo(6f, 12f); curveTo(8f, 10f, 10f, 10f, 10f, 12f); curveTo(10f, 14f, 8f, 14f, 6f, 12f)
                moveTo(18f, 12f); curveTo(16f, 10f, 14f, 10f, 14f, 12f); curveTo(14f, 14f, 16f, 14f, 18f, 12f)
            }
            // Sneeze dots
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(3f, 4f); lineTo(3.1f, 4f)
                moveTo(5f, 2f); lineTo(5.1f, 2f)
                moveTo(20f, 19f); lineTo(20.1f, 19f)
            }
        }.build()
    }

    /** illness — thermometer with cross */
    val Illness: ImageVector by lazy {
        ImageVector.Builder("Illness", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Thermometer
                moveTo(9f, 3f); lineTo(13f, 3f); lineTo(13f, 14f)
                arcTo(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9f, 14f)
                lineTo(9f, 3f)
                moveTo(11f, 15f); lineTo(11f, 8f)
            }
            // Cross
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(18f, 6f); lineTo(18f, 14f)
                moveTo(14f, 10f); lineTo(22f, 10f)
            }
        }.build()
    }

    /** medication — pill capsule */
    val Medication: ImageVector by lazy {
        ImageVector.Builder("Medication", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Capsule
                moveTo(8.5f, 4f); curveTo(6f, 4f, 4f, 6f, 4f, 8.5f)
                lineTo(4f, 11.5f); lineTo(12.5f, 20f)
                lineTo(15.5f, 20f); curveTo(18f, 20f, 20f, 18f, 20f, 15.5f)
                lineTo(20f, 12.5f); lineTo(11.5f, 4f); close()
                // Divider
                moveTo(8f, 16f); lineTo(16f, 8f)
            }
        }.build()
    }

    /** motion_sickness — spiral */
    val MotionSickness: ImageVector by lazy {
        ImageVector.Builder("MotionSickness", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 12f)
                curveTo(12f, 10f, 14f, 9f, 15f, 10f)
                curveTo(17f, 12f, 15f, 16f, 12f, 16f)
                curveTo(8f, 16f, 6f, 12f, 6f, 10f)
                curveTo(6f, 5f, 10f, 2f, 14f, 2f)
                curveTo(20f, 2f, 22f, 7f, 22f, 12f)
                curveTo(22f, 18f, 17.5f, 22f, 12f, 22f)
                curveTo(6f, 22f, 2f, 17.5f, 2f, 12f)
            }
        }.build()
    }

    /** sexual_activity — two hearts */
    val SexualActivity: ImageVector by lazy {
        ImageVector.Builder("SexualActivity", W.dp, H.dp, W, H).apply {
            // Small heart
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(8f, 14f)
                curveTo(4f, 11f, 3f, 9f, 3f, 7.5f); curveTo(3f, 6f, 4f, 5f, 5.5f, 5f); curveTo(6.5f, 5f, 7.5f, 5.5f, 8f, 6.5f)
                curveTo(8.5f, 5.5f, 9.5f, 5f, 10.5f, 5f); curveTo(12f, 5f, 13f, 6f, 13f, 7.5f); curveTo(13f, 9f, 12f, 11f, 8f, 14f)
            }
            // Big heart
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(16f, 21f)
                curveTo(11f, 17f, 10f, 15f, 10f, 13f); curveTo(10f, 11f, 11.5f, 10f, 13f, 10f); curveTo(14f, 10f, 15f, 11f, 16f, 12f)
                curveTo(17f, 11f, 18f, 10f, 19f, 10f); curveTo(20.5f, 10f, 22f, 11f, 22f, 13f); curveTo(22f, 15f, 21f, 17f, 16f, 21f)
            }
        }.build()
    }

    /** tobacco — cigarette */
    val Tobacco: ImageVector by lazy {
        ImageVector.Builder("Tobacco", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Cigarette body
                moveTo(2f, 14f); lineTo(18f, 14f); lineTo(18f, 18f); lineTo(2f, 18f); close()
                // Filter
                moveTo(14f, 14f); lineTo(14f, 18f)
                // Smoke wisps
                moveTo(19f, 12f); curveTo(19f, 10f, 21f, 10f, 21f, 8f)
                moveTo(21f, 12f); curveTo(21f, 10f, 23f, 10f, 23f, 8f)
            }
        }.build()
    }

    /** low_blood_sugar — droplet with arrow down */
    val LowBloodSugar: ImageVector by lazy {
        ImageVector.Builder("LowBloodSugar", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Droplet
                moveTo(12f, 3f); curveTo(12f, 3f, 6f, 10f, 6f, 14f)
                arcTo(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = false, 18f, 14f)
                curveTo(18f, 10f, 12f, 3f, 12f, 3f)
            }
            // Arrow down inside
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 11f); lineTo(12f, 18f)
                moveTo(10f, 16f); lineTo(12f, 18f); lineTo(14f, 16f)
            }
        }.build()
    }

    // ─── MENTAL ──────────────────────────────────────────────

    /** screen_time — phone/monitor */
    val ScreenTime: ImageVector by lazy {
        ImageVector.Builder("ScreenTime", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Monitor
                moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 16f); lineTo(3f, 16f); close()
                // Stand
                moveTo(8f, 20f); lineTo(16f, 20f)
                moveTo(12f, 16f); lineTo(12f, 20f)
            }
        }.build()
    }

    /** late_screen — phone with moon */
    val LateScreen: ImageVector by lazy {
        ImageVector.Builder("LateScreen", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Phone
                moveTo(7f, 2f); lineTo(17f, 2f); lineTo(17f, 22f); lineTo(7f, 22f); close()
                moveTo(10f, 19f); lineTo(14f, 19f)
            }
            // Moon inside screen
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(15f, 10f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 10f, 7f)
                curveTo(12.5f, 7f, 15f, 8.5f, 15f, 10f)
            }
        }.build()
    }

    /** noise — sound waves */
    val Noise: ImageVector by lazy {
        ImageVector.Builder("Noise", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Speaker
                moveTo(3f, 9f); lineTo(7f, 9f); lineTo(11f, 5f); lineTo(11f, 19f); lineTo(7f, 15f); lineTo(3f, 15f); close()
                // Sound waves
                moveTo(14f, 8f); curveTo(16f, 9.5f, 16f, 14.5f, 14f, 16f)
                moveTo(17f, 5f); curveTo(20f, 8f, 20f, 16f, 17f, 19f)
            }
        }.build()
    }

    /** stress — head with lightning */
    val Stress: ImageVector by lazy {
        ImageVector.Builder("Stress", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Head circle
                moveTo(19f, 12f)
                arcTo(7f, 7f, 0f, isMoreThanHalf = true, isPositiveArc = true, 5f, 12f)
                arcTo(7f, 7f, 0f, isMoreThanHalf = true, isPositiveArc = true, 19f, 12f)
            }
            // Lightning inside
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(13f, 7f); lineTo(10f, 12f); lineTo(13f, 12f); lineTo(11f, 17f)
            }
        }.build()
    }

    /** anxiety — swirl/spiral in head */
    val Anxiety: ImageVector by lazy {
        ImageVector.Builder("Anxiety", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Head outline
                moveTo(19f, 13f)
                arcTo(7f, 7f, 0f, isMoreThanHalf = true, isPositiveArc = true, 5f, 13f)
                lineTo(5f, 20f)
                moveTo(19f, 13f); lineTo(19f, 20f)
            }
            // Spiral inside
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 11f)
                curveTo(12f, 10f, 13.5f, 9.5f, 14f, 10.5f)
                curveTo(15f, 12f, 13f, 14f, 11f, 13f)
                curveTo(9f, 12f, 9.5f, 8f, 12f, 7f)
            }
        }.build()
    }

    /** depression — rain cloud */
    val Depression: ImageVector by lazy {
        ImageVector.Builder("Depression", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Cloud
                moveTo(6f, 14f); lineTo(18f, 14f)
                curveTo(20f, 14f, 21f, 12f, 21f, 10f); curveTo(21f, 8f, 19f, 6f, 17f, 6f)
                curveTo(17f, 4f, 15f, 2f, 12f, 2f); curveTo(9f, 2f, 7f, 4f, 7f, 6f)
                curveTo(5f, 6f, 3f, 8f, 3f, 10f); curveTo(3f, 12f, 4f, 14f, 6f, 14f)
            }
            // Rain drops
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(8f, 17f); lineTo(8f, 20f)
                moveTo(12f, 17f); lineTo(12f, 22f)
                moveTo(16f, 17f); lineTo(16f, 20f)
            }
        }.build()
    }

    /** anger — explosion/bang */
    val Anger: ImageVector by lazy {
        ImageVector.Builder("Anger", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Jagged star/burst
                moveTo(12f, 2f); lineTo(14f, 8f); lineTo(20f, 6f); lineTo(16f, 12f)
                lineTo(22f, 14f); lineTo(16f, 16f); lineTo(18f, 22f); lineTo(12f, 18f)
                lineTo(6f, 22f); lineTo(8f, 16f); lineTo(2f, 14f); lineTo(8f, 12f)
                lineTo(4f, 6f); lineTo(10f, 8f); close()
            }
        }.build()
    }

    /** letdown — downward trend arrow */
    val Letdown: ImageVector by lazy {
        ImageVector.Builder("Letdown", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Trend down then relief
                moveTo(2f, 6f); lineTo(8f, 6f); lineTo(14f, 16f); lineTo(22f, 10f)
                // Arrow head
                moveTo(18f, 10f); lineTo(22f, 10f); lineTo(22f, 14f)
            }
        }.build()
    }

    /** computer — laptop */
    val Computer: ImageVector by lazy {
        ImageVector.Builder("Computer", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Screen
                moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 16f); lineTo(4f, 16f); close()
                // Base
                moveTo(2f, 20f); lineTo(22f, 20f)
                moveTo(8f, 16f); lineTo(6f, 20f)
                moveTo(16f, 16f); lineTo(18f, 20f)
            }
        }.build()
    }

    /** travel — suitcase */
    val Travel: ImageVector by lazy {
        ImageVector.Builder("Travel", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Suitcase body
                moveTo(3f, 8f); lineTo(21f, 8f); lineTo(21f, 20f); lineTo(3f, 20f); close()
                // Handle
                moveTo(8f, 8f); lineTo(8f, 5f); curveTo(8f, 4f, 9f, 3f, 10f, 3f)
                lineTo(14f, 3f); curveTo(15f, 3f, 16f, 4f, 16f, 5f); lineTo(16f, 8f)
                // Strap
                moveTo(12f, 12f); lineTo(12f, 16f)
            }
        }.build()
    }

    // ─── ENVIRONMENT ─────────────────────────────────────────

    /** pressure — barometer */
    val Pressure: ImageVector by lazy {
        ImageVector.Builder("Pressure", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Gauge circle
                moveTo(21f, 12f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 3f, 12f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 21f, 12f)
                // Needle
                moveTo(12f, 12f); lineTo(8f, 8f)
                // Center dot
                moveTo(13f, 12f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 11f, 12f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 13f, 12f)
            }
        }.build()
    }

    /** humidity — water droplets */
    val Humidity: ImageVector by lazy {
        ImageVector.Builder("Humidity", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Big drop
                moveTo(12f, 3f); curveTo(12f, 3f, 7f, 9f, 7f, 13f)
                arcTo(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 17f, 13f)
                curveTo(17f, 9f, 12f, 3f, 12f, 3f)
            }
            // Small drop
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(18f, 14f); curveTo(18f, 14f, 16f, 17f, 18f, 19f); curveTo(20f, 17f, 18f, 14f, 18f, 14f)
            }
        }.build()
    }

    /** temperature — sun thermometer */
    val Temperature: ImageVector by lazy {
        ImageVector.Builder("Temperature", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Sun
                moveTo(18f, 6f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 12f, 6f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 18f, 6f)
                // Rays
                moveTo(15f, 1f); lineTo(15f, 2f)
                moveTo(15f, 10f); lineTo(15f, 11f)
                moveTo(20f, 6f); lineTo(21f, 6f)
                moveTo(9f, 6f); lineTo(10f, 6f)
            }
            // Small thermometer
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5f, 10f); lineTo(7f, 10f); lineTo(7f, 18f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 5f, 18f)
                lineTo(5f, 10f)
            }
        }.build()
    }

    /** uv — sun with UV label */
    val Uv: ImageVector by lazy {
        ImageVector.Builder("Uv", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Sun circle
                moveTo(17f, 10f)
                arcTo(5f, 5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 7f, 10f)
                arcTo(5f, 5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 17f, 10f)
                // Rays
                moveTo(12f, 2f); lineTo(12f, 3f)
                moveTo(12f, 17f); lineTo(12f, 18f)
                moveTo(4f, 10f); lineTo(3f, 10f)
                moveTo(21f, 10f); lineTo(20f, 10f)
                moveTo(6.3f, 4.3f); lineTo(7f, 5f)
                moveTo(17f, 15f); lineTo(17.7f, 15.7f)
                moveTo(17.7f, 4.3f); lineTo(17f, 5f)
                moveTo(7f, 15f); lineTo(6.3f, 15.7f)
            }
            // UV text hint - small line under
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(8f, 21f); lineTo(16f, 21f)
            }
        }.build()
    }

    /** wind — wind lines */
    val Wind: ImageVector by lazy {
        ImageVector.Builder("Wind", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 8f); lineTo(16f, 8f); curveTo(19f, 8f, 19f, 4f, 16f, 4f)
                moveTo(2f, 12f); lineTo(19f, 12f); curveTo(22f, 12f, 22f, 16f, 19f, 16f)
                moveTo(2f, 16f); lineTo(12f, 16f); curveTo(14f, 16f, 14f, 20f, 12f, 20f)
            }
        }.build()
    }

    /** altitude — mountain */
    val Altitude: ImageVector by lazy {
        ImageVector.Builder("Altitude", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 20f); lineTo(9f, 6f); lineTo(14f, 14f); lineTo(17f, 10f); lineTo(22f, 20f); close()
            }
        }.build()
    }

    /** fluorescent — tube light */
    val Fluorescent: ImageVector by lazy {
        ImageVector.Builder("Fluorescent", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Tube
                moveTo(4f, 10f); lineTo(20f, 10f); lineTo(20f, 14f); lineTo(4f, 14f); close()
                // Mounting lines
                moveTo(8f, 6f); lineTo(8f, 10f)
                moveTo(16f, 6f); lineTo(16f, 10f)
                // Glow rays
                moveTo(6f, 17f); lineTo(6f, 19f)
                moveTo(12f, 17f); lineTo(12f, 20f)
                moveTo(18f, 17f); lineTo(18f, 19f)
            }
        }.build()
    }

    /** smoke — cloud with particles */
    val Smoke: ImageVector by lazy {
        ImageVector.Builder("Smoke", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Smoke cloud
                moveTo(5f, 18f); lineTo(19f, 18f)
                curveTo(21f, 18f, 22f, 16f, 22f, 14f); curveTo(22f, 12f, 20f, 10f, 18f, 10f)
                curveTo(18f, 7f, 15f, 5f, 12f, 5f); curveTo(9f, 5f, 6f, 7f, 6f, 10f)
                curveTo(4f, 10f, 2f, 12f, 2f, 14f); curveTo(2f, 16f, 3f, 18f, 5f, 18f)
            }
            // Particles
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(8f, 21f); lineTo(8.1f, 21f)
                moveTo(12f, 21f); lineTo(12.1f, 21f)
                moveTo(16f, 21f); lineTo(16.1f, 21f)
            }
        }.build()
    }

    /** strong_smell — nose with waves */
    val StrongSmell: ImageVector by lazy {
        ImageVector.Builder("StrongSmell", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Nose
                moveTo(12f, 3f); lineTo(12f, 12f); curveTo(12f, 16f, 8f, 17f, 7f, 15f)
            }
            // Smell waves
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(16f, 10f); curveTo(16f, 8f, 18f, 8f, 18f, 10f); curveTo(18f, 12f, 16f, 12f, 16f, 14f)
                moveTo(19f, 8f); curveTo(19f, 6f, 21f, 6f, 21f, 8f); curveTo(21f, 10f, 19f, 10f, 19f, 12f)
            }
        }.build()
    }

    // ─── MENSTRUAL ───────────────────────────────────────────

    /** menstruation — droplet */
    val Menstruation: ImageVector by lazy {
        ImageVector.Builder("Menstruation", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3f); curveTo(12f, 3f, 5f, 11f, 5f, 15f)
                arcTo(7f, 7f, 0f, isMoreThanHalf = false, isPositiveArc = false, 19f, 15f)
                curveTo(19f, 11f, 12f, 3f, 12f, 3f)
            }
        }.build()
    }

    /** ovulation — egg cell */
    val Ovulation: ImageVector by lazy {
        ImageVector.Builder("Ovulation", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Outer circle
                moveTo(20f, 12f)
                arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4f, 12f)
                arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 20f, 12f)
                // Inner nucleus
                moveTo(15f, 11f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9f, 11f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 15f, 11f)
            }
        }.build()
    }

    /** contraceptive — pill pack */
    val Contraceptive: ImageVector by lazy {
        ImageVector.Builder("Contraceptive", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Pack outline
                moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close()
                // Pill dots (grid)
                moveTo(8f, 8f); lineTo(8.1f, 8f)
                moveTo(12f, 8f); lineTo(12.1f, 8f)
                moveTo(16f, 8f); lineTo(16.1f, 8f)
                moveTo(8f, 12f); lineTo(8.1f, 12f)
                moveTo(12f, 12f); lineTo(12.1f, 12f)
                moveTo(16f, 12f); lineTo(16.1f, 12f)
                moveTo(8f, 16f); lineTo(8.1f, 16f)
                moveTo(12f, 16f); lineTo(12.1f, 16f)
                moveTo(16f, 16f); lineTo(16.1f, 16f)
            }
        }.build()
    }

    // ─── DIET ────────────────────────────────────────────────

    /** calories — flame */
    val Calories: ImageVector by lazy {
        ImageVector.Builder("Calories", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 22f)
                curveTo(7f, 22f, 4f, 18f, 4f, 14f)
                curveTo(4f, 10f, 8f, 6f, 12f, 2f)
                curveTo(16f, 6f, 20f, 10f, 20f, 14f)
                curveTo(20f, 18f, 17f, 22f, 12f, 22f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 20f)
                curveTo(10f, 20f, 8.5f, 18.5f, 8.5f, 16.5f)
                curveTo(8.5f, 14.5f, 10f, 13f, 12f, 11f)
                curveTo(14f, 13f, 15.5f, 14.5f, 15.5f, 16.5f)
                curveTo(15.5f, 18.5f, 14f, 20f, 12f, 20f)
            }
        }.build()
    }

    /** caffeine — coffee cup */
    val Caffeine: ImageVector by lazy {
        ImageVector.Builder("Caffeine", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Cup
                moveTo(4f, 8f); lineTo(16f, 8f); lineTo(15f, 20f); lineTo(5f, 20f); close()
                // Handle
                moveTo(16f, 10f); curveTo(19f, 10f, 19f, 16f, 16f, 16f)
                // Steam
                moveTo(8f, 4f); curveTo(8f, 3f, 9f, 3f, 9f, 4f); curveTo(9f, 5f, 10f, 5f, 10f, 4f)
                moveTo(12f, 4f); curveTo(12f, 3f, 13f, 3f, 13f, 4f); curveTo(13f, 5f, 14f, 5f, 14f, 4f)
            }
        }.build()
    }

    /** alcohol — wine glass */
    val Alcohol: ImageVector by lazy {
        ImageVector.Builder("Alcohol", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Glass bowl
                moveTo(6f, 2f); lineTo(18f, 2f); curveTo(18f, 10f, 14f, 12f, 12f, 12f)
                curveTo(10f, 12f, 6f, 10f, 6f, 2f)
                // Stem
                moveTo(12f, 12f); lineTo(12f, 19f)
                // Base
                moveTo(8f, 19f); lineTo(16f, 19f)
                // Wine level
                moveTo(7f, 6f); lineTo(17f, 6f)
            }
        }.build()
    }

    /** sugar — cube with S */
    val Sugar: ImageVector by lazy {
        ImageVector.Builder("Sugar", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Cube
                moveTo(4f, 6f); lineTo(12f, 2f); lineTo(20f, 6f); lineTo(20f, 18f); lineTo(12f, 22f); lineTo(4f, 18f); close()
                moveTo(12f, 2f); lineTo(12f, 22f)
                moveTo(4f, 6f); lineTo(20f, 18f)
            }
        }.build()
    }

    /** sodium — salt shaker */
    val Sodium: ImageVector by lazy {
        ImageVector.Builder("Sodium", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Shaker body
                moveTo(7f, 8f); lineTo(17f, 8f); lineTo(16f, 22f); lineTo(8f, 22f); close()
                // Cap
                moveTo(8f, 8f); curveTo(8f, 5f, 10f, 3f, 12f, 3f); curveTo(14f, 3f, 16f, 5f, 16f, 8f)
                // Holes
                moveTo(10f, 5f); lineTo(10.1f, 5f)
                moveTo(12f, 4f); lineTo(12.1f, 4f)
                moveTo(14f, 5f); lineTo(14.1f, 5f)
            }
        }.build()
    }

    /** cholesterol — droplet with cross */
    val Cholesterol: ImageVector by lazy {
        ImageVector.Builder("Cholesterol", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3f); curveTo(12f, 3f, 5f, 11f, 5f, 15f)
                arcTo(7f, 7f, 0f, isMoreThanHalf = false, isPositiveArc = false, 19f, 15f)
                curveTo(19f, 11f, 12f, 3f, 12f, 3f)
                // Blocked sign
                moveTo(8f, 13f); lineTo(16f, 17f)
            }
        }.build()
    }

    /** protein — meat/drumstick */
    val Protein: ImageVector by lazy {
        ImageVector.Builder("Protein", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Drumstick
                moveTo(16f, 4f); curveTo(20f, 4f, 22f, 8f, 19f, 12f)
                curveTo(16f, 16f, 12f, 14f, 12f, 14f)
                lineTo(6f, 20f); curveTo(4f, 22f, 2f, 20f, 4f, 18f)
                lineTo(10f, 12f); curveTo(10f, 12f, 8f, 8f, 12f, 5f)
                curveTo(14f, 3f, 16f, 4f, 16f, 4f)
            }
        }.build()
    }

    /** carbs — bread slice */
    val Carbs: ImageVector by lazy {
        ImageVector.Builder("Carbs", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Bread shape
                moveTo(5f, 10f); curveTo(5f, 5f, 8f, 3f, 12f, 3f); curveTo(16f, 3f, 19f, 5f, 19f, 10f)
                lineTo(19f, 20f); lineTo(5f, 20f); close()
            }
        }.build()
    }

    /** fat — oil drop */
    val Fat: ImageVector by lazy {
        ImageVector.Builder("Fat", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3f); curveTo(12f, 3f, 6f, 10f, 6f, 14f)
                arcTo(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = false, 18f, 14f)
                curveTo(18f, 10f, 12f, 3f, 12f, 3f)
                // Shine
                moveTo(10f, 14f); curveTo(9f, 12f, 10f, 10f, 12f, 9f)
            }
        }.build()
    }

    /** fibre — wheat stalk */
    val Fibre: ImageVector by lazy {
        ImageVector.Builder("Fibre", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Stem
                moveTo(12f, 22f); lineTo(12f, 6f)
                // Leaves
                moveTo(12f, 8f); curveTo(8f, 6f, 7f, 3f, 7f, 3f)
                moveTo(12f, 8f); curveTo(16f, 6f, 17f, 3f, 17f, 3f)
                moveTo(12f, 12f); curveTo(8f, 10f, 6f, 8f, 6f, 8f)
                moveTo(12f, 12f); curveTo(16f, 10f, 18f, 8f, 18f, 8f)
                moveTo(12f, 16f); curveTo(8f, 14f, 6f, 12f, 6f, 12f)
                moveTo(12f, 16f); curveTo(16f, 14f, 18f, 12f, 18f, 12f)
            }
        }.build()
    }

    /** gluten — wheat with X */
    val Gluten: ImageVector by lazy {
        ImageVector.Builder("Gluten", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 22f); lineTo(12f, 8f)
                moveTo(12f, 10f); curveTo(8f, 8f, 7f, 5f, 7f, 5f)
                moveTo(12f, 10f); curveTo(16f, 8f, 17f, 5f, 17f, 5f)
                moveTo(12f, 14f); curveTo(8f, 12f, 6f, 10f, 6f, 10f)
                moveTo(12f, 14f); curveTo(16f, 12f, 18f, 10f, 18f, 10f)
            }
            // X over it
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(3f, 3f); lineTo(21f, 21f)
                moveTo(21f, 3f); lineTo(3f, 21f)
            }
        }.build()
    }

    /** tyramine — cheese wedge */
    val Tyramine: ImageVector by lazy {
        ImageVector.Builder("Tyramine", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Cheese wedge
                moveTo(2f, 18f); lineTo(22f, 18f); lineTo(22f, 8f); close()
                // Holes
                moveTo(10f, 15f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 7f, 15f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 10f, 15f)
                moveTo(18f, 13f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 16f, 13f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 18f, 13f)
            }
        }.build()
    }

    /** iron — magnet shape */
    val Iron: ImageVector by lazy {
        ImageVector.Builder("Iron", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Horseshoe magnet
                moveTo(6f, 4f); lineTo(6f, 14f)
                arcTo(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = false, 18f, 14f)
                lineTo(18f, 4f)
                // Pole caps
                moveTo(3f, 4f); lineTo(9f, 4f)
                moveTo(15f, 4f); lineTo(21f, 4f)
            }
        }.build()
    }

    /** magnesium — Mg atom */
    val Magnesium: ImageVector by lazy {
        ImageVector.Builder("Magnesium", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Atom circle
                moveTo(20f, 12f)
                arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4f, 12f)
                arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 20f, 12f)
                // Electron orbits
                moveTo(12f, 5f); curveTo(16f, 5f, 20f, 8f, 18f, 12f)
                moveTo(12f, 19f); curveTo(8f, 19f, 4f, 16f, 6f, 12f)
            }
        }.build()
    }

    /** skipped_meals — plate with X */
    val SkippedMeals: ImageVector by lazy {
        ImageVector.Builder("SkippedMeals", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Plate circle
                moveTo(20f, 12f)
                arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4f, 12f)
                arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 20f, 12f)
            }
            // X
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(9f, 9f); lineTo(15f, 15f)
                moveTo(15f, 9f); lineTo(9f, 15f)
            }
        }.build()
    }

    /** mineral — gemstone/crystal */
    val Mineral: ImageVector by lazy {
        ImageVector.Builder("Mineral", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Diamond/gem shape
                moveTo(6f, 3f); lineTo(18f, 3f); lineTo(22f, 9f); lineTo(12f, 21f); lineTo(2f, 9f); close()
                // Facet lines
                moveTo(2f, 9f); lineTo(22f, 9f)
                moveTo(6f, 3f); lineTo(12f, 9f); lineTo(18f, 3f)
            }
        }.build()
    }

    /** vitamin — capsule pill */
    val Vitamin: ImageVector by lazy {
        ImageVector.Builder("Vitamin", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Capsule vertical
                moveTo(9f, 6f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 15f, 6f)
                lineTo(15f, 18f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9f, 18f)
                close()
                // Divider
                moveTo(9f, 12f); lineTo(15f, 12f)
            }
        }.build()
    }

    // ─── LOOKUP ──────────────────────────────────────────────

    data class TriggerPickerIcon(val key: String, val label: String, val icon: ImageVector)

    val ALL_ICONS: List<TriggerPickerIcon> by lazy { listOf(
        TriggerPickerIcon("duration", "Duration", Duration),
        TriggerPickerIcon("score", "Score", Score),
        TriggerPickerIcon("efficiency", "Efficiency", Efficiency),
        TriggerPickerIcon("disturbances", "Disturbances", Disturbances),
        TriggerPickerIcon("bedtime", "Bedtime", Bedtime),
        TriggerPickerIcon("wake_time", "Wake time", WakeTime),
        TriggerPickerIcon("deep_sleep", "Deep sleep", DeepSleep),
        TriggerPickerIcon("rem_sleep", "REM sleep", RemSleep),
        TriggerPickerIcon("light_sleep", "Light sleep", LightSleep),
        TriggerPickerIcon("jet_lag", "Jet lag", JetLag),
        TriggerPickerIcon("sleep_apnea", "Sleep apnea", SleepApnea),
        TriggerPickerIcon("recovery", "Recovery", Recovery),
        TriggerPickerIcon("spo2", "SpO2", SpO2),
        TriggerPickerIcon("skin_temp", "Skin temp", SkinTemp),
        TriggerPickerIcon("resp_rate", "Resp rate", RespRate),
        TriggerPickerIcon("hr_zones", "HR zones", HrZones),
        TriggerPickerIcon("steps", "Steps", Steps),
        TriggerPickerIcon("body_fat", "Body fat", BodyFat),
        TriggerPickerIcon("weight", "Weight", Weight),
        TriggerPickerIcon("bp", "Blood pressure", Bp),
        TriggerPickerIcon("blood_glucose", "Blood glucose", BloodGlucose),
        TriggerPickerIcon("strain", "Strain", Strain),
        TriggerPickerIcon("hydration", "Hydration", Hydration),
        TriggerPickerIcon("allergies", "Allergies", Allergies),
        TriggerPickerIcon("illness", "Illness", Illness),
        TriggerPickerIcon("medication", "Medication", Medication),
        TriggerPickerIcon("motion_sickness", "Motion sickness", MotionSickness),
        TriggerPickerIcon("sexual_activity", "Sexual activity", SexualActivity),
        TriggerPickerIcon("tobacco", "Tobacco", Tobacco),
        TriggerPickerIcon("low_blood_sugar", "Low blood sugar", LowBloodSugar),
        TriggerPickerIcon("screen_time", "Screen time", ScreenTime),
        TriggerPickerIcon("late_screen", "Late screen", LateScreen),
        TriggerPickerIcon("noise", "Noise", Noise),
        TriggerPickerIcon("stress", "Stress", Stress),
        TriggerPickerIcon("anxiety", "Anxiety", Anxiety),
        TriggerPickerIcon("depression", "Depression", Depression),
        TriggerPickerIcon("anger", "Anger", Anger),
        TriggerPickerIcon("letdown", "Let-down", Letdown),
        TriggerPickerIcon("computer", "Computer", Computer),
        TriggerPickerIcon("travel", "Travel", Travel),
        TriggerPickerIcon("pressure", "Pressure", Pressure),
        TriggerPickerIcon("humidity", "Humidity", Humidity),
        TriggerPickerIcon("temperature", "Temperature", Temperature),
        TriggerPickerIcon("uv", "UV", Uv),
        TriggerPickerIcon("wind", "Wind", Wind),
        TriggerPickerIcon("altitude", "Altitude", Altitude),
        TriggerPickerIcon("fluorescent", "Fluorescent", Fluorescent),
        TriggerPickerIcon("smoke", "Smoke", Smoke),
        TriggerPickerIcon("strong_smell", "Strong smell", StrongSmell),
        TriggerPickerIcon("menstruation", "Menstruation", Menstruation),
        TriggerPickerIcon("ovulation", "Ovulation", Ovulation),
        TriggerPickerIcon("contraceptive", "Contraceptive", Contraceptive),
        TriggerPickerIcon("calories", "Calories", Calories),
        TriggerPickerIcon("caffeine", "Caffeine", Caffeine),
        TriggerPickerIcon("alcohol", "Alcohol", Alcohol),
        TriggerPickerIcon("sugar", "Sugar", Sugar),
        TriggerPickerIcon("sodium", "Sodium", Sodium),
        TriggerPickerIcon("cholesterol", "Cholesterol", Cholesterol),
        TriggerPickerIcon("protein", "Protein", Protein),
        TriggerPickerIcon("carbs", "Carbs", Carbs),
        TriggerPickerIcon("fat", "Fat", Fat),
        TriggerPickerIcon("fibre", "Fibre", Fibre),
        TriggerPickerIcon("gluten", "Gluten", Gluten),
        TriggerPickerIcon("tyramine", "Tyramine", Tyramine),
        TriggerPickerIcon("iron", "Iron", Iron),
        TriggerPickerIcon("magnesium", "Magnesium", Magnesium),
        TriggerPickerIcon("skipped_meals", "Skipped meals", SkippedMeals),
        TriggerPickerIcon("mineral", "Mineral", Mineral),
        TriggerPickerIcon("vitamin", "Vitamin", Vitamin),
    ) }

    fun forKey(key: String?): ImageVector? = ALL_ICONS.find { it.key == key }?.icon
}
