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

    /** combination — two overlapping pills with connector dot */
    val Combination: ImageVector by lazy {
        ImageVector.Builder("Combination", W.dp, H.dp, W, H).apply {
            // Pill 1 (horizontal)
            path(stroke = stroke, strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5f, 9f); lineTo(11f, 9f)
                curveTo(12.7f, 9f, 14f, 10.3f, 14f, 12f)
                curveTo(14f, 13.7f, 12.7f, 15f, 11f, 15f)
                lineTo(5f, 15f)
                curveTo(3.3f, 15f, 2f, 13.7f, 2f, 12f)
                curveTo(2f, 10.3f, 3.3f, 9f, 5f, 9f)
            }
            path(stroke = stroke, strokeLineWidth = 1f, strokeLineCap = StrokeCap.Round, strokeAlpha = 0.5f) {
                moveTo(8f, 9f); lineTo(8f, 15f)
            }
            // Pill 2 (diagonal)
            path(stroke = stroke, strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(14.5f, 5.5f)
                curveTo(15.7f, 4.3f, 17.6f, 4.3f, 18.8f, 5.5f)
                curveTo(20f, 6.7f, 20f, 8.6f, 18.8f, 9.8f)
                lineTo(13.2f, 15.4f)
                curveTo(12f, 16.6f, 10.1f, 16.6f, 8.9f, 15.4f)
                curveTo(7.7f, 14.2f, 7.7f, 12.3f, 8.9f, 11.1f)
                close()
            }
            path(stroke = stroke, strokeLineWidth = 1f, strokeLineCap = StrokeCap.Round, strokeAlpha = 0.5f) {
                moveTo(16.6f, 7.4f); lineTo(11f, 13f)
            }
        }.build()
    }

    /** ergotamine — hexagonal alkaloid ring structure */
    val Ergotamine: ImageVector by lazy {
        ImageVector.Builder("Ergotamine", W.dp, H.dp, W, H).apply {
            // Outer hexagon
            path(stroke = stroke, strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3f)
                lineTo(18.5f, 7f)
                lineTo(18.5f, 15f)
                lineTo(12f, 19f)
                lineTo(5.5f, 15f)
                lineTo(5.5f, 7f)
                close()
            }
            // Inner hexagon
            path(stroke = stroke, strokeLineWidth = 0.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, strokeAlpha = 0.5f) {
                moveTo(12f, 7f)
                lineTo(15.5f, 9f)
                lineTo(15.5f, 14f)
                lineTo(12f, 16f)
                lineTo(8.5f, 14f)
                lineTo(8.5f, 9f)
                close()
            }
            // Axon tail
            path(stroke = stroke, strokeLineWidth = 1.3f, strokeLineCap = StrokeCap.Round, strokeAlpha = 0.7f) {
                moveTo(12f, 19f); lineTo(12f, 21.5f)
            }
        }.build()
    }

    /** ditan — serotonin receptor neuron with signal dot */
    val Ditan: ImageVector by lazy {
        ImageVector.Builder("Ditan", W.dp, H.dp, W, H).apply {
            // Cell body
            path(stroke = stroke, strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round) {
                moveTo(16f, 12f)
                arcTo(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 8f, 12f)
                arcTo(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 16f, 12f)
            }
            // Dendrites
            path(stroke = stroke, strokeLineWidth = 1.3f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 8f); lineTo(12f, 3f)
                moveTo(15.5f, 9.5f); lineTo(19f, 6f)
                moveTo(16f, 12f); lineTo(21f, 12f)
                moveTo(8.5f, 9.5f); lineTo(5f, 6f)
                moveTo(8f, 12f); lineTo(3f, 12f)
            }
            // Axon + signal dot
            path(stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 16f); lineTo(12f, 20f)
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(13.2f, 21f)
                arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 10.8f, 21f)
                arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 13.2f, 21f)
            }
        }.build()
    }

    // ─── LOOKUP ────────────────────────────────────────────────

    data class MedicinePickerIcon(val key: String, val label: String, val icon: ImageVector)

    val ALL_ICONS: List<MedicinePickerIcon> by lazy { listOf(
        MedicinePickerIcon("Analgesic", "Analgesic", Analgesic),
        MedicinePickerIcon("Anti-Nausea", "Anti-Nausea", AntiNausea),
        MedicinePickerIcon("CGRP", "CGRP", Cgrp),
        MedicinePickerIcon("Combination", "Combination", Combination),
        MedicinePickerIcon("Ditan", "Ditan", Ditan),
        MedicinePickerIcon("Ergotamine", "Ergotamine", Ergotamine),
        MedicinePickerIcon("Preventive", "Preventive", Preventive),
        MedicinePickerIcon("Supplement", "Supplement", Supplement),
        MedicinePickerIcon("Triptan", "Triptan", Triptan),
        MedicinePickerIcon("Other", "Other", Other),
    ) }

    /** Look up icon by category name */
    fun forKey(key: String?): ImageVector? = ALL_ICONS.find { it.key == key }?.icon
}
