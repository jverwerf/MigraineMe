// FILE: app/src/main/java/com/migraineme/MainActivity.kt
package com.migraineme

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val PROFILE = "profile"
    const val DATA = "data"
    const val COMMUNITY = "community"
    const val INSIGHTS = "insights"
    const val MONITOR = "monitor"
    const val JOURNAL = "journal"

    const val MIGRAINE = "migraine"
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

    const val THIRD_PARTY_CONNECTIONS = "third_party_connections"

    const val CHANGE_PASSWORD = "change_password"
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // IMPORTANT: MainActivity should ONLY persist callback URIs.
        // Do NOT call WhoopAuthService.completeAuth() here.
        handleWhoopOAuthIntent(intent)
        handleSupabaseOAuthIntent(intent)

        setContent { MaterialTheme { AppRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // IMPORTANT: persist callback URIs on every new intent.
        handleWhoopOAuthIntent(intent)
        handleSupabaseOAuthIntent(intent)
    }

    private fun handleWhoopOAuthIntent(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data?.scheme == "migraineme" && data.host == "whoop" && data.path == "/callback") {

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
                    // Completion happens in ThirdPartyConnectionsScreen (it calls WhoopAuthService().completeAuth(context))
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

    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext

    val authVm: AuthViewModel = viewModel()
    val logVm: LogViewModel = viewModel()
    val triggerVm: TriggerViewModel = viewModel()
    val medVm: MedicineViewModel = viewModel()
    val reliefVm: ReliefViewModel = viewModel()
    val migraineVm: MigraineViewModel = viewModel()

    val authState by authVm.state.collectAsState()
    val token = authState.accessToken
    var preloaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val persistedToken = SessionStore.getValidAccessToken(appCtx)

        if (!persistedToken.isNullOrBlank() && authState.accessToken.isNullOrBlank()) {
            var persistedUserId = SessionStore.readUserId(appCtx)

            if (persistedUserId.isNullOrBlank()) {
                persistedUserId = JwtUtils.extractUserIdFromAccessToken(persistedToken)
                if (!persistedUserId.isNullOrBlank()) {
                    SessionStore.saveUserId(appCtx, persistedUserId)
                }
            }

            if (!persistedUserId.isNullOrBlank()) {
                authVm.setSession(persistedToken, persistedUserId)
            }
        }
    }

    LaunchedEffect(token) {
        if (token.isNullOrBlank()) {
            preloaded = false
        } else if (!preloaded) {
            logVm.loadJournal(token)
            preloaded = true
        }
    }

    val journal by logVm.journal.collectAsState()
    val attentionCount = remember(journal) { journal.count { needsAttention(it) } }

    data class DrawerItem(val title: String, val route: String, val icon: ImageVector)

    val drawerItems = listOf(
        DrawerItem("Profile", Routes.PROFILE, Icons.Outlined.Person),
        DrawerItem("Connections", Routes.THIRD_PARTY_CONNECTIONS, Icons.Outlined.Link),
        DrawerItem("Data", Routes.DATA, Icons.Outlined.Storage),
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

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            when (current) {
                                Routes.MONITOR -> "Monitor"
                                Routes.INSIGHTS -> "Insights"
                                Routes.HOME -> "Home"
                                Routes.MIGRAINE -> "Migraine"
                                Routes.COMMUNITY -> "Community"
                                Routes.JOURNAL -> "Journal"
                                Routes.LOGIN -> "Sign in"
                                Routes.SIGNUP -> "Create account"
                                Routes.PROFILE -> "Profile"
                                Routes.DATA -> "Data"
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
                composable(Routes.MONITOR) { MonitorScreen() }
                composable(Routes.INSIGHTS) { InsightsScreen() }
                composable(Routes.HOME) {
                    HomeScreenRoot(
                        onLogout = { nav.navigate(Routes.LOGOUT) { launchSingleTop = true } },
                        onNavigateToMigraine = { nav.navigate(Routes.MIGRAINE) },
                        authVm = authVm,
                        logVm = logVm
                    )
                }
                composable(Routes.COMMUNITY) { CommunityScreen() }
                composable(Routes.JOURNAL) {
                    JournalScreen(navController = nav, authVm = authVm, vm = logVm)
                }

                composable(Routes.MIGRAINE) {
                    LogHomeScreen(navController = nav, authVm = authVm, vm = logVm)
                }

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
                    ChangePasswordScreen(
                        authVm = authVm,
                        onDone = { nav.popBackStack() }
                    )
                }

                composable(Routes.THIRD_PARTY_CONNECTIONS) {
                    ThirdPartyConnectionsScreen(onBack = { nav.popBackStack() })
                }

                composable(Routes.DATA) { DataSettingsScreen() }

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

    NavigationBar {
        val backStack by nav.currentBackStackEntryAsState()
        val currentRoute = backStack?.destination?.route

        items.forEach { item ->
            val showBadge = item.route == Routes.JOURNAL && journalBadgeCount > 0
            NavigationBarItem(
                selected = currentRoute == item.route,
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
