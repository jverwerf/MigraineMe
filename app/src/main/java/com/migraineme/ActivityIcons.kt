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

    // ─── LOOKUP ────────────────────────────────────────────────

    data class ActivityPickerIcon(val key: String, val label: String, val icon: ImageVector)

    val ALL_ICONS: List<ActivityPickerIcon> by lazy { listOf(
        ActivityPickerIcon("cycling", "Cycling", Cycling),
        ActivityPickerIcon("exercising", "Exercising", Exercising),
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
        ActivityPickerIcon("meeting", "Meeting", Meeting),
        ActivityPickerIcon("presenting", "Presenting", Presenting),
        ActivityPickerIcon("studying", "Studying", Studying),
        ActivityPickerIcon("working", "Working", Working),
        ActivityPickerIcon("other", "Other", Other),
    ) }

    fun forKey(key: String?): ImageVector? = ALL_ICONS.find { it.key == key }?.icon
}
