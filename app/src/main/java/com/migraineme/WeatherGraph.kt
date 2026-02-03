package com.migraineme

import androidx.compose.foundation.Canvas
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
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Metric colors
private val metricColors = mapOf(
    WeatherCardConfig.METRIC_TEMPERATURE to Color(0xFFFF7043),
    WeatherCardConfig.METRIC_PRESSURE to Color(0xFF42A5F5),
    WeatherCardConfig.METRIC_HUMIDITY to Color(0xFF26C6DA),
    WeatherCardConfig.METRIC_WIND_SPEED to Color(0xFF66BB6A),
    WeatherCardConfig.METRIC_UV_INDEX to Color(0xFFFFCA28)
)

// Get metric value from day data
private fun getDayValue(day: WeatherDayData, metric: String): Float {
    return when (metric) {
        WeatherCardConfig.METRIC_TEMPERATURE -> day.tempMean.toFloat()
        WeatherCardConfig.METRIC_PRESSURE -> day.pressureMean.toFloat()
        WeatherCardConfig.METRIC_HUMIDITY -> day.humidityMean.toFloat()
        WeatherCardConfig.METRIC_WIND_SPEED -> day.windSpeedMean.toFloat()
        WeatherCardConfig.METRIC_UV_INDEX -> day.uvIndexMax.toFloat()
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
    days: Int = 14
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val weatherService = remember { WeatherHistoryService(context) }

    var historyData by remember { mutableStateOf<List<WeatherDayData>>(emptyList()) }
    var allTimeMin by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var allTimeMax by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedMetrics by remember { mutableStateOf(setOf(WeatherCardConfig.METRIC_TEMPERATURE)) }

    // Load history data
    LaunchedEffect(Unit) {
        scope.launch {
            val result = weatherService.getWeatherHistory(days)
            historyData = result.days
            allTimeMin = result.allTimeMin
            allTimeMax = result.allTimeMax
            isLoading = false
        }
    }

    val isNormalized = selectedMetrics.size >= 2
    val daysWithData = historyData.filter { hasData(it) }

    BaseCard {
        Text(
            "$days-Day History",
            color = AppTheme.TitleColor,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
        )

        Spacer(Modifier.height(8.dp))

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
                "No data available",
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().height(150.dp).padding(vertical = 60.dp),
                textAlign = TextAlign.Center
            )
        } else if (selectedMetrics.isEmpty()) {
            Text(
                "Select a metric below",
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
                selectedMetrics.forEach { metric ->
                    val color = metricColors[metric] ?: AppTheme.AccentPurple
                    val label = WeatherCardConfig.WEATHER_METRIC_LABELS[metric] ?: metric
                    val unit = WeatherCardConfig.WEATHER_METRIC_UNITS[metric] ?: ""
                    val values = daysWithData.map { getDayValue(it, metric) }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(8.dp)) { drawCircle(color) }
                        Spacer(Modifier.width(4.dp))
                        if (isNormalized) {
                            val minVal = allTimeMin[metric] ?: 0f
                            val maxVal = allTimeMax[metric] ?: 1f
                            Text("$label [${formatValue(minVal, unit)}-${formatValue(maxVal, unit)}]", color = color, style = MaterialTheme.typography.labelSmall)
                        } else {
                            val avg = if (values.isNotEmpty()) values.average().toFloat() else 0f
                            Text("$label (avg: ${formatValue(avg, unit)})", color = color, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            if (isNormalized) {
                Spacer(Modifier.height(4.dp))
                Text("⚠️ Normalized 0-1 scale • Dotted = last $days days avg", color = Color(0xFFFFB74D), style = MaterialTheme.typography.labelSmall)
            } else if (daysWithData.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Dotted line = last $days days average", color = AppTheme.SubtleTextColor.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(8.dp))

            if (daysWithData.isEmpty()) {
                Text(
                    "No logged days in this period",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                val yAxisWidth = 50.dp

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
                    val unit = WeatherCardConfig.WEATHER_METRIC_UNITS[metric] ?: ""
                    val values = daysWithData.map { getDayValue(it, metric) }
                    val max = values.maxOrNull() ?: 1f
                    val min = values.minOrNull() ?: 0f
                    yTop = formatValue(max, unit)
                    yMid = formatValue((max + min) / 2, unit)
                    yBot = formatValue(min, unit)
                }

                Row(modifier = Modifier.fillMaxWidth().height(150.dp)) {
                    // Y-axis labels
                    Column(
                        modifier = Modifier.width(yAxisWidth).fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(yTop, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        Text(yMid, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        Text(yBot, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    }

                    // Graph canvas
                    Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        val padding = 8.dp.toPx()
                        val graphWidth = size.width - padding * 2
                        val graphHeight = size.height - padding * 2
                        val dashWidth = 6.dp.toPx()
                        val gapWidth = 4.dp.toPx()

                        selectedMetrics.forEach { metric ->
                            val color = metricColors[metric] ?: Color.White

                            // Get indexed values for X positioning
                            val indexedValues = historyData.mapIndexedNotNull { i, day ->
                                if (hasData(day)) i to getDayValue(day, metric) else null
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

                            // Draw dotted average line (always show)
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

                            // Draw line
                            if (plotPoints.size > 1) {
                                val path = Path()
                                plotPoints.forEachIndexed { i, (dayIdx, normalizedValue) ->
                                    val x = padding + (dayIdx.toFloat() / (historyData.size - 1).coerceAtLeast(1)) * graphWidth
                                    val y = padding + graphHeight - (normalizedValue * graphHeight)
                                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }

                            // Draw dots
                            plotPoints.forEach { (dayIdx, normalizedValue) ->
                                val x = padding + (dayIdx.toFloat() / (historyData.size - 1).coerceAtLeast(1)) * graphWidth
                                val y = padding + graphHeight - (normalizedValue * graphHeight)
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
                    Text(LocalDate.parse(historyData.first().date).format(fmt), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    Text(LocalDate.parse(historyData.last().date).format(fmt), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Metric selector - multi-select
        Text(
            "Select Metrics${if (selectedMetrics.size > 1) " (${selectedMetrics.size} selected)" else ""}",
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.height(8.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            WeatherCardConfig.ALL_WEATHER_METRICS.forEach { metric ->
                val isSelected = metric in selectedMetrics
                val chipColor = metricColors[metric] ?: AppTheme.AccentPurple
                val chipLabel = WeatherCardConfig.WEATHER_METRIC_LABELS[metric] ?: metric

                FilterChip(
                    selected = isSelected,
                    onClick = {
                        selectedMetrics = if (isSelected) {
                            selectedMetrics - metric
                        } else {
                            selectedMetrics + metric
                        }
                    },
                    label = { Text(chipLabel, style = MaterialTheme.typography.labelSmall) },
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

private fun formatValue(value: Float, unit: String): String {
    return when (unit) {
        "hPa" -> "${value.toInt()}$unit"
        "%" -> "${value.toInt()}$unit"
        else -> String.format("%.1f%s", value, unit)
    }
}