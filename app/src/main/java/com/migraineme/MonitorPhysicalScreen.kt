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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
fun MonitorPhysicalScreen(
    navController: NavController,
    authVm: AuthViewModel = viewModel()
) {
    val ctx = LocalContext.current.applicationContext
    val authState by authVm.state.collectAsState()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now(ZoneId.systemDefault()).toString() }

    var physicalDetail by remember { mutableStateOf<PhysicalDetailData?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    var enabledRegistryKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var settingsLoaded by remember { mutableStateOf(false) }

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
                            enabledRegistryKeys = MetricRegistry.enabledKeys(settings, "physical")
                        } catch (_: Exception) {
                            enabledRegistryKeys = MetricRegistry.byGroup("physical").map { it.key }.toSet()
                        }
                        settingsLoaded = true
                    }

                    if (enabledRegistryKeys.isNotEmpty()) {
                        val token = authState.accessToken
                        if (!token.isNullOrBlank()) {
                            withContext(Dispatchers.IO) {
                                physicalDetail = try {
                                    loadPhysicalDetailData(ctx, token, today)
                                } catch (_: Exception) { null }
                            }
                        }
                    } else {
                        physicalDetail = null
                    }
                    isLoading = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val allMetrics = remember(settingsLoaded, enabledRegistryKeys) {
        MetricRegistry.byGroup("physical").filter { it.key in enabledRegistryKeys }
    }

    val displayKeys = remember(settingsLoaded, enabledRegistryKeys) {
        MetricDisplayStore.getDisplayMetrics(ctx, "physical")
            .filter { it in enabledRegistryKeys }
            .ifEmpty { allMetrics.take(3).map { it.key } }
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            }

            HeroCard(modifier = Modifier.clickable { navController.navigate(Routes.PHYSICAL_CONFIG) }) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, contentDescription = "Configure", tint = AppTheme.AccentPurple, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Customize Monitor Card", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Text("Choose 3 metrics for the Physical Health card on Monitor", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
                }
            }

            // How it works — purple accent card
            Card(
                colors = CardDefaults.cardColors(containerColor = AppTheme.AccentPurple.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Info, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp))
                    Text(
                        "Poor recovery, low HRV, and elevated resting heart rate are strongly correlated with migraine onset. Tracking these metrics helps identify patterns and predict migraine risk.",
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (settingsLoaded && enabledRegistryKeys.isEmpty()) {
                BaseCard {
                    Text("Physical health tracking is disabled", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(4.dp))
                    Text("Enable physical health metrics in Data Settings to start tracking.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Go to Data Settings →", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clickable { navController.navigate(Routes.DATA) })
                }
            } else {
                BaseCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (PremiumManager.isPremium) navController.navigate(Routes.PHYSICAL_DATA_HISTORY)
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
                    } else if (physicalDetail == null) {
                        Text("No physical health data for today", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        Text("Connect a wearable or enable Health Connect in Data Settings", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
                    } else {
                        val detail = physicalDetail!!

                        val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            displayKeys.forEachIndexed { index, key ->
                                val metric = MetricRegistry.get(key)
                                val formatted = physicalDisplayByKey(detail, key) ?: "—"
                                val label = metric?.label ?: key
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(formatted, color = slotColors.getOrElse(index) { slotColors.last() }, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                    Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        val remainingMetrics = allMetrics.filter { it.key !in displayKeys }
                        if (remainingMetrics.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.2f))
                            Spacer(Modifier.height(8.dp))
                            Text("All Metrics", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                            Spacer(Modifier.height(4.dp))

                            remainingMetrics.forEach { m ->
                                val formatted = physicalDisplayByKey(detail, m.key)
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
                    }
                }

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
}

// ─── Bridge ──────────────────────────────────────────────────────────────────

private fun physicalDisplayByKey(detail: PhysicalDetailData, key: String): String? {
    return when (key) {
        "recovery_score_daily" -> detail.recovery?.let { "${it.toInt()}%" }
        "hrv_daily" -> detail.hrv?.let { "${it.toInt()} ms" }
        "resting_hr_daily" -> detail.restingHr?.let { "${it.toInt()} bpm" }
        "spo2_daily" -> detail.spo2?.let { "${it.toInt()}%" }
        "skin_temp_daily" -> detail.skinTemp?.let { String.format("%.1f°C", it) }
        "respiratory_rate_daily" -> detail.respiratoryRate?.let { String.format("%.1f bpm", it) }
        "stress_index_daily" -> detail.stress?.let { String.format("%.0f", it) }
        "time_in_high_hr_zones_daily" -> detail.highHrZonesMinutes?.let { "${it.toInt()} min" }
        "steps_daily" -> detail.steps?.let { "%,d".format(it) }
        "weight_daily" -> detail.weight?.let { String.format("%.1f kg", it) }
        "body_fat_daily" -> detail.bodyFat?.let { String.format("%.1f%%", it) }
        "blood_pressure_daily::systolic_mmhg" -> {
            if (detail.bloodPressureSystolic != null && detail.bloodPressureDiastolic != null)
                "${detail.bloodPressureSystolic.toInt()}/${detail.bloodPressureDiastolic.toInt()}"
            else null
        }
        "blood_glucose_daily" -> detail.bloodGlucose?.let { String.format("%.0f mg/dL", it) }
        else -> null
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
)

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
