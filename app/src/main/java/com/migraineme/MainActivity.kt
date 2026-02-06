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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
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


object Routes {
    const val HOME = "home"
    const val PROFILE = "profile"
    const val DATA = "data"
    const val MENSTRUATION_SETTINGS = "menstruation_settings"
    const val COMMUNITY = "community"
    const val INSIGHTS = "insights"
    const val INSIGHTS_DETAIL = "insights_detail"
    const val MONITOR = "monitor"
    const val MONITOR_CONFIG = "monitor_config"
    const val JOURNAL = "journal"

    const val MIGRAINE = "migraine"
    const val LOG_MIGRAINE = "log_migraine"  // Full migraine wizard
    const val QUICK_LOG_TRIGGER = "quick_log_trigger"
    const val QUICK_LOG_MEDICINE = "quick_log_medicine"
    const val QUICK_LOG_RELIEF = "quick_log_relief"
    
    const val MONITOR_NUTRITION = "monitor_nutrition"
    const val NUTRITION_CONFIG = "nutrition_config"
    const val NUTRITION_HISTORY = "nutrition_history"
    const val WEATHER_CONFIG = "weather_config"
    const val SLEEP_DATA_HISTORY = "sleep_data_history"
    const val ENV_DATA_HISTORY = "env_data_history"
    const val MONITOR_PHYSICAL = "monitor_physical"
    const val MONITOR_SLEEP = "monitor_sleep"
    const val SLEEP_CONFIG = "sleep_config"
    const val FULL_GRAPH = "full_graph"
    const val FULL_GRAPH_SLEEP = "full_graph_sleep"
    const val FULL_GRAPH_WEATHER = "full_graph_weather"
    const val FULL_GRAPH_NUTRITION = "full_graph_nutrition"
    const val MONITOR_MENTAL = "monitor_mental"
    const val MONITOR_ENVIRONMENT = "monitor_environment"
    
    const val TRIGGERS = "triggers"
    const val ADJUST_TRIGGERS = "adjust_triggers"
    const val MEDICINES = "medicines"
    const val ADJUST_MEDICINES = "adjust_medicines"
    const val RELIEFS = "reliefs"
    const val ADJUST_RELIEFS = "adjust_reliefs"
    const val REVIEW = "review"

    const val LOGIN = "login"
    const val SIGNUP = "signup"
    const val LOGOUT = "logout"

    const val EDIT_MIGRAINE = "edit_migraine"
    const val EDIT_TRIGGER = "edit_trigger"
    const val EDIT_MEDICINE = "edit_medicine"
    const val EDIT_RELIEF = "edit_relief"

    const val ADJUST_MIGRAINES = "adjust_migraines"

    const val TESTING = "testing"
    const val TESTING_COMPLETE = "testing_complete"
    const val LOCATION_DEBUG = "location_debug"

    const val THIRD_PARTY_CONNECTIONS = "third_party_connections"

    const val CHANGE_PASSWORD = "change_password"
    
    const val TRIGGERS_SETTINGS = "triggers_settings"
    const val CUSTOMIZE_TRIGGERS = "customize_triggers"
}

class MainActivity : ComponentActivity() {

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

        setContent {
            MaterialTheme {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWhoopOAuthIntent(intent)
        handleSupabaseOAuthIntent(intent)
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
                    Toast.makeText(this, "Returning from WHOOP…", Toast.LENGTH_SHORT).show()
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

            Toast.makeText(this, "Returning from sign-in…", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
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

    val authState by authVm.state.collectAsState()
    val token = authState.accessToken

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

    data class DrawerItem(val title: String, val route: String, val icon: ImageVector)

    val drawerItems = listOf(
        DrawerItem("Profile", Routes.PROFILE, Icons.Outlined.Person),
        DrawerItem("Connections", Routes.THIRD_PARTY_CONNECTIONS, Icons.Outlined.Link),
        DrawerItem("Data", Routes.DATA, Icons.Outlined.Storage),
        DrawerItem("Location Debug", Routes.LOCATION_DEBUG, Icons.Outlined.LocationOn),
        DrawerItem("Testing", Routes.TESTING, Icons.Outlined.BarChart),
        DrawerItem("Testing Complete", Routes.TESTING_COMPLETE, Icons.Outlined.Assessment),
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
        
        // Updated to include JOURNAL and Migraine screens
        val showHomeBackground =
            current == Routes.HOME || 
            current == Routes.INSIGHTS || 
            current == Routes.INSIGHTS_DETAIL ||
            current == Routes.JOURNAL ||
            current == Routes.MIGRAINE ||
            current == Routes.QUICK_LOG_TRIGGER ||
            current == Routes.QUICK_LOG_MEDICINE ||
            current == Routes.QUICK_LOG_RELIEF ||
            current == Routes.MONITOR ||
            current == Routes.MONITOR_CONFIG ||
            current == Routes.MONITOR_NUTRITION ||
            current == Routes.NUTRITION_CONFIG ||
            current == Routes.NUTRITION_HISTORY ||
            current == Routes.WEATHER_CONFIG ||
            current == Routes.SLEEP_DATA_HISTORY ||
            current == Routes.ENV_DATA_HISTORY ||
            current == Routes.MONITOR_PHYSICAL ||
            current == Routes.MONITOR_SLEEP ||
            current == Routes.SLEEP_CONFIG ||
            current == Routes.FULL_GRAPH_SLEEP ||
            current == Routes.FULL_GRAPH_WEATHER ||
            current == Routes.FULL_GRAPH_NUTRITION ||
            current == Routes.MONITOR_MENTAL ||
            current == Routes.MONITOR_ENVIRONMENT ||
            current == Routes.LOCATION_DEBUG ||
            current == Routes.THIRD_PARTY_CONNECTIONS

        // Insights background
        val insightsBgResId = remember {
            ctx.resources.getIdentifier("purple_sky_bg_insights", "drawable", ctx.packageName)
        }
        var insightsBgBitmap by remember { mutableStateOf<Bitmap?>(null) }

        LaunchedEffect(current, insightsBgResId) {
            val isInsights = current == Routes.INSIGHTS || current == Routes.INSIGHTS_DETAIL
            if (!isInsights) return@LaunchedEffect
            if (insightsBgResId != 0) return@LaunchedEffect
            if (insightsBgBitmap != null) return@LaunchedEffect

            insightsBgBitmap = withContext(Dispatchers.IO) {
                try {
                    appCtx.assets.open("purple_sky_bg_insights.png").use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                } catch (_: Throwable) {
                    null
                }
            }
        }

        // Migraine background
        val migraineBgResId = remember {
            ctx.resources.getIdentifier("purple_sky_bg_migraine", "drawable", ctx.packageName)
        }
        var migraineBgBitmap by remember { mutableStateOf<Bitmap?>(null) }

        LaunchedEffect(current, migraineBgResId) {
            val isMigraine = current == Routes.MIGRAINE || 
                current == Routes.QUICK_LOG_TRIGGER || 
                current == Routes.QUICK_LOG_MEDICINE || 
                current == Routes.QUICK_LOG_RELIEF
            if (!isMigraine) return@LaunchedEffect
            if (migraineBgResId != 0) return@LaunchedEffect
            if (migraineBgBitmap != null) return@LaunchedEffect

            migraineBgBitmap = withContext(Dispatchers.IO) {
                try {
                    appCtx.assets.open("purple_sky_bg_migraine.png").use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                } catch (_: Throwable) {
                    null
                }
            }
        }

        // Monitor background
        val monitorBgResId = remember {
            ctx.resources.getIdentifier("purple_sky_bg_monitor", "drawable", ctx.packageName)
        }
        var monitorBgBitmap by remember { mutableStateOf<Bitmap?>(null) }

        LaunchedEffect(current, monitorBgResId) {
            val isMonitor = current == Routes.MONITOR || 
                current == Routes.MONITOR_CONFIG ||
                current == Routes.MONITOR_NUTRITION ||
                current == Routes.NUTRITION_CONFIG ||
                current == Routes.NUTRITION_HISTORY ||
                current == Routes.WEATHER_CONFIG ||
                current == Routes.SLEEP_DATA_HISTORY ||
                current == Routes.ENV_DATA_HISTORY ||
                current == Routes.MONITOR_PHYSICAL ||
                current == Routes.MONITOR_SLEEP ||
                current == Routes.SLEEP_CONFIG ||
                current == Routes.FULL_GRAPH_SLEEP ||
                current == Routes.FULL_GRAPH_WEATHER ||
                current == Routes.FULL_GRAPH_NUTRITION ||
                current == Routes.MONITOR_MENTAL ||
                current == Routes.MONITOR_ENVIRONMENT ||
                current == Routes.LOCATION_DEBUG ||
            current == Routes.THIRD_PARTY_CONNECTIONS
            if (!isMonitor) return@LaunchedEffect
            if (monitorBgResId != 0) return@LaunchedEffect
            if (monitorBgBitmap != null) return@LaunchedEffect

            monitorBgBitmap = withContext(Dispatchers.IO) {
                try {
                    appCtx.assets.open("purple_sky_bg_monitor.png").use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                } catch (_: Throwable) {
                    null
                }
            }
        }

        // Journal background
        val journalBgResId = remember {
            ctx.resources.getIdentifier("purple_sky_bg_journal", "drawable", ctx.packageName)
        }
        var journalBgBitmap by remember { mutableStateOf<Bitmap?>(null) }

        LaunchedEffect(current, journalBgResId) {
            if (current != Routes.JOURNAL) return@LaunchedEffect
            if (journalBgResId != 0) return@LaunchedEffect
            if (journalBgBitmap != null) return@LaunchedEffect

            journalBgBitmap = withContext(Dispatchers.IO) {
                try {
                    appCtx.assets.open("purple_sky_bg_journal.png").use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                } catch (_: Throwable) {
                    null
                }
            }
        }


        // Connections background
        val connectionsBgResId = remember {
            ctx.resources.getIdentifier("purple_sky_bg_3rd_connection", "drawable", ctx.packageName)
        }
        var connectionsBgBitmap by remember { mutableStateOf<Bitmap?>(null) }

        LaunchedEffect(current, connectionsBgResId) {
            if (current != Routes.THIRD_PARTY_CONNECTIONS) return@LaunchedEffect
            if (connectionsBgResId != 0) return@LaunchedEffect
            if (connectionsBgBitmap != null) return@LaunchedEffect

            connectionsBgBitmap = withContext(Dispatchers.IO) {
                try {
                    appCtx.assets.open("purple_sky_bg_3rd_connection.png").use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                } catch (_: Throwable) {
                    null
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when (current) {
                Routes.HOME -> {
                    Image(
                        painter = painterResource(R.drawable.purple_sky_bg),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                Routes.MIGRAINE, Routes.QUICK_LOG_TRIGGER, Routes.QUICK_LOG_MEDICINE, Routes.QUICK_LOG_RELIEF -> {
                    when {
                        migraineBgResId != 0 -> {
                            Image(
                                painter = painterResource(migraineBgResId),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        migraineBgBitmap != null -> {
                            Image(
                                bitmap = migraineBgBitmap!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        else -> {
                            // Fallback to home background
                            Image(
                                painter = painterResource(R.drawable.purple_sky_bg),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }

                Routes.JOURNAL -> {
                    when {
                        journalBgResId != 0 -> {
                            Image(
                                painter = painterResource(journalBgResId),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        journalBgBitmap != null -> {
                            Image(
                                bitmap = journalBgBitmap!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        else -> {
                            // Fallback to home background
                            Image(
                                painter = painterResource(R.drawable.purple_sky_bg),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }

                Routes.MONITOR, Routes.MONITOR_CONFIG, Routes.MONITOR_NUTRITION, Routes.NUTRITION_CONFIG, Routes.NUTRITION_HISTORY, Routes.WEATHER_CONFIG, Routes.SLEEP_DATA_HISTORY, Routes.ENV_DATA_HISTORY, Routes.MONITOR_PHYSICAL, Routes.MONITOR_SLEEP, Routes.SLEEP_CONFIG, Routes.FULL_GRAPH_SLEEP, Routes.FULL_GRAPH_WEATHER, Routes.FULL_GRAPH_NUTRITION, Routes.MONITOR_MENTAL, Routes.MONITOR_ENVIRONMENT, Routes.LOCATION_DEBUG -> {
                    when {
                        monitorBgResId != 0 -> {
                            Image(
                                painter = painterResource(monitorBgResId),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        monitorBgBitmap != null -> {
                            Image(
                                bitmap = monitorBgBitmap!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        else -> {
                            // Fallback to home background
                            Image(
                                painter = painterResource(R.drawable.purple_sky_bg),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }

                Routes.THIRD_PARTY_CONNECTIONS -> {
                    when {
                        connectionsBgResId != 0 -> {
                            Image(
                                painter = painterResource(connectionsBgResId),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        connectionsBgBitmap != null -> {
                            Image(
                                bitmap = connectionsBgBitmap!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        else -> {
                            // Fallback to home background
                            Image(
                                painter = painterResource(R.drawable.purple_sky_bg),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }

                Routes.INSIGHTS, Routes.INSIGHTS_DETAIL -> {
                    when {
                        insightsBgResId != 0 -> {
                            Image(
                                painter = painterResource(insightsBgResId),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        insightsBgBitmap != null -> {
                            Image(
                                bitmap = insightsBgBitmap!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        else -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                            )
                        }
                    }
                }

                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    )
                }
            }

            Scaffold(
                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                containerColor = Color.Transparent,
                topBar = {
                    CenterAlignedTopAppBar(
                        scrollBehavior = scrollBehavior,
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = Color(0xFF2A003D),
                            titleContentColor = if (showHomeBackground) Color.White else MaterialTheme.colorScheme.onSurface,
                            navigationIconContentColor = if (showHomeBackground) Color.White else MaterialTheme.colorScheme.onSurface,
                            actionIconContentColor = if (showHomeBackground) Color.White else MaterialTheme.colorScheme.onSurface
                        ),
                        title = {
                            Text(
                                when (current) {
                                    Routes.MONITOR -> "Monitor"
                                    Routes.MONITOR_CONFIG -> "Configure Monitor"
                                    Routes.INSIGHTS -> "Insights"
                                    Routes.INSIGHTS_DETAIL -> "Insights"
                                    Routes.HOME -> "Home"
                                    Routes.MIGRAINE -> "Migraine"
                                    Routes.LOG_MIGRAINE -> "Log Migraine"
                                    Routes.QUICK_LOG_TRIGGER -> "Log Trigger"
                                    Routes.QUICK_LOG_MEDICINE -> "Log Medicine"
                                    Routes.QUICK_LOG_RELIEF -> "Log Relief"
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
                                    Routes.ADJUST_RELIEFS -> "Adjust Reliefs"
                                    Routes.TRIGGERS -> "Triggers"
                                    Routes.ADJUST_TRIGGERS -> "Adjust Triggers"
                                    Routes.REVIEW -> "Review Log"
                                    Routes.EDIT_MIGRAINE -> "Edit Migraine"
                                    Routes.EDIT_TRIGGER -> "Edit Trigger"
                                    Routes.EDIT_MEDICINE -> "Edit Medicine"
                                    Routes.EDIT_RELIEF -> "Edit Relief"
                                    Routes.ADJUST_MIGRAINES -> "Adjust Migraines"
                                    Routes.TESTING -> "Testing"
                                    Routes.TESTING_COMPLETE -> "Testing Complete"
                                    Routes.LOCATION_DEBUG -> "Location Debug"
                                    Routes.THIRD_PARTY_CONNECTIONS -> "Connections"
                                    Routes.CHANGE_PASSWORD -> "Change password"
                                    else -> ""
                                }
                            )
                        },
                        navigationIcon = {
                            if (current != Routes.LOGIN && current != Routes.SIGNUP) {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                                }
                            }
                        },
                        actions = {
                            if (current != Routes.LOGIN && current != Routes.SIGNUP) {
                                IconButton(onClick = { nav.navigate(Routes.COMMUNITY) }) {
                                    Icon(Icons.Outlined.Groups, contentDescription = "Community")
                                }
                            }
                        }
                    )
                },
                bottomBar = {
                    if (current != Routes.LOGIN && current != Routes.SIGNUP) {
                        BottomBar(nav, attentionCount)
                    }
                }
            ) { inner ->
                NavHost(
                    navController = nav,
                    startDestination = Routes.LOGIN,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(inner)
                ) {
                    composable(Routes.MONITOR) { MonitorScreen(navController = nav, authVm = authVm) }
                    composable(Routes.MONITOR_CONFIG) { MonitorConfigScreen(onBack = { nav.popBackStack() }) }
                    
                    // Monitor detail screens (placeholders for now)
                    composable(Routes.MONITOR_NUTRITION) { MonitorNutritionScreen(navController = nav, authVm = authVm) }
                    composable(Routes.NUTRITION_CONFIG) { NutritionConfigScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.NUTRITION_HISTORY) { NutritionHistoryScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.WEATHER_CONFIG) { WeatherConfigScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.SLEEP_DATA_HISTORY) { SleepDataHistoryScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.ENV_DATA_HISTORY) { EnvironmentDataHistoryScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.MONITOR_PHYSICAL) { MonitorPhysicalScreen(navController = nav, authVm = authVm) }
                    composable(Routes.MONITOR_SLEEP) { MonitorSleepScreen(navController = nav, authVm = authVm) }
                    composable(Routes.SLEEP_CONFIG) { SleepConfigScreen(onBack = { nav.popBackStack() }) }
                    composable(Routes.FULL_GRAPH_SLEEP) { FullScreenGraphScreen(graphType = "sleep", onBack = { nav.popBackStack() }) }
                    composable(Routes.FULL_GRAPH_WEATHER) { FullScreenGraphScreen(graphType = "weather", onBack = { nav.popBackStack() }) }
                    composable(Routes.FULL_GRAPH_NUTRITION) { FullScreenGraphScreen(graphType = "nutrition", onBack = { nav.popBackStack() }) }
                    composable(Routes.MONITOR_MENTAL) { MonitorMentalScreen(navController = nav, authVm = authVm) }
                    composable(Routes.MONITOR_ENVIRONMENT) { MonitorEnvironmentScreen(navController = nav, authVm = authVm) }
                    
                    composable(Routes.INSIGHTS) { InsightsScreen(navController = nav) }
                    composable(Routes.INSIGHTS_DETAIL) { InsightsDetailScreen() }
                    composable(Routes.TRIGGERS_SETTINGS) { TriggersSettingsScreen(navController = nav, authVm = authVm) }
                    composable(Routes.CUSTOMIZE_TRIGGERS) { CustomizeTriggersScreen() }
                    composable(Routes.HOME) {
                        HomeScreenRoot(
                            onLogout = { nav.navigate(Routes.LOGOUT) { launchSingleTop = true } },
                            onNavigateToMigraine = { nav.navigate(Routes.MIGRAINE) },
                            authVm = authVm,
                            logVm = logVm
                        )
                    }
                    composable(Routes.COMMUNITY) { CommunityScreen() }
                    composable(Routes.JOURNAL) { JournalScreen(navController = nav, authVm = authVm, vm = logVm) }

                    // Migraine Hub (main migraine tab)
                    composable(Routes.MIGRAINE) { MigraineHubScreen(navController = nav) }
                    
                    // Full migraine wizard flow
                    composable(Routes.LOG_MIGRAINE) { LogHomeScreen(navController = nav, authVm = authVm, vm = logVm) }

                    // Quick log screens (standalone)
                    composable(Routes.QUICK_LOG_TRIGGER) { QuickLogTriggerScreen(navController = nav, authVm = authVm) }
                    composable(Routes.QUICK_LOG_MEDICINE) { QuickLogMedicineScreen(navController = nav, authVm = authVm) }
                    composable(Routes.QUICK_LOG_RELIEF) { QuickLogReliefScreen(navController = nav, authVm = authVm) }

                    composable(Routes.TRIGGERS) {
                        TriggersScreen(navController = nav, vm = triggerVm, authVm = authVm, logVm = logVm)
                    }
                    composable(Routes.ADJUST_TRIGGERS) {
                        AdjustTriggersScreen(navController = nav, vm = triggerVm, authVm = authVm)
                    }

                    composable(Routes.MEDICINES) {
                        MedicinesScreen(navController = nav, vm = medVm, authVm = authVm, logVm = logVm)
                    }
                    composable(Routes.ADJUST_MEDICINES) {
                        AdjustMedicinesScreen(navController = nav, vm = medVm, authVm = authVm)
                    }

                    composable(Routes.RELIEFS) {
                        ReliefsScreen(navController = nav, vm = reliefVm, authVm = authVm, logVm = logVm)
                    }
                    composable(Routes.ADJUST_RELIEFS) {
                        AdjustReliefsScreen(navController = nav, vm = reliefVm, authVm = authVm)
                    }

                    composable(Routes.REVIEW) { ReviewLogScreen(navController = nav, authVm = authVm, vm = logVm) }

                    composable("${Routes.EDIT_MIGRAINE}/{id}") {
                        val id = it.arguments?.getString("id") ?: return@composable
                        EditMigraineScreen(navController = nav, authVm = authVm, vm = logVm, id = id)
                    }
                    composable("${Routes.EDIT_TRIGGER}/{id}") {
                        val id = it.arguments?.getString("id") ?: return@composable
                        EditTriggerScreen(navController = nav, authVm = authVm, vm = logVm, id = id)
                    }
                    composable("${Routes.EDIT_MEDICINE}/{id}") {
                        val id = it.arguments?.getString("id") ?: return@composable
                        EditMedicineScreen(navController = nav, authVm = authVm, vm = logVm, id = id)
                    }
                    composable("${Routes.EDIT_RELIEF}/{id}") {
                        val id = it.arguments?.getString("id") ?: return@composable
                        EditReliefScreen(navController = nav, authVm = authVm, vm = logVm, id = id)
                    }

                    composable(Routes.ADJUST_MIGRAINES) {
                        AdjustMigrainesScreen(navController = nav, vm = migraineVm, authVm = authVm)
                    }

                    composable(Routes.LOGIN) {
                        val a by authVm.state.collectAsState()
                        LaunchedEffect(a.accessToken) {
                            if (!a.accessToken.isNullOrBlank()) {
                                nav.navigate(Routes.HOME) {
                                    popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }
                        LoginScreen(
                            authVm = authVm,
                            onLoggedIn = {
                                nav.navigate(Routes.HOME) {
                                    popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                                    launchSingleTop = true
                                }
                            },
                            onNavigateToSignUp = { nav.navigate(Routes.SIGNUP) { launchSingleTop = true } }
                        )
                    }

                    composable(Routes.SIGNUP) {
                        SignupScreen(
                            authVm = authVm,
                            onSignedUpAndLoggedIn = {
                                nav.navigate(Routes.HOME) {
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
                            onNavigateChangePassword = { nav.navigate(Routes.CHANGE_PASSWORD) }
                        )
                    }

                    composable(Routes.CHANGE_PASSWORD) {
                        ChangePasswordScreen(authVm = authVm, onDone = { nav.popBackStack() })
                    }

                    composable(Routes.THIRD_PARTY_CONNECTIONS) {
                        ThirdPartyConnectionsScreen(onBack = { nav.popBackStack() })
                    }

                    composable(Routes.DATA) {
                        DataSettingsScreen(onOpenMenstruationSettings = { nav.navigate(Routes.MENSTRUATION_SETTINGS) })
                    }

                    composable(Routes.MENSTRUATION_SETTINGS) {
                        MenstruationSettingsScreen(onBack = { nav.popBackStack() })
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

                    composable(Routes.TESTING) { TestingScreen(authVm = authVm) }
                    composable(Routes.TESTING_COMPLETE) { TestingScreenComplete(authVm = authVm) }
                    composable(Routes.LOCATION_DEBUG) { LocationDebugScreen() }
                }
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
        BottomItem(Routes.MIGRAINE, "Migraine", Icons.Outlined.Psychology),
        BottomItem(Routes.JOURNAL, "Journal", Icons.Outlined.History)
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
                    (item.route == Routes.MONITOR && currentRoute == Routes.MONITOR_CONFIG)

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
                    if (showBadge) {
                        BadgedBox(badge = { Badge { Text(journalBadgeCount.toString()) } }) {
                            Icon(item.icon, contentDescription = item.label)
                        }
                    } else {
                        Icon(item.icon, contentDescription = item.label)
                    }
                },
                label = { Text(item.label) },
                alwaysShowLabel = true
            )
        }
    }
}

