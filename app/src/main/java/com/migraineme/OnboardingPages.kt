package com.migraineme

import androidx.compose.animation.core.*
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

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
fun WelcomePage() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        val anim = rememberInfiniteTransition(label = "pulse")
        val scale by anim.animateFloat(0.95f, 1.05f, infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "s")
        Image(
            painter = painterResource(id = R.drawable.logo),
            contentDescription = "MigraineMe",
            modifier = Modifier.size((100 * scale).dp)
        )
        Spacer(Modifier.height(28.dp))
        Text("Welcome to MigraineMe", color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text("Understand your triggers. Predict attacks before they happen.",
            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
        Text("Let's show you around and get things personalised.",
            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))

        Spacer(Modifier.weight(1f))

        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FeatureBullet(Icons.Outlined.Speed, "Real-time migraine risk prediction")
            FeatureBullet(Icons.Outlined.BarChart, "Pattern analysis across all your data")
            FeatureBullet(Icons.Outlined.AutoAwesome, "AI-powered daily insights")
            FeatureBullet(Icons.Outlined.FavoriteBorder, "Wearable & health data integration")
        }

        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun FeatureBullet(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(28.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun HowItWorksPage(
    alreadyRevealed: Boolean = false,
    onAllRevealed: () -> Unit = {}
) {
    val steps = listOf(
        Triple(Icons.Outlined.Sensors, "Connect", "Data flows in from your wearable, Health Connect and phone"),
        Triple(Icons.Outlined.Bolt, "Detect", "Sleep changes, weather shifts, and stress spikes get flagged automatically"),
        Triple(Icons.Outlined.Speed, "Score", "Everything adds up to your daily risk"),
        Triple(Icons.Outlined.CalendarMonth, "Predict", "See what's coming 7 days ahead"),
        Triple(Icons.Outlined.AutoAwesome, "Learn", "Gets smarter the more you use it"),
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
        Text("How It Works", color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
        Column {
            steps.forEachIndexed { index, (icon, title, subtitle) ->
                val isActive = index < revealedSteps
                val stepAlpha by animateFloatAsState(if (isActive) 1f else 0.15f, tween(600, easing = FastOutSlowInEasing), label = "a$index")
                val stepScale by animateFloatAsState(if (index == revealedSteps - 1 && !alreadyRevealed && revealedSteps <= steps.size) 1.04f else 1f, spring(dampingRatio = 0.5f, stiffness = 300f), label = "s$index")
                val offsetX by animateDpAsState(if (isActive) 0.dp else 40.dp, tween(500, easing = FastOutSlowInEasing), label = "x$index")
                Row(Modifier.fillMaxWidth().offset(x = offsetX).graphicsLayer { alpha = stepAlpha; scaleX = stepScale; scaleY = stepScale },
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    // Timeline: circle + connector line
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(Modifier.size(44.dp)
                                .background(if (isActive) AppTheme.AccentPurple else Color.White.copy(alpha = 0.1f), CircleShape))
                            if (isActive) {
                                Box(Modifier.size(52.dp)
                                    .border(2.dp, AppTheme.AccentPurple.copy(alpha = 0.4f), CircleShape))
                            }
                            Icon(icon, null, tint = if (isActive) Color.White else AppTheme.SubtleTextColor, modifier = Modifier.size(18.dp))
                        }
                        if (index < steps.size - 1) {
                            val lineColor = if (index < revealedSteps - 1) AppTheme.AccentPurple.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f)
                            Box(Modifier.width(2.dp).height(36.dp).background(lineColor))
                        }
                    }
                    // Text
                    Column(Modifier.padding(top = 10.dp)) {
                        Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Text(subtitle, color = if (isActive) AppTheme.BodyTextColor else AppTheme.SubtleTextColor.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
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
        Text("Enable Location", color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(12.dp))
        Text(
            "MigraineMe uses your location to track weather conditions — a top migraine trigger. We collect city-level data only, never your exact address.",
            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(Modifier.height(24.dp))
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureBullet(Icons.Outlined.Cloud, "Automatic weather tracking (pressure, humidity, temp)")
            FeatureBullet(Icons.Outlined.Schedule, "Timezone-accurate trigger detection")
            FeatureBullet(Icons.Outlined.Terrain, "Altitude change monitoring")
            FeatureBullet(Icons.Outlined.TrendingUp, "Better risk predictions")
        }

        Spacer(Modifier.weight(1f))

        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hasLocation.value) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF81C784), modifier = Modifier.size(20.dp))
                    Text("Location enabled", color = Color(0xFF81C784), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Continue", fontWeight = FontWeight.SemiBold) }
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
                ) { Text("Allow Location Access", fontWeight = FontWeight.SemiBold) }
            }
            TextButton(onClick = onSkip) {
                Text("Skip for now", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
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
        Text("Enable Notifications", color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(12.dp))
        Text(
            "Stay on top of your migraine risk with timely alerts. MigraineMe sends you daily check-in reminders and warns you when your risk spikes.",
            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(Modifier.height(24.dp))
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureBullet(Icons.Outlined.Warning, "High-risk alerts before migraines hit")
            FeatureBullet(Icons.Outlined.Nightlight, "Evening check-in reminders")
            FeatureBullet(Icons.Outlined.AutoAwesome, "AI calibration updates")
            FeatureBullet(Icons.Outlined.Article, "New articles matching your triggers")
        }

        Spacer(Modifier.weight(1f))

        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hasNotification.value) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF81C784), modifier = Modifier.size(20.dp))
                    Text("Notifications enabled", color = Color(0xFF81C784), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Continue", fontWeight = FontWeight.SemiBold) }
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
                ) { Text("Allow Notifications", fontWeight = FontWeight.SemiBold) }
            }
            TextButton(onClick = onSkip) {
                Text("Skip for now", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
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
        Text("Enable Microphone", color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(12.dp))
        Text(
            "MigraineMe can use your microphone for voice-based logging and ambient noise detection — both known migraine factors.",
            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(Modifier.height(24.dp))
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureBullet(Icons.Outlined.RecordVoiceOver, "Voice migraine logging")
            FeatureBullet(Icons.Outlined.GraphicEq, "Ambient noise sampling")
            FeatureBullet(Icons.Outlined.Nightlight, "Evening check-in voice input")
            FeatureBullet(Icons.Outlined.AutoAwesome, "AI story recording")
        }

        Spacer(Modifier.weight(1f))

        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hasMic.value) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF81C784), modifier = Modifier.size(20.dp))
                    Text("Microphone enabled", color = Color(0xFF81C784), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Continue", fontWeight = FontWeight.SemiBold) }
            } else {
                Button(
                    onClick = { launcher.launch(android.Manifest.permission.RECORD_AUDIO) },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Allow Microphone Access", fontWeight = FontWeight.SemiBold) }
            }
            TextButton(onClick = onSkip) {
                Text("Skip for now", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
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
        Text("Enable Screen Time", color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(12.dp))
        Text(
            "Screen time is a common migraine trigger. MigraineMe can track your usage patterns to find correlations with your attacks.",
            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(Modifier.height(24.dp))
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureBullet(Icons.Outlined.Timer, "Screen time trigger tracking")
            FeatureBullet(Icons.Outlined.Nightlight, "Late-night usage detection")
            FeatureBullet(Icons.Outlined.Apps, "App usage patterns")
            FeatureBullet(Icons.Outlined.SelfImprovement, "Digital wellness correlation")
        }

        Spacer(Modifier.weight(1f))

        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hasScreenTime.value) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF81C784), modifier = Modifier.size(20.dp))
                    Text("Screen time enabled", color = Color(0xFF81C784), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Continue", fontWeight = FontWeight.SemiBold) }
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
                ) { Text("Open Settings", fontWeight = FontWeight.SemiBold) }
            }
            TextButton(onClick = onSkip) {
                Text("Skip for now", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// MARK: - Background Location Permission Page

@Composable
fun BackgroundLocationPermissionPage(onGrant: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) onGrant() }
    fun checkBgLoc(): Boolean = context.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val hasBackgroundLocation = remember { mutableStateOf(checkBgLoc()) }
    LaunchedEffect(hasBackgroundLocation.value) { if (hasBackgroundLocation.value) { kotlinx.coroutines.delay(500); onGrant() } }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) hasBackgroundLocation.value = checkBgLoc()
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
            Icon(Icons.Outlined.LocationOn, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(40.dp))
        }

        Spacer(Modifier.height(24.dp))
        Text("Enable Background Location", color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(12.dp))
        Text(
            "For the most accurate predictions, MigraineMe needs location access even when the app is in the background. This enables continuous weather and pressure monitoring.",
            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(Modifier.height(24.dp))
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureBullet(Icons.Outlined.Cloud, "Continuous weather monitoring")
            FeatureBullet(Icons.Outlined.Nightlight, "Overnight pressure tracking")
            FeatureBullet(Icons.Outlined.Flight, "Travel detection")
            FeatureBullet(Icons.Outlined.TrendingUp, "Always-accurate forecasts")
        }

        Spacer(Modifier.weight(1f))

        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hasBackgroundLocation.value) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF81C784), modifier = Modifier.size(20.dp))
                    Text("Background location enabled", color = Color(0xFF81C784), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Continue", fontWeight = FontWeight.SemiBold) }
            } else {
                Button(
                    onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            launcher.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        } else {
                            onGrant()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Allow Background Location", fontWeight = FontWeight.SemiBold) }
            }
            TextButton(onClick = onSkip) {
                Text("Skip for now", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
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
        Text("Disable Battery Optimization", color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(12.dp))
        Text(
            "Android may pause MigraineMe in the background to save battery. Disabling optimization ensures reliable data collection and timely alerts.",
            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(Modifier.height(24.dp))
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureBullet(Icons.Outlined.Sync, "Reliable background sync")
            FeatureBullet(Icons.Outlined.Storage, "Uninterrupted data collection")
            FeatureBullet(Icons.Outlined.Notifications, "Timely notifications")
            FeatureBullet(Icons.Outlined.Speed, "Consistent risk scoring")
        }

        Spacer(Modifier.weight(1f))

        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isIgnoring.value) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF81C784), modifier = Modifier.size(20.dp))
                    Text("Battery optimization disabled", color = Color(0xFF81C784), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Continue", fontWeight = FontWeight.SemiBold) }
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
                ) { Text("Disable Battery Optimization", fontWeight = FontWeight.SemiBold) }
            }
            TextButton(onClick = onSkip) {
                Text("Skip for now", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun ConnectionsPage(onNavigateToConnections: () -> Unit, wearableConnected: String?, onWearableChanged: (String) -> Unit) {
    OnboardingScrollPage {
        OnboardingIconHeader(Icons.Outlined.Link, "Connect your data")
        Text("The more data MigraineMe has, the better it predicts.", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        listOf("Health Connect" to "Steps, sleep, heart rate, nutrition, and more", "WHOOP" to "Sleep, recovery, HRV, HR, SpO₂, skin temp", "Both" to "Get the best of both sources", "None" to "I'll log everything manually").forEach { (label, desc) ->
            OnboardingChoiceCard(label, desc, wearableConnected == label) { onWearableChanged(label) }
        }
        if (wearableConnected != null && wearableConnected != "None") {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onNavigateToConnections, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.AccentPurple)) {
                Icon(Icons.Outlined.Settings, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text("Set up connections now", color = AppTheme.AccentPurple)
            }
            Text("You can also do this later from the menu.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun DataCollectionPage(wearable: String?, enabledMetrics: MutableMap<String, Boolean>) {
    val hasWearable = wearable == "WHOOP" || wearable == "Both"
    val hasLocation = enabledMetrics["user_location_daily"] == true
    OnboardingScrollPage {
        OnboardingIconHeader(Icons.Outlined.Storage, "What do you want to track?")
        Text("Turn on the data you want MigraineMe to collect. You can change these later in Settings → Data.",
            color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        dataCollectionGroups.forEach { group ->
            Text(group.title, color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 4.dp))
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
        OnboardingIconHeader(Icons.Outlined.Person, "About your migraines")
        OnboardingQuestionSection("How often do you get migraines?") {
            listOf("Daily", "2-3x per week", "Weekly", "2-3x per month", "Monthly", "Rarely").forEach { OnboardingChoiceChip(it, frequency == it) { onFrequency(it) } }
        }
        OnboardingQuestionSection("How long do they usually last?") {
            listOf("A few hours", "Half a day", "A full day", "2-3 days", "More than 3 days").forEach { OnboardingChoiceChip(it, duration == it) { onDuration(it) } }
        }
        OnboardingQuestionSection("How severe are they typically?") {
            listOf("Mild — can push through", "Moderate — slows me down", "Severe — can't function", "Debilitating — bed rest required").forEach { OnboardingChoiceChip(it, severity == it) { onSeverity(it) } }
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun PersonalQuestionsPage2(timing: String?, onTiming: (String) -> Unit, warningSign: String?, onWarningSign: (String) -> Unit, medication: String?, onMedication: (String) -> Unit) {
    OnboardingScrollPage {
        OnboardingIconHeader(Icons.Outlined.Schedule, "Timing & patterns")
        OnboardingQuestionSection("When do they usually hit?") {
            listOf("Morning (wake up with it)", "Afternoon", "Evening", "Night", "No pattern / varies").forEach { OnboardingChoiceChip(it, timing == it) { onTiming(it) } }
        }
        OnboardingQuestionSection("Do you get warning signs before an attack?") {
            listOf("Yes, clearly — I can feel one coming", "Sometimes — occasional hints", "Rarely — they catch me off guard", "No — they come without warning").forEach { OnboardingChoiceChip(it, warningSign == it) { onWarningSign(it) } }
        }
        OnboardingQuestionSection("Do you take preventive or acute medication?") {
            listOf("Yes, preventive daily medication", "Yes, acute medication when needed", "Both preventive and acute", "No medication currently").forEach { OnboardingChoiceChip(it, medication == it) { onMedication(it) } }
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun PersonalQuestionsPage3(knownTriggerAreas: Set<String>, onTriggerAreas: (Set<String>) -> Unit, familyHistory: String?, onFamilyHistory: (String) -> Unit, trackCycle: String?, onTrackCycle: (String) -> Unit) {
    OnboardingScrollPage {
        OnboardingIconHeader(Icons.Outlined.Lightbulb, "What you already know")
        OnboardingQuestionSection("Which of these seem to affect your migraines? (select all that apply)") {
            listOf("Sleep", "Stress", "Weather", "Screen time", "Diet", "Hormones", "Exercise", "Not sure yet").forEach { area ->
                val selected = area in knownTriggerAreas
                OnboardingChoiceChip(area, selected) {
                    if (area == "Not sure yet") onTriggerAreas(setOf("Not sure yet"))
                    else { val new = knownTriggerAreas.toMutableSet(); new.remove("Not sure yet"); if (selected) new.remove(area) else new.add(area); onTriggerAreas(new) }
                }
            }
        }
        OnboardingQuestionSection("Does anyone in your family get migraines?") {
            listOf("Yes", "No", "Not sure").forEach { OnboardingChoiceChip(it, familyHistory == it) { onFamilyHistory(it) } }
        }
        OnboardingQuestionSection("Do you want to track your menstrual cycle?") {
            listOf("Yes", "No", "Not applicable").forEach { OnboardingChoiceChip(it, trackCycle == it) { onTrackCycle(it) } }
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun TriggerGroupPage(title: String, icon: ImageVector, questions: List<SeverityQuestion>, answers: MutableMap<String, SeverityChoice>) {
    OnboardingScrollPage {
        OnboardingIconHeader(icon, title)
        Text("How much does each of these affect your migraines?", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        questions.forEach { q -> SeverityQuestionCard(q, answers[q.label] ?: SeverityChoice.NONE) { answers[q.label] = it } }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun SuggestionsPage(suggestions: MutableMap<String, SeverityChoice>) {
    OnboardingScrollPage {
        OnboardingIconHeader(Icons.Outlined.AutoAwesome, "Your personalised model")
        Text("Based on your answers, here's what we suggest. Tap to adjust.", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        if (suggestions.isEmpty()) {
            Text("Complete the previous sections to get personalised suggestions.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
        } else {
            val grouped = suggestions.entries.sortedByDescending { it.value.ordinal }.groupBy { it.value }
            grouped.forEach { (severity, items) ->
                if (severity == SeverityChoice.NONE) return@forEach
                Text("${severity.label} influence", color = severity.color, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 8.dp))
                items.forEach { (label, sev) -> SuggestionRow(label, sev) { suggestions[label] = it } }
            }
            val noneCount = suggestions.count { it.value == SeverityChoice.NONE }
            if (noneCount > 0) Text("$noneCount triggers left at None (no influence)", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun RiskModelPage() {
    OnboardingScrollPage {
        OnboardingIconHeader(Icons.Outlined.Speed, "Your Risk Gauge")
        Text("Each trigger has a severity — you decide how much it matters:", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        SeverityExplainRow(SeverityChoice.HIGH, "Reliably triggers your migraines")
        SeverityExplainRow(SeverityChoice.MILD, "Contributes but not always the cause")
        SeverityExplainRow(SeverityChoice.LOW, "Might play a role occasionally")
        SeverityExplainRow(SeverityChoice.NONE, "No influence — doesn't count")
        Spacer(Modifier.height(12.dp))
        Text("Recent triggers weigh more than older ones. The score decays over 7 days.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text("Adjust everything anytime in Settings → Risk Model.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
private fun SeverityExplainRow(severity: SeverityChoice, description: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(severity.color, CircleShape))
        Text("${severity.label}:", color = severity.color, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.width(42.dp))
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
        Text("Almost there!", color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text("Your risk model is personalised.\n\nWe're loading some sample data so you can see the app in action. Next up: a quick tour of every screen.",
            color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

