package com.migraineme

import android.util.Log
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

@Composable
fun MonitorMentalScreen(
    navController: NavController,
    authVm: AuthViewModel = viewModel()
) {
    val ctx = LocalContext.current.applicationContext
    val authState by authVm.state.collectAsState()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now(ZoneId.systemDefault()).toString() }

    var mentalDetail by remember { mutableStateOf<MentalDetailData?>(null) }
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
                            enabledRegistryKeys = MetricRegistry.enabledKeys(settings, "mental")
                        } catch (_: Exception) {
                            enabledRegistryKeys = MetricRegistry.byGroup("mental").map { it.key }.toSet()
                        }
                        settingsLoaded = true
                    }

                    if (enabledRegistryKeys.isNotEmpty()) {
                        val token = authState.accessToken
                        if (!token.isNullOrBlank()) {
                            withContext(Dispatchers.IO) {
                                mentalDetail = try {
                                    loadMentalDetailData(ctx, token, today)
                                } catch (e: Exception) {
                                    Log.e("MentalDetail", "Load failed", e); null
                                }
                            }
                        }
                    } else {
                        mentalDetail = null
                    }
                    isLoading = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // All mental metrics from registry, filtered by enabled state
    val allMetrics = remember(settingsLoaded, enabledRegistryKeys) {
        MetricRegistry.byGroup("mental").filter { it.key in enabledRegistryKeys }
    }

    // Display metrics — filtered to only enabled ones
    val displayKeys = remember(settingsLoaded, enabledRegistryKeys) {
        MetricDisplayStore.getDisplayMetrics(ctx, "mental")
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
            HeroCard(modifier = Modifier.clickable { navController.navigate(Routes.MENTAL_CONFIG) }) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, contentDescription = "Configure", tint = AppTheme.AccentPurple, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Customize Monitor Card", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Text("Choose 3 metrics for the Mental Health card on Monitor", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
                }
            }

            DismissableInfoCard(
                key = "monitor_mental",
                text = "Screen time, phone usage patterns, and environmental noise are linked to stress and migraine triggers. Tracking these passively helps identify behavioral patterns."
            )

            // Show disabled message if ALL mental tracking is off
            if (settingsLoaded && enabledRegistryKeys.isEmpty()) {
                BaseCard {
                    Text(
                        "Mental health tracking is disabled",
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Enable mental health metrics in Data Settings to start tracking.",
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
                // Today's Mental Health
                BaseCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (PremiumManager.isPremium) navController.navigate(Routes.MENTAL_DATA_HISTORY)
                            else navController.navigate(Routes.PAYWALL)
                        },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Today's Data", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
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
                    } else if (mentalDetail == null) {
                        Text("No mental health data for today", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        Text("Enable metrics in Data Settings to start tracking", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
                    } else {
                        val detail = mentalDetail!!

                        // Top 3 selected metrics
                        val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            displayKeys.forEachIndexed { index, key ->
                                val metric = MetricRegistry.get(key)
                                val value = mentalValueByKey(detail, key)
                                val formatted = if (value != null && metric != null) {
                                    MetricFormatter.format(value, metric.unit, metric.column)
                                } else "—"
                                val label = metric?.label ?: key
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(formatted, color = slotColors.getOrElse(index) { slotColors.last() }, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
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
                                val value = mentalValueByKey(detail, m.key)
                                val formatted = if (value != null) {
                                    MetricFormatter.format(value, m.unit, m.column)
                                } else "—"
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(m.label, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
                                    Text(formatted, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                                }
                            }
                        }
                    }
                }

                // History Graph — premium only
                PremiumGate(
                    message = "Unlock Mental Health Trends",
                    subtitle = "Track your mental health patterns over time",
                    onUpgrade = { navController.navigate(Routes.PAYWALL) }
                ) {
                    MentalHistoryGraph(
                        days = 14,
                        onClick = { navController.navigate(Routes.FULL_GRAPH_MENTAL) }
                    )
                }
            } // end enabled check
        }
    }
}

/**
 * Bridge: extract a value from MentalDetailData using a MetricRegistry key.
 */
private fun mentalValueByKey(detail: MentalDetailData, key: String): Double? {
    return when (key) {
        "stress_index_daily" -> detail.stress
        "screen_time_daily" -> detail.screenTimeHours
        "screen_time_late_night" -> detail.lateScreenTimeHours
        "ambient_noise_index_daily::day_mean_lmean" -> detail.noiseIndex
        "phone_brightness_daily" -> detail.brightness
        "phone_volume_daily" -> detail.volumePct
        "phone_dark_mode_daily" -> detail.darkModeHours
        "phone_unlock_daily" -> detail.unlockCount?.toDouble()
        else -> null
    }
}

// ─── Data class (kept for data loading compatibility) ────────────────────────

private data class MentalDetailData(
    val stress: Double?,
    val screenTimeHours: Double?,
    val lateScreenTimeHours: Double?,
    val noiseIndex: Double?,
    val brightness: Double?,
    val volumePct: Double?,
    val darkModeHours: Double?,
    val unlockCount: Int?
)

// ─── Data loading ────────────────────────────────────────────────────────────

private suspend fun loadMentalDetailData(
    ctx: android.content.Context,
    token: String,
    date: String
): MentalDetailData? = withContext(Dispatchers.IO) {
    val userId = SessionStore.readUserId(ctx) ?: return@withContext null
    val client = okhttp3.OkHttpClient()
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val key = BuildConfig.SUPABASE_ANON_KEY

    fun fetchDouble(table: String, column: String): Double? {
        return try {
            val url = "$base/rest/v1/$table?user_id=eq.$userId&date=eq.$date&select=$column&limit=1"
            val request = okhttp3.Request.Builder().url(url).get()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $token").build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (response.isSuccessful && !body.isNullOrBlank()) {
                val arr = org.json.JSONArray(body)
                if (arr.length() > 0) arr.getJSONObject(0).optDouble(column).takeIf { !it.isNaN() } else null
            } else null
        } catch (_: Exception) { null }
    }

    fun fetchInt(table: String, column: String): Int? {
        return try {
            val url = "$base/rest/v1/$table?user_id=eq.$userId&date=eq.$date&select=$column&limit=1"
            val request = okhttp3.Request.Builder().url(url).get()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $token").build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (response.isSuccessful && !body.isNullOrBlank()) {
                val arr = org.json.JSONArray(body)
                if (arr.length() > 0) {
                    val v = arr.getJSONObject(0).optInt(column, Int.MIN_VALUE)
                    if (v != Int.MIN_VALUE) v else null
                } else null
            } else null
        } catch (_: Exception) { null }
    }

    fun fetchSamplesAvg(table: String, column: String, tsColumn: String = "sampled_at"): Double? {
        return try {
            val url = "$base/rest/v1/$table?user_id=eq.$userId&${tsColumn}=gte.${date}T00:00:00&${tsColumn}=lt.${date}T23:59:59&select=$column"
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

    fun fetchSamplesMax(table: String, column: String, tsColumn: String = "sampled_at"): Int? {
        return try {
            val url = "$base/rest/v1/$table?user_id=eq.$userId&${tsColumn}=gte.${date}T00:00:00&${tsColumn}=lt.${date}T23:59:59&select=$column"
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
                        if (v != Int.MIN_VALUE) v else null
                    }
                    values.maxOrNull()
                } else null
            } else null
        } catch (_: Exception) { null }
    }

    fun fetchDarkModeSamplesHours(): Double? {
        return try {
            val url = "$base/rest/v1/phone_dark_mode_samples?user_id=eq.$userId&sampled_at=gte.${date}T00:00:00&sampled_at=lt.${date}T23:59:59&select=is_dark"
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

    val stress = fetchDouble("stress_index_daily", "value")
    val screenTime = fetchDouble("screen_time_daily", "total_hours")
        ?: fetchDouble("screen_time_live", "value_hours")
    val lateScreenTime = fetchDouble("screen_time_late_night", "value_hours")
    val noise = fetchDouble("ambient_noise_index_daily", "day_mean_lmean")
        ?: fetchSamplesAvg("ambient_noise_samples", "l_mean", "start_ts")

    val brightness = fetchDouble("phone_brightness_daily", "value_mean")
        ?: fetchSamplesAvg("phone_brightness_samples", "value")
    val volume = fetchDouble("phone_volume_daily", "value_mean_pct")
        ?: fetchSamplesAvg("phone_volume_samples", "value_pct")
    val darkMode = fetchDouble("phone_dark_mode_daily", "value_hours")
        ?: fetchDarkModeSamplesHours()
    val unlocks = fetchInt("phone_unlock_daily", "value_count")
        ?: fetchSamplesMax("phone_unlock_samples", "value_count")

    Log.d("MentalDetail", "stress=$stress screenTime=$screenTime lateScreen=$lateScreenTime noise=$noise brightness=$brightness volume=$volume darkMode=$darkMode unlocks=$unlocks")

    if (stress == null && screenTime == null && lateScreenTime == null && noise == null &&
        brightness == null && volume == null && darkMode == null && unlocks == null) {
        return@withContext null
    }

    MentalDetailData(
        stress = stress,
        screenTimeHours = screenTime,
        lateScreenTimeHours = lateScreenTime,
        noiseIndex = noise,
        brightness = brightness,
        volumePct = volume,
        darkModeHours = darkMode,
        unlockCount = unlocks
    )
}
