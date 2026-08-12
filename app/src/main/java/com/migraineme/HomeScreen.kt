package com.migraineme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Star
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
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

        // While the tour's Home card is up, auto-scroll so the risk gauge is visible.
        val tourState by TourManager.state.collectAsState()
        var riskCardY by remember { mutableStateOf(0) }
        LaunchedEffect(tourState.active, tourState.stepIndex, riskCardY) {
            if (tourState.active && tourState.phase == CoachPhase.TOUR && tourState.stepIndex == 0 && riskCardY > 0) {
                kotlinx.coroutines.delay(400)
                scrollState.animateScrollTo((riskCardY - 12).coerceAtLeast(0))
            }
        }

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

        // Last visible card carries the Brainy watermark, same rule as Insights detail screens.
        val contributorsVisible = displayTriggers.isNotEmpty()
        val insightVisible = selectedDay == 0 && !state.dailyInsight.isNullOrBlank()
        val watermarkOn = when {
            contributorsVisible -> "contributors"
            insightVisible -> "insight"
            else -> "ask"
        }

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

                Box(Modifier.onGloballyPositioned { riskCardY = it.positionInParent().y.toInt() }) {
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
                }

                // ── Android silently revoked location — weather/risk are dead
                //    until it's restored. Only shows when the user still has the
                //    location metric ON server-side. ──
                LocationPermissionBanner()

                // ── Ask MigraineMe — chat assistant (premium only) ──
                PremiumGate(
                    message = t("Unlock AI Chat"),
                    subtitle = t("Ask questions about your health data"),
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
                            Box(modifier = Modifier.fillMaxWidth()) {
                                if (watermarkOn == "ask") {
                                    Box(Modifier.matchParentSize()) {
                                        Image(
                                            painter = painterResource(R.drawable.brainy_risk),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(120.dp)
                                                .align(Alignment.BottomEnd)
                                                .offset(x = 18.dp, y = 24.dp)
                                                .alpha(0.14f)
                                                .graphicsLayer(scaleX = -1f)
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    BrainyBlobIcon(resId = R.drawable.brainy_ask_small)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            t("Ask MigraineMe"),
                                            color = AppTheme.TitleColor,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                        Text(
                                            t("Chat with your health data"),
                                            color = AppTheme.SubtleTextColor,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text("\u2192", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
                                }
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
                                contentDescription = t("About Ask MigraineMe"),
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
                                Text(t("Got it"), color = AppTheme.AccentPurple)
                            }
                        },
                        title = {
                            Text(t("About Ask MigraineMe"), color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        },
                        text = {
                            Text(AskMigraineMeInfoCopy.text, modifier = Modifier.verticalScroll(rememberScrollState()), color = AppTheme.BodyTextColor,
                                style = MaterialTheme.typography.bodyMedium)
                        },
                        containerColor = AppTheme.BaseCardContainer
                    )
                }

                // ── AI Daily Insight — premium only, today only ──
                if (insightVisible) {
                    // ── Well done — the one card that is purely on the user's side.
                    // Deliberately NOT premium-gated: encouragement should not be paywalled.
                    state.positives.firstOrNull()?.let { praise ->
                        WellDoneCard(text = praise)
                    }

                    PremiumGate(
                        message = t("Unlock Daily Insights"),
                        subtitle = t("Personalised advice based on your data"),
                        onUpgrade = onNavigateToPaywall
                    ) {
                        AiInsightCard(
                            insight = state.dailyInsight!!,
                            watermark = watermarkOn == "insight"
                        )
                    }
                }

                // ── Active triggers — blurred for free users ──
                PremiumGate(
                    message = t("Unlock trigger breakdown"),
                    subtitle = t("See what\u2019s driving your risk score"),
                    onUpgrade = onNavigateToPaywall
                ) {
                    ActiveTriggersCard(
                        triggers = displayTriggers.take(3),
                        gaugeMax = state.gaugeMaxScore,
                        onTap = onNavigateToRiskDetail,
                        watermark = watermarkOn == "contributors"
                    )
                }

                // ── Medical disclaimer (dismissible, Google Play Health Content policy) ──
                MedicalDisclaimerCard(prefKey = "home_dismissed")
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
    val dayLabel = if (selectedDay == 0) t("Risk today") else {
        val date = dayRisks.getOrNull(selectedDay)?.date
        if (date != null) t("Risk · %s", date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", rememberAppLocale())))
        else t("Risk")
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
                    t("History →"),
                    color = AppTheme.AccentPurple,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clickable { onHistoryTap() }
                        .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
                )
            }
        }

        // Score + zone sit inside the gauge arc to keep the card compact
        val gaugeWithScore: @Composable () -> Unit = {
            Box(contentAlignment = Alignment.BottomCenter) {
                RiskGauge(
                    percent = clamped,
                    diameter = 200.dp,
                    stroke = 15.dp,
                    trackColor = AppTheme.TrackColor,
                    progressColor = AppTheme.AccentPurple
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "%.1f".format(riskScore),
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(t(riskZone.label),
                        color = zoneColor,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
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
                        contentDescription = t("Previous day"),
                        tint = if (selectedDay > 0) AppTheme.AccentPurple else AppTheme.SubtleTextColor.copy(alpha = 0.3f)
                    )
                }

                gaugeWithScore()

                IconButton(
                    onClick = { if (selectedDay < maxDay) onDaySelected(selectedDay + 1) },
                    enabled = selectedDay < maxDay
                ) {
                    Icon(
                        Icons.Outlined.KeyboardArrowRight,
                        contentDescription = t("Next day"),
                        tint = if (selectedDay < maxDay) AppTheme.AccentPurple else AppTheme.SubtleTextColor.copy(alpha = 0.3f)
                    )
                }
            }
        } else {
            gaugeWithScore()
        }

        SevenDayOutlook(
            values = forecast,
            selectedDay = selectedDay,
            dayRisks = dayRisks,
            onDaySelected = onDaySelected
        )
    }
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 12.dp)
        ) {
            BrainyBlobIcon(resId = R.drawable.brainy_risk_small)
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
                    contentDescription = t("About Risk today"),
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
                    Text(t("Got it"), color = AppTheme.AccentPurple)
                }
            },
            title = {
                Text(t("About Risk today"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            },
            text = {
                Text(infoText, modifier = Modifier.verticalScroll(rememberScrollState()), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
            },
            containerColor = AppTheme.BaseCardContainer
        )
    }
}

private val MEDICAL_NOTE: String get() = "\n\n" + tSync("MigraineMe is not a medical device and does not diagnose, treat, cure, or prevent any condition. This is not medical advice, always consult a qualified healthcare professional.")

object RiskInfoCopy {
    val text: String get() = tSync("Your migraine risk for today, calculated from every trigger and prodrome you've logged over the last 7 days. It's based on the bucket theory of migraine: triggers stack up inside your personal bucket, and once the level gets high enough, you tip over into an attack.\n\nProdromes count too. When you're in one (yawning, neck stiffness, mood shifts and the rest), an attack is already on the way, so logging a prodrome pushes the bucket up just like a fresh trigger.\n\nEach item's weight depends on two things: how severe you marked it (HIGH items count for more than MILD or LOW, NONE doesn't count), and how recent it was. Contributions decay each day after, so a trigger from this morning counts a lot more than one from six days ago.\n\nThe big number is your raw score (your current bucket level). The colour comes from your three personal thresholds: cross the LOW threshold and the gauge turns green; cross MILD it turns amber; cross HIGH it goes red and the bucket is close to overflowing. Thresholds start at sensible defaults and recalibrate over time so the colour reflects what a risky day actually looks like for you, not the average person.\n\nBelow the gauge, the Active Triggers card shows the top 3 things pushing on your bucket right now. Tap the gauge itself to open the Risk detail screen, which lists every contributor in order with how much each is adding. From there, tap \"History →\" at the top of the gauge to open the 14-day graph and see how your bucket level rose and fell day by day over the past two weeks (handy for spotting which days your score peaked before an attack).\n\nEverything is tunable. Open the menu and tap Manage Items to change which triggers and prodromes you care about and how severe each one is. Tap Risk Model to adjust the thresholds and the day-by-day decay curve.") + MEDICAL_NOTE
}

object AskMigraineMeInfoCopy {
    val text: String get() = tSync("Your personal AI assistant with read access to your health data: sleep, HRV, resting heart rate, stress, steps, weather, attacks, triggers, prodromes, medicines, reliefs and aura detail. Ask it anything in plain English and it answers from what it actually sees in your data, not generic advice.\n\nWondering about a treatment or medicine? You can ask here too. We'll check your data and give you something to think about.\n\nGreat prompts to try: \"What triggered my last migraine?\", \"How's my sleep been lately?\", \"Is my rescue medication actually working?\", \"Where does my aura usually show up?\", \"Are there preventive treatments I should ask my doctor about?\"\n\nHeads up: the assistant can spot patterns and suggest things to consider, but it's not a doctor and can't prescribe. For actual treatment decisions, talk to your neurologist or GP.")
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
    val dayFmt = DateTimeFormatter.ofPattern("EEE", rememberAppLocale())

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            for (i in 0 until 7) {
                val percent = values.getOrNull(i)?.coerceIn(0, 100) ?: 0
                val date = today.plusDays(i.toLong())
                val dayLabel = if (i == 0) t("Today") else date.format(dayFmt)
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

        Text(
            t("7-day outlook"),
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelMedium
        )
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
    onTap: () -> Unit = {},
    watermark: Boolean = false
) {
    if (triggers.isEmpty()) return

    var showInfo by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth().clickable { onTap() }) {
    MaybeWatermarkCard(watermark = watermark, resId = R.drawable.brainy_risk, flipWatermark = true) {
        Text(
            t("Top contributors"),
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
                        Strings.t("%1\$s%% of risk · %2\$s pts · %3\$s", pctOfTotal, t.score, if (t.daysActive == 1) Strings.t("today only") else Strings.t("%s days active", t.daysActive)),
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
                contentDescription = t("About Active triggers"),
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
                    Text(t("Got it"), color = AppTheme.AccentPurple)
                }
            },
            title = {
                Text(t("About Active triggers"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            },
            text = {
                Text(ActiveTriggersInfoCopy.text, modifier = Modifier.verticalScroll(rememberScrollState()), color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium)
            },
            containerColor = AppTheme.BaseCardContainer
        )
    }
}

object ActiveTriggersInfoCopy {
    val text: String get() = tSync("The three things pushing hardest on your bucket today, ordered by how much each is contributing to your risk score. Could be triggers you've logged (caffeine, poor sleep, stress) or prodromes you're currently in (yawning, mood shifts, neck stiffness).\n\nEach row shows the item's name, its severity tag (HIGH / MILD / LOW), and a bar showing how much it's adding compared to a fully-overflowing bucket. The more recent and the more severe, the bigger the contribution.\n\nTap the card to open the Risk detail screen and see every contributor in order, not just the top 3.") + MEDICAL_NOTE
}

@Composable
private fun WellDoneCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppTheme.BaseCardShape,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF81C784).copy(alpha = 0.10f)),
        elevation = CardDefaults.cardElevation(0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF81C784).copy(alpha = 0.25f))
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF81C784),
                modifier = Modifier.size(20.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    t("Well done"),
                    color = Color(0xFF81C784),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text,
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun AiInsightCard(insight: String, watermark: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppTheme.AccentPurple.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(Modifier.fillMaxWidth()) {
        if (watermark) {
            Box(Modifier.matchParentSize()) {
                Image(
                    painter = painterResource(R.drawable.brainy_risk),
                    contentDescription = null,
                    modifier = Modifier
                        .size(120.dp)
                        .align(Alignment.BottomEnd)
                        .offset(x = 18.dp, y = 24.dp)
                        .alpha(0.14f)
                        .graphicsLayer(scaleX = -1f)
                )
            }
        }
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
                    t("MigraineMe Recommendation"),
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

