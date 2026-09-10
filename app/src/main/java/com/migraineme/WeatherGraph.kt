package com.migraineme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Metric colors
private val metricColors = mapOf(
    WeatherCardConfig.METRIC_TEMPERATURE to Color(0xFFFF7043),
    WeatherCardConfig.METRIC_PRESSURE to Color(0xFF42A5F5),
    WeatherCardConfig.METRIC_HUMIDITY to Color(0xFF26C6DA),
    WeatherCardConfig.METRIC_WIND_SPEED to Color(0xFF66BB6A),
    WeatherCardConfig.METRIC_UV_INDEX to Color(0xFFFFCA28),
    WeatherCardConfig.METRIC_ALTITUDE to Color(0xFFCE93D8),
    WeatherCardConfig.METRIC_ALTITUDE_CHANGE to Color(0xFFBA68C8)
)

// The selector's grouping. Same metrics, same order as
// WeatherCardConfig.ALL_WEATHER_METRICS, split into the three families they
// come from so a fifteen-row list stays readable.
private val weatherMetricGroups = listOf(
    "Weather" to listOf(
        WeatherCardConfig.METRIC_TEMPERATURE,
        WeatherCardConfig.METRIC_PRESSURE,
        WeatherCardConfig.METRIC_HUMIDITY,
        WeatherCardConfig.METRIC_WIND_SPEED,
        WeatherCardConfig.METRIC_UV_INDEX,
        WeatherCardConfig.METRIC_THUNDERSTORM
    ),
    "Location" to listOf(
        WeatherCardConfig.METRIC_ALTITUDE,
        WeatherCardConfig.METRIC_ALTITUDE_CHANGE
    ),
    "Air quality" to listOf(
        WeatherCardConfig.METRIC_POLLEN,
        WeatherCardConfig.METRIC_POLLEN_TREE,
        WeatherCardConfig.METRIC_POLLEN_GRASS,
        WeatherCardConfig.METRIC_POLLEN_WEED,
        WeatherCardConfig.METRIC_PM25,
        WeatherCardConfig.METRIC_PM10,
        WeatherCardConfig.METRIC_OZONE
    )
)

// Get metric value from day data
private fun getDayValue(day: WeatherDayData, metric: String): Float? {
    return when (metric) {
        WeatherCardConfig.METRIC_TEMPERATURE -> day.tempMean.toFloat()
        WeatherCardConfig.METRIC_PRESSURE -> day.pressureMean.toFloat()
        WeatherCardConfig.METRIC_HUMIDITY -> day.humidityMean.toFloat()
        WeatherCardConfig.METRIC_WIND_SPEED -> day.windSpeedMean.toFloat()
        WeatherCardConfig.METRIC_UV_INDEX -> day.uvIndexMax.toFloat()
        WeatherCardConfig.METRIC_ALTITUDE -> (day.altitudeMaxM ?: 0.0).toFloat()
        WeatherCardConfig.METRIC_ALTITUDE_CHANGE -> (day.altitudeChangeM ?: 0.0).toFloat()
        // Pollen + air quality can be absent for a covered day; null keeps the
        // point off the line rather than dropping it to zero.
        WeatherCardConfig.METRIC_POLLEN -> day.pollenOverall?.toFloat()
        WeatherCardConfig.METRIC_POLLEN_TREE -> day.pollenTree?.toFloat()
        WeatherCardConfig.METRIC_POLLEN_GRASS -> day.pollenGrass?.toFloat()
        WeatherCardConfig.METRIC_POLLEN_WEED -> day.pollenWeed?.toFloat()
        WeatherCardConfig.METRIC_PM25 -> day.pm25Mean?.toFloat()
        WeatherCardConfig.METRIC_PM10 -> day.pm10Mean?.toFloat()
        WeatherCardConfig.METRIC_OZONE -> day.ozoneMax?.toFloat()
        else -> 0f
    }
}

// Check if day has data (at least one metric is non-zero)
private fun hasData(day: WeatherDayData): Boolean {
    return day.tempMean != 0.0 || day.pressureMean != 0.0 || day.humidityMean != 0.0
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeatherHistoryGraph(
    days: Int = 14,
    endDate: java.time.LocalDate = java.time.LocalDate.now(),
    forecastStartDate: String? = null,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val weatherService = remember { WeatherHistoryService(context) }

    var historyData by remember { mutableStateOf<List<WeatherDayData>>(emptyList()) }
    var allTimeMin by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var allTimeMax by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedMetrics by remember { mutableStateOf(setOf(WeatherCardConfig.METRIC_TEMPERATURE)) }
    var migraineDates by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Load history data
    LaunchedEffect(days, endDate) {
        scope.launch {
            val result = weatherService.getWeatherHistory(days, endDate)
            historyData = result.days
            allTimeMin = result.allTimeMin
            allTimeMax = result.allTimeMax
            migraineDates = MigraineOverlayHelper.fetchMigraineDates(context, days, endDate)
            isLoading = false
        }
    }

    val isNormalized = selectedMetrics.size >= 2
    val daysWithData = historyData.filter { hasData(it) }

    val title = if (forecastStartDate != null) t("History + Forecast") else t("%s-Day History", days)

    BaseCard(modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier) {
        val dateFmt = DateTimeFormatter.ofPattern("MMM d", appLocale())
        val rangeLabel = if (historyData.isNotEmpty()) {
            val from = LocalDate.parse(historyData.first().date).format(dateFmt)
            val to = LocalDate.parse(historyData.last().date).format(dateFmt)
            "$from – $to"
        } else ""

        // A metric's own average over the days that carry data. Feeds both the
        // header reading and the value column of the checklist.
        fun averageOf(metric: String): Float? {
            val values = daysWithData.mapNotNull { getDayValue(it, metric) }
            return if (values.isEmpty()) null else values.average().toFloat()
        }

        // What a checklist row shows on the right. Thunderstorm is a flag, so
        // it counts days rather than averaging to a meaningless decimal, and a
        // missing altitude stays "-" instead of the 0.0 getDayValue coerces to.
        val stormDays = daysWithData.count { it.isThunderstormDay }
        val stormDaysText = if (stormDays == 0) null else t("%s days", stormDays)

        fun rowValue(metric: String): String? {
            val unit = WeatherCardConfig.WEATHER_METRIC_UNITS[metric] ?: ""
            return when (metric) {
                WeatherCardConfig.METRIC_THUNDERSTORM -> stormDaysText
                WeatherCardConfig.METRIC_ALTITUDE -> {
                    val values = daysWithData.mapNotNull { it.altitudeMaxM?.toFloat() }
                    if (values.isEmpty()) null else formatValue(values.average().toFloat(), unit)
                }
                WeatherCardConfig.METRIC_ALTITUDE_CHANGE -> {
                    val values = daysWithData.mapNotNull { it.altitudeChangeM?.toFloat() }
                    if (values.isEmpty()) null else formatValue(values.average().toFloat(), unit)
                }
                else -> averageOf(metric)?.let { formatValue(it, unit) }
            }
        }

        val singleMetric = selectedMetrics.singleOrNull()

        // Header. One metric reads as its own average in its own unit; two or
        // more share a normalised plot, where no single number is honest, so
        // the reading states the scale instead.
        val subtitle: String
        val readout: String
        val readoutUnit: String
        val readoutColor: Color
        val readoutCaption: String
        if (singleMetric != null) {
            val label = tSync(WeatherCardConfig.WEATHER_METRIC_LABELS[singleMetric] ?: singleMetric)
            val unit = WeatherCardConfig.WEATHER_METRIC_UNITS[singleMetric] ?: ""
            val avg = averageOf(singleMetric)
            subtitle = listOf(t(label), rangeLabel).filter { it.isNotEmpty() }.joinToString(" · ")
            readout = if (avg != null) formatValue(avg, "") else "-"
            readoutUnit = unit
            readoutColor = metricColors[singleMetric] ?: AppTheme.AccentPurple
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
            title = title,
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
        } else if (historyData.isEmpty()) {
            Text(
                t("No data available"),
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().height(150.dp).padding(vertical = 60.dp),
                textAlign = TextAlign.Center
            )
        } else if (selectedMetrics.isEmpty()) {
            Text(
                t("Select a metric below"),
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
                        val unit = WeatherCardConfig.WEATHER_METRIC_UNITS[metric] ?: ""
                        val label = tSync(WeatherCardConfig.WEATHER_METRIC_LABELS[metric] ?: metric)
                        Triple(
                            t(label),
                            "${formatValue(allTimeMin[metric] ?: 0f, unit)}–${formatValue(allTimeMax[metric] ?: 1f, unit)}",
                            metricColors[metric] ?: AppTheme.AccentPurple
                        )
                    }
                )
            }

            Spacer(Modifier.height(10.dp))

            if (daysWithData.isEmpty()) {
                Text(
                    t("No logged days in this period"),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                val yAxisWidth = 44.dp

                // Y-axis values depend on normalization
                val yTop: String
                val yMid: String
                val yBot: String

                if (isNormalized) {
                    yTop = "1.0"
                    yMid = "0.5"
                    yBot = "0.0"
                } else {
                    val metric = selectedMetrics.first()
                    val values = daysWithData.mapNotNull { getDayValue(it, metric) }
                    val max = values.maxOrNull() ?: 1f
                    val min = values.minOrNull() ?: 0f
                    yTop = formatValue(max, "")
                    yMid = formatValue((max + min) / 2, "")
                    yBot = formatValue(min, "")
                }

                val forecastIdx = if (forecastStartDate != null) {
                    historyData.indexOfFirst { it.date >= forecastStartDate }
                } else -1

                Row(modifier = Modifier.fillMaxWidth().height(168.dp)) {
                    // Graph canvas. The y labels sit on the right so the line
                    // starts at the card edge instead of behind a gutter.
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val padding = 8.dp.toPx()
                            val graphWidth = size.width - padding * 2
                            val graphHeight = size.height - padding * 2
                            val dashWidth = 6.dp.toPx()
                            val gapWidth = 4.dp.toPx()

                            // Gridlines at the three y values
                            listOf(0f, 0.5f, 1f).forEach { f ->
                                val y = padding + graphHeight * f
                                drawLine(
                                    Color.White.copy(alpha = 0.07f),
                                    Offset(padding, y),
                                    Offset(size.width - padding, y),
                                    strokeWidth = 1.dp.toPx()
                                )
                            }

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

                            // Forecast zone and the divider that opens it
                            if (forecastIdx > 0) {
                                val divX = padding + ((forecastIdx - 0.5f) / (historyData.size - 1).coerceAtLeast(1)) * graphWidth
                                drawRect(
                                    Color(0xFF4FC3F7).copy(alpha = 0.10f),
                                    topLeft = Offset(divX, padding),
                                    size = androidx.compose.ui.geometry.Size(size.width - padding - divX, graphHeight)
                                )
                                var yPos = padding
                                while (yPos < padding + graphHeight) {
                                    drawLine(
                                        Color.White.copy(alpha = 0.4f),
                                        Offset(divX, yPos),
                                        Offset(divX, (yPos + dashWidth).coerceAtMost(padding + graphHeight)),
                                        strokeWidth = 1.5.dp.toPx()
                                    )
                                    yPos += dashWidth + gapWidth
                                }
                            }

                            selectedMetrics.forEach { metric ->
                                val color = metricColors[metric] ?: Color.White

                                // Get indexed values for X positioning
                                val indexedValues = historyData.mapIndexedNotNull { i, day ->
                                    if (hasData(day)) getDayValue(day, metric)?.let { i to it } else null
                                }

                                if (indexedValues.isEmpty()) return@forEach

                                val values = indexedValues.map { it.second }

                                // For single metric: use window min/max (actual values)
                                // For multi metric: use all-time min/max (normalized)
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

                                // Normalize values to 0-1 scale for plotting
                                val plotPoints = indexedValues.map { (idx, value) ->
                                    idx to ((value - minVal) / range).coerceIn(0f, 1f)
                                }

                                fun xOf(dayIdx: Int) =
                                    padding + (dayIdx.toFloat() / (historyData.size - 1).coerceAtLeast(1)) * graphWidth
                                fun yOf(normalized: Float) = padding + graphHeight - (normalized * graphHeight)

                                // Screen points once, then the curve: smoothPath is
                                // the same Catmull-Rom the Migraine Timeline draws, so
                                // a line reads the same wherever it appears.
                                val offsets = plotPoints.map { (dayIdx, v) -> Offset(xOf(dayIdx), yOf(v)) }

                                // Soft fill under a single line, so the plot has
                                // some depth without another colour in play.
                                if (!isNormalized && plotPoints.size > 1) {
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

                                // Draw dotted average line (always show)
                                // When forecast is shown, only average history points (not forecast)
                                if (plotPoints.isNotEmpty()) {
                                    val avgPoints = if (forecastIdx > 0) {
                                        plotPoints.filter { it.first < forecastIdx }.ifEmpty { plotPoints }
                                    } else plotPoints
                                    val avgNormalized = avgPoints.map { it.second }.average().toFloat()
                                    val avgY = yOf(avgNormalized)

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

                                // Draw line: a wide, faint pass under a solid one,
                                // the same double stroke the timeline uses to keep a
                                // thin curve legible on the dark card.
                                if (plotPoints.size > 1) {
                                    val path = smoothPath(offsets)
                                    drawPath(
                                        path,
                                        color.copy(alpha = 0.12f),
                                        style = Stroke(
                                            (if (isNormalized) 5f else 6f).dp.toPx(),
                                            cap = StrokeCap.Round,
                                            join = StrokeJoin.Round
                                        )
                                    )
                                    drawPath(
                                        path,
                                        color,
                                        style = Stroke(
                                            (if (isNormalized) 1.9f else 2.2f).dp.toPx(),
                                            cap = StrokeCap.Round,
                                            join = StrokeJoin.Round
                                        )
                                    )
                                }

                                // Draw dots. Forecast days sit at lower opacity
                                // so a prediction never reads as a measurement.
                                plotPoints.forEach { (dayIdx, normalizedValue) ->
                                    val isForecast = forecastIdx > 0 && dayIdx >= forecastIdx
                                    drawCircle(
                                        color.copy(alpha = if (isForecast) 0.55f else 1f),
                                        (if (isNormalized) 2.6f else 3.2f).dp.toPx(),
                                        Offset(xOf(dayIdx), yOf(normalizedValue))
                                    )
                                }
                            }
                        }

                        if (forecastIdx > 0) {
                            Text(
                                t("Forecast"),
                                color = Color(0xFF4FC3F7),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 4.dp, end = 8.dp)
                            )
                        }
                    }

                    // Y-axis labels
                    Column(
                        modifier = Modifier.width(yAxisWidth).fillMaxHeight().padding(start = 6.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(yTop, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        Text(yMid, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        Text(yBot, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    }
                }

                // Date labels, with today marked where the forecast opens.
                Spacer(Modifier.height(4.dp))
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(end = yAxisWidth)) {
                    Text(
                        LocalDate.parse(historyData.first().date).format(dateFmt),
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                    if (forecastIdx > 0) {
                        val fraction = (forecastIdx - 0.5f) / (historyData.size - 1).coerceAtLeast(1)
                        Text(
                            t("today"),
                            color = AppTheme.SubtleTextColor.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .offset(x = maxWidth * fraction - 14.dp)
                        )
                    }
                    Text(
                        LocalDate.parse(historyData.last().date).format(dateFmt),
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }

                GraphKeyRow(
                    averageLabel = if (isNormalized) t("each line's average") else t("history average"),
                    averageColor = if (isNormalized) Color.White.copy(alpha = 0.55f)
                        else metricColors[selectedMetrics.first()] ?: AppTheme.AccentPurple,
                    showMigraineDays = migraineDates.isNotEmpty(),
                    showForecast = forecastIdx > 0
                )
            }
        }

        // Metric selector: grouped checklist, every metric carrying its own
        // value for the window.
        MetricChecklist(
            groups = weatherMetricGroups.map { (group, metrics) ->
                group to metrics.map { metric ->
                    GraphMetricRow(
                        key = metric,
                        label = t(tSync(WeatherCardConfig.WEATHER_METRIC_LABELS[metric] ?: metric)),
                        value = rowValue(metric),
                        color = metricColors[metric] ?: AppTheme.AccentPurple
                    )
                }
            },
            selected = selectedMetrics,
            onToggle = { metric ->
                selectedMetrics = if (metric in selectedMetrics) selectedMetrics - metric
                    else selectedMetrics + metric
            }
        )
    }
}

private fun formatValue(value: Float, unit: String): String {
    return when (unit) {
        "hPa" -> "${value.toInt()}$unit"
        "%" -> "${value.toInt()}$unit"
        else -> String.format("%.1f%s", value, unit)
    }
}


