package com.migraineme

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.google.firebase.messaging.FirebaseMessaging
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Star
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.get
import io.ktor.client.request.header


object Routes {
    const val HOME = "home"
    const val PROFILE = "profile"
    const val DATA = "data"
    const val MENSTRUATION_SETTINGS = "menstruation_settings"
    const val COMMUNITY = "community"
    const val ARTICLE_DETAIL = "community/article"
    const val FORUM_POST_DETAIL = "community/forum"
    const val INSIGHTS = "insights"
    const val INSIGHTS_DETAIL = "insights_detail"
    const val INSIGHTS_REPORT = "insights_report"
    const val INSIGHTS_BREAKDOWN = "insights_breakdown"
    const val MONITOR = "monitor"
    const val MONITOR_CONFIG = "monitor_config"
    const val JOURNAL = "journal"

    const val MIGRAINE = "migraine"
    const val LOG_MIGRAINE = "log_migraine"  // Full migraine wizard
    const val PAIN_LOCATION = "pain_location"  // Pain location picker (wizard page 2)
    const val PRODROMES_LOG = "prodromes_log"  // Prodromes picker (wizard page 3)
    const val QUICK_LOG_TRIGGER = "quick_log_trigger"
    const val QUICK_LOG_MEDICINE = "quick_log_medicine"
    const val QUICK_LOG_RELIEF = "quick_log_relief"
    const val QUICK_LOG_ACTIVITY = "quick_log_activity"
    const val QUICK_LOG_PRODROME = "quick_log_prodrome"
    const val QUICK_LOG_MIGRAINE = "quick_log_migraine"
    
    const val MONITOR_NUTRITION = "monitor_nutrition"
    const val NUTRITION_CONFIG = "nutrition_config"
    const val NUTRITION_HISTORY = "nutrition_history"
    const val WEATHER_CONFIG = "weather_config"
    const val SLEEP_DATA_HISTORY = "sleep_data_history"
    const val ENV_DATA_HISTORY = "env_data_history"
    const val MONITOR_PHYSICAL = "monitor_physical"
    const val PHYSICAL_CONFIG = "physical_config"
    const val PHYSICAL_DATA_HISTORY = "physical_data_history"
    const val FULL_GRAPH_PHYSICAL = "full_graph_physical"
    const val MONITOR_SLEEP = "monitor_sleep"
    const val SLEEP_CONFIG = "sleep_config"
    const val FULL_GRAPH = "full_graph"
    const val FULL_GRAPH_SLEEP = "full_graph_sleep"
    const val FULL_GRAPH_WEATHER = "full_graph_weather"
    const val FULL_GRAPH_NUTRITION = "full_graph_nutrition"
    const val MONITOR_MENTAL = "monitor_mental"
    const val MENTAL_CONFIG = "mental_config"
    const val MENTAL_DATA_HISTORY = "mental_data_history"
    const val FULL_GRAPH_MENTAL = "full_graph_mental"
    const val MONITOR_ENVIRONMENT = "monitor_environment"
    
    const val TRIGGERS = "triggers"
    const val ADJUST_TRIGGERS = "adjust_triggers"
    const val MEDICINES = "medicines"
    const val ADJUST_MEDICINES = "adjust_medicines"
    const val RELIEFS = "reliefs"
    const val ADJUST_RELIEFS = "adjust_reliefs"
    const val REVIEW = "review"
    const val NOTES = "notes"

    const val LOGIN = "login"
    const val SIGNUP = "signup"
    const val LOGOUT = "logout"

    const val EDIT_MIGRAINE = "edit_migraine"
    const val EDIT_TRIGGER = "edit_trigger"
    const val EDIT_MEDICINE = "edit_medicine"
    const val EDIT_RELIEF = "edit_relief"
    const val EDIT_PRODROME = "edit_prodrome"
    const val EDIT_ACTIVITY = "edit_activity"
    const val EDIT_LOCATION = "edit_location"

    const val ADJUST_MIGRAINES = "adjust_migraines"
    const val MANAGE_SYMPTOMS = "manage_symptoms"
    const val MANAGE_ITEMS = "manage_items"
    const val MANAGE_TRIGGERS = "manage_triggers"
    const val MANAGE_MEDICINES = "manage_medicines"
    const val MANAGE_RELIEFS = "manage_reliefs"
    const val MANAGE_PRODROMES = "manage_prodromes"
    const val LOCATIONS = "locations"
    const val ACTIVITIES = "activities"
    const val MANAGE_LOCATIONS = "manage_locations"
    const val MANAGE_ACTIVITIES = "manage_activities"
    const val MISSED_ACTIVITIES = "missed_activities"
    const val MANAGE_MISSED_ACTIVITIES = "manage_missed_activities"
    const val TIMING = "timing"


    const val THIRD_PARTY_CONNECTIONS = "third_party_connections"

    const val CHANGE_PASSWORD = "change_password"
    
    const val TRIGGERS_SETTINGS = "triggers_settings"
    const val RISK_WEIGHTS = "risk_weights"
    const val RISK_DETAIL = "risk_detail"
    const val TESTING = "testing"
    const val ONBOARDING = "onboarding"
    const val AI_SETUP = "ai_setup"
    const val CUSTOMIZE_TRIGGERS = "customize_triggers"
    const val EVENING_CHECKIN = "evening_checkin"
    const val RECALIBRATION_REVIEW = "recalibration_review"
    const val PAYWALL = "paywall"
}

class MainActivity : ComponentActivity() {

    // Deep link route from notification tap
    private var pendingNavigationRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        window.setBackgroundDrawable(null)

        handleWhoopOAuthIntent(intent)
        handleSupabaseOAuthIntent(intent)
        handleNavigationIntent(intent)

        setContent {
            MaterialTheme {
                AppRoot(pendingNavigationRoute = pendingNavigationRoute)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWhoopOAuthIntent(intent)
        handleSupabaseOAuthIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleWhoopOAuthIntent(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data?.scheme == "whoop" && data.host == "migraineme" && data.path == "/callback") {

            val code = data.getQueryParameter("code")
            val state = data.getQueryParameter("state")
            val error = data.getQueryParameter("error")

            val prefs = getSharedPreferences("whoop_oauth", MODE_PRIVATE)
            prefs.edit()
                .putString("last_uri", data.toString())
                .putString("code", code)
                .putString("state", state)
                .putString("error", error)
                .apply()

            when {
                !error.isNullOrBlank() -> {
                    Toast.makeText(this, "WHOOP auth error: $error", Toast.LENGTH_SHORT).show()
                }

                !code.isNullOrBlank() -> {
                    Toast.makeText(this, "Returning from WHOOP...", Toast.LENGTH_SHORT).show()
                }

                else -> {
                    Toast.makeText(this, "WHOOP callback opened.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleSupabaseOAuthIntent(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data?.scheme == "migraineme" && data.host == "auth" && data.path == "/callback") {
            val prefs = getSharedPreferences("supabase_oauth", MODE_PRIVATE)
            prefs.edit()
                .putString("last_uri", data.toString())
                .apply()

            Toast.makeText(this, "Returning from sign-in...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleNavigationIntent(intent: Intent?) {
        intent?.getStringExtra("navigate_to")?.let { route ->
            pendingNavigationRoute.value = route
            // Clear so it doesn't re-trigger
            intent.removeExtra("navigate_to")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(pendingNavigationRoute: MutableState<String?> = mutableStateOf(null)) {
    val nav = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current

    val authVm: AuthViewModel = viewModel()
    val logVm: LogViewModel = viewModel()
    val triggerVm: TriggerViewModel = viewModel()
    val medVm: MedicineViewModel = viewModel()
    val reliefVm: ReliefViewModel = viewModel()
    val migraineVm: MigraineViewModel = viewModel()
    val symptomVm: SymptomViewModel = viewModel()
    val homeVm: HomeViewModel = viewModel()
    val communityVm: CommunityViewModel = viewModel()

    val authState by authVm.state.collectAsState()
    val token = authState.accessToken

    val communityState by communityVm.state.collectAsState()
    val communityUnreadCount = communityState.unreadCount

    // Refresh community unread count when authenticated
    LaunchedEffect(token) {
        if (!token.isNullOrBlank()) {
            communityVm.refreshUnreadCount(token)
        }
    }

    // ── Premium state ──
    LaunchedEffect(token) {
        if (!token.isNullOrBlank()) {
            val userId = SessionStore.readUserId(appCtx)
            PremiumManager.initialize(appCtx, userId)
            PremiumManager.loadState(appCtx)
        } else {
            PremiumManager.reset()
        }
    }

    var lastPreloadedToken by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        authVm.syncFromSessionStore(appCtx)
        
        // Get and save FCM token
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                android.util.Log.d("FCM", "FCM Token: $token")
                // Save token locally
                SessionStore.saveFcmToken(appCtx, token)
                // Save to Supabase (needs to be done after login, handled in MigraineMeFirebaseService)
            } else {
                android.util.Log.e("FCM", "Failed to get FCM token", task.exception)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    authVm.syncFromSessionStore(appCtx)
                }
                // Run location sync on app resume
                LocationDailySyncWorker.runOnceNow(appCtx)
                
                // Run Health Connect sync on app resume
                // This is the primary sync method - always works when app is open
                // FCM-triggered syncs are secondary (only work when app is in foreground)
                HealthConnectSyncManager.triggerSyncIfEnabled(appCtx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(token) {
        if (token.isNullOrBlank()) {
            lastPreloadedToken = null
            return@LaunchedEffect
        }

        val valid = authVm.getValidAccessToken(appCtx)
        if (valid.isNullOrBlank()) {
            lastPreloadedToken = null
            return@LaunchedEffect
        }

        if (lastPreloadedToken != valid) {
            logVm.loadJournal(valid)
            lastPreloadedToken = valid
            
            // Save FCM token to Supabase now that we have a valid access token
            val fcmToken = SessionStore.readFcmToken(appCtx)
            if (!fcmToken.isNullOrBlank()) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val userId = SessionStore.readUserId(appCtx)
                        if (!userId.isNullOrBlank()) {
                            val client = okhttp3.OkHttpClient()
                            val json = org.json.JSONObject().apply {
                                put("fcm_token", fcmToken)
                            }
                            val request = okhttp3.Request.Builder()
                                .url("${BuildConfig.SUPABASE_URL}/rest/v1/profiles?user_id=eq.$userId")
                                .header("Authorization", "Bearer $valid")
                                .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                                .header("Content-Type", "application/json")
                                .header("Prefer", "return=minimal")
                                .patch(okhttp3.RequestBody.create(
                                    "application/json".toMediaTypeOrNull(),
                                    json.toString()
                                ))
                                .build()
                            client.newCall(request).execute().use { response ->
                                if (response.isSuccessful) {
                                    android.util.Log.d("FCM", "FCM token saved to Supabase")
                                } else {
                                    android.util.Log.e("FCM", "Failed to save FCM token: ${response.code}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("FCM", "Error saving FCM token to Supabase", e)
                    }
                }
            }
        }
    }

    val journal by logVm.journal.collectAsState()
    val attentionCount = remember(journal) { journal.count { needsAttention(it) } }

    // Handle deep link navigation from notification tap
    LaunchedEffect(pendingNavigationRoute.value, token) {
        val route = pendingNavigationRoute.value ?: return@LaunchedEffect
        // Wait until user is authenticated before navigating
        if (!token.isNullOrBlank()) {
            nav.navigate(route) { launchSingleTop = true }
            pendingNavigationRoute.value = null
        }
    }

    data class DrawerItem(val title: String, val route: String, val icon: ImageVector)

    val drawerItems = listOf(
        DrawerItem("Profile", Routes.PROFILE, Icons.Outlined.Person),
        DrawerItem("Connections", Routes.THIRD_PARTY_CONNECTIONS, Icons.Outlined.Link),
        DrawerItem("Data", Routes.DATA, Icons.Outlined.Storage),
        DrawerItem("Risk Model", Routes.RISK_WEIGHTS, Icons.Outlined.Speed),
        DrawerItem("Manage Items", Routes.MANAGE_ITEMS, Icons.Outlined.Tune),
        DrawerItem("Logout", Routes.LOGOUT, Icons.AutoMirrored.Outlined.Logout)
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                )
                drawerItems.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(item.title) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            nav.navigate(item.route) { launchSingleTop = true }
                        },
                        icon = { Icon(item.icon, contentDescription = item.title) }
                    )
                }
            }
        }
    ) {
        val backStack by nav.currentBackStackEntryAsState()
        val current = backStack?.destination?.route ?: Routes.LOGIN

        // Tour hint state for nav bar pulse animations
        val topBarTourState by TourManager.state.collectAsState()
        val topBarTourHint = if (topBarTourState.active && topBarTourState.phase == CoachPhase.TOUR)
            tourSteps.getOrNull(topBarTourState.stepIndex)?.navHint else null
        val navPulseTransition = rememberInfiniteTransition(label = "topBarPulse")
        val topBarPulseScale by navPulseTransition.animateFloat(
            1f, 1.6f,
            infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "topPulseScale"
        )
        val topBarPulseAlpha by navPulseTransition.animateFloat(
            0.6f, 0f,
            infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "topPulseAlpha"
        )
        
        // Updated to include JOURNAL and Migraine screens
        val showHomeBackground =
            current == Routes.HOME || 
            current == Routes.INSIGHTS || 
            current == Routes.INSIGHTS_DETAIL ||
            current == Routes.INSIGHTS_REPORT ||
            current?.startsWith(Routes.INSIGHTS_BREAKDOWN) == true ||
            current == Routes.JOURNAL ||
            current == Routes.MIGRAINE ||
            current == Routes.QUICK_LOG_TRIGGER ||
            current == Routes.QUICK_LOG_MEDICINE ||
            current == Routes.QUICK_LOG_RELIEF ||
            current == Routes.QUICK_LOG_ACTIVITY ||
            current == Routes.QUICK_LOG_PRODROME ||
            current == Routes.QUICK_LOG_MIGRAINE ||
            current == Routes.LOG_MIGRAINE ||
            current == Routes.PAIN_LOCATION ||
            current == Routes.TRIGGERS ||
            current == Routes.MEDICINES ||
            current == Routes.RELIEFS ||
            current == Routes.NOTES ||
            current == Routes.REVIEW ||
            current == Routes.TIMING ||
            current == Routes.MANAGE_SYMPTOMS ||
            current == Routes.MANAGE_ITEMS ||
            current == Routes.MANAGE_TRIGGERS ||
            current == Routes.MANAGE_MEDICINES ||
            current == Routes.MANAGE_RELIEFS ||
            current == Routes.MANAGE_PRODROMES ||
            current == Routes.LOCATIONS ||
            current == Routes.ACTIVITIES ||
            current == Routes.MANAGE_LOCATIONS ||
            current == Routes.MANAGE_ACTIVITIES ||
            current == Routes.MISSED_ACTIVITIES ||
            current == Routes.MANAGE_MISSED_ACTIVITIES ||
            current == Routes.MONITOR ||
            current == Routes.MONITOR_CONFIG ||
            current == Routes.MONITOR_NUTRITION ||
            current == Routes.NUTRITION_CONFIG ||
            current == Routes.NUTRITION_HISTORY ||
            current == Routes.WEATHER_CONFIG ||
            current == Routes.SLEEP_DATA_HISTORY ||
            current == Routes.ENV_DATA_HISTORY ||
            current == Routes.MONITOR_PHYSICAL ||
            current == Routes.PHYSICAL_CONFIG ||
            current == Routes.PHYSICAL_DATA_HISTORY ||
            current == Routes.FULL_GRAPH_PHYSICAL ||
            current == Routes.MONITOR_SLEEP ||
            current == Routes.SLEEP_CONFIG ||
            current == Routes.FULL_GRAPH_SLEEP ||
            current == Routes.FULL_GRAPH_WEATHER ||
            current == Routes.FULL_GRAPH_NUTRITION ||
            current == Routes.MONITOR_MENTAL ||
            current == Routes.MENTAL_CONFIG ||
            current == Routes.MENTAL_DATA_HISTORY ||
            current == Routes.FULL_GRAPH_MENTAL ||
            current == Routes.MONITOR_ENVIRONMENT ||
            current == Routes.THIRD_PARTY_CONNECTIONS ||
            current == Routes.PROFILE ||
            current == Routes.DATA ||
            current == Routes.RISK_WEIGHTS ||
            current == Routes.RISK_DETAIL ||
            current == Routes.CHANGE_PASSWORD ||
            current == Routes.MENSTRUATION_SETTINGS ||
            current == Routes.EVENING_CHECKIN ||
            current == Routes.COMMUNITY ||
            current?.startsWith(Routes.ARTICLE_DETAIL) == true ||
            current?.startsWith(Routes.FORUM_POST_DETAIL) == true ||
            current == Routes.PAYWALL

        // Wizard fullscreen: hide top bar + bottom nav for immersive logging
        val isWizardFullscreen = current in setOf(
            Routes.LOGIN, Routes.SIGNUP, Routes.LOGOUT,
            Routes.LOG_MIGRAINE, Routes.TIMING, Routes.PAIN_LOCATION,
            Routes.TRIGGERS, Routes.MEDICINES,
            Routes.RELIEFS, Routes.LOCATIONS, Routes.ACTIVITIES, Routes.MISSED_ACTIVITIES,
            Routes.NOTES, Routes.REVIEW,
            Routes.MANAGE_SYMPTOMS,
            Routes.MANAGE_TRIGGERS, Routes.MANAGE_MEDICINES, Routes.MANAGE_RELIEFS, Routes.MANAGE_PRODROMES,
            Routes.MANAGE_LOCATIONS, Routes.MANAGE_ACTIVITIES, Routes.MANAGE_MISSED_ACTIVITIES,
            Routes.ONBOARDING, Routes.AI_SETUP, "${Routes.ONBOARDING}/setup",
            Routes.EVENING_CHECKIN, "backfill_loading", "subscribe",
            Routes.PAYWALL
        )

        Box(modifier = Modifier.fillMaxSize()) {
            when (current) {
                Routes.HOME, Routes.PAYWALL -> {
                    Image(
                        painter = painterResource(R.drawable.purple_sky_bg),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(AppTheme.FadeColor)
                    )
                }
            }

            Scaffold(
                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                containerColor = Color.Transparent,
                topBar = {
                    if (!isWizardFullscreen) {
                    CenterAlignedTopAppBar(
                        scrollBehavior = scrollBehavior,
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = Color(0xFF2A003D),
                            titleContentColor = Color.White,
                            navigationIconContentColor = Color.White,
                            actionIconContentColor = Color.White
                        ),
                        title = {
                            Text(
                                when (current) {
                                    Routes.MONITOR -> "Monitor"
                                    Routes.MONITOR_CONFIG -> "Configure Monitor"
                                    Routes.INSIGHTS -> "Insights"
                                    Routes.INSIGHTS_DETAIL -> "Insights"
                                    Routes.INSIGHTS_REPORT -> "Insights"
                                    "${Routes.INSIGHTS_BREAKDOWN}/{logType}" -> "Insights"
                                    Routes.HOME -> "Home"
                                    Routes.MIGRAINE -> "Log"
                                    Routes.LOG_MIGRAINE -> "Log Migraine"
                                    Routes.PAIN_LOCATION -> "Pain Location"
                                    Routes.QUICK_LOG_TRIGGER -> "Log Trigger"
                                    Routes.QUICK_LOG_MEDICINE -> "Log Medicine"
                                    Routes.QUICK_LOG_RELIEF -> "Log Relief"
                                    Routes.QUICK_LOG_ACTIVITY -> "Log Activity"
                                    Routes.QUICK_LOG_PRODROME -> "Log Prodrome"
                                    Routes.QUICK_LOG_MIGRAINE -> "Quick Migraine"
                                    Routes.MONITOR_NUTRITION -> "Nutrition"
                                    Routes.NUTRITION_CONFIG -> "Customize Nutrition"
                                    Routes.NUTRITION_HISTORY -> "Nutrition History"
                                    Routes.MONITOR_ENVIRONMENT -> "Environment"
                                    Routes.WEATHER_CONFIG -> "Customize Environment"
                                    Routes.SLEEP_DATA_HISTORY -> "Sleep Data"
                                    Routes.ENV_DATA_HISTORY -> "Environment Data"
                                    Routes.SLEEP_CONFIG -> "Customize Sleep"
                                    Routes.FULL_GRAPH_SLEEP -> "Sleep History"
                                    Routes.FULL_GRAPH_WEATHER -> "Environment History"
                                    Routes.FULL_GRAPH_NUTRITION -> "Nutrition History"
                                    Routes.MONITOR_PHYSICAL -> "Physical Health"
                                    Routes.MONITOR_SLEEP -> "Sleep"
                                    Routes.MONITOR_MENTAL -> "Mental Health"
                                    Routes.COMMUNITY -> "Community"
                                    Routes.JOURNAL -> "Journal"
                                    Routes.LOGIN -> "Sign in"
                                    Routes.SIGNUP -> "Create account"
                                    Routes.PROFILE -> "Profile"
                                    Routes.DATA -> "Data"
                                    Routes.MENSTRUATION_SETTINGS -> "Menstruation Settings"
                                    Routes.LOGOUT -> "Logout"
                                    Routes.MEDICINES -> "Medicines"
                                    Routes.ADJUST_MEDICINES -> "Adjust Medicines"
                                    Routes.RELIEFS -> "Reliefs"
                                    Routes.NOTES -> "Notes"
                                    Routes.ADJUST_RELIEFS -> "Adjust Reliefs"
                                    Routes.TRIGGERS -> "Triggers"
                                    Routes.ADJUST_TRIGGERS -> "Adjust Triggers"
                                    Routes.REVIEW -> "Review Log"
                                    Routes.EDIT_MIGRAINE -> "Edit Migraine"
                                    Routes.EDIT_TRIGGER -> "Edit Trigger"
                                    Routes.EDIT_MEDICINE -> "Edit Medicine"
                                    Routes.EDIT_RELIEF -> "Edit Relief"
                                    Routes.EDIT_PRODROME -> "Edit Prodrome"
                                    Routes.EDIT_ACTIVITY -> "Edit Activity"
                                    Routes.EDIT_LOCATION -> "Edit Location"
                                    Routes.ADJUST_MIGRAINES -> "Adjust Migraines"
                                    Routes.MANAGE_SYMPTOMS -> "Manage Symptoms"
                                    Routes.MANAGE_ITEMS -> "Manage Items"
                                    Routes.MANAGE_TRIGGERS -> "Manage Triggers"
                                    Routes.MANAGE_MEDICINES -> "Manage Medicines"
                                    Routes.MANAGE_RELIEFS -> "Manage Reliefs"
                                    Routes.MANAGE_PRODROMES -> "Manage Prodromes"
                                    Routes.TIMING -> "Timing"
                                    Routes.THIRD_PARTY_CONNECTIONS -> "Connections"
                                    Routes.CHANGE_PASSWORD -> "Change password"
                                    Routes.RISK_WEIGHTS -> "Risk Model"
                                    Routes.RISK_DETAIL -> "Risk Detail"
                                    else -> ""
                                }
                            )
                        },
                        navigationIcon = {
                            if (current != Routes.LOGIN && current != Routes.SIGNUP) {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (topBarTourHint == NavHintLocation.TOP_SETTINGS) {
                                            Box(
                                                Modifier
                                                    .size((24 * topBarPulseScale).dp)
                                                    .background(AppTheme.AccentPink.copy(alpha = topBarPulseAlpha), CircleShape)
                                            )
                                        }
                                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                                    }
                                }
                            }
                        },
                        actions = {
                            if (current != Routes.LOGIN && current != Routes.SIGNUP) {
                                IconButton(onClick = { nav.navigate(Routes.COMMUNITY) }) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (topBarTourHint == NavHintLocation.TOP_COMMUNITY) {
                                            Box(
                                                Modifier
                                                    .size((24 * topBarPulseScale).dp)
                                                    .background(AppTheme.AccentPink.copy(alpha = topBarPulseAlpha), CircleShape)
                                            )
                                        }
                                        if (communityUnreadCount > 0) {
                                            BadgedBox(badge = { Badge { Text(communityUnreadCount.toString()) } }) {
                                                Icon(Icons.Outlined.Groups, contentDescription = "Community")
                                            }
                                        } else {
                                            Icon(Icons.Outlined.Groups, contentDescription = "Community")
                                        }
                                    }
                                }
                            }
                        }
                    )
                    } // end !isWizardFullscreen
                },
                bottomBar = {
                    if (current != Routes.LOGIN && current != Routes.SIGNUP && !isWizardFullscreen) {
                        BottomBar(nav, attentionCount)
                    }
                }
            ) { inner ->
                Box(modifier = Modifier.fillMaxSize().padding(inner)) {
                NavHost(
                    navController = nav,
                    startDestination = Routes.LOGIN,
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    composable(Routes.MONITOR) { MonitorScreen(navController = nav, authVm = authVm) }
                    composable(Routes.MONITOR_CONFIG) { MonitorConfigScreen(onBack = { nav.popBackStack() }) }
                    
                    // Monitor detail screens (placeholders for now)
                    composable(Routes.MONITOR_NUTRITION) { MonitorNutritionScreen(navController = nav, authVm = authVm) }
                    composable(Routes.NUTRITION_CONFIG) { NutritionConfigScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.NUTRITION_HISTORY) {
                        val pState by PremiumManager.state.collectAsState()
                        if (!pState.isLoading && !pState.isPremium) {
                            LaunchedEffect(Unit) { nav.navigate(Routes.PAYWALL) { popUpTo(Routes.MONITOR) } }
                        } else { NutritionHistoryScreen(onBack = { nav.popBackStack() }) }
                    }
                    composable(Routes.WEATHER_CONFIG) { WeatherConfigScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.SLEEP_DATA_HISTORY) {
                        val pState by PremiumManager.state.collectAsState()
                        if (!pState.isLoading && !pState.isPremium) {
                            LaunchedEffect(Unit) { nav.navigate(Routes.PAYWALL) { popUpTo(Routes.MONITOR) } }
                        } else { SleepDataHistoryScreen(onBack = { nav.popBackStack() }) }
                    }
                    composable(Routes.ENV_DATA_HISTORY) {
                        val pState by PremiumManager.state.collectAsState()
                        if (!pState.isLoading && !pState.isPremium) {
                            LaunchedEffect(Unit) { nav.navigate(Routes.PAYWALL) { popUpTo(Routes.MONITOR) } }
                        } else { EnvironmentDataHistoryScreen(onBack = { nav.popBackStack() }) }
                    }
                    composable(Routes.MONITOR_PHYSICAL) { MonitorPhysicalScreen(navController = nav, authVm = authVm) }
                    composable(Routes.PHYSICAL_CONFIG) { PhysicalConfigScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.PHYSICAL_DATA_HISTORY) {
                        val pState by PremiumManager.state.collectAsState()
                        if (!pState.isLoading && !pState.isPremium) {
                            LaunchedEffect(Unit) { nav.navigate(Routes.PAYWALL) { popUpTo(Routes.MONITOR) } }
                        } else { PhysicalDataHistoryScreen(onBack = { nav.popBackStack() }) }
                    }
                    composable(Routes.FULL_GRAPH_PHYSICAL) {
                        val pState by PremiumManager.state.collectAsState()
                        if (!pState.isLoading && !pState.isPremium) {
                            LaunchedEffect(Unit) { nav.navigate(Routes.PAYWALL) { popUpTo(Routes.MONITOR) } }
                        } else { FullScreenGraphScreen(graphType = "physical", onBack = { nav.popBackStack() }) }
                    }
                    composable(Routes.MONITOR_SLEEP) { MonitorSleepScreen(navController = nav, authVm = authVm) }
                    composable(Routes.SLEEP_CONFIG) { SleepConfigScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.FULL_GRAPH_SLEEP) {
                        val pState by PremiumManager.state.collectAsState()
                        if (!pState.isLoading && !pState.isPremium) {
                            LaunchedEffect(Unit) { nav.navigate(Routes.PAYWALL) { popUpTo(Routes.MONITOR) } }
                        } else { FullScreenGraphScreen(graphType = "sleep", onBack = { nav.popBackStack() }) }
                    }
                    composable(Routes.FULL_GRAPH_WEATHER) {
                        val pState by PremiumManager.state.collectAsState()
                        if (!pState.isLoading && !pState.isPremium) {
                            LaunchedEffect(Unit) { nav.navigate(Routes.PAYWALL) { popUpTo(Routes.MONITOR) } }
                        } else { FullScreenGraphScreen(graphType = "weather", onBack = { nav.popBackStack() }) }
                    }
                    composable(Routes.FULL_GRAPH_NUTRITION) {
                        val pState by PremiumManager.state.collectAsState()
                        if (!pState.isLoading && !pState.isPremium) {
                            LaunchedEffect(Unit) { nav.navigate(Routes.PAYWALL) { popUpTo(Routes.MONITOR) } }
                        } else { FullScreenGraphScreen(graphType = "nutrition", onBack = { nav.popBackStack() }) }
                    }
                    composable(Routes.MONITOR_MENTAL) { MonitorMentalScreen(navController = nav, authVm = authVm) }
                    composable(Routes.MENTAL_CONFIG) { MentalConfigScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.MENTAL_DATA_HISTORY) {
                        val pState by PremiumManager.state.collectAsState()
                        if (!pState.isLoading && !pState.isPremium) {
                            LaunchedEffect(Unit) { nav.navigate(Routes.PAYWALL) { popUpTo(Routes.MONITOR) } }
                        } else { MentalDataHistoryScreen(onBack = { nav.popBackStack() }) }
                    }
                    composable(Routes.FULL_GRAPH_MENTAL) {
                        val pState by PremiumManager.state.collectAsState()
                        if (!pState.isLoading && !pState.isPremium) {
                            LaunchedEffect(Unit) { nav.navigate(Routes.PAYWALL) { popUpTo(Routes.MONITOR) } }
                        } else { FullScreenGraphScreen(graphType = "mental", onBack = { nav.popBackStack() }) }
                    }
                    composable(Routes.MONITOR_ENVIRONMENT) { MonitorEnvironmentScreen(navController = nav, authVm = authVm) }
                    
                    composable(Routes.INSIGHTS) {
                        val owner = androidx.compose.ui.platform.LocalContext.current as androidx.lifecycle.ViewModelStoreOwner
                        val insightsVm: InsightsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(owner)
                        InsightsScreen(navController = nav, vm = insightsVm)
                    }
                    composable(Routes.INSIGHTS_DETAIL) {
                        val pState by PremiumManager.state.collectAsState()
                        if (!pState.isLoading && !pState.isPremium) {
                            LaunchedEffect(Unit) { nav.navigate(Routes.PAYWALL) { popUpTo(Routes.INSIGHTS) } }
                        } else {
                            val owner = androidx.compose.ui.platform.LocalContext.current as androidx.lifecycle.ViewModelStoreOwner
                            val insightsVm: InsightsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(owner)
                            InsightsDetailScreen(navController = nav, vm = insightsVm)
                        }
                    }
                    composable(Routes.INSIGHTS_REPORT) {
                        val pState by PremiumManager.state.collectAsState()
                        if (!pState.isLoading && !pState.isPremium) {
                            LaunchedEffect(Unit) { nav.navigate(Routes.PAYWALL) { popUpTo(Routes.INSIGHTS) } }
                        } else {
                            val owner = androidx.compose.ui.platform.LocalContext.current as androidx.lifecycle.ViewModelStoreOwner
                            val insightsVm: InsightsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(owner)
                            InsightsReportScreen(navController = nav, vm = insightsVm)
                        }
                    }
                    composable("${Routes.INSIGHTS_BREAKDOWN}/{logType}") { backStack ->
                        val pState by PremiumManager.state.collectAsState()
                        if (!pState.isLoading && !pState.isPremium) {
                            LaunchedEffect(Unit) { nav.navigate(Routes.PAYWALL) { popUpTo(Routes.INSIGHTS) } }
                        } else {
                            val logType = backStack.arguments?.getString("logType") ?: "Triggers"
                            val owner = androidx.compose.ui.platform.LocalContext.current as androidx.lifecycle.ViewModelStoreOwner
                            val insightsVm: InsightsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(owner)
                            InsightsBreakdownScreen(logType = logType, navController = nav, vm = insightsVm)
                        }
                    }
                    composable(Routes.TRIGGERS_SETTINGS) { TriggersSettingsScreen(navController = nav, authVm = authVm) }
                    composable(Routes.CUSTOMIZE_TRIGGERS) { CustomizeTriggersScreen() }
                    composable(Routes.HOME) {
                        HomeScreenRoot(
                            onLogout = { nav.navigate(Routes.LOGOUT) { launchSingleTop = true } },
                            onNavigateToMigraine = { nav.navigate(Routes.MIGRAINE) },
                            onNavigateToRiskDetail = { nav.navigate(Routes.RISK_DETAIL) },
                            onNavigateToRecalibrationReview = { nav.navigate(Routes.RECALIBRATION_REVIEW) },
                            onNavigateToPaywall = { nav.navigate(Routes.PAYWALL) },
                            authVm = authVm,
                            logVm = logVm,
                            vm = homeVm,
                            triggerVm = triggerVm,
                            medicineVm = medVm,
                            reliefVm = reliefVm,
                            symptomVm = symptomVm,
                        )
                    }
                    composable(Routes.COMMUNITY) {
                        CommunityScreen(authVm = authVm, navController = nav, vm = communityVm)
                    }
                    composable("${Routes.ARTICLE_DETAIL}/{articleId}") { backStack ->
                        val articleId = backStack.arguments?.getString("articleId") ?: return@composable
                        val authState by authVm.state.collectAsState()
                        val owner = androidx.compose.ui.platform.LocalContext.current as androidx.lifecycle.ViewModelStoreOwner
                        val insightsVm: InsightsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(owner)
                        ArticleDetailScreen(
                            articleId = articleId,
                            vm = communityVm,
                            accessToken = authState.accessToken,
                            currentUserId = authState.userId,
                            insightsVm = insightsVm,
                            onBack = { nav.popBackStack() }
                        )
                    }
                    composable("${Routes.FORUM_POST_DETAIL}/{postId}") { backStack ->
                        val postId = backStack.arguments?.getString("postId") ?: return@composable
                        val authState by authVm.state.collectAsState()
                        val owner = androidx.compose.ui.platform.LocalContext.current as androidx.lifecycle.ViewModelStoreOwner
                        val insightsVm: InsightsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(owner)
                        ForumPostDetailScreen(
                            postId = postId,
                            vm = communityVm,
                            accessToken = authState.accessToken,
                            currentUserId = authState.userId,
                            insightsVm = insightsVm,
                            onBack = { nav.popBackStack() }
                        )
                    }
                    composable(Routes.JOURNAL) { JournalScreen(navController = nav, authVm = authVm, vm = logVm) }

                    // Migraine Hub (main migraine tab)
                    composable(Routes.MIGRAINE) { MigraineHubScreen(navController = nav) }
                    
                    // Full migraine wizard flow
                    val wizardClose: () -> Unit = {
                        logVm.clearDraft()
                        nav.popBackStack(Routes.MIGRAINE, inclusive = false)
                    }
                    composable(Routes.LOG_MIGRAINE) { LogHomeScreen(navController = nav, authVm = authVm, vm = logVm, symptomVm = symptomVm, onClose = wizardClose) }
                    composable(Routes.TIMING) { TimingScreen(navController = nav, vm = logVm, onClose = wizardClose) }
                    composable(Routes.PAIN_LOCATION) { PainLocationScreen(navController = nav, vm = logVm, onClose = wizardClose) }
                    composable(Routes.PRODROMES_LOG) {
                        val prodromeVm: ProdromeViewModel = viewModel()
                        ProdromeLogScreen(navController = nav, vm = prodromeVm, authVm = authVm, logVm = logVm, onClose = wizardClose)
                    }

                    // Quick log screens (standalone)
                    composable(Routes.QUICK_LOG_TRIGGER) {
                        val triggerVm: TriggerViewModel = viewModel()
                        val quickLogVm: LogViewModel = viewModel()
                        val scope = rememberCoroutineScope()
                        var linkedMigraineId by remember { mutableStateOf<String?>(null) }
                        TriggersScreen(
                            navController = nav, vm = triggerVm, authVm = authVm, logVm = quickLogVm,
                            quickLogMode = true,
                            linkedMigraineId = linkedMigraineId,
                            onMigraineSelect = { linkedMigraineId = it },
                            onSave = {
                                scope.launch {
                                    val token = authVm.state.value.accessToken ?: return@launch
                                    val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                                    quickLogVm.draft.value.triggers.forEach { t ->
                                        runCatching { db.insertTrigger(token, linkedMigraineId, t.type, t.startAtIso ?: java.time.Instant.now().toString(), t.note) }
                                    }
                                    quickLogVm.clearDraft()
                                    nav.popBackStack()
                                }
                            }
                        )
                    }
                    composable(Routes.QUICK_LOG_MEDICINE) {
                        val medVm: MedicineViewModel = viewModel()
                        val quickLogVm: LogViewModel = viewModel()
                        val scope = rememberCoroutineScope()
                        var linkedMigraineId by remember { mutableStateOf<String?>(null) }
                        MedicinesScreen(
                            navController = nav, vm = medVm, authVm = authVm, logVm = quickLogVm,
                            quickLogMode = true,
                            linkedMigraineId = linkedMigraineId,
                            onMigraineSelect = { linkedMigraineId = it },
                            onSave = {
                                scope.launch {
                                    val token = authVm.state.value.accessToken ?: return@launch
                                    val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                                    quickLogVm.draft.value.meds.forEach { m ->
                                        runCatching { db.insertMedicine(token, linkedMigraineId, m.name, m.amount, m.startAtIso ?: java.time.Instant.now().toString(), m.notes) }
                                    }
                                    quickLogVm.clearDraft()
                                    nav.popBackStack()
                                }
                            }
                        )
                    }
                    composable(Routes.QUICK_LOG_RELIEF) {
                        val reliefVm: ReliefViewModel = viewModel()
                        val quickLogVm: LogViewModel = viewModel()
                        val scope = rememberCoroutineScope()
                        var linkedMigraineId by remember { mutableStateOf<String?>(null) }
                        ReliefsScreen(
                            navController = nav, vm = reliefVm, authVm = authVm, logVm = quickLogVm,
                            quickLogMode = true,
                            linkedMigraineId = linkedMigraineId,
                            onMigraineSelect = { linkedMigraineId = it },
                            onSave = {
                                scope.launch {
                                    val token = authVm.state.value.accessToken ?: return@launch
                                    val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                                    quickLogVm.draft.value.rels.forEach { r ->
                                        runCatching { db.insertRelief(token, linkedMigraineId, r.type, r.startAtIso ?: java.time.Instant.now().toString(), r.endAtIso, r.notes, r.reliefScale) }
                                    }
                                    quickLogVm.clearDraft()
                                    nav.popBackStack()
                                }
                            }
                        )
                    }
                    composable(Routes.QUICK_LOG_ACTIVITY) {
                        val activityVm: ActivityViewModel = viewModel()
                        val quickLogVm: LogViewModel = viewModel()
                        val scope = rememberCoroutineScope()
                        var linkedMigraineId by remember { mutableStateOf<String?>(null) }
                        ActivitiesScreen(
                            navController = nav, vm = activityVm, authVm = authVm, logVm = quickLogVm,
                            quickLogMode = true,
                            linkedMigraineId = linkedMigraineId,
                            onMigraineSelect = { linkedMigraineId = it },
                            onSave = {
                                scope.launch {
                                    val token = authVm.state.value.accessToken ?: return@launch
                                    val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                                    quickLogVm.draft.value.activities.forEach { a ->
                                        runCatching { db.insertActivity(token, linkedMigraineId, a.type, a.startAtIso ?: java.time.Instant.now().toString(), a.endAtIso, a.note) }
                                    }
                                    quickLogVm.clearDraft()
                                    nav.popBackStack()
                                }
                            }
                        )
                    }
                    composable(Routes.QUICK_LOG_PRODROME) {
                        val prodromeVm: ProdromeViewModel = viewModel()
                        val quickLogVm: LogViewModel = viewModel()
                        val scope = rememberCoroutineScope()
                        var linkedMigraineId by remember { mutableStateOf<String?>(null) }
                        ProdromeLogScreen(
                            navController = nav, vm = prodromeVm, authVm = authVm, logVm = quickLogVm,
                            quickLogMode = true,
                            linkedMigraineId = linkedMigraineId,
                            onMigraineSelect = { linkedMigraineId = it },
                            onSave = {
                                scope.launch {
                                    val token = authVm.state.value.accessToken ?: return@launch
                                    val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                                    quickLogVm.draft.value.prodromes.forEach { p ->
                                        runCatching { db.insertProdrome(token, linkedMigraineId, p.type, p.startAtIso, p.note) }
                                    }
                                    quickLogVm.clearDraft()
                                    nav.popBackStack()
                                }
                            }
                        )
                    }

                    composable(Routes.QUICK_LOG_MIGRAINE) {
                        val symptomVmLocal: SymptomViewModel = viewModel()
                        QuickMigraineScreen(
                            navController = nav, authVm = authVm, symptomVm = symptomVmLocal,
                            onClose = { nav.popBackStack() }
                        )
                    }

                    composable(Routes.TRIGGERS) {
                        TriggersScreen(navController = nav, vm = triggerVm, authVm = authVm, logVm = logVm, onClose = wizardClose)
                    }
                    composable(Routes.ADJUST_TRIGGERS) {
                        AdjustTriggersScreen(navController = nav, vm = triggerVm, authVm = authVm)
                    }

                    composable(Routes.MEDICINES) {
                        MedicinesScreen(navController = nav, vm = medVm, authVm = authVm, logVm = logVm, onClose = wizardClose)
                    }
                    composable(Routes.ADJUST_MEDICINES) {
                        AdjustMedicinesScreen(navController = nav, vm = medVm, authVm = authVm)
                    }

                    composable(Routes.RELIEFS) {
                        ReliefsScreen(navController = nav, vm = reliefVm, authVm = authVm, logVm = logVm, onClose = wizardClose)
                    }
                    composable(Routes.ADJUST_RELIEFS) {
                        AdjustReliefsScreen(navController = nav, vm = reliefVm, authVm = authVm)
                    }

                    composable(Routes.LOCATIONS) {
                        val locationVm: LocationViewModel = viewModel()
                        LocationsScreen(navController = nav, vm = locationVm, authVm = authVm, logVm = logVm, onClose = wizardClose)
                    }
                    composable(Routes.ACTIVITIES) {
                        val activityVm: ActivityViewModel = viewModel()
                        ActivitiesScreen(navController = nav, vm = activityVm, authVm = authVm, logVm = logVm, onClose = wizardClose)
                    }
                    composable(Routes.MISSED_ACTIVITIES) {
                        val missedVm: MissedActivityViewModel = viewModel()
                        MissedActivitiesScreen(navController = nav, vm = missedVm, authVm = authVm, logVm = logVm, onClose = wizardClose)
                    }

                    composable(Routes.NOTES) { NotesScreen(navController = nav, vm = logVm, onClose = wizardClose) }
                    composable(Routes.REVIEW) { ReviewLogScreen(navController = nav, authVm = authVm, vm = logVm, onClose = wizardClose) }

                    composable("${Routes.EDIT_MIGRAINE}/{id}") {
                        val id = it.arguments?.getString("id") ?: return@composable
                        val authState by authVm.state.collectAsState()
                        var prefilling by remember { mutableStateOf(true) }
                        LaunchedEffect(id) {
                            val token = authState.accessToken ?: return@LaunchedEffect
                            logVm.prefillForEdit(token, id) {
                                prefilling = false
                            }
                        }
                        if (!prefilling) {
                            // Navigate to wizard, replacing this route
                            LaunchedEffect(Unit) {
                                nav.navigate(Routes.LOG_MIGRAINE) {
                                    popUpTo("${Routes.EDIT_MIGRAINE}/{id}") { inclusive = true }
                                }
                            }
                        } else {
                            // Loading indicator
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = AppTheme.AccentPurple)
                            }
                        }
                    }
                    composable("${Routes.EDIT_TRIGGER}/{id}") {
                        val id = it.arguments?.getString("id") ?: return@composable
                        JournalEditScreen(itemType = "trigger", itemId = id, authVm = authVm, onBack = { nav.popBackStack() })
                    }
                    composable("${Routes.EDIT_MEDICINE}/{id}") {
                        val id = it.arguments?.getString("id") ?: return@composable
                        JournalEditScreen(itemType = "medicine", itemId = id, authVm = authVm, onBack = { nav.popBackStack() })
                    }
                    composable("${Routes.EDIT_RELIEF}/{id}") {
                        val id = it.arguments?.getString("id") ?: return@composable
                        JournalEditScreen(itemType = "relief", itemId = id, authVm = authVm, onBack = { nav.popBackStack() })
                    }
                    composable("${Routes.EDIT_PRODROME}/{id}") {
                        val id = it.arguments?.getString("id") ?: return@composable
                        JournalEditScreen(itemType = "prodrome", itemId = id, authVm = authVm, onBack = { nav.popBackStack() })
                    }
                    composable("${Routes.EDIT_ACTIVITY}/{id}") {
                        val id = it.arguments?.getString("id") ?: return@composable
                        JournalEditScreen(itemType = "activity", itemId = id, authVm = authVm, onBack = { nav.popBackStack() })
                    }
                    composable("${Routes.EDIT_LOCATION}/{id}") {
                        val id = it.arguments?.getString("id") ?: return@composable
                        JournalEditScreen(itemType = "location", itemId = id, authVm = authVm, onBack = { nav.popBackStack() })
                    }

                    composable(Routes.ADJUST_MIGRAINES) {
                        AdjustMigrainesScreen(navController = nav, vm = migraineVm, authVm = authVm)
                    }
                    composable(Routes.MANAGE_SYMPTOMS) {
                        ManageSymptomsScreen(navController = nav, vm = symptomVm, authVm = authVm)
                    }
                    composable(Routes.MANAGE_ITEMS) {
                        ManageItemsScreen(navController = nav)
                    }
                    composable(Routes.MANAGE_TRIGGERS) {
                        val triggerVm: TriggerViewModel = viewModel()
                        val authState by authVm.state.collectAsState()
                        val pool by triggerVm.pool.collectAsState()
                        val frequent by triggerVm.frequent.collectAsState()
                        val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
                        val edge = remember { EdgeFunctionsService() }
                        val scope = rememberCoroutineScope()

                        // Trigger automation settings: label -> full setting (enabled + threshold)
                        var triggerSettings by remember { mutableStateOf<Map<String, EdgeFunctionsService.TriggerSettingResponse>>(emptyMap()) }

                        // Build automatableMap dynamically from pool:
                        // any trigger with direction != null is automatable
                        // label is used as the key in trigger_settings
                        val automatableMap = remember(pool) {
                            pool.filter { it.direction != null }
                                .associate { it.label to it.label }
                        }

                        LaunchedEffect(authState.accessToken) {
                            authState.accessToken?.let { triggerVm.loadAll(it) }
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching { edge.getTriggerSettings(ctx) }
                                    .onSuccess { list ->
                                        triggerSettings = list.associate { it.triggerType to it }
                                    }
                            }
                        }

                        val frequentIds = remember(frequent) { frequent.map { it.triggerId }.toSet() }
                        val items = remember(pool, frequentIds, triggerSettings) {
                            pool.map { row ->
                                val autoKey = automatableMap[row.label]
                                val setting = autoKey?.let { triggerSettings[it] }
                                PoolItem(
                                    id = row.id,
                                    label = row.label,
                                    iconKey = row.iconKey,
                                    category = row.category,
                                    isFavorite = row.id in frequentIds,
                                    prediction = PredictionValue.fromString(row.predictionValue),
                                    isAutomatable = autoKey != null,
                                    isAutomated = setting?.enabled ?: row.enabledByDefault,
                                    threshold = setting?.threshold,
                                    defaultThreshold = row.defaultThreshold,
                                    unit = row.unit,
                                    direction = row.direction,
                                    displayGroup = row.displayGroup
                                )
                            }
                        }

                        ManagePoolScreen(
                            navController = nav,
                            config = PoolConfig(
                                title = "Triggers",
                                subtitle = "Add, star, or remove triggers",
                                iconColor = Color(0xFFFFB74D),
                                drawHeroIcon = { HubIcons.run { drawTriggerBolt(it) } },
                                items = items,
                                showPrediction = true,
                                categories = listOf("Body", "Cognitive", "Diet", "Environment", "Menstrual Cycle", "Physical", "Sleep"),
                                iconResolver = { key -> TriggerIcons.forKey(key) },
                                pickerIcons = TriggerIcons.ALL_ICONS.map { PickerIconEntry(it.key, it.label, it.icon) },
                                onAdd = { label, category, prediction ->
                                    authState.accessToken?.let { triggerVm.addNewToPool(it, label, category, prediction.name) }
                                },
                                onDelete = { id -> authState.accessToken?.let { triggerVm.removeFromPool(it, id) } },
                                onToggleFavorite = { id, starred ->
                                    val token = authState.accessToken ?: return@PoolConfig
                                    if (starred) triggerVm.addToFrequent(token, id)
                                    else {
                                        val pref = frequent.find { it.triggerId == id }
                                        pref?.let { triggerVm.removeFromFrequent(token, it.id) }
                                    }
                                },
                                onSetPrediction = { id, pv ->
                                    authState.accessToken?.let { triggerVm.setPrediction(it, id, pv.name) }
                                },
                                onToggleAutomation = { id, enabled ->
                                    val label = pool.find { it.id == id }?.label ?: return@PoolConfig
                                    val autoKey = automatableMap[label] ?: return@PoolConfig
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        val ok = edge.upsertTriggerSetting(ctx, autoKey, enabled)
                                        if (ok) {
                                            runCatching { edge.getTriggerSettings(ctx) }
                                                .onSuccess { list ->
                                                    triggerSettings = list.associate { it.triggerType to it }
                                                }
                                        }
                                    }
                                },
                                onThresholdChange = { id, threshold ->
                                    val label = pool.find { it.id == id }?.label ?: return@PoolConfig
                                    val autoKey = automatableMap[label] ?: return@PoolConfig
                                    val currentEnabled = triggerSettings[autoKey]?.enabled
                                        ?: pool.find { it.id == id }?.enabledByDefault ?: true
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        val ok = edge.upsertTriggerSetting(ctx, autoKey, currentEnabled, threshold)
                                        if (ok) {
                                            runCatching { edge.getTriggerSettings(ctx) }
                                                .onSuccess { list ->
                                                    triggerSettings = list.associate { it.triggerType to it }
                                                }
                                        }
                                    }
                                },
                                onSetCategory = { id, category ->
                                    authState.accessToken?.let { triggerVm.setCategory(it, id, category) }
                                },
                                onSave = {
                                    edge.triggerRecalc(ctx)
                                    authState.accessToken?.let { triggerVm.loadAll(it) }
                                }
                            )
                        )
                    }
                    composable(Routes.MANAGE_MEDICINES) {
                        val medicineVm: MedicineViewModel = viewModel()
                        val authState by authVm.state.collectAsState()
                        val pool by medicineVm.pool.collectAsState()
                        val frequent by medicineVm.frequent.collectAsState()

                        LaunchedEffect(authState.accessToken) {
                            authState.accessToken?.let { medicineVm.loadAll(it) }
                        }

                        val frequentIds = remember(frequent) { frequent.map { it.medicineId }.toSet() }
                        val items = remember(pool, frequentIds) {
                            pool.map { row ->
                                PoolItem(
                                    id = row.id,
                                    label = row.label,
                                    iconKey = row.category,
                                    category = row.category,
                                    isFavorite = row.id in frequentIds
                                )
                            }
                        }

                        ManagePoolScreen(
                            navController = nav,
                            config = PoolConfig(
                                title = "Medicines",
                                subtitle = "Add, star, or remove medicines",
                                iconColor = Color(0xFF4FC3F7),
                                drawHeroIcon = { HubIcons.run { drawMedicinePill(it) } },
                                items = items,
                                categories = listOf("Analgesic", "Anti-Nausea", "CGRP", "Preventive", "Supplement", "Triptan", "Other"),
                                iconResolver = { key -> MedicineIcons.forKey(key) },
                                pickerIcons = MedicineIcons.ALL_ICONS.map { PickerIconEntry(it.key, it.label, it.icon) },
                                onAdd = { label, category, _ ->
                                    authState.accessToken?.let { medicineVm.addNewToPool(it, label, category) }
                                },
                                onDelete = { id -> authState.accessToken?.let { medicineVm.removeFromPool(it, id) } },
                                onToggleFavorite = { id, starred ->
                                    val token = authState.accessToken ?: return@PoolConfig
                                    if (starred) medicineVm.addToFrequent(token, id)
                                    else {
                                        val pref = frequent.find { it.medicineId == id }
                                        pref?.let { medicineVm.removeFromFrequent(token, it.id) }
                                    }
                                },
                                onSetCategory = { id, category ->
                                    authState.accessToken?.let { medicineVm.setCategory(it, id, category) }
                                }
                            )
                        )
                    }
                    composable(Routes.MANAGE_RELIEFS) {
                        val reliefVm: ReliefViewModel = viewModel()
                        val authState by authVm.state.collectAsState()
                        val pool by reliefVm.pool.collectAsState()
                        val frequent by reliefVm.frequent.collectAsState()

                        LaunchedEffect(authState.accessToken) {
                            authState.accessToken?.let { reliefVm.loadAll(it) }
                        }

                        val frequentIds = remember(frequent) { frequent.map { it.reliefId }.toSet() }
                        val items = remember(pool, frequentIds) {
                            pool.map { row ->
                                PoolItem(
                                    id = row.id,
                                    label = row.label,
                                    iconKey = row.iconKey,
                                    category = row.category,
                                    isFavorite = row.id in frequentIds,
                                    isAutomatable = row.isAutomatable,
                                    isAutomated = row.isAutomated
                                )
                            }
                        }

                        ManagePoolScreen(
                            navController = nav,
                            config = PoolConfig(
                                title = "Reliefs",
                                subtitle = "Add, star, or remove reliefs",
                                iconColor = Color(0xFF81C784),
                                drawHeroIcon = { HubIcons.run { drawReliefLeaf(it) } },
                                items = items,
                                categories = listOf("Breathing", "Cold/Heat", "Darkness", "Hydration", "Massage", "Meditation", "Movement", "Rest", "Supplement", "Other"),
                                iconResolver = { key -> ReliefIcons.forKey(key) },
                                pickerIcons = ReliefIcons.ALL_ICONS.map { PickerIconEntry(it.key, it.label, it.icon) },
                                onAdd = { label, category, _ ->
                                    authState.accessToken?.let { reliefVm.addNewToPool(it, label, category) }
                                },
                                onDelete = { id -> authState.accessToken?.let { reliefVm.removeFromPool(it, id) } },
                                onToggleFavorite = { id, starred ->
                                    val token = authState.accessToken ?: return@PoolConfig
                                    if (starred) reliefVm.addToFrequent(token, id)
                                    else {
                                        val pref = frequent.find { it.reliefId == id }
                                        pref?.let { reliefVm.removeFromFrequent(token, it.id) }
                                    }
                                },
                                onSetCategory = { id, category ->
                                    authState.accessToken?.let { reliefVm.setCategory(it, id, category) }
                                },
                                onToggleAutomation = { id, enabled ->
                                    authState.accessToken?.let { reliefVm.setAutomation(it, id, enabled) }
                                }
                            )
                        )
                    }

                    composable(Routes.MANAGE_PRODROMES) {
                        val prodromeVm: ProdromeViewModel = viewModel()
                        val authState by authVm.state.collectAsState()
                        val pool by prodromeVm.pool.collectAsState()
                        val frequent by prodromeVm.frequent.collectAsState()
                        val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
                        val edge = remember { EdgeFunctionsService() }
                        val scope = rememberCoroutineScope()

                        // Prodrome settings: prodromeType -> ProdromeSettingResponse (includes threshold)
                        var prodromeSettings by remember { mutableStateOf<Map<String, EdgeFunctionsService.ProdromeSettingResponse>>(emptyMap()) }

                        // Build automatable map dynamically from pool (direction != null = auto-detectable)
                        val automatableMap = remember(pool) {
                            pool.filter { it.direction != null }
                                .associate { it.label to it.label }
                        }

                        LaunchedEffect(authState.accessToken) {
                            authState.accessToken?.let { prodromeVm.loadAll(it) }
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching { edge.getProdromeSettings(ctx) }
                                    .onSuccess { list ->
                                        prodromeSettings = list.associate { it.prodromeType to it }
                                    }
                            }
                        }

                        val frequentIds = remember(frequent) { frequent.map { it.prodromeId }.toSet() }
                        val items = remember(pool, frequentIds, prodromeSettings) {
                            pool.map { row ->
                                val autoKey = automatableMap[row.label]
                                val setting = autoKey?.let { prodromeSettings[it] }
                                PoolItem(
                                    id = row.id,
                                    label = row.label,
                                    iconKey = row.iconKey,
                                    category = row.category,
                                    isFavorite = row.id in frequentIds,
                                    prediction = PredictionValue.fromString(row.predictionValue),
                                    isAutomatable = autoKey != null,
                                    isAutomated = setting?.enabled ?: row.enabledByDefault,
                                    threshold = setting?.threshold,
                                    defaultThreshold = row.defaultThreshold,
                                    unit = row.unit,
                                    direction = row.direction,
                                    displayGroup = row.displayGroup
                                )
                            }
                        }

                        ManagePoolScreen(
                            navController = nav,
                            config = PoolConfig(
                                title = "Prodromes",
                                subtitle = "Early warning signs before a migraine",
                                iconColor = Color(0xFFCE93D8),
                                drawHeroIcon = { HubIcons.run { drawProdromeEye(it) } },
                                items = items,
                                showPrediction = true,
                                categories = listOf("Autonomic", "Cognitive", "Digestive", "Mood", "Physical", "Sensitivity", "Sensory", "Sleep", "Speech", "Visual"),
                                iconResolver = { key -> ProdromeIcons.forKey(key) },
                                pickerIcons = ProdromeIcons.ALL_ICONS.map { PickerIconEntry(it.key, it.label, it.icon) },
                                onAdd = { label, category, prediction ->
                                    authState.accessToken?.let { prodromeVm.addNewToPool(it, label, category, prediction.name) }
                                },
                                onDelete = { id -> authState.accessToken?.let { prodromeVm.removeFromPool(it, id) } },
                                onToggleFavorite = { id, starred ->
                                    val token = authState.accessToken ?: return@PoolConfig
                                    if (starred) prodromeVm.addToFrequent(token, id)
                                    else {
                                        val pref = frequent.find { it.prodromeId == id }
                                        pref?.let { prodromeVm.removeFromFrequent(token, it.id) }
                                    }
                                },
                                onSetPrediction = { id, pv ->
                                    authState.accessToken?.let { prodromeVm.setPrediction(it, id, pv.name) }
                                },
                                onSetCategory = { id, category ->
                                    authState.accessToken?.let { prodromeVm.setCategory(it, id, category) }
                                },
                                onToggleAutomation = { id, enabled ->
                                    val label = pool.find { it.id == id }?.label ?: return@PoolConfig
                                    val autoKey = automatableMap[label] ?: return@PoolConfig
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        val ok = edge.upsertProdromeSetting(ctx, autoKey, enabled)
                                        if (ok) {
                                            runCatching { edge.getProdromeSettings(ctx) }
                                                .onSuccess { list ->
                                                    prodromeSettings = list.associate { it.prodromeType to it }
                                                }
                                        }
                                    }
                                },
                                onThresholdChange = { id, newThreshold ->
                                    val label = pool.find { it.id == id }?.label ?: return@PoolConfig
                                    val autoKey = automatableMap[label] ?: return@PoolConfig
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        edge.upsertProdromeSetting(ctx, autoKey, true, newThreshold)
                                        runCatching { edge.getProdromeSettings(ctx) }
                                            .onSuccess { list ->
                                                prodromeSettings = list.associate { it.prodromeType to it }
                                            }
                                    }
                                },
                                onSave = {
                                    edge.triggerRecalc(ctx)
                                    authState.accessToken?.let { prodromeVm.loadAll(it) }
                                }
                            )
                        )
                    }

                    composable(Routes.MANAGE_LOCATIONS) {
                        val locationVm: LocationViewModel = viewModel()
                        val authState by authVm.state.collectAsState()
                        val pool by locationVm.pool.collectAsState()
                        val frequent by locationVm.frequent.collectAsState()

                        LaunchedEffect(authState.accessToken) { authState.accessToken?.let { locationVm.loadAll(it) } }

                        val frequentIds = remember(frequent) { frequent.map { it.locationId }.toSet() }
                        val items = remember(pool, frequentIds) {
                            pool.map { row ->
                                PoolItem(
                                    id = row.id, label = row.label, iconKey = row.iconKey, category = row.category,
                                    isFavorite = row.id in frequentIds,
                                    isAutomatable = row.isAutomatable, isAutomated = row.isAutomated
                                )
                            }
                        }

                        ManagePoolScreen(
                            navController = nav,
                            config = PoolConfig(
                                title = "Locations",
                                subtitle = "Where were you?",
                                iconColor = Color(0xFF64B5F6),
                                drawHeroIcon = { HubIcons.run { drawLocationPin(it) } },
                                items = items,
                                categories = listOf("Exercise", "Home", "Medical", "Outdoors", "Social", "Transport", "Work", "Other"),
                                iconResolver = { key -> LocationIcons.forKey(key) },
                                pickerIcons = LocationIcons.ALL_ICONS.map { PickerIconEntry(it.key, it.label, it.icon) },
                                onAdd = { label, category, _ -> authState.accessToken?.let { locationVm.addNewToPool(it, label, category) } },
                                onDelete = { id -> authState.accessToken?.let { locationVm.removeFromPool(it, id) } },
                                onToggleFavorite = { id, starred ->
                                    val token = authState.accessToken ?: return@PoolConfig
                                    if (starred) locationVm.addToFrequent(token, id)
                                    else frequent.find { it.locationId == id }?.let { locationVm.removeFromFrequent(token, it.id) }
                                },
                                onSetCategory = { id, cat -> authState.accessToken?.let { locationVm.setCategory(it, id, cat) } },
                                onToggleAutomation = { id, en -> authState.accessToken?.let { locationVm.setAutomation(it, id, en) } }
                            )
                        )
                    }

                    composable(Routes.MANAGE_ACTIVITIES) {
                        val activityVm: ActivityViewModel = viewModel()
                        val authState by authVm.state.collectAsState()
                        val pool by activityVm.pool.collectAsState()
                        val frequent by activityVm.frequent.collectAsState()

                        LaunchedEffect(authState.accessToken) { authState.accessToken?.let { activityVm.loadAll(it) } }

                        val frequentIds = remember(frequent) { frequent.map { it.activityId }.toSet() }
                        val items = remember(pool, frequentIds) {
                            pool.map { row ->
                                PoolItem(
                                    id = row.id, label = row.label, iconKey = row.iconKey, category = row.category,
                                    isFavorite = row.id in frequentIds,
                                    isAutomatable = row.isAutomatable, isAutomated = row.isAutomated
                                )
                            }
                        }

                        ManagePoolScreen(
                            navController = nav,
                            config = PoolConfig(
                                title = "Activities",
                                subtitle = "What were you doing?",
                                iconColor = Color(0xFFFF8A65),
                                drawHeroIcon = { HubIcons.run { drawActivityPulse(it) } },
                                items = items,
                                categories = listOf("Exercise", "Leisure", "Screen", "Sleep", "Social", "Travel", "Work", "Other"),
                                iconResolver = { key -> ActivityIcons.forKey(key) },
                                pickerIcons = ActivityIcons.ALL_ICONS.map { PickerIconEntry(it.key, it.label, it.icon) },
                                onAdd = { label, category, _ -> authState.accessToken?.let { activityVm.addNewToPool(it, label, category) } },
                                onDelete = { id -> authState.accessToken?.let { activityVm.removeFromPool(it, id) } },
                                onToggleFavorite = { id, starred ->
                                    val token = authState.accessToken ?: return@PoolConfig
                                    if (starred) activityVm.addToFrequent(token, id)
                                    else frequent.find { it.activityId == id }?.let { activityVm.removeFromFrequent(token, it.id) }
                                },
                                onSetCategory = { id, cat -> authState.accessToken?.let { activityVm.setCategory(it, id, cat) } },
                                onToggleAutomation = { id, en -> authState.accessToken?.let { activityVm.setAutomation(it, id, en) } }
                            )
                        )
                    }

                    composable(Routes.MANAGE_MISSED_ACTIVITIES) {
                        val missedVm: MissedActivityViewModel = viewModel()
                        val authState by authVm.state.collectAsState()
                        val pool by missedVm.pool.collectAsState()
                        val frequent by missedVm.frequent.collectAsState()

                        LaunchedEffect(authState.accessToken) { authState.accessToken?.let { missedVm.loadAll(it) } }

                        val frequentIds = remember(frequent) { frequent.map { it.missedActivityId }.toSet() }
                        val items = remember(pool, frequentIds) {
                            pool.map { row ->
                                PoolItem(
                                    id = row.id, label = row.label, iconKey = row.iconKey, category = row.category,
                                    isFavorite = row.id in frequentIds,
                                    isAutomatable = row.isAutomatable, isAutomated = row.isAutomated
                                )
                            }
                        }

                        ManagePoolScreen(
                            navController = nav,
                            config = PoolConfig(
                                title = "Missed Activities",
                                subtitle = "What did you miss?",
                                iconColor = Color(0xFFEF9A9A),
                                drawHeroIcon = { HubIcons.run { drawMissedActivity(it) } },
                                items = items,
                                categories = listOf("Care", "Exercise", "Leisure", "Screen", "Sleep", "Social", "Travel", "Work", "Other"),
                                iconResolver = { key -> MissedActivityIcons.forKey(key) },
                                pickerIcons = MissedActivityIcons.ALL_ICONS.map { PickerIconEntry(it.key, it.label, it.icon) },
                                onAdd = { label, category, _ -> authState.accessToken?.let { missedVm.addNewToPool(it, label, category) } },
                                onDelete = { id -> authState.accessToken?.let { missedVm.removeFromPool(it, id) } },
                                onToggleFavorite = { id, starred ->
                                    val token = authState.accessToken ?: return@PoolConfig
                                    if (starred) missedVm.addToFrequent(token, id)
                                    else frequent.find { it.missedActivityId == id }?.let { missedVm.removeFromFrequent(token, it.id) }
                                },
                                onSetCategory = { id, cat -> authState.accessToken?.let { missedVm.setCategory(it, id, cat) } },
                                onToggleAutomation = { id, en -> authState.accessToken?.let { missedVm.setAutomation(it, id, en) } }
                            )
                        )
                    }

                    composable(Routes.LOGIN) {
                        val a by authVm.state.collectAsState()
                        val loginCtx = LocalContext.current
                        LaunchedEffect(a.accessToken) {
                            if (!a.accessToken.isNullOrBlank()) {
                                // If returning from WHOOP OAuth during setup, go back to Connections
                                val whoopPrefs = loginCtx.getSharedPreferences("whoop_oauth", android.content.Context.MODE_PRIVATE)
                                val returnToSetup = whoopPrefs.getBoolean("return_to_setup", false)
                                if (returnToSetup) {
                                    whoopPrefs.edit().putBoolean("return_to_setup", false).apply()
                                    nav.navigate(Routes.THIRD_PARTY_CONNECTIONS) {
                                        popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                } else {
                                    val completed = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                        OnboardingPrefs.isCompletedFromSupabase(loginCtx)
                                    }
                                    val dest = if (completed) Routes.HOME else Routes.ONBOARDING
                                    nav.navigate(dest) {
                                        popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                            }
                        }
                        LoginScreen(
                            authVm = authVm,
                            onLoggedIn = {
                                scope.launch {
                                    val completed = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                        OnboardingPrefs.isCompletedFromSupabase(loginCtx)
                                    }
                                    val dest = if (completed) Routes.HOME else Routes.ONBOARDING
                                    nav.navigate(dest) {
                                        popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                            },
                            onNavigateToSignUp = { nav.navigate(Routes.SIGNUP) { launchSingleTop = true } }
                        )
                    }

                    composable(Routes.SIGNUP) {
                        val signupCtx = LocalContext.current
                        SignupScreen(
                            authVm = authVm,
                            onSignedUpAndLoggedIn = {
                                nav.navigate(Routes.ONBOARDING) {
                                    popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                                    launchSingleTop = true
                                }
                            },
                            onNavigateToLogin = { nav.navigate(Routes.LOGIN) { launchSingleTop = true } }
                        )
                    }

                    composable(Routes.PROFILE) {
                        ProfileScreen(
                            authVm = authVm,
                            onBack = { nav.popBackStack() },
                            onNavigateChangePassword = { nav.navigate(Routes.CHANGE_PASSWORD) },
                            onNavigateToRecalibrationReview = { nav.navigate(Routes.RECALIBRATION_REVIEW) },
                            onNavigateToPaywall = { nav.navigate(Routes.PAYWALL) }
                        )
                    }

                    composable(Routes.CHANGE_PASSWORD) {
                        ChangePasswordScreen(authVm = authVm, onDone = { nav.popBackStack() })
                    }

                    composable(Routes.THIRD_PARTY_CONNECTIONS) {
                        ThirdPartyConnectionsScreen(onBack = { nav.popBackStack() })
                    }

                    composable(Routes.DATA) {
                        DataSettingsScreen(onBack = { nav.popBackStack() }, onOpenMenstruationSettings = { nav.navigate(Routes.MENSTRUATION_SETTINGS) })
                    }

                    composable(Routes.RISK_WEIGHTS) {
                        val pState by PremiumManager.state.collectAsState()
                        val tourActive = TourManager.isActive()
                        if (!tourActive && !pState.isLoading && !pState.isPremium) {
                            LaunchedEffect(Unit) { nav.navigate(Routes.PAYWALL) { popUpTo(Routes.HOME) } }
                        } else { RiskWeightsScreen(onBack = { nav.popBackStack() }) }
                    }

                    composable(Routes.RISK_DETAIL) {
                        val pState by PremiumManager.state.collectAsState()
                        if (!pState.isLoading && !pState.isPremium) {
                            LaunchedEffect(Unit) { nav.navigate(Routes.PAYWALL) { popUpTo(Routes.HOME) } }
                        } else {
                            val homeState by homeVm.state.collectAsState()
                            RiskDetailScreen(
                                navController = nav,
                                state = homeState
                            )
                        }
                    }

                    composable(Routes.RECALIBRATION_REVIEW) {
                        RecalibrationReviewScreen(
                            onBack = { nav.popBackStack() }
                        )
                    }

                    composable(Routes.ONBOARDING) {
                        OnboardingScreen(
                            startAtSetup = false,
                            onComplete = {
                                nav.navigate(Routes.HOME) {
                                    popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                                    launchSingleTop = true
                                }
                            },
                            onStartTour = {
                                nav.navigate(Routes.HOME) {
                                    popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                                    launchSingleTop = true
                                }
                                TourManager.startTour()
                            },
                            onStartSetup = {
                                nav.navigate(Routes.THIRD_PARTY_CONNECTIONS) {
                                    launchSingleTop = true
                                }
                                TourManager.startSetup()
                            },
                            onTourSkipped = {
                                nav.navigate("${Routes.ONBOARDING}/setup") {
                                    popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    composable("${Routes.ONBOARDING}/setup") {
                        OnboardingScreen(
                            startAtSetup = true,
                            onComplete = {
                                nav.navigate(Routes.AI_SETUP) {
                                    popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                                    launchSingleTop = true
                                }
                            },
                            onStartTour = { /* already done */ },
                            onStartSetup = {
                                nav.navigate(Routes.THIRD_PARTY_CONNECTIONS) {
                                    launchSingleTop = true
                                }
                                TourManager.startSetup()
                            }
                        )
                    }

                    composable(Routes.AI_SETUP) {
                        AiSetupScreen(
                            onComplete = {
                                scope.launch(Dispatchers.IO) {
                                    try { EdgeFunctionsService().enqueueLoginBackfill(appCtx) } catch (_: Exception) {}
                                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                                        nav.navigate("backfill_loading") {
                                            popUpTo(Routes.AI_SETUP) { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    }
                                }
                            },
                            onSkip = {
                                scope.launch(Dispatchers.IO) {
                                    try { EdgeFunctionsService().enqueueLoginBackfill(appCtx) } catch (_: Exception) {}
                                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                                        nav.navigate("backfill_loading") {
                                            popUpTo(Routes.AI_SETUP) { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    }
                                }
                            }
                        )
                    }

                    // â”€â”€ Backfill loading: polls edge_audit until backfill-all completes (max 60s) â”€â”€
                    composable("backfill_loading") {
                        val ctx = LocalContext.current
                        var progress by remember { mutableFloatStateOf(0f) }
                        var statusText by remember { mutableStateOf("Analyzing your data...") }
                        var showContinueButton by remember { mutableStateOf(false) }

                        LaunchedEffect(Unit) {
                            kotlinx.coroutines.withContext(Dispatchers.IO) {
                                val token = SessionStore.readAccessToken(ctx) ?: ""
                                val userId = SessionStore.readUserId(ctx) ?: ""
                                val base = BuildConfig.SUPABASE_URL
                                val key = BuildConfig.SUPABASE_ANON_KEY
                                val client = io.ktor.client.HttpClient()
                                val startTime = System.currentTimeMillis()
                                val startIso = java.time.Instant.now().toString()
                                val timeoutMs = 120_000L
                                var done = false

                                // Wait a few seconds before first poll to let backfill start
                                kotlinx.coroutines.delay(5000)

                                try {
                                    while (!done && (System.currentTimeMillis() - startTime) < timeoutMs) {
                                        val elapsed = System.currentTimeMillis() - startTime
                                        progress = (elapsed.toFloat() / timeoutMs).coerceAtMost(0.95f)

                                        // Update status text based on progress
                                        statusText = when {
                                            elapsed < 12_000 -> "Fetching your WHOOP data..."
                                            elapsed < 22_000 -> "Loading weather patterns..."
                                            elapsed < 38_000 -> "Evaluating your triggers..."
                                            elapsed < 52_000 -> "Calculating risk scores..."
                                            else -> "Almost there..."
                                        }

                                        try {
                                            val resp = client.get("$base/rest/v1/edge_audit?fn=eq.backfill-all&user_id=eq.$userId&ok=eq.true&created_at=gte.$startIso&order=created_at.desc&limit=1") {
                                                header("Authorization", "Bearer $token")
                                                header("apikey", key)
                                            }
                                            val body = resp.bodyAsText()
                                            // Non-empty array means a matching row was found
                                            if (body.startsWith("[") && body.length > 5 && !body.startsWith("[]")) {
                                                done = true
                                                progress = 1f
                                                statusText = "All set!"
                                            }
                                        } catch (_: Exception) {}

                                        if (!done) kotlinx.coroutines.delay(3000)
                                    }
                                } finally { client.close() }

                                if (!done) { progress = 1f; statusText = "Taking longer than expected..." }
                                kotlinx.coroutines.delay(800) // Brief pause to show final status
                            }

                            if (statusText == "All set!") {
                                // Backfill completed — go to subscribe
                                nav.navigate("subscribe") {
                                    popUpTo("backfill_loading") { inclusive = true }
                                    launchSingleTop = true
                                }
                            } else {
                                // Backfill still running — show retry/continue option
                                statusText = "Still setting up in the background"
                                showContinueButton = true
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(AppTheme.FadeColor)
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                // Animated brain/pulse icon
                                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                                val pulseScale by infiniteTransition.animateFloat(
                                    initialValue = 0.9f, targetValue = 1.1f,
                                    animationSpec = infiniteRepeatable(
                                        tween(1000, easing = FastOutSlowInEasing),
                                        RepeatMode.Reverse
                                    ), label = "scale"
                                )
                                val animatedProgress by animateFloatAsState(progress, tween(500), label = "prog")
                                Icon(
                                    Icons.Outlined.Analytics, contentDescription = null,
                                    tint = AppTheme.AccentPink,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .scale(pulseScale)
                                )

                                Text(
                                    statusText,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AppTheme.TitleColor,
                                    textAlign = TextAlign.Center
                                )

                                LinearProgressIndicator(
                                    progress = { animatedProgress },
                                    modifier = Modifier
                                        .fillMaxWidth(0.7f)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = AppTheme.AccentPink,
                                    trackColor = AppTheme.TrackColor
                                )

                                Text(
                                    if (showContinueButton) "Your data is still being processed.\nThis will complete in the background."
                                    else "Setting up your personalised migraine predictions",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppTheme.SubtleTextColor,
                                    textAlign = TextAlign.Center
                                )

                                if (showContinueButton) {
                                    Spacer(Modifier.height(8.dp))
                                    Button(
                                        onClick = {
                                            nav.navigate("subscribe") {
                                                popUpTo("backfill_loading") { inclusive = true }
                                                launchSingleTop = true
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Continue")
                                    }
                                }
                            }
                        }
                    }

                    // ── Subscribe / Paywall ──
                    composable("subscribe") {
                        OnboardingPaywallScreen(
                            onDismiss = {
                                nav.navigate(Routes.HOME) {
                                    popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                                    launchSingleTop = true
                                }
                            },
                            onSubscribed = {
                                nav.navigate(Routes.HOME) {
                                    popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    composable(Routes.PAYWALL) {
                        PaywallScreen(navController = nav)
                    }

                    composable(Routes.MENSTRUATION_SETTINGS) {
                        MenstruationSettingsScreen(onBack = { nav.popBackStack() })
                    }

                    composable(Routes.EVENING_CHECKIN) {
                        EveningCheckInScreen(
                            navController = nav,
                            authVm = authVm,
                            triggerVm = triggerVm,
                            prodromeVm = viewModel(),
                            medicineVm = medVm,
                            reliefVm = reliefVm,
                        )
                    }

                    composable(Routes.LOGOUT) {
                        LogoutScreen(
                            authVm = authVm,
                            onLoggedOut = {
                                nav.navigate(Routes.LOGIN) {
                                    popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                }

                    // Coach overlay for feature tour (floats on top of real screens)
                    CoachOverlay(
                        navigateTo = { route ->
                            nav.navigate(route) {
                                launchSingleTop = true
                            }
                        },
                        onTourFinished = {
                            nav.navigate("${Routes.ONBOARDING}/setup") {
                                popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        onSetupFinished = {
                            nav.navigate(Routes.AI_SETUP) {
                                popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    )
                } // end Box wrapper
            }
        }
    }
}

private fun needsAttention(ev: JournalEvent): Boolean {
    return when (ev) {
        is JournalEvent.Migraine ->
            ev.row.startAt.isNullOrBlank() || ev.row.endAt.isNullOrBlank() || ev.row.severity == null

        is JournalEvent.Trigger ->
            ev.row.startAt.isNullOrBlank()

        is JournalEvent.Medicine ->
            ev.row.amount.isNullOrBlank() || ev.row.startAt.isNullOrBlank()

        is JournalEvent.Relief ->
            ev.row.durationMinutes == null || ev.row.startAt.isNullOrBlank()

        is JournalEvent.Prodrome -> ev.row.startAt.isNullOrBlank()
        is JournalEvent.Activity -> ev.row.startAt.isNullOrBlank()
        is JournalEvent.Location -> false
        is JournalEvent.MissedActivity -> false
    }
}

@Composable
private fun BottomBar(
    nav: androidx.navigation.NavHostController,
    journalBadgeCount: Int
) {
    data class BottomItem(val route: String, val label: String, val icon: ImageVector)

    val items = listOf(
        BottomItem(Routes.MONITOR, "Monitor", Icons.Outlined.Timeline),
        BottomItem(Routes.INSIGHTS, "Insights", Icons.Outlined.BarChart),
        BottomItem(Routes.HOME, "Home", Icons.Outlined.Home),
        BottomItem(Routes.MIGRAINE, "Log", Icons.Outlined.Psychology),
        BottomItem(Routes.JOURNAL, "Journal", Icons.Outlined.History)
    )

    // Tour pulse state
    val tourState by TourManager.state.collectAsState()
    val currentTourStep = if (tourState.active && tourState.phase == CoachPhase.TOUR)
        tourSteps.getOrNull(tourState.stepIndex) else null
    val tourHintRoute = currentTourStep?.navHint?.let { hint ->
        when (hint) {
            NavHintLocation.BOTTOM_HOME -> Routes.HOME
            NavHintLocation.BOTTOM_MONITOR -> Routes.MONITOR
            NavHintLocation.BOTTOM_INSIGHTS -> Routes.INSIGHTS
            NavHintLocation.BOTTOM_MIGRAINE -> Routes.MIGRAINE
            NavHintLocation.BOTTOM_JOURNAL -> Routes.JOURNAL
            else -> null
        }
    }
    val infiniteTransition = rememberInfiniteTransition(label = "navPulse")
    val pulseScale by infiniteTransition.animateFloat(
        1f, 1.5f,
        infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "navPulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        0.5f, 0f,
        infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "navPulseAlpha"
    )

    NavigationBar(
        containerColor = Color(0xFF2A003D),
        tonalElevation = 0.dp
    ) {
        val backStack by nav.currentBackStackEntryAsState()
        val currentRoute = backStack?.destination?.route

        items.forEach { item ->
            val showBadge = item.route == Routes.JOURNAL && journalBadgeCount > 0
            val selected = currentRoute == item.route ||
                    (item.route == Routes.INSIGHTS && currentRoute == Routes.INSIGHTS_DETAIL) ||
                    (item.route == Routes.INSIGHTS && currentRoute == Routes.INSIGHTS_REPORT) ||
                    (item.route == Routes.INSIGHTS && currentRoute?.startsWith(Routes.INSIGHTS_BREAKDOWN) == true) ||
                    (item.route == Routes.MONITOR && currentRoute == Routes.MONITOR_CONFIG)
            val isTourTarget = tourHintRoute == item.route

            NavigationBarItem(
                selected = selected,
                onClick = {
                    nav.navigate(item.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(24.dp)
                    ) {
                        if (isTourTarget) {
                            Box(
                                Modifier
                                    .size((24 * pulseScale).dp)
                                    .background(
                                        AppTheme.AccentPink.copy(alpha = pulseAlpha),
                                        CircleShape
                                    )
                            )
                        }
                        if (showBadge) {
                            BadgedBox(badge = { Badge { Text(journalBadgeCount.toString()) } }) {
                                Icon(item.icon, contentDescription = item.label)
                            }
                        } else {
                            Icon(item.icon, contentDescription = item.label)
                        }
                    }
                },
                label = { Text(item.label) },
                alwaysShowLabel = true,
                colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White,
                    selectedTextColor = Color.White,
                    unselectedIconColor = Color.White.copy(alpha = 0.85f),
                    unselectedTextColor = Color.White.copy(alpha = 0.85f),
                    indicatorColor = AppTheme.AccentPurple.copy(alpha = 0.25f)
                )
            )
        }
    }
}




