package com.migraineme

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

        // ---- Risk Gauge ----
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

        // ---- Top Triggers ----
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

        // ---- AI Recommendation ----
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

/**
 * Data Status Card behavior:
 * - Refresh on app resume (foreground)
 * - Optional periodic refresh between 06:00 and 09:00 while Home is visible,
 *   until anchors are loaded (no WHOOP API calls here; only Supabase hasXForDate + local log reads)
 * - Manual refresh button
 * - User can "slide away" (dismiss) for today (stored locally)
 *
 * Supabase remains the source of truth for group "Loaded".
 * WhoopSyncLogStore outcomes are only used to explain optional warnings (no-data / couldn't-fetch).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DataStatusCard(accessToken: String?) {
    val ctx = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val zone = ZoneId.systemDefault()

    val today = remember { LocalDate.now(zone).toString() }
    val nowTime = remember { LocalTime.now(zone) }

    // ----- Dismiss for today (slide away) -----
    val prefs = remember { ctx.getSharedPreferences("home_status_prefs", Context.MODE_PRIVATE) }
    val dismissedKey = "dismissed_date"
    var dismissedToday by remember {
        mutableStateOf((prefs.getString(dismissedKey, null) ?: "") == today)
    }

    fun dismissForToday() {
        prefs.edit().putString(dismissedKey, today).apply()
        dismissedToday = true
    }

    // ----- Refresh trigger -----
    var refreshNonce by remember { mutableIntStateOf(0) }
    fun requestRefresh() { refreshNonce++ }

    // Refresh when app resumes
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                requestRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // WHOOP connected = token exists locally
    val whoopConnected = remember {
        runCatching { WhoopTokenStore(ctx).load() != null }.getOrDefault(false)
    }

    // Tables
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

    // Anchor presence (Supabase)
    var sleepAnchorLoaded by remember(today) { mutableStateOf<Boolean?>(null) }
    var physicalAnchorLoaded by remember(today) { mutableStateOf<Boolean?>(null) }
    var locationLoaded by remember(today) { mutableStateOf<Boolean?>(null) }

    val logStore = remember { WhoopSyncLogStore(ctx) }

    /**
     * Optional warnings:
     * - Only show if we have a recorded outcome today.
     * - Do NOT show "pending" for optionals (unknown is not actionable).
     */
    fun optionalWarningReasonOrNull(table: String): String? {
        val o = logStore.getOutcome(today, table) ?: return null
        return when (o.type) {
            WhoopSyncLogStore.TableOutcomeType.FETCH_FAILED -> "couldn't fetch"
            WhoopSyncLogStore.TableOutcomeType.FETCH_OK_NO_DATA -> "no data"
            WhoopSyncLogStore.TableOutcomeType.FETCH_OK_STORED -> null
        }
    }

    suspend fun refreshNow() {
        if (accessToken.isNullOrBlank()) {
            sleepAnchorLoaded = null
            physicalAnchorLoaded = null
            locationLoaded = null
            return
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

    // Primary refresh: on resume + manual + internal ticks
    LaunchedEffect(today, accessToken, refreshNonce) {
        refreshNow()
    }

    // Periodic refresh window while visible: 06:00–09:00
    // - Only if something relevant is enabled
    // - Stops early if both WHOOP anchors are loaded (or not collecting) and location loaded (or not collecting)
    LaunchedEffect(today, accessToken) {
        if (dismissedToday) return@LaunchedEffect

        fun withinWindow(): Boolean {
            val t = LocalTime.now(zone)
            return !t.isBefore(LocalTime.of(6, 0)) && t.isBefore(LocalTime.of(9, 0))
        }

        val anyWhoopEnabled =
            (enabledSleepAnchors.isNotEmpty() || enabledSleepOptionals.isNotEmpty() ||
                    enabledPhysicalAnchors.isNotEmpty() || enabledPhysicalOptionals.isNotEmpty())
        val anyEnabled = anyWhoopEnabled || locationEnabled

        if (!anyEnabled) return@LaunchedEffect
        if (!withinWindow()) return@LaunchedEffect

        while (isActive && withinWindow()) {
            // Stop if everything that's enabled is loaded (anchors only, since optionals are warnings)
            val sleepOk = (enabledSleepAnchors.isEmpty() && enabledSleepOptionals.isEmpty()) || (sleepAnchorLoaded == true)
            val physOk = (enabledPhysicalAnchors.isEmpty() && enabledPhysicalOptionals.isEmpty()) || (physicalAnchorLoaded == true)
            val locOk = (!locationEnabled) || (locationLoaded == true)

            if (sleepOk && physOk && locOk) break

            // Refresh every 15 minutes while Home is visible in the window
            delay(15 * 60 * 1000L)
            requestRefresh()
        }
    }

    // Slide-away UI
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart || value == SwipeToDismissBoxValue.StartToEnd) {
                dismissForToday()
                true
            } else {
                false
            }
        }
    )

    AnimatedVisibility(visible = !dismissedToday) {
        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = {
                // Keep background empty to avoid visual noise
            },
            content = {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Data status", style = MaterialTheme.typography.titleMedium)

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { requestRefresh() }) {
                                    Text("Refresh")
                                }
                                TextButton(onClick = { dismissForToday() }) {
                                    Text("Hide today")
                                }
                            }
                        }

                        val afterNine = LocalTime.now(zone) >= LocalTime.of(9, 0)

                        StatusGroupWhoop(
                            title = "Sleep",
                            enabledAnchors = enabledSleepAnchors,
                            enabledOptionals = enabledSleepOptionals,
                            anchorLoaded = sleepAnchorLoaded,
                            afterNine = afterNine,
                            wearableConnected = whoopConnected,
                            optionalWarningReasonOrNull = ::optionalWarningReasonOrNull
                        )

                        StatusGroupWhoop(
                            title = "Physical",
                            enabledAnchors = enabledPhysicalAnchors,
                            enabledOptionals = enabledPhysicalOptionals,
                            anchorLoaded = physicalAnchorLoaded,
                            afterNine = afterNine,
                            wearableConnected = whoopConnected,
                            optionalWarningReasonOrNull = ::optionalWarningReasonOrNull
                        )

                        StatusGroupLocation(
                            title = "Location",
                            enabled = locationEnabled,
                            loaded = locationLoaded,
                            afterNine = afterNine
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun StatusGroupWhoop(
    title: String,
    enabledAnchors: List<String>,
    enabledOptionals: List<String>,
    anchorLoaded: Boolean?,
    afterNine: Boolean,
    wearableConnected: Boolean,
    optionalWarningReasonOrNull: (String) -> String?
) {
    if (enabledAnchors.isEmpty() && enabledOptionals.isEmpty()) {
        Text("$title: not collecting", color = Color.Gray)
        return
    }

    if (!wearableConnected) {
        Text("$title: not connected", fontWeight = FontWeight.Medium)
        Text(
            "• Connect your wearable to collect this data.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
        return
    }

    val status = when (anchorLoaded) {
        true -> "Loaded"
        false -> if (afterNine) "Still fetching" else "Fetching"
        null -> "Checking…"
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$title: $status", fontWeight = FontWeight.Medium)

        // Optional warnings (only when we have a recorded reason; no "pending" spam)
        enabledOptionals.forEach { table ->
            val reason = optionalWarningReasonOrNull(table)
            if (reason != null) {
                Text("• $table: $reason", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun StatusGroupLocation(
    title: String,
    enabled: Boolean,
    loaded: Boolean?,
    afterNine: Boolean
) {
    if (!enabled) {
        Text("$title: not collecting", color = Color.Gray)
        return
    }

    val status = when (loaded) {
        true -> "Loaded"
        false -> if (afterNine) "Still fetching" else "Fetching"
        null -> "Checking…"
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$title: $status", fontWeight = FontWeight.Medium)
        if (loaded == false) {
            Text("• user_location_daily: pending", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

@Composable
private fun RiskGauge(
    percent: Int,
    diameter: Dp,
    stroke: Dp,
    trackColor: Color,
    progressColor: Color
) {
    val p = percent.coerceIn(0, 100) / 100f
    Canvas(modifier = Modifier.size(diameter)) {
        val strokePx = stroke.toPx()
        val arcSize = Size(size.minDimension - strokePx, size.minDimension - strokePx)
        val topLeft = Offset(
            (size.width - arcSize.width) / 2f,
            (size.height - arcSize.height) / 2f
        )
        drawArc(
            color = trackColor,
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
            size = arcSize,
            topLeft = topLeft
        )
        drawArc(
            color = progressColor,
            startAngle = 135f,
            sweepAngle = 270f * p,
            useCenter = false,
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
            size = arcSize,
            topLeft = topLeft
        )
    }
}
