package com.migraineme

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** Brainy pose for each coach step, matched on title prefix. */
internal fun brainyForCoachTitle(title: String): Int? = when {
    title.startsWith("Home") -> R.drawable.brainy_risk_small
    title.startsWith("Monitor") -> R.drawable.brainy_physical_small
    title.startsWith("Diet") -> R.drawable.brainy_diet_small
    title.startsWith("Insights") -> R.drawable.brainy_detective_small
    title.startsWith("Log") -> R.drawable.brainy_migraines_small
    title.startsWith("Auto-Captured") -> R.drawable.brainy_runner_small
    title.startsWith("Daily Check-In") -> R.drawable.brainy_ask_small
    title.startsWith("Menstrual") -> R.drawable.brainy_menstruation_small
    title.startsWith("Treatments") -> R.drawable.brainy_treatments_small
    title.startsWith("Journal") -> R.drawable.brainy_briefcase_small
    title.startsWith("Community") -> R.drawable.brainy_recs_small
    title.startsWith("Settings") -> R.drawable.brainy_cognitive_small
    title.startsWith("Risk Model") -> R.drawable.brainy_archer_small
    title.startsWith("Manage Items") -> R.drawable.brainy_gardener_small
    title.startsWith("Profile") -> R.drawable.brainy_recover_small
    title.startsWith("AI Calibration") -> R.drawable.brainy_shield_small
    title.startsWith("Connect Health") -> R.drawable.brainy_physical_small
    title.startsWith("Connect Your Wearable") -> R.drawable.brainy_runner_small
    title.startsWith("Configure Data") -> R.drawable.brainy_cognitive_small
    else -> null
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
    // ── New-style tour card (stills over the live app) ──
    /** "Where *HIGH* starts." — one pink word between asterisks. Empty = use [title]. */
    val headline: String = "",
    /** Handwritten sub line under the headline. */
    val sub: String = "",
    /** "Find it:" line — where the screen lives. */
    val findIt: String = "",
    /** Pop-out image drawn under the card (drawable-nodpi/tour_pop_NN). */
    val pop: Int? = null,
    /** Brainy pose in the card header. */
    val brainy: Int? = null,
    /** Optional second card on the same stop (Food + cycle). */
    val second: TourSegment? = null,
    /** The closing "much more" screen: full card with feature rows, no pop. */
    val closing: Boolean = false,
)

data class TourSegment(val headline: String, val sub: String, val body: String, val pop: Int, val brainy: Int)

data class TourFeature(val icon: Int, val title: String, val desc: String)

/** Rows on the closing screen — the promo video's "And there's much more" slide, adjusted for the tour. */
val tourClosingFeatures = listOf(
    TourFeature(R.drawable.brainy_act_meeting, "Connect your calendar", "AI tracks triggers based on what's in your calendar"),
    TourFeature(R.drawable.tile_watch, "Log and track from your wearable", "Garmin, Apple Watch, Polar, Oura, WHOOP and more"),
    TourFeature(R.drawable.brainy_trig_medication, "Started a treatment?", "see how it's going, from your own logs"),
    TourFeature(R.drawable.tile_report, "Share a report", "a PDF from your own data"),
    TourFeature(R.drawable.brainy_act_reading, "Your journal", "everything you logged, tap to fix what's missing"),
    TourFeature(R.drawable.brainy_trigger_small, "Your own pool", "add triggers, medicines, reliefs; switch off the rest"),
    TourFeature(R.drawable.brainy_trig_storm, "Set up trigger alarms", "get warned when pressure drops or sleep goes off"),
)

val tourSteps = listOf(
    TourStep(Routes.HOME, Icons.Outlined.Home, "Home", highlight = "", navHint = NavHintLocation.BOTTOM_HOME,
        headline = "Your risk *today.*", sub = "look here every morning",
        body = "The gauge shows how full your bucket is: Low, Mild or High. Under it: the 7 day outlook.",
        findIt = "Home tab", pop = R.drawable.tour_pop_01, brainy = R.drawable.brainy_risk_small),
    TourStep(Routes.HOME, Icons.Outlined.Tune, "Risk Model", highlight = "", navHint = NavHintLocation.TOP_SETTINGS,
        headline = "Where *HIGH* starts.", sub = "the AI sets the lines, you can move them",
        body = "Every trigger counts Low, Mild or High towards your score, and fades over the days after. Cross a line and your level changes. Adjust any of it by hand.",
        findIt = "Settings menu (top left) › Risk Model", pop = R.drawable.tour_pop_02, brainy = R.drawable.brainy_archer_small),
    TourStep(Routes.MIGRAINE, Icons.Outlined.Psychology, "Log", highlight = "", navHint = NavHintLocation.BOTTOM_MIGRAINE,
        headline = "Log an *attack.*", sub = "the one thing you have to do",
        body = "Tap Log Migraine, or just talk. Triggers your watch and phone caught are already ticked, with the time they hit.",
        findIt = "Log tab › Log Migraine", pop = R.drawable.tour_pop_03, brainy = R.drawable.brainy_migraines_small),
    TourStep(Routes.HOME, Icons.Outlined.Forum, "Ask MigraineMe", highlight = "", navHint = NavHintLocation.BOTTOM_HOME,
        headline = "Ask *MigraineMe.*", sub = "questions about your own data",
        body = "Ask in plain words: was weather a factor, what helped last time. It answers from your own logs.",
        findIt = "Home tab › Ask MigraineMe card", pop = R.drawable.tour_pop_04, brainy = R.drawable.brainy_ask_small),
    TourStep(Routes.MONITOR, Icons.Outlined.Timeline, "Monitor", highlight = "", navHint = NavHintLocation.BOTTOM_MONITOR,
        headline = "All your *health data.*", sub = "one place, one card each",
        body = "Sleep, HRV, weather, cycle, food. Tap any card for the detail and the history.",
        findIt = "Monitor tab › Sleep card", pop = R.drawable.tour_pop_05, brainy = R.drawable.brainy_detective_small),
    TourStep(Routes.MONITOR_NUTRITION, Icons.Outlined.Restaurant, "Diet", highlight = "", navHint = NavHintLocation.BOTTOM_MONITOR,
        headline = "What you *eat.*", sub = "scan it, or search it",
        body = "Tyramine, histamine, gluten and alcohol get flagged as you add a food.",
        findIt = "Monitor tab › Diet card  ·  Monitor › Menstruation", pop = R.drawable.tour_pop_06_1, brainy = R.drawable.brainy_diet_small,
        second = TourSegment("Your *cycle.*", "risk rises around your period",
            "Set your last period and cycle length. Risk rises around the predicted date.",
            R.drawable.tour_pop_06_2, R.drawable.brainy_menstruation_small)),
    TourStep(Routes.INSIGHTS, Icons.Outlined.BarChart, "Insights", highlight = "", navHint = NavHintLocation.BOTTOM_INSIGHTS,
        headline = "What your data *says.*", sub = "after a few logged attacks",
        body = "Which triggers really matter, what helped, and a report you can hand your doctor.",
        findIt = "Insights tab", pop = R.drawable.tour_pop_07, brainy = R.drawable.brainy_recs_small),
    TourStep(Routes.EVENING_CHECKIN, Icons.Outlined.Nightlight, "Daily Check-In", highlight = "", navHint = NavHintLocation.BOTTOM_MIGRAINE, bottomCard = true,
        headline = "A check-in *each evening.*", sub = "for anything you want to log yourself",
        body = "Triggers, medicines, reliefs, how you felt. One pass through your day, tap what applies, done.",
        findIt = "Log tab › Daily Check-In", pop = R.drawable.tour_pop_08, brainy = R.drawable.brainy_ask_small),
    TourStep(Routes.HOME, Icons.Outlined.AutoAwesome, "AI Calibration", highlight = "", navHint = NavHintLocation.BOTTOM_HOME,
        headline = "Every Monday it *learns.*", sub = "accept or reject, one tap",
        body = "Once a week the AI proposes changes, like moving your gauge lines. Tick what you agree with.",
        findIt = "Home › “suggestions for you” banner", pop = R.drawable.tour_pop_09, brainy = R.drawable.brainy_gardener_small),
    TourStep(Routes.HOME, Icons.Outlined.Forum, "Community", highlight = "", navHint = NavHintLocation.TOP_COMMUNITY,
        headline = "You are *not alone.*", sub = "guidance, articles, forum",
        body = "Find a practice near you, share your diary with a practitioner you choose, read articles picked for you, join the forum.",
        findIt = "Community icon (top right)", pop = R.drawable.tour_pop_10, brainy = R.drawable.brainy_recs_small),
    TourStep(Routes.HOME, Icons.Outlined.Home, "", body = "", highlight = "", closing = true,
        headline = "And there's\n*much more*", sub = "take it from here", brainy = R.drawable.brainy_recs_small),
)

val setupSteps = listOf(
    TourStep(Routes.THIRD_PARTY_CONNECTIONS, Icons.Outlined.FavoriteBorder, "Connect Health Connect",
        "Pulls sleep, steps and nutrition from the apps already on your phone. Fitbit or Samsung? They come through here too.",
        "Tap Connect on the Health Connect card above.",
        interactive = true, spotlightKey = "health_connect_card", bottomCard = true, brainy = R.drawable.brainy_physical_small,
        headline = "Connect *Health Connect.*", sub = "phone data in, nothing to type"),
    TourStep(Routes.THIRD_PARTY_CONNECTIONS, Icons.Outlined.Watch, "Connect Your Wearable",
        "Sleep, HRV, skin temperature and more come in on their own. Garmin? Pair watch unlocks our watch app.",
        "Tap Connect on yours below.",
        interactive = true, spotlightKey = "wearables_group", brainy = R.drawable.brainy_detective_small,
        headline = "Connect your *wearable.*", sub = "Oura, Polar, Garmin"),
    TourStep(Routes.DATA, Icons.Outlined.Storage, "Configure Data Collection",
        "Every metric is a switch. Some ask for a permission first. We already switched on what your wearable provides.",
        "Use search at the top to jump to a specific metric.",
        interactive = true, brainy = R.drawable.brainy_shield_small,
        headline = "Choose what to *collect.*", sub = "you are in control"),
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

/** Root-space edges of the app's top and bottom bars, reported by MainActivity. */
object CoachBars {
    var topBottomPx by mutableStateOf(0f)
    var bottomTopPx by mutableStateOf(0f)
    var overlayTopPx by mutableStateOf(0f)
    var overlayBottomPx by mutableStateOf(0f)
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

    fun startSetup(startIndex: Int = 0) {
        SpotlightState.clear()
        _state.update {
            TourState(active = true, phase = CoachPhase.SETUP, stepIndex = startIndex.coerceIn(0, setupSteps.lastIndex))
        }
    }

    /**
     * No-seed onboarding jumps straight to the final "Configure Data Collection"
     * step, skipping the Connect Health / Connect Wearable steps. From there the
     * existing flow ends the phase → onSetupFinished → AI Setup.
     */
    fun startSetupAtDataSettings() = startSetup(setupSteps.lastIndex)

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
private fun TourChrome(step: TourStep, pulseAlpha: Float) {
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val ring = Color(0xFFFF7BB0).copy(alpha = pulseAlpha)
    Box(Modifier.fillMaxSize()) {
        ObLatticeBackground()
        // Top bar: settings (left), screen title, community (right) — same places as the app.
        Box(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color(0xFF2A003D)).padding(top = statusInset).height(64.dp)) {
            Box(Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(44.dp)
                .then(if (step.navHint == NavHintLocation.TOP_SETTINGS) Modifier.border(3.dp, ring, RoundedCornerShape(12.dp)) else Modifier),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Settings, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Text(t(step.title), color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.Center))
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(44.dp)
                .then(if (step.navHint == NavHintLocation.TOP_COMMUNITY) Modifier.border(3.dp, ring, RoundedCornerShape(12.dp)) else Modifier),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Groups, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        // Bottom bar: the five tabs, solid, the hinted one ringed.
        val tabs = listOf(
            Triple(NavHintLocation.BOTTOM_MONITOR, "Monitor", Icons.Outlined.Timeline),
            Triple(NavHintLocation.BOTTOM_INSIGHTS, "Insights", Icons.Outlined.BarChart),
            Triple(NavHintLocation.BOTTOM_HOME, "Home", Icons.Outlined.Home),
            Triple(NavHintLocation.BOTTOM_MIGRAINE, "Log", SymptomIcons.MigraineStarburst),
            Triple(NavHintLocation.BOTTOM_JOURNAL, "Journal", Icons.Outlined.History),
        )
        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color(0xFF2A003D)).padding(bottom = navInset).height(80.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { (hint, label, icon) ->
                val hinted = step.navHint == hint
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.width(64.dp).height(32.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (hinted) AppTheme.AccentPurple.copy(alpha = 0.25f) else Color.Transparent)
                            .then(if (hinted) Modifier.border(3.dp, ring, RoundedCornerShape(16.dp)) else Modifier),
                        contentAlignment = Alignment.Center
                    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                    Spacer(Modifier.height(4.dp))
                    Text(t(label), color = Color.White.copy(alpha = if (hinted) 1f else 0.85f), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun coachBarPaddings(): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> {
    val density = LocalDensity.current
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val top = if (CoachBars.topBottomPx > 0f) with(density) { (CoachBars.topBottomPx - CoachBars.overlayTopPx).toDp() } else statusInset + 64.dp
    val bottom = if (CoachBars.bottomTopPx > 0f && CoachBars.overlayBottomPx > 0f) with(density) { (CoachBars.overlayBottomPx - CoachBars.bottomTopPx).toDp() } else 80.dp + navInset
    return top.coerceAtLeast(0.dp) to bottom.coerceAtLeast(0.dp)
}

@Composable
fun CoachOverlay(
    navigateTo: (String) -> Unit,
    onTourFinished: () -> Unit = {},
    onSetupFinished: () -> Unit = {},
    logVm: LogViewModel? = null,
    insightsVm: InsightsViewModel? = null,
    homeVm: HomeViewModel? = null,
) {
    val tourState by TourManager.state.collectAsState()
    val steps = when (tourState.phase) {
        CoachPhase.TOUR -> tourSteps
        CoachPhase.SETUP -> setupSteps
    }
    val step = if (tourState.active) steps.getOrNull(tourState.stepIndex) else null
    val ctx = LocalContext.current
    val density = LocalDensity.current

    /// Every way out of the coach: the ✕ in the card corner, Skip, and the
    /// final Done. In the TOUR phase it clears the demo rows first
    /// (clearDemoData joins any in-flight seed, so late inserts cannot survive
    /// the delete) and then hands back through onTourFinished, which lands on
    /// the setup landing — the boxed step cards — never Home.
    fun finishAndClean() {
        val wasTour = tourState.phase == CoachPhase.TOUR
        TourManager.endPhase()
        if (wasTour) {
            CoroutineScope(Dispatchers.IO).launch {
                if (!OnboardingMode.noSeed) {
                    DemoDataSeeder.clearDemoData(ctx, logVm, insightsVm, homeVm)
                }
                kotlinx.coroutines.withContext(Dispatchers.Main) { onTourFinished() }
            }
        } else {
            onSetupFinished()
        }
    }

    /// Back from the coach's FIRST step. There is no previous coach step, so
    /// rather than hiding Back and trapping the user in the walkthrough:
    ///
    /// - SETUP: return to the permission page the phase was entered from.
    /// - TOUR: this is an exit like ✕ or Skip, so run the identical path —
    ///   same demo cleanup, same landing on the setup landing. It used to drop
    ///   the user on Home instead, which skipped the rest of onboarding.
    fun backOutOfCoach() {
        if (tourState.phase == CoachPhase.TOUR) {
            finishAndClean()
            return
        }
        TourManager.endPhase()
        navigateTo(Routes.ONBOARDING)
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
                CoachBars.overlayTopPx = coords.boundsInRoot().top
                CoachBars.overlayBottomPx = coords.boundsInRoot().bottom
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
                    // Heavier scrim for the two Connect steps (Health Connect + Wearable)
                    // so the surrounding cards fade out and the tour card stands forward.
                    val heavyDim = tourState.phase == CoachPhase.SETUP && tourState.stepIndex < 2
                    drawRect(Color.Black.copy(alpha = if (heavyDim) 0.8f else 0.2f))
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
                if (tourState.phase == CoachPhase.TOUR) {
                    // Tour stops draw their own chrome: lattice full screen, a top bar
                    // with the settings and community icons, a solid bottom bar with the
                    // five tabs. The live app is not shown. The nav hint rings the tab or
                    // icon the stop refers to.
                    TourChrome(step, pulseAlpha)
                } else {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)))
                }
            }
        }
    }

    var isCollapsed by remember { mutableStateOf(false) }
    // Set when the user manually expands the pill — blocks any pending auto-collapse
    // coroutine from immediately re-collapsing the card.
    var userExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(tourState.stepIndex) {
        isCollapsed = false
        userExpanded = false
        // Reset scroll state when entering the data step so the overlay starts expanded-at-bottom.
        if (tourState.phase == CoachPhase.SETUP && tourState.stepIndex == 2) {
            SetupScrollState.scrollPosition = 0
        }
        val currentStep = steps.getOrNull(tourState.stepIndex)
        // TOUR steps never auto-collapse. The interactive ones (Risk Model,
        // Manage Items, Profile — steps 13/14/15) used to shrink to the
        // "N/16 — Tap to expand" pill three seconds after landing, which hid
        // Next behind a pill the user had no reason to think was tappable.
        // Only the SETUP Data step still collapses, and only because the card
        // sits on top of the very list it asks the user to scroll.
        if (currentStep?.interactive == true && tourState.phase == CoachPhase.SETUP) {
            if (tourState.stepIndex == 2) {
                kotlinx.coroutines.delay(5000)
                if (!userExpanded && SetupScrollState.scrollPosition == 0) {
                    isCollapsed = true
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
            val alignBottom = shouldAlignBottom
            val isTour = tourState.phase == CoachPhase.TOUR
            val screenH = LocalConfiguration.current.screenHeightDp.dp

            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .then(
                        if (isTour) {
                            // Tour: the stack lives between our two bars, never over them.
                            val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                            val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                            Modifier.padding(top = statusInset + 64.dp + 8.dp, bottom = 80.dp + navInset + 8.dp)
                        } else if (alignBottom) {
                            Modifier.padding(bottom = 12.dp)
                        } else {
                            // Push the card down on the Connect Wearable step so it sits
                            // below the status bar / Back row instead of hugging the top.
                            // Below the status bar, or the collapsed pill lands under it and
                            // never receives the tap that expands it.
                            val isWearableStep = tourState.phase == CoachPhase.SETUP && tourState.stepIndex == 1
                            val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                            Modifier.padding(top = statusInset + if (isWearableStep) 44.dp else 12.dp, bottom = 12.dp)
                        }
                    ),
                contentAlignment = if (isTour) Alignment.Center else if (alignBottom) Alignment.BottomCenter else Alignment.TopCenter
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
                        // Collapsed pill: tap to bring the card back. Same behaviour as
                        // before, restyled. The user hides the card to work the page.
                        Card(
                            onClick = {
                                userExpanded = true
                                isCollapsed = false
                            },
                            colors = CardDefaults.cardColors(containerColor = ObStyle.CardFill),
                            shape = RoundedCornerShape(28.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                            modifier = Modifier.border(1.5.dp, ObStyle.Pink.copy(alpha = 0.6f), RoundedCornerShape(28.dp))
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (step.brainy != null) Image(painterResource(step.brainy), null, modifier = Modifier.size(28.dp))
                                else Icon(step.icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Text(
                                    t("%1\$s/%2\$s — Tap to expand", tourState.stepIndex + 1, steps.size),
                                    style = ObStyle.label(13.sp)
                                )
                            }
                        }
                    } else {
                        // Setup wearable step keeps a fixed shorter card so the wearables below
                        // (WHOOP / Oura / Polar / Garmin) stay visible. Body text scrolls inside.
                        val isWearableStep = tourState.phase == CoachPhase.SETUP && tourState.stepIndex == 1
                        // Data step overlays the data list and asks the user to scroll it —
                        // keep the card translucent so the toggles behind stay visible.
                        val isDataStepCard = tourState.phase == CoachPhase.SETUP && tourState.stepIndex == 2
                        val maxCardHeight = screenH * 0.4f
                        // Wearable step: the card may use exactly the space above the measured
                        // wearables group (top of Oura), minus the card's own top padding and a gap.
                        val wearableCardMax: androidx.compose.ui.unit.Dp = run {
                            val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                            val spotTop = spotlightRect?.top
                            if (spotTop != null && spotTop > 0f) (with(density) { spotTop.toDp() } - statusInset - 44.dp - 16.dp).coerceIn(150.dp, screenH * 0.45f)
                            else 240.dp
                        }

                        // ── Shared button row: Back · Skip · Next/Done. Logic unchanged. ──
                        val buttonsRow: @Composable () -> Unit = {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // The tour's first step has nothing behind it: Back
                                // here used to exit the whole tour (landing the user
                                // on setup out of nowhere). Hide it, matching iOS;
                                // the setup phase keeps Back on step 0 as its route
                                // to the permission pages.
                                val hideBack = isTour && tourState.stepIndex == 0
                                Text(
                                    t("Skip"),
                                    style = ObStyle.label(14.sp).copy(color = ObStyle.Muted),
                                    modifier = Modifier.clickable { finishAndClean() }.padding(vertical = 8.dp)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (!hideBack) {
                                        OutlinedButton(
                                            onClick = {
                                                val route = TourManager.prevStep()
                                                if (route != null) navigateTo(route) else backOutOfCoach()
                                            },
                                            shape = RoundedCornerShape(50),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                            border = androidx.compose.foundation.BorderStroke(1.5.dp, ObStyle.CardLine),
                                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
                                        ) { Text(t("Back"), style = ObStyle.button(13.sp)) }
                                    }
                                    if (tourState.stepIndex < steps.size - 1) {
                                        Button(
                                            onClick = { val route = TourManager.nextStep(); if (route != null) navigateTo(route) },
                                            colors = ButtonDefaults.buttonColors(containerColor = ObStyle.Pink, contentColor = ObStyle.Ink),
                                            shape = RoundedCornerShape(50),
                                            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp)
                                        ) { Text(t("Next"), style = ObStyle.button(13.sp)) }
                                    } else {
                                        // Data step: gate Done until the user has scrolled to
                                        // the very bottom of the data list (scrollPosition ==
                                        // -1). Designed behaviour, unchanged.
                                        val isDataStepDone = tourState.phase == CoachPhase.SETUP && tourState.stepIndex == 2
                                        val gateScroll = isDataStepDone && SetupScrollState.scrollPosition != -1
                                        Button(
                                            onClick = { if (!gateScroll) finishAndClean() },
                                            enabled = !gateScroll,
                                            colors = ButtonDefaults.buttonColors(containerColor = ObStyle.Pink, contentColor = ObStyle.Ink,
                                                disabledContainerColor = ObStyle.Pink.copy(alpha = 0.35f), disabledContentColor = ObStyle.Ink.copy(alpha = 0.6f)),
                                            shape = RoundedCornerShape(50),
                                            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)
                                        ) { Text(if (isTour) t("Start") else t("Done"), style = ObStyle.button(13.sp)) }
                                    }
                                }
                            }
                        }

                        // ── Header row: Brainy · collapse chevron · exit (tour) · counter ──
                        val headerRow: @Composable (brainy: Int?, showCounter: Boolean) -> Unit = { brainy, showCounter ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (brainy != null) Image(painterResource(brainy), null, modifier = Modifier.size(if (isTour) 36.dp else 44.dp))
                                else Icon(step.icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.weight(1f))
                                if (!isTour) {
                                    IconButton(onClick = { isCollapsed = true }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Outlined.UnfoldLess, t("Minimize"), tint = ObStyle.Lavender, modifier = Modifier.size(18.dp))
                                    }
                                }
                                if (isTour) {
                                    // Exit runs the same cleanup as finishing.
                                    IconButton(onClick = { finishAndClean() }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Outlined.Close, t("Exit tour"), tint = ObStyle.Lavender, modifier = Modifier.size(16.dp))
                                    }
                                }
                                if (showCounter) {
                                    Spacer(Modifier.width(4.dp))
                                    Text(t("%1\$s of %2\$s", tourState.stepIndex + 1, steps.size), style = ObStyle.label(12.sp))
                                }
                            }
                        }

                        val cardShape = RoundedCornerShape(17.dp)
                        val cardBorder = if (isInteractive) ObStyle.Pink.copy(alpha = pulseAlpha) else ObStyle.CardLine.copy(alpha = 0.7f)
                        val cardFill = ObStyle.CardFill.copy(alpha = if (isDataStepCard) 0.88f else 1f)

                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        if (isTour && step.closing) {
                            // ── Closing screen: "And there's much more", feature rows, Start. Full page, like the mock. ──
                            Column(Modifier.fillMaxSize().padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Spacer(Modifier.height(4.dp))
                                Text(t("THE TOUR"), style = ObStyle.label(13.sp).copy(letterSpacing = 1.5.sp))
                                Spacer(Modifier.height(6.dp))
                                ObHeadline(t(step.headline), Modifier.fillMaxWidth(), size = 36.sp)
                                ObHand(t(step.sub), Modifier.fillMaxWidth(), size = 21.sp)
                                Spacer(Modifier.height(12.dp))
                                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    tourClosingFeatures.forEach { f ->
                                        Row(
                                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ObStyle.CardFill)
                                                .border(1.dp, ObStyle.CardLine.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Box(Modifier.size(40.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                                                Image(painterResource(f.icon), null, modifier = Modifier.size(27.dp))
                                            }
                                            Column {
                                                Text(t(f.title), style = ObStyle.body(14.sp).copy(fontWeight = FontWeight.SemiBold, color = Color.White))
                                                Text(t(f.desc), style = ObStyle.body(11.5.sp).copy(color = ObStyle.Lavender), maxLines = 1)
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                    ObPillButton(t("Set up profile"), { finishAndClean() }, Modifier.width(244.dp).height(46.dp))
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        } else if (isTour) {
                            // ── Tour stop: card (+ pop-out still) [+ second card + pop] ──
                            val two = step.second != null
                            val popImage: @Composable ColumnScope.(Int) -> Unit = { res ->
                                Image(
                                    painter = painterResource(res), contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    modifier = Modifier.fillMaxWidth(if (two) 0.8f else 1f).weight(1f, fill = false)
                                )
                            }
                            val tourCard: @Composable (headline: String, sub: String, body: String, brainy: Int?, findIt: String, counter: Boolean, buttons: Boolean) -> Unit =
                                { headline, sub, body, brainy, findIt, counter, buttons ->
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = cardFill), shape = cardShape,
                                        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                                        modifier = Modifier.fillMaxWidth().border(1.5.dp, cardBorder, cardShape)
                                    ) {
                                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                            headerRow(brainy, counter)
                                            Spacer(Modifier.height(2.dp))
                                            ObHeadline(t(headline), Modifier.fillMaxWidth(), size = 26.sp, align = TextAlign.Start)
                                            ObHand(t(sub), Modifier.fillMaxWidth(), size = 19.sp, align = TextAlign.Start)
                                            Spacer(Modifier.height(4.dp))
                                            Text(t(body), style = ObStyle.body(13.5.sp))
                                            if (findIt.isNotEmpty()) {
                                                Spacer(Modifier.height(6.dp))
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(t("Find it:"), style = ObStyle.label(11.5.sp))
                                                    Spacer(Modifier.width(6.dp))
                                                    Text(t(findIt), style = ObStyle.label(11.5.sp).copy(color = Color.White, fontWeight = FontWeight.SemiBold))
                                                }
                                            }
                                            if (buttons) {
                                                Spacer(Modifier.height(8.dp))
                                                buttonsRow()
                                            }
                                        }
                                    }
                                }
                            Column(
                                Modifier.fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                tourCard(step.headline, step.sub, step.body, step.brainy, if (two) "" else step.findIt, true, !two)
                                step.pop?.let { popImage(it) }
                                step.second?.let { seg ->
                                    tourCard(seg.headline, seg.sub, seg.body, seg.brainy, step.findIt, false, true)
                                    popImage(seg.pop)
                                }
                            }
                        } else {
                            // ── Setup step: same card as before, restyled. Height cap, scroll
                            // fade, wearable fixed height, data translucency all unchanged. ──
                            Card(
                                colors = CardDefaults.cardColors(containerColor = cardFill), shape = cardShape,
                                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                                modifier = Modifier.fillMaxWidth()
                                    .heightIn(max = if (isWearableStep) wearableCardMax else screenH * 0.45f).animateContentSize(spring(dampingRatio = 0.8f))
                                    .border(1.5.dp, cardBorder, cardShape)
                            ) {
                                val bodyScrollState = rememberScrollState()
                                LaunchedEffect(tourState.stepIndex) { bodyScrollState.scrollTo(0) }
                                val compact = isWearableStep
                                Column(Modifier.padding(horizontal = 16.dp, vertical = if (compact) 10.dp else 16.dp)) {
                                    Box(Modifier.weight(1f, fill = false)) {
                                        Column(Modifier.verticalScroll(bodyScrollState)) {
                                            headerRow(step.brainy, true)
                                            Spacer(Modifier.height(if (compact) 0.dp else 4.dp))
                                            ObHeadline(t(step.headline.ifEmpty { step.title }), Modifier.fillMaxWidth(), size = if (compact) 22.sp else 26.sp, align = TextAlign.Start)
                                            if (step.sub.isNotEmpty()) ObHand(t(step.sub), Modifier.fillMaxWidth(), size = if (compact) 17.sp else 19.sp, align = TextAlign.Start)
                                            Spacer(Modifier.height(if (compact) 2.dp else 6.dp))
                                            Text(t(step.body), style = ObStyle.body(if (compact) 13.sp else 14.sp))
                                            Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                                            Text(t(step.highlight), style = ObStyle.body(if (compact) 13.sp else 14.sp).copy(color = ObStyle.Pink, fontWeight = FontWeight.SemiBold))
                                            Spacer(Modifier.height(if (isInteractive) 6.dp else 14.dp))
                                        }
                                        // Static fade + chevron while there is more body below
                                        // (no animation: motion is a migraine trigger).
                                        if (bodyScrollState.canScrollForward) {
                                            Box(
                                                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(30.dp)
                                                    .background(Brush.verticalGradient(listOf(Color.Transparent, cardFill))),
                                                contentAlignment = Alignment.BottomCenter
                                            ) {
                                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = t("Scroll for more"), tint = ObStyle.Lavender, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                    buttonsRow()
                                    Spacer(Modifier.height(10.dp))
                                    // Step dots — hidden on the wearable step, where the card
                                    // is height-capped and the header already says "N of 3".
                                    if (!isWearableStep) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                steps.indices.forEach { i ->
                                                    Box(Modifier.size(if (i == tourState.stepIndex) 8.dp else 5.dp).clip(CircleShape).background(
                                                        when {
                                                            i == tourState.stepIndex -> ObStyle.Pink
                                                            i < tourState.stepIndex -> ObStyle.Pink.copy(alpha = 0.4f)
                                                            else -> ObStyle.Muted.copy(alpha = 0.5f)
                                                        }
                                                    ))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        } // end Box wrapping the card(s)
                        }
                    }
                }
            }
        }
    }
}
