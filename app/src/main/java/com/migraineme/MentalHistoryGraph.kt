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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$days-Day Mental Health History",
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
                text = "No mental health data available",
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
                    val color = MentalCardConfig.colorFor(metric)
                    val label = MentalCardConfig.labelFor(metric)
                    val unit = MentalCardConfig.unitFor(metric)
                    val values: List<Float> = daysWithData.mapNotNull { getMentalDayValue(it, metric) }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(8.dp)) { drawCircle(color) }
                        Spacer(Modifier.width(4.dp))
                        if (isNormalized) {
                            val minVal = allTimeMin[metric] ?: 0f
                            val maxVal = allTimeMax[metric] ?: 1f
                            Text(
                                text = "$label [${formatMentalValue(minVal, unit)}-${formatMentalValue(maxVal, unit)}]",
                                color = color,
                                style = MaterialTheme.typography.labelSmall
                            )
                        } else {
                            val avg = if (values.isNotEmpty()) values.average().toFloat() else 0f
                            Text(
                                text = "$label (avg: ${formatMentalValue(avg, unit)})",
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
                    val unit = MentalCardConfig.unitFor(metric)
                    val values: List<Float> = daysWithData.mapNotNull { getMentalDayValue(it, metric) }
                    val max = values.maxOrNull() ?: 1f
                    val min = values.minOrNull() ?: 0f
                    yTop = formatMentalValue(max, unit)
                    yMid = formatMentalValue((max + min) / 2, unit)
                    yBot = formatMentalValue(min, unit)
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
            for (metric in MentalCardConfig.GRAPHABLE_METRICS) {
                val isSelected = metric in selectedMetrics
                val chipColor = MentalCardConfig.colorFor(metric)
                val chipLabel = MentalCardConfig.labelFor(metric)

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
