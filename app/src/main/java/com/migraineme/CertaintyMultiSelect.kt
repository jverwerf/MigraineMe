package com.migraineme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

data class CertaintyItem(
    val key: String,
    val label: String,
    val description: String? = null,
)

/**
 * Chip label for each certainty level. The questions ask how OFTEN something
 * brings a migraine on, and Certainty is a frequency enum, so the chips read as
 * frequency: Never / Rarely / Sometimes / Often / Every time. They used to read
 * None / Low / Mild / High — severity words over a frequency scale, and one word
 * short, which left RARELY with no chip to render.
 *
 * Kept in step with VertigoMe / MeSeries, which use the same five labels.
 */
private val CERTAINTY_CHIP_LABELS: Map<DeterministicMapper.Certainty, String> = mapOf(
    DeterministicMapper.Certainty.NO to "Never",
    DeterministicMapper.Certainty.RARELY to "Rarely",
    DeterministicMapper.Certainty.SOMETIMES to "Sometimes",
    DeterministicMapper.Certainty.OFTEN to "Often",
    DeterministicMapper.Certainty.EVERY_TIME to "Every time",
)

/**
 * Every level, weakest first. Derived from DeterministicMapper.CERTAINTY_SCALE
 * rather than hand-written, so a level cannot exist in the enum with no chip to
 * show it as selected.
 */
private val SINGLE_CERTAINTY_OPTIONS: List<Pair<DeterministicMapper.Certainty, String>> =
    DeterministicMapper.CERTAINTY_SCALE.map { it to (CERTAINTY_CHIP_LABELS[it] ?: it.name) }

/**
 * Same, minus "Never". In a multi-select the tick box is the never: a row is on
 * the list or it is not, and the chips under it only say how often. A ticked row
 * reading "Never" would contradict itself.
 */
private val MULTI_CERTAINTY_OPTIONS: List<Pair<DeterministicMapper.Certainty, String>> =
    SINGLE_CERTAINTY_OPTIONS.filterNot { it.first == DeterministicMapper.Certainty.NO }

/** Selected-chip fill. Every level needs one, or a selected chip reads as unselected. */
private fun certaintyChipColor(c: DeterministicMapper.Certainty): Color = when (c) {
    DeterministicMapper.Certainty.EVERY_TIME -> Color(0xFFE53935)
    DeterministicMapper.Certainty.OFTEN      -> Color(0xFFFFA726)
    DeterministicMapper.Certainty.SOMETIMES  -> Color(0xFFFFEE58)
    DeterministicMapper.Certainty.RARELY     -> Color(0xFF78909C)
    DeterministicMapper.Certainty.NO         -> Color(0xFF455A64)
}

/** Yellow needs dark text; the rest are dark enough for white. */
private fun certaintyChipTextColor(c: DeterministicMapper.Certainty): Color =
    if (c == DeterministicMapper.Certainty.SOMETIMES) Color.Black else Color.White

@Composable
fun CertaintyMultiSelect(
    items: List<CertaintyItem>,
    selections: Map<String, DeterministicMapper.Certainty>,
    onSelectionChanged: (Map<String, DeterministicMapper.Certainty>) -> Unit,
    accentColor: Color = AppTheme.AccentPink,
    showNoneOption: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (item in items) {
            val isSelected = item.key in selections
            val certainty = selections[item.key]

            val bgColor = if (isSelected) accentColor.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f)
            val borderColor = if (isSelected) accentColor.copy(alpha = 0.5f) else Color.Transparent

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(bgColor)
                    .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                    .clickable {
                        val updated = selections.toMutableMap()
                        if (isSelected) {
                            updated.remove(item.key)
                        } else {
                            updated[item.key] = DeterministicMapper.Certainty.OFTEN
                        }
                        onSelectionChanged(updated)
                    }
                    .padding(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) accentColor else Color.White.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }

                    Column(Modifier.weight(1f)) {
                        Text(t(item.label),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (item.description != null) {
                            Text(t(item.description), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                AnimatedVisibility(
                    visible = isSelected,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    CertaintySelector(
                        selected = certainty ?: DeterministicMapper.Certainty.OFTEN,
                        onSelected = { newCert ->
                            val updated = selections.toMutableMap()
                            updated[item.key] = newCert
                            onSelectionChanged(updated)
                        },
                        accentColor = accentColor,
                        modifier = Modifier.padding(top = 8.dp, start = 32.dp),
                    )
                }
            }
        }

        if (showNoneOption) {
            val noneSelected = selections.isEmpty()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (noneSelected) Color.White.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.03f))
                    .clickable { onSelectionChanged(emptyMap()) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (noneSelected) AppTheme.SubtleTextColor else Color.White.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (noneSelected) {
                        Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
                Text(t("None of these"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CertaintySelector(
    selected: DeterministicMapper.Certainty,
    onSelected: (DeterministicMapper.Certainty) -> Unit,
    accentColor: Color = AppTheme.AccentPink,
    modifier: Modifier = Modifier,
) {
    val options = MULTI_CERTAINTY_OPTIONS

    // FlowRow, not Row: the scale gained a chip and the labels are words rather
    // than the old three-letter severities, so on a narrow screen (or in German)
    // the last one wraps instead of being clipped off the edge.
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((certainty, label) in options) {
            val isSelected = selected == certainty
            val chipColor =
                if (isSelected) certaintyChipColor(certainty) else Color.White.copy(alpha = 0.08f)
            val textColor =
                if (isSelected) certaintyChipTextColor(certainty) else AppTheme.SubtleTextColor

            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(chipColor)
                    .clickable { onSelected(certainty) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(t(label),
                    color = textColor,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
fun SingleCertaintySelect(
    selected: DeterministicMapper.Certainty?,
    onSelected: (DeterministicMapper.Certainty) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = SINGLE_CERTAINTY_OPTIONS

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((certainty, label) in options) {
            val isSelected = selected == certainty
            val chipColor =
                if (isSelected) certaintyChipColor(certainty) else Color.White.copy(alpha = 0.08f)
            val textColor =
                if (isSelected) certaintyChipTextColor(certainty) else AppTheme.SubtleTextColor

            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(chipColor)
                    .clickable { onSelected(certainty) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(t(label),
                    color = textColor,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    // Five equal-width chips now, and "Every time" (or "Jedes Mal")
                    // does not fit one line at a fifth of the row. Wrap, don't clip.
                    maxLines = 2,
                )
            }
        }
    }
}
