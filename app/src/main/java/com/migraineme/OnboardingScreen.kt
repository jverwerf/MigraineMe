package com.migraineme

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.compose.runtime.rememberCoroutineScope

object OnboardingPrefs {

    /**
     * Check Supabase directly — no local cache.
     * Must be called from IO dispatcher.
     */
    suspend fun isCompletedFromSupabase(context: Context): Boolean {
        return try {
            val token = SessionStore.getValidAccessToken(context) ?: return false
            val userId = SessionStore.readUserId(context) ?: JwtUtils.extractUserIdFromAccessToken(token) ?: return false
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/profiles?user_id=eq.$userId&select=onboarding_completed"
            val request = okhttp3.Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .build()
            val response = okhttp3.OkHttpClient().newCall(request).execute()
            val body = response.body?.string() ?: return false
            response.close()
            body.contains("\"onboarding_completed\":true") || body.contains("\"onboarding_completed\": true")
        } catch (_: Exception) { false }
    }

    /**
     * Mark onboarding complete in Supabase.
     * Must be called from IO dispatcher.
     */
    suspend fun setCompletedInSupabase(context: Context) {
        try {
            val token = SessionStore.getValidAccessToken(context) ?: return
            val userId = SessionStore.readUserId(context) ?: JwtUtils.extractUserIdFromAccessToken(token) ?: return
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/profiles?user_id=eq.$userId"
            val request = okhttp3.Request.Builder()
                .url(url)
                .patch(
                    """{"onboarding_completed": true}""".toRequestBody("application/json".toMediaType())
                )
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()
            okhttp3.OkHttpClient().newCall(request).execute().close()
        } catch (_: Exception) {}
    }
}

private enum class PageId { WELCOME, HOW_IT_WORKS, LOADING_DATA, SETUP_LANDING }

@Composable
fun OnboardingScreen(
    startAtSetup: Boolean = false,
    onComplete: () -> Unit,
    onStartTour: () -> Unit = {},
    onStartSetup: () -> Unit = {},
    onTourSkipped: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext

    val pages = PageId.entries
    var currentIdx by remember { mutableStateOf(if (startAtSetup) pages.indexOf(PageId.SETUP_LANDING) else 0) }
    val currentPage = pages[currentIdx]

    // ── Observe seeder progress directly ──
    val seedProgress by DemoDataSeeder.progress.collectAsState()
    val dataReady by DemoDataSeeder.dataReady.collectAsState()
    var seedingStarted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // ── Skip exit: clear demo data + mark complete in Supabase + navigate ──
    fun skipOnboarding(then: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            DemoDataSeeder.clearDemoData(appCtx)
            OnboardingPrefs.setCompletedInSupabase(appCtx)
            kotlinx.coroutines.withContext(Dispatchers.Main) { then() }
        }
    }

    // ── Tour/Setup exit: keep demo data for the tour, just mark complete ──
    fun proceedWithTour(then: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            OnboardingPrefs.setCompletedInSupabase(appCtx)
            kotlinx.coroutines.withContext(Dispatchers.Main) { then() }
        }
    }

    // ── Kick off seeding when we hit the LOADING_DATA page ──
    LaunchedEffect(currentPage) {
        if (currentPage == PageId.LOADING_DATA && !seedingStarted && !startAtSetup) {
            seedingStarted = true
            // Ensure userId is set before seeding
            withContext(Dispatchers.IO) {
                val token = SessionStore.getValidAccessToken(appCtx)
                if (token != null) {
                    var userId = SessionStore.readUserId(appCtx)
                    if (userId.isNullOrBlank()) {
                        userId = JwtUtils.extractUserIdFromAccessToken(token)
                        if (!userId.isNullOrBlank()) {
                            SessionStore.saveUserId(appCtx, userId)
                        }
                    }
                }
            }
            // Seed everything (including risk_score_live directly)
            launch(Dispatchers.IO) {
                DemoDataSeeder.seedDemoData(appCtx)
            }
        }
    }

    // ── Auto-advance to tour when data is ready ──
    LaunchedEffect(dataReady) {
        if (dataReady && currentPage == PageId.LOADING_DATA) {
            kotlinx.coroutines.delay(600L)
            proceedWithTour { onStartTour() }
        }
    }

    val bgBrush = remember { Brush.verticalGradient(listOf(Color(0xFF1A0029), Color(0xFF2A003D), Color(0xFF1A0029))) }

    Box(Modifier.fillMaxSize().background(bgBrush)) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(20.dp))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = currentIdx,
                    transitionSpec = {
                        if (targetState > initialState) slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                        else slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                    }, label = "page"
                ) { idx ->
                    when (pages[idx]) {
                        PageId.WELCOME -> WelcomePage()
                        PageId.HOW_IT_WORKS -> HowItWorksPage()
                        PageId.LOADING_DATA -> LoadingDataPage(
                            progress = seedProgress.fraction,
                            statusText = seedProgress.phase,
                            isComplete = dataReady
                        )
                        PageId.SETUP_LANDING -> SetupLandingPage()
                    }
                }
            }

            // ── Bottom buttons ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left
                when (currentPage) {
                    PageId.WELCOME -> {
                        TextButton(onClick = { skipOnboarding { onComplete() } }) {
                            Text("Skip", color = AppTheme.SubtleTextColor)
                        }
                    }
                    PageId.HOW_IT_WORKS -> {
                        TextButton(onClick = { currentIdx-- }) {
                            Text("Back", color = AppTheme.SubtleTextColor)
                        }
                    }
                    PageId.LOADING_DATA -> {
                        TextButton(onClick = { skipOnboarding { onComplete() } }) {
                            Text("Skip", color = AppTheme.SubtleTextColor)
                        }
                    }
                    PageId.SETUP_LANDING -> {
                        TextButton(onClick = { skipOnboarding { onComplete() } }) {
                            Text("Skip", color = AppTheme.SubtleTextColor)
                        }
                    }
                }

                // Right
                when (currentPage) {
                    PageId.WELCOME -> {
                        Button(
                            onClick = { currentIdx++ },
                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Next"); Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                        }
                    }
                    PageId.HOW_IT_WORKS -> {
                        Button(
                            onClick = { currentIdx = pages.indexOf(PageId.LOADING_DATA) },
                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Take the Tour"); Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                        }
                    }
                    PageId.LOADING_DATA -> {
                        Button(
                            onClick = { proceedWithTour { onStartTour() } },
                            enabled = dataReady,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppTheme.AccentPink,
                                disabledContainerColor = AppTheme.AccentPink.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (dataReady) {
                                Text("Start Tour"); Spacer(Modifier.width(4.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                            } else {
                                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp)); Text("Loading…")
                            }
                        }
                    }
                    PageId.SETUP_LANDING -> {
                        Button(
                            onClick = { proceedWithTour { onStartSetup() } },
                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Let's go"); Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Loading Data Page
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun LoadingDataPage(progress: Float, statusText: String, isComplete: Boolean) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "progress"
    )

    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulse.animateFloat(0.95f, 1.08f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "s")
    val pulseAlpha by pulse.animateFloat(0.4f, 0.8f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "a")

    data class LoadStep(val label: String, val threshold: Float, val icon: androidx.compose.ui.graphics.vector.ImageVector)
    val steps = listOf(
        LoadStep("Account", 0.05f, Icons.Outlined.Person),
        LoadStep("Sleep & health data", 0.25f, Icons.Outlined.FavoriteBorder),
        LoadStep("Weather & nutrition", 0.50f, Icons.Outlined.Cloud),
        LoadStep("Migraine history", 0.70f, Icons.Outlined.Psychology),
        LoadStep("Risk gauge", 0.85f, Icons.Outlined.Speed),
        LoadStep("Ready!", 1.0f, Icons.Outlined.CheckCircle),
    )

    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Animated icon
        Box(
            Modifier
                .size((80 * if (!isComplete) pulseScale else 1f).dp)
                .background(
                    Brush.linearGradient(listOf(
                        AppTheme.AccentPurple.copy(alpha = if (!isComplete) pulseAlpha else 0.8f),
                        AppTheme.AccentPink.copy(alpha = if (!isComplete) pulseAlpha * 0.7f else 0.6f)
                    )),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isComplete) Icons.Outlined.CheckCircle else Icons.Outlined.CloudSync,
                null, tint = Color.White, modifier = Modifier.size(40.dp)
            )
        }

        Spacer(Modifier.height(28.dp))

        Text(
            if (isComplete) "All set!" else "Preparing your onboarding",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            if (isComplete) "Your dashboard is ready for the tour."
            else "We're getting everything ready so you\ncan see the app in action.",
            color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        // Progress bar
        Box(
            Modifier.fillMaxWidth().height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(AppTheme.TrackColor)
        ) {
            Box(
                Modifier.fillMaxWidth(animatedProgress).height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient(listOf(AppTheme.AccentPurple, AppTheme.AccentPink)))
            )
        }

        Spacer(Modifier.height(24.dp))

        // Step checklist
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            steps.forEach { step ->
                val isDone = animatedProgress >= step.threshold
                val isActive = !isDone && animatedProgress >= (step.threshold - 0.15f)
                val alpha by animateFloatAsState(
                    when { isDone -> 1f; isActive -> 0.9f; else -> 0.35f },
                    tween(400), label = "a_${step.label}"
                )

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(32.dp).background(
                            when {
                                isDone -> AppTheme.AccentPurple.copy(alpha = 0.25f)
                                isActive -> AppTheme.AccentPink.copy(alpha = 0.15f)
                                else -> Color.White.copy(alpha = 0.05f)
                            }, CircleShape
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            isDone -> Icon(Icons.Outlined.Check, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(16.dp))
                            isActive -> CircularProgressIndicator(Modifier.size(14.dp), AppTheme.AccentPink, strokeWidth = 2.dp)
                            else -> Icon(step.icon, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(16.dp))
                        }
                    }
                    Text(
                        step.label,
                        color = Color.White.copy(alpha = alpha),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (isDone || isActive) FontWeight.Medium else FontWeight.Normal
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Current status text
        AnimatedContent(
            targetState = statusText,
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
            label = "status"
        ) { text ->
            Text(
                text,
                color = if (isComplete) AppTheme.AccentPurple else AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (isComplete) FontWeight.SemiBold else FontWeight.Normal
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun SetupLandingPage() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
            Box(Modifier.size(56.dp).background(AppTheme.AccentPurple.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Link, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(28.dp))
            }
            Box(Modifier.size(56.dp).background(AppTheme.AccentPink.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Storage, null, tint = AppTheme.AccentPink, modifier = Modifier.size(28.dp))
            }
            Box(Modifier.size(56.dp).background(Color(0xFFFFB74D).copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = Color(0xFFFFB74D), modifier = Modifier.size(28.dp))
            }
        }

        Spacer(Modifier.height(28.dp))
        Text("Now let's set up\nyour data", color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text("We'll connect your wearables, choose health metrics to track, then AI will personalise your entire app. Takes about 2 minutes.",
            color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(28.dp))
        SetupStepPreview(Icons.Outlined.Link, "1. Connect", "WHOOP, Health Connect")
        Spacer(Modifier.height(10.dp))
        SetupStepPreview(Icons.Outlined.Storage, "2. Configure", "Choose which data to collect")
        Spacer(Modifier.height(10.dp))
        SetupStepPreview(Icons.Outlined.AutoAwesome, "3. AI Personalisation", "Answer a few questions, AI sets up everything")
    }
}

@Composable
private fun SetupStepPreview(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Row(
        Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).background(AppTheme.AccentPurple.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp))
        }
        Column {
            Text(title, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(subtitle, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
        }
    }
}

