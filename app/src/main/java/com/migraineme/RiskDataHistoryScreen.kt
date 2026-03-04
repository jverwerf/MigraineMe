package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun RiskDataHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    val today = remember { LocalDate.now() }
    val maxForecastDate = remember { today.plusDays(7) }
    var selectedDate by remember { mutableStateOf(today) }
    val selectedDateStr = selectedDate.toString()

    var riskRow by remember { mutableStateOf<SupabaseDbService.RiskScoreDailyRow?>(null) }
    var riskLive by remember { mutableStateOf<SupabaseDbService.RiskScoreLiveRow?>(null) }
    var forecastScore by remember { mutableStateOf<Double?>(null) }

    // Category data for the selected date
    var weatherSummary by remember { mutableStateOf<WeatherSummary?>(null) }
    var physicalSummary by remember { mutableStateOf<PhysicalSummary?>(null) }
    var sleepSummary by remember { mutableStateOf<SleepSummary?>(null) }
    var mentalSummary by remember { mutableStateOf<MentalSummary?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Configs for favorites
    val favs = remember { getEffectiveFavOfFavs(context) }
    val pool = remember { buildFavoritesPool(context) }

    fun resolveValue(key: String): String {
        val parts = key.split(":", limit = 2)
        if (parts.size != 2) return "\u2014"
        val (cat, metric) = parts
        return when (cat) {
            "sleep" -> sleepSummary?.let { sleepMetricDisplayValue(it, metric) } ?: "\u2014"
            "weather" -> weatherSummary?.let { getWeatherMetricValue(it, metric) } ?: "\u2014"
            "physical" -> physicalSummary?.let { physicalMetricDisplayValue(it, metric) } ?: "\u2014"
            "mental" -> mentalSummary?.displayValue(metric) ?: "\u2014"
            else -> "\u2014"
        }
    }

    fun loadData() {
        scope.launch {
            isLoading = true
            val token = SessionStore.readAccessToken(context) ?: run { isLoading = false; return@launch }
            withContext(Dispatchers.IO) {
                try {
                    val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                    val rows = db.getRiskScoreDaily(token, 30)
                    riskRow = rows.find { it.date == selectedDateStr }
                    if (riskLive == null) riskLive = db.getRiskScoreLive(token)
                } catch (_: Exception) { riskRow = null }
                // Load category data for this date
                try { weatherSummary = loadWeatherSummary(context, token, selectedDateStr) } catch (_: Exception) {}
                try { physicalSummary = loadPhysicalSummary(context, token, selectedDateStr) } catch (_: Exception) {}
                try { sleepSummary = loadSleepSummary(context, token, selectedDateStr) } catch (_: Exception) {}
                try { mentalSummary = loadMentalSummary(context, token, selectedDateStr) } catch (_: Exception) {}
            }
            forecastScore = if (selectedDate > today && riskLive != null) {
                parseForecastForDate(riskLive!!.dayRisks, selectedDateStr)
            } else null
            isLoading = false
        }
    }

    LaunchedEffect(selectedDateStr) { loadData() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.FadeColor)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
        }

        HeroCard {
            Text("Risk Data", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(4.dp))
            Text("View your daily risk scores and triggers", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
        }

        // Date navigation
        BaseCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedDate = selectedDate.minusDays(1) }) {
                    Icon(Icons.Default.ChevronLeft, "Previous day", tint = AppTheme.AccentPurple)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when (selectedDate) {
                            today -> "Today"
                            today.minusDays(1) -> "Yesterday"
                            today.plusDays(1) -> "Tomorrow"
                            else -> selectedDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                        },
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        selectedDate.format(DateTimeFormatter.ofPattern("MMM d, yyyy")),
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                IconButton(
                    onClick = { selectedDate = selectedDate.plusDays(1) },
                    enabled = selectedDate < maxForecastDate
                ) {
                    Icon(
                        Icons.Default.ChevronRight, "Next day",
                        tint = if (selectedDate < maxForecastDate) AppTheme.AccentPurple else AppTheme.SubtleTextColor.copy(alpha = 0.3f)
                    )
                }
            }
        }

        // Data card
        BaseCard {
            if (isLoading) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AppTheme.AccentPurple, strokeWidth = 2.dp)
                }
            } else if (riskRow == null && forecastScore == null) {
                Text("No risk data for this day", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            } else {
                // Derive values from daily row or forecast
                val score = riskRow?.score ?: forecastScore ?: 0.0
                val zone = riskRow?.zone?.lowercase()?.replaceFirstChar { it.uppercase() }
                    ?: when { score >= 10 -> "High"; score >= 5 -> "Mild"; score >= 3 -> "Low"; else -> "Minimal" }
                val percent = riskRow?.percent
                val triggers = if (riskRow != null) parseTopTriggersFromJsonElement(riskRow!!.topTriggers) else emptyList()
                val isForecast = riskRow == null && forecastScore != null

                // Score / Zone / Triggers
                val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%.1f".format(score), color = slotColors[0], style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                        Text(if (isForecast) "Forecast" else "Score", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(zone, color = slotColors[1], style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                        Text("Zone", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${triggers.size}", color = slotColors[2], style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                        Text("Triggers", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Fav-of-favs
                if (favs.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.15f))
                    Spacer(Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        favs.take(3).forEachIndexed { i, fav ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(resolveValue(fav.key), color = slotColors.getOrElse(i) { slotColors.last() }, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                Text(fav.label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.2f))
                Spacer(Modifier.height(8.dp))

                // All metrics
                RiskMetricRow("Risk Score", "%.1f".format(score))
                RiskMetricRow("Zone", zone)
                if (percent != null) RiskMetricRow("Risk %", "${percent}%")
                RiskMetricRow("Active Triggers", "${triggers.size}")

                // Remaining favorites with real values — only show those that have data
                val favKeys = favs.map { it.key }.toSet()
                val remaining = pool.filter { it.key !in favKeys }
                    .filter { resolveValue(it.key) != "—" }
                if (remaining.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    remaining.forEach { entry ->
                        RiskMetricRow(entry.label, resolveValue(entry.key))
                    }
                }

                // Trigger breakdown
                if (triggers.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.2f))
                    Spacer(Modifier.height(8.dp))
                    Text("Triggers", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(4.dp))
                    triggers.forEach { trigger ->
                        val sevColor = when (trigger.severity.uppercase()) {
                            "HIGH" -> Color(0xFFEF5350); "MILD" -> Color(0xFFFFB74D); else -> Color(0xFF81C784)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(trigger.name, color = Color.White, style = MaterialTheme.typography.bodySmall)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${trigger.score}", color = sevColor, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                                Text(trigger.severity, color = sevColor, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RiskMetricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
        Text(value, color = Color.White, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
    }
}

private fun parseTopTriggersFromJsonElement(raw: JsonElement?): List<ParsedTrigger> {
    if (raw == null) return emptyList()
    return try {
        val arr = raw.jsonArray
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

/**
 * Parse day_risks JSON to find the forecast score for a specific date.
 * day_risks is typically: [{"date":"2026-02-25","score":4.2,...}, ...]
 */
private fun parseForecastForDate(dayRisksJson: String?, date: String): Double? {
    if (dayRisksJson.isNullOrBlank() || dayRisksJson == "null") return null
    return try {
        val arr = kotlinx.serialization.json.Json.parseToJsonElement(dayRisksJson).jsonArray
        arr.firstOrNull { el ->
            el.jsonObject["date"]?.jsonPrimitive?.content == date
        }?.jsonObject?.get("score")?.jsonPrimitive?.content?.toDoubleOrNull()
    } catch (_: Exception) { null }
}
