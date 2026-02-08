package com.migraineme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class SleepGraphDay(
    val date: String,
    val duration: Double?,
    val score: Double?,
    val efficiency: Double?,
    val disturbances: Int?,
    val stagesDeep: Double?,
    val stagesRem: Double?,
    val stagesLight: Double?
)

data class SleepGraphResult(
    val days: List<SleepGraphDay>,
    val allTimeMin: Map<String, Float>,
    val allTimeMax: Map<String, Float>
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SleepHistoryGraph(
    days: Int = 14,
    endDate: java.time.LocalDate = java.time.LocalDate.now(),
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var graphResult by remember { mutableStateOf<SleepGraphResult?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedMetrics by remember { mutableStateOf<Set<String>>(setOf(SleepCardConfig.METRIC_DURATION)) }
    var migraineDates by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(days, endDate) {
        scope.launch {
            graphResult = loadSleepGraphData(context, days, endDate)
            migraineDates = MigraineOverlayHelper.fetchMigraineDates(context, days, endDate)
            isLoading = false
        }
    }

    val historyData: List<SleepGraphDay> = graphResult?.days ?: emptyList()
    val allTimeMin: Map<String, Float> = graphResult?.allTimeMin ?: emptyMap()
    val allTimeMax: Map<String, Float> = graphResult?.allTimeMax ?: emptyMap()
    val isNormalized = selectedMetrics.size >= 2
    val daysWithData: List<SleepGraphDay> = historyData.filter { it.duration != null && it.duration > 0.0 }

    BaseCard(modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$days-Day Sleep History",
                color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
            if (onClick != null) {
                Text("View Full →", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(8.dp))

        if (isLoading) {
            Row(
                modifier = Modifier.fillMaxWidth().height(150.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), AppTheme.AccentPurple, strokeWidth = 2.dp)
            }
        } else if (historyData.isEmpty() || daysWithData.isEmpty()) {
            Text(
                text = "No sleep data available",
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().height(150.dp).padding(vertical = 60.dp),
                textAlign = TextAlign.Center
            )
        } else if (selectedMetrics.isEmpty()) {
            Text(
                text = "Select a metric below",
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().height(150.dp).padding(vertical = 60.dp),
                textAlign = TextAlign.Center
            )
        } else {
            // Legend
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (metric in selectedMetrics) {
                    val color = SleepCardConfig.colorFor(metric)
                    val label = SleepCardConfig.labelFor(metric)
                    val unit = SleepCardConfig.unitFor(metric)
                    val values: List<Float> = daysWithData.mapNotNull { getSleepDayValue(it, metric) }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(8.dp)) { drawCircle(color) }
                        Spacer(Modifier.width(4.dp))
                        if (isNormalized) {
                            val minVal = allTimeMin[metric] ?: 0f
                            val maxVal = allTimeMax[metric] ?: 1f
                            Text(
                                text = "$label [${formatSleepValue(minVal, unit)}-${formatSleepValue(maxVal, unit)}]",
                                color = color,
                                style = MaterialTheme.typography.labelSmall
                            )
                        } else {
                            val avg = if (values.isNotEmpty()) values.average().toFloat() else 0f
                            Text(
                                text = "$label (avg: ${formatSleepValue(avg, unit)})",
                                color = color,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            if (isNormalized) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "⚠️ Normalized 0-1 scale • Dotted = $days days avg",
                    color = Color(0xFFFFB74D),
                    style = MaterialTheme.typography.labelSmall
                )
            } else if (daysWithData.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Dotted line = $days-day average",
                    color = AppTheme.SubtleTextColor.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            if (migraineDates.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(8.dp)) { drawRect(Color(0xFFE57373).copy(alpha = 0.35f)) }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Red bands = migraine days",
                        color = Color(0xFFE57373),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (daysWithData.isEmpty()) {
                Text(
                    text = "No logged days in this period",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                val yAxisWidth = 50.dp

                val yTop: String
                val yMid: String
                val yBot: String

                if (isNormalized) {
                    yTop = "1.0"
                    yMid = "0.5"
                    yBot = "0.0"
                } else {
                    val metric = selectedMetrics.first()
                    val unit = SleepCardConfig.unitFor(metric)
                    val values: List<Float> = daysWithData.mapNotNull { getSleepDayValue(it, metric) }
                    val max = values.maxOrNull() ?: 1f
                    val min = values.minOrNull() ?: 0f
                    yTop = formatSleepValue(max, unit)
                    yMid = formatSleepValue((max + min) / 2, unit)
                    yBot = formatSleepValue(min, unit)
                }

                Row(modifier = Modifier.fillMaxWidth().height(150.dp)) {
                    Column(
                        modifier = Modifier.width(yAxisWidth).fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = yTop, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        Text(text = yMid, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        Text(text = yBot, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    }

                    Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        val padding = 8.dp.toPx()
                        val graphWidth = size.width - padding * 2
                        val graphHeight = size.height - padding * 2
                        val dashWidth = 6.dp.toPx()
                        val gapWidth = 4.dp.toPx()

                        // Draw migraine bands (behind everything)
                        with(MigraineOverlayHelper) {
                            drawMigraineBands(
                                dateList = historyData.map { it.date },
                                migraineDates = migraineDates,
                                padding = padding,
                                graphWidth = graphWidth,
                                graphHeight = graphHeight
                            )
                        }

                        for (metric in selectedMetrics) {
                            val color = SleepCardConfig.colorFor(metric)

                            val indexedValues: List<Pair<Int, Float>> = historyData.mapIndexedNotNull { i, day ->
                                val value = getSleepDayValue(day, metric)
                                if (value != null && day.duration != null && day.duration > 0.0) Pair(i, value) else null
                            }

                            if (indexedValues.isEmpty()) continue

                            val values: List<Float> = indexedValues.map { it.second }

                            val minVal: Float
                            val maxVal: Float
                            if (isNormalized) {
                                minVal = allTimeMin[metric] ?: 0f
                                maxVal = (allTimeMax[metric] ?: 1f).coerceAtLeast(minVal + 0.1f)
                            } else {
                                minVal = values.minOrNull() ?: 0f
                                maxVal = (values.maxOrNull() ?: 1f).coerceAtLeast(minVal + 0.1f)
                            }
                            val range = maxVal - minVal

                            val plotPoints: List<Pair<Int, Float>> = indexedValues.map { (idx, value) ->
                                Pair(idx, ((value - minVal) / range).coerceIn(0f, 1f))
                            }

                            // Dotted average
                            if (plotPoints.isNotEmpty()) {
                                val avgNormalized = plotPoints.map { it.second }.average().toFloat()
                                val avgY = padding + graphHeight - (avgNormalized * graphHeight)
                                var x = padding
                                while (x < size.width - padding) {
                                    drawLine(
                                        color.copy(alpha = 0.5f),
                                        Offset(x, avgY),
                                        Offset((x + dashWidth).coerceAtMost(size.width - padding), avgY),
                                        strokeWidth = 1.5.dp.toPx()
                                    )
                                    x += dashWidth + gapWidth
                                }
                            }

                            // Line
                            if (plotPoints.size > 1) {
                                val path = Path()
                                plotPoints.forEachIndexed { i, pair ->
                                    val x = padding + (pair.first.toFloat() / (historyData.size - 1).coerceAtLeast(1)) * graphWidth
                                    val y = padding + graphHeight - (pair.second * graphHeight)
                                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }

                            // Dots
                            for (pair in plotPoints) {
                                val x = padding + (pair.first.toFloat() / (historyData.size - 1).coerceAtLeast(1)) * graphWidth
                                val y = padding + graphHeight - (pair.second * graphHeight)
                                drawCircle(color, 4.dp.toPx(), Offset(x, y))
                            }
                        }
                    }
                }

                // Date labels
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = yAxisWidth),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val fmt = DateTimeFormatter.ofPattern("MMM d")
                    if (historyData.isNotEmpty()) {
                        val firstLabel = try { LocalDate.parse(historyData.first().date).format(fmt) } catch (_: Exception) { historyData.first().date }
                        val lastLabel = try { LocalDate.parse(historyData.last().date).format(fmt) } catch (_: Exception) { historyData.last().date }
                        Text(text = firstLabel, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        Text(text = lastLabel, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Select Metrics" + if (selectedMetrics.size > 1) " (${selectedMetrics.size} selected)" else "",
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.height(8.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (metric in SleepCardConfig.GRAPHABLE_METRICS) {
                val isSelected = metric in selectedMetrics
                val chipColor = SleepCardConfig.colorFor(metric)
                val chipLabel = SleepCardConfig.labelFor(metric)

                FilterChip(
                    selected = isSelected,
                    onClick = {
                        selectedMetrics = if (isSelected) {
                            selectedMetrics.minus(metric)
                        } else {
                            selectedMetrics.plus(metric)
                        }
                    },
                    label = { Text(text = chipLabel, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = chipColor.copy(alpha = 0.3f),
                        selectedLabelColor = chipColor,
                        containerColor = AppTheme.BaseCardContainer,
                        labelColor = AppTheme.SubtleTextColor
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = if (isSelected) chipColor else AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                        selectedBorderColor = chipColor,
                        enabled = true,
                        selected = isSelected
                    )
                )
            }
        }
    }
}

private fun getSleepDayValue(day: SleepGraphDay, metric: String): Float? {
    return when (metric) {
        SleepCardConfig.METRIC_DURATION -> day.duration?.toFloat()
        SleepCardConfig.METRIC_SCORE -> day.score?.toFloat()
        SleepCardConfig.METRIC_EFFICIENCY -> day.efficiency?.toFloat()
        SleepCardConfig.METRIC_DISTURBANCES -> day.disturbances?.toFloat()
        SleepCardConfig.METRIC_STAGES_DEEP -> day.stagesDeep?.toFloat()
        SleepCardConfig.METRIC_STAGES_REM -> day.stagesRem?.toFloat()
        SleepCardConfig.METRIC_STAGES_LIGHT -> day.stagesLight?.toFloat()
        else -> null
    }
}

private fun formatSleepValue(value: Float, unit: String): String {
    return when (unit) {
        "h" -> String.format("%.1f%s", value, unit)
        "%" -> "${value.toInt()}%"
        else -> value.toInt().toString()
    }
}

private suspend fun loadSleepGraphData(
    ctx: android.content.Context,
    days: Int,
    endDate: java.time.LocalDate = java.time.LocalDate.now()
): SleepGraphResult = withContext(Dispatchers.IO) {
    try {
        val token = SessionStore.getValidAccessToken(ctx) ?: return@withContext SleepGraphResult(emptyList(), emptyMap(), emptyMap())
        val metrics = SupabaseMetricsService(ctx)

        val startDate = endDate.minusDays(days.toLong() - 1)

        // Fetch enough data to cover the window (fetch extra to be safe)
        val fetchLimit = days + 14
        val durations = try { metrics.fetchSleepDurationDaily(token, fetchLimit) } catch (_: Exception) { emptyList() }
        val scores = try { metrics.fetchSleepScoreDaily(token, fetchLimit) } catch (_: Exception) { emptyList() }
        val efficiencies = try { metrics.fetchSleepEfficiencyDaily(token, fetchLimit) } catch (_: Exception) { emptyList() }
        val disturbances = try { metrics.fetchSleepDisturbancesDaily(token, fetchLimit) } catch (_: Exception) { emptyList() }
        val stages = try { metrics.fetchSleepStagesDaily(token, fetchLimit) } catch (_: Exception) { emptyList() }

        val scoreMap = scores.associateBy { it.date }
        val effMap = efficiencies.associateBy { it.date }
        val distMap = disturbances.associateBy { it.date }
        val stagesMap = stages.associateBy { it.date }

        // Build all days in the window, filter to date range
        val startStr = startDate.toString()
        val endStr = endDate.toString()

        val graphDays: List<SleepGraphDay> = durations
            .filter { it.date >= startStr && it.date <= endStr }
            .map { dur ->
                val score = scoreMap[dur.date]
                val eff = effMap[dur.date]
                val dist = distMap[dur.date]
                val stg = stagesMap[dur.date]
                SleepGraphDay(
                    date = dur.date,
                    duration = dur.value_hours,
                    score = score?.value_pct,
                    efficiency = eff?.value_pct,
                    disturbances = dist?.value_count,
                    stagesDeep = stg?.value_sws_hm,
                    stagesRem = stg?.value_rem_hm,
                    stagesLight = stg?.value_light_hm
                )
            }.sortedBy { it.date }

        val allTimeMin = mutableMapOf<String, Float>()
        val allTimeMax = mutableMapOf<String, Float>()

        for (metric in SleepCardConfig.GRAPHABLE_METRICS) {
            val values: List<Float> = graphDays.mapNotNull { getSleepDayValue(it, metric) }
            if (values.isNotEmpty()) {
                allTimeMin[metric] = values.minOrNull() ?: 0f
                allTimeMax[metric] = values.maxOrNull() ?: 1f
            }
        }

        SleepGraphResult(graphDays, allTimeMin, allTimeMax)
    } catch (_: Exception) {
        SleepGraphResult(emptyList(), emptyMap(), emptyMap())
    }
}

