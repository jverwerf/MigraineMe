package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.ZoneId

private val CAT_COLORS = mapOf(
    "Sleep" to Color(0xFF7E57C2), "Weather" to Color(0xFF4FC3F7),
    "Physical" to Color(0xFF81C784), "Mental" to Color(0xFFBA68C8), "Nutrition" to Color(0xFFFFB74D)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorRiskScreen(
    navController: NavController,
    authVm: AuthViewModel = viewModel()
) {
    val ctx = LocalContext.current.applicationContext
    val authState by authVm.state.collectAsState()
    val scrollState = rememberScrollState()
    val today = remember { LocalDate.now(ZoneId.systemDefault()).toString() }

    // Risk data
    var riskLive by remember { mutableStateOf<SupabaseDbService.RiskScoreLiveRow?>(null) }
    var riskHistory by remember { mutableStateOf<List<SupabaseDbService.RiskScoreDailyRow>>(emptyList()) }
    var migraineDates by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Category data for resolving fav values
    var weatherSummary by remember { mutableStateOf<WeatherSummary?>(null) }
    var physicalSummary by remember { mutableStateOf<PhysicalSummary?>(null) }
    var sleepSummary by remember { mutableStateOf<SleepSummary?>(null) }
    var mentalSummary by remember { mutableStateOf<MentalSummary?>(null) }
    var nutritionItems by remember { mutableStateOf<List<NutritionLogItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Fav-of-favs (auto-picks 3 if none saved)
    val effectiveFavs = remember { getEffectiveFavOfFavs(ctx) }
    val favPool = remember { buildFavoritesPool(ctx) }


    LaunchedEffect(authState.accessToken) {
        val token = authState.accessToken
        if (token.isNullOrBlank()) { isLoading = false; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            try {
                val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                riskLive = db.getRiskScoreLive(token)
                riskHistory = db.getRiskScoreDaily(token, 14).sortedBy { it.date }
                val migraines = db.getMigraines(token)
                migraineDates = migraines.map { it.startAt.take(10) }.toSet()
            } catch (_: Exception) {}
            try { weatherSummary = loadWeatherSummary(ctx, token, today) } catch (_: Exception) {}
            try { physicalSummary = loadPhysicalSummary(ctx, token, today) } catch (_: Exception) {}
            try { sleepSummary = loadSleepSummary(ctx, token, today) } catch (_: Exception) {}
            try { mentalSummary = loadMentalSummary(ctx, token, today) } catch (_: Exception) {}
            try { nutritionItems = USDAFoodSearchService(ctx).getTodayNutritionItems() } catch (_: Exception) {}
            isLoading = false
        }
    }

    val parsedTriggers = remember(riskLive) { parseTopTriggersFromJson(riskLive?.topTriggers) }

    // Resolve any namespaced key to display value
    fun resolveValue(key: String): String {
        val parts = key.split(":", limit = 2)
        if (parts.size != 2) return "\u2014"
        val (cat, metric) = parts
        return when (cat) {
            "sleep" -> sleepSummary?.let { sleepMetricDisplayValue(it, metric) } ?: "\u2014"
            "weather" -> weatherSummary?.let { getWeatherMetricValue(it, metric) } ?: "\u2014"
            "physical" -> physicalSummary?.let { physicalMetricDisplayValue(it, metric) } ?: "\u2014"
            "mental" -> mentalSummary?.displayValue(metric) ?: "\u2014"
            "nutrition" -> {
                if (nutritionItems.isEmpty()) return "\u2014"
                val total = nutritionItems.sumOf { it.metricValue(metric) ?: 0.0 }
                if (total <= 0) return "\u2014"
                val registryKey = MetricRegistry.nutritionRegistryKey(metric)
                val unit = MetricRegistry.unit(registryKey)
                val isRisk = metric in setOf("tyramine_exposure", "alcohol_exposure", "gluten_exposure")
                if (isRisk) {
                    when { total >= 3 -> "High"; total >= 2 -> "Med"; total >= 1 -> "Low"; else -> "None" }
                } else if (total >= 10) "${total.toInt()}$unit" else String.format("%.1f$unit", total)
            }
            else -> "\u2014"
        }
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
            // Back
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
            }

            // 1. Configure HeroCard
            HeroCard(modifier = Modifier.clickable { navController.navigate(Routes.RISK_CONFIG) }) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, "Configure", tint = AppTheme.AccentPurple, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Customize Monitor Card", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Text("Choose 3 favorites for the Risk card on Monitor", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("\u2192", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
                }
            }

            // 3. Today's Data card — mirrors main card layout
            BaseCard {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (PremiumManager.isPremium) navController.navigate(Routes.RISK_DATA_HISTORY)
                        else navController.navigate(Routes.PAYWALL)
                    },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Today's Data", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    if (PremiumManager.isPremium) {
                        Text("History \u2192", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Outlined.Lock, "Premium", tint = AppTheme.AccentPurple, modifier = Modifier.size(14.dp))
                            Text("History", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (isLoading) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AppTheme.AccentPurple, strokeWidth = 2.dp)
                    }
                } else {
                    // Risk's own data (Score / Zone / Triggers) — same as main card
                    val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))
                    if (riskLive != null) {
                        val live = riskLive!!
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("%.1f".format(live.score), color = slotColors[0], style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                Text("Score", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(live.zone.lowercase().replaceFirstChar { it.uppercase() }, color = slotColors[1], style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                Text("Zone", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${parsedTriggers.size}", color = slotColors[2], style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                Text("Triggers", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        // Fav-of-favs beneath risk data
                        if (effectiveFavs.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.15f))
                            Spacer(Modifier.height(6.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                effectiveFavs.take(3).forEachIndexed { i, fav ->
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(resolveValue(fav.key), color = slotColors.getOrElse(i) { slotColors.last() }, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                        Text(fav.label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }

                        // Trigger breakdown
                        if (parsedTriggers.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.15f))
                            Spacer(Modifier.height(6.dp))
                            parsedTriggers.forEach { trigger ->
                                val sevColor = when (trigger.severity.uppercase()) {
                                    "HIGH" -> Color(0xFFEF5350); "MILD" -> Color(0xFFFFB74D); else -> Color(0xFF81C784)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(trigger.name, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("${trigger.score}", color = sevColor, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                                        Text(trigger.severity, color = sevColor, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    } else {
                        Text("No risk data yet", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }

                    // All Metrics — remaining favorites grouped by category
                    val selectedKeys = effectiveFavs.map { it.key }.toSet()
                    val remaining = favPool.filter { it.key !in selectedKeys }
                    if (remaining.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.2f))
                        Spacer(Modifier.height(8.dp))
                        Text("All Metrics", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.height(4.dp))

                        remaining.groupBy { it.category }.forEach { (category, entries) ->
                            val color = CAT_COLORS[category] ?: Color(0xFF999999)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(8.dp).background(color, CircleShape))
                                Text(category, color = color, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                            }
                            Spacer(Modifier.height(2.dp))
                            entries.forEach { entry ->
                                MetricRowItem(entry.label, resolveValue(entry.key))
                            }
                        }
                    }
                }
            }

            // 4. History Graph — premium only
            PremiumGate(
                message = "Unlock Risk Trends",
                subtitle = "Track your risk score over time",
                onUpgrade = { navController.navigate(Routes.PAYWALL) }
            ) {
                RiskHistoryGraph(
                    days = 14,
                    onClick = { navController.navigate(Routes.FULL_GRAPH_RISK) }
                )
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun MetricRowItem(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
        Text(value, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
    }
}

data class ParsedTrigger(val name: String, val score: Int, val severity: String, val daysActive: Int)

internal fun parseTopTriggersFromJson(raw: String?): List<ParsedTrigger> {
    if (raw.isNullOrBlank() || raw == "null") return emptyList()
    return try {
        val arr = Json.parseToJsonElement(raw).jsonArray
        arr.mapNotNull { el ->
            val obj = el.jsonObject
            ParsedTrigger(
                name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                score = obj["score"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                severity = obj["severity"]?.jsonPrimitive?.content ?: "LOW",
                daysActive = obj["daysActive"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            )
        }
    } catch (_: Exception) { emptyList() }
}

internal fun parseForecastFromJson(raw: String?): List<Int> {
    if (raw.isNullOrBlank() || raw == "null") return emptyList()
    return try {
        val arr = Json.parseToJsonElement(raw).jsonArray
        arr.map { it.jsonPrimitive.content.toIntOrNull() ?: 0 }
    } catch (_: Exception) { emptyList() }
}

internal fun sleepMetricDisplayValue(s: SleepSummary, metric: String): String = when (metric) {
    SleepCardConfig.METRIC_DURATION -> "%.1fh".format(s.durationHours)
    SleepCardConfig.METRIC_SCORE -> "${s.sleepScore}"
    SleepCardConfig.METRIC_EFFICIENCY -> "${s.efficiency}%"
    SleepCardConfig.METRIC_FELL_ASLEEP -> s.fellAsleepDisplay ?: "\u2014"
    SleepCardConfig.METRIC_WOKE_UP -> s.wokeUpDisplay ?: "\u2014"
    SleepCardConfig.METRIC_DISTURBANCES -> s.disturbances?.toString() ?: "\u2014"
    SleepCardConfig.METRIC_STAGES_DEEP -> s.stagesDeep?.let { "%.1fh".format(it) } ?: "\u2014"
    SleepCardConfig.METRIC_STAGES_REM -> s.stagesRem?.let { "%.1fh".format(it) } ?: "\u2014"
    SleepCardConfig.METRIC_STAGES_LIGHT -> s.stagesLight?.let { "%.1fh".format(it) } ?: "\u2014"
    else -> "\u2014"
}

internal fun physicalMetricDisplayValue(p: PhysicalSummary, metric: String): String = when (metric) {
    PhysicalCardConfig.METRIC_RECOVERY -> p.recoveryScore?.let { "${it.toInt()}%" } ?: "\u2014"
    PhysicalCardConfig.METRIC_HRV -> p.hrv?.let { "${it.toInt()} ms" } ?: "\u2014"
    PhysicalCardConfig.METRIC_RESTING_HR -> p.restingHr?.let { "${it.toInt()} bpm" } ?: "\u2014"
    PhysicalCardConfig.METRIC_SPO2 -> p.spo2?.let { "${it.toInt()}%" } ?: "\u2014"
    PhysicalCardConfig.METRIC_SKIN_TEMP -> p.skinTemp?.let { "%.1f\u00B0".format(it) } ?: "\u2014"
    PhysicalCardConfig.METRIC_RESPIRATORY_RATE -> p.respiratoryRate?.let { "%.1f".format(it) } ?: "\u2014"
    PhysicalCardConfig.METRIC_HIGH_HR_ZONES -> p.highHrZones?.let { "%.0f min".format(it) } ?: "\u2014"
    PhysicalCardConfig.METRIC_STEPS -> p.steps?.toString() ?: "\u2014"
    PhysicalCardConfig.METRIC_WEIGHT -> p.weight?.let { "%.1f kg".format(it) } ?: "\u2014"
    PhysicalCardConfig.METRIC_BODY_FAT -> p.bodyFat?.let { "%.1f%%".format(it) } ?: "\u2014"
    PhysicalCardConfig.METRIC_BLOOD_PRESSURE -> if (p.bpSystolic != null) "${p.bpSystolic}/${p.bpDiastolic}" else "\u2014"
    PhysicalCardConfig.METRIC_BLOOD_GLUCOSE -> p.bloodGlucose?.let { "%.1f".format(it) } ?: "\u2014"
    else -> "\u2014"
}
