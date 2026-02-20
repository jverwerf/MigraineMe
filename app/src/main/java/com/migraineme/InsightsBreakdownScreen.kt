package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController

/**
 * Drill-down screen: shows one SpiderData's categories,
 * each expanded into a subcategory spider chart.
 */
@Composable
fun InsightsBreakdownScreen(
    logType: String,
    navController: NavController,
    vm: InsightsViewModel = viewModel()
) {
    val spiderData = when (logType) {
        "Triggers" -> vm.triggerSpider.collectAsState().value
        "Prodromes" -> vm.prodromeSpider.collectAsState().value
        "Symptoms" -> vm.symptomSpider.collectAsState().value
        "Medicines" -> vm.medicineSpider.collectAsState().value
        "Reliefs" -> vm.reliefSpider.collectAsState().value
        "Activities" -> vm.activitySpider.collectAsState().value
        "Missed Activities" -> vm.missedActivitySpider.collectAsState().value
        "Locations" -> vm.locationSpider.collectAsState().value
        else -> null
    }

    val scrollState = rememberScrollState()

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Outlined.ArrowBack, "Back", tint = AppTheme.BodyTextColor)
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        logType,
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    if (spiderData != null) {
                        Text(
                            "${spiderData.totalLogged} logged across ${spiderData.breakdown.size} categories",
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (spiderData == null || spiderData.breakdown.isEmpty()) {
                BaseCard {
                    Text(
                        "No data yet — log items linked to a migraine to see breakdowns here.",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                return@ScrollableScreenContent
            }

            // Special layout for Symptoms
            if (logType == "Symptoms") {
                SymptomsBreakdownContent(vm = vm)
                return@ScrollableScreenContent
            }

            // Overall spider chart (categories)
            HeroCard {
                Text(
                    "Categories Overview",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(8.dp))

                if (spiderData.axes.size >= 3) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        SpiderChart(
                            axes = spiderData.axes,
                            accentColor = colorForLogType(logType),
                            size = 260.dp
                        )
                    }
                } else {
                    // 1–2 categories — stacked proportional bar
                    StackedProportionalBar(
                        axes = spiderData.axes,
                        accentColor = colorForLogType(logType)
                    )
                }
            }

            // Effectiveness by category (Medicines & Reliefs only) — dual spider
            if (logType == "Medicines" || logType == "Reliefs") {
                val effectiveness = if (logType == "Medicines")
                    vm.medicineEffectiveness.collectAsState().value
                else
                    vm.reliefEffectiveness.collectAsState().value

                if (effectiveness.isNotEmpty() && effectiveness.size >= 3) {
                    val accentCol = colorForLogType(logType)
                    val reliefCol = accentCol.copy(alpha = 0.5f)
                    BaseCard {
                        Text(
                            "Usage vs Effectiveness",
                            color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(Modifier.height(4.dp))

                        // Legend
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(accentCol))
                            Spacer(Modifier.width(6.dp))
                            Text("Count", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.width(16.dp))
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.6f)))
                            Spacer(Modifier.width(6.dp))
                            Text("Avg Relief", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        }

                        Spacer(Modifier.height(8.dp))

                        val countAxes = effectiveness.map {
                            SpiderAxis(label = it.category, value = it.count.toFloat())
                        }
                        val reliefAxes = effectiveness.map {
                            SpiderAxis(label = it.category, value = it.avgRelief, maxValue = 3f)
                        }

                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            SpiderChart(
                                axes = countAxes,
                                accentColor = accentCol,
                                size = 260.dp,
                                fillAlpha = 0.15f,
                                secondAxes = reliefAxes,
                                secondColor = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            // Per-category subcategory spider charts
            for (cat in spiderData.breakdown) {
                BaseCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                cat.categoryName,
                                color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                "${cat.totalCount} logged • ${cat.items.size} types",
                                color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        // Show category-level relief badge for meds/reliefs
                        if (logType == "Medicines" || logType == "Reliefs") {
                            val effectiveness = if (logType == "Medicines")
                                vm.medicineEffectiveness.collectAsState().value
                            else
                                vm.reliefEffectiveness.collectAsState().value
                            val catEff = effectiveness.find { it.category == cat.categoryName }
                            if (catEff != null) {
                                val reliefLabel = when {
                                    catEff.avgRelief >= 2.5f -> "High"
                                    catEff.avgRelief >= 1.5f -> "Mild"
                                    catEff.avgRelief >= 0.5f -> "Low"
                                    else -> "None"
                                }
                                val reliefColor = when {
                                    catEff.avgRelief >= 2.5f -> Color(0xFF81C784)
                                    catEff.avgRelief >= 1.5f -> Color(0xFFFFB74D)
                                    catEff.avgRelief >= 0.5f -> Color(0xFFE57373)
                                    else -> Color(0xFF666666)
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(reliefColor.copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(reliefLabel, color = reliefColor,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Build item-level relief axes for meds/reliefs
                    val itemEffMap = if (logType == "Medicines")
                        vm.medicineItemEffectiveness.collectAsState().value
                    else if (logType == "Reliefs")
                        vm.reliefItemEffectiveness.collectAsState().value
                    else emptyMap()

                    val subAxes = cat.items.map {
                        SpiderAxis(label = it.first, value = it.second.toFloat())
                    }
                    val subReliefAxes = if (itemEffMap.isNotEmpty() && (logType == "Medicines" || logType == "Reliefs")) {
                        cat.items.map {
                            SpiderAxis(label = it.first, value = itemEffMap[it.first.lowercase()] ?: 0f, maxValue = 3f)
                        }
                    } else null

                    if (cat.items.size >= 3) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            SpiderChart(
                                axes = subAxes,
                                accentColor = colorForLogType(logType),
                                size = 240.dp,
                                fillAlpha = 0.2f,
                                secondAxes = subReliefAxes,
                                secondColor = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    } else {
                        StackedProportionalBar(
                            axes = subAxes,
                            accentColor = colorForLogType(logType)
                        )
                        // Show relief per item for 1-2 items
                        if (subReliefAxes != null) {
                            Spacer(Modifier.height(8.dp))
                            for (i in cat.items.indices) {
                                val relief = itemEffMap[cat.items[i].first.lowercase()] ?: 0f
                                val rl = when {
                                    relief >= 2.5f -> "High"
                                    relief >= 1.5f -> "Mild"
                                    relief >= 0.5f -> "Low"
                                    else -> "None"
                                }
                                val rc = when {
                                    relief >= 2.5f -> Color(0xFF81C784)
                                    relief >= 1.5f -> Color(0xFFFFB74D)
                                    relief >= 0.5f -> Color(0xFFE57373)
                                    else -> Color(0xFF666666)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(cat.items[i].first, color = AppTheme.BodyTextColor,
                                        style = MaterialTheme.typography.bodySmall)
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(rc.copy(alpha = 0.2f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(rl, color = rc,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryBarRow(label: String, count: Int, maxCount: Int, color: Color) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                color = AppTheme.BodyTextColor,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                "$count",
                color = color,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (count.toFloat() / maxCount.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = color,
            trackColor = color.copy(alpha = 0.1f)
        )
    }
}

internal fun colorForLogType(logType: String): Color = when (logType) {
    "Triggers" -> Color(0xFFFF8A65)
    "Prodromes" -> Color(0xFFFFD54F)
    "Symptoms" -> Color(0xFFE57373)
    "Medicines" -> Color(0xFF4FC3F7)
    "Reliefs" -> Color(0xFF81C784)
    "Activities" -> Color(0xFFBA68C8)
    "Missed Activities" -> Color(0xFFFF7043)
    "Locations" -> Color(0xFF4DD0E1)
    else -> AppTheme.AccentPurple
}

@Composable
private fun SymptomsBreakdownContent(vm: InsightsViewModel) {
    val painCharSpider by vm.painCharSpider.collectAsState()
    val accompSpider by vm.accompSpider.collectAsState()
    val painLocSpider by vm.painLocationSpider.collectAsState()
    val severityCounts by vm.severityCounts.collectAsState()
    val durationStats by vm.durationStats.collectAsState()

    val painCharColor = Color(0xFFEF5350)
    val accompColor = Color(0xFFBA68C8)
    val painLocColor = Color(0xFFFF8A65)
    val severityColor = Color(0xFF4FC3F7)
    val durationColor = Color(0xFF81C784)

    // Pain Character
    if (painCharSpider != null && painCharSpider!!.axes.isNotEmpty()) {
        SpiderSection(title = "Pain Character", spider = painCharSpider!!, color = painCharColor)
    }

    // Accompanying Experience
    if (accompSpider != null && accompSpider!!.axes.isNotEmpty()) {
        SpiderSection(title = "Accompanying Experience", spider = accompSpider!!, color = accompColor)
    }

    // Pain Locations
    if (painLocSpider != null && painLocSpider!!.axes.isNotEmpty()) {
        SpiderSection(title = "Pain Locations", spider = painLocSpider!!, color = painLocColor)
    }

    // Severity Distribution
    if (severityCounts.isNotEmpty()) {
        val sevAxes = severityCounts.map { (sev, count) ->
            SpiderAxis(label = "Level $sev", value = count.toFloat())
        }
        val sevTotal = severityCounts.sumOf { it.second }
        val allSevValues = severityCounts.flatMap { (sev, count) -> List(count) { sev } }
        val sevMin = allSevValues.min()
        val sevMax = allSevValues.max()
        val sevAvg = allSevValues.average().toFloat()

        BaseCard {
            Text(
                "Severity Distribution",
                color = severityColor,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "$sevTotal migraines rated",
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SeverityStat(label = "Lowest", value = sevMin, color = Color(0xFF81C784))
                SeverityStat(label = "Average", value = sevAvg, color = severityColor)
                SeverityStat(label = "Highest", value = sevMax, color = Color(0xFFEF5350))
            }

            Spacer(Modifier.height(16.dp))

            if (sevAxes.size >= 3) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SpiderChart(axes = sevAxes, accentColor = severityColor, size = 220.dp, fillAlpha = 0.2f)
                }
            } else {
                StackedProportionalBar(axes = sevAxes, accentColor = severityColor)
            }
        }
    }

    // Duration Stats
    if (durationStats != null) {
        val stats = durationStats!!
        BaseCard {
            Text(
                "Duration",
                color = durationColor,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${stats.durations.size} migraines with end time",
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))

            // Stat cards row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                DurationStat(label = "Shortest", hours = stats.minHours, color = Color(0xFF81C784))
                DurationStat(label = "Average", hours = stats.avgHours, color = durationColor)
                DurationStat(label = "Longest", hours = stats.maxHours, color = Color(0xFFEF5350))
            }

            // Bucket distribution as spider/bar/card
            if (stats.durations.size > 1) {
                Spacer(Modifier.height(16.dp))

                val buckets = mutableMapOf<String, Int>()
                for (h in stats.durations) {
                    val bucket = when {
                        h < 1f -> "< 1h"
                        h < 4f -> "1-4h"
                        h < 12f -> "4-12h"
                        h < 24f -> "12-24h"
                        h < 48f -> "1 day"
                        h < 72f -> "2 days"
                        h < 96f -> "3 days"
                        h < 120f -> "4 days"
                        h < 144f -> "5 days"
                        h < 168f -> "6 days"
                        else -> "7+ days"
                    }
                    buckets[bucket] = (buckets[bucket] ?: 0) + 1
                }
                val orderedBuckets = listOf("< 1h", "1-4h", "4-12h", "12-24h", "1 day", "2 days", "3 days", "4 days", "5 days", "6 days", "7+ days")
                val durationAxes = orderedBuckets
                    .filter { (buckets[it] ?: 0) > 0 }
                    .map { SpiderAxis(label = it, value = (buckets[it] ?: 0).toFloat()) }

                if (durationAxes.size >= 3) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        SpiderChart(axes = durationAxes, accentColor = durationColor, size = 220.dp, fillAlpha = 0.2f)
                    }
                } else {
                    StackedProportionalBar(axes = durationAxes, accentColor = durationColor)
                }
            }
        }
    }
}

@Composable
private fun SpiderSection(title: String, spider: SpiderData, color: Color) {
    BaseCard {
        Text(
            title,
            color = color,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${spider.totalLogged} logged • ${spider.axes.size} types",
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))

        if (spider.axes.size >= 3) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                SpiderChart(axes = spider.axes, accentColor = color, size = 240.dp, fillAlpha = 0.2f)
            }
        } else if (spider.axes.size == 1) {
            StackedProportionalBar(axes = spider.axes, accentColor = color)
        } else {
            StackedProportionalBar(axes = spider.axes, accentColor = color)
        }
    }
}

@Composable
private fun DurationStat(label: String, hours: Float, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            formatDuration(hours),
            color = color,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            label,
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private fun formatDuration(hours: Float): String = when {
    hours < 1f -> "${(hours * 60).toInt()}m"
    hours < 24f -> "%.1fh".format(hours)
    else -> "%.1fd".format(hours / 24f)
}

@Composable
private fun SeverityStat(label: String, value: Number, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (value is Float) "%.1f".format(value) else "${value}",
            color = color,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            label,
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

