package com.migraineme

import androidx.compose.animation.core.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OnboardingCenteredPage(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally, content = content)
}

@Composable
fun OnboardingScrollPage(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
fun WelcomePage(
    onTakeFullTour: () -> Unit,
    onSetUpProfile: () -> Unit,
    onGoToApp: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())
    ) {
        // Language as a flag pinned top-right: the full-width dropdown pushed
        // the whole hero down and undid the page's layout. Same picker, one tap.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Spacer(Modifier.height(44.dp))
            LanguageFlagButton()
        }

        // Fixed top gap keeps the sky background visible above the content.
        Spacer(Modifier.height(66.dp))

        Text(
            t("Welcome to MigraineMe"),
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(14.dp))

        // Sits on top of the hero artwork, where the muted grey was close to
        // unreadable — white, like the title above it. Same fix as iOS.
        Text(
            t("How would you like to start?"),
            color = Color.White.copy(alpha = 0.92f),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        // Three ways in, fastest first. Each card says what it costs in
        // minutes. The acute-attack strip that used to sit above the cards is
        // now the badge on the first card: same destination, one control.
        // The tour stays the recommended one and keeps the only filled button.
        StartCard(
            poseRes = R.drawable.brainy_gardener_small,
            title = t("Just start"),
            kicker = t("0 minutes"),
            badge = t("In an attack? This one"),
            onBadgeClick = onGoToApp,
            points = listOf(
                t("Straight into the app. Nothing to fill in, nothing to connect."),
                t("Log your attacks as they happen. That is the only thing you have to do."),
                t("The AI reads what you log and proposes your profile every Monday.") + " · " + t("Premium"),
                t("Connecting your wearable and turning on which data you would like to collect is yours to do, in Settings."),
            ),
            pinkPoints = setOf(3),
            note = t("The AI needs about five logged attacks before it has anything to say."),
            buttonLabel = t("Start now"),
            buttonIcon = Icons.AutoMirrored.Filled.ArrowForward,
            filled = false,
            onClick = onGoToApp
        )

        Spacer(Modifier.height(10.dp))

        StartCard(
            poseRes = R.drawable.brainy_ask_small,
            title = t("Give the AI a head start"),
            kicker = t("About 3 minutes"),
            badge = t("Minimal setup for the app to work"),
            points = listOf(
                t("Answer a short set of questions about your attacks."),
                t("Connect a wearable and Health Connect, if you have them."),
                t("Your risk gauge is personal from day one instead of week five."),
            ),
            buttonLabel = t("Answer a few questions"),
            buttonIcon = Icons.Outlined.Tune,
            filled = false,
            onClick = onSetUpProfile
        )

        Spacer(Modifier.height(10.dp))

        StartCard(
            poseRes = R.drawable.brainy_briefcase_small,
            title = t("Show me the app first"),
            kicker = t("About 5 minutes"),
            badge = t("RECOMMENDED"),
            lead = true,
            points = listOf(
                t("Every screen filled with example data, so nothing is empty."),
                t("See what the risk gauge, insights and auto-tracking actually do."),
                t("Ends with the questions above. Leave at any point."),
            ),
            buttonLabel = t("Take the full tour"),
            buttonIcon = Icons.Outlined.AutoAwesome,
            filled = true,
            onClick = onTakeFullTour
        )

        Spacer(Modifier.height(10.dp))

        Text(
            t("You can run setup, or rerun it, anytime from Profile."),
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))
    }
}

/// One of the three start cards on the welcome page. `lead` gives the card
/// the pink ring the old tour card had; `badge` is the pill top-right and,
/// when `onBadgeClick` is set, is itself a shortcut (the acute-attack strip
/// lives there now).
@Composable
private fun StartCard(
    poseRes: Int,
    title: String,
    kicker: String,
    points: List<String>,
    buttonLabel: String,
    buttonIcon: ImageVector,
    filled: Boolean,
    onClick: () -> Unit,
    // Points the user must act on themselves get the pink number.
    pinkPoints: Set<Int> = emptySet(),
    badge: String? = null,
    onBadgeClick: (() -> Unit)? = null,
    lead: Boolean = false,
    note: String? = null,
) {
    val ring = if (lead) AppTheme.AccentPink.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.08f)
    HeroCard(
        modifier = Modifier.border(1.dp, ring, AppTheme.HeroCardShape)
    ) {
        Column(Modifier.padding(16.dp)) {
            // Badge on its own line: next to the title it forced "Show me the
            // app first" onto two lines on a Pixel-width screen.
            if (badge != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(AppTheme.AccentPink.copy(alpha = 0.14f))
                        .border(1.dp, AppTheme.AccentPink.copy(alpha = 0.35f), RoundedCornerShape(50))
                        .then(if (onBadgeClick != null) Modifier.clickable { onBadgeClick() } else Modifier)
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        badge.uppercase(),
                        color = AppTheme.AccentPink,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(
                    painter = painterResource(id = poseRes),
                    contentDescription = null,
                    modifier = Modifier.size(42.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text(
                        kicker.uppercase(),
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            points.forEachIndexed { i, p ->
                if (i > 0) Spacer(Modifier.height(6.dp))
                NumberedPoint(i + 1, p, accent = if (i in pinkPoints) AppTheme.AccentPink else AppTheme.AccentPurple)
            }
            if (note != null) {
                Spacer(Modifier.height(8.dp))
                Text(note, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(12.dp))
            if (filled) {
                Button(
                    onClick = onClick,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(buttonIcon, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(buttonLabel, fontWeight = FontWeight.SemiBold)
                }
            } else {
                OutlinedButton(
                    onClick = onClick,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(buttonIcon, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(buttonLabel, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun NumberedPoint(n: Int, text: String, accent: Color = AppTheme.AccentPurple) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text("$n", color = accent, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
        }
        Text(text, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FeatureBullet(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(AppTheme.AccentPurple.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(12.dp))
        }
        Text(text, color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun HowItWorksPage(
    alreadyRevealed: Boolean = false,
    onAllRevealed: () -> Unit = {}
) {
    val steps = listOf(
        Triple(R.drawable.brainy_physical_small, "Connect", "Data flows in from your wearable, Health Connect and phone"),
        Triple(R.drawable.brainy_detective_small, "Detect", "Sleep changes, weather shifts, and stress spikes get flagged automatically"),
        Triple(R.drawable.brainy_risk_small, "Score", "Everything adds up to your daily risk"),
        Triple(R.drawable.brainy_archer_small, "Estimate", "See your risk outlook 7 days ahead"),
        Triple(R.drawable.brainy_gardener_small, "Learn", "Gets smarter the more you use it"),
    )
    var revealedSteps by remember { mutableIntStateOf(if (alreadyRevealed) steps.size else 0) }
    var hasAnimated by remember { mutableStateOf(alreadyRevealed) }
    LaunchedEffect(Unit) {
        if (!hasAnimated) {
            delay(800)
            for (i in 1..steps.size) { revealedSteps = i; delay(1200) }
            delay(500); hasAnimated = true; onAllRevealed()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(t("How It Works"), color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
        Column {
            steps.forEachIndexed { index, (brainyRes, title, subtitle) ->
                val isActive = index < revealedSteps
                val stepAlpha by animateFloatAsState(if (isActive) 1f else 0.15f, tween(600, easing = FastOutSlowInEasing), label = "a$index")
                val stepScale by animateFloatAsState(if (index == revealedSteps - 1 && !alreadyRevealed && revealedSteps <= steps.size) 1.04f else 1f, spring(dampingRatio = 0.5f, stiffness = 300f), label = "s$index")
                val offsetX by animateDpAsState(if (isActive) 0.dp else 40.dp, tween(500, easing = FastOutSlowInEasing), label = "x$index")
                Row(Modifier.fillMaxWidth().offset(x = offsetX).graphicsLayer { alpha = stepAlpha; scaleX = stepScale; scaleY = stepScale },
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    // Timeline: Brainy node + connector line
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(Modifier.size(44.dp)
                                .background(
                                    if (isActive) Brush.linearGradient(listOf(Color(0x57CE93D8), Color(0x24B388FF)))
                                    else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.1f), Color.White.copy(alpha = 0.1f))),
                                    CircleShape
                                ))
                            if (isActive) {
                                Box(Modifier.size(52.dp)
                                    .border(2.dp, AppTheme.AccentPurple.copy(alpha = 0.4f), CircleShape))
                            }
                            Image(
                                painter = painterResource(id = brainyRes),
                                contentDescription = null,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        if (index < steps.size - 1) {
                            val lineColor = if (index < revealedSteps - 1) AppTheme.AccentPurple.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f)
                            Box(Modifier.width(2.dp).height(36.dp).background(lineColor))
                        }
                    }
                    // Text
                    Column(Modifier.padding(top = 10.dp)) {
                        Text(t(title), color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Text(t(subtitle), color = if (isActive) AppTheme.BodyTextColor else AppTheme.SubtleTextColor.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// MARK: - Location Permission Page (matches iOS LocationPermissionPage)

@Composable
fun LocationPermissionPage(onGrant: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) onGrant()
    }
    fun checkLoc(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val hasLocation = remember { mutableStateOf(checkLoc()) }
    LaunchedEffect(hasLocation.value) { if (hasLocation.value) { kotlinx.coroutines.delay(500); onGrant() } }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(100.dp).background(
                Brush.linearGradient(listOf(AppTheme.AccentPurple.copy(alpha = 0.3f), AppTheme.AccentPink.copy(alpha = 0.2f))),
                CircleShape
            ))
            Icon(Icons.Outlined.LocationOn, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(40.dp))
        }

        Spacer(Modifier.height(24.dp))
        Text(t("Enable Location"), color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(12.dp))
        Text(
            t("MigraineMe uses your location to track weather conditions — a top migraine trigger. We collect city-level data only, never your exact address."),
            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(Modifier.height(24.dp))
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureBullet(Icons.Outlined.Cloud, t("Automatic weather tracking (pressure, humidity, temp)"))
            FeatureBullet(Icons.Outlined.Schedule, t("Timezone-accurate trigger detection"))
            FeatureBullet(Icons.Outlined.Terrain, t("Altitude change monitoring"))
            FeatureBullet(Icons.Outlined.TrendingUp, t("Better risk predictions"))
        }

        Spacer(Modifier.weight(1f))

        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hasLocation.value) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF81C784), modifier = Modifier.size(20.dp))
                    Text(t("Location enabled"), color = Color(0xFF81C784), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(t("Continue"), fontWeight = FontWeight.SemiBold) }
            } else {
                Button(
                    onClick = {
                        launcher.launch(arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        ))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(t("Allow Location Access"), fontWeight = FontWeight.SemiBold) }
            }
            TextButton(onClick = onSkip) {
                Text(t("Skip for now"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// MARK: - Notification Permission Page

@Composable
fun NotificationPermissionPage(onGrant: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) onGrant() }
    val hasNotification = remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            else true
        )
    }
    LaunchedEffect(hasNotification.value) { if (hasNotification.value) { kotlinx.coroutines.delay(500); onGrant() } }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(100.dp).background(
                Brush.linearGradient(listOf(AppTheme.AccentPurple.copy(alpha = 0.3f), AppTheme.AccentPink.copy(alpha = 0.2f))),
                CircleShape
            ))
            Icon(Icons.Outlined.Notifications, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(40.dp))
        }

        Spacer(Modifier.height(24.dp))
        Text(t("Enable Notifications"), color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(12.dp))
        Text(
            t("Stay on top of your migraine risk with timely alerts. MigraineMe sends you daily check-in reminders and warns you when your risk spikes."),
            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(Modifier.height(24.dp))
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureBullet(Icons.Outlined.Warning, t("High-risk alerts before migraines hit"))
            FeatureBullet(Icons.Outlined.Nightlight, t("Evening check-in reminders"))
            FeatureBullet(Icons.Outlined.AutoAwesome, t("AI calibration updates"))
            FeatureBullet(Icons.Outlined.Article, t("New articles matching your triggers"))
        }

        Spacer(Modifier.weight(1f))

        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hasNotification.value) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF81C784), modifier = Modifier.size(20.dp))
                    Text(t("Notifications enabled"), color = Color(0xFF81C784), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(t("Continue"), fontWeight = FontWeight.SemiBold) }
            } else {
                Button(
                    onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else { onGrant() }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(t("Allow Notifications"), fontWeight = FontWeight.SemiBold) }
            }
            TextButton(onClick = onSkip) {
                Text(t("Skip for now"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// MARK: - Microphone Permission Page

@Composable
fun MicrophonePermissionPage(onGrant: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) onGrant() }
    val hasMic = remember {
        mutableStateOf(
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    LaunchedEffect(hasMic.value) { if (hasMic.value) { kotlinx.coroutines.delay(500); onGrant() } }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(100.dp).background(
                Brush.linearGradient(listOf(AppTheme.AccentPurple.copy(alpha = 0.3f), AppTheme.AccentPink.copy(alpha = 0.2f))),
                CircleShape
            ))
            Icon(Icons.Outlined.Mic, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(40.dp))
        }

        Spacer(Modifier.height(24.dp))
        Text(t("Enable Microphone"), color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(12.dp))
        Text(
            t("MigraineMe can use your microphone for voice-based logging and ambient noise detection — both known migraine factors."),
            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(Modifier.height(24.dp))
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureBullet(Icons.Outlined.RecordVoiceOver, t("Voice-powered migraine logging"))
            FeatureBullet(Icons.Outlined.GraphicEq, t("Ambient noise level tracking"))
            FeatureBullet(Icons.Outlined.Nightlight, t("Evening check-in by voice"))
            FeatureBullet(Icons.Outlined.AutoAwesome, t("AI story input via voice"))
        }

        Spacer(Modifier.weight(1f))

        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hasMic.value) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF81C784), modifier = Modifier.size(20.dp))
                    Text(t("Microphone enabled"), color = Color(0xFF81C784), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(t("Continue"), fontWeight = FontWeight.SemiBold) }
            } else {
                Button(
                    onClick = { launcher.launch(android.Manifest.permission.RECORD_AUDIO) },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(t("Allow Microphone Access"), fontWeight = FontWeight.SemiBold) }
            }
            TextButton(onClick = onSkip) {
                Text(t("Skip for now"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// MARK: - Calendar Permission Page

@Composable
fun CalendarPermissionPage(onGrant: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val hasCal = remember {
        mutableStateOf(
            context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCal.value = granted
        if (granted) {
            // Combined permission + data: opt into calendar_events in the same step.
            CoroutineScope(Dispatchers.IO).launch {
                EdgeFunctionsService().upsertMetricSetting(context, "calendar_events", true)
                withContext(Dispatchers.Main) { onGrant() }
            }
        }
    }
    LaunchedEffect(hasCal.value) { if (hasCal.value) { kotlinx.coroutines.delay(500); onGrant() } }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(100.dp).background(
                Brush.linearGradient(listOf(AppTheme.AccentPurple.copy(alpha = 0.3f), AppTheme.AccentPink.copy(alpha = 0.2f))),
                CircleShape
            ))
            Icon(Icons.Outlined.DateRange, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(40.dp))
        }

        Spacer(Modifier.height(24.dp))
        Text(t("Enable Calendar"), color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(12.dp))
        Text(
            t("MigraineMe reads your calendar to suggest activities, reliefs, and stress triggers from your events — no events are stored, only what you confirm."),
            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(Modifier.height(24.dp))
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureBullet(Icons.Outlined.FitnessCenter, t("Auto-detect workouts and yoga"))
            FeatureBullet(Icons.Outlined.Warning, t("Spot stress-heavy meeting days"))
            FeatureBullet(Icons.Outlined.CheckCircle, t("One tap to confirm or skip"))
            FeatureBullet(Icons.Outlined.Lock, t("Read-only, never written back"))
        }

        Spacer(Modifier.weight(1f))

        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hasCal.value) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF81C784), modifier = Modifier.size(20.dp))
                    Text(t("Calendar enabled"), color = Color(0xFF81C784), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(t("Continue"), fontWeight = FontWeight.SemiBold) }
            } else {
                Button(
                    onClick = { launcher.launch(android.Manifest.permission.READ_CALENDAR) },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(t("Allow Calendar Access"), fontWeight = FontWeight.SemiBold) }
            }
            TextButton(onClick = onSkip) {
                Text(t("Skip for now"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// MARK: - Screen Time Permission Page

@Composable
fun ScreenTimePermissionPage(onGrant: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    fun checkScreenTime(): Boolean = try {
        val appOps = context.getSystemService(android.content.Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        ) == android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    val hasScreenTime = remember { mutableStateOf(checkScreenTime()) }
    LaunchedEffect(hasScreenTime.value) { if (hasScreenTime.value) { kotlinx.coroutines.delay(500); onGrant() } }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) hasScreenTime.value = checkScreenTime()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(100.dp).background(
                Brush.linearGradient(listOf(AppTheme.AccentPurple.copy(alpha = 0.3f), AppTheme.AccentPink.copy(alpha = 0.2f))),
                CircleShape
            ))
            Icon(Icons.Outlined.PhoneAndroid, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(40.dp))
        }

        Spacer(Modifier.height(24.dp))
        Text(t("Enable Screen Time"), color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(12.dp))
        Text(
            t("Screen time is a common migraine trigger. MigraineMe can track your usage patterns to find correlations with your attacks."),
            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(Modifier.height(24.dp))
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureBullet(Icons.Outlined.Timer, t("Screen time trigger tracking"))
            FeatureBullet(Icons.Outlined.Nightlight, t("Late-night usage detection"))
            FeatureBullet(Icons.Outlined.Apps, t("App usage patterns"))
            FeatureBullet(Icons.Outlined.SelfImprovement, t("Digital wellness correlation"))
        }

        Spacer(Modifier.weight(1f))

        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hasScreenTime.value) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF81C784), modifier = Modifier.size(20.dp))
                    Text(t("Screen time enabled"), color = Color(0xFF81C784), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(t("Continue"), fontWeight = FontWeight.SemiBold) }
            } else {
                Button(
                    onClick = {
                        try {
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        } catch (_: Exception) {}
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(t("Open Settings"), fontWeight = FontWeight.SemiBold) }
            }
            TextButton(onClick = onSkip) {
                Text(t("Skip for now"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// MARK: - Battery Optimization Page

@Composable
fun BatteryOptimizationPage(onGrant: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val powerManager = remember { context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager }
    val isIgnoring = remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }
    LaunchedEffect(isIgnoring.value) { if (isIgnoring.value) { kotlinx.coroutines.delay(500); onGrant() } }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) isIgnoring.value = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(100.dp).background(
                Brush.linearGradient(listOf(AppTheme.AccentPurple.copy(alpha = 0.3f), AppTheme.AccentPink.copy(alpha = 0.2f))),
                CircleShape
            ))
            Icon(Icons.Outlined.BatteryChargingFull, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(40.dp))
        }

        Spacer(Modifier.height(24.dp))
        Text(t("Disable Battery Optimization"), color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(12.dp))
        Text(
            t("Android may pause MigraineMe in the background to save battery. Disabling optimization ensures reliable data collection and timely alerts."),
            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(Modifier.height(24.dp))
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureBullet(Icons.Outlined.Sync, t("Reliable background sync"))
            FeatureBullet(Icons.Outlined.Storage, t("Uninterrupted data collection"))
            FeatureBullet(Icons.Outlined.Notifications, t("Timely notifications"))
            FeatureBullet(Icons.Outlined.Speed, t("Consistent risk scoring"))
        }

        Spacer(Modifier.weight(1f))

        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isIgnoring.value) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF81C784), modifier = Modifier.size(20.dp))
                    Text(t("Battery optimization disabled"), color = Color(0xFF81C784), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(t("Continue"), fontWeight = FontWeight.SemiBold) }
            } else {
                @Suppress("BatteryLife")
                Button(
                    onClick = {
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(t("Disable Battery Optimization"), fontWeight = FontWeight.SemiBold) }
            }
            TextButton(onClick = onSkip) {
                Text(t("Skip for now"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun ConnectionsPage(onNavigateToConnections: () -> Unit, wearableConnected: String?, onWearableChanged: (String) -> Unit) {
    OnboardingScrollPage {
        OnboardingIconHeader(Icons.Outlined.Link, t("Connect your data"))
        Text(t("The more data MigraineMe has, the better it predicts."), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        listOf("Health Connect" to "Steps, sleep, heart rate, nutrition, and more", "WHOOP" to "Sleep, recovery, HRV, HR, SpO₂, skin temp", "Both" to "Get the best of both sources", "None" to "I'll log everything manually").forEach { (label, desc) ->
            OnboardingChoiceCard(label, desc, wearableConnected == label) { onWearableChanged(label) }
        }
        if (wearableConnected != null && wearableConnected != "None") {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onNavigateToConnections, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.AccentPurple)) {
                Icon(Icons.Outlined.Settings, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(t("Set up connections now"), color = AppTheme.AccentPurple)
            }
            Text(t("You can also do this later from the menu."), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun DataCollectionPage(wearable: String?, enabledMetrics: MutableMap<String, Boolean>) {
    val hasWearable = wearable == "WHOOP" || wearable == "Both"
    val hasLocation = enabledMetrics["user_location_daily"] == true
    OnboardingScrollPage {
        OnboardingIconHeader(Icons.Outlined.Storage, t("What do you want to track?"))
        Text(t("Turn on the data you want MigraineMe to collect. You can change these later in Settings → Data."),
            color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        dataCollectionGroups.forEach { group ->
            Text(t(group.title), color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 4.dp))
            group.items.forEach { item ->
                val available = when {
                    item.requiresWearable && !hasWearable -> false
                    item.requiresLocation && !hasLocation -> false
                    else -> true
                }
                val enabled = enabledMetrics[item.metric] ?: (available && item.source != "reference")
                DataToggleRow(item, enabled, available) { on -> enabledMetrics[item.metric] = on }
            }
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun PersonalQuestionsPage1(frequency: String?, onFrequency: (String) -> Unit, duration: String?, onDuration: (String) -> Unit, severity: String?, onSeverity: (String) -> Unit) {
    OnboardingScrollPage {
        OnboardingIconHeader(Icons.Outlined.Person, t("About your migraines"))
        OnboardingQuestionSection(t("How often do you get migraines?")) {
            listOf("Daily", "2-3x per week", "Weekly", "2-3x per month", "Monthly", "Rarely").forEach { OnboardingChoiceChip(it, frequency == it) { onFrequency(it) } }
        }
        OnboardingQuestionSection(t("How long do they usually last?")) {
            listOf("A few hours", "Half a day", "A full day", "2-3 days", "More than 3 days").forEach { OnboardingChoiceChip(it, duration == it) { onDuration(it) } }
        }
        OnboardingQuestionSection(t("How severe are they typically?")) {
            listOf("Mild — can push through", "Moderate — slows me down", "Severe — can't function", "Debilitating — bed rest required").forEach { OnboardingChoiceChip(it, severity == it) { onSeverity(it) } }
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun PersonalQuestionsPage2(timing: String?, onTiming: (String) -> Unit, warningSign: String?, onWarningSign: (String) -> Unit, medication: String?, onMedication: (String) -> Unit) {
    OnboardingScrollPage {
        OnboardingIconHeader(Icons.Outlined.Schedule, t("Timing & patterns"))
        OnboardingQuestionSection(t("When do they usually hit?")) {
            listOf("Morning (wake up with it)", "Afternoon", "Evening", "Night", "No pattern / varies").forEach { OnboardingChoiceChip(it, timing == it) { onTiming(it) } }
        }
        OnboardingQuestionSection(t("Do you get warning signs before an attack?")) {
            listOf("Yes, clearly — I can feel one coming", "Sometimes — occasional hints", "Rarely — they catch me off guard", "No — they come without warning").forEach { OnboardingChoiceChip(it, warningSign == it) { onWarningSign(it) } }
        }
        OnboardingQuestionSection(t("Do you take preventive or acute medication?")) {
            listOf("Yes, preventive daily medication", "Yes, acute medication when needed", "Both preventive and acute", "No medication currently").forEach { OnboardingChoiceChip(it, medication == it) { onMedication(it) } }
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun PersonalQuestionsPage3(knownTriggerAreas: Set<String>, onTriggerAreas: (Set<String>) -> Unit, familyHistory: String?, onFamilyHistory: (String) -> Unit, trackCycle: String?, onTrackCycle: (String) -> Unit) {
    OnboardingScrollPage {
        OnboardingIconHeader(Icons.Outlined.Lightbulb, t("What you already know"))
        OnboardingQuestionSection(t("Which of these seem to affect your migraines? (select all that apply)")) {
            listOf("Sleep", "Stress", "Weather", "Screen time", "Diet", "Hormones", "Exercise", "Not sure yet").forEach { area ->
                val selected = area in knownTriggerAreas
                OnboardingChoiceChip(area, selected) {
                    if (area == "Not sure yet") onTriggerAreas(setOf("Not sure yet"))
                    else { val new = knownTriggerAreas.toMutableSet(); new.remove("Not sure yet"); if (selected) new.remove(area) else new.add(area); onTriggerAreas(new) }
                }
            }
        }
        OnboardingQuestionSection(t("Does anyone in your family get migraines?")) {
            listOf("Yes", "No", "Not sure").forEach { OnboardingChoiceChip(it, familyHistory == it) { onFamilyHistory(it) } }
        }
        OnboardingQuestionSection(t("Do you want to track your menstrual cycle?")) {
            listOf("Yes", "No", "Not applicable").forEach { OnboardingChoiceChip(it, trackCycle == it) { onTrackCycle(it) } }
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun TriggerGroupPage(title: String, icon: ImageVector, questions: List<SeverityQuestion>, answers: MutableMap<String, SeverityChoice>) {
    OnboardingScrollPage {
        OnboardingIconHeader(icon, title)
        Text(t("How much does each of these affect your migraines?"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        questions.forEach { q -> SeverityQuestionCard(q, answers[q.label] ?: SeverityChoice.NONE) { answers[q.label] = it } }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun SuggestionsPage(suggestions: MutableMap<String, SeverityChoice>) {
    OnboardingScrollPage {
        OnboardingIconHeader(Icons.Outlined.AutoAwesome, t("Your personalised model"))
        Text(t("Based on your answers, here's what we suggest. Tap to adjust."), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        if (suggestions.isEmpty()) {
            Text(t("Complete the previous sections to get personalised suggestions."), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
        } else {
            val grouped = suggestions.entries.sortedByDescending { it.value.ordinal }.groupBy { it.value }
            grouped.forEach { (severity, items) ->
                if (severity == SeverityChoice.NONE) return@forEach
                Text(t("%s influence", t(severity.label)), color = severity.color, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 8.dp))
                items.forEach { (label, sev) -> SuggestionRow(label, sev) { suggestions[label] = it } }
            }
            val noneCount = suggestions.count { it.value == SeverityChoice.NONE }
            if (noneCount > 0) Text(t("%s triggers left at None (no influence)", noneCount), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun RiskModelPage() {
    OnboardingScrollPage {
        OnboardingIconHeader(Icons.Outlined.Speed, t("Your Risk Gauge"))
        Text(t("Each trigger has a severity — you decide how much it matters:"), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        SeverityExplainRow(SeverityChoice.HIGH, t("Reliably triggers your migraines"))
        SeverityExplainRow(SeverityChoice.MILD, t("Contributes but not always the cause"))
        SeverityExplainRow(SeverityChoice.LOW, t("Might play a role occasionally"))
        SeverityExplainRow(SeverityChoice.NONE, t("No influence — doesn't count"))
        Spacer(Modifier.height(12.dp))
        Text(t("Recent triggers weigh more than older ones. The score decays over 7 days."), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(t("Adjust everything anytime in Settings → Risk Model."), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
private fun SeverityExplainRow(severity: SeverityChoice, description: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(severity.color, CircleShape))
        Text("${t(severity.label)}:", color = severity.color, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.width(42.dp))
        Text(description, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
fun CompletePage(saving: Boolean) {
    OnboardingCenteredPage {
        Box(Modifier.size(80.dp).background(Brush.linearGradient(listOf(AppTheme.AccentPurple, AppTheme.AccentPink)), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(t("Almost there!"), color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(t("Your risk model is personalised.\n\nWe're loading some sample data so you can see the app in action. Next up: a quick tour of every screen."),
            color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

