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
    val stagesLight: Double?,
    val fellAsleepHours: Double? = null,
    val wokeUpHours: Double? = null
)

data class SleepGraphResult(
    val days: List<SleepGraphDay>,
    val allTimeMin: Map<String, Float>,
    val allTimeMax: Map<String, Float>
)

// The selector's grouping: the same GRAPHABLE_METRICS, split into the families
// they come from so a nine-row list reads at a glance.
private val sleepMetricGroups = listOf(
    "Sleep" to listOf(
        SleepCardConfig.METRIC_DURATION,
        SleepCardConfig.METRIC_SCORE,
        SleepCardConfig.METRIC_EFFICIENCY,
        SleepCardConfig.METRIC_DISTURBANCES
    ),
    "Sleep stages" to listOf(
        SleepCardConfig.METRIC_STAGES_DEEP,
        SleepCardConfig.METRIC_STAGES_REM,
        SleepCardConfig.METRIC_STAGES_LIGHT
    ),
    "Sleep times" to listOf(
        SleepCardConfig.METRIC_FELL_ASLEEP,
        SleepCardConfig.METRIC_WOKE_UP
    )
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
    var sources by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(days, endDate) {
        scope.launch {
            graphResult = loadSleepGraphData(context, days, endDate)
            migraineDates = MigraineOverlayHelper.fetchMigraineDates(context, days, endDate)
            val token = SessionStore.getValidAccessToken(context)
            if (token != null) {
                sources = fetchSourcesForDate(context, token, java.time.LocalDate.now().toString(), listOf("sleep_duration_daily", "sleep_score_daily"))
            }
            isLoading = false
        }
    }

    val historyData: List<SleepGraphDay> = graphResult?.days ?: emptyList()
    val allTimeMin: Map<String, Float> = graphResult?.allTimeMin ?: emptyMap()
    val allTimeMax: Map<String, Float> = graphResult?.allTimeMax ?: emptyMap()
    val isNormalized = selectedMetrics.size >= 2
    val daysWithData: List<SleepGraphDay> = historyData.filter { it.duration != null && it.duration > 0.0 }

    BaseCard(modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier) {
        val dateFmt = DateTimeFormatter.ofPattern("MMM d", appLocale())
        val rangeLabel = if (historyData.isNotEmpty()) {
            val from = try { LocalDate.parse(historyData.first().date).format(dateFmt) } catch (_: Exception) { historyData.first().date }
            val to = try { LocalDate.parse(historyData.last().date).format(dateFmt) } catch (_: Exception) { historyData.last().date }
            "$from – $to"
        } else ""

        // A metric's own average over the days that carry data. Feeds both the
        // header reading and the value column of the checklist.
        fun averageOf(metric: String): Float? {
            val values = daysWithData.mapNotNull { getSleepDayValue(it, metric) }
            return if (values.isEmpty()) null else values.average().toFloat()
        }

        val singleMetric = selectedMetrics.singleOrNull()
        val subtitle: String
        val readout: String
        val readoutUnit: String
        val readoutColor: Color
        val readoutCaption: String
        if (singleMetric != null) {
            val unit = SleepCardConfig.unitFor(singleMetric)
            val avg = averageOf(singleMetric)
            subtitle = listOf(SleepCardConfig.labelFor(singleMetric), rangeLabel).filter { it.isNotEmpty() }.joinToString(" · ")
            readout = if (avg != null) formatSleepValue(avg, "") else "-"
            readoutUnit = unit
            readoutColor = SleepCardConfig.colorFor(singleMetric)
            readoutCaption = t("%s-day average", days)
        } else if (selectedMetrics.size >= 2) {
            subtitle = listOf(t("%s metrics", selectedMetrics.size), rangeLabel).filter { it.isNotEmpty() }.joinToString(" · ")
            readout = "0–1"
            readoutUnit = ""
            readoutColor = GraphNormalisedColor
            readoutCaption = t("normalised scale")
        } else {
            subtitle = rangeLabel
            readout = "-"
            readoutUnit = ""
            readoutColor = AppTheme.SubtleTextColor
            readoutCaption = ""
        }

        GraphCardHeader(
            title = t("Sleep History"),
            subtitle = subtitle,
            readout = readout,
            readoutUnit = readoutUnit,
            readoutColor = readoutColor,
            readoutCaption = readoutCaption
        )

        if (sources.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            SourceBadgeRow(sources)
        }

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
                text = t("No sleep data available"),
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().height(150.dp).padding(vertical = 60.dp),
                textAlign = TextAlign.Center
            )
        } else if (selectedMetrics.isEmpty()) {
            Text(
                text = t("Select a metric below"),
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().height(150.dp).padding(vertical = 60.dp),
                textAlign = TextAlign.Center
            )
        } else {
            // Per-metric ranges, the only way to read a normalised line back to
            // real units.
            if (isNormalized) {
                Spacer(Modifier.height(10.dp))
                MetricRangeKeys(
                    selectedMetrics.map { metric ->
                        val unit = SleepCardConfig.unitFor(metric)
                        Triple(
                            SleepCardConfig.labelFor(metric),
                            "${formatSleepValue(allTimeMin[metric] ?: 0f, unit)}–${formatSleepValue(allTimeMax[metric] ?: 1f, unit)}",
                            SleepCardConfig.colorFor(metric)
                        )
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            if (daysWithData.isEmpty()) {
                Text(
                    text = t("No logged days in this period"),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                val yAxisWidth = 44.dp

                val yTop: String
                val yMid: String
                val yBot: String

                if (isNormalized) {
                    yTop = "1.0"
                    yMid = "0.5"
                    yBot = "0.0"
                } else {
                    val metric = selectedMetrics.first()
                    val values: List<Float> = daysWithData.mapNotNull { getSleepDayValue(it, metric) }
                    val max = values.maxOrNull() ?: 1f
                    val min = values.minOrNull() ?: 0f
                    yTop = formatSleepValue(max, "")
                    yMid = formatSleepValue((max + min) / 2, "")
                    yBot = formatSleepValue(min, "")
                }

                Row(modifier = Modifier.fillMaxWidth().height(168.dp)) {
                    Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        val padding = 8.dp.toPx()
                        val graphWidth = size.width - padding * 2
                        val graphHeight = size.height - padding * 2
                        val dashWidth = 6.dp.toPx()
                        val gapWidth = 4.dp.toPx()

                        drawGraphGrid(padding, graphHeight, 1.dp.toPx())

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

                            // Screen points once, then the shared curve: the same
                            // Catmull-Rom the Migraine Timeline draws.
                            val offsets = plotPoints.map { pair ->
                                Offset(
                                    padding + (pair.first.toFloat() / (historyData.size - 1).coerceAtLeast(1)) * graphWidth,
                                    padding + graphHeight - (pair.second * graphHeight)
                                )
                            }

                            if (!isNormalized) {
                                drawSeriesFill(offsets, color, padding, graphHeight)
                            }

                            // Dotted average
                            if (plotPoints.isNotEmpty()) {
                                val avgNormalized = plotPoints.map { it.second }.average().toFloat()
                                drawDashedAverage(
                                    color,
                                    padding + graphHeight - (avgNormalized * graphHeight),
                                    padding, dashWidth, gapWidth, 1.5.dp.toPx()
                                )
                            }

                            drawSeriesCurve(
                                offsets,
                                color,
                                (if (isNormalized) 1.9f else 2.2f).dp.toPx(),
                                (if (isNormalized) 5f else 6f).dp.toPx()
                            )

                            offsets.forEach { o ->
                                drawCircle(color, (if (isNormalized) 2.6f else 3.2f).dp.toPx(), o)
                            }
                        }
                    }

                    // Y-axis labels
                    Column(
                        modifier = Modifier.width(yAxisWidth).fillMaxHeight().padding(start = 6.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(text = yTop, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        Text(text = yMid, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        Text(text = yBot, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    }
                }

                // Date labels
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = yAxisWidth),
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

                GraphKeyRow(
                    averageLabel = if (isNormalized) t("each line's average") else t("history average"),
                    averageColor = if (isNormalized) Color.White.copy(alpha = 0.55f)
                        else SleepCardConfig.colorFor(selectedMetrics.first()),
                    showMigraineDays = migraineDates.isNotEmpty(),
                    showForecast = false
                )
            }
        }

        // Metric selector: grouped checklist, every metric carrying its own
        // average for the window.
        MetricChecklist(
            groups = sleepMetricGroups.map { (group, metrics) ->
                group to metrics.map { metric ->
                    val unit = SleepCardConfig.unitFor(metric)
                    GraphMetricRow(
                        key = metric,
                        label = SleepCardConfig.labelFor(metric),
                        value = averageOf(metric)?.let { formatSleepValue(it, unit) },
                        color = SleepCardConfig.colorFor(metric)
                    )
                }
            },
            selected = selectedMetrics,
            onToggle = { metric ->
                selectedMetrics = if (metric in selectedMetrics) selectedMetrics.minus(metric)
                    else selectedMetrics.plus(metric)
            }
        )
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
        SleepCardConfig.METRIC_FELL_ASLEEP -> day.fellAsleepHours?.toFloat()
        SleepCardConfig.METRIC_WOKE_UP -> day.wokeUpHours?.toFloat()
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
        val userId = SessionStore.readUserId(ctx)
        val fellAsleep = if (userId != null) fetchTimeOfDayHours(token, userId, "fell_asleep_time_daily", fetchLimit, shiftPastMidnight = true) else emptyList()
        val wokeUp = if (userId != null) fetchTimeOfDayHours(token, userId, "woke_up_time_daily", fetchLimit, shiftPastMidnight = false) else emptyList()

        val scoreMap = scores.associateBy { it.date }
        val effMap = efficiencies.associateBy { it.date }
        val distMap = disturbances.associateBy { it.date }
        val stagesMap = stages.associateBy { it.date }
        val fellAsleepMap = fellAsleep.toMap()
        val wokeUpMap = wokeUp.toMap()

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
                    stagesLight = stg?.value_light_hm,
                    fellAsleepHours = fellAsleepMap[dur.date],
                    wokeUpHours = wokeUpMap[dur.date]
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

/**
 * Fetch a time-of-day daily table (`value_at` column = ISO timestamp) and convert
 * to hours-from-midnight. For fell-asleep entries logged after midnight (0-12h)
 * we shift by +24 so the line stays continuous with same-evening bed-times.
 */
private fun fetchTimeOfDayHours(
    token: String,
    userId: String,
    table: String,
    limit: Int,
    shiftPastMidnight: Boolean
): List<Pair<String, Double>> {
    return try {
        val client = okhttp3.OkHttpClient()
        val url = "${BuildConfig.SUPABASE_URL}/rest/v1/$table?user_id=eq.$userId&select=date,value_at&order=date.desc&limit=$limit"
        val req = okhttp3.Request.Builder().url(url).get()
            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer $token").build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: return emptyList()
        if (!resp.isSuccessful) return emptyList()
        val arr = org.json.JSONArray(body)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.getJSONObject(i)
            val date = obj.optString("date", "")
            val ts = obj.optString("value_at", "")
            if (date.isBlank() || ts.isBlank()) return@mapNotNull null
            val hours = TimeOfDay.toHoursOfDay(ts) ?: return@mapNotNull null
            val shifted = if (shiftPastMidnight && hours < 12) hours + 24 else hours
            date to shifted
        }
    } catch (_: Exception) { emptyList() }
}

