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
    MonitorCardConfig.METRIC_CALORIES to Color(0xFFFFB74D),
    MonitorCardConfig.METRIC_PROTEIN to Color(0xFF81C784),
    MonitorCardConfig.METRIC_CARBS to Color(0xFF64B5F6),
    MonitorCardConfig.METRIC_FAT to Color(0xFFE57373),
    MonitorCardConfig.METRIC_FIBER to Color(0xFFBA68C8),
    MonitorCardConfig.METRIC_SUGAR to Color(0xFFFF8A65),
    MonitorCardConfig.METRIC_SODIUM to Color(0xFF4DD0E1),
    MonitorCardConfig.METRIC_CAFFEINE to Color(0xFFAED581),
    MonitorCardConfig.METRIC_CHOLESTEROL to Color(0xFFFFD54F),
    MonitorCardConfig.METRIC_SATURATED_FAT to Color(0xFFEF5350),
    MonitorCardConfig.METRIC_UNSATURATED_FAT to Color(0xFF66BB6A),
    MonitorCardConfig.METRIC_TRANS_FAT to Color(0xFFEC407A),
    MonitorCardConfig.METRIC_POTASSIUM to Color(0xFF26C6DA),
    MonitorCardConfig.METRIC_CALCIUM to Color(0xFFAB47BC),
    MonitorCardConfig.METRIC_IRON to Color(0xFF8D6E63),
    MonitorCardConfig.METRIC_MAGNESIUM to Color(0xFF5C6BC0),
    MonitorCardConfig.METRIC_ZINC to Color(0xFF78909C),
    MonitorCardConfig.METRIC_VITAMIN_A to Color(0xFFFFA726),
    MonitorCardConfig.METRIC_VITAMIN_C to Color(0xFFFFEE58),
    MonitorCardConfig.METRIC_VITAMIN_D to Color(0xFFFFCA28),
    MonitorCardConfig.METRIC_VITAMIN_E to Color(0xFF9CCC65),
    MonitorCardConfig.METRIC_VITAMIN_K to Color(0xFF26A69A),
    MonitorCardConfig.METRIC_VITAMIN_B6 to Color(0xFF7E57C2),
    MonitorCardConfig.METRIC_VITAMIN_B12 to Color(0xFFEC407A),
    MonitorCardConfig.METRIC_THIAMIN to Color(0xFF42A5F5),
    MonitorCardConfig.METRIC_RIBOFLAVIN to Color(0xFF66BB6A),
    MonitorCardConfig.METRIC_NIACIN to Color(0xFFFFCA28),
    MonitorCardConfig.METRIC_FOLATE to Color(0xFF26C6DA),
    MonitorCardConfig.METRIC_BIOTIN to Color(0xFFAB47BC),
    MonitorCardConfig.METRIC_PANTOTHENIC_ACID to Color(0xFF8D6E63),
    MonitorCardConfig.METRIC_PHOSPHORUS to Color(0xFF5C6BC0),
    MonitorCardConfig.METRIC_SELENIUM to Color(0xFF78909C),
    MonitorCardConfig.METRIC_COPPER to Color(0xFFD4E157),
    MonitorCardConfig.METRIC_MANGANESE to Color(0xFF29B6F6)
)

// Get metric value from day data
private fun getDayValue(day: NutritionDayData, metric: String): Float {
    return when (metric) {
        MonitorCardConfig.METRIC_CALORIES -> day.calories.toFloat()
        MonitorCardConfig.METRIC_PROTEIN -> day.protein.toFloat()
        MonitorCardConfig.METRIC_CARBS -> day.carbs.toFloat()
        MonitorCardConfig.METRIC_FAT -> day.fat.toFloat()
        MonitorCardConfig.METRIC_FIBER -> day.fiber.toFloat()
        MonitorCardConfig.METRIC_SUGAR -> day.sugar.toFloat()
        MonitorCardConfig.METRIC_SODIUM -> day.sodium.toFloat()
        MonitorCardConfig.METRIC_CAFFEINE -> day.caffeine.toFloat()
        else -> 0f
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NutritionHistoryGraph(
    days: Int = 14,
    endDate: java.time.LocalDate = java.time.LocalDate.now(),
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val searchService = remember { USDAFoodSearchService(context) }
    
    var historyData by remember { mutableStateOf<List<NutritionDayData>>(emptyList()) }
    var allTimeMin by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var allTimeMax by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedMetrics by remember { mutableStateOf(setOf(MonitorCardConfig.METRIC_CALORIES)) }
    var migraineDates by remember { mutableStateOf<Set<String>>(emptySet()) }
    
    // Load history data
    LaunchedEffect(days, endDate) {
        scope.launch {
            val result = searchService.getNutritionHistory(days, endDate)
            historyData = result.days
            allTimeMin = result.allTimeMin
            allTimeMax = result.allTimeMax
            migraineDates = MigraineOverlayHelper.fetchMigraineDates(context, days, endDate)
            isLoading = false
        }
    }
    
    val isNormalized = selectedMetrics.size >= 2
    val daysWithLogs = historyData.filter { it.calories > 0 }
    
    // Helper to get metric key for allTime lookups
    fun metricKey(m: String) = when (m) {
        MonitorCardConfig.METRIC_CALORIES -> "calories"
        MonitorCardConfig.METRIC_PROTEIN -> "protein"
        MonitorCardConfig.METRIC_CARBS -> "carbs"
        MonitorCardConfig.METRIC_FAT -> "fat"
        MonitorCardConfig.METRIC_FIBER -> "fiber"
        MonitorCardConfig.METRIC_SUGAR -> "sugar"
        MonitorCardConfig.METRIC_SODIUM -> "sodium"
        MonitorCardConfig.METRIC_CAFFEINE -> "caffeine"
        else -> "calories"
    }
    
    BaseCard(modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier) {
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
                    val label = MonitorCardConfig.NUTRITION_METRIC_LABELS[metric] ?: metric
                    val unit = MonitorCardConfig.NUTRITION_METRIC_UNITS[metric] ?: ""
                    val values = daysWithLogs.map { getDayValue(it, metric) }
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(8.dp)) { drawCircle(color) }
                        Spacer(Modifier.width(4.dp))
                        if (isNormalized) {
                            val key = metricKey(metric)
                            val minVal = allTimeMin[key] ?: 0f
                            val maxVal = allTimeMax[key] ?: 1f
                            Text("$label [${minVal.toInt()}-${maxVal.toInt()}$unit]", color = color, style = MaterialTheme.typography.labelSmall)
                        } else {
                            val avg = values.average().toInt()
                            Text("$label (avg: $avg$unit)", color = color, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            
            if (isNormalized) {
                Spacer(Modifier.height(4.dp))
                Text("⚠️ Normalized 0-1 scale • Dotted = last $days days avg", color = Color(0xFFFFB74D), style = MaterialTheme.typography.labelSmall)
            } else if (daysWithLogs.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Dotted line = last $days days average", color = AppTheme.SubtleTextColor.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }

            if (migraineDates.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(8.dp)) { drawRect(Color(0xFFE57373).copy(alpha = 0.35f)) }
                    Spacer(Modifier.width(4.dp))
                    Text("Red bands = migraine days", color = Color(0xFFE57373), style = MaterialTheme.typography.labelSmall)
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            if (daysWithLogs.isEmpty()) {
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
                    val unit = MonitorCardConfig.NUTRITION_METRIC_UNITS[metric] ?: ""
                    val values = daysWithLogs.map { getDayValue(it, metric) }
                    val max = values.maxOrNull() ?: 1f
                    val min = values.minOrNull() ?: 0f
                    yTop = "${max.toInt()}$unit"
                    yMid = "${((max + min) / 2).toInt()}$unit"
                    yBot = "${min.toInt()}$unit"
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
                        
                        selectedMetrics.forEach { metric ->
                            val color = metricColors[metric] ?: Color.White
                            
                            // Get indexed values for X positioning
                            val indexedValues = historyData.mapIndexedNotNull { i, day ->
                                if (day.calories > 0) i to getDayValue(day, metric) else null
                            }
                            
                            if (indexedValues.isEmpty()) return@forEach
                            
                            val values = indexedValues.map { it.second }
                            
                            // For single metric: use window min/max (actual values)
                            // For multi metric: use all-time min/max (normalized)
                            val minVal: Float
                            val maxVal: Float
                            if (isNormalized) {
                                val key = metricKey(metric)
                                minVal = allTimeMin[key] ?: 0f
                                maxVal = (allTimeMax[key] ?: 1f).coerceAtLeast(minVal + 1f)
                            } else {
                                minVal = values.minOrNull() ?: 0f
                                maxVal = (values.maxOrNull() ?: 1f).coerceAtLeast(minVal + 1f)
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
            MonitorCardConfig.ALL_NUTRITION_METRICS.forEach { metric ->
                val isSelected = metric in selectedMetrics
                val chipColor = metricColors[metric] ?: AppTheme.AccentPurple
                val chipLabel = MonitorCardConfig.NUTRITION_METRIC_LABELS[metric] ?: metric
                
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

