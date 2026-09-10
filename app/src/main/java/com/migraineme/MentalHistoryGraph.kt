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

data class MentalGraphDay(
    val date: String,
    val stress: Double?,
    val screenTimeHours: Double?,
    val lateScreenTimeHours: Double?,
    val noiseHigh: Double?,
    val noiseAvg: Double?,
    val noiseLow: Double?,
    val brightness: Double?,
    val volumePct: Double?,
    val darkModeHours: Double?,
    val unlockCount: Double?
)

data class MentalGraphResult(
    val days: List<MentalGraphDay>,
    val allTimeMin: Map<String, Float>,
    val allTimeMax: Map<String, Float>
)

// The selector's grouping: the same GRAPHABLE_METRICS, split into the families
// they come from so a ten-row list reads at a glance.
private val mentalMetricGroups = listOf(
    "Mind" to listOf(
        MentalCardConfig.METRIC_STRESS
    ),
    "Screen" to listOf(
        MentalCardConfig.METRIC_SCREEN_TIME,
        MentalCardConfig.METRIC_LATE_SCREEN_TIME,
        MentalCardConfig.METRIC_BRIGHTNESS,
        MentalCardConfig.METRIC_DARK_MODE,
        MentalCardConfig.METRIC_UNLOCKS
    ),
    "Sound" to listOf(
        MentalCardConfig.METRIC_VOLUME,
        MentalCardConfig.METRIC_NOISE_AVG,
        MentalCardConfig.METRIC_NOISE_HIGH,
        MentalCardConfig.METRIC_NOISE_LOW
    )
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MentalHistoryGraph(
    days: Int = 14,
    endDate: LocalDate = LocalDate.now(),
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var graphResult by remember { mutableStateOf<MentalGraphResult?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedMetrics by remember { mutableStateOf<Set<String>>(setOf(MentalCardConfig.METRIC_STRESS)) }
    var migraineDates by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(days, endDate) {
        scope.launch {
            graphResult = loadMentalGraphData(context, days, endDate)
            migraineDates = MigraineOverlayHelper.fetchMigraineDates(context, days, endDate)
            isLoading = false
        }
    }

    val historyData: List<MentalGraphDay> = graphResult?.days ?: emptyList()
    val allTimeMin: Map<String, Float> = graphResult?.allTimeMin ?: emptyMap()
    val allTimeMax: Map<String, Float> = graphResult?.allTimeMax ?: emptyMap()
    val isNormalized = selectedMetrics.size >= 2
    val daysWithData: List<MentalGraphDay> = historyData.filter { day ->
        MentalCardConfig.GRAPHABLE_METRICS.any { getMentalDayValue(day, it) != null }
    }

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
            val values = daysWithData.mapNotNull { getMentalDayValue(it, metric) }
            return if (values.isEmpty()) null else values.average().toFloat()
        }

        val singleMetric = selectedMetrics.singleOrNull()
        val subtitle: String
        val readout: String
        val readoutUnit: String
        val readoutColor: Color
        val readoutCaption: String
        if (singleMetric != null) {
            val avg = averageOf(singleMetric)
            subtitle = listOf(MentalCardConfig.labelFor(singleMetric), rangeLabel).filter { it.isNotEmpty() }.joinToString(" · ")
            readout = if (avg != null) formatMentalValue(avg, "") else "-"
            readoutUnit = MentalCardConfig.unitFor(singleMetric)
            readoutColor = MentalCardConfig.colorFor(singleMetric)
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
            title = t("Cognitive History"),
            subtitle = subtitle,
            readout = readout,
            readoutUnit = readoutUnit,
            readoutColor = readoutColor,
            readoutCaption = readoutCaption
        )


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
                text = t("No cognitive data available"),
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
                        val unit = MentalCardConfig.unitFor(metric)
                        Triple(
                            MentalCardConfig.labelFor(metric),
                            "${formatMentalValue(allTimeMin[metric] ?: 0f, unit)}–${formatMentalValue(allTimeMax[metric] ?: 1f, unit)}",
                            MentalCardConfig.colorFor(metric)
                        )
                    }
                )
            }

            Spacer(Modifier.height(10.dp))

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
                    val values: List<Float> = daysWithData.mapNotNull { getMentalDayValue(it, metric) }
                    val max = values.maxOrNull() ?: 1f
                    val min = values.minOrNull() ?: 0f
                    yTop = formatMentalValue(max, "")
                    yMid = formatMentalValue((max + min) / 2, "")
                    yBot = formatMentalValue(min, "")
                }

                Row(modifier = Modifier.fillMaxWidth().height(168.dp)) {
                    Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        val padding = 8.dp.toPx()
                        val graphWidth = size.width - padding * 2
                        val graphHeight = size.height - padding * 2
                        val dashWidth = 6.dp.toPx()
                        val gapWidth = 4.dp.toPx()

                        drawGraphGrid(padding, graphHeight, 1.dp.toPx())

                        // Draw migraine bands
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
                            val color = MentalCardConfig.colorFor(metric)

                            val indexedValues: List<Pair<Int, Float>> = historyData.mapIndexedNotNull { i, day ->
                                val value = getMentalDayValue(day, metric)
                                if (value != null) Pair(i, value) else null
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

                            // Noise carries its meaning in the band colour, so its
                            // segments stay individually coloured — each one drawn as
                            // its slice of the same curve.
                            val isNoise = metric == MentalCardConfig.METRIC_NOISE_HIGH ||
                                          metric == MentalCardConfig.METRIC_NOISE_AVG ||
                                          metric == MentalCardConfig.METRIC_NOISE_LOW

                            if (!isNormalized && !isNoise) {
                                drawSeriesFill(offsets, color, padding, graphHeight)
                            }

                            if (plotPoints.isNotEmpty()) {
                                val avgNormalized = plotPoints.map { it.second }.average().toFloat()
                                drawDashedAverage(
                                    color,
                                    padding + graphHeight - (avgNormalized * graphHeight),
                                    padding, dashWidth, gapWidth, 1.5.dp.toPx()
                                )
                            }

                            if (isNoise) {
                                for (i in 0 until offsets.size - 1) {
                                    val rawA = indexedValues[i].second.toDouble()
                                    val rawB = indexedValues[i + 1].second.toDouble()
                                    val segColor = noiseBandColor((rawA + rawB) / 2)
                                    val seg = smoothSegment(offsets, i)
                                    drawPath(seg, segColor.copy(alpha = 0.12f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
                                    drawPath(seg, segColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                                }
                                offsets.forEachIndexed { i, o ->
                                    drawCircle(noiseBandColor(indexedValues[i].second.toDouble()), 3.2.dp.toPx(), o)
                                }
                            } else {
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
                        else MentalCardConfig.colorFor(selectedMetrics.first()),
                    showMigraineDays = migraineDates.isNotEmpty(),
                    showForecast = false
                )
            }
        }

        // Metric selector: grouped checklist, every metric carrying its own
        // average for the window.
        MetricChecklist(
            groups = mentalMetricGroups.map { (group, metrics) ->
                group to metrics.map { metric ->
                    val unit = MentalCardConfig.unitFor(metric)
                    GraphMetricRow(
                        key = metric,
                        label = MentalCardConfig.labelFor(metric),
                        value = averageOf(metric)?.let { formatMentalValue(it, unit) },
                        color = MentalCardConfig.colorFor(metric)
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

private fun getMentalDayValue(day: MentalGraphDay, metric: String): Float? {
    return when (metric) {
        MentalCardConfig.METRIC_STRESS -> day.stress?.toFloat()
        MentalCardConfig.METRIC_SCREEN_TIME -> day.screenTimeHours?.toFloat()
        MentalCardConfig.METRIC_LATE_SCREEN_TIME -> day.lateScreenTimeHours?.toFloat()
        MentalCardConfig.METRIC_NOISE_HIGH -> day.noiseHigh?.toFloat()
        MentalCardConfig.METRIC_NOISE_AVG -> day.noiseAvg?.toFloat()
        MentalCardConfig.METRIC_NOISE_LOW -> day.noiseLow?.toFloat()
        MentalCardConfig.METRIC_BRIGHTNESS -> day.brightness?.toFloat()
        MentalCardConfig.METRIC_VOLUME -> day.volumePct?.toFloat()
        MentalCardConfig.METRIC_DARK_MODE -> day.darkModeHours?.toFloat()
        MentalCardConfig.METRIC_UNLOCKS -> day.unlockCount?.toFloat()
        else -> null
    }
}

private fun formatMentalValue(value: Float, unit: String): String {
    return when (unit) {
        "%" -> "${value.toInt()}%"
        "h" -> String.format("%.1fh", value)
        "" -> "%,d".format(value.toInt())
        else -> String.format("%.1f%s", value, unit)
    }
}

private suspend fun loadMentalGraphData(
    ctx: android.content.Context,
    days: Int,
    endDate: LocalDate = LocalDate.now()
): MentalGraphResult = withContext(Dispatchers.IO) {
    try {
        val token = SessionStore.getValidAccessToken(ctx) ?: return@withContext MentalGraphResult(emptyList(), emptyMap(), emptyMap())
        val userId = SessionStore.readUserId(ctx) ?: return@withContext MentalGraphResult(emptyList(), emptyMap(), emptyMap())

        val startDate = endDate.minusDays(days.toLong() - 1)
        val startStr = startDate.toString()
        val endStr = endDate.toString()
        val fetchLimit = days + 14

        val client = okhttp3.OkHttpClient()

        fun fetchDailyDoubles(table: String, column: String): List<Pair<String, Double>> {
            return try {
                val url = "${BuildConfig.SUPABASE_URL}/rest/v1/$table?user_id=eq.$userId&select=date,$column&order=date.desc&limit=$fetchLimit"
                val request = okhttp3.Request.Builder().url(url).get()
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $token").build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val arr = org.json.JSONArray(body)
                    (0 until arr.length()).mapNotNull { i ->
                        val obj = arr.getJSONObject(i)
                        val date = obj.optString("date", "")
                        val value = obj.optDouble(column)
                        if (date.isNotBlank() && !value.isNaN()) Pair(date, value) else null
                    }
                } else emptyList()
            } catch (_: Exception) { emptyList() }
        }

        val stressList = fetchDailyDoubles("stress_index_daily", "value")
        val screenTimeList = fetchDailyDoubles("screen_time_daily", "total_hours")
        val lateScreenList = fetchDailyDoubles("screen_time_late_night", "value_hours")
        val noiseHighList = fetchDailyDoubles("ambient_noise_index_daily", "day_max_lmax")
        val noiseAvgList = fetchDailyDoubles("ambient_noise_index_daily", "day_mean_lmean")
        val noiseLowList = fetchDailyDoubles("ambient_noise_index_daily", "day_min_lmean")
        val brightnessList = fetchDailyDoubles("phone_brightness_daily", "value_mean")
        val volumeList = fetchDailyDoubles("phone_volume_daily", "value_mean_pct")
        val darkModeList = fetchDailyDoubles("phone_dark_mode_daily", "value_hours")
        val unlockList = fetchDailyDoubles("phone_unlock_daily", "value_count")

        // Live samples fallback for today — if no daily row exists yet
        val todayStr = LocalDate.now().toString()
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val key = BuildConfig.SUPABASE_ANON_KEY

        fun fetchTodaySamplesAvg(table: String, column: String, tsColumn: String = "sampled_at"): Double? {
            return try {
                val url = "$base/rest/v1/$table?user_id=eq.$userId&${tsColumn}=gte.${todayStr}T00:00:00&${tsColumn}=lt.${todayStr}T23:59:59&select=$column"
                val request = okhttp3.Request.Builder().url(url).get()
                    .addHeader("apikey", key)
                    .addHeader("Authorization", "Bearer $token").build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val arr = org.json.JSONArray(body)
                    if (arr.length() > 0) {
                        val values = (0 until arr.length()).mapNotNull {
                            arr.getJSONObject(it).optDouble(column).takeIf { v -> !v.isNaN() }
                        }
                        if (values.isNotEmpty()) values.average() else null
                    } else null
                } else null
            } catch (_: Exception) { null }
        }

        fun fetchTodaySamplesMax(table: String, column: String, tsColumn: String = "sampled_at"): Double? {
            return try {
                val url = "$base/rest/v1/$table?user_id=eq.$userId&${tsColumn}=gte.${todayStr}T00:00:00&${tsColumn}=lt.${todayStr}T23:59:59&select=$column"
                val request = okhttp3.Request.Builder().url(url).get()
                    .addHeader("apikey", key)
                    .addHeader("Authorization", "Bearer $token").build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val arr = org.json.JSONArray(body)
                    if (arr.length() > 0) {
                        val values = (0 until arr.length()).mapNotNull {
                            val v = arr.getJSONObject(it).optInt(column, Int.MIN_VALUE)
                            if (v != Int.MIN_VALUE) v.toDouble() else null
                        }
                        values.maxOrNull()
                    } else null
                } else null
            } catch (_: Exception) { null }
        }

        fun fetchTodayDarkModeHours(): Double? {
            return try {
                val url = "$base/rest/v1/phone_dark_mode_samples?user_id=eq.$userId&sampled_at=gte.${todayStr}T00:00:00&sampled_at=lt.${todayStr}T23:59:59&select=is_dark"
                val request = okhttp3.Request.Builder().url(url).get()
                    .addHeader("apikey", key)
                    .addHeader("Authorization", "Bearer $token").build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val arr = org.json.JSONArray(body)
                    val total = arr.length()
                    if (total > 0) {
                        val darkCount = (0 until total).count { arr.getJSONObject(it).optBoolean("is_dark", false) }
                        (darkCount.toDouble() / total) * 24.0
                    } else null
                } else null
            } catch (_: Exception) { null }
        }

        fun fetchTodaySamplesMin(table: String, column: String, tsColumn: String = "sampled_at"): Double? {
            return try {
                val url = "$base/rest/v1/$table?user_id=eq.$userId&${tsColumn}=gte.${todayStr}T00:00:00&${tsColumn}=lt.${todayStr}T23:59:59&select=$column"
                val request = okhttp3.Request.Builder().url(url).get()
                    .addHeader("apikey", key)
                    .addHeader("Authorization", "Bearer $token").build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val arr = org.json.JSONArray(body)
                    if (arr.length() > 0) {
                        val values = (0 until arr.length()).mapNotNull {
                            arr.getJSONObject(it).optDouble(column).takeIf { v -> !v.isNaN() }
                        }
                        values.minOrNull()
                    } else null
                } else null
            } catch (_: Exception) { null }
        }

        fun fetchTodaySamplesMaxDouble(table: String, column: String, tsColumn: String = "sampled_at"): Double? {
            return try {
                val url = "$base/rest/v1/$table?user_id=eq.$userId&${tsColumn}=gte.${todayStr}T00:00:00&${tsColumn}=lt.${todayStr}T23:59:59&select=$column"
                val request = okhttp3.Request.Builder().url(url).get()
                    .addHeader("apikey", key)
                    .addHeader("Authorization", "Bearer $token").build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val arr = org.json.JSONArray(body)
                    if (arr.length() > 0) {
                        val values = (0 until arr.length()).mapNotNull {
                            arr.getJSONObject(it).optDouble(column).takeIf { v -> !v.isNaN() }
                        }
                        values.maxOrNull()
                    } else null
                } else null
            } catch (_: Exception) { null }
        }

        // Merge live samples into the lists if today has no daily row
        val mutableBrightness = brightnessList.toMutableList()
        val mutableVolume = volumeList.toMutableList()
        val mutableDarkMode = darkModeList.toMutableList()
        val mutableUnlocks = unlockList.toMutableList()
        val mutableNoiseHigh = noiseHighList.toMutableList()
        val mutableNoiseAvg = noiseAvgList.toMutableList()
        val mutableNoiseLow = noiseLowList.toMutableList()
        val mutableScreenTime = screenTimeList.toMutableList()

        if (todayStr >= startStr && todayStr <= endStr) {
            // Screen time: fall back to screen_time_live
            if (mutableScreenTime.none { it.first == todayStr }) {
                fetchDailyDoubles("screen_time_live", "value_hours")
                    .find { it.first == todayStr }?.let {
                        mutableScreenTime.add(it)
                    }
            }
            if (mutableBrightness.none { it.first == todayStr }) {
                fetchTodaySamplesAvg("phone_brightness_samples", "value")?.let {
                    mutableBrightness.add(Pair(todayStr, it))
                }
            }
            if (mutableVolume.none { it.first == todayStr }) {
                fetchTodaySamplesAvg("phone_volume_samples", "value_pct")?.let {
                    mutableVolume.add(Pair(todayStr, it))
                }
            }
            if (mutableDarkMode.none { it.first == todayStr }) {
                fetchTodayDarkModeHours()?.let {
                    mutableDarkMode.add(Pair(todayStr, it))
                }
            }
            if (mutableUnlocks.none { it.first == todayStr }) {
                fetchTodaySamplesMax("phone_unlock_samples", "value_count")?.let {
                    mutableUnlocks.add(Pair(todayStr, it))
                }
            }
            // Noise: high = max of l_max, avg = avg of l_mean, low = min of l_mean
            if (mutableNoiseHigh.none { it.first == todayStr }) {
                fetchTodaySamplesMaxDouble("ambient_noise_samples", "l_max", "start_ts")?.let {
                    mutableNoiseHigh.add(Pair(todayStr, it))
                }
            }
            if (mutableNoiseAvg.none { it.first == todayStr }) {
                fetchTodaySamplesAvg("ambient_noise_samples", "l_mean", "start_ts")?.let {
                    mutableNoiseAvg.add(Pair(todayStr, it))
                }
            }
            if (mutableNoiseLow.none { it.first == todayStr }) {
                fetchTodaySamplesMin("ambient_noise_samples", "l_mean", "start_ts")?.let {
                    mutableNoiseLow.add(Pair(todayStr, it))
                }
            }
        }

        val stressMap = stressList.associateBy { it.first }
        val screenTimeMap = mutableScreenTime.associateBy { it.first }
        val lateScreenMap = lateScreenList.associateBy { it.first }
        val noiseHighMap = mutableNoiseHigh.associateBy { it.first }
        val noiseAvgMap = mutableNoiseAvg.associateBy { it.first }
        val noiseLowMap = mutableNoiseLow.associateBy { it.first }
        val brightnessMap = mutableBrightness.associateBy { it.first }
        val volumeMap = mutableVolume.associateBy { it.first }
        val darkModeMap = mutableDarkMode.associateBy { it.first }
        val unlockMap = mutableUnlocks.associateBy { it.first }

        val allDates = mutableSetOf<String>()
        listOf(stressMap, screenTimeMap, lateScreenMap, noiseHighMap, noiseAvgMap, noiseLowMap, brightnessMap, volumeMap, darkModeMap, unlockMap).forEach {
            allDates.addAll(it.keys)
        }

        val graphDays = allDates
            .filter { it >= startStr && it <= endStr }
            .sorted()
            .map { date ->
                MentalGraphDay(
                    date = date,
                    stress = stressMap[date]?.second,
                    screenTimeHours = screenTimeMap[date]?.second,
                    lateScreenTimeHours = lateScreenMap[date]?.second,
                    noiseHigh = noiseHighMap[date]?.second,
                    noiseAvg = noiseAvgMap[date]?.second,
                    noiseLow = noiseLowMap[date]?.second,
                    brightness = brightnessMap[date]?.second,
                    volumePct = volumeMap[date]?.second,
                    darkModeHours = darkModeMap[date]?.second,
                    unlockCount = unlockMap[date]?.second
                )
            }

        val allTimeMin = mutableMapOf<String, Float>()
        val allTimeMax = mutableMapOf<String, Float>()

        for (metric in MentalCardConfig.GRAPHABLE_METRICS) {
            val values: List<Float> = graphDays.mapNotNull { getMentalDayValue(it, metric) }
            if (values.isNotEmpty()) {
                allTimeMin[metric] = values.minOrNull() ?: 0f
                allTimeMax[metric] = values.maxOrNull() ?: 1f
            }
        }

        MentalGraphResult(graphDays, allTimeMin, allTimeMax)
    } catch (_: Exception) {
        MentalGraphResult(emptyList(), emptyMap(), emptyMap())
    }
}

/** Color a noise log-RMS value by its display band. Mirrors iOS noiseBandColor. */
private fun noiseBandColor(v: Double): Color = when {
    v >= 10.0 -> Color(0xFFEF5350)  // Very loud (>=85 dB)
    v >= 8.0  -> Color(0xFFFFB74D)  // Loud (70-85 dB)
    v >= 6.0  -> Color(0xFFFFEB3B)  // Moderate (50-70 dB)
    else      -> Color(0xFF81C784)  // Quiet (<50 dB)
}
