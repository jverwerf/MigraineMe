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
    // The day carries every nutrition metric now. The named fields below stay
    // as the fallback for the eight this screen was originally built around, so
    // a row that predates the generic read still plots.
    day.values[metric]?.let { return it }
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

// The selector's grouping: the same ALL_NUTRITION_METRICS, split into the
// families a person thinks in, because thirty-nine rows in one alphabetical
// list is a haystack. Migraine-relevant exposures lead, since those are the
// ones this app exists to correlate.
private val nutritionMetricGroups = listOf(
    "Trigger exposure" to listOf(
        MonitorCardConfig.METRIC_TYRAMINE_EXPOSURE,
        MonitorCardConfig.METRIC_HISTAMINE_EXPOSURE,
        MonitorCardConfig.METRIC_GLUTEN_EXPOSURE,
        MonitorCardConfig.METRIC_ALCOHOL_EXPOSURE,
        MonitorCardConfig.METRIC_CAFFEINE
    ),
    "Energy and macros" to listOf(
        MonitorCardConfig.METRIC_CALORIES,
        MonitorCardConfig.METRIC_PROTEIN,
        MonitorCardConfig.METRIC_CARBS,
        MonitorCardConfig.METRIC_SUGAR,
        MonitorCardConfig.METRIC_FIBER,
        MonitorCardConfig.METRIC_FAT,
        MonitorCardConfig.METRIC_SATURATED_FAT,
        MonitorCardConfig.METRIC_UNSATURATED_FAT,
        MonitorCardConfig.METRIC_TRANS_FAT,
        MonitorCardConfig.METRIC_CHOLESTEROL
    ),
    "Minerals" to listOf(
        MonitorCardConfig.METRIC_SODIUM,
        MonitorCardConfig.METRIC_POTASSIUM,
        MonitorCardConfig.METRIC_MAGNESIUM,
        MonitorCardConfig.METRIC_CALCIUM,
        MonitorCardConfig.METRIC_IRON,
        MonitorCardConfig.METRIC_ZINC,
        MonitorCardConfig.METRIC_PHOSPHORUS,
        MonitorCardConfig.METRIC_COPPER,
        MonitorCardConfig.METRIC_MANGANESE,
        MonitorCardConfig.METRIC_SELENIUM
    ),
    "Vitamins" to listOf(
        MonitorCardConfig.METRIC_VITAMIN_A,
        MonitorCardConfig.METRIC_VITAMIN_B6,
        MonitorCardConfig.METRIC_VITAMIN_B12,
        MonitorCardConfig.METRIC_VITAMIN_C,
        MonitorCardConfig.METRIC_VITAMIN_D,
        MonitorCardConfig.METRIC_VITAMIN_E,
        MonitorCardConfig.METRIC_VITAMIN_K,
        MonitorCardConfig.METRIC_THIAMIN,
        MonitorCardConfig.METRIC_RIBOFLAVIN,
        MonitorCardConfig.METRIC_NIACIN,
        MonitorCardConfig.METRIC_FOLATE,
        MonitorCardConfig.METRIC_PANTOTHENIC_ACID,
        MonitorCardConfig.METRIC_BIOTIN
    )
)

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
        val dateFmt = DateTimeFormatter.ofPattern("MMM d", appLocale())
        val rangeLabel = if (historyData.isNotEmpty()) {
            val from = try { LocalDate.parse(historyData.first().date).format(dateFmt) } catch (_: Exception) { historyData.first().date }
            val to = try { LocalDate.parse(historyData.last().date).format(dateFmt) } catch (_: Exception) { historyData.last().date }
            "$from – $to"
        } else ""

        // A metric's own average over the logged days. Feeds both the header
        // reading and the value column of the checklist.
        fun averageOf(metric: String): Float? {
            val values = daysWithLogs.map { getDayValue(it, metric) }
            return if (values.isEmpty()) null else values.average().toFloat()
        }
        fun labelOf(metric: String) = tSync(MonitorCardConfig.NUTRITION_METRIC_LABELS[metric] ?: metric)
        fun unitOf(metric: String) = MonitorCardConfig.NUTRITION_METRIC_UNITS[metric] ?: ""

        val singleMetric = selectedMetrics.singleOrNull()
        val subtitle: String
        val readout: String
        val readoutUnit: String
        val readoutColor: Color
        val readoutCaption: String
        if (singleMetric != null) {
            val avg = averageOf(singleMetric)
            subtitle = listOf(labelOf(singleMetric), rangeLabel).filter { it.isNotEmpty() }.joinToString(" · ")
            readout = if (avg != null) "${avg.toInt()}" else "-"
            readoutUnit = unitOf(singleMetric)
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
            title = t("Diet History"),
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
                        val key = metricKey(metric)
                        val unit = unitOf(metric)
                        Triple(
                            labelOf(metric),
                            "${(allTimeMin[key] ?: 0f).toInt()}–${(allTimeMax[key] ?: 1f).toInt()}$unit",
                            metricColors[metric] ?: AppTheme.AccentPurple
                        )
                    }
                )
            }

            Spacer(Modifier.height(10.dp))

            if (daysWithLogs.isEmpty()) {
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
                    val values = daysWithLogs.map { getDayValue(it, metric) }
                    val max = values.maxOrNull() ?: 1f
                    val min = values.minOrNull() ?: 0f
                    yTop = "${max.toInt()}"
                    yMid = "${((max + min) / 2).toInt()}"
                    yBot = "${min.toInt()}"
                }
                
                Row(modifier = Modifier.fillMaxWidth().height(168.dp)) {
                    // Graph canvas
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
                            
                            // Screen points once, then the shared curve: the same
                            // Catmull-Rom the Migraine Timeline draws.
                            val offsets = plotPoints.map { (dayIdx, normalizedValue) ->
                                Offset(
                                    padding + (dayIdx.toFloat() / (historyData.size - 1).coerceAtLeast(1)) * graphWidth,
                                    padding + graphHeight - (normalizedValue * graphHeight)
                                )
                            }

                            if (!isNormalized) {
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
                        Text(yTop, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        Text(yMid, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        Text(yBot, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    }
                }
                
                // Date labels
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = yAxisWidth),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(LocalDate.parse(historyData.first().date).format(dateFmt), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    Text(LocalDate.parse(historyData.last().date).format(dateFmt), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                }

                GraphKeyRow(
                    averageLabel = if (isNormalized) t("each line's average") else t("history average"),
                    averageColor = if (isNormalized) Color.White.copy(alpha = 0.55f)
                        else metricColors[selectedMetrics.first()] ?: AppTheme.AccentPurple,
                    showMigraineDays = migraineDates.isNotEmpty(),
                    showForecast = false
                )
            }
        }
        
        // Metric selector: grouped checklist, every metric carrying its own
        // average for the window.
        MetricChecklist(
            groups = nutritionMetricGroups.map { (group, metrics) ->
                group to metrics.map { metric ->
                    GraphMetricRow(
                        key = metric,
                        label = labelOf(metric),
                        value = averageOf(metric)?.let { "${it.toInt()}${unitOf(metric)}" },
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
