// FILE: FrequencyTrendsScreen.kt
package com.migraineme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
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
    val migraines by vm.migraines.collectAsState()
    val weeklySummary by vm.weeklySummary.collectAsState()
    val dayOfWeek by vm.dayOfWeekPattern.collectAsState()
    val scrollState = rememberScrollState()
    val zone = ZoneId.systemDefault()

    // Group migraines by month
    val byMonth = remember(migraines) {
        migraines.groupBy {
            it.start.atZone(zone).toLocalDate().withDayOfMonth(1)
        }.toSortedMap()
    }

    // Group by week (last 12 weeks)
    val byWeek = remember(migraines) {
        val today = LocalDate.now()
        val weekStart = today.minusWeeks(12)
        migraines.filter {
            val d = it.start.atZone(zone).toLocalDate()
            !d.isBefore(weekStart)
        }.groupBy {
            val d = it.start.atZone(zone).toLocalDate()
            // Monday-based week start
            d.minusDays(d.dayOfWeek.value.toLong() - 1)
        }.toSortedMap()
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            // ── Header ──
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Text("Frequency Trends", color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            }

            // ── Summary hero card ──
            weeklySummary?.let { ws ->
                HeroCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        val perMonth = if (byMonth.isNotEmpty()) {
                            migraines.size.toFloat() / byMonth.size
                        } else 0f

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

            // ── Weekly bar chart (last 12 weeks) ──
            if (byWeek.size >= 3) {
                Spacer(Modifier.height(4.dp))
                BaseCard {
                    Text("Weekly (last 12 weeks)", color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(8.dp))

                    // Fill in missing weeks with 0
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

            // ── Day-of-week distribution ──
            if (dayOfWeek.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
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

                    // Highlight worst day
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

            // ── Severity over time (monthly average) ──
            if (byMonth.size >= 3) {
                val monthsWithSev = byMonth.entries.mapNotNull { (month, migs) ->
                    val sevs = migs.mapNotNull { it.severity }
                    if (sevs.isEmpty()) null else Triple(month, sevs.average().toFloat(), sevs.size)
                }
                if (monthsWithSev.size >= 3) {
                    Spacer(Modifier.height(4.dp))
                    BaseCard {
                        Text("Average Severity", color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.height(8.dp))

                        SeverityLineChart(
                            data = monthsWithSev,
                            modifier = Modifier.fillMaxWidth().height(160.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatColumn(value: String, label: String, sub: String? = null, color: Color = Color.White) {
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
private fun SeverityLineChart(
    data: List<Triple<LocalDate, Float, Int>>, // month, avgSeverity, count
    modifier: Modifier = Modifier,
) {
    val fmt = DateTimeFormatter.ofPattern("MMM")

    Canvas(modifier) {
        val w = size.width; val h = size.height
        val padL = 32f; val padR = 8f; val padT = 12f; val padB = 28f
        val cw = w - padL - padR; val ch = h - padT - padB

        val maxSev = 10f
        val minSev = 0f

        // Grid lines
        for (sev in listOf(2f, 4f, 6f, 8f)) {
            val gy = padT + ch - (sev / maxSev) * ch
            drawLine(Color.White.copy(alpha = 0.06f), Offset(padL, gy), Offset(w - padR, gy), 1f)
        }

        // Y-axis
        val yPaint = android.graphics.Paint().apply {
            color = Color.White.copy(alpha = 0.4f).toArgb()
            textSize = 18f; isAntiAlias = true; textAlign = android.graphics.Paint.Align.RIGHT
        }
        drawContext.canvas.nativeCanvas.drawText("10", padL - 4f, padT + 5f, yPaint)
        drawContext.canvas.nativeCanvas.drawText("0", padL - 4f, padT + ch + 4f, yPaint)

        if (data.size < 2) return@Canvas

        val daySpan = ChronoUnit.DAYS.between(data.first().first, data.last().first).toFloat().coerceAtLeast(1f)
        fun dateX(d: LocalDate) = padL + (ChronoUnit.DAYS.between(data.first().first, d).toFloat() / daySpan) * cw
        fun sevY(s: Float) = padT + ch - (s / maxSev) * ch

        // Line
        val path = android.graphics.Path()
        data.forEachIndexed { i, (month, avg, _) ->
            val x = dateX(month); val py = sevY(avg)
            if (i == 0) path.moveTo(x, py) else path.lineTo(x, py)
        }
        drawContext.canvas.nativeCanvas.drawPath(path, android.graphics.Paint().apply {
            color = AppTheme.AccentPink.copy(alpha = 0.15f).toArgb()
            strokeWidth = 5f; style = android.graphics.Paint.Style.STROKE
            isAntiAlias = true; strokeCap = android.graphics.Paint.Cap.ROUND
        })
        drawContext.canvas.nativeCanvas.drawPath(path, android.graphics.Paint().apply {
            color = AppTheme.AccentPink.copy(alpha = 0.8f).toArgb()
            strokeWidth = 2.5f; style = android.graphics.Paint.Style.STROKE
            isAntiAlias = true; strokeCap = android.graphics.Paint.Cap.ROUND
        })

        // Points + labels
        data.forEach { (month, avg, count) ->
            val x = dateX(month); val py = sevY(avg)
            drawCircle(AppTheme.AccentPink.copy(alpha = 0.3f), 5f, Offset(x, py))
            drawCircle(AppTheme.AccentPink, 3f, Offset(x, py))

            drawContext.canvas.nativeCanvas.drawText(
                String.format("%.1f", avg), x, py - 8f,
                android.graphics.Paint().apply {
                    color = Color.White.copy(alpha = 0.7f).toArgb()
                    textSize = 16f; isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
        }

        // X-axis month labels
        val xPaint = android.graphics.Paint().apply {
            color = Color.White.copy(alpha = 0.4f).toArgb()
            textSize = 16f; isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val step = (data.size / 6).coerceAtLeast(1)
        data.forEachIndexed { i, (month, _, _) ->
            if (i % step == 0 || i == data.lastIndex) {
                drawContext.canvas.nativeCanvas.drawText(
                    month.format(fmt), dateX(month), h - 4f, xPaint)
            }
        }
    }
}
