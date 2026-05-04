package com.migraineme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState

import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Tune

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now(ZoneId.systemDefault()).toString() }

    var sleepDetail by remember { mutableStateOf<SleepDetailData?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Data settings — which individual metrics are enabled
    var enabledRegistryKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var settingsLoaded by remember { mutableStateOf(false) }

    // Re-fetch on every resume
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    isLoading = true
                    withContext(Dispatchers.IO) {
                        try {
                            val edge = EdgeFunctionsService()
                            val settings = edge.getMetricSettings(ctx)
                            enabledRegistryKeys = MetricRegistry.enabledKeys(settings, "sleep")
                        } catch (_: Exception) {
                            enabledRegistryKeys = MetricRegistry.byGroup("sleep").map { it.key }.toSet()
                        }
                        settingsLoaded = true
                    }

                    if (enabledRegistryKeys.isNotEmpty()) {
                        val token = authState.accessToken
                        if (!token.isNullOrBlank()) {
                            withContext(Dispatchers.IO) {
                                sleepDetail = try {
                                    loadSleepDetailData(ctx, token, today)
                                } catch (_: Exception) { null }
                            }
                        }
                    } else {
                        sleepDetail = null
                    }
                    isLoading = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // All sleep metrics from registry, filtered by enabled state
    val allMetrics = remember(settingsLoaded, enabledRegistryKeys) {
        MetricRegistry.byGroup("sleep").filter { it.key in enabledRegistryKeys }
    }

    // Display metrics — filtered to only enabled ones
    val displayKeys = remember(settingsLoaded, enabledRegistryKeys) {
        MetricDisplayStore.getDisplayMetrics(ctx, "sleep")
            .filter { it in enabledRegistryKeys }
            .ifEmpty { allMetrics.take(3).map { it.key } }
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
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
                        Text("Customize Monitor Card", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Text("Choose 3 metrics for the Sleep card on Monitor", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
                }
            }

            // Show disabled message if ALL sleep tracking is off
            if (settingsLoaded && enabledRegistryKeys.isEmpty()) {
                BaseCard {
                    Text(
                        "Sleep tracking is disabled",
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Enable sleep tracking in Data Settings to see your sleep data.",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Go to Data Settings →",
                        color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clickable { navController.navigate(Routes.DATA) }
                    )
                }
            } else {
                // Last Night's Sleep
                BaseCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (PremiumManager.isPremium) navController.navigate(Routes.SLEEP_DATA_HISTORY)
                            else navController.navigate(Routes.PAYWALL)
                        },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Last Night's Sleep", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        if (PremiumManager.isPremium) {
                            Text("History →", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Outlined.Lock, contentDescription = "Premium", tint = AppTheme.AccentPurple, modifier = Modifier.size(14.dp))
                                Text("History", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    if (isLoading) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AppTheme.AccentPurple, strokeWidth = 2.dp)
                        }
                    } else if (sleepDetail == null) {
                        Text("No sleep data for last night", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        Text("Enable phone sleep tracking or connect a wearable in Data Settings", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
                    } else {
                        val detail = sleepDetail!!

                        // Top 3 selected metrics
                        val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            displayKeys.forEachIndexed { index, key ->
                                val metric = MetricRegistry.get(key)
                                val formatted = sleepDisplayByKey(detail, key) ?: "—"
                                val label = metric?.label ?: key
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(formatted, color = slotColors.getOrElse(index) { slotColors.last() }, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                    Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        // "All Metrics" section
                        val remainingMetrics = allMetrics.filter { it.key !in displayKeys }
                        if (remainingMetrics.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.2f))
                            Spacer(Modifier.height(8.dp))
                            Text("All Metrics", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                            Spacer(Modifier.height(4.dp))

                            remainingMetrics.forEach { m ->
                                val formatted = sleepDisplayByKey(detail, m.key)
                                if (formatted != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().height(24.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(m.label, color = AppTheme.TitleColor, style = MaterialTheme.typography.bodySmall)
                                        Text(formatted, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                                    }
                                }
                            }
                        }
                        // Source attribution
                        if (detail.sourceLabel.isNotBlank() && detail.sourceLabel != "Unknown") {
                            Spacer(Modifier.height(4.dp))
                            HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.2f))
                            Spacer(Modifier.height(8.dp))
                            SourceBadgeRow(listOf(detail.sourceLabel.lowercase()))
                        }
                    }
                }

                // History Graph — premium only
                PremiumGate(
                    message = "Unlock Sleep Trends",
                    subtitle = "Track your sleep patterns over time",
                    onUpgrade = { navController.navigate(Routes.PAYWALL) }
                ) {
                    SleepHistoryGraph(
                        days = 14,
                        onClick = { navController.navigate(Routes.FULL_GRAPH_SLEEP) }
                    )
                }
            } // end enabled check
        }
    }
}

// ─── Bridge functions ────────────────────────────────────────────────────────

/**
 * Bridge: get a formatted display value from SleepDetailData using a MetricRegistry key.
 * Returns null if no data available.
 */
private fun sleepDisplayByKey(detail: SleepDetailData, key: String): String? {
    return when (key) {
        "sleep_duration_daily" -> sleepHoursMinutes(detail.durationHours)
        "fell_asleep_time_daily" -> detail.fellAsleepDisplay
        "woke_up_time_daily" -> detail.wokeUpDisplay
        "sleep_score_daily" -> detail.sleepScore?.let { "${it.toInt()}%" }
        "sleep_efficiency_daily" -> detail.efficiency?.let { "${it.toInt()}%" }
        "sleep_disturbances_daily" -> detail.disturbances?.toString()
        "sleep_stages_daily::value_sws_hm" -> detail.stagesDeep?.let { sleepHoursMinutes(it) }
        "sleep_stages_daily::value_rem_hm" -> detail.stagesRem?.let { sleepHoursMinutes(it) }
        "sleep_stages_daily::value_light_hm" -> detail.stagesLight?.let { sleepHoursMinutes(it) }
        else -> null
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
)

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
    val sourceLabel = if (sourceInfo != null) sourceDisplayLabel(sourceInfo, ctx) else "Unknown"

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
