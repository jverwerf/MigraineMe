package com.migraineme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object ActivityIcons {

    private val stroke = SolidColor(Color.White)
    private const val W = 24f
    private const val H = 24f

    // ─── EXERCISE ───────────────────────────────────────────

    /** cycling — bicycle */
    val Cycling: ImageVector by lazy {
        ImageVector.Builder("Cycling", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(7f, 18f); arcTo(3f, 3f, 0f, true, true, 1f, 18f); arcTo(3f, 3f, 0f, true, true, 7f, 18f)
                moveTo(23f, 18f); arcTo(3f, 3f, 0f, true, true, 17f, 18f); arcTo(3f, 3f, 0f, true, true, 23f, 18f)
                moveTo(12f, 18f); lineTo(12f, 12f); lineTo(17f, 7f)
                moveTo(12f, 12f); lineTo(4f, 18f)
                moveTo(17f, 7f); lineTo(20f, 7f)
                moveTo(14f, 4f); lineTo(17f, 4f); lineTo(17f, 7f)
            }
        }.build()
    }

    /** exercising — jumping figure */
    val Exercising: ImageVector by lazy {
        ImageVector.Builder("Exercising", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(14f, 4f); arcTo(2f, 2f, 0f, true, true, 10f, 4f); arcTo(2f, 2f, 0f, true, true, 14f, 4f)
                moveTo(12f, 6f); lineTo(12f, 13f)
                moveTo(12f, 9f); lineTo(6f, 6f); moveTo(12f, 9f); lineTo(18f, 6f)
                moveTo(12f, 13f); lineTo(7f, 20f); moveTo(12f, 13f); lineTo(17f, 20f)
            }
        }.build()
    }

    /** running — runner */
    val Running: ImageVector by lazy {
        ImageVector.Builder("Running", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(16f, 4f); arcTo(2f, 2f, 0f, true, true, 12f, 4f); arcTo(2f, 2f, 0f, true, true, 16f, 4f)
                moveTo(10f, 22f); lineTo(12f, 16f); lineTo(15f, 14f)
                moveTo(6f, 16f); lineTo(9f, 12f); lineTo(14f, 10f); lineTo(18f, 12f)
                moveTo(14f, 10f); lineTo(16f, 6f)
                moveTo(18f, 22f); lineTo(16f, 16f); lineTo(15f, 14f)
            }
        }.build()
    }

    /** swimming — swimmer */
    val Swimming: ImageVector by lazy {
        ImageVector.Builder("Swimming", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(7f, 6f); arcTo(2f, 2f, 0f, true, true, 3f, 6f); arcTo(2f, 2f, 0f, true, true, 7f, 6f)
                moveTo(5f, 8f); lineTo(10f, 11f); lineTo(16f, 8f)
                moveTo(10f, 11f); lineTo(10f, 14f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 17f); curveTo(4f, 15f, 6f, 15f, 8f, 17f); curveTo(10f, 19f, 12f, 19f, 14f, 17f)
                curveTo(16f, 15f, 18f, 15f, 20f, 17f); curveTo(21f, 18f, 22f, 18f, 22f, 17f)
            }
        }.build()
    }

    /** walking — person walking */
    val Walking: ImageVector by lazy {
        ImageVector.Builder("Walking", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(14f, 4f); arcTo(2f, 2f, 0f, true, true, 10f, 4f); arcTo(2f, 2f, 0f, true, true, 14f, 4f)
                moveTo(12f, 6f); lineTo(12f, 14f)
                moveTo(12f, 9f); lineTo(8f, 12f); moveTo(12f, 9f); lineTo(16f, 11f)
                moveTo(12f, 14f); lineTo(9f, 22f); moveTo(12f, 14f); lineTo(15f, 22f)
            }
        }.build()
    }

    /** yoga — tree pose */
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

    // ─── LEISURE ────────────────────────────────────────────

    /** gaming — game controller */
    val Gaming: ImageVector by lazy {
        ImageVector.Builder("Gaming", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 9f); curveTo(4f, 7f, 6f, 6f, 8f, 6f); lineTo(16f, 6f); curveTo(18f, 6f, 20f, 7f, 20f, 9f)
                lineTo(20f, 13f); curveTo(20f, 16f, 18f, 18f, 16f, 18f); lineTo(8f, 18f); curveTo(6f, 18f, 4f, 16f, 4f, 13f); close()
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(8f, 10f); lineTo(8f, 14f); moveTo(6f, 12f); lineTo(10f, 12f)
                moveTo(15f, 10f); lineTo(15.1f, 10f); moveTo(17f, 13f); lineTo(17.1f, 13f)
            }
        }.build()
    }

    /** hobbies — palette */
    val Hobbies: ImageVector by lazy {
        ImageVector.Builder("Hobbies", W.dp, H.dp, W, H).apply {
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

    /** reading — open book */
    val Reading: ImageVector by lazy {
        ImageVector.Builder("Reading", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 4f); lineTo(2f, 18f); curveTo(2f, 18f, 5f, 16f, 12f, 16f)
                moveTo(22f, 4f); lineTo(22f, 18f); curveTo(22f, 18f, 19f, 16f, 12f, 16f)
                moveTo(12f, 16f); lineTo(12f, 4f)
                moveTo(2f, 4f); curveTo(2f, 4f, 5f, 2f, 12f, 4f)
                moveTo(22f, 4f); curveTo(22f, 4f, 19f, 2f, 12f, 4f)
            }
        }.build()
    }

    // ─── OTHER ──────────────────────────────────────────────

    /** childcare — baby/person */
    val Childcare: ImageVector by lazy {
        ImageVector.Builder("Childcare", W.dp, H.dp, W, H).apply {
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

    /** cleaning — broom/sparkle */
    val Cleaning: ImageVector by lazy {
        ImageVector.Builder("Cleaning", W.dp, H.dp, W, H).apply {
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
        ImageVector.Builder("Cooking", W.dp, H.dp, W, H).apply {
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
        ImageVector.Builder("Shopping", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5f, 8f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 8f); close()
                moveTo(8f, 8f); lineTo(8f, 6f); curveTo(8f, 4f, 10f, 2f, 12f, 2f); curveTo(14f, 2f, 16f, 4f, 16f, 6f); lineTo(16f, 8f)
            }
        }.build()
    }

    // ─── SCREEN ─────────────────────────────────────────────

    /** phone — mobile phone */
    val Phone: ImageVector by lazy {
        ImageVector.Builder("Phone", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(7f, 2f); lineTo(17f, 2f); curveTo(18f, 2f, 19f, 3f, 19f, 4f); lineTo(19f, 20f)
                curveTo(19f, 21f, 18f, 22f, 17f, 22f); lineTo(7f, 22f); curveTo(6f, 22f, 5f, 21f, 5f, 20f); lineTo(5f, 4f); curveTo(5f, 3f, 6f, 2f, 7f, 2f)
                moveTo(12f, 18f); lineTo(12.1f, 18f)
            }
        }.build()
    }

    /** screen_time — monitor */
    val ScreenTime: ImageVector by lazy {
        ImageVector.Builder("ScreenTime", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 16f); lineTo(3f, 16f); close()
                moveTo(12f, 16f); lineTo(12f, 20f)
                moveTo(8f, 20f); lineTo(16f, 20f)
            }
        }.build()
    }

    /** tv_film — TV with play */
    val TvFilm: ImageVector by lazy {
        ImageVector.Builder("TvFilm", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 17f); lineTo(3f, 17f); close()
                moveTo(7f, 21f); lineTo(17f, 21f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(10f, 8f); lineTo(16f, 11f); lineTo(10f, 14f); close()
            }
        }.build()
    }

    // ─── SLEEP ──────────────────────────────────────────────

    /** napping — bed with Zzz */
    val Napping: ImageVector by lazy {
        ImageVector.Builder("Napping", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3f, 20f); lineTo(3f, 12f); lineTo(21f, 12f); lineTo(21f, 20f)
                moveTo(3f, 12f); curveTo(3f, 12f, 5f, 9f, 8f, 9f); curveTo(10f, 9f, 10f, 12f, 10f, 12f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(15f, 3f); lineTo(19f, 3f); lineTo(15f, 7f); lineTo(19f, 7f)
            }
        }.build()
    }

    /** sleeping — moon with stars */
    val Sleeping: ImageVector by lazy {
        ImageVector.Builder("Sleeping", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(20f, 12f); curveTo(20f, 16.4f, 16.4f, 20f, 12f, 20f); curveTo(7.6f, 20f, 4f, 16.4f, 4f, 12f)
                curveTo(4f, 7.6f, 7.6f, 4f, 12f, 4f); curveTo(10f, 6f, 9f, 10f, 11f, 14f); curveTo(13f, 18f, 17f, 18f, 20f, 12f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(19f, 4f); lineTo(19f, 6f); moveTo(18f, 5f); lineTo(20f, 5f)
            }
        }.build()
    }

    // ─── SOCIAL ─────────────────────────────────────────────

    /** eating_out — fork + knife */
    val EatingOut: ImageVector by lazy {
        ImageVector.Builder("EatingOut", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(7f, 2f); lineTo(7f, 9f); curveTo(7f, 11f, 8f, 12f, 10f, 12f); lineTo(10f, 22f)
                moveTo(7f, 6f); lineTo(4f, 6f); lineTo(4f, 2f)
                moveTo(7f, 6f); lineTo(10f, 6f); lineTo(10f, 2f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(17f, 2f); lineTo(17f, 8f); curveTo(17f, 10f, 19f, 12f, 20f, 12f)
                moveTo(17f, 12f); lineTo(17f, 22f)
            }
        }.build()
    }

    /** partying — party popper */
    val Partying: ImageVector by lazy {
        ImageVector.Builder("Partying", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 22f); lineTo(10f, 2f); lineTo(20f, 12f); close()
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(16f, 3f); lineTo(18f, 2f)
                moveTo(20f, 5f); lineTo(22f, 4f)
                moveTo(21f, 8f); lineTo(22f, 9f)
            }
        }.build()
    }

    /** socialising — two people */
    val Socialising: ImageVector by lazy {
        ImageVector.Builder("Socialising", W.dp, H.dp, W, H).apply {
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

    /** commuting — train */
    val Commuting: ImageVector by lazy {
        ImageVector.Builder("Commuting", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6f, 3f); lineTo(18f, 3f); curveTo(19f, 3f, 20f, 4f, 20f, 5f); lineTo(20f, 16f)
                curveTo(20f, 17f, 19f, 18f, 18f, 18f); lineTo(6f, 18f); curveTo(5f, 18f, 4f, 17f, 4f, 16f); lineTo(4f, 5f); curveTo(4f, 4f, 5f, 3f, 6f, 3f)
                moveTo(4f, 11f); lineTo(20f, 11f)
                moveTo(7f, 22f); lineTo(4f, 18f); moveTo(17f, 22f); lineTo(20f, 18f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(8f, 15f); lineTo(8.1f, 15f); moveTo(16f, 15f); lineTo(16.1f, 15f)
            }
        }.build()
    }

    /** driving — car */
    val Driving: ImageVector by lazy {
        ImageVector.Builder("Driving", W.dp, H.dp, W, H).apply {
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

    // ─── WORK ───────────────────────────────────────────────

    /** meeting — people at table */
    val Meeting: ImageVector by lazy {
        ImageVector.Builder("Meeting", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 14f); lineTo(20f, 14f)
                moveTo(7f, 10f); arcTo(2f, 2f, 0f, true, true, 3f, 10f); arcTo(2f, 2f, 0f, true, true, 7f, 10f)
                moveTo(14f, 10f); arcTo(2f, 2f, 0f, true, true, 10f, 10f); arcTo(2f, 2f, 0f, true, true, 14f, 10f)
                moveTo(21f, 10f); arcTo(2f, 2f, 0f, true, true, 17f, 10f); arcTo(2f, 2f, 0f, true, true, 21f, 10f)
                moveTo(8f, 14f); lineTo(8f, 20f); moveTo(16f, 14f); lineTo(16f, 20f)
            }
        }.build()
    }

    /** presenting — whiteboard */
    val Presenting: ImageVector by lazy {
        ImageVector.Builder("Presenting", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3f, 3f); lineTo(21f, 3f); lineTo(21f, 15f); lineTo(3f, 15f); close()
                moveTo(12f, 15f); lineTo(12f, 19f)
                moveTo(8f, 19f); lineTo(16f, 19f)
                moveTo(12f, 3f); lineTo(12f, 1f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(7f, 7f); lineTo(14f, 7f)
                moveTo(7f, 10f); lineTo(11f, 10f)
            }
        }.build()
    }

    /** studying — graduation cap */
    val Studying: ImageVector by lazy {
        ImageVector.Builder("Studying", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 10f); lineTo(12f, 5f); lineTo(22f, 10f); lineTo(12f, 15f); close()
                moveTo(6f, 12f); lineTo(6f, 17f); curveTo(6f, 17f, 9f, 20f, 12f, 20f); curveTo(15f, 20f, 18f, 17f, 18f, 17f); lineTo(18f, 12f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(22f, 10f); lineTo(22f, 16f)
            }
        }.build()
    }

    /** working — briefcase */
    val Working: ImageVector by lazy {
        ImageVector.Builder("Working", W.dp, H.dp, W, H).apply {
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

    /** other — activity pulse */
    val Other: ImageVector by lazy {
        ImageVector.Builder("ActivityOther", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 12f); lineTo(6f, 12f); lineTo(9f, 4f); lineTo(12f, 20f); lineTo(15f, 8f); lineTo(18f, 12f); lineTo(22f, 12f)
            }
        }.build()
    }

    /** gym — dumbbell */
    val Gym: ImageVector by lazy {
        ImageVector.Builder("Gym", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
                moveTo(8f, 12f); lineTo(16f, 12f)
            }
            path(stroke = stroke, strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 9f); lineTo(5f, 9f); lineTo(5f, 15f); lineTo(2f, 15f); close()
                moveTo(5f, 10.5f); lineTo(8f, 10.5f)
                moveTo(5f, 13.5f); lineTo(8f, 13.5f)
                moveTo(22f, 9f); lineTo(19f, 9f); lineTo(19f, 15f); lineTo(22f, 15f); close()
                moveTo(19f, 10.5f); lineTo(16f, 10.5f)
                moveTo(19f, 13.5f); lineTo(16f, 13.5f)
            }
        }.build()
    }

    /** hiking — person silhouette with mountain */
    val Hiking: ImageVector by lazy {
        ImageVector.Builder("Hiking", W.dp, H.dp, W, H).apply {
            // Mountain
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(1f, 20f); lineTo(8f, 8f); lineTo(13f, 14f); lineTo(17f, 10f); lineTo(23f, 20f)
            }
            // Head
            path(stroke = stroke, strokeLineWidth = 1.3f, strokeLineCap = StrokeCap.Round) {
                moveTo(9.8f, 6.8f)
                arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 6.2f, 6.8f)
                arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9.8f, 6.8f)
            }
            // Walking stick
            path(stroke = stroke, strokeLineWidth = 1.3f, strokeLineCap = StrokeCap.Round) {
                moveTo(11f, 8f); lineTo(13f, 15f)
            }
        }.build()
    }

    /** flying — airplane */
    val Flying: ImageVector by lazy {
        ImageVector.Builder("Flying", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Fuselage
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
        ImageVector.Builder("Concert", W.dp, H.dp, W, H).apply {
            // Stage trapezoid
            path(stroke = stroke, strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 20f); lineTo(8f, 12f); lineTo(16f, 12f); lineTo(20f, 20f); close()
            }
            // Spotlight beam
            path(stroke = stroke, strokeLineWidth = 1.3f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 12f); lineTo(12f, 6f)
            }
            // Music note 1
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
                // Head
                moveTo(14f, 5f); arcTo(2f, 2f, 0f, true, true, 10f, 5f); arcTo(2f, 2f, 0f, true, true, 14f, 5f)
                // Body sitting
                moveTo(12f, 7f); lineTo(12f, 14f)
                // Crossed legs
                moveTo(12f, 14f); lineTo(7f, 18f); moveTo(12f, 14f); lineTo(17f, 18f)
                // Arms resting on knees
                moveTo(12f, 10f); lineTo(7.5f, 14f); moveTo(12f, 10f); lineTo(16.5f, 14f)
            }
            // Aura dots
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
                // Stem
                moveTo(12f, 22f); lineTo(12f, 10f)
                // Left leaf
                moveTo(12f, 16f); curveTo(6f, 14f, 6f, 10f, 12f, 12f)
                // Right leaf
                moveTo(12f, 14f); curveTo(18f, 12f, 18f, 8f, 12f, 10f)
                // Flower petals
                moveTo(12f, 10f); curveTo(10f, 8f, 8f, 5f, 10f, 3f)
                moveTo(12f, 10f); curveTo(14f, 8f, 16f, 5f, 14f, 3f)
                moveTo(12f, 10f); curveTo(9f, 9f, 6f, 7f, 8f, 4f)
                moveTo(12f, 10f); curveTo(15f, 9f, 18f, 7f, 16f, 4f)
            }
            // Flower center
            path(fill = SolidColor(Color.White)) {
                moveTo(13.2f, 9f); arcTo(1.2f, 1.2f, 0f, true, true, 10.8f, 9f); arcTo(1.2f, 1.2f, 0f, true, true, 13.2f, 9f)
            }
        }.build()
    }

    /** dancing — figure with arms up */
    val Dancing: ImageVector by lazy {
        ImageVector.Builder("Dancing", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Head
                moveTo(14f, 4f); arcTo(2f, 2f, 0f, true, true, 10f, 4f); arcTo(2f, 2f, 0f, true, true, 14f, 4f)
                // Body slight curve
                moveTo(12f, 6f); curveTo(12f, 9f, 13f, 11f, 12f, 14f)
                // Arms up in dance pose
                moveTo(12f, 8f); curveTo(9f, 6f, 6f, 4f, 5f, 2f)
                moveTo(12f, 8f); curveTo(15f, 6f, 18f, 5f, 20f, 3f)
                // Legs in movement
                moveTo(12f, 14f); curveTo(10f, 17f, 8f, 19f, 6f, 21f)
                moveTo(12f, 14f); curveTo(14f, 16f, 16f, 17f, 18f, 20f)
            }
        }.build()
    }

    /** stretching — figure reaching */
    val Stretching: ImageVector by lazy {
        ImageVector.Builder("Stretching", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Head
                moveTo(14f, 5f); arcTo(2f, 2f, 0f, true, true, 10f, 5f); arcTo(2f, 2f, 0f, true, true, 14f, 5f)
                // Body
                moveTo(12f, 7f); lineTo(12f, 15f)
                // One arm up, one arm horizontal
                moveTo(12f, 9f); lineTo(6f, 7f); moveTo(12f, 9f); lineTo(18f, 4f)
                // Legs in lunge
                moveTo(12f, 15f); lineTo(7f, 21f); moveTo(12f, 15f); lineTo(17f, 19f); lineTo(20f, 21f)
            }
        }.build()
    }

    /** music — headphones / music note */
    val Music: ImageVector by lazy {
        ImageVector.Builder("Music", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Note stem
                moveTo(9f, 18f); lineTo(9f, 6f); lineTo(19f, 4f); lineTo(19f, 16f)
            }
            // Note heads (filled ovals)
            path(fill = SolidColor(Color.White), stroke = stroke, strokeLineWidth = 1.2f) {
                moveTo(11f, 18f); arcTo(2f, 1.5f, 330f, true, true, 7f, 18f); arcTo(2f, 1.5f, 330f, true, true, 11f, 18f)
            }
            path(fill = SolidColor(Color.White), stroke = stroke, strokeLineWidth = 1.2f) {
                moveTo(21f, 16f); arcTo(2f, 1.5f, 330f, true, true, 17f, 16f); arcTo(2f, 1.5f, 330f, true, true, 21f, 16f)
            }
            // Beam
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(9f, 8f); lineTo(19f, 6f)
            }
        }.build()
    }

    /** diy — wrench/hammer */
    val DIY: ImageVector by lazy {
        ImageVector.Builder("DIY", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Hammer head
                moveTo(6f, 4f); lineTo(14f, 4f); lineTo(14f, 8f); lineTo(6f, 8f); close()
                // Handle
                moveTo(10f, 8f); lineTo(10f, 20f)
                // Nail
                moveTo(16f, 12f); lineTo(20f, 12f)
                moveTo(18f, 10f); lineTo(18f, 14f)
            }
        }.build()
    }

    /** pet care — paw print */
    val PetCare: ImageVector by lazy {
        ImageVector.Builder("PetCare", W.dp, H.dp, W, H).apply {
            // Main pad
            path(fill = SolidColor(Color.White), stroke = stroke, strokeLineWidth = 1.2f) {
                moveTo(12f, 18f); curveTo(9f, 18f, 7f, 15f, 8f, 13f); curveTo(9f, 11f, 11f, 11f, 12f, 12f)
                curveTo(13f, 11f, 15f, 11f, 16f, 13f); curveTo(17f, 15f, 15f, 18f, 12f, 18f)
            }
            // Toe pads
            path(fill = SolidColor(Color.White)) {
                moveTo(8.5f, 10f); arcTo(1.5f, 1.8f, 0f, true, true, 8.5f, 10.01f)
                moveTo(11f, 8f); arcTo(1.3f, 1.6f, 0f, true, true, 11f, 8.01f)
                moveTo(15.5f, 10f); arcTo(1.5f, 1.8f, 0f, true, true, 15.5f, 10.01f)
                moveTo(13f, 8f); arcTo(1.3f, 1.6f, 0f, true, true, 13f, 8.01f)
            }
        }.build()
    }

    /** self care — heart with sparkle */
    val SelfCare: ImageVector by lazy {
        ImageVector.Builder("SelfCare", W.dp, H.dp, W, H).apply {
            // Heart
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 20f); curveTo(4f, 14f, 2f, 10f, 5f, 7f)
                curveTo(7f, 5f, 10f, 6f, 12f, 9f)
                curveTo(14f, 6f, 17f, 5f, 19f, 7f)
                curveTo(22f, 10f, 20f, 14f, 12f, 20f)
            }
            // Sparkle
            path(stroke = stroke, strokeLineWidth = 1.2f, strokeLineCap = StrokeCap.Round) {
                moveTo(18f, 3f); lineTo(18f, 5f)
                moveTo(17f, 4f); lineTo(19f, 4f)
            }
        }.build()
    }

    // ─── LOOKUP ────────────────────────────────────────────────

    data class ActivityPickerIcon(val key: String, val label: String, val icon: ImageVector)

    val ALL_ICONS: List<ActivityPickerIcon> by lazy { listOf(
        ActivityPickerIcon("cycling", "Cycling", Cycling),
        ActivityPickerIcon("exercising", "Exercising", Exercising),
        ActivityPickerIcon("gym", "Gym", Gym),
        ActivityPickerIcon("hiking", "Hiking", Hiking),
        ActivityPickerIcon("running", "Running", Running),
        ActivityPickerIcon("swimming", "Swimming", Swimming),
        ActivityPickerIcon("walking", "Walking", Walking),
        ActivityPickerIcon("yoga", "Yoga", Yoga),
        ActivityPickerIcon("gaming", "Gaming", Gaming),
        ActivityPickerIcon("hobbies", "Hobbies", Hobbies),
        ActivityPickerIcon("reading", "Reading", Reading),
        ActivityPickerIcon("childcare", "Childcare", Childcare),
        ActivityPickerIcon("cleaning", "Cleaning", Cleaning),
        ActivityPickerIcon("cooking", "Cooking", Cooking),
        ActivityPickerIcon("shopping", "Shopping", Shopping),
        ActivityPickerIcon("phone", "Phone", Phone),
        ActivityPickerIcon("screen_time", "Screen time", ScreenTime),
        ActivityPickerIcon("tv_film", "TV / Film", TvFilm),
        ActivityPickerIcon("napping", "Napping", Napping),
        ActivityPickerIcon("sleeping", "Sleeping", Sleeping),
        ActivityPickerIcon("eating_out", "Eating out", EatingOut),
        ActivityPickerIcon("partying", "Partying", Partying),
        ActivityPickerIcon("socialising", "Socialising", Socialising),
        ActivityPickerIcon("commuting", "Commuting", Commuting),
        ActivityPickerIcon("driving", "Driving", Driving),
        ActivityPickerIcon("flying", "Flying", Flying),
        ActivityPickerIcon("concert", "Concert / event", Concert),
        ActivityPickerIcon("meeting", "Meeting", Meeting),
        ActivityPickerIcon("presenting", "Presenting", Presenting),
        ActivityPickerIcon("studying", "Studying", Studying),
        ActivityPickerIcon("working", "Working", Working),
        ActivityPickerIcon("meditation", "Meditation", Meditation),
        ActivityPickerIcon("gardening", "Gardening", Gardening),
        ActivityPickerIcon("dancing", "Dancing", Dancing),
        ActivityPickerIcon("stretching", "Stretching", Stretching),
        ActivityPickerIcon("music", "Music", Music),
        ActivityPickerIcon("diy", "DIY", DIY),
        ActivityPickerIcon("pet_care", "Pet care", PetCare),
        ActivityPickerIcon("self_care", "Self-care", SelfCare),
        ActivityPickerIcon("other", "Other", Other),
    ) }

    fun forKey(key: String?): ImageVector? = ALL_ICONS.find { it.key == key }?.icon

    fun forLabel(label: String, iconKey: String? = null): ImageVector? {
        if (iconKey != null) return forKey(iconKey)
        val l = label.lowercase()
        return when {
            l.contains("cycl") || l.contains("bike") || l.contains("biking") -> Cycling
            l.contains("exercis") || l.contains("workout") -> Exercising
            l.contains("gym") || l.contains("weight") || l.contains("lift") -> Gym
            l.contains("hik") || l.contains("trek") -> Hiking
            l.contains("run") || l.contains("jog") -> Running
            l.contains("swim") -> Swimming
            l.contains("walk") || l.contains("hike") -> Walking
            l.contains("yoga") || l.contains("pilates") -> Yoga
            l.contains("stretch") -> Stretching
            l.contains("meditat") || l.contains("mindful") -> Meditation
            l.contains("gam") || l.contains("video game") -> Gaming
            l.contains("hobb") || l.contains("craft") -> Hobbies
            l.contains("diy") -> DIY
            l.contains("garden") -> Gardening
            l.contains("read") -> Reading
            l.contains("child") || l.contains("kid") || l.contains("parent") -> Childcare
            l.contains("clean") || l.contains("chore") || l.contains("housework") -> Cleaning
            l.contains("cook") || l.contains("bak") -> Cooking
            l.contains("shop") -> Shopping
            l.contains("phone") || l.contains("call") -> Phone
            l.contains("screen") || l.contains("comput") || l.contains("laptop") -> ScreenTime
            l.contains("tv") || l.contains("film") || l.contains("movie") || l.contains("netflix") || l.contains("watch") -> TvFilm
            l.contains("nap") -> Napping
            l.contains("sleep") -> Sleeping
            l.contains("eating out") || l.contains("restaurant") || l.contains("dinner out") -> EatingOut
            l.contains("party") || l.contains("club") -> Partying
            l.contains("danc") -> Dancing
            l.contains("social") || l.contains("friend") || l.contains("hangout") || l.contains("hang out") -> Socialising
            l.contains("commut") || l.contains("train") || l.contains("bus") -> Commuting
            l.contains("driv") || l.contains("car") -> Driving
            l.contains("fly") || l.contains("flight") || l.contains("plane") || l.contains("airport") -> Flying
            l.contains("concert") || l.contains("event") || l.contains("gig") || l.contains("show") -> Concert
            l.contains("music") || l.contains("listen") -> Music
            l.contains("meeting") -> Meeting
            l.contains("present") || l.contains("speech") || l.contains("talk") -> Presenting
            l.contains("study") || l.contains("school") || l.contains("homework") || l.contains("exam") -> Studying
            l.contains("work") || l.contains("office") || l.contains("job") -> Working
            l.contains("sex") || l.contains("intimat") -> Exercising
            l.contains("pet") || l.contains("dog") || l.contains("cat") -> PetCare
            l.contains("self-care") || l.contains("self care") || l.contains("groom") -> SelfCare
            else -> Other
        }
    }
}
