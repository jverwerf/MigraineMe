package com.migraineme

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object SetupScrollState {
    var scrollPosition by mutableStateOf(0)
}

enum class NavHintLocation(val icon: ImageVector, val label: String) {
    BOTTOM_HOME(Icons.Outlined.Home, "Home tab"),
    BOTTOM_MONITOR(Icons.Outlined.Timeline, "Monitor tab"),
    BOTTOM_INSIGHTS(Icons.Outlined.BarChart, "Insights tab"),
    BOTTOM_MIGRAINE(Icons.Outlined.Psychology, "Migraine tab"),
    BOTTOM_JOURNAL(Icons.Outlined.History, "Journal tab"),
    TOP_COMMUNITY(Icons.Outlined.Groups, "Community icon (top right)"),
    TOP_SETTINGS(Icons.Outlined.Settings, "Settings menu (top left)"),
}

data class TourStep(
    val route: String,
    val icon: ImageVector,
    val title: String,
    val body: String,
    val highlight: String,
    val interactive: Boolean = false,
    val spotlightKey: String? = null,
    val navHint: NavHintLocation? = null,
    val bottomCard: Boolean = false,
)

val tourSteps = listOf(
    TourStep(Routes.HOME, Icons.Outlined.Home, "Home — Your Risk Today",
        "Your migraine likelihood for today, based on the bucket theory: every trigger and prodrome from the last 7 days stacks up in your personal bucket, recent days weighted more than older ones.\n\nThe gauge turns amber when you cross your MILD threshold, red when you cross HIGH.\n\nAbove the gauge is Quick Log for one-tap entries (migraine, prodrome, trigger, medicine, relief, symptom). Below it: Ask MigraineMe (chat with your data) and the top 3 contributors to today's score.",
        "Tap any day on the 7-day forecast strip to preview future risk, or tap the gauge for the full contributor breakdown.",
        navHint = NavHintLocation.BOTTOM_HOME),
    TourStep(Routes.MONITOR, Icons.Outlined.Timeline, "Monitor — Your Health Data",
        "Every relevant health signal in one place, each as its own card.\n\nRisk shows your current bucket level, top contributors, and the favourites you've pinned. Migraines rolls up this week's attacks (count, avg severity, vs last week, all-time total). Medicines tracks 30-day usage in D/W/M totals. Treatments scores each drug or lifestyle change as Working well / Showing progress / Some effect / Not noticeable, compared against your migraine days before it started.\n\nSleep, Physical, Mental, Environment, and Nutrition pull live from your wearable, phone, weather feed, and any nutrition app linked through Health Connect or HealthKit (caffeine, sodium, tyramine, alcohol, gluten and histamine get flagged automatically).\n\nMenstruation predicts your next period and feeds it back into the risk score. Tap any card for the detail screen with charts and a history view.",
        "Hit Customize at the top to reorder cards (drag on Android, arrows on iOS) or hide ones you don't care about. The order saves to your account.",
        navHint = NavHintLocation.BOTTOM_MONITOR),
    TourStep(Routes.MONITOR_NUTRITION, Icons.Outlined.Restaurant, "Diet — What You're Eating",
        "Three ways to log food: scan a barcode, search the USDA database (700,000+ items), or let Health Connect / HealthKit pull from MyFitnessPal, Cronometer, or any nutrition app you already use.\n\nMigraineMe automatically scores each item against the nutrients that matter most for migraine. For everyday tracking that's caffeine, sodium and sugar.\n\nOn top of those, four known dietary triggers get flagged whenever they show up: tyramine (aged cheeses, cured meats), alcohol, gluten, and histamine (fermented foods). Excess in any of these, or a sudden withdrawal like skipping your usual coffee, feeds straight into your risk score.\n\nYou pick which three metrics show on the card; the detail screen has the full breakdown plus a daily history chart.",
        "Try the barcode scanner on something in your kitchen, or search a food to see how tyramine, alcohol, gluten and histamine get flagged when any of them are present.",
        interactive = true,
        navHint = NavHintLocation.BOTTOM_MONITOR),
    TourStep(Routes.INSIGHTS, Icons.Outlined.BarChart, "Insights — What Your Data Says",
        "Seven cards that turn your logs into something actionable.\n\nFull Report builds a doctor-ready PDF with every metric for the period you choose. AI Recommendations reads your data and suggests changes across six categories (triggers, prodromes, medicines, reliefs, activities, symptoms), including overuse flags for medicines.\n\nAccuracy scores your gauge against what actually happened: true positives, false alarms, and missed attacks. What Happened ranks each trigger and prodrome by how much it lifts your attack risk.\n\nWhat Worked scores each treatment by its impact on severity and duration. What Were You Doing shows which activities and locations turn up most around your attacks. How Did It Impact You rolls up severity, duration, pain locations and missed activities.",
        "Tap Full Report for the PDF, or AI Recommendations to review and accept or reject each suggestion with one tap.",
        navHint = NavHintLocation.BOTTOM_INSIGHTS),
    TourStep(Routes.RECALIBRATION_REVIEW, Icons.Outlined.AutoAwesome, "AI Calibration — Tune Your Risk Model",
        "Once a month MigraineMe re-reads your data and proposes adjustments to two things that drive your gauge:\n\n• Your personal thresholds (where LOW becomes MILD becomes HIGH).\n• The day-by-day decay curve (how fast a trigger from 6 days ago counts vs. one from this morning).\n\nWhat counts as a 'risky day' for you isn't the same as someone else, and your bucket shape shifts as you log more — so the calibration nudges your model 20-30% toward whatever actually predicts your attacks best.",
        "Each suggestion lands on the review screen with a one-tap accept or reject. You can always tweak the same values manually under Risk Model.",
        navHint = NavHintLocation.BOTTOM_INSIGHTS),
    TourStep(Routes.MIGRAINE, Icons.Outlined.Psychology, "Log — Capture Everything",
        "Tap Log Migraine for the full 13-step wizard (timing, symptoms, pain, prodromes, triggers, medicines, reliefs, locations, activities, postdromes, missed activities, notes, review).\n\nIf you're in the middle of an attack, there's an \"It's happening right now\" shortcut on the timing step that saves the entry in one tap so you don't have to answer 12 more questions while in pain.\n\nBelow the hero card is Quick Log: one-tap entry for a single migraine, prodrome, trigger, medicine, relief, or symptom without a full wizard.\n\nDaily Check-In is the evening rollup where the app lists everything it caught automatically (sleep, weather, nutrition, activity) and you confirm, plus capture postdrome symptoms tied to today's attack.",
        "Test the \"It's happening right now\" button on the first wizard step; it logs immediately so you can come back and fill in details later.",
        navHint = NavHintLocation.BOTTOM_MIGRAINE),
    TourStep(Routes.TRIGGERS, Icons.Outlined.Whatshot, "Auto-Captured Data — Less Logging, More Tracking",
        "You don't have to manually log every trigger. MigraineMe pulls signals from your wearable, phone, location, and the apps you've linked (Health Connect / HealthKit) and converts them into trigger logs automatically.\n\nThe big ones: poor sleep or sudden over-sleep, low HRV vs. your baseline, falling barometric pressure, hot or humid days, period-prediction windows, and nutrient flags from anything you ate (caffeine, sodium, tyramine, alcohol, gluten, histamine).\n\nActivities like workouts, runs, walks, and meditations get pulled in the same way, so they appear under your daily logs without you tapping anything.\n\nEven prodromes can be auto-suggested when your data shows the signature pattern (HRV dip, sleep disruption, mood shift) that usually precedes one of your attacks.\n\nAnything auto-captured shows up on your Triggers screen with a timestamp so you can see exactly when it landed.",
        "If the app is detecting something you don't trust, tap the item and adjust its severity in Manage Items, or turn the whole source off under Data Settings.",
        navHint = NavHintLocation.BOTTOM_MIGRAINE, bottomCard = true),
    TourStep(Routes.EVENING_CHECKIN, Icons.Outlined.Nightlight, "Daily Check-In — One-Tap Review of Your Day",
        "Every evening MigraineMe sends a single notification asking how today went.\n\nOpen it and you get a list of everything the app already caught for you (sleep, weather, nutrients, activities, prodromes), plus a sweep across triggers, prodromes, medicines, reliefs, activities, and a \"From your calendar\" page that lifts events out of your phone calendar so you can tag them as triggers, reliefs, or activities in one tap.\n\nFor every active treatment regimen, you also get a side-effects page so the Treatments card knows what to weigh against efficacy.\n\nPostdromes are linked here too: instead of asking \"do you have a postdrome right now?\" which usually means nothing, the check-in walks you back through any attack you had today and asks what came after.\n\nTwo ways to fill in anything missing: tap the mic and talk through your day in plain English (voice-to-text AI parses out triggers, prodromes, medicines, reliefs, mood, postdromes, anything you mention), or step through the form manually.",
        "Try the voice input on a real day. The AI is forgiving, you can ramble through \"tired, had wine and pasta, took ibuprofen at 4, felt better by 8\" and it'll log all four.",
        navHint = NavHintLocation.BOTTOM_MIGRAINE, bottomCard = true),
    TourStep(Routes.MENSTRUATION_SETTINGS, Icons.Outlined.CalendarMonth, "Menstrual Cycle — A Real Risk Factor",
        "Up to 60% of women with migraine report cycle-linked attacks, most commonly in the 2 days before and the first 3 days of bleeding when oestrogen falls sharply.\n\nSet your last period date and cycle length and MigraineMe predicts every upcoming window.\n\nThe prediction feeds straight into your risk score as a \"menstruation_predicted\" factor with a symmetric weight around the predicted date, so the gauge knows to expect the spike before it happens.\n\nThe Menstruation card on Monitor shows your next predicted period, days until, and cycle length.",
        "Open the card and tap Edit to set or correct your last period date. If your cycle is irregular, log each actual period as it happens and the prediction adjusts over time.",
        navHint = NavHintLocation.BOTTOM_MONITOR),
    TourStep(Routes.JOURNAL, Icons.Outlined.History, "Journal — Everything You've Logged",
        "A chronological feed of every migraine, trigger, prodrome, medicine, relief, activity, location and missed activity.\n\nFilter the view by type or time range, or jump straight to \"Needs attention\" to see entries with missing fields (an attack with no end time, a migraine with no severity, a quick-logged item waiting for details).\n\nTap any entry to open it in edit mode and fill in what's missing. The number badge on the tab itself shows how many entries currently need attention so you don't have to remember to come back.",
        "Tap any entry to edit it. Use the \"Needs attention\" filter when the tab shows a number so you can clean those up in one pass.",
        navHint = NavHintLocation.BOTTOM_JOURNAL),
    TourStep(Routes.COMMUNITY, Icons.Outlined.Forum, "Community — Articles & Forum",
        "Articles is a library of expert-written pieces on migraine science, treatments, lifestyle changes, and patient experience.\n\nSwitch between For You (personalised to the conditions and triggers you've tagged), Latest, Browse, and Saved.\n\nForum is open discussion: post anything, comment on others, save threads.\n\nConnect to one of our AI companions in Profile (each one has its own focus area: nutrition, sleep, hormonal, etc.) and the For You feed re-ranks toward articles that match your subscribed companions and your actual data.",
        "Browse the articles or jump into a discussion. To get personalised recommendations, open Profile and subscribe to a companion or two.",
        navHint = NavHintLocation.TOP_COMMUNITY),
    TourStep(Routes.RISK_WEIGHTS, Icons.Outlined.Tune, "Settings — Make It Yours",
        "Open the drawer (top-right menu) for full control.\n\nManage Items is where everything is customisable: add your own triggers, prodromes, medicines, reliefs, symptoms, activities or locations, change the severity weighting on each one, or turn off the defaults you don't care about.\n\nRisk Model lets you move your LOW / MILD / HIGH thresholds and reshape the per-day decay curve (how much today's trigger counts vs. one from 6 days ago) so the gauge reflects what an actual risky day looks like for you.\n\nData Settings controls which metrics MigraineMe pulls from your wearable, Health Connect, or HealthKit. Connections lets you link or unlink your wearable, calendar, and health platforms.\n\nNotifications, Profile, and Help round out the drawer.",
        "Pop into Manage Items and turn off anything that's noise for you (caffeine if it doesn't affect you, or any default symptom you never experience). Less noise = a sharper, more honest gauge.",
        navHint = NavHintLocation.TOP_SETTINGS),
)

val setupSteps = listOf(
    TourStep(Routes.THIRD_PARTY_CONNECTIONS, Icons.Outlined.FavoriteBorder, "Connect Health Connect",
        "Health Connect pulls health data from apps on your phone. If apps like MyFitnessPal, Cronometer, or Samsung Health share data with Health Connect, we can use it too.",
        "Tap Connect on the Health Connect card above.",
        interactive = true, spotlightKey = "health_connect_card", bottomCard = true),
    TourStep(Routes.THIRD_PARTY_CONNECTIONS, Icons.Outlined.Watch, "Connect Your Wearable",
        "We support WHOOP, Oura, Polar, and Garmin. Connect yours to automatically import sleep, recovery, HRV, skin temp, and more.",
        "Tap Connect on your wearable below.",
        interactive = true, spotlightKey = "wearables_group"),
    TourStep(Routes.DATA, Icons.Outlined.Storage, "Configure Data Collection",
        "Pick which metrics MigraineMe pulls from your connected sources.\n\nEach section (Sleep, Physical Health, Cognitive, Environment, Diet, Menstruation) lists every metric we can collect for you, and for ones that can come from either your phone or wearable you'll see a source picker to choose the best one.\n\nSome metrics depend on permissions you granted earlier: noise sampling needs the microphone, weather needs location, the Daily Check-In voice input needs speech recognition, evening reminders need notifications, and calendar capture (the \"From your calendar\" page in Daily Check-In) needs calendar access.\n\nAnything you turn off here won't be tracked, won't show up on Monitor cards or Insights, and won't feed your risk score — so leave on whatever you might want later (you can always come back via the drawer).",
        "Defaults are on for everything your connected sources and permissions support. Use search at the top to jump to a specific metric. Permissions can be changed later in Android system settings.",
        interactive = true),
)

object SpotlightState {
    private val _rects = mutableStateMapOf<String, Rect>()
    var overlayRootOffset by mutableStateOf(Offset.Zero)

    fun register(key: String, rootBounds: Rect) { _rects[key] = rootBounds }
    fun clear() { _rects.clear(); overlayRootOffset = Offset.Zero }

    @Composable
    fun getLocalRect(key: String): Rect? {
        val rootRect = _rects[key] ?: return null
        val off = overlayRootOffset
        return Rect(
            left = rootRect.left - off.x,
            top = rootRect.top - off.y,
            right = rootRect.right - off.x,
            bottom = rootRect.bottom - off.y
        )
    }
}

fun Modifier.spotlightTarget(key: String): Modifier = this.onGloballyPositioned { coords ->
    if (TourManager.isActive() && TourManager.currentPhase() == CoachPhase.SETUP) {
        SpotlightState.register(key, coords.boundsInRoot())
    }
}

enum class CoachPhase { TOUR, SETUP }

data class TourState(
    val active: Boolean = false,
    val phase: CoachPhase = CoachPhase.TOUR,
    val stepIndex: Int = 0,
)

object TourManager {
    private val _state = MutableStateFlow(TourState())
    val state: StateFlow<TourState> = _state

    private fun currentSteps(): List<TourStep> = when (_state.value.phase) {
        CoachPhase.TOUR -> tourSteps
        CoachPhase.SETUP -> setupSteps
    }

    fun startTour() {
        _state.update { TourState(active = true, phase = CoachPhase.TOUR, stepIndex = 0) }
    }

    fun startSetup() {
        SpotlightState.clear()
        _state.update { TourState(active = true, phase = CoachPhase.SETUP, stepIndex = 0) }
    }

    fun nextStep(): String? {
        val steps = currentSteps()
        val nextIdx = _state.value.stepIndex + 1
        if (nextIdx < steps.size) {
            _state.update { it.copy(stepIndex = nextIdx) }
            return steps[nextIdx].route
        } else {
            endPhase()
            return null
        }
    }

    fun prevStep(): String? {
        val steps = currentSteps()
        val prevIdx = _state.value.stepIndex - 1
        if (prevIdx >= 0) {
            _state.update { it.copy(stepIndex = prevIdx) }
            return steps[prevIdx].route
        }
        return null
    }

    fun endPhase() {
        SpotlightState.clear()
        _state.update { TourState() }
    }

    fun isActive(): Boolean = _state.value.active
    fun currentPhase(): CoachPhase = _state.value.phase
}

@Composable
fun CoachOverlay(
    navigateTo: (String) -> Unit,
    onTourFinished: () -> Unit = {},
    onSetupFinished: () -> Unit = {},
    logVm: LogViewModel? = null,
    insightsVm: InsightsViewModel? = null,
) {
    val tourState by TourManager.state.collectAsState()
    val steps = when (tourState.phase) {
        CoachPhase.TOUR -> tourSteps
        CoachPhase.SETUP -> setupSteps
    }
    val step = if (tourState.active) steps.getOrNull(tourState.stepIndex) else null
    val ctx = LocalContext.current
    val density = LocalDensity.current

    fun finishAndClean() {
        val wasTour = tourState.phase == CoachPhase.TOUR
        TourManager.endPhase()
        if (wasTour) {
            CoroutineScope(Dispatchers.IO).launch {
                DemoDataSeeder.clearDemoData(ctx, logVm, insightsVm)
                kotlinx.coroutines.withContext(Dispatchers.Main) { onTourFinished() }
            }
        } else {
            onSetupFinished()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseAlpha"
    )
    val arrowOffset by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 8f,
        animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "arrowBounce"
    )

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                SpotlightState.overlayRootOffset = Offset(
                    coords.boundsInRoot().left,
                    coords.boundsInRoot().top
                )
            }
    )

    val spotlightRect = step?.spotlightKey?.let { SpotlightState.getLocalRect(it) }
    val hasSpotlight = spotlightRect != null && spotlightRect.width > 0f && spotlightRect.height > 0f

    AnimatedVisibility(
        visible = step != null,
        enter = fadeIn(tween(300)), exit = fadeOut(tween(300))
    ) {
        if (step != null) {
            if (hasSpotlight && spotlightRect != null) {
                val paddingPx = with(density) { 8.dp.toPx() }
                val cornerPx = with(density) { 18.dp.toPx() }
                val strokePx = with(density) { 3.dp.toPx() }

                Canvas(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                ) {
                    drawRect(Color.Black.copy(alpha = 0.5f))
                    val cutout = RoundRect(
                        left = spotlightRect.left - paddingPx,
                        top = spotlightRect.top - paddingPx,
                        right = spotlightRect.right + paddingPx,
                        bottom = spotlightRect.bottom + paddingPx,
                        cornerRadius = CornerRadius(cornerPx)
                    )
                    val cutoutPath = Path().apply { addRoundRect(cutout) }
                    clipPath(cutoutPath, ClipOp.Intersect) {
                        drawRect(Color.Transparent, blendMode = BlendMode.Clear)
                    }
                    drawRoundRect(
                        color = Color(0xFFFF7BB0).copy(alpha = pulseAlpha),
                        topLeft = Offset(cutout.left, cutout.top),
                        size = Size(cutout.right - cutout.left, cutout.bottom - cutout.top),
                        cornerRadius = CornerRadius(cornerPx),
                        style = Stroke(width = strokePx)
                    )
                }
            } else if (!step.interactive) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
            }
        }
    }

    var isCollapsed by remember { mutableStateOf(false) }
    LaunchedEffect(tourState.stepIndex) {
        isCollapsed = false
        // Reset scroll state when entering the data step so the overlay starts expanded-at-bottom.
        if (tourState.phase == CoachPhase.SETUP && tourState.stepIndex == 2) {
            SetupScrollState.scrollPosition = 0
        }
        val currentStep = steps.getOrNull(tourState.stepIndex)
        if (currentStep?.interactive == true) {
            // TOUR: don't collapse page 1 (Home, idx 0) or page 3 (Nutrition, idx 2)
            // SETUP: HC (idx 0) and WHOOP (idx 1) auto-collapse after 3s.
            // SETUP: Data Collection (idx 2) auto-collapses after 5s, but only if user
            // is still at the top of the list (scroll handler takes over otherwise).
            when (tourState.phase) {
                CoachPhase.TOUR -> if (tourState.stepIndex > 0 && tourState.stepIndex != 2) {
                    kotlinx.coroutines.delay(3000)
                    isCollapsed = true
                }
                CoachPhase.SETUP -> when {
                    tourState.stepIndex < 2 -> {
                        kotlinx.coroutines.delay(3000)
                        isCollapsed = true
                    }
                    tourState.stepIndex == 2 -> {
                        kotlinx.coroutines.delay(5000)
                        // Only collapse if user is still at the top of the list — the
                        // scroll handler takes over once they start scrolling.
                        if (SetupScrollState.scrollPosition == 0) {
                            isCollapsed = true
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(SetupScrollState.scrollPosition) {
        val isDataStep = tourState.phase == CoachPhase.SETUP && tourState.stepIndex == 2
        if (isDataStep) {
            // Card stays at top throughout. Expand at the top and bottom of the list,
            // collapse anywhere in the middle so the user can read the toggles freely.
            isCollapsed = SetupScrollState.scrollPosition != 0 && SetupScrollState.scrollPosition != -1
        } else {
            if (SetupScrollState.scrollPosition == 0 && isCollapsed) isCollapsed = false
            if (SetupScrollState.scrollPosition == -1 && isCollapsed) isCollapsed = false
        }
    }

    val isSetupConnectionStep = tourState.phase == CoachPhase.SETUP && tourState.stepIndex < 2
    val isTourPhase = tourState.phase == CoachPhase.TOUR
    val shouldAlignBottom = step?.let {
        isTourPhase || it.route == Routes.PROFILE || it.route == Routes.RISK_WEIGHTS || it.bottomCard
    } ?: false
    AnimatedVisibility(visible = step != null, enter = slideInVertically { if (shouldAlignBottom) it else -it } + fadeIn(tween(300)), exit = slideOutVertically { if (shouldAlignBottom) it else -it } + fadeOut(tween(200))) {
        if (step != null) {
            val isInteractive = step.interactive
            val isProfileStep = step.route == Routes.PROFILE
            val isRiskModelStep = step.route == Routes.RISK_WEIGHTS
            val alignBottom = shouldAlignBottom

            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .then(
                        if (alignBottom) Modifier.padding(bottom = 12.dp)
                        else Modifier.padding(top = 12.dp, bottom = 12.dp)
                    ),
                contentAlignment = if (alignBottom) Alignment.BottomCenter else Alignment.TopCenter
            ) {
                AnimatedContent(
                    targetState = isCollapsed,
                    transitionSpec = {
                        fadeIn(tween(200)) + scaleIn(initialScale = 0.8f, animationSpec = tween(200)) togetherWith
                        fadeOut(tween(150)) + scaleOut(targetScale = 0.8f, animationSpec = tween(150))
                    },
                    label = "collapse"
                ) { collapsed ->
                    if (collapsed) {
                        Card(
                            onClick = { isCollapsed = false },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E0A2E)),
                            shape = RoundedCornerShape(28.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                            modifier = Modifier
                                .border(1.dp, Color(0xFFFF7BB0).copy(alpha = 0.5f), RoundedCornerShape(28.dp))
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    Modifier.size(28.dp).background(
                                        Brush.linearGradient(listOf(AppTheme.AccentPurple.copy(alpha = 0.3f), AppTheme.AccentPink.copy(alpha = 0.2f))),
                                        CircleShape
                                    ), contentAlignment = Alignment.Center
                                ) {
                                    Icon(step.icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                                Text(
                                    "${tourState.stepIndex + 1}/${steps.size} — Tap to expand",
                                    color = AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    } else {
                        val maxCardHeight = LocalConfiguration.current.screenHeightDp.dp * 0.4f
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E0A2E)),
                            shape = RoundedCornerShape(18.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(max = maxCardHeight)
                                .animateContentSize(spring(dampingRatio = 0.8f))
                                .border(
                                    2.dp,
                                    if (isInteractive) Brush.linearGradient(listOf(Color(0xFFFF7BB0).copy(alpha = pulseAlpha), Color(0xFFFF7BB0).copy(alpha = pulseAlpha)))
                                    else Brush.linearGradient(listOf(AppTheme.AccentPurple.copy(alpha = 0.5f), AppTheme.AccentPink.copy(alpha = 0.3f))),
                                    RoundedCornerShape(18.dp)
                                )
                        ) {
                            Box(Modifier.fillMaxWidth().height(3.dp).background(
                                Brush.horizontalGradient(listOf(
                                    if (isInteractive) AppTheme.AccentPink else AppTheme.AccentPurple,
                                    if (isInteractive) AppTheme.AccentPurple else AppTheme.AccentPink
                                ))
                            ))

                            val bodyScrollState = rememberScrollState()
                            // Reset scroll to top whenever the user advances or goes back a step.
                            LaunchedEffect(tourState.stepIndex) { bodyScrollState.scrollTo(0) }
                            Column(Modifier.padding(16.dp).verticalScroll(bodyScrollState)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(Modifier.size(38.dp).background(
                                        Brush.linearGradient(listOf(AppTheme.AccentPurple.copy(alpha = 0.3f), AppTheme.AccentPink.copy(alpha = 0.2f))),
                                        CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(step.icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(step.title, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                        Text("${tourState.stepIndex + 1} of ${steps.size}", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                                    }
                                    // Allow minimize on all steps except SETUP pages 1+2 (HC, WHOOP)
                                    val allowMinimize = true
                                    if (allowMinimize) {
                                        IconButton(onClick = { isCollapsed = true }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Outlined.UnfoldLess, "Minimize", tint = AppTheme.SubtleTextColor, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    // ★ Never show X during onboarding (TOUR or SETUP)
                                    // User must complete the flow to ensure cleanup
                                }

                                Spacer(Modifier.height(10.dp))
                                Text(step.body, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium, lineHeight = MaterialTheme.typography.bodyMedium.lineHeight)
                                Spacer(Modifier.height(8.dp))
                                Text(step.highlight, color = if (isInteractive) AppTheme.AccentPink else AppTheme.AccentPurple, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))

                                if (isInteractive && step.spotlightKey == null && !(tourState.phase == CoachPhase.TOUR && tourState.stepIndex == 2)) {
                                    Spacer(Modifier.height(4.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                        Icon(Icons.Filled.KeyboardArrowDown, "Scroll down",
                                            tint = AppTheme.AccentPink.copy(alpha = pulseAlpha),
                                            modifier = Modifier.size(28.dp).offset(y = arrowOffset.dp))
                                    }
                                }

                                Spacer(Modifier.height(if (isInteractive) 6.dp else 14.dp))

                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    if (tourState.stepIndex > 0) {
                                        TextButton(onClick = { val route = TourManager.prevStep(); if (route != null) navigateTo(route) }) {
                                            Text("Back", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    } else {
                                        Spacer(Modifier.width(1.dp))
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                                        steps.indices.forEach { i ->
                                            Box(Modifier.size(if (i == tourState.stepIndex) 8.dp else 5.dp).clip(CircleShape).background(
                                                when {
                                                    i == tourState.stepIndex -> if (isInteractive) AppTheme.AccentPink else AppTheme.AccentPurple
                                                    i < tourState.stepIndex -> (if (isInteractive) AppTheme.AccentPink else AppTheme.AccentPurple).copy(alpha = 0.4f)
                                                    else -> AppTheme.TrackColor
                                                }
                                            ))
                                        }
                                    }

                                    if (tourState.stepIndex < steps.size - 1) {
                                        Button(onClick = { val route = TourManager.nextStep(); if (route != null) navigateTo(route) },
                                            colors = ButtonDefaults.buttonColors(containerColor = if (isInteractive) AppTheme.AccentPink else AppTheme.AccentPurple),
                                            shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
                                            Text("Next", style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.width(4.dp))
                                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(16.dp))
                                        }
                                    } else {
                                        val isTour = tourState.phase == CoachPhase.TOUR
                                        Button(onClick = { finishAndClean() },
                                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink),
                                            shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
                                            if (isTour) {
                                                Text("Set up my data", style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.width(4.dp))
                                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(16.dp))
                                            } else {
                                                Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                                                Text("Done!", style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


