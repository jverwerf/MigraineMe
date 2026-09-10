package com.migraineme

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shared furniture for the history-graph cards (Weather, Sleep, Physical,
 * Mental, Nutrition, Risk).
 *
 * The cards used to stack four legend sentences above the plot, park the y
 * values in a 50dp gutter on the left, and end in a wall of grey filter chips.
 * These pieces replace that with one reading at the top, one quiet key line
 * under the plot, and a grouped checklist that carries each metric's own value.
 *
 * Everything here is static by design: motion and flashing are symptom
 * triggers for this app's users, so no piece animates.
 */

private val KeyTextColor = Color.White.copy(alpha = 0.58f)
private val MigraineRed = Color(0xFFE57373)
private val ForecastBlue = Color(0xFF4FC3F7)
private val NormalisedAmber = Color(0xFFFFB74D)

/** One row of a [MetricChecklist]: what to draw, and what the metric reads today. */
data class GraphMetricRow(
    val key: String,
    val label: String,
    /** The metric's own average over the window, already formatted with its
     *  unit, or null when nothing is recorded — rendered as "-". */
    val value: String?,
    val color: Color
)

/**
 * Card header: title and subtitle on the left, the reading on the right.
 *
 * With one metric selected the reading is that metric's average in its own
 * colour and unit. With several, the plot is normalised, so there is no honest
 * single number and the reading states the scale instead.
 */
@Composable
fun GraphCardHeader(
    title: String,
    subtitle: String,
    readout: String,
    readoutUnit: String,
    readoutColor: Color,
    readoutCaption: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                subtitle,
                color = AppTheme.SubtleTextColor.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    readout,
                    color = readoutColor,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                )
                if (readoutUnit.isNotEmpty()) {
                    Text(
                        readoutUnit,
                        color = readoutColor,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(start = 1.dp, bottom = 2.dp)
                    )
                }
            }
            Text(
                readoutCaption,
                color = AppTheme.SubtleTextColor.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/** The amber colour the normalised reading uses, so callers stay consistent. */
val GraphNormalisedColor: Color get() = NormalisedAmber

/**
 * One wrapping row of "● Metric  lo–hi" entries, each in its metric colour.
 * Shown when several metrics share a normalised plot, where the per-metric
 * range is the only way to read a line back to real units.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetricRangeKeys(entries: List<Triple<String, String, Color>>) {
    if (entries.isEmpty()) return
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        entries.forEach { (label, range, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawCircle(color) }
                Spacer(Modifier.width(5.dp))
                Text(label, color = color, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(4.dp))
                Text(
                    range,
                    color = color.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}

/**
 * The key that used to be three coloured sentences above the plot: dashed
 * swatch for the average, red for migraine days, blue for the forecast zone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GraphKeyRow(
    averageLabel: String,
    averageColor: Color,
    showMigraineDays: Boolean,
    showForecast: Boolean
) {
    Spacer(Modifier.height(9.dp))
    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
    Spacer(Modifier.height(9.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(width = 14.dp, height = 8.dp)) {
                val dash = 4.dp.toPx()
                val gap = 3.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    drawLine(
                        averageColor,
                        Offset(x, size.height / 2),
                        Offset((x + dash).coerceAtMost(size.width), size.height / 2),
                        strokeWidth = 2.dp.toPx()
                    )
                    x += dash + gap
                }
            }
            Spacer(Modifier.width(5.dp))
            Text(averageLabel, color = KeyTextColor, style = MaterialTheme.typography.labelSmall)
        }
        if (showMigraineDays) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawRect(MigraineRed.copy(alpha = 0.55f)) }
                Spacer(Modifier.width(5.dp))
                Text(t("migraine days"), color = KeyTextColor, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (showForecast) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawRect(ForecastBlue.copy(alpha = 0.35f)) }
                Spacer(Modifier.width(5.dp))
                Text(t("forecast"), color = KeyTextColor, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * The metric selector: grouped rows, each with a checkbox, the metric name and
 * the metric's own value for the window. Replaces the chip wall — the whole
 * list stays on the card, in the order the group declares it.
 */
@Composable
fun MetricChecklist(
    groups: List<Pair<String, List<GraphMetricRow>>>,
    selected: Set<String>,
    onToggle: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Text(t("Metrics"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
        Text(
            t("%s selected", selected.size),
            color = AppTheme.AccentPurple,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
        )
    }
    Spacer(Modifier.height(6.dp))
    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

    groups.forEach { (group, rows) ->
        if (rows.isEmpty()) return@forEach
        Text(
            t(group).uppercase(),
            color = AppTheme.SubtleTextColor.copy(alpha = 0.68f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 12.dp, bottom = 3.dp)
        )
        rows.forEach { row ->
            val isSelected = row.key in selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(row.key) }
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(
                            if (isSelected) row.color else Color.Transparent,
                            RoundedCornerShape(4.dp)
                        )
                        .border(
                            1.5.dp,
                            if (isSelected) row.color else Color.White.copy(alpha = 0.28f),
                            RoundedCornerShape(4.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color(0xFF25002F),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
                Spacer(Modifier.width(9.dp))
                Text(
                    row.label,
                    color = if (isSelected) row.color else AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    row.value ?: "-",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
        }
    }
}

// ── Plot furniture ───────────────────────────────────────────────────────────
// Each history graph keeps its own value extraction and its own scaling, but
// the drawing below is identical everywhere, so it lives here once.

/** Three faint gridlines, at the same heights as the three y labels. */
fun DrawScope.drawGraphGrid(padding: Float, graphHeight: Float, strokePx: Float) {
    listOf(0f, 0.5f, 1f).forEach { f ->
        val y = padding + graphHeight * f
        drawLine(
            Color.White.copy(alpha = 0.07f),
            Offset(padding, y),
            Offset(size.width - padding, y),
            strokeWidth = strokePx
        )
    }
}

/** The dotted average line, spanning the full plot width. */
fun DrawScope.drawDashedAverage(color: Color, y: Float, padding: Float, dashPx: Float, gapPx: Float, strokePx: Float) {
    var x = padding
    while (x < size.width - padding) {
        drawLine(
            color.copy(alpha = 0.5f),
            Offset(x, y),
            Offset((x + dashPx).coerceAtMost(size.width - padding), y),
            strokeWidth = strokePx
        )
        x += dashPx + gapPx
    }
}

/**
 * A series line: the Migraine Timeline's Catmull-Rom curve, stroked twice —
 * a wide faint pass under a solid one, which keeps a thin curve legible on the
 * dark card.
 */
fun DrawScope.drawSeriesCurve(offsets: List<Offset>, color: Color, widthPx: Float, glowPx: Float) {
    if (offsets.size < 2) return
    val path = smoothPath(offsets)
    drawPath(path, color.copy(alpha = 0.12f), style = Stroke(glowPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, color, style = Stroke(widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Soft gradient under a single series, for depth without another colour. */
fun DrawScope.drawSeriesFill(offsets: List<Offset>, color: Color, padding: Float, graphHeight: Float) {
    if (offsets.size < 2) return
    val fill = smoothPath(offsets)
    fill.lineTo(offsets.last().x, padding + graphHeight)
    fill.lineTo(offsets.first().x, padding + graphHeight)
    fill.close()
    drawPath(
        fill,
        Brush.verticalGradient(
            0f to color.copy(alpha = 0.26f),
            1f to color.copy(alpha = 0f),
            startY = padding,
            endY = padding + graphHeight
        )
    )
}
