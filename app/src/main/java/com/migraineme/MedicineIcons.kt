package com.migraineme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Medicine icons for MigraineMe — one per category.
 * Style: 24dp viewport, 2dp stroke, rounded caps/joins, no fill.
 * Categories: Analgesic, Anti-Nausea, CGRP, Preventive, Supplement, Triptan, Other
 */
object MedicineIcons {

    private val stroke = SolidColor(Color.White)
    private const val W = 24f
    private const val H = 24f

    /** analgesic — pill tablet with cross */
    val Analgesic: ImageVector by lazy {
        ImageVector.Builder("Analgesic", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Rounded pill shape
                moveTo(7f, 4f); lineTo(17f, 4f)
                curveTo(19f, 4f, 20f, 5f, 20f, 7f)
                lineTo(20f, 17f)
                curveTo(20f, 19f, 19f, 20f, 17f, 20f)
                lineTo(7f, 20f)
                curveTo(5f, 20f, 4f, 19f, 4f, 17f)
                lineTo(4f, 7f)
                curveTo(4f, 5f, 5f, 4f, 7f, 4f)
            }
            // Cross
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 9f); lineTo(12f, 15f)
                moveTo(9f, 12f); lineTo(15f, 12f)
            }
        }.build()
    }

    /** anti_nausea — stomach with calm wave */
    val AntiNausea: ImageVector by lazy {
        ImageVector.Builder("AntiNausea", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Stomach shape
                moveTo(8f, 4f)
                curveTo(12f, 4f, 16f, 4f, 17f, 7f)
                curveTo(18f, 10f, 18f, 14f, 16f, 16f)
                curveTo(14f, 18f, 11f, 18f, 9f, 20f)
                moveTo(8f, 4f)
                curveTo(6f, 4f, 5f, 6f, 5f, 8f)
                curveTo(5f, 10f, 6f, 11f, 8f, 11f)
            }
            // Calm wave inside
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(9f, 13f)
                curveTo(10f, 11.5f, 11f, 14.5f, 12f, 13f)
                curveTo(13f, 11.5f, 14f, 14.5f, 15f, 13f)
            }
        }.build()
    }

    /** cgrp — antibody / Y-shape molecule */
    val Cgrp: ImageVector by lazy {
        ImageVector.Builder("Cgrp", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Y-shape antibody
                moveTo(12f, 22f); lineTo(12f, 12f)
                moveTo(12f, 12f); lineTo(6f, 4f)
                moveTo(12f, 12f); lineTo(18f, 4f)
            }
            // Binding tips
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 3f); lineTo(8f, 5f)
                moveTo(20f, 3f); lineTo(16f, 5f)
            }
            // Base dot
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 22f); lineTo(12.1f, 22f)
            }
        }.build()
    }

    /** preventive — shield with check */
    val Preventive: ImageVector by lazy {
        ImageVector.Builder("Preventive", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Shield
                moveTo(12f, 2f)
                lineTo(20f, 6f)
                lineTo(20f, 12f)
                curveTo(20f, 17f, 16f, 20f, 12f, 22f)
                curveTo(8f, 20f, 4f, 17f, 4f, 12f)
                lineTo(4f, 6f)
                close()
            }
            // Checkmark
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 9f)
            }
        }.build()
    }

    /** supplement — leaf / natural */
    val Supplement: ImageVector by lazy {
        ImageVector.Builder("Supplement", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Leaf shape
                moveTo(12f, 22f)
                curveTo(12f, 22f, 4f, 16f, 4f, 10f)
                curveTo(4f, 4f, 12f, 2f, 12f, 2f)
                curveTo(12f, 2f, 20f, 4f, 20f, 10f)
                curveTo(20f, 16f, 12f, 22f, 12f, 22f)
            }
            // Center vein
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 6f); lineTo(12f, 18f)
            }
            // Side veins
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(8f, 10f); lineTo(12f, 12f)
                moveTo(16f, 10f); lineTo(12f, 12f)
            }
        }.build()
    }

    /** triptan — lightning bolt (fast acting) */
    val Triptan: ImageVector by lazy {
        ImageVector.Builder("Triptan", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
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

    /** other — generic capsule */
    val Other: ImageVector by lazy {
        ImageVector.Builder("Other", W.dp, H.dp, W, H).apply {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                // Capsule outline
                moveTo(9f, 2f)
                curveTo(6f, 2f, 4f, 4f, 4f, 7f)
                lineTo(4f, 17f)
                curveTo(4f, 20f, 6f, 22f, 9f, 22f)
                lineTo(15f, 22f)
                curveTo(18f, 22f, 20f, 20f, 20f, 17f)
                lineTo(20f, 7f)
                curveTo(20f, 4f, 18f, 2f, 15f, 2f)
                close()
            }
            // Divider line
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(4f, 12f); lineTo(20f, 12f)
            }
        }.build()
    }

    // ─── LOOKUP ────────────────────────────────────────────────

    data class MedicinePickerIcon(val key: String, val label: String, val icon: ImageVector)

    val ALL_ICONS: List<MedicinePickerIcon> by lazy { listOf(
        MedicinePickerIcon("Analgesic", "Analgesic", Analgesic),
        MedicinePickerIcon("Anti-Nausea", "Anti-Nausea", AntiNausea),
        MedicinePickerIcon("CGRP", "CGRP", Cgrp),
        MedicinePickerIcon("Preventive", "Preventive", Preventive),
        MedicinePickerIcon("Supplement", "Supplement", Supplement),
        MedicinePickerIcon("Triptan", "Triptan", Triptan),
        MedicinePickerIcon("Other", "Other", Other),
    ) }

    /** Look up icon by category name */
    fun forKey(key: String?): ImageVector? = ALL_ICONS.find { it.key == key }?.icon
}
