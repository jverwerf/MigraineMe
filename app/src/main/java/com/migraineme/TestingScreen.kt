package com.migraineme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Composable
fun TestingScreen(
    authVm: AuthViewModel,
    onNavigateToOnboarding: () -> Unit = {},
    onNavigateToHome: () -> Unit = {},
    onNavigateToCheckIn: () -> Unit = {},
    onNavigateToRecalibrationReview: () -> Unit = {},
) {
    val auth by authVm.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Recalibration state
    var recalibStatus by remember { mutableStateOf("") }
    var recalibRunning by remember { mutableStateOf(false) }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 60.dp) {

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Testing",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }

            DataStatusCard(accessToken = auth.accessToken)

            // ── AI Recalibration ──
            BaseCard {
                Text(
                    "AI Recalibration",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    "Invoke the monthly recalibration edge function on demand. " +
                    "This runs both AI calls (Neurologist + Statistician) and writes proposals.",
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))

                if (recalibStatus.isNotBlank()) {
                    Text(
                        recalibStatus,
                        color = if (recalibStatus.startsWith("Error")) AppTheme.AccentPink else AppTheme.AccentPurple,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            recalibRunning = true
                            recalibStatus = "Running recalibration..."
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        invokeRecalibration(ctx)
                                    }
                                    recalibStatus = result
                                } catch (e: Exception) {
                                    recalibStatus = "Error: ${e.message}"
                                } finally {
                                    recalibRunning = false
                                }
                            }
                        },
                        enabled = !recalibRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ) {
                        if (recalibRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Run Recalibration")
                    }

                    Button(
                        onClick = onNavigateToRecalibrationReview,
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ) {
                        Text("Review Proposals")
                    }
                }
            }

            // Evening Check-in
            BaseCard {
                Text(
                    "Evening Check-in",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    "Test the daily evening check-in flow",
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onNavigateToCheckIn,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                ) {
                    Text("Open Evening Check-in")
                }
            }

            // Onboarding reset
            BaseCard {
                Text(
                    "Onboarding",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )

                Text(
                    "Completed: ${OnboardingPrefs.isCompleted(ctx)}",
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )

                Button(
                    onClick = {
                        OnboardingPrefs.reset(ctx)
                        onNavigateToOnboarding()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                ) {
                    Text("Restart Onboarding")
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        TourManager.startTour()
                        onNavigateToHome()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                ) {
                    Text("Start Feature Tour Only")
                }
            }
        }
    }
}

/**
 * Calls the recalibrate edge function with force=true.
 * Returns a status string for the UI.
 */
private suspend fun invokeRecalibration(context: android.content.Context): String {
    val accessToken = SessionStore.getValidAccessToken(context.applicationContext)
        ?: return "Error: Not authenticated"

    val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/recalibrate"
    val body = """{"force": true}"""
        .toRequestBody("application/json".toMediaType())

    val request = okhttp3.Request.Builder()
        .url(url)
        .post(body)
        .header("Authorization", "Bearer $accessToken")
        .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
        .build()

    client.newCall(request).execute().use { response ->
        val responseBody = response.body?.string() ?: ""
        return if (response.isSuccessful) {
            try {
                val json = org.json.JSONObject(responseBody)
                val status = json.optString("status", "unknown")
                val proposals = json.optInt("proposals", 0)
                when (status) {
                    "ok" -> "Done! $proposals proposals generated. Tap 'Review Proposals' to see them."
                    "no_profile" -> "Please complete your profile setup first — we need your migraine profile to generate personalised suggestions."
                    "insufficient_data" -> {
                        val count = json.optInt("migraine_count", 0)
                        "No learning this month — we need at least 5 logged migraines to spot patterns (you have $count so far). Keep logging and we'll have suggestions for you soon!"
                    }
                    else -> "Status: $status — $responseBody"
                }
            } catch (_: Exception) {
                "Done (raw): $responseBody"
            }
        } else {
            "Error ${response.code}: $responseBody"
        }
    }
}

@Composable
private fun DataStatusCard(accessToken: String?) {
    val ctx = LocalContext.current.applicationContext
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone).toString()
    val afterNine = LocalTime.now(zone) >= LocalTime.of(9, 0)

    var whoopConnected by remember(accessToken, today) { mutableStateOf(false) }
    var metricSettings by remember { mutableStateOf<List<EdgeFunctionsService.MetricSettingResponse>>(emptyList()) }
    var settingsLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(accessToken, today) {
        whoopConnected = runCatching { WhoopTokenStore(ctx).load() != null }.getOrDefault(false)

        if (!accessToken.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                metricSettings = try {
                    EdgeFunctionsService().getMetricSettings(ctx)
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }
        settingsLoaded = true
    }

    val isWhoopMetricEnabled: (String) -> Boolean = { metric ->
        val setting = metricSettings.find { it.metric == metric }
        setting != null && setting.enabled && setting.preferredSource == "whoop"
    }

    val isMetricEnabled: (String) -> Boolean = { metric ->
        val setting = metricSettings.find { it.metric == metric }
        setting != null && setting.enabled
    }

    val sleepAnchorsAll = listOf("sleep_duration_daily", "sleep_score_daily")
    val sleepOptionalsAll = listOf(
        "sleep_efficiency_daily", "sleep_stages_daily", "sleep_disturbances_daily",
        "fell_asleep_time_daily", "woke_up_time_daily"
    )
    val physicalAnchorsAll = listOf("recovery_score_daily", "resting_hr_daily", "hrv_daily")
    val physicalOptionalsAll = listOf("spo2_daily", "skin_temp_daily", "time_in_high_hr_zones_daily")

    val enabledSleepAnchors = sleepAnchorsAll.filter { isWhoopMetricEnabled(it) }
    val enabledSleepOptionals = sleepOptionalsAll.filter { isWhoopMetricEnabled(it) }
    val enabledPhysicalAnchors = physicalAnchorsAll.filter { isWhoopMetricEnabled(it) }
    val enabledPhysicalOptionals = physicalOptionalsAll.filter { isWhoopMetricEnabled(it) }
    val locationEnabled = isMetricEnabled("user_location_daily")

    var sleepAnchorLoaded by remember(today, accessToken) { mutableStateOf<Boolean?>(null) }
    var physicalAnchorLoaded by remember(today, accessToken) { mutableStateOf<Boolean?>(null) }
    var locationLoaded by remember(today, accessToken) { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(today, accessToken, whoopConnected, settingsLoaded, metricSettings) {
        if (accessToken.isNullOrBlank() || !settingsLoaded) {
            sleepAnchorLoaded = null
            physicalAnchorLoaded = null
            locationLoaded = null
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            sleepAnchorLoaded = if (enabledSleepAnchors.isEmpty() && enabledSleepOptionals.isEmpty()) {
                null
            } else if (!whoopConnected) {
                false
            } else {
                runCatching { SupabaseMetricsService(ctx).hasSleepForDate(accessToken, today, "whoop") }.getOrDefault(false)
            }

            physicalAnchorLoaded = if (enabledPhysicalAnchors.isEmpty() && enabledPhysicalOptionals.isEmpty()) {
                null
            } else if (!whoopConnected) {
                false
            } else {
                runCatching { SupabasePhysicalHealthService(ctx).hasRecoveryForDate(accessToken, today, "whoop") }.getOrDefault(false)
            }

            locationLoaded = if (!locationEnabled) {
                null
            } else {
                runCatching { SupabasePersonalService(ctx).hasUserLocationForDate(accessToken, today, source = "device") }.getOrDefault(false)
            }
        }
    }

    BaseCard {
        Text(
            "Data status",
            color = AppTheme.TitleColor,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )

        Text(
            text = "WHOOP: " + if (whoopConnected) "Connected" else "Not connected",
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge
        )

        val sleepText = when (sleepAnchorLoaded) {
            null -> "Sleep: Disabled"
            true -> "Sleep: Loaded for $today"
            false -> "Sleep: Missing for $today"
        }
        Text(sleepText, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)

        val physicalText = when (physicalAnchorLoaded) {
            null -> "Recovery: Disabled"
            true -> "Recovery: Loaded for $today"
            false -> "Recovery: Missing for $today"
        }
        Text(physicalText, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)

        val locationText = when (locationLoaded) {
            null -> "Location: Disabled"
            true -> "Location: Loaded for $today"
            false -> "Location: Missing for $today"
        }
        Text(locationText, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)

        if (afterNine) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Tip: After 9:00, today's WHOOP sleep/recovery should usually be available.",
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
