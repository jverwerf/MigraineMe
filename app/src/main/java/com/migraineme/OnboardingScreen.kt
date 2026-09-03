package com.migraineme

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.compose.runtime.rememberCoroutineScope

/**
 * Process-scoped flag for the "Rerun Onboarding (no seed)" Profile button.
 * When true, the onboarding flow skips ALL demo seeding AND deletion so a
 * user with real data can re-walk the tour without touching their account.
 * Set from ProfileScreen, cleared on onboarding completion / skip.
 */
object OnboardingMode {
    var noSeed: Boolean = false
}

object OnboardingPrefs {

    private const val PREFS = "onboarding_prefs"
    private fun cacheKey(userId: String) = "onboarding_completed_$userId"

    private fun readCached(context: Context, userId: String): Boolean? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(cacheKey(userId))) return null
        return prefs.getBoolean(cacheKey(userId), false)
    }

    private fun writeCached(context: Context, userId: String, completed: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(cacheKey(userId), completed)
            .apply()
    }

    /**
     * Has this user finished onboarding? `null` means WE DO NOT KNOW — the read
     * failed and this device has never been told the answer.
     *
     * This used to return a plain Boolean and answer `false` on every failure
     * path: no token, no user id, empty body, any exception. Offline that made
     * a long-since onboarded user look brand new, and MainActivity routed them
     * to Routes.ONBOARDING with popUpTo(inclusive) — no way back, and the flow
     * seeds demo data and re-grants the trial over a real account.
     *
     * Now: a successful read is cached locally, a failed read falls back to that
     * cache, and only a genuine "never seen this user" returns null.
     *
     * Must be called from IO dispatcher.
     */
    suspend fun isCompletedFromSupabase(context: Context): Boolean? {
        val tokenResult = SessionStore.getAccessToken(context)
        val userId = SessionStore.readUserId(context)
            ?: (tokenResult as? TokenResult.Valid)
                ?.let { JwtUtils.extractUserIdFromAccessToken(it.accessToken) }
            ?: return null
        val token = (tokenResult as? TokenResult.Valid)?.accessToken
            ?: return readCached(context, userId)
        return try {
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/profiles?user_id=eq.$userId&select=onboarding_completed"
            val request = okhttp3.Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .build()
            val response = okhttp3.OkHttpClient().newCall(request).execute()
            // A 401/500 body does not contain the flag either, and reading that
            // as "not onboarded" is the same bug in a different coat.
            if (!response.isSuccessful) {
                response.close()
                return readCached(context, userId)
            }
            val body = response.body?.string()
            response.close()
            if (body == null) return readCached(context, userId)
            val completed = body.contains("\"onboarding_completed\":true") ||
                body.contains("\"onboarding_completed\": true")
            writeCached(context, userId, completed)
            completed
        } catch (_: Exception) {
            readCached(context, userId)
        }
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
            val response = okhttp3.OkHttpClient().newCall(request).execute()
            val ok = response.isSuccessful
            response.close()
            // Only mirror a write the server ACCEPTED — caching an optimistic
            // "completed" would hide a dropped write behind a local true.
            if (ok) writeCached(context, userId, true)
        } catch (_: Exception) {}
    }

    // DEBUG: mirrors iOS resetOnboarding — flips onboarding_completed back to false.
    suspend fun resetInSupabase(context: Context) {
        try {
            val token = SessionStore.getValidAccessToken(context) ?: return
            val userId = SessionStore.readUserId(context) ?: JwtUtils.extractUserIdFromAccessToken(token) ?: return
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/profiles?user_id=eq.$userId"
            val request = okhttp3.Request.Builder()
                .url(url)
                .patch(
                    """{"onboarding_completed": false}""".toRequestBody("application/json".toMediaType())
                )
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()
            val response = okhttp3.OkHttpClient().newCall(request).execute()
            val ok = response.isSuccessful
            response.close()
            if (ok) writeCached(context, userId, false)
        } catch (_: Exception) {}
    }
}

private enum class PageId { WELCOME, HOW_IT_WORKS, CHOICE, LOADING_DATA, SETUP_LANDING, LOCATION_PERMISSION, NOTIFICATION_PERMISSION, MICROPHONE_PERMISSION, CALENDAR_PERMISSION, SCREEN_TIME_PERMISSION, BATTERY_OPTIMIZATION }

@Composable
fun OnboardingScreen(
    startAtSetup: Boolean = false,
    logVm: LogViewModel? = null,
    insightsVm: InsightsViewModel? = null,
    onComplete: () -> Unit,
    onStartTour: () -> Unit = {},
    onStartSetup: () -> Unit = {},
    onStartDataSettingsNoSeed: () -> Unit = {},
    onTourSkipped: () -> Unit = {},
    /** Acute-attack escape hatch: land in the log wizard, not on Home. Someone
     *  mid-attack tapped it to log the attack, so dropping them on Home makes
     *  them find the wizard themselves. Falls back to [onComplete] where the
     *  caller has nowhere to send them. */
    onAcuteAttack: () -> Unit = onComplete,
) {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext

    val pages = PageId.entries
    var currentIdx by rememberSaveable { mutableStateOf(if (startAtSetup) pages.indexOf(PageId.SETUP_LANDING) else 0) }
    // Clamp on read — two LaunchedEffects can race on permission grant and both
    // increment currentIdx, which would otherwise crash on the next render.
    val currentPage = pages[currentIdx.coerceIn(0, pages.size - 1)]

    // Auto-skip permission pages if already granted
    fun isPermissionGranted(page: PageId): Boolean = try {
        when (page) {
            PageId.LOCATION_PERMISSION ->
                appCtx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            PageId.NOTIFICATION_PERMISSION ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                    appCtx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                else true
            PageId.MICROPHONE_PERMISSION ->
                appCtx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            PageId.CALENDAR_PERMISSION ->
                appCtx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
            PageId.SCREEN_TIME_PERMISSION -> {
                val appOps = appCtx.getSystemService(android.content.Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                appOps.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), appCtx.packageName) == android.app.AppOpsManager.MODE_ALLOWED
            }
            PageId.BATTERY_OPTIMIZATION ->
                (appCtx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(appCtx.packageName)
            else -> false
        }
    } catch (_: Exception) { false }

    LaunchedEffect(currentIdx) {
        val page = pages.getOrNull(currentIdx) ?: return@LaunchedEffect
        if (page.name.contains("PERMISSION") || page == PageId.BATTERY_OPTIMIZATION) {
            if (isPermissionGranted(page)) {
                if (page == PageId.BATTERY_OPTIMIZATION) {
                    // Last permission — trigger setup flow instead of incrementing past the end
                    onStartSetup()
                } else {
                    currentIdx++
                }
            }
        }
    }

    // ── Observe seeder progress directly ──
    val seedProgress by DemoDataSeeder.progress.collectAsState()
    val dataReady by DemoDataSeeder.dataReady.collectAsState()
    var seedingStarted by rememberSaveable { mutableStateOf(false) }
    var hasNavigatedToTour by rememberSaveable { mutableStateOf(false) }
    var howItWorksRevealed by rememberSaveable { mutableStateOf(false) }
    var trialStarted by rememberSaveable { mutableStateOf(false) }
    var showSkipDataDialog by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // ── Grant the 14-day onboarding trial once when entering onboarding ──
    LaunchedEffect(Unit) {
        if (!trialStarted && !startAtSetup) {
            trialStarted = true
            scope.launch(Dispatchers.IO) {
                PremiumManager.startOnboardingTrial(appCtx)
            }
        }
    }

    // ── Skip exit: clear demo data + mark complete in Supabase + navigate ──
    // Trial is intentionally NOT cleared — the user keeps their 14 days.
    // No-seed mode never deletes anything (safe to use on accounts with real data).
    fun skipOnboarding(then: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            if (!OnboardingMode.noSeed) {
                DemoDataSeeder.clearDemoData(appCtx, logVm, insightsVm)
            }
            OnboardingPrefs.setCompletedInSupabase(appCtx)
            kotlinx.coroutines.withContext(Dispatchers.Main) { then() }
        }
    }

    // ── Tour/Setup entry: keep demo data, do NOT mark complete yet ──
    // A DB trigger (profiles_onboarding_complete_purge_demo) purges every demo
    // row the instant onboarding_completed flips to true, so marking complete
    // here deleted the tour's data before the first card was shown. Completion
    // is now written at the end of the flow (gift screen), matching iOS.
    fun proceedWithTour(then: () -> Unit) {
        then()
    }

    // ── Kick off seeding when we hit the LOADING_DATA page ──
    LaunchedEffect(currentPage) {
        if (currentPage == PageId.LOADING_DATA && !seedingStarted && !startAtSetup) {
            seedingStarted = true
            hasNavigatedToTour = false
            if (OnboardingMode.noSeed) {
                // No-seed mode: skip past the loading page straight to the tour.
                hasNavigatedToTour = true
                proceedWithTour { onStartTour() }
            } else {
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
                // Seed everything (including risk_score_live directly).
                // Track the Job on DemoDataSeeder so clearDemoData joins it
                // before deleting (prevents late inserts from surviving).
                val seedJob = launch(Dispatchers.IO) {
                    DemoDataSeeder.seedDemoData(appCtx, logVm)
                }
                DemoDataSeeder.setCurrentSeedJob(seedJob)
            }
        }
    }

    // ── Auto-advance to permission pages when data is ready ──
    LaunchedEffect(dataReady, currentPage) {
        if (dataReady && seedingStarted && !hasNavigatedToTour && currentPage == PageId.LOADING_DATA) {
            hasNavigatedToTour = true
            kotlinx.coroutines.delay(600L)
            proceedWithTour { onStartTour() }
        }
    }

    val bgBrush = remember { Brush.verticalGradient(listOf(Color(0xFF1A0029), Color(0xFF2A003D), Color(0xFF1A0029))) }

    Box(Modifier.fillMaxSize().background(bgBrush)) {
        // Welcome page gets the Home-style sky background fading into the gradient.
        if (currentPage == PageId.WELCOME) {
            // The Scaffold hosting this route still reserves the status-bar
            // inset even with no top bar, so without pulling the art up by
            // that amount it stops short of the screen top instead of
            // bleeding under the status bar.
            val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            Image(
                painter = painterResource(id = R.drawable.purple_sky_bg),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Crop from the TOP of the artwork, not its centre. Centre
                // crop skips the empty sky the illustration opens with and
                // pushes the swing hard against the status bar.
                alignment = Alignment.TopCenter,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.46f)
                    .align(Alignment.TopCenter)
                    .offset(y = -statusBarInset)
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.46f)
                    .align(Alignment.TopCenter)
                    .offset(y = -statusBarInset)
                    .background(
                        Brush.verticalGradient(
                            0.3f to Color.Transparent,
                            1f to Color(0xFF2A003D)
                        )
                    )
            )
        }
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(20.dp))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                run {
                    AnimatedContent(
                        targetState = currentIdx.coerceIn(0, pages.size - 1),
                        transitionSpec = {
                            if (targetState > initialState) slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                            else slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                        }, label = "page"
                    ) { idx ->
                        when (pages[idx.coerceIn(0, pages.size - 1)]) {
                            PageId.WELCOME -> ObWelcomePage(onNext = { currentIdx = pages.indexOf(PageId.HOW_IT_WORKS) })
                            PageId.HOW_IT_WORKS -> ObHowItWorksPage(
                                onDone = { currentIdx = pages.indexOf(PageId.CHOICE) },
                                onSkip = { currentIdx = pages.indexOf(PageId.CHOICE) }
                            )
                            PageId.CHOICE -> ObChoicePage(
                                onTakeTour = {
                                    // The tour runs on stills over the live app, so
                                    // nothing is seeded: no loading page, no demo rows,
                                    // no purge on exit. noSeed makes every existing
                                    // cleanup path a no-op.
                                    OnboardingMode.noSeed = true
                                    proceedWithTour { onStartTour() }
                                },
                                onSetUpProfile = {
                                    OnboardingMode.noSeed = true
                                    currentIdx = pages.indexOf(PageId.SETUP_LANDING)
                                },
                                onGoToApp = { skipOnboarding { onAcuteAttack() } }
                            )
                            PageId.LOADING_DATA -> LoadingDataPage(
                                progress = seedProgress.fraction,
                                statusText = seedProgress.phase,
                                isComplete = dataReady
                            )
                            PageId.LOCATION_PERMISSION -> LocationPermissionPage(onGrant = { currentIdx++ }, onSkip = { currentIdx++ })
                            PageId.NOTIFICATION_PERMISSION -> NotificationPermissionPage(onGrant = { currentIdx++ }, onSkip = { currentIdx++ })
                            PageId.MICROPHONE_PERMISSION -> MicrophonePermissionPage(onGrant = { currentIdx++ }, onSkip = { currentIdx++ })
                            PageId.CALENDAR_PERMISSION -> CalendarPermissionPage(onGrant = { currentIdx++ }, onSkip = { currentIdx++ })
                            PageId.SCREEN_TIME_PERMISSION -> ScreenTimePermissionPage(onGrant = { currentIdx++ }, onSkip = { currentIdx++ })
                            PageId.BATTERY_OPTIMIZATION -> BatteryOptimizationPage(onGrant = { onStartSetup() }, onSkip = { onStartSetup() })
                            PageId.SETUP_LANDING -> SetupLandingPage()
                        }
                    }
                }
            }

            // ── Bottom buttons ──
            if (currentPage == PageId.WELCOME || currentPage == PageId.HOW_IT_WORKS || currentPage == PageId.CHOICE || currentPage == PageId.LOCATION_PERMISSION || currentPage == PageId.NOTIFICATION_PERMISSION || currentPage == PageId.MICROPHONE_PERMISSION || currentPage == PageId.CALENDAR_PERMISSION || currentPage == PageId.SCREEN_TIME_PERMISSION || currentPage == PageId.BATTERY_OPTIMIZATION) {
                // These pages handle their own buttons
            } else Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left
                when (currentPage) {
                    PageId.LOADING_DATA -> {
                        TextButton(onClick = { showSkipDataDialog = true }) {
                            Text(t("Skip"), color = AppTheme.SubtleTextColor)
                        }
                    }
                    PageId.SETUP_LANDING -> {
                        TextButton(onClick = { skipOnboarding { onComplete() } }) {
                            Text(t("Skip"), color = AppTheme.SubtleTextColor)
                        }
                    }
                    else -> {}
                }

                // Right
                when (currentPage) {
                    PageId.HOW_IT_WORKS -> {}
                    PageId.CHOICE -> {}
                    PageId.WELCOME -> {}
                    PageId.LOCATION_PERMISSION -> {}
                    PageId.NOTIFICATION_PERMISSION -> {}
                    PageId.MICROPHONE_PERMISSION -> {}
                    PageId.CALENDAR_PERMISSION -> {}
                    PageId.SCREEN_TIME_PERMISSION -> {}
                    PageId.BATTERY_OPTIMIZATION -> {}
                    PageId.LOADING_DATA -> {
                        val canStart = dataReady && seedingStarted && !hasNavigatedToTour
                        Button(
                            onClick = { if (!hasNavigatedToTour) { hasNavigatedToTour = true; proceedWithTour { onStartTour() } } },
                            enabled = canStart,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppTheme.AccentPink,
                                disabledContainerColor = AppTheme.AccentPink.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (canStart) {
                                Text(t("Start Tour")); Spacer(Modifier.width(4.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                            } else {
                                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp)); Text(t("Loading…"))
                            }
                        }
                    }
                    PageId.SETUP_LANDING -> {
                        Button(
                            onClick = { currentIdx = pages.indexOf(PageId.LOCATION_PERMISSION) },
                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(t("Let's go")); Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    // ── Skip-while-loading confirmation ──
    if (showSkipDataDialog) {
        AlertDialog(
            onDismissRequest = { showSkipDataDialog = false },
            containerColor = Color(0xFF2A003D),
            shape = RoundedCornerShape(20.dp),
            title = { Text(t("Skip loading?"), color = Color.White, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    t("If you skip now, only limited demo data will be shown. The app will work perfectly fine — you'll just see fewer example insights in the tour."),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSkipDataDialog = false
                    // Stop seeding, keep whatever partial demo data exists, and
                    // advance to the next page (the tour) right away.
                    DemoDataSeeder.cancelSeedJob()
                    if (!hasNavigatedToTour) {
                        hasNavigatedToTour = true
                        proceedWithTour { onStartTour() }
                    }
                }) {
                    Text(t("Skip anyway"), color = AppTheme.AccentPink, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSkipDataDialog = false }) {
                    Text(t("Keep loading"), color = AppTheme.SubtleTextColor)
                }
            }
        )
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// Loading Data Page
// ═════════════════════════════════════════════════════════════════════════════

private val LOADING_FACTS = listOf(
    "MigraineMe surfaces patterns in the data you log.",
    "Chat with your own data — ask MigraineMe anything.",
    "MigraineMe learns your patterns and recalibrates every month.",
    "A 7-day risk outlook on your home screen.",
    "HRV, pressure and sleep changes tracked alongside your attacks.",
    "See which treatments are actually working — efficacy scored against your data.",
    "AI recommendations every month based on what your data actually shows.",
    "30+ migraine signals tracked automatically.",
    "Connect Oura, Polar, Garmin and Health Connect.",
    "Your full migraine profile, AI-configured in under a minute.",
    "Cycle-aware: hormonal windows feed straight into your risk score.",
    "Watch your risk gauge fill in real time.",
    "\"It's happening right now\" — one tap to log, fill in details later.",
    "Caffeine, sodium, tyramine, alcohol, gluten and histamine — flagged in every meal.",
    "One-tap doctor-ready PDF of everything you've logged.",
    "Talk through your day — voice-powered evening check-ins.",
    "Customise every trigger, prodrome, threshold and weight to fit you.",
    "Personalised companions for nutrition, sleep, stress and more.",
    "Pulls food from MyFitnessPal, Cronometer or barcode scan in seconds.",
    "Wear OS, Garmin and your phone — always in sync.",
    "Triggers, prodromes, reliefs, treatments — all in one place.",
    "Articles and a forum built for migraine sufferers, by migraine sufferers.",
    "Your data, locked to your account. End-to-end private.",
)

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

    var factIndex by remember { mutableStateOf(0) }
    LaunchedEffect(isComplete) {
        if (!isComplete) {
            while (true) {
                kotlinx.coroutines.delay(3500)
                factIndex = (factIndex + 1) % LOADING_FACTS.size
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.fillMaxHeight(0.4f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (!isComplete) {
                        // Slow, calm ring — one revolution every 6s instead of the
                        // default indeterminate spinner's frantic pace.
                        val ringTransition = rememberInfiniteTransition(label = "ring")
                        val ringAngle by ringTransition.animateFloat(
                            0f, 360f,
                            infiniteRepeatable(tween(6000, easing = LinearEasing)),
                            label = "ringAngle"
                        )
                        androidx.compose.foundation.Canvas(
                            Modifier.size(80.dp).rotate(ringAngle)
                        ) {
                            drawArc(
                                color = AppTheme.AccentPink,
                                startAngle = 0f,
                                sweepAngle = 270f,
                                useCenter = false,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = 2.5.dp.toPx(),
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                                )
                            )
                        }
                    }
                    Box(
                        Modifier
                            .size((60 * if (!isComplete) pulseScale else 1f).dp)
                            .background(
                                Brush.linearGradient(listOf(
                                    Color(0x57CE93D8),
                                    Color(0x24B388FF)
                                )),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.brainy_briefcase_small),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    if (isComplete) {
                        Icon(
                            Icons.Outlined.CheckCircle, null, tint = Color(0xFF81C784),
                            modifier = Modifier.size(22.dp).align(Alignment.BottomEnd)
                        )
                    }
                }

                Text(
                    if (isComplete) t("All set!") else t("Getting things ready"),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )

                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppTheme.AccentPurple.copy(alpha = 0.12f))
                        .border(1.dp, AppTheme.AccentPurple.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        if (isComplete) t("The app is ready for the tour.")
                        else t("We're preparing your example data — this usually takes up to a minute. After the tour we'll ask a few quick questions and connect your wearables. Just once."),
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }

                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(5.dp)
                        .clip(RoundedCornerShape(2.5.dp))
                        .background(AppTheme.TrackColor)
                ) {
                    Box(
                        Modifier.fillMaxWidth(animatedProgress).height(5.dp)
                            .clip(RoundedCornerShape(2.5.dp))
                            .background(Brush.horizontalGradient(listOf(AppTheme.AccentPurple, AppTheme.AccentPink)))
                    )
                }

                if (!isComplete) {
                    AnimatedContent(
                        targetState = factIndex,
                        transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(300)) },
                        label = "fact"
                    ) { i ->
                        Text(
                            t(LOADING_FACTS[i]),
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
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
        // One Brainy mark rather than three overlapping glyph circles: the
        // character introduces the setup, and it keeps this page to the point.
        Image(
            painter = painterResource(id = R.drawable.brainy_briefcase),
            contentDescription = null,
            modifier = Modifier.size(96.dp)
        )

        Spacer(Modifier.height(20.dp))
        Text(t("Now let's set up\nyour data"), color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(t("We'll connect your wearables, choose health metrics to track, then AI will personalise your entire app. Takes a few minutes."),
            color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(28.dp))
        SetupStepPreview(Icons.Outlined.Lock, t("1. Permissions"), t("Location, notifications, microphone, calendar"))
        Spacer(Modifier.height(8.dp))
        SetupStepPreview(Icons.Outlined.Link, t("2. Connect"), t("Health Connect, Oura, Polar, Garmin"))
        Spacer(Modifier.height(8.dp))
        SetupStepPreview(Icons.Outlined.Storage, t("3. Configure"), t("Choose which data to collect"))
        Spacer(Modifier.height(8.dp))
        SetupStepPreview(Icons.Outlined.AutoAwesome, t("4. AI Personalisation"), t("Answer questions about your migraines, AI configures everything"))
        Spacer(Modifier.height(14.dp))
        // Pointer to the policy; iOS and the RN apps carry the same line.
        Text(
            t("See our Privacy Policy for details."),
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
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

