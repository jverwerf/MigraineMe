package com.migraineme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object LocationIcons {

    private val stroke = SolidColor(Color.White)
    private const val W = 24f
    private const val H = 24f

    // ─── EXERCISE ───────────────────────────────────────────

    /** gym — dumbbell */
    val Gym: ImageVector by lazy {
        ImageVector.Builder("Gym", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6f, 9f); lineTo(6f, 15f); moveTo(4f, 10f); lineTo(4f, 14f); moveTo(2f, 11f); lineTo(2f, 13f)
                moveTo(18f, 9f); lineTo(18f, 15f); moveTo(20f, 10f); lineTo(20f, 14f); moveTo(22f, 11f); lineTo(22f, 13f)
                moveTo(6f, 12f); lineTo(18f, 12f)
            }
        }.build()
    }

    // ─── HOME ───────────────────────────────────────────────

    /** home — house */
    val Home: ImageVector by lazy {
        ImageVector.Builder("Home", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3f, 10f); lineTo(12f, 3f); lineTo(21f, 10f)
                moveTo(5f, 10f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 10f)
                moveTo(10f, 20f); lineTo(10f, 14f); lineTo(14f, 14f); lineTo(14f, 20f)
            }
        }.build()
    }

    // ─── MEDICAL ────────────────────────────────────────────

    /** doctor — stethoscope */
    val Doctor: ImageVector by lazy {
        ImageVector.Builder("Doctor", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6f, 3f); lineTo(6f, 8f); curveTo(6f, 12f, 9f, 14f, 12f, 14f)
                moveTo(18f, 3f); lineTo(18f, 8f); curveTo(18f, 12f, 15f, 14f, 12f, 14f)
                moveTo(12f, 14f); lineTo(12f, 17f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(14f, 19f); arcTo(2f, 2f, 0f, true, true, 10f, 19f); arcTo(2f, 2f, 0f, true, true, 14f, 19f)
            }
        }.build()
    }

    /** hospital — building with cross */
    val Hospital: ImageVector by lazy {
        ImageVector.Builder("Hospital", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 21f); lineTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 21f); close()
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 8f); lineTo(12f, 14f); moveTo(9f, 11f); lineTo(15f, 11f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(9f, 21f); lineTo(9f, 17f); lineTo(15f, 17f); lineTo(15f, 21f)
            }
        }.build()
    }

    // ─── OTHER ──────────────────────────────────────────────

    /** church — building with cross on top */
    val Church: ImageVector by lazy {
        ImageVector.Builder("Church", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 2f); lineTo(12f, 6f); moveTo(10f, 4f); lineTo(14f, 4f)
                moveTo(12f, 6f); lineTo(6f, 12f); lineTo(6f, 21f); lineTo(18f, 21f); lineTo(18f, 12f); close()
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(10f, 21f); lineTo(10f, 17f); curveTo(10f, 16f, 11f, 15f, 12f, 15f); curveTo(13f, 15f, 14f, 16f, 14f, 17f); lineTo(14f, 21f)
            }
        }.build()
    }

    /** school — graduation cap */
    val School: ImageVector by lazy {
        ImageVector.Builder("School", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 10f); lineTo(12f, 5f); lineTo(22f, 10f); lineTo(12f, 15f); close()
                moveTo(6f, 12f); lineTo(6f, 17f); curveTo(6f, 17f, 9f, 20f, 12f, 20f); curveTo(15f, 20f, 18f, 17f, 18f, 17f); lineTo(18f, 12f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(22f, 10f); lineTo(22f, 16f)
            }
        }.build()
    }

    /** shop — shopping bag */
    val Shop: ImageVector by lazy {
        ImageVector.Builder("Shop", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5f, 8f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 8f); close()
                moveTo(8f, 8f); lineTo(8f, 6f); curveTo(8f, 4f, 10f, 2f, 12f, 2f); curveTo(14f, 2f, 16f, 4f, 16f, 6f); lineTo(16f, 8f)
            }
        }.build()
    }

    /** supermarket — shopping cart */
    val Supermarket: ImageVector by lazy {
        ImageVector.Builder("Supermarket", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 3f); lineTo(5f, 3f); lineTo(8f, 16f); lineTo(19f, 16f); lineTo(21f, 7f); lineTo(6f, 7f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(10f, 20f); arcTo(1f, 1f, 0f, true, true, 8f, 20f); arcTo(1f, 1f, 0f, true, true, 10f, 20f)
                moveTo(19f, 20f); arcTo(1f, 1f, 0f, true, true, 17f, 20f); arcTo(1f, 1f, 0f, true, true, 19f, 20f)
            }
        }.build()
    }

    // ─── OUTDOORS ───────────────────────────────────────────

    /** beach — sun + wave */
    val Beach: ImageVector by lazy {
        ImageVector.Builder("Beach", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(17f, 8f); arcTo(3f, 3f, 0f, true, true, 11f, 8f); arcTo(3f, 3f, 0f, true, true, 17f, 8f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 18f); curveTo(4f, 16f, 6f, 16f, 8f, 18f); curveTo(10f, 20f, 12f, 20f, 14f, 18f)
                curveTo(16f, 16f, 18f, 16f, 20f, 18f); curveTo(21f, 19f, 22f, 19f, 22f, 18f)
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(5f, 14f); lineTo(19f, 14f)
            }
        }.build()
    }

    /** outdoors — tree */
    val Outdoors: ImageVector by lazy {
        ImageVector.Builder("Outdoors", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3f); lineTo(5f, 13f); lineTo(8f, 13f); lineTo(4f, 19f); lineTo(20f, 19f); lineTo(16f, 13f); lineTo(19f, 13f); close()
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 19f); lineTo(12f, 22f)
            }
        }.build()
    }

    /** park — bench */
    val Park: ImageVector by lazy {
        ImageVector.Builder("Park", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 12f); lineTo(20f, 12f)
                moveTo(6f, 12f); lineTo(4f, 20f); moveTo(18f, 12f); lineTo(20f, 20f)
                moveTo(4f, 8f); lineTo(4f, 12f); moveTo(20f, 8f); lineTo(20f, 12f)
                moveTo(8f, 12f); lineTo(8f, 16f); moveTo(16f, 12f); lineTo(16f, 16f)
                moveTo(8f, 16f); lineTo(16f, 16f)
            }
        }.build()
    }

    // ─── SOCIAL ─────────────────────────────────────────────

    /** bar — cocktail glass */
    val Bar: ImageVector by lazy {
        ImageVector.Builder("Bar", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 4f); lineTo(20f, 4f); lineTo(12f, 14f); close()
                moveTo(12f, 14f); lineTo(12f, 20f)
                moveTo(8f, 20f); lineTo(16f, 20f)
            }
        }.build()
    }

    /** cinema — film clapboard */
    val Cinema: ImageVector by lazy {
        ImageVector.Builder("Cinema", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 8f); lineTo(20f, 8f); lineTo(20f, 20f); lineTo(4f, 20f); close()
                moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 8f); lineTo(4f, 8f); close()
            }
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(8f, 4f); lineTo(10f, 8f)
                moveTo(14f, 4f); lineTo(16f, 8f)
            }
        }.build()
    }

    /** restaurant — fork + knife */
    val Restaurant: ImageVector by lazy {
        ImageVector.Builder("Restaurant", W.dp, H.dp, W, H).apply {
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

    // ─── TRANSPORT ──────────────────────────────────────────

    /** bus */
    val Bus: ImageVector by lazy {
        ImageVector.Builder("Bus", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5f, 4f); lineTo(19f, 4f); curveTo(20f, 4f, 21f, 5f, 21f, 6f); lineTo(21f, 17f); lineTo(3f, 17f); lineTo(3f, 6f); curveTo(3f, 5f, 4f, 4f, 5f, 4f)
                moveTo(3f, 12f); lineTo(21f, 12f)
                moveTo(5f, 17f); lineTo(5f, 20f); moveTo(19f, 17f); lineTo(19f, 20f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(7f, 15f); lineTo(7.1f, 15f); moveTo(17f, 15f); lineTo(17.1f, 15f)
            }
        }.build()
    }

    /** car */
    val Car: ImageVector by lazy {
        ImageVector.Builder("Car", W.dp, H.dp, W, H).apply {
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

    /** plane */
    val Plane: ImageVector by lazy {
        ImageVector.Builder("Plane", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2f, 14f); lineTo(10f, 11f); lineTo(10f, 4f)
                curveTo(10f, 3f, 11f, 2f, 12f, 2f); curveTo(13f, 2f, 14f, 3f, 14f, 4f)
                lineTo(14f, 11f); lineTo(22f, 14f); lineTo(22f, 16f); lineTo(14f, 14f)
                lineTo(14f, 18f); lineTo(17f, 20f); lineTo(17f, 21f); lineTo(12f, 20f)
                lineTo(7f, 21f); lineTo(7f, 20f); lineTo(10f, 18f); lineTo(10f, 14f)
                lineTo(2f, 16f); close()
            }
        }.build()
    }

    /** train */
    val Train: ImageVector by lazy {
        ImageVector.Builder("Train", W.dp, H.dp, W, H).apply {
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

    // ─── WORK ───────────────────────────────────────────────

    /** office — desk with monitor */
    val Office: ImageVector by lazy {
        ImageVector.Builder("Office", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6f, 4f); lineTo(18f, 4f); lineTo(18f, 14f); lineTo(6f, 14f); close()
                moveTo(12f, 14f); lineTo(12f, 17f)
                moveTo(8f, 17f); lineTo(16f, 17f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(3f, 20f); lineTo(21f, 20f)
            }
        }.build()
    }

    /** work — briefcase */
    val Work: ImageVector by lazy {
        ImageVector.Builder("Work", W.dp, H.dp, W, H).apply {
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

    /** other — map pin */
    val Other: ImageVector by lazy {
        ImageVector.Builder("LocationOther", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 22f); curveTo(12f, 22f, 19f, 16f, 19f, 10f); curveTo(19f, 6f, 16f, 3f, 12f, 3f)
                curveTo(8f, 3f, 5f, 6f, 5f, 10f); curveTo(5f, 16f, 12f, 22f, 12f, 22f)
            }
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(14f, 10f); arcTo(2f, 2f, 0f, true, true, 10f, 10f); arcTo(2f, 2f, 0f, true, true, 14f, 10f)
            }
        }.build()
    }

    // ─── LOOKUP ────────────────────────────────────────────────

    data class LocationPickerIcon(val key: String, val label: String, val icon: ImageVector)

    val ALL_ICONS: List<LocationPickerIcon> by lazy { listOf(
        LocationPickerIcon("gym", "Gym", Gym),
        LocationPickerIcon("home", "Home", Home),
        LocationPickerIcon("doctor", "Doctor", Doctor),
        LocationPickerIcon("hospital", "Hospital", Hospital),
        LocationPickerIcon("church", "Church", Church),
        LocationPickerIcon("school", "School", School),
        LocationPickerIcon("shop", "Shop", Shop),
        LocationPickerIcon("supermarket", "Supermarket", Supermarket),
        LocationPickerIcon("beach", "Beach", Beach),
        LocationPickerIcon("outdoors", "Outdoors", Outdoors),
        LocationPickerIcon("park", "Park", Park),
        LocationPickerIcon("bar", "Bar / Pub", Bar),
        LocationPickerIcon("cinema", "Cinema", Cinema),
        LocationPickerIcon("restaurant", "Restaurant", Restaurant),
        LocationPickerIcon("bus", "Bus", Bus),
        LocationPickerIcon("car", "Car", Car),
        LocationPickerIcon("plane", "Plane", Plane),
        LocationPickerIcon("train", "Train", Train),
        LocationPickerIcon("office", "Office", Office),
        LocationPickerIcon("work", "Work", Work),
        LocationPickerIcon("other", "Other", Other),
    ) }

    fun forKey(key: String?): ImageVector? = ALL_ICONS.find { it.key == key }?.icon
}
