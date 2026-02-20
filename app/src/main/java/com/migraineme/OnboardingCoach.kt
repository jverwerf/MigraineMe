package com.migraineme

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
)

val tourSteps = listOf(
    TourStep(Routes.HOME, Icons.Outlined.Home, "Home — Your Risk Gauge",
        "This gauge shows your real-time migraine risk based on active triggers. The 7-day forecast lets you tap any day to preview future risk. Active Triggers shows exactly what's driving your score and how much each contributes.",
        "Your daily command centre — always start here.",
        navHint = NavHintLocation.BOTTOM_HOME),
    TourStep(Routes.MONITOR, Icons.Outlined.Timeline, "Monitor — Live Data",
        "All your data streams in one place: sleep, recovery, heart rate, weather, screen time, nutrition. Each card shows today's value and a trend graph. Tap any card for detailed history and configuration.",
        "Keep an eye on your body and environment.",
        navHint = NavHintLocation.BOTTOM_MONITOR),
    TourStep(Routes.INSIGHTS, Icons.Outlined.BarChart, "Insights — Migraine Analysis",
        "Explore each migraine in detail: see what was happening in your body and environment before, during, and after. Toggle metrics to spot patterns. Drill into breakdowns by trigger, medicine, relief, and more.",
        "Understand what's really going on.",
        navHint = NavHintLocation.BOTTOM_INSIGHTS),
    TourStep(Routes.MIGRAINE, Icons.Outlined.Psychology, "Log — Log Attacks",
        "When a migraine hits, log everything here: pain location, severity, and symptoms. Quick-log buttons at the top let you rapidly log a trigger, medicine, relief, or activity without the full wizard.",
        "Your go-to when a migraine hits.",
        navHint = NavHintLocation.BOTTOM_MIGRAINE),
    TourStep(Routes.JOURNAL, Icons.Outlined.History, "Journal — Your Timeline",
        "Every migraine, trigger, medicine, relief, activity, and location — all in chronological order. Tap any entry to edit. The badge shows items that need your attention.",
        "Your complete migraine diary.",
        navHint = NavHintLocation.BOTTOM_JOURNAL),
    TourStep(Routes.COMMUNITY, Icons.Outlined.Forum, "Community — Articles & Forum",
        "Read expert articles on migraine science, triggers, and treatment. Join the forum to share experiences, ask questions, and connect with others who understand what you're going through.",
        "You're not alone — learn and share.",
        navHint = NavHintLocation.TOP_COMMUNITY),
    TourStep(Routes.MANAGE_ITEMS, Icons.Outlined.Tune, "Settings — Manage Items",
        "Add, remove, or customise your triggers, medicines, reliefs, prodromes, and activities. Set severity levels for triggers and prodromes. Reorder your favourites for quick logging.",
        "Make the app truly yours.",
        navHint = NavHintLocation.TOP_SETTINGS),
    TourStep(Routes.RISK_WEIGHTS, Icons.Outlined.Speed, "Settings — Risk Model",
        "Customise how your risk score is calculated. Decay weights control how fast triggers lose influence over days. Thresholds set where the gauge transitions between None, Low, Mild, and High zones.",
        "Advanced control over your predictions.",
        navHint = NavHintLocation.TOP_SETTINGS),
    TourStep(Routes.PROFILE, Icons.Outlined.Person, "Settings — Profile",
        "Your account details and app preferences. Change your password, manage your data, and configure notification settings.",
        "Your account, your rules.",
        navHint = NavHintLocation.TOP_SETTINGS),
)

val setupSteps = listOf(
    TourStep(Routes.THIRD_PARTY_CONNECTIONS, Icons.Outlined.FavoriteBorder, "Connect Health Connect",
        "Health Connect links your phone's health data — sleep, steps, heart rate, nutrition, and more from any compatible app.",
        "Tap Connect on the Health Connect card below",
        interactive = true, spotlightKey = "health_connect_card"),
    TourStep(Routes.THIRD_PARTY_CONNECTIONS, Icons.Outlined.Watch, "Connect WHOOP",
        "If you have a WHOOP band, tap the Connect button below to link it. This will automatically import your sleep, recovery, HRV, and more.",
        "Tap Connect on the WHOOP card below",
        interactive = true, spotlightKey = "whoop_card"),
    TourStep(Routes.DATA, Icons.Outlined.Storage, "Configure Data Collection",
        "Control exactly which metrics MigraineMe collects. Scroll through and toggle on the data you want to track. You can always change these later in Settings.",
        "Toggle on the metrics that matter to you ↓",
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
                DemoDataSeeder.clearDemoData(ctx)
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
        val currentStep = steps.getOrNull(tourState.stepIndex)
        if (currentStep?.interactive == true) {
            // TOUR: don't collapse page 1 (Home, idx 0)
            // SETUP: don't collapse page 1 (HC, idx 0) or page 2 (WHOOP, idx 1)
            // SETUP: DO collapse page 3 (Data Collection, idx 2) after delay
            val shouldCollapse = when (tourState.phase) {
                CoachPhase.TOUR -> tourState.stepIndex > 0
                CoachPhase.SETUP -> tourState.stepIndex >= 2
            }
            if (shouldCollapse) {
                kotlinx.coroutines.delay(3000)
                isCollapsed = true
            }
        }
    }
    LaunchedEffect(SetupScrollState.scrollPosition) {
        // Expand when scrolled to top
        if (SetupScrollState.scrollPosition == 0 && isCollapsed) {
            isCollapsed = false
        }
        // Expand when scrolled to bottom (position == -1 sentinel)
        if (SetupScrollState.scrollPosition == -1 && isCollapsed) {
            isCollapsed = false
        }
    }

    val isSetupConnectionStep = tourState.phase == CoachPhase.SETUP && tourState.stepIndex < 2
    AnimatedVisibility(visible = step != null, enter = slideInVertically { if (step?.route == Routes.PROFILE || isSetupConnectionStep) it else -it } + fadeIn(tween(300)), exit = slideOutVertically { if (step?.route == Routes.PROFILE || isSetupConnectionStep) it else -it } + fadeOut(tween(200))) {
        if (step != null) {
            val isInteractive = step.interactive
            val isProfileStep = step.route == Routes.PROFILE

            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .then(
                        if (isProfileStep) Modifier.padding(bottom = 12.dp)
                        else Modifier.padding(top = 12.dp, bottom = 12.dp)
                    ),
                contentAlignment = if (isProfileStep || isSetupConnectionStep) Alignment.BottomCenter else Alignment.TopCenter
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
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    } else {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E0A2E)),
                            shape = RoundedCornerShape(18.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                            modifier = Modifier.fillMaxWidth()
                                .animateContentSize(spring(dampingRatio = 0.8f))
                                .then(
                                    if (isInteractive) Modifier.border(
                                        2.dp,
                                        Color(0xFFFF7BB0).copy(alpha = pulseAlpha),
                                        RoundedCornerShape(18.dp)
                                    ) else Modifier
                                )
                        ) {
                            Box(Modifier.fillMaxWidth().height(3.dp).background(
                                Brush.horizontalGradient(listOf(
                                    if (isInteractive) AppTheme.AccentPink else AppTheme.AccentPurple,
                                    if (isInteractive) AppTheme.AccentPurple else AppTheme.AccentPink
                                ))
                            ))

                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(Modifier.size(38.dp).background(
                                        Brush.linearGradient(listOf(AppTheme.AccentPurple.copy(alpha = 0.3f), AppTheme.AccentPink.copy(alpha = 0.2f))),
                                        CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(step.icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(step.title, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                        Text("${tourState.stepIndex + 1} of ${steps.size}", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                                    }
                                    if (isInteractive) {
                                        // Hide minimize on SETUP pages 1+2 (WHOOP, HC) — always expanded
                                        val allowMinimize = !(tourState.phase == CoachPhase.SETUP && tourState.stepIndex < 2)
                                        if (allowMinimize) {
                                            IconButton(onClick = { isCollapsed = true }, modifier = Modifier.size(28.dp)) {
                                                Icon(Icons.Outlined.UnfoldLess, "Minimize", tint = AppTheme.SubtleTextColor, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                    // ★ Never show X during onboarding (TOUR or SETUP)
                                    // User must complete the flow to ensure cleanup
                                }

                                Spacer(Modifier.height(10.dp))
                                Text(step.body, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall, lineHeight = MaterialTheme.typography.bodySmall.lineHeight)
                                Spacer(Modifier.height(6.dp))
                                Text(step.highlight, color = if (isInteractive) AppTheme.AccentPink else AppTheme.AccentPurple, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))

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
                                            Text("Back", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
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
                                            shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                                            Text("Next", style = MaterialTheme.typography.labelMedium); Spacer(Modifier.width(4.dp))
                                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(14.dp))
                                        }
                                    } else {
                                        val isTour = tourState.phase == CoachPhase.TOUR
                                        Button(onClick = { finishAndClean() },
                                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink),
                                            shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                                            if (isTour) {
                                                Text("Set up my data", style = MaterialTheme.typography.labelMedium); Spacer(Modifier.width(4.dp))
                                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(14.dp))
                                            } else {
                                                Icon(Icons.Filled.Check, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                                                Text("Done!", style = MaterialTheme.typography.labelMedium)
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


