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

@Composable
fun MonitorPhysicalScreen(
    navController: NavController,
    authVm: AuthViewModel = viewModel()
) {
    val ctx = LocalContext.current.applicationContext
    val authState by authVm.state.collectAsState()
    val scrollState = rememberScrollState()
    val today = remember { LocalDate.now(ZoneId.systemDefault()).toString() }

    var physicalDetail by remember { mutableStateOf<PhysicalDetailData?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val physicalConfig = remember { PhysicalCardConfigStore.load(ctx) }

    LaunchedEffect(authState.accessToken, today) {
        val token = authState.accessToken
        if (token.isNullOrBlank()) {
            isLoading = false
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            physicalDetail = try { loadPhysicalDetailData(ctx, token, today) } catch (_: Exception) { null }
            isLoading = false
        }
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
            HeroCard(modifier = Modifier.clickable { navController.navigate(Routes.PHYSICAL_CONFIG) }) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, contentDescription = "Configure", tint = AppTheme.AccentPurple, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Customize Monitor Card", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Text(text = "Choose 3 metrics for the Physical Health card on Monitor", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(text = "→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
                }
            }

            // Explainer
            BaseCard {
                Text(
                    text = "Poor recovery, low HRV, and elevated resting heart rate are strongly correlated with migraine onset. Tracking these metrics helps identify patterns and predict migraine risk.",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            // Today's Physical Health — shows ALL metrics
            BaseCard {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { if (PremiumManager.isPremium) navController.navigate(Routes.PHYSICAL_DATA_HISTORY) else navController.navigate(Routes.PAYWALL) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Today's Data", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    if (PremiumManager.isPremium) { Text("History →", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall) } else { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { Icon(Icons.Outlined.Lock, contentDescription = "Premium", tint = AppTheme.AccentPurple, modifier = Modifier.size(14.dp)); Text("History", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall) } }
                }
                Spacer(Modifier.height(8.dp))

                if (isLoading) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AppTheme.AccentPurple, strokeWidth = 2.dp)
                    }
                } else if (physicalDetail == null) {
                    Text(text = "No physical health data for today", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(text = "Connect a wearable or enable Health Connect in Data Settings", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
                } else {
                    val detail = physicalDetail!!

                    // Top 3 selected metrics
                    val selectedMetrics = physicalConfig.physicalDisplayMetrics.take(3)
                    val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        selectedMetrics.forEachIndexed { index, metric ->
                            val value = detail.displayValue(metric) ?: "—"
                            val label = PhysicalCardConfig.labelFor(metric)
                            PhysicalMetricLargeItem(label, value, slotColors.getOrElse(index) { slotColors.last() })
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.2f))
                    Spacer(Modifier.height(8.dp))
                    Text(text = "All Metrics", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(4.dp))

                    // All metrics (show "—" for missing data)
                    PhysicalCardConfig.ALL_PHYSICAL_METRICS.forEach { metric ->
                        if (metric !in selectedMetrics) {
                            val value = detail.displayValue(metric) ?: "—"
                            val label = PhysicalCardConfig.labelFor(metric)
                            PhysicalMetricRowItem(label, value, AppTheme.SubtleTextColor)
                        }
                    }
                }
            }

            // History Graph — premium only
            PremiumGate(
                message = "Unlock Physical Trends",
                subtitle = "Track your health metrics over time",
                onUpgrade = { navController.navigate(Routes.PAYWALL) }
            ) {
                PhysicalHistoryGraph(
                    days = 14,
                    onClick = { navController.navigate(Routes.FULL_GRAPH_PHYSICAL) }
                )
            }
        }
    }
}

// ─── Composable helpers ──────────────────────────────────────────────────────

@Composable
private fun PhysicalMetricLargeItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = color, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        Text(text = label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PhysicalMetricRowItem(label: String, value: String?, color: Color) {
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

// ─── Data class ──────────────────────────────────────────────────────────────

private data class PhysicalDetailData(
    val recovery: Double?,
    val hrv: Double?,
    val restingHr: Double?,
    val spo2: Double?,
    val skinTemp: Double?,
    val respiratoryRate: Double?,
    val stress: Double?,
    val highHrZonesMinutes: Double?,
    val steps: Int?,
    val weight: Double?,
    val bodyFat: Double?,
    val bloodPressureSystolic: Double?,
    val bloodPressureDiastolic: Double?,
    val bloodGlucose: Double?
) {
    fun displayValue(metric: String): String? = when (metric) {
        PhysicalCardConfig.METRIC_RECOVERY -> recovery?.let { "${it.toInt()}%" }
        PhysicalCardConfig.METRIC_HRV -> hrv?.let { "${it.toInt()} ms" }
        PhysicalCardConfig.METRIC_RESTING_HR -> restingHr?.let { "${it.toInt()} bpm" }
        PhysicalCardConfig.METRIC_SPO2 -> spo2?.let { "${it.toInt()}%" }
        PhysicalCardConfig.METRIC_SKIN_TEMP -> skinTemp?.let { String.format("%.1f°C", it) }
        PhysicalCardConfig.METRIC_RESPIRATORY_RATE -> respiratoryRate?.let { String.format("%.1f bpm", it) }
        PhysicalCardConfig.METRIC_STRESS -> stress?.let { String.format("%.0f", it) }
        PhysicalCardConfig.METRIC_HIGH_HR_ZONES -> highHrZonesMinutes?.let { "${it.toInt()} min" }
        PhysicalCardConfig.METRIC_STEPS -> steps?.let { "%,d".format(it) }
        PhysicalCardConfig.METRIC_WEIGHT -> weight?.let { String.format("%.1f kg", it) }
        PhysicalCardConfig.METRIC_BODY_FAT -> bodyFat?.let { String.format("%.1f%%", it) }
        PhysicalCardConfig.METRIC_BLOOD_PRESSURE -> {
            if (bloodPressureSystolic != null && bloodPressureDiastolic != null)
                "${bloodPressureSystolic.toInt()}/${bloodPressureDiastolic.toInt()}"
            else null
        }
        PhysicalCardConfig.METRIC_BLOOD_GLUCOSE -> bloodGlucose?.let { String.format("%.0f mg/dL", it) }
        else -> null
    }

    /** Numeric value for a metric (for graph usage) */
    fun numericValue(metric: String): Double? = when (metric) {
        PhysicalCardConfig.METRIC_RECOVERY -> recovery
        PhysicalCardConfig.METRIC_HRV -> hrv
        PhysicalCardConfig.METRIC_RESTING_HR -> restingHr
        PhysicalCardConfig.METRIC_SPO2 -> spo2
        PhysicalCardConfig.METRIC_SKIN_TEMP -> skinTemp
        PhysicalCardConfig.METRIC_RESPIRATORY_RATE -> respiratoryRate
        PhysicalCardConfig.METRIC_STRESS -> stress
        PhysicalCardConfig.METRIC_HIGH_HR_ZONES -> highHrZonesMinutes
        PhysicalCardConfig.METRIC_STEPS -> steps?.toDouble()
        PhysicalCardConfig.METRIC_WEIGHT -> weight
        PhysicalCardConfig.METRIC_BODY_FAT -> bodyFat
        PhysicalCardConfig.METRIC_BLOOD_PRESSURE -> bloodPressureSystolic
        PhysicalCardConfig.METRIC_BLOOD_GLUCOSE -> bloodGlucose
        else -> null
    }
}

// ─── Data loading ────────────────────────────────────────────────────────────

private suspend fun loadPhysicalDetailData(
    ctx: android.content.Context,
    token: String,
    date: String
): PhysicalDetailData? = withContext(Dispatchers.IO) {
    val physService = SupabasePhysicalHealthService(ctx)

    val recovery = try { physService.fetchRecoveryScoreDaily(token, 1).find { it.date == date } } catch (_: Exception) { null }
    val hrv = try { physService.fetchHrvDaily(token, 1).find { it.date == date } } catch (_: Exception) { null }
    val rhr = try { physService.fetchRestingHrDaily(token, 1).find { it.date == date } } catch (_: Exception) { null }
    val spo2 = try { physService.fetchSpo2Daily(token, 1).find { it.date == date } } catch (_: Exception) { null }
    val skinTemp = try { physService.fetchSkinTempDaily(token, 1).find { it.date == date } } catch (_: Exception) { null }
    val stress = try { physService.fetchStressIndexDaily(token, 1).find { it.date == date } } catch (_: Exception) { null }
    val highHr = try { physService.fetchHighHrDaily(token, 1).find { it.date == date } } catch (_: Exception) { null }

    // Fetch metrics from tables that don't have dedicated fetch methods yet — use direct REST
    val userId = SessionStore.readUserId(ctx)
    val client = okhttp3.OkHttpClient()

    val steps = if (userId != null) fetchSingleDouble(client, token, "steps_daily", userId, date, "value_count")?.toInt() else null
    val weight = if (userId != null) fetchSingleDouble(client, token, "weight_daily", userId, date, "value_kg") else null
    val bodyFat = if (userId != null) fetchSingleDouble(client, token, "body_fat_daily", userId, date, "value_pct") else null
    val respiratoryRate = if (userId != null) fetchSingleDouble(client, token, "respiratory_rate_daily", userId, date, "value_bpm") else null
    val bloodGlucose = if (userId != null) fetchSingleDouble(client, token, "blood_glucose_daily", userId, date, "value_mgdl") else null

    var bpSystolic: Double? = null
    var bpDiastolic: Double? = null
    if (userId != null) {
        try {
            val url = "${BuildConfig.SUPABASE_URL}/rest/v1/blood_pressure_daily?user_id=eq.$userId&date=eq.$date&select=value_systolic,value_diastolic&limit=1"
            val request = okhttp3.Request.Builder().url(url).get()
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token").build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (response.isSuccessful && !body.isNullOrBlank()) {
                val arr = org.json.JSONArray(body)
                if (arr.length() > 0) {
                    val obj = arr.getJSONObject(0)
                    bpSystolic = obj.optDouble("value_systolic").takeIf { !it.isNaN() }
                    bpDiastolic = obj.optDouble("value_diastolic").takeIf { !it.isNaN() }
                }
            }
        } catch (_: Exception) {}
    }

    // Return null only if we have absolutely no data
    if (recovery == null && hrv == null && rhr == null && spo2 == null && skinTemp == null &&
        stress == null && highHr == null && steps == null && weight == null && bodyFat == null &&
        respiratoryRate == null && bloodGlucose == null && bpSystolic == null) {
        return@withContext null
    }

    PhysicalDetailData(
        recovery = recovery?.value_pct,
        hrv = hrv?.value_rmssd_ms,
        restingHr = rhr?.value_bpm,
        spo2 = spo2?.value_pct,
        skinTemp = skinTemp?.value_celsius,
        respiratoryRate = respiratoryRate,
        stress = stress?.value,
        highHrZonesMinutes = highHr?.value_minutes,
        steps = steps,
        weight = weight,
        bodyFat = bodyFat,
        bloodPressureSystolic = bpSystolic,
        bloodPressureDiastolic = bpDiastolic,
        bloodGlucose = bloodGlucose
    )
}

/** Generic helper to fetch a single double value from a daily table */
private fun fetchSingleDouble(
    client: okhttp3.OkHttpClient,
    token: String,
    table: String,
    userId: String,
    date: String,
    column: String
): Double? {
    return try {
        val url = "${BuildConfig.SUPABASE_URL}/rest/v1/$table?user_id=eq.$userId&date=eq.$date&select=$column&limit=1"
        val request = okhttp3.Request.Builder().url(url).get()
            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer $token").build()
        val response = client.newCall(request).execute()
        val body = response.body?.string()
        if (response.isSuccessful && !body.isNullOrBlank()) {
            val arr = org.json.JSONArray(body)
            if (arr.length() > 0) {
                arr.getJSONObject(0).optDouble(column).takeIf { !it.isNaN() }
            } else null
        } else null
    } catch (_: Exception) { null }
}

