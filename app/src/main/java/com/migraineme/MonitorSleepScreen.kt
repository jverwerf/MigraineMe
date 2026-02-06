package com.migraineme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Composable
fun MonitorSleepScreen(
    navController: NavController,
    authVm: AuthViewModel = viewModel()
) {
    val ctx = LocalContext.current.applicationContext
    val authState by authVm.state.collectAsState()
    val scrollState = rememberScrollState()
    val today = remember { LocalDate.now(ZoneId.systemDefault()).toString() }

    var sleepDetail by remember { mutableStateOf<SleepDetailData?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val sleepConfig = remember { SleepCardConfigStore.load(ctx) }

    LaunchedEffect(authState.accessToken, today) {
        val token = authState.accessToken
        if (token.isNullOrBlank()) {
            isLoading = false
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            sleepDetail = try { loadSleepDetailData(ctx, token, today) } catch (_: Exception) { null }
            isLoading = false
        }
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll) {
            // Back
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            }

            // Customize HeroCard
            HeroCard(modifier = Modifier.clickable { navController.navigate(Routes.SLEEP_CONFIG) }) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, contentDescription = "Configure", tint = AppTheme.AccentPurple, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Customize Monitor Card", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Text(text = "Choose 3 metrics for the Sleep card on Monitor", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(text = "→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
                }
            }

            // Explainer
            BaseCard {
                Text(
                    text = "Poor sleep quality and irregular sleep patterns are among the most common migraine triggers. Tracking your sleep helps identify patterns and predict migraine risk.",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            // Today's Sleep — shows ALL metrics
            BaseCard {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { navController.navigate(Routes.SLEEP_DATA_HISTORY) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Last Night's Sleep", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text("History →", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))

                if (isLoading) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AppTheme.AccentPurple, strokeWidth = 2.dp)
                    }
                } else if (sleepDetail == null) {
                    Text(text = "No sleep data for last night", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(text = "Enable phone sleep tracking or connect a wearable in Data Settings", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
                } else {
                    val detail = sleepDetail!!

                    // Top 3 selected metrics
                    val selectedMetrics = sleepConfig.sleepDisplayMetrics.take(3)
                    val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        selectedMetrics.forEachIndexed { index, metric ->
                            val value = detail.displayValue(metric) ?: "—"
                            val label = SleepCardConfig.labelFor(metric)
                            SleepMetricLargeItem(label, value, slotColors.getOrElse(index) { slotColors.last() })
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.2f))
                    Spacer(Modifier.height(8.dp))
                    Text(text = "All Metrics", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(4.dp))

                    // All metrics that have data
                    SleepCardConfig.ALL_SLEEP_METRICS.forEach { metric ->
                        if (metric !in selectedMetrics) {
                            val value = detail.displayValue(metric)
                            if (value != null) {
                                val label = SleepCardConfig.labelFor(metric)
                                SleepMetricRowItem(label, value, AppTheme.SubtleTextColor)
                            }
                        }
                    }
                }
            }

            // History Graph
            SleepHistoryGraph(
                days = 14,
                onClick = { navController.navigate(Routes.FULL_GRAPH_SLEEP) }
            )


        }
    }
}

// ─── Composable helpers ──────────────────────────────────────────────────────

@Composable
private fun SleepMetricLargeItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = color, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        Text(text = label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SleepMetricRowItem(label: String, value: String?, color: Color) {
    if (value == null) return
    Row(
        modifier = Modifier.fillMaxWidth().height(24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = AppTheme.TitleColor, style = MaterialTheme.typography.bodySmall)
        Text(text = value, color = color, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun sleepHoursMinutes(hm: Double): String {
    val totalMinutes = (hm * 60).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun sleepTimeFromIso(isoString: String): String? {
    return try {
        val zdt = ZonedDateTime.parse(isoString)
        val local = zdt.withZoneSameInstant(ZoneId.systemDefault())
        local.format(DateTimeFormatter.ofPattern("h:mm a"))
    } catch (_: Exception) {
        try { isoString.take(5) } catch (_: Exception) { null }
    }
}

// ─── Data class ──────────────────────────────────────────────────────────────

private data class SleepDetailData(
    val durationHours: Double,
    val fellAsleepDisplay: String?,
    val wokeUpDisplay: String?,
    val sleepScore: Double?,
    val efficiency: Double?,
    val disturbances: Int?,
    val stagesDeep: Double?,
    val stagesRem: Double?,
    val stagesLight: Double?,
    val sourceLabel: String,
    val hasWearableData: Boolean
) {
    fun displayValue(metric: String): String? = when (metric) {
        SleepCardConfig.METRIC_DURATION -> String.format("%.1fh", durationHours)
        SleepCardConfig.METRIC_FELL_ASLEEP -> fellAsleepDisplay
        SleepCardConfig.METRIC_WOKE_UP -> wokeUpDisplay
        SleepCardConfig.METRIC_SCORE -> sleepScore?.let { "${it.toInt()}%" }
        SleepCardConfig.METRIC_EFFICIENCY -> efficiency?.let { "${it.toInt()}%" }
        SleepCardConfig.METRIC_DISTURBANCES -> disturbances?.toString()
        SleepCardConfig.METRIC_STAGES_DEEP -> stagesDeep?.let { sleepHoursMinutes(it) }
        SleepCardConfig.METRIC_STAGES_REM -> stagesRem?.let { sleepHoursMinutes(it) }
        SleepCardConfig.METRIC_STAGES_LIGHT -> stagesLight?.let { sleepHoursMinutes(it) }
        else -> null
    }
}

// ─── Data loading ────────────────────────────────────────────────────────────

private suspend fun loadSleepDetailData(
    ctx: android.content.Context,
    token: String,
    date: String
): SleepDetailData? = withContext(Dispatchers.IO) {
    val metrics = SupabaseMetricsService(ctx)

    val duration = try { metrics.fetchSleepDurationDaily(token, 1).find { it.date == date } } catch (_: Exception) { null }
    if (duration == null) return@withContext null

    val sourceInfo = try { sleepFetchSource(ctx, token, date) } catch (_: Exception) { null }
    val sourceLabel = when (sourceInfo) {
        "phone" -> "Phone"
        "whoop" -> "WHOOP"
        "health_connect" -> "Health Connect"
        else -> "Unknown"
    }

    val fellAsleep = try { sleepFetchTime(ctx, token, date, "fell_asleep_time_daily") } catch (_: Exception) { null }
    val wokeUp = try { sleepFetchTime(ctx, token, date, "woke_up_time_daily") } catch (_: Exception) { null }

    val score = try { metrics.fetchSleepScoreDaily(token, 1).find { it.date == date } } catch (_: Exception) { null }
    val efficiency = try { metrics.fetchSleepEfficiencyDaily(token, 1).find { it.date == date } } catch (_: Exception) { null }
    val disturbances = try { metrics.fetchSleepDisturbancesDaily(token, 1).find { it.date == date } } catch (_: Exception) { null }
    val stages = try { metrics.fetchSleepStagesDaily(token, 1).find { it.date == date } } catch (_: Exception) { null }

    val hasWearable = score != null || efficiency != null || disturbances != null || stages != null

    SleepDetailData(
        durationHours = duration.value_hours,
        fellAsleepDisplay = fellAsleep?.let { sleepTimeFromIso(it) },
        wokeUpDisplay = wokeUp?.let { sleepTimeFromIso(it) },
        sleepScore = score?.value_pct,
        efficiency = efficiency?.value_pct,
        disturbances = disturbances?.value_count,
        stagesDeep = stages?.value_sws_hm,
        stagesRem = stages?.value_rem_hm,
        stagesLight = stages?.value_light_hm,
        sourceLabel = sourceLabel,
        hasWearableData = hasWearable
    )
}

private suspend fun sleepFetchSource(ctx: android.content.Context, token: String, date: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val client = okhttp3.OkHttpClient()
            val userId = SessionStore.readUserId(ctx) ?: return@withContext null
            val url = "${BuildConfig.SUPABASE_URL}/rest/v1/sleep_duration_daily?user_id=eq.$userId&date=eq.$date&select=source&limit=1"
            val request = okhttp3.Request.Builder().url(url).get()
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token").build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            if (!response.isSuccessful) return@withContext null
            val arr = org.json.JSONArray(body)
            if (arr.length() > 0) arr.getJSONObject(0).optString("source", null) else null
        } catch (_: Exception) { null }
    }
}

private suspend fun sleepFetchTime(ctx: android.content.Context, token: String, date: String, table: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val client = okhttp3.OkHttpClient()
            val userId = SessionStore.readUserId(ctx) ?: return@withContext null
            val url = "${BuildConfig.SUPABASE_URL}/rest/v1/$table?user_id=eq.$userId&date=eq.$date&select=value_at&limit=1"
            val request = okhttp3.Request.Builder().url(url).get()
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token").build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            if (!response.isSuccessful) return@withContext null
            val arr = org.json.JSONArray(body)
            if (arr.length() > 0) arr.getJSONObject(0).optString("value_at", null) else null
        } catch (_: Exception) { null }
    }
}
