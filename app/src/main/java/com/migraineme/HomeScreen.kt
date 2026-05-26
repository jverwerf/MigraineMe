package com.migraineme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Star
import androidx.compose.ui.draw.blur
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.header
import io.ktor.client.request.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun HomeScreenRoot(
    onLogout: () -> Unit,
    onNavigateToMigraine: () -> Unit = {},
    onNavigateToRiskDetail: () -> Unit = {},
    onNavigateToRecalibrationReview: () -> Unit = {},
    onNavigateToPaywall: () -> Unit = {},
    onNavigateToChatAssistant: () -> Unit = {},
    authVm: AuthViewModel,
    logVm: LogViewModel,
    vm: HomeViewModel = viewModel(),
    // Quick log VMs — created at call site or defaulted
    triggerVm: TriggerViewModel = viewModel(),
    medicineVm: MedicineViewModel = viewModel(),
    reliefVm: ReliefViewModel = viewModel(),
    prodromeVm: ProdromeViewModel = viewModel(),
    symptomVm: SymptomViewModel = viewModel(),
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

    /**
     * On Oura OAuth return, complete auth and trigger backfill (same pattern as WHOOP).
     */
    LaunchedEffect(Unit) {
        val prefs = appCtx.getSharedPreferences("oura_oauth", android.content.Context.MODE_PRIVATE)
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

                val ok = OuraAuthService().completeAuth(appCtx)

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

    // ── Load real risk score from triggers + prodromes ──
    LaunchedEffect(auth.accessToken) {
        if (!auth.accessToken.isNullOrBlank()) {
            vm.loadRisk(appCtx)
        }
    }

    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        val scrollState = rememberScrollState()

        // ── Premium state ──
        val premiumState by PremiumManager.state.collectAsState()

        // Selected day index: 0 = today (default), 1 = tomorrow, etc.
        var selectedDay by remember { mutableStateOf(0) }
        var showAskInfo by remember { mutableStateOf(false) }

        // Derive the displayed data from selected day
        val dayData = state.dayRisks.getOrNull(selectedDay)
        val displayScore = dayData?.score ?: state.riskScore
        val displayZone = dayData?.zone ?: state.riskZone
        val displayPercent = dayData?.percent ?: state.riskPercent
        val displayTriggers = dayData?.topTriggers ?: state.triggersAtRisk

        ScrollFadeContainer(scrollState = scrollState) { scroll ->
            ScrollableScreenContent(scrollState = scroll) {

                // ── Trial banner ──
                TrialBanner(onUpgrade = onNavigateToPaywall)

                RecalibrationBanner(
                    onTap = onNavigateToRecalibrationReview
                )

                // ── Quick Log Strip — above the gauge ──
                QuickLogStrip(
                    authVm = authVm,
                    triggerVm = triggerVm,
                    medicineVm = medicineVm,
                    reliefVm = reliefVm,
                    prodromeVm = prodromeVm,
                    symptomVm = symptomVm,
                    onLogComplete = { vm.loadRisk(appCtx) }
                )

                RiskHeroCard(
                    riskPercent = displayPercent,
                    riskScore = displayScore,
                    riskZone = displayZone,
                    // Gate: only show full 7-day forecast for premium users
                    forecast = if (premiumState.isPremium) state.forecast
                               else listOf(state.forecast.firstOrNull() ?: 0),
                    selectedDay = if (premiumState.isPremium) selectedDay else 0,
                    dayRisks = if (premiumState.isPremium) state.dayRisks
                               else state.dayRisks.take(1),
                    onDaySelected = {
                        if (premiumState.isPremium) {
                            selectedDay = it
                        } else {
                            onNavigateToPaywall()
                        }
                    },
                    onTap = {
                        if (premiumState.isPremium) {
                            onNavigateToRiskDetail()
                        } else {
                            onNavigateToPaywall()
                        }
                    },
                    infoText = RiskInfoCopy.text
                )

                // ── Ask MigraineMe — chat assistant (premium only) ──
                PremiumGate(
                    message = "Unlock AI Chat",
                    subtitle = "Ask questions about your health data",
                    onUpgrade = onNavigateToPaywall
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Surface(
                            onClick = onNavigateToChatAssistant,
                            shape = AppTheme.BaseCardShape,
                            color = AppTheme.BaseCardContainer,
                            border = AppTheme.BaseCardBorder,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        "Ask MigraineMe",
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        "Chat with your health data",
                                        color = Color.White.copy(alpha = 0.55f),
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                                Text(
                                    "\u2726",
                                    fontSize = 20.sp,
                                    color = AppTheme.AccentPurple,
                                    modifier = Modifier.align(Alignment.CenterStart)
                                )
                            }
                        }
                        IconButton(
                            onClick = { showAskInfo = true },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 10.dp, y = (-14).dp)
                                .size(34.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = "About Ask MigraineMe",
                                tint = AppTheme.SubtleTextColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                if (showAskInfo) {
                    AlertDialog(
                        onDismissRequest = { showAskInfo = false },
                        confirmButton = {
                            TextButton(onClick = { showAskInfo = false }) {
                                Text("Got it", color = AppTheme.AccentPurple)
                            }
                        },
                        title = {
                            Text("About Ask MigraineMe", color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        },
                        text = {
                            Text(AskMigraineMeInfoCopy.text, color = AppTheme.BodyTextColor,
                                style = MaterialTheme.typography.bodyMedium)
                        },
                        containerColor = AppTheme.BaseCardContainer
                    )
                }

                // ── AI Daily Insight — premium only, today only ──
                if (selectedDay == 0 && !state.dailyInsight.isNullOrBlank()) {
                    PremiumGate(
                        message = "Unlock Daily Insights",
                        subtitle = "Personalised advice based on your data",
                        onUpgrade = onNavigateToPaywall
                    ) {
                        AiInsightCard(insight = state.dailyInsight!!)
                    }
                }

                // ── Active triggers — blurred for free users ──
                PremiumGate(
                    message = "Unlock trigger breakdown",
                    subtitle = "See what\u2019s driving your risk score",
                    onUpgrade = onNavigateToPaywall
                ) {
                    ActiveTriggersCard(
                        triggers = displayTriggers.take(3),
                        gaugeMax = state.gaugeMaxScore,
                        onTap = onNavigateToRiskDetail
                    )
                }
            }
        }
    }
}

@Composable
private fun RiskHeroCard(
    riskPercent: Int,
    riskScore: Double = 0.0,
    riskZone: RiskZone = RiskZone.NONE,
    forecast: List<Int> = listOf(0, 0, 0, 0, 0, 0, 0),
    selectedDay: Int = 0,
    dayRisks: List<DayRisk> = emptyList(),
    onDaySelected: (Int) -> Unit = {},
    onTap: () -> Unit = {},
    onHistoryTap: (() -> Unit)? = null,
    infoText: String? = null,
    showDayArrows: Boolean = false,
    modifier: Modifier = Modifier
) {
    val clamped = riskPercent.coerceIn(0, 100)
    val maxDay = 6
    var showInfo by remember { mutableStateOf(false) }

    val zoneColor = when (riskZone) {
        RiskZone.HIGH -> Color(0xFFE57373)
        RiskZone.MILD -> Color(0xFFFFB74D)
        RiskZone.LOW -> Color(0xFF81C784)
        RiskZone.NONE -> AppTheme.SubtleTextColor
    }

    // Day label for selected day
    val dayLabel = if (selectedDay == 0) "Risk today" else {
        val date = dayRisks.getOrNull(selectedDay)?.date
        if (date != null) "Risk · ${date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))}"
        else "Risk"
    }

    Box(modifier = modifier) {
    HeroCard(modifier = Modifier.clickable { onTap() }) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                dayLabel,
                color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.align(Alignment.Center)
            )
            if (onHistoryTap != null) {
                Text(
                    "History →",
                    color = AppTheme.AccentPurple,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clickable { onHistoryTap() }
                        .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
                )
            }
        }

        if (showDayArrows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(
                    onClick = { if (selectedDay > 0) onDaySelected(selectedDay - 1) },
                    enabled = selectedDay > 0
                ) {
                    Icon(
                        Icons.Outlined.KeyboardArrowLeft,
                        contentDescription = "Previous day",
                        tint = if (selectedDay > 0) AppTheme.AccentPurple else AppTheme.SubtleTextColor.copy(alpha = 0.3f)
                    )
                }

                RiskGauge(
                    percent = clamped,
                    diameter = 220.dp,
                    stroke = 16.dp,
                    trackColor = AppTheme.TrackColor,
                    progressColor = AppTheme.AccentPurple
                )

                IconButton(
                    onClick = { if (selectedDay < maxDay) onDaySelected(selectedDay + 1) },
                    enabled = selectedDay < maxDay
                ) {
                    Icon(
                        Icons.Outlined.KeyboardArrowRight,
                        contentDescription = "Next day",
                        tint = if (selectedDay < maxDay) AppTheme.AccentPurple else AppTheme.SubtleTextColor.copy(alpha = 0.3f)
                    )
                }
            }
        } else {
            RiskGauge(
                percent = clamped,
                diameter = 220.dp,
                stroke = 16.dp,
                trackColor = AppTheme.TrackColor,
                progressColor = AppTheme.AccentPurple
            )
        }

        // Score display
        Text(
            "%.1f".format(riskScore),
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
        )

        // Zone label
        Text(
            riskZone.label,
            color = zoneColor,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
        )

        SevenDayOutlook(
            values = forecast,
            selectedDay = selectedDay,
            dayRisks = dayRisks,
            onDaySelected = onDaySelected
        )
    }
        if (infoText != null) {
            IconButton(
                onClick = { showInfo = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 10.dp, y = (-14).dp)
                    .size(34.dp)
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = "About Risk today",
                    tint = AppTheme.SubtleTextColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    if (showInfo && infoText != null) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text("Got it", color = AppTheme.AccentPurple)
                }
            },
            title = {
                Text("About Risk today", color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            },
            text = {
                Text(infoText, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
            },
            containerColor = AppTheme.BaseCardContainer
        )
    }
}

object RiskInfoCopy {
    const val text = "Your migraine risk for today, calculated from every trigger and prodrome you've logged over the last 7 days. It's based on the bucket theory of migraine: triggers stack up inside your personal bucket, and once the level gets high enough, you tip over into an attack.\n\nProdromes count too. When you're in one (yawning, neck stiffness, mood shifts and the rest), an attack is already on the way, so logging a prodrome pushes the bucket up just like a fresh trigger.\n\nEach item's weight depends on two things: how severe you marked it (HIGH items count for more than MILD or LOW, NONE doesn't count), and how recent it was. Contributions decay each day after, so a trigger from this morning counts a lot more than one from six days ago.\n\nThe big number is your raw score (your current bucket level). The colour comes from your three personal thresholds: cross the LOW threshold and the gauge turns green; cross MILD it turns amber; cross HIGH it goes red and the bucket is close to overflowing. Thresholds start at sensible defaults and recalibrate over time so the colour reflects what a risky day actually looks like for you, not the average person.\n\nBelow the gauge, the Active Triggers card shows the top 3 things pushing on your bucket right now. Tap the gauge itself to open the Risk detail screen, which lists every contributor in order with how much each is adding. From there, tap \"History →\" at the top of the gauge to open the 14-day graph and see how your bucket level rose and fell day by day over the past two weeks (handy for spotting which days your score peaked before an attack).\n\nEverything is tunable. Open the menu and tap Manage Items to change which triggers and prodromes you care about and how severe each one is. Tap Risk Model to adjust the thresholds and the day-by-day decay curve."
}

object AskMigraineMeInfoCopy {
    const val text = "Your personal AI assistant with read access to your health data: sleep, HRV, resting heart rate, stress, steps, weather, attacks, triggers, prodromes, medicines and reliefs. Ask it anything in plain English and it answers from what it actually sees in your data, not generic advice.\n\nWondering about a treatment or medicine? You can ask here too. We'll check your data and give you something to think about.\n\nGreat prompts to try: \"What triggered my last migraine?\", \"How's my sleep been lately?\", \"Is my rescue medication actually working?\", \"Are there preventive treatments I should ask my doctor about?\"\n\nHeads up: the assistant can spot patterns and suggest things to consider, but it's not a doctor and can't prescribe. For actual treatment decisions, talk to your neurologist or GP."
}

@Composable
private fun SevenDayOutlook(
    values: List<Int>,
    selectedDay: Int = 0,
    dayRisks: List<DayRisk> = emptyList(),
    onDaySelected: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val dayFmt = DateTimeFormatter.ofPattern("EEE")

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            "7-day forecast",
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            for (i in 0 until 7) {
                val percent = values.getOrNull(i)?.coerceIn(0, 100) ?: 0
                val date = today.plusDays(i.toLong())
                val dayLabel = if (i == 0) "Today" else date.format(dayFmt)
                val isSelected = i == selectedDay

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.clickable { onDaySelected(i) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(
                                        color = AppTheme.AccentPurple.copy(alpha = 0.25f),
                                        shape = CircleShape
                                    )
                            )
                        }
                        MiniGauge(
                            percent = percent,
                            size = 36.dp,
                            strokeWidth = 3.5.dp
                        )
                    }
                    Text(
                        dayLabel,
                        color = if (isSelected) AppTheme.AccentPurple else if (i == 0) Color.White else AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniGauge(
    percent: Int,
    size: Dp,
    strokeWidth: Dp
) {
    val clamped = percent.coerceIn(0, 100)
    val p = clamped / 100f

    val progressColor = lerp(AppTheme.AccentPurple, AppTheme.AccentPink, p)

    Box(
        modifier = Modifier.width(size).height(size * 0.62f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sw = strokeWidth.toPx()
            val radius = (minOf(this.size.width, this.size.height * 2f) / 2f) - sw
            val cx = this.size.width / 2f; val cy = this.size.height
            drawArc(AppTheme.TrackColor, 180f, 180f, false,
                Offset(cx - radius, cy - radius), Size(radius * 2f, radius * 2f),
                style = Stroke(sw * 0.7f, cap = StrokeCap.Round))
            if (p > 0f) {
                drawArc(progressColor, 180f, 180f * p, false,
                    Offset(cx - radius, cy - radius), Size(radius * 2f, radius * 2f),
                    style = Stroke(sw, cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun ActiveTriggersCard(
    triggers: List<TriggerScore>,
    gaugeMax: Double = 10.0,
    onTap: () -> Unit = {}
) {
    if (triggers.isEmpty()) return

    var showInfo by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
    BaseCard(modifier = Modifier.fillMaxWidth().clickable { onTap() }) {
        Text(
            "Top contributors",
            color = AppTheme.TitleColor,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )

        Spacer(Modifier.height(8.dp))

        triggers.forEach { t ->
            val sevColor = when (t.severity.uppercase()) {
                "HIGH" -> Color(0xFFE57373)
                "MILD" -> Color(0xFFFFB74D)
                else -> Color(0xFF81C784)
            }
            val sevBg = sevColor.copy(alpha = 0.12f)
            val totalPts = triggers.sumOf { it.score }.coerceAtLeast(1)
            val pctOfTotal = (t.score * 100) / totalPts
            val barFraction = (t.score.toFloat() / gaugeMax.toFloat()).coerceIn(0f, 1f)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(sevColor, CircleShape)
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Top row: name + severity chip
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            t.name,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(Modifier.width(8.dp))

                        Box(
                            modifier = Modifier
                                .background(
                                    color = sevBg,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                t.severity,
                                color = sevColor,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    // Progress bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(
                                color = AppTheme.TrackColor,
                                shape = RoundedCornerShape(2.dp)
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(barFraction)
                                .height(4.dp)
                                .background(
                                    color = sevColor,
                                    shape = RoundedCornerShape(2.dp)
                                )
                        )
                    }

                    // Bottom row: points + days active
                    Text(
                        "${pctOfTotal}% of risk · ${t.score} pts · ${if (t.daysActive == 1) "today only" else "${t.daysActive} days active"}",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
        IconButton(
            onClick = { showInfo = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 10.dp, y = (-14).dp)
                .size(34.dp)
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = "About Active triggers",
                tint = AppTheme.SubtleTextColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text("Got it", color = AppTheme.AccentPurple)
                }
            },
            title = {
                Text("About Active triggers", color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            },
            text = {
                Text(ActiveTriggersInfoCopy.text, color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium)
            },
            containerColor = AppTheme.BaseCardContainer
        )
    }
}

object ActiveTriggersInfoCopy {
    const val text = "The three things pushing hardest on your bucket today, ordered by how much each is contributing to your risk score. Could be triggers you've logged (caffeine, poor sleep, stress) or prodromes you're currently in (yawning, mood shifts, neck stiffness).\n\nEach row shows the item's name, its severity tag (HIGH / MILD / LOW), and a bar showing how much it's adding compared to a fully-overflowing bucket. The more recent and the more severe, the bigger the contribution.\n\nTap the card to open the Risk detail screen and see every contributor in order, not just the top 3."
}

@Composable
private fun AiInsightCard(insight: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppTheme.AccentPurple.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Star,
                contentDescription = null,
                tint = AppTheme.AccentPurple,
                modifier = Modifier.size(20.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "MigraineMe Recommendation",
                    color = AppTheme.AccentPurple,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    insight,
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
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

