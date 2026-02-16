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

object OnboardingPrefs {
    private const val PREFS_NAME = "onboarding"
    private const val KEY_COMPLETED = "completed"
    fun isCompleted(context: Context): Boolean = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_COMPLETED, false)
    fun setCompleted(context: Context) { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_COMPLETED, true).apply() }
    fun reset(context: Context) { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_COMPLETED, false).apply() }
}

private enum class PageId { WELCOME, HOW_IT_WORKS, SETUP_LANDING }

@Composable
fun OnboardingScreen(
    startAtSetup: Boolean = false,
    onComplete: () -> Unit,
    onStartTour: () -> Unit = {},
    onStartSetup: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext

    val pages = PageId.entries
    // If coming back after tour, jump straight to setup landing
    var currentIdx by remember { mutableStateOf(if (startAtSetup) pages.indexOf(PageId.SETUP_LANDING) else 0) }
    val currentPage = pages[currentIdx]

    // Seed demo data immediately so screens are populated by tour time
    var demoSeeded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!demoSeeded && !startAtSetup) {
            demoSeeded = true
            launch(Dispatchers.IO) {
                DemoDataSeeder.seedDemoData(appCtx)
            }
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
                        PageId.SETUP_LANDING -> SetupLandingPage()
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {

                // Left button
                when (currentPage) {
                    PageId.WELCOME -> {
                        TextButton(onClick = { OnboardingPrefs.setCompleted(appCtx); onComplete() }) {
                            Text("Skip", color = AppTheme.SubtleTextColor)
                        }
                    }
                    PageId.HOW_IT_WORKS -> {
                        TextButton(onClick = { currentIdx-- }) {
                            Text("Back", color = AppTheme.SubtleTextColor)
                        }
                    }
                    PageId.SETUP_LANDING -> {
                        TextButton(onClick = { OnboardingPrefs.setCompleted(appCtx); onComplete() }) {
                            Text("Skip for now", color = AppTheme.SubtleTextColor)
                        }
                    }
                }

                // Right button
                when (currentPage) {
                    PageId.WELCOME -> {
                        Button(onClick = { currentIdx++ }, colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple), shape = RoundedCornerShape(12.dp)) {
                            Text("Next"); Spacer(Modifier.width(4.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                        }
                    }
                    PageId.HOW_IT_WORKS -> {
                        Button(
                            onClick = { onStartTour() },
                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Take the Tour"); Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                        }
                    }
                    PageId.SETUP_LANDING -> {
                        Button(
                            onClick = {
                                OnboardingPrefs.setCompleted(appCtx)
                                onStartSetup()
                            },
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

        Text(
            "Now let's set up\nyour data",
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "We'll connect your wearables, choose health metrics to track, then AI will personalise your entire app. Takes about 2 minutes.",
            color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

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
        Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
