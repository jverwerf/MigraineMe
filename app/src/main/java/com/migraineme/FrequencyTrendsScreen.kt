// FILE: FrequencyTrendsScreen.kt
package com.migraineme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.*
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Composable
fun FrequencyTrendsScreen(
    onBack: () -> Unit = {},
    vm: InsightsViewModel = viewModel(),
) {
    val scrollState = rememberScrollState()
    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
            FrequencyTrendsContent(vm = vm)
        }
    }
}

@Composable
fun FrequencyTrendsContent(vm: InsightsViewModel) {
    Column(Modifier.fillMaxWidth()) {
        FrequencyHeroSection(vm = vm)
        FrequencyChartsSection(vm = vm)
    }
}

@Composable
fun FrequencyHeroSection(vm: InsightsViewModel) {
    val migraines by vm.migraines.collectAsState()
    val weeklySummary by vm.weeklySummary.collectAsState()
    val zone = ZoneId.systemDefault()
    val byMonth = remember(migraines) {
        migraines.groupBy { it.start.atZone(zone).toLocalDate().withDayOfMonth(1) }.toSortedMap()
    }
    weeklySummary?.let { ws ->
        HeroCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val perMonth = if (byMonth.isNotEmpty()) migraines.size.toFloat() / byMonth.size else 0f
                StatColumn("${ws.thisWeekCount}", "This Week",
                    sub = "vs ${ws.lastWeekCount} last",
                    color = Color.White)
                StatColumn(String.format("%.1f", perMonth), "Per Month",
                    color = AppTheme.AccentPurple)
                StatColumn("${ws.totalLogged}", "Total",
                    sub = "all time",
                    color = AppTheme.AccentPurple)
            }
        }
    }
}

@Composable
fun FrequencyChartsSection(vm: InsightsViewModel) {
    val migraines by vm.migraines.collectAsState()
    val dayOfWeek by vm.dayOfWeekPattern.collectAsState()
    val zone = ZoneId.systemDefault()

    val byMonth = remember(migraines) {
        migraines.groupBy {
            it.start.atZone(zone).toLocalDate().withDayOfMonth(1)
        }.toSortedMap()
    }

    val byWeek = remember(migraines) {
        val today = LocalDate.now()
        val weekStart = today.minusWeeks(12)
        migraines.filter {
            val d = it.start.atZone(zone).toLocalDate()
            !d.isBefore(weekStart)
        }.groupBy {
            val d = it.start.atZone(zone).toLocalDate()
            d.minusDays(d.dayOfWeek.value.toLong() - 1)
        }.toSortedMap()
    }

    Column(Modifier.fillMaxWidth()) {
        run {
            // ── Day-of-week (moved earlier per spec) ──
            if (dayOfWeek.isNotEmpty()) {
                BaseCard {
                    Text("Day of Week", color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(8.dp))
                    val maxPct = dayOfWeek.maxOf { it.pct }.coerceAtLeast(1f)
                    Row(
                        Modifier.fillMaxWidth().height(120.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        dayOfWeek.sortedBy { it.dayIndex }.forEach { stat ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("${stat.count}", color = Color.White,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                Spacer(Modifier.height(2.dp))
                                val barH = (stat.pct / maxPct * 80f).coerceAtLeast(4f)
                                val barColor = if (stat.pct > maxPct * 0.8f) Color(0xFFE57373)
                                    else AppTheme.AccentPurple
                                Box(
                                    Modifier
                                        .width(24.dp)
                                        .height(barH.dp)
                                        .padding(horizontal = 2.dp)
                                ) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        drawRoundRect(
                                            barColor.copy(alpha = 0.8f),
                                            cornerRadius = CornerRadius(4f),
                                            size = size,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(stat.dayName.take(3), color = AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    val worst = dayOfWeek.maxByOrNull { it.count }
                    if (worst != null && worst.count > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text("Most frequent: ${worst.dayName} (${worst.count} migraines, ${String.format("%.0f", worst.pct)}%)",
                            color = AppTheme.BodyTextColor,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // ── Weekly bar chart (last 12 weeks) ──
            if (byWeek.size >= 3) {
                Spacer(Modifier.height(4.dp))
                BaseCard {
                    Text("Weekly (last 12 weeks)", color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(8.dp))
                    val today = LocalDate.now()
                    val allWeeks = (0 until 12).map {
                        val d = today.minusWeeks(11L - it)
                        d.minusDays(d.dayOfWeek.value.toLong() - 1)
                    }
                    val weekCounts = allWeeks.map { wk -> byWeek[wk]?.size ?: 0 }
                    val maxWk = weekCounts.max().coerceAtLeast(1)
                    val avgWk = weekCounts.average().toFloat()
                    WeeklyBarChart(
                        weeks = allWeeks,
                        counts = weekCounts,
                        maxCount = maxWk,
                        avgCount = avgWk,
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                    )
                }
            }

            // ── Monthly bar chart ──
            if (byMonth.size >= 2) {
                Spacer(Modifier.height(4.dp))
                BaseCard {
                    Text("Monthly", color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(8.dp))

                    val months = byMonth.keys.toList()
                    val counts = months.map { byMonth[it]?.size ?: 0 }
                    val maxCount = counts.max().coerceAtLeast(1)
                    val avgCount = counts.average().toFloat()

                    MonthlyBarChart(
                        months = months,
                        counts = counts,
                        maxCount = maxCount,
                        avgCount = avgCount,
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                    )

                    // Trend indicator
                    if (counts.size >= 4) {
                        val firstHalf = counts.take(counts.size / 2).average()
                        val secondHalf = counts.drop(counts.size / 2).average()
                        val trendText = when {
                            secondHalf < firstHalf * 0.75 -> "↓ Improving — frequency decreasing"
                            secondHalf > firstHalf * 1.25 -> "↑ Worsening — frequency increasing"
                            else -> "→ Stable — consistent frequency"
                        }
                        val trendColor = when {
                            secondHalf < firstHalf * 0.75 -> Color(0xFF81C784)
                            secondHalf > firstHalf * 1.25 -> Color(0xFFE57373)
                            else -> AppTheme.BodyTextColor
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(trendText, color = trendColor,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // ── Seasonal (hemisphere-aware, gated on ≥4 migraines) ──
            if (migraines.size >= 4) {
                val userLocations by vm.userLocations.collectAsState()
                val medianLat: Double? = remember(userLocations) {
                    val lats = userLocations.map { it.latitude }.sorted()
                    if (lats.isEmpty()) null
                    else if (lats.size % 2 == 0) (lats[lats.size / 2 - 1] + lats[lats.size / 2]) / 2.0
                    else lats[lats.size / 2]
                }
                val southern = (medianLat ?: 0.0) < 0
                val labels = if (southern)
                    listOf("Summer", "Autumn", "Winter", "Spring")
                else
                    listOf("Winter", "Spring", "Summer", "Autumn")
                val colors = if (southern)
                    listOf(Color(0xFFFFB74D), Color(0xFFFF8A65), Color(0xFF4FC3F7), Color(0xFF81C784))
                else
                    listOf(Color(0xFF4FC3F7), Color(0xFF81C784), Color(0xFFFFB74D), Color(0xFFFF8A65))

                val counts = remember(migraines) {
                    val c = IntArray(4)
                    migraines.forEach {
                        val month = it.start.atZone(zone).toLocalDate().monthValue
                        // Dec/Jan/Feb=0, Mar-May=1, Jun-Aug=2, Sep-Nov=3 (NH meteorological)
                        c[(month % 12) / 3]++
                    }
                    c.toList()
                }
                val maxCount = counts.max().coerceAtLeast(1)
                val total = counts.sum()

                Spacer(Modifier.height(4.dp))
                BaseCard {
                    Text("Seasonal", color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().height(120.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        counts.forEachIndexed { i, count ->
                            val isMax = count == maxCount && count > 0
                            val barColor = if (isMax) Color(0xFFE57373) else colors[i]
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("$count", color = Color.White,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                Spacer(Modifier.height(2.dp))
                                val barH = (count.toFloat() / maxCount * 80f).coerceAtLeast(4f)
                                Box(
                                    Modifier
                                        .width(28.dp)
                                        .height(barH.dp)
                                        .padding(horizontal = 2.dp)
                                ) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        drawRoundRect(
                                            barColor.copy(alpha = 0.8f),
                                            cornerRadius = CornerRadius(4f),
                                            size = size,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(labels[i], color = AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    val worstIdx = counts.indices.maxByOrNull { counts[it] }
                    if (worstIdx != null && counts[worstIdx] > 0 && total > 0) {
                        val pct = counts[worstIdx].toFloat() / total * 100f
                        Spacer(Modifier.height(8.dp))
                        Text("Most frequent: ${labels[worstIdx]} (${counts[worstIdx]} migraines, ${String.format("%.0f", pct)}%)",
                            color = AppTheme.BodyTextColor,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // ── Severity over time (monthly average) ──
            if (byMonth.size >= 3) {
                val monthsWithSev = byMonth.entries.mapNotNull { (month, migs) ->
                    val sevs = migs.mapNotNull { it.severity }
                    if (sevs.isEmpty()) null else Triple(month, sevs.average().toFloat(), sevs.size)
                }
                if (monthsWithSev.size >= 3) {
                    Spacer(Modifier.height(4.dp))
                    BaseCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.MonitorHeart,
                                contentDescription = null,
                                tint = AppTheme.AccentPink,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Average Severity", color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        }
                        Spacer(Modifier.height(8.dp))

                        SeverityMonthlyBars(data = monthsWithSev)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun StatColumn(value: String, label: String, sub: String? = null, color: Color = Color.White) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Text(label, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.labelSmall)
        if (sub != null) {
            Text(sub, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun MonthlyBarChart(
    months: List<LocalDate>,
    counts: List<Int>,
    maxCount: Int,
    avgCount: Float,
    modifier: Modifier = Modifier,
) {
    val fmt = DateTimeFormatter.ofPattern("MMM")
    val fmtYear = DateTimeFormatter.ofPattern("yy")

    Canvas(modifier) {
        val w = size.width; val h = size.height
        val padL = 32f; val padR = 8f; val padT = 16f; val padB = 32f
        val cw = w - padL - padR; val ch = h - padT - padB

        val barCount = counts.size
        val totalBarW = cw / barCount
        val barW = totalBarW * 0.7f
        val gap = totalBarW * 0.15f

        // Avg dashed line
        val avgY = padT + ch - (avgCount / maxCount) * ch
        drawLine(
            Color.White.copy(alpha = 0.2f),
            start = Offset(padL, avgY),
            end = Offset(w - padR, avgY),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
        )
        // Avg label
        drawContext.canvas.nativeCanvas.drawText(
            "avg ${String.format("%.1f", avgCount)}",
            w - padR + 2f, avgY + 4f,
            android.graphics.Paint().apply {
                color = Color.White.copy(alpha = 0.4f).toArgb()
                textSize = 18f; isAntiAlias = true
            })

        // Y-axis labels
        val yPaint = android.graphics.Paint().apply {
            color = Color.White.copy(alpha = 0.4f).toArgb()
            textSize = 20f; isAntiAlias = true; textAlign = android.graphics.Paint.Align.RIGHT
        }
        drawContext.canvas.nativeCanvas.drawText("$maxCount", padL - 4f, padT + 6f, yPaint)
        drawContext.canvas.nativeCanvas.drawText("0", padL - 4f, padT + ch + 4f, yPaint)

        // Bars
        for (i in counts.indices) {
            val x = padL + i * totalBarW + gap
            val count = counts[i]
            val barH = (count.toFloat() / maxCount) * ch
            val barTop = padT + ch - barH

            val barColor = when {
                count > avgCount * 1.5f -> Color(0xFFE57373)
                count < avgCount * 0.5f && count > 0 -> Color(0xFF81C784)
                count == 0 -> Color.White.copy(alpha = 0.1f)
                else -> AppTheme.AccentPurple
            }

            drawRoundRect(
                barColor.copy(alpha = if (count == 0) 0.15f else 0.8f),
                topLeft = Offset(x, barTop),
                size = Size(barW, barH.coerceAtLeast(2f)),
                cornerRadius = CornerRadius(3f),
            )

            // Count on top
            if (count > 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    "$count", x + barW / 2f, barTop - 4f,
                    android.graphics.Paint().apply {
                        color = Color.White.toArgb()
                        textSize = 18f; isAntiAlias = true
                        textAlign = android.graphics.Paint.Align.CENTER
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    })
            }

            // Month label
            val labelPaint = android.graphics.Paint().apply {
                color = Color.White.copy(alpha = 0.5f).toArgb()
                textSize = 17f; isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
            }
            drawContext.canvas.nativeCanvas.drawText(
                months[i].format(fmt), x + barW / 2f, h - padB + 14f, labelPaint)

            // Year on first + year changes
            if (i == 0 || months[i].year != months[i - 1].year) {
                drawContext.canvas.nativeCanvas.drawText(
                    "'${months[i].format(fmtYear)}", x + barW / 2f, h - padB + 26f,
                    android.graphics.Paint().apply {
                        color = Color.White.copy(alpha = 0.3f).toArgb()
                        textSize = 14f; isAntiAlias = true
                        textAlign = android.graphics.Paint.Align.CENTER
                    })
            }
        }
    }
}

@Composable
internal fun WeeklyBarChart(
    weeks: List<LocalDate>,
    counts: List<Int>,
    maxCount: Int,
    avgCount: Float,
    modifier: Modifier = Modifier,
) {
    val fmt = DateTimeFormatter.ofPattern("dd/MM")

    Canvas(modifier) {
        val w = size.width; val h = size.height
        val padL = 24f; val padR = 8f; val padT = 12f; val padB = 28f
        val cw = w - padL - padR; val ch = h - padT - padB

        val barCount = counts.size
        val totalBarW = cw / barCount
        val barW = totalBarW * 0.7f
        val gap = totalBarW * 0.15f

        // Avg dashed line
        val avgY = padT + ch - (avgCount / maxCount) * ch
        drawLine(
            Color.White.copy(alpha = 0.15f),
            start = Offset(padL, avgY),
            end = Offset(w - padR, avgY),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
        )

        for (i in counts.indices) {
            val x = padL + i * totalBarW + gap
            val count = counts[i]
            val barH = (count.toFloat() / maxCount) * ch

            val barColor = if (count == 0) Color.White.copy(alpha = 0.1f) else AppTheme.AccentPurple

            drawRoundRect(
                barColor.copy(alpha = if (count == 0) 0.15f else 0.7f),
                topLeft = Offset(x, padT + ch - barH.coerceAtLeast(2f)),
                size = Size(barW, barH.coerceAtLeast(2f)),
                cornerRadius = CornerRadius(3f),
            )

            if (count > 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    "$count", x + barW / 2f, padT + ch - barH - 3f,
                    android.graphics.Paint().apply {
                        color = Color.White.toArgb()
                        textSize = 16f; isAntiAlias = true
                        textAlign = android.graphics.Paint.Align.CENTER
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    })
            }

            // Show week label every 2nd bar
            if (i % 2 == 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    weeks[i].format(fmt), x + barW / 2f, h - 4f,
                    android.graphics.Paint().apply {
                        color = Color.White.copy(alpha = 0.4f).toArgb()
                        textSize = 14f; isAntiAlias = true
                        textAlign = android.graphics.Paint.Align.CENTER
                    })
            }
        }
    }
}

@Composable
private fun SeverityMonthlyBars(data: List<Triple<LocalDate, Float, Int>>) {
    // Mirrors iOS SeverityOverTimeChart (InsightsSubScreens.swift L1236-1249):
    // horizontally-scrolling row of bars, value-on-top coloured by severity.
    val fmt = DateTimeFormatter.ofPattern("MMM")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .height(120.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        data.forEach { (month, avg, _) ->
            val sevColor = when {
                avg >= 7f -> Color(0xFFE57373)
                avg >= 4f -> Color(0xFFFFB74D)
                else -> Color(0xFF81C784)
            }
            val barHeight = (avg / 10f * 80f).coerceAtLeast(4f).dp
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    String.format("%.1f", avg),
                    color = sevColor,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                )
                Box(
                    Modifier
                        .width(24.dp)
                        .height(barHeight)
                        .clip(RoundedCornerShape(4.dp))
                        .background(AppTheme.AccentPink.copy(alpha = 0.6f)),
                )
                Text(
                    month.format(fmt),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
