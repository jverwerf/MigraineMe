package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One stacked bar per calendar month: migraine days, pain days, free days.
 *
 * Bars are the full month's height so a part-month cannot read as a good
 * month, and a month with no data shows an empty bar rather than vanishing.
 * Static by design, no animation anywhere in this app's health surfaces.
 *
 * Spec: docs/day-classification-spec.md
 */
object DayMixColors {
    val Migraine = Color(0xFFFF7B8F)
    val Pain = Color(0xFFFFC46B)
    val Free = Color(0xFF6FD6B0)
    val Empty = Color.White.copy(alpha = 0.05f)
}

/** The (i) text. Explains the logging, not the chart, because the number is
 *  only as honest as what people put in. */
fun dayMixInfoText(painLabel: String): String = tSync(
    "Every day falls into one of three: a migraine day if you logged an attack, " +
        "a pain day if you logged %1\$s without an attack, and a free day if neither. " +
        "%2\$s is in your warning signs list, for the days you hurt but it never became " +
        "a full attack.\n\nA multi-day attack counts every day it covers. Days you " +
        "didn't log anything count as free, so the number is only as honest as your logging.",
    painLabel, painLabel
)

@Composable
fun DayMixMonthlyChart(
    months: List<InsightsViewModel.MonthDayMix>,
    modifier: Modifier = Modifier,
    painLabel: String = t("Background pain"),
    title: String = t("Your months"),
) {
    var showInfo by remember { mutableStateOf(false) }

    Box(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title, color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        t("Free days, pain days and migraine days"),
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                IconButton(onClick = { showInfo = true }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Outlined.Info, contentDescription = t("About these months"),
                        tint = AppTheme.SubtleTextColor, modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (months.isEmpty()) {
                Text(
                    t("Nothing logged yet."), color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    months.forEach { m -> MonthColumn(m, Modifier.weight(1f)) }
                }
                DayMixLegend(months)
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(t("Got it"), color = AppTheme.AccentPurple)
                }
            },
            title = {
                Text(
                    t("How your days are counted"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            },
            text = {
                Text(
                    dayMixInfoText(painLabel), color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            containerColor = Color(0xFF2A0C3C),
        )
    }
}

@Composable
private fun MonthColumn(m: InsightsViewModel.MonthDayMix, modifier: Modifier = Modifier) {
    // Every bar is the whole month tall, so February and a part-month read
    // honestly against a full one.
    val span = m.month.lengthOfMonth().toFloat().coerceAtLeast(1f)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${m.freeDays}", color = DayMixColors.Free,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.height(3.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .height(112.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(DayMixColors.Empty),
            verticalArrangement = Arrangement.Bottom
        ) {
            // Unlogged tail of a part-month sits on top as empty space.
            Segment(weight = (span - m.totalDays) / span, color = Color.Transparent)
            Segment(weight = m.freeDays / span, color = DayMixColors.Free.copy(alpha = 0.55f))
            Segment(weight = m.painDays / span, color = DayMixColors.Pain.copy(alpha = 0.60f))
            Segment(weight = m.migraineDays / span, color = DayMixColors.Migraine.copy(alpha = 0.60f))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            monthShortLabel(m.month),
            color = AppTheme.SubtleTextColor.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Segment(weight: Float, color: Color) {
    if (weight <= 0f) return
    Box(
        Modifier
            .fillMaxWidth()
            .weight(weight)
            .background(color)
    )
}

@Composable
private fun DayMixLegend(months: List<InsightsViewModel.MonthDayMix>) {
    val last = months.last()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendDot(DayMixColors.Free, t("%s free", last.freeDays))
        LegendDot(DayMixColors.Pain, t("%s pain", last.painDays))
        LegendDot(DayMixColors.Migraine, t("%s migraine", last.migraineDays))
    }
    val prev = months.getOrNull(months.size - 2)
    val direction = when {
        prev == null -> null
        last.isPartial -> t("%s so far this month", last.freeDays)
        last.freeDays > prev.freeDays -> t("up from %s free last month", prev.freeDays)
        last.freeDays < prev.freeDays -> t("down from %s free last month", prev.freeDays)
        else -> t("same as last month")
    }
    if (direction != null) {
        Text(
            direction, color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
    }
}

private fun monthShortLabel(ym: java.time.YearMonth): String = runCatching {
    ym.format(java.time.format.DateTimeFormatter.ofPattern("LLL", appLocale()))
}.getOrDefault(ym.monthValue.toString())
