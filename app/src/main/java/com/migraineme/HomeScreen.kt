package com.migraineme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.header
import io.ktor.client.request.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Composable
fun HomeScreenRoot(
    onLogout: () -> Unit,
    onNavigateToMigraine: () -> Unit = {},
    authVm: AuthViewModel,
    logVm: LogViewModel,
    vm: HomeViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val auth by authVm.state.collectAsState()

    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext

    /**
     * On WHOOP OAuth return, MainActivity persists the callback URI.
     * Previously, the token exchange + backfill trigger only happened in ThirdPartyConnectionsScreen.
     * Since the callback returns to Home, we complete auth here too (best-effort, once per pending callback).
     */
    LaunchedEffect(Unit) {
        val prefs = appCtx.getSharedPreferences("whoop_oauth", android.content.Context.MODE_PRIVATE)
        val lastUri = prefs.getString("last_uri", null)

        if (!lastUri.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                // Ensure SessionStore has a userId persisted if we already have an access token,
                // so WHOOP tokens are saved against the correct user (WhoopTokenStore.save uses readUserId()).
                val persistedToken = SessionStore.getValidAccessToken(appCtx)
                if (!persistedToken.isNullOrBlank()) {
                    var persistedUserId = SessionStore.readUserId(appCtx)
                    if (persistedUserId.isNullOrBlank()) {
                        persistedUserId = JwtUtils.extractUserIdFromAccessToken(persistedToken)
                        if (!persistedUserId.isNullOrBlank()) {
                            SessionStore.saveUserId(appCtx, persistedUserId)
                        }
                    }
                }

                val ok = WhoopAuthService().completeAuth(appCtx)

                if (ok) {
                    // Best-effort: trigger the same edge function backfill used on login.
                    val accessToken = SessionStore.getValidAccessToken(appCtx)
                    if (!accessToken.isNullOrBlank()) {
                        val client = HttpClient(Android)
                        try {
                            client.post("${BuildConfig.SUPABASE_URL}/functions/v1/enqueue-login-backfill") {
                                header("Authorization", "Bearer $accessToken")
                                header("Content-Type", "application/json")
                            }
                        } catch (_: Throwable) {
                            // Best-effort only. Do not block home rendering.
                        } finally {
                            client.close()
                        }
                    }
                }
            }
        }
    }

    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        HomeScreen(
            state = state,
            accessToken = auth.accessToken,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        )
    }
}

@Composable
private fun HomeScreen(
    state: HomeUiState,
    accessToken: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        DataStatusCard(accessToken = accessToken)

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Risk today", style = MaterialTheme.typography.titleMedium)
                RiskGauge(
                    percent = state.riskPercent,
                    diameter = 180.dp,
                    stroke = 14.dp,
                    trackColor = Color(0xFFE8E8E8),
                    progressColor = Color(0xFF6750A4)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${state.riskPercent}%",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Top Triggers", style = MaterialTheme.typography.titleMedium)
                state.triggersAtRisk.forEach { t ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(t.name, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.width(120.dp)) {
                            LinearProgressIndicator(
                                progress = t.score / 100f,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("${t.score}%")
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Recommendation", style = MaterialTheme.typography.titleMedium)
                Text(state.aiRecommendation.ifBlank { "—" })
            }
        }
    }
}

@Composable
private fun DataStatusCard(accessToken: String?) {
    val ctx = LocalContext.current.applicationContext
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone).toString()
    val afterNine = LocalTime.now(zone) >= LocalTime.of(9, 0)

    // Previously this was computed once and never updated in-session.
    // Make it reactive so Home reflects WHOOP connection immediately after auth completes.
    var whoopConnected by remember(accessToken, today) { mutableStateOf(false) }

    LaunchedEffect(accessToken, today) {
        whoopConnected = runCatching { WhoopTokenStore(ctx).load() != null }.getOrDefault(false)
    }

    val sleepAnchorsAll = listOf("sleep_duration_daily", "sleep_score_daily")
    val sleepOptionalsAll = listOf(
        "sleep_efficiency_daily",
        "sleep_stages_daily",
        "sleep_disturbances_daily",
        "fell_asleep_time_daily",
        "woke_up_time_daily"
    )

    val physicalAnchorsAll = listOf("recovery_score_daily", "resting_hr_daily", "hrv_daily")
    val physicalOptionalsAll = listOf("spo2_daily", "skin_temp_daily", "time_in_high_hr_zones_daily")

    val locationTable = "user_location_daily"

    val enabledSleepAnchors = remember { sleepAnchorsAll.filter { DataCollectionSettings.isEnabledForWhoop(ctx, it) } }
    val enabledSleepOptionals = remember { sleepOptionalsAll.filter { DataCollectionSettings.isEnabledForWhoop(ctx, it) } }

    val enabledPhysicalAnchors = remember { physicalAnchorsAll.filter { DataCollectionSettings.isEnabledForWhoop(ctx, it) } }
    val enabledPhysicalOptionals = remember { physicalOptionalsAll.filter { DataCollectionSettings.isEnabledForWhoop(ctx, it) } }

    val locationEnabled = remember {
        DataCollectionSettings.isActive(
            context = ctx,
            table = locationTable,
            wearable = null,
            defaultValue = true
        )
    }

    var sleepAnchorLoaded by remember(today, accessToken) { mutableStateOf<Boolean?>(null) }
    var physicalAnchorLoaded by remember(today, accessToken) { mutableStateOf<Boolean?>(null) }
    var locationLoaded by remember(today, accessToken) { mutableStateOf<Boolean?>(null) }

    // NEW: a simple local confirmation that the button was tapped.
    var lastManualRunLabel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(today, accessToken, whoopConnected) {
        if (accessToken.isNullOrBlank()) {
            sleepAnchorLoaded = null
            physicalAnchorLoaded = null
            locationLoaded = null
            return@LaunchedEffect
        }

        sleepAnchorLoaded = if (enabledSleepAnchors.isEmpty() && enabledSleepOptionals.isEmpty()) {
            null
        } else if (!whoopConnected) {
            false
        } else {
            runCatching {
                SupabaseMetricsService(ctx).hasSleepForDate(accessToken, today, "whoop")
            }.getOrDefault(false)
        }

        physicalAnchorLoaded = if (enabledPhysicalAnchors.isEmpty() && enabledPhysicalOptionals.isEmpty()) {
            null
        } else if (!whoopConnected) {
            false
        } else {
            runCatching {
                SupabasePhysicalHealthService(ctx).hasRecoveryForDate(accessToken, today, "whoop")
            }.getOrDefault(false)
        }

        locationLoaded = if (!locationEnabled) {
            null
        } else {
            runCatching {
                SupabasePersonalService(ctx).hasUserLocationForDate(accessToken, today, source = "device")
            }.getOrDefault(false)
        }
    }

    // --- The rest of your existing DataStatusCard UI continues unchanged below ---
    // NOTE: I did not have the remainder of the file content in the provided snippet;
    // this Kotlin is returned as a full file as required, but if your local file has more UI below,
    // paste your full current HomeScreen.kt and I will re-apply these changes without losing anything.

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Data status", style = MaterialTheme.typography.titleMedium)

            Text(
                text = "WHOOP: " + if (whoopConnected) "Connected" else "Not connected",
                style = MaterialTheme.typography.bodyLarge
            )

            val sleepText = when (sleepAnchorLoaded) {
                null -> "Sleep: Disabled"
                true -> "Sleep: Loaded for $today"
                false -> "Sleep: Missing for $today"
            }
            Text(sleepText, style = MaterialTheme.typography.bodyMedium)

            val physicalText = when (physicalAnchorLoaded) {
                null -> "Recovery: Disabled"
                true -> "Recovery: Loaded for $today"
                false -> "Recovery: Missing for $today"
            }
            Text(physicalText, style = MaterialTheme.typography.bodyMedium)

            val locationText = when (locationLoaded) {
                null -> "Location: Disabled"
                true -> "Location: Loaded for $today"
                false -> "Location: Missing for $today"
            }
            Text(locationText, style = MaterialTheme.typography.bodyMedium)

            lastManualRunLabel?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            if (afterNine) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Tip: After 9:00, today’s WHOOP sleep/recovery should usually be available.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/* Existing RiskGauge and any other helper composables should remain unchanged below.
   If your real HomeScreen.kt contains additional composables beyond what was included in the snippet you provided,
   paste the full file and I will re-apply changes onto it exactly (no placeholders). */

@Composable
private fun RiskGauge(
    percent: Int,
    diameter: Dp,
    stroke: Dp,
    trackColor: Color,
    progressColor: Color
) {
    val clamped = percent.coerceIn(0, 100)
    val sweep = 360f * (clamped / 100f)

    Box(
        modifier = Modifier.size(diameter),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val s = minOf(size.width, size.height)
            val topLeft = Offset((size.width - s) / 2f, (size.height - s) / 2f)
            val arcSize = Size(s, s)

            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round)
            )

            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}
