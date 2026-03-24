package com.migraineme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object MissedActivityIcons {

    private val stroke = SolidColor(Color.White)
    private const val W = 24f
    private const val H = 24f

    // ─── CARE ───────────────────────────────────────────────

    /** childcare — adult + small figure */
    val Childcare: ImageVector by lazy {
        ImageVector.Builder("MissedChildcare", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(19f, 5f); arcTo(2f, 2f, 0f, true, true, 15f, 5f); arcTo(2f, 2f, 0f, true, true, 19f, 5f)
                moveTo(17f, 7f); lineTo(17f, 13f)
                moveTo(17f, 10f); lineTo(21f, 12f); moveTo(17f, 10f); lineTo(13f, 8f)
                moveTo(17f, 13f); lineTo(15f, 20f); moveTo(17f, 13f); lineTo(19f, 20f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9f, 8f); arcTo(2f, 2f, 0f, true, true, 5f, 8f); arcTo(2f, 2f, 0f, true, true, 9f, 8f)
                moveTo(7f, 10f); lineTo(7f, 14f)
                moveTo(7f, 14f); lineTo(5f, 19f); moveTo(7f, 14f); lineTo(9f, 19f)
            }
        }.build()
    }

    /** pet_care — paw print */
    val PetCare: ImageVector by lazy {
        ImageVector.Builder("PetCare", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Main pad
                moveTo(12f, 16f); curveTo(10f, 14f, 7f, 14f, 7f, 17f); curveTo(7f, 20f, 12f, 22f, 12f, 22f)
                curveTo(12f, 22f, 17f, 20f, 17f, 17f); curveTo(17f, 14f, 14f, 14f, 12f, 16f)
            }
            // Toe pads
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(7f, 10f); arcTo(1.5f, 2f, 0f, true, true, 7f, 10.01f)
                moveTo(10.5f, 7f); arcTo(1.5f, 2f, 0f, true, true, 10.5f, 7.01f)
                moveTo(13.5f, 7f); arcTo(1.5f, 2f, 0f, true, true, 13.5f, 7.01f)
                moveTo(17f, 10f); arcTo(1.5f, 2f, 0f, true, true, 17f, 10.01f)
            }
        }.build()
    }

    /** self_care — heart in hand */
    val SelfCare: ImageVector by lazy {
        ImageVector.Builder("SelfCare", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Heart
                moveTo(12f, 10f); curveTo(12f, 7f, 9f, 5f, 7.5f, 7f); curveTo(6f, 9f, 8f, 12f, 12f, 15f)
                curveTo(16f, 12f, 18f, 9f, 16.5f, 7f); curveTo(15f, 5f, 12f, 7f, 12f, 10f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Cupped hand
                moveTo(5f, 18f); curveTo(5f, 16f, 8f, 15f, 12f, 15f); curveTo(16f, 15f, 19f, 16f, 19f, 18f)
                lineTo(19f, 20f); lineTo(5f, 20f); close()
            }
        }.build()
    }

    // ─── EXERCISE ───────────────────────────────────────────

    /** exercise — jumping figure */
    val Exercise: ImageVector by lazy {
        ImageVector.Builder("MissedExercise", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(14f, 4f); arcTo(2f, 2f, 0f, true, true, 10f, 4f); arcTo(2f, 2f, 0f, true, true, 14f, 4f)
                moveTo(12f, 6f); lineTo(12f, 13f)
                moveTo(12f, 9f); lineTo(6f, 6f); moveTo(12f, 9f); lineTo(18f, 6f)
                moveTo(12f, 13f); lineTo(7f, 20f); moveTo(12f, 13f); lineTo(17f, 20f)
            }
        }.build()
    }

    /** gym — dumbbell */
    val Gym: ImageVector by lazy {
        ImageVector.Builder("MissedGym", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6f, 12f); lineTo(18f, 12f)
                moveTo(3f, 9f); lineTo(3f, 15f); moveTo(6f, 8f); lineTo(6f, 16f)
                moveTo(21f, 9f); lineTo(21f, 15f); moveTo(18f, 8f); lineTo(18f, 16f)
            }
        }.build()
    }

    /** sport — trophy */
    val Sport: ImageVector by lazy {
        ImageVector.Builder("Sport", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(7f, 4f); lineTo(17f, 4f); lineTo(17f, 10f); curveTo(17f, 13f, 15f, 15f, 12f, 15f)
                curveTo(9f, 15f, 7f, 13f, 7f, 10f); close()
                moveTo(12f, 15f); lineTo(12f, 18f)
                moveTo(8f, 20f); lineTo(16f, 20f)
                moveTo(12f, 18f); lineTo(12f, 20f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(7f, 6f); curveTo(5f, 6f, 3f, 7f, 3f, 9f); curveTo(3f, 11f, 5f, 12f, 7f, 12f)
                moveTo(17f, 6f); curveTo(19f, 6f, 21f, 7f, 21f, 9f); curveTo(21f, 11f, 19f, 12f, 17f, 12f)
            }
        }.build()
    }

    /** walk — person walking */
    val Walk: ImageVector by lazy {
        ImageVector.Builder("MissedWalk", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(14f, 4f); arcTo(2f, 2f, 0f, true, true, 10f, 4f); arcTo(2f, 2f, 0f, true, true, 14f, 4f)
                moveTo(12f, 6f); lineTo(12f, 14f)
                moveTo(12f, 9f); lineTo(8f, 12f); moveTo(12f, 9f); lineTo(16f, 11f)
                moveTo(12f, 14f); lineTo(9f, 22f); moveTo(12f, 14f); lineTo(15f, 22f)
            }
        }.build()
    }

    // ─── LEISURE ────────────────────────────────────────────

    /** hobbies — palette */
    val Hobbies: ImageVector by lazy {
        ImageVector.Builder("MissedHobbies", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 2f); curveTo(17f, 2f, 22f, 6f, 22f, 12f); curveTo(22f, 14f, 20f, 15f, 18f, 15f)
                lineTo(16f, 15f); curveTo(15f, 15f, 14f, 16f, 14f, 17f); curveTo(14f, 18f, 14f, 19f, 13f, 20f)
                curveTo(12f, 21f, 10f, 22f, 8f, 22f); curveTo(4f, 22f, 2f, 18f, 2f, 12f); curveTo(2f, 6f, 6f, 2f, 12f, 2f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(7f, 12f); lineTo(7.1f, 12f)
                moveTo(12f, 7f); lineTo(12.1f, 7f)
                moveTo(17f, 10f); lineTo(17.1f, 10f)
            }
        }.build()
    }

    // ─── OTHER ──────────────────────────────────────────────

    /** chores — broom */
    val Chores: ImageVector by lazy {
        ImageVector.Builder("Chores", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 2f); lineTo(12f, 12f)
                moveTo(8f, 12f); lineTo(16f, 12f); lineTo(18f, 22f); lineTo(6f, 22f); close()
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(19f, 3f); lineTo(19f, 5f); moveTo(18f, 4f); lineTo(20f, 4f)
                moveTo(5f, 6f); lineTo(5f, 8f); moveTo(4f, 7f); lineTo(6f, 7f)
            }
        }.build()
    }

    /** cooking — pot with steam */
    val Cooking: ImageVector by lazy {
        ImageVector.Builder("MissedCooking", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 12f); lineTo(20f, 12f); lineTo(20f, 18f); curveTo(20f, 20f, 18f, 21f, 16f, 21f)
                lineTo(8f, 21f); curveTo(6f, 21f, 4f, 20f, 4f, 18f); close()
                moveTo(2f, 12f); lineTo(22f, 12f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(9f, 9f); curveTo(9f, 7f, 10f, 6f, 9f, 5f)
                moveTo(12f, 9f); curveTo(12f, 7f, 13f, 6f, 12f, 5f)
                moveTo(15f, 9f); curveTo(15f, 7f, 16f, 6f, 15f, 5f)
            }
        }.build()
    }

    /** shopping — bag */
    val Shopping: ImageVector by lazy {
        ImageVector.Builder("MissedShopping", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5f, 8f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 8f); close()
                moveTo(8f, 8f); lineTo(8f, 6f); curveTo(8f, 4f, 10f, 2f, 12f, 2f); curveTo(14f, 2f, 16f, 4f, 16f, 6f); lineTo(16f, 8f)
            }
        }.build()
    }

    // ─── SOCIAL ─────────────────────────────────────────────

    /** date — heart */
    val Date: ImageVector by lazy {
        ImageVector.Builder("Date", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 8f); curveTo(12f, 5f, 9f, 3f, 7f, 5f); curveTo(5f, 7f, 5f, 10f, 12f, 19f)
                curveTo(19f, 10f, 19f, 7f, 17f, 5f); curveTo(15f, 3f, 12f, 5f, 12f, 8f)
            }
        }.build()
    }

    /** family_event — people with star */
    val FamilyEvent: ImageVector by lazy {
        ImageVector.Builder("FamilyEvent", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(11f, 8f); arcTo(3f, 3f, 0f, true, true, 5f, 8f); arcTo(3f, 3f, 0f, true, true, 11f, 8f)
                moveTo(1f, 20f); curveTo(1f, 16f, 4f, 14f, 8f, 14f); curveTo(10f, 14f, 12f, 15f, 13f, 16f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(19f, 4f); lineTo(19.7f, 6f); lineTo(22f, 6.3f); lineTo(20.3f, 8f); lineTo(20.7f, 10.3f)
                lineTo(19f, 9.2f); lineTo(17.3f, 10.3f); lineTo(17.7f, 8f); lineTo(16f, 6.3f); lineTo(18.3f, 6f); close()
            }
        }.build()
    }

    /** night_out — moon + glass */
    val NightOut: ImageVector by lazy {
        ImageVector.Builder("NightOut", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Cocktail glass
                moveTo(7f, 3f); lineTo(17f, 3f); lineTo(12f, 12f); close()
                moveTo(12f, 12f); lineTo(12f, 19f)
                moveTo(8f, 19f); lineTo(16f, 19f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(19f, 5f); lineTo(19f, 7f); moveTo(18f, 6f); lineTo(20f, 6f)
            }
        }.build()
    }

    /** social_plans — two people */
    val SocialPlans: ImageVector by lazy {
        ImageVector.Builder("SocialPlans", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(11f, 6f); arcTo(3f, 3f, 0f, true, true, 5f, 6f); arcTo(3f, 3f, 0f, true, true, 11f, 6f)
                moveTo(1f, 20f); curveTo(1f, 16f, 4f, 13f, 8f, 13f); curveTo(12f, 13f, 15f, 16f, 15f, 20f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(19f, 6f); arcTo(2.5f, 2.5f, 0f, true, true, 14f, 6f); arcTo(2.5f, 2.5f, 0f, true, true, 19f, 6f)
                moveTo(23f, 20f); curveTo(23f, 17f, 21f, 14f, 17f, 14f)
            }
        }.build()
    }

    // ─── TRAVEL ─────────────────────────────────────────────

    /** driving — car */
    val Driving: ImageVector by lazy {
        ImageVector.Builder("MissedDriving", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5f, 11f); lineTo(7f, 5f); lineTo(17f, 5f); lineTo(19f, 11f)
                moveTo(3f, 11f); lineTo(21f, 11f); lineTo(21f, 17f); lineTo(3f, 17f); close()
                moveTo(5f, 17f); lineTo(5f, 19f); moveTo(19f, 17f); lineTo(19f, 19f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(7f, 14f); lineTo(7.1f, 14f); moveTo(17f, 14f); lineTo(17.1f, 14f)
            }
        }.build()
    }

    /** travel — suitcase */
    val Travel: ImageVector by lazy {
        ImageVector.Builder("Travel", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3f, 8f); lineTo(21f, 8f); lineTo(21f, 20f); lineTo(3f, 20f); close()
                moveTo(8f, 8f); lineTo(8f, 5f); curveTo(8f, 4f, 9f, 3f, 10f, 3f); lineTo(14f, 3f)
                curveTo(15f, 3f, 16f, 4f, 16f, 5f); lineTo(16f, 8f)
                moveTo(12f, 12f); lineTo(12f, 16f)
            }
        }.build()
    }

    // ─── WORK ───────────────────────────────────────────────

    /** meeting — people at table */
    val Meeting: ImageVector by lazy {
        ImageVector.Builder("MissedMeeting", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 14f); lineTo(20f, 14f)
                moveTo(7f, 10f); arcTo(2f, 2f, 0f, true, true, 3f, 10f); arcTo(2f, 2f, 0f, true, true, 7f, 10f)
                moveTo(14f, 10f); arcTo(2f, 2f, 0f, true, true, 10f, 10f); arcTo(2f, 2f, 0f, true, true, 14f, 10f)
                moveTo(21f, 10f); arcTo(2f, 2f, 0f, true, true, 17f, 10f); arcTo(2f, 2f, 0f, true, true, 21f, 10f)
                moveTo(8f, 14f); lineTo(8f, 20f); moveTo(16f, 14f); lineTo(16f, 20f)
            }
        }.build()
    }

    /** school — graduation cap */
    val School: ImageVector by lazy {
        ImageVector.Builder("MissedSchool", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 10f); lineTo(12f, 5f); lineTo(22f, 10f); lineTo(12f, 15f); close()
                moveTo(6f, 12f); lineTo(6f, 17f); curveTo(6f, 17f, 9f, 20f, 12f, 20f); curveTo(15f, 20f, 18f, 17f, 18f, 17f); lineTo(18f, 12f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(22f, 10f); lineTo(22f, 16f)
            }
        }.build()
    }

    /** study — open book */
    val Study: ImageVector by lazy {
        ImageVector.Builder("MissedStudy", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 4f); lineTo(2f, 18f); curveTo(2f, 18f, 5f, 16f, 12f, 16f)
                moveTo(22f, 4f); lineTo(22f, 18f); curveTo(22f, 18f, 19f, 16f, 12f, 16f)
                moveTo(12f, 16f); lineTo(12f, 4f)
                moveTo(2f, 4f); curveTo(2f, 4f, 5f, 2f, 12f, 4f)
                moveTo(22f, 4f); curveTo(22f, 4f, 19f, 2f, 12f, 4f)
            }
        }.build()
    }

    /** work — briefcase */
    val Work: ImageVector by lazy {
        ImageVector.Builder("MissedWork", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3f, 8f); lineTo(21f, 8f); lineTo(21f, 20f); lineTo(3f, 20f); close()
                moveTo(8f, 8f); lineTo(8f, 5f); curveTo(8f, 4f, 9f, 3f, 10f, 3f); lineTo(14f, 3f)
                curveTo(15f, 3f, 16f, 4f, 16f, 5f); lineTo(16f, 8f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(3f, 13f); lineTo(21f, 13f)
            }
        }.build()
    }

    /** other — circle X */
    val Other: ImageVector by lazy {
        ImageVector.Builder("MissedOther", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 2f); arcTo(10f, 10f, 0f, true, true, 12f, 22f); arcTo(10f, 10f, 0f, true, true, 12f, 2f)
                moveTo(9f, 9f); lineTo(15f, 15f); moveTo(15f, 9f); lineTo(9f, 15f)
            }
        }.build()
    }

    /** hiking — person with mountain */
    val Hiking: ImageVector by lazy {
        ImageVector.Builder("MissedHiking", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(1f, 20f); lineTo(8f, 8f); lineTo(13f, 14f); lineTo(17f, 10f); lineTo(23f, 20f)
            }
            path(stroke = stroke, strokeLineWidth = 1.3f, strokeLineCap = StrokeCap.Round) {
                moveTo(9.8f, 6.8f)
                arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 6.2f, 6.8f)
                arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9.8f, 6.8f)
                moveTo(11f, 8f); lineTo(13f, 15f)
            }
        }.build()
    }

    /** flying — airplane */
    val Flying: ImageVector by lazy {
        ImageVector.Builder("MissedFlying", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(22f, 8f)
                curveTo(22f, 8f, 20f, 6f, 17f, 7f)
                lineTo(13f, 9f); lineTo(5f, 5f); lineTo(3f, 7f); lineTo(9f, 12f)
                lineTo(6f, 14f); lineTo(3f, 13f); lineTo(2f, 14.5f); lineTo(6f, 16.5f)
                lineTo(8f, 21f); lineTo(10f, 20f); lineTo(9f, 16f); lineTo(12f, 14f)
                lineTo(14f, 20f); lineTo(16f, 19f); lineTo(14f, 12f)
                lineTo(18f, 10f)
                curveTo(21f, 9f, 22f, 8f, 22f, 8f)
                close()
            }
        }.build()
    }

    /** concert — stage spotlight with music notes */
    val Concert: ImageVector by lazy {
        ImageVector.Builder("MissedConcert", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 20f); lineTo(8f, 12f); lineTo(16f, 12f); lineTo(20f, 20f); close()
            }
            path(stroke = stroke, strokeLineWidth = 1.3f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 12f); lineTo(12f, 6f)
            }
            path(stroke = stroke, strokeLineWidth = 1.1f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(8f, 9f); lineTo(8f, 5f); lineTo(14f, 4f); lineTo(14f, 8f)
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(9.2f, 9f)
                arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 6.8f, 9f)
                arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9.2f, 9f)
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(15.2f, 8f)
                arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 12.8f, 8f)
                arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 15.2f, 8f)
            }
        }.build()
    }

    /** meditation — seated figure with aura */
    val Meditation: ImageVector by lazy {
        ImageVector.Builder("Meditation", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(14f, 5f); arcTo(2f, 2f, 0f, true, true, 10f, 5f); arcTo(2f, 2f, 0f, true, true, 14f, 5f)
                moveTo(12f, 7f); lineTo(12f, 14f)
                moveTo(12f, 14f); lineTo(7f, 18f); moveTo(12f, 14f); lineTo(17f, 18f)
                moveTo(12f, 10f); lineTo(7.5f, 14f); moveTo(12f, 10f); lineTo(16.5f, 14f)
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 1.5f); arcTo(0.7f, 0.7f, 0f, true, true, 12f, 2.9f); arcTo(0.7f, 0.7f, 0f, true, true, 12f, 1.5f)
                moveTo(6f, 8f); arcTo(0.6f, 0.6f, 0f, true, true, 6f, 9.2f); arcTo(0.6f, 0.6f, 0f, true, true, 6f, 8f)
                moveTo(18f, 8f); arcTo(0.6f, 0.6f, 0f, true, true, 18f, 9.2f); arcTo(0.6f, 0.6f, 0f, true, true, 18f, 8f)
            }
        }.build()
    }

    /** gardening — flower / plant */
    val Gardening: ImageVector by lazy {
        ImageVector.Builder("Gardening", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 22f); lineTo(12f, 10f)
                moveTo(12f, 16f); curveTo(6f, 14f, 6f, 10f, 12f, 12f)
                moveTo(12f, 14f); curveTo(18f, 12f, 18f, 8f, 12f, 10f)
                moveTo(12f, 10f); curveTo(10f, 8f, 8f, 5f, 10f, 3f)
                moveTo(12f, 10f); curveTo(14f, 8f, 16f, 5f, 14f, 3f)
                moveTo(12f, 10f); curveTo(9f, 9f, 6f, 7f, 8f, 4f)
                moveTo(12f, 10f); curveTo(15f, 9f, 18f, 7f, 16f, 4f)
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(13.2f, 9f); arcTo(1.2f, 1.2f, 0f, true, true, 10.8f, 9f); arcTo(1.2f, 1.2f, 0f, true, true, 13.2f, 9f)
            }
        }.build()
    }

    /** dancing — figure with arms up */
    val Dancing: ImageVector by lazy {
        ImageVector.Builder("Dancing", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(14f, 4f); arcTo(2f, 2f, 0f, true, true, 10f, 4f); arcTo(2f, 2f, 0f, true, true, 14f, 4f)
                moveTo(12f, 6f); curveTo(12f, 9f, 13f, 11f, 12f, 14f)
                moveTo(12f, 8f); curveTo(9f, 6f, 6f, 4f, 5f, 2f)
                moveTo(12f, 8f); curveTo(15f, 6f, 18f, 5f, 20f, 3f)
                moveTo(12f, 14f); curveTo(10f, 17f, 8f, 19f, 6f, 21f)
                moveTo(12f, 14f); curveTo(14f, 16f, 16f, 17f, 18f, 20f)
            }
        }.build()
    }

    /** music — music notes */
    val Music: ImageVector by lazy {
        ImageVector.Builder("Music", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9f, 18f); lineTo(9f, 6f); lineTo(19f, 4f); lineTo(19f, 16f)
            }
            path(fill = SolidColor(Color.White), stroke = stroke, strokeLineWidth = 1.2f) {
                moveTo(11f, 18f); arcTo(2f, 1.5f, 330f, true, true, 7f, 18f); arcTo(2f, 1.5f, 330f, true, true, 11f, 18f)
            }
            path(fill = SolidColor(Color.White), stroke = stroke, strokeLineWidth = 1.2f) {
                moveTo(21f, 16f); arcTo(2f, 1.5f, 330f, true, true, 17f, 16f); arcTo(2f, 1.5f, 330f, true, true, 21f, 16f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(9f, 8f); lineTo(19f, 6f)
            }
        }.build()
    }

    /** swimming — swimmer figure */
    val Swimming: ImageVector by lazy {
        ImageVector.Builder("Swimming", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(8f, 8f); arcTo(2f, 2f, 0f, true, true, 4f, 8f); arcTo(2f, 2f, 0f, true, true, 8f, 8f)
                moveTo(6f, 10f); lineTo(10f, 12f); lineTo(18f, 10f)
                moveTo(10f, 12f); lineTo(8f, 16f)
                // Waves
                moveTo(2f, 18f); curveTo(4f, 16f, 6f, 20f, 8f, 18f); curveTo(10f, 16f, 12f, 20f, 14f, 18f); curveTo(16f, 16f, 18f, 20f, 20f, 18f); curveTo(21f, 17f, 22f, 19f, 22f, 18f)
            }
        }.build()
    }

    /** cycling — bicycle (for missed activities) */
    val Cycling: ImageVector by lazy {
        ImageVector.Builder("Cycling", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(7f, 18f); arcTo(3f, 3f, 0f, true, true, 1f, 18f); arcTo(3f, 3f, 0f, true, true, 7f, 18f)
                moveTo(23f, 18f); arcTo(3f, 3f, 0f, true, true, 17f, 18f); arcTo(3f, 3f, 0f, true, true, 23f, 18f)
                moveTo(12f, 18f); lineTo(12f, 12f); lineTo(17f, 7f)
                moveTo(12f, 12f); lineTo(4f, 18f)
                moveTo(17f, 7f); lineTo(20f, 7f)
            }
        }.build()
    }

    /** reading — open book */
    val Reading: ImageVector by lazy {
        ImageVector.Builder("Reading", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 6f); curveTo(10f, 4f, 6f, 4f, 2f, 5f); lineTo(2f, 20f); curveTo(6f, 19f, 10f, 19f, 12f, 21f)
                moveTo(12f, 6f); curveTo(14f, 4f, 18f, 4f, 22f, 5f); lineTo(22f, 20f); curveTo(18f, 19f, 14f, 19f, 12f, 21f)
                moveTo(12f, 6f); lineTo(12f, 21f)
            }
        }.build()
    }

    /** napping — moon with z's */
    val Napping: ImageVector by lazy {
        ImageVector.Builder("Napping", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Crescent moon
                moveTo(15f, 4f); curveTo(10f, 4f, 6f, 8f, 6f, 13f); curveTo(6f, 18f, 10f, 22f, 15f, 22f)
                curveTo(12f, 20f, 10f, 17f, 10f, 13f); curveTo(10f, 9f, 12f, 6f, 15f, 4f)
            }
            // Z's
            path(stroke = stroke, strokeLineWidth = 1.3f, strokeLineCap = StrokeCap.Round) {
                moveTo(17f, 7f); lineTo(21f, 7f); lineTo(17f, 11f); lineTo(21f, 11f)
            }
        }.build()
    }

    /** partying — same as NightOut */
    val Partying: ImageVector get() = NightOut

    // ─── LOOKUP ────────────────────────────────────────────────

    data class MissedActivityPickerIcon(val key: String, val label: String, val icon: ImageVector)

    val ALL_ICONS: List<MissedActivityPickerIcon> by lazy { listOf(
        MissedActivityPickerIcon("childcare", "Childcare", Childcare),
        MissedActivityPickerIcon("pet_care", "Pet care", PetCare),
        MissedActivityPickerIcon("self_care", "Self-care", SelfCare),
        MissedActivityPickerIcon("exercise", "Exercise", Exercise),
        MissedActivityPickerIcon("gym", "Gym", Gym),
        MissedActivityPickerIcon("hiking", "Hiking", Hiking),
        MissedActivityPickerIcon("sport", "Sport", Sport),
        MissedActivityPickerIcon("walk", "Walk", Walk),
        MissedActivityPickerIcon("hobbies", "Hobbies", Hobbies),
        MissedActivityPickerIcon("chores", "Chores", Chores),
        MissedActivityPickerIcon("cooking", "Cooking", Cooking),
        MissedActivityPickerIcon("shopping", "Shopping", Shopping),
        MissedActivityPickerIcon("date", "Date", Date),
        MissedActivityPickerIcon("family_event", "Family event", FamilyEvent),
        MissedActivityPickerIcon("night_out", "Night out", NightOut),
        MissedActivityPickerIcon("concert", "Concert / event", Concert),
        MissedActivityPickerIcon("social_plans", "Social plans", SocialPlans),
        MissedActivityPickerIcon("driving", "Driving", Driving),
        MissedActivityPickerIcon("flying", "Flying", Flying),
        MissedActivityPickerIcon("travel", "Travel", Travel),
        MissedActivityPickerIcon("meeting", "Meeting", Meeting),
        MissedActivityPickerIcon("school", "School", School),
        MissedActivityPickerIcon("study", "Study", Study),
        MissedActivityPickerIcon("work", "Work", Work),
        MissedActivityPickerIcon("meditation", "Meditation", Meditation),
        MissedActivityPickerIcon("gardening", "Gardening", Gardening),
        MissedActivityPickerIcon("dancing", "Dancing", Dancing),
        MissedActivityPickerIcon("music", "Music", Music),
        MissedActivityPickerIcon("swimming", "Swimming", Swimming),
        MissedActivityPickerIcon("cycling", "Cycling", Cycling),
        MissedActivityPickerIcon("reading", "Reading", Reading),
        MissedActivityPickerIcon("napping", "Napping", Napping),
        MissedActivityPickerIcon("partying", "Partying", Partying),
        MissedActivityPickerIcon("other", "Other", Other),
    ) }

    fun forKey(key: String?): ImageVector? = ALL_ICONS.find { it.key == key }?.icon

    fun forLabel(label: String, iconKey: String? = null): ImageVector? {
        if (iconKey != null) return forKey(iconKey)
        val l = label.lowercase()
        return when {
            l.contains("child") || l.contains("kid") || l.contains("parent") -> Childcare
            l.contains("pet") || l.contains("dog") || l.contains("cat") -> PetCare
            l.contains("self-care") || l.contains("self care") || l.contains("groom") -> SelfCare
            l.contains("exercis") || l.contains("workout") -> Exercise
            l.contains("gym") || l.contains("weight") -> Gym
            l.contains("hik") || l.contains("trek") -> Hiking
            l.contains("sport") || l.contains("match") || l.contains("game") -> Sport
            l.contains("walk") || l.contains("hike") || l.contains("run") || l.contains("jog") -> Walk
            l.contains("swim") -> Swimming
            l.contains("cycl") || l.contains("bike") -> Cycling
            l.contains("hobb") || l.contains("craft") || l.contains("diy") || l.contains("garden") -> Hobbies
            l.contains("garden") -> Gardening
            l.contains("read") -> Reading
            l.contains("chore") || l.contains("clean") || l.contains("housework") -> Chores
            l.contains("cook") || l.contains("bak") || l.contains("meal") -> Cooking
            l.contains("shop") -> Shopping
            l.contains("date") || l.contains("sex") || l.contains("intimat") -> Date
            l.contains("family") || l.contains("gathering") || l.contains("reunion") -> FamilyEvent
            l.contains("night out") || l.contains("party") || l.contains("club") -> NightOut
            l.contains("danc") -> Dancing
            l.contains("concert") || l.contains("gig") || l.contains("show") || l.contains("event") -> Concert
            l.contains("music") || l.contains("listen") -> Music
            l.contains("social") || l.contains("friend") || l.contains("plan") || l.contains("hangout") -> SocialPlans
            l.contains("driv") || l.contains("car") -> Driving
            l.contains("fly") || l.contains("flight") || l.contains("plane") || l.contains("airport") -> Flying
            l.contains("travel") || l.contains("trip") || l.contains("vacat") || l.contains("commut") -> Travel
            l.contains("meeting") -> Meeting
            l.contains("school") || l.contains("class") -> School
            l.contains("study") || l.contains("exam") || l.contains("homework") -> Study
            l.contains("work") || l.contains("office") || l.contains("job") || l.contains("shift") -> Work
            l.contains("yoga") || l.contains("pilates") || l.contains("stretch") -> Exercise
            l.contains("meditat") || l.contains("mindful") -> Meditation
            l.contains("nap") || l.contains("sleep") -> Napping
            else -> Other
        }
    }
}
