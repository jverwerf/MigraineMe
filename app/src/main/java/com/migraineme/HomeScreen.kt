package com.migraineme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
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
import kotlin.math.cos
import kotlin.math.sin

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
                    val accessToken = SessionStore.getValidAccessToken(appCtx)
                    if (!accessToken.isNullOrBlank()) {
                        val client = HttpClient(Android)
                        try {
                            client.post("${BuildConfig.SUPABASE_URL}/functions/v1/enqueue-login-backfill") {
                                header("Authorization", "Bearer $accessToken")
                                header("Content-Type", "application/json")
                            }
                        } catch (_: Throwable) {
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
        val scrollState = rememberScrollState()

        ScrollFadeContainer(scrollState = scrollState) { scroll ->
            ScrollableScreenContent(scrollState = scroll) {
                RiskHeroCard(riskPercent = state.riskPercent)
                DataStatusCard(accessToken = auth.accessToken)
                TopTriggersCard(triggers = state.triggersAtRisk)
                RecommendationCard(recommendation = state.aiRecommendation)
            }
        }
    }
}

@Composable
private fun RiskHeroCard(
    riskPercent: Int,
    modifier: Modifier = Modifier
) {
    val clamped = riskPercent.coerceIn(0, 100)
    val label = remember(clamped) {
        when {
            clamped < 33 -> "Low"
            clamped < 67 -> "Medium"
            else -> "High"
        }
    }

    val outlook = remember(clamped) { simpleSevenDayForecast(clamped) }

    HeroCard(modifier = modifier) {
        Text(
            "Risk today",
            color = AppTheme.TitleColor,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )

        RiskGauge(
            percent = clamped,
            diameter = 220.dp,
            stroke = 16.dp,
            trackColor = AppTheme.TrackColor,
            progressColor = AppTheme.AccentPurple
        )

        Text(
            "$clamped%",
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
        )

        Text(
            label,
            color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
        )

        SevenDayOutlook(values = outlook)
    }
}

@Composable
private fun SevenDayOutlook(
    values: List<Int>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            "Next 7 days (predictive)",
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelMedium
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
        ) {
            val n = 7
            val gap = size.width * 0.03f
            val barW = (size.width - gap * (n - 1)) / n
            val maxH = size.height

            for (i in 0 until n) {
                val v = values.getOrNull(i)?.coerceIn(0, 100) ?: 0
                val t = v / 100f
                val h = (maxH * (0.25f + 0.75f * t)).coerceAtMost(maxH)
                val x = i * (barW + gap)
                val y = maxH - h

                drawRoundRect(
                    color = Color.White.copy(alpha = 0.10f),
                    topLeft = Offset(x, 0f),
                    size = Size(barW, maxH),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                )

                drawRoundRect(
                    color = lerp(AppTheme.AccentPurple, AppTheme.AccentPink, t).copy(alpha = 0.95f),
                    topLeft = Offset(x, y),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                )
            }
        }
    }
}

private fun simpleSevenDayForecast(base: Int): List<Int> {
    val b = base.coerceIn(0, 100)
    val amp = (4 + (b / 10)).coerceIn(4, 14)
    return (0 until 7).map { i ->
        val phase = (i - 1) * 0.85f
        val delta = (sin(phase) * amp).toInt()
        (b + delta).coerceIn(0, 100)
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

    // Helper lambdas for checking metric settings
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

    // Derive enabled metrics from settings
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

@Composable
private fun TopTriggersCard(triggers: List<TriggerScore>) {
    BaseCard {
        Text(
            "Top Triggers",
            color = AppTheme.TitleColor,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )

        triggers.forEach { t ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(t.name, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.width(120.dp)) {
                    LinearProgressIndicator(
                        progress = t.score / 100f,
                        modifier = Modifier.fillMaxWidth(),
                        color = AppTheme.AccentPurple,
                        trackColor = AppTheme.TrackColor
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("${t.score}%", color = AppTheme.BodyTextColor)
            }
        }
    }
}

@Composable
private fun RecommendationCard(recommendation: String) {
    BaseCard {
        Text(
            "Recommendation",
            color = AppTheme.TitleColor,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )
        Text(
            recommendation.ifBlank { "—" },
            color = AppTheme.BodyTextColor
        )
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
    val clamped = percent.coerceIn(0, 100)

    val anim = remember { Animatable(0f) }
    LaunchedEffect(clamped) {
        anim.animateTo(
            targetValue = clamped.toFloat(),
            animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing)
        )
    }
    val p = (anim.value / 100f).coerceIn(0f, 1f)

    val progressStroke = stroke
    val trackStroke = (stroke.value * 0.72f).dp

    val width = diameter
    val height = diameter * 0.62f

    Box(
        modifier = Modifier.width(width).height(height),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = (minOf(size.width, size.height * 2f) / 2f) - progressStroke.toPx()
            val cx = size.width / 2f
            val cy = size.height

            val startAngle = 180f
            val fullSweep = 180f
            val sweep = fullSweep * p

            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = fullSweep,
                useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = trackStroke.toPx(), cap = StrokeCap.Round)
            )

            val tickCount = 11
            val tickOuter = radius + trackStroke.toPx() * 0.10f
            val tickInner = radius - trackStroke.toPx() * 0.55f
            for (i in 0 until tickCount) {
                val a = startAngle + (fullSweep / (tickCount - 1)) * i
                val rad = Math.toRadians(a.toDouble())
                val ox = cx + cos(rad).toFloat() * tickOuter
                val oy = cy + sin(rad).toFloat() * tickOuter
                val ix = cx + cos(rad).toFloat() * tickInner
                val iy = cy + sin(rad).toFloat() * tickInner
                drawLine(
                    color = Color.White.copy(alpha = 0.14f),
                    start = Offset(ix, iy),
                    end = Offset(ox, oy),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            drawArc(
                color = progressColor.copy(alpha = 0.22f),
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = (progressStroke.toPx() * 1.75f), cap = StrokeCap.Round)
            )

            val segs = 42
            val segSweep = sweep / segs
            for (j in 0 until segs) {
                val t = if (segs == 1) 1f else j / (segs - 1f)
                val c = lerp(AppTheme.AccentPurple, AppTheme.AccentPink, t)
                val sa = startAngle + segSweep * j
                drawArc(
                    color = c,
                    startAngle = sa,
                    sweepAngle = segSweep.coerceAtLeast(0f),
                    useCenter = false,
                    topLeft = Offset(cx - radius, cy - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = progressStroke.toPx(), cap = StrokeCap.Round)
                )
            }

            val endAngle = startAngle + sweep
            val endRad = Math.toRadians(endAngle.toDouble())
            val ex = cx + cos(endRad).toFloat() * radius
            val ey = cy + sin(endRad).toFloat() * radius
            drawCircle(
                color = Color.White.copy(alpha = 0.90f),
                radius = (progressStroke.toPx() * 0.42f),
                center = Offset(ex, ey)
            )
            drawCircle(
                color = AppTheme.AccentPink.copy(alpha = 0.95f),
                radius = (progressStroke.toPx() * 0.30f),
                center = Offset(ex, ey)
            )
        }
    }
}