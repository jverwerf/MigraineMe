package com.migraineme

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleDailyWeatherWork(this)
        setContent { MaterialTheme { AppRoot() } }
    }
}

private fun scheduleDailyWeatherWork(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val request = PeriodicWorkRequestBuilder<WeatherDailyWorker>(1, TimeUnit.DAYS)
        .setConstraints(constraints)
        .setInitialDelay(1, TimeUnit.HOURS)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "weather-daily-sync",
        ExistingPeriodicWorkPolicy.KEEP,
        request
    )
}

object Routes {
    const val HOME = "home"
    const val PROFILE = "profile"
    const val COMMUNITY = "community"
    const val INSIGHTS = "insights"
    const val MONITOR = "monitor"
    const val JOURNAL = "journal"
    // log flow
    const val MIGRAINE = "migraine"
    const val TRIGGERS = "triggers"
    const val ADJUST_TRIGGERS = "adjust_triggers"
    const val MEDICINES = "medicines"
    const val ADJUST_MEDICINES = "adjust_medicines"
    const val RELIEFS = "reliefs"
    const val ADJUST_RELIEFS = "adjust_reliefs"
    const val REVIEW = "review"
    // auth
    const val LOGIN = "login"
    const val SIGNUP = "signup"
    const val LOGOUT = "logout"
    // edit screens
    const val EDIT_MIGRAINE = "edit_migraine"
    const val EDIT_TRIGGER = "edit_trigger"
    const val EDIT_MEDICINE = "edit_medicine"
    const val EDIT_RELIEF = "edit_relief"
    // migraine prefs
    const val ADJUST_MIGRAINES = "adjust_migraines"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val authVm: AuthViewModel = viewModel()
    val logVm: LogViewModel = viewModel()
    val triggerVm: TriggerViewModel = viewModel()
    val medVm: MedicineViewModel = viewModel()
    val reliefVm: ReliefViewModel = viewModel()
    val migraineVm: MigraineViewModel = viewModel() // NEW

    // Journal attention badge
    val journal by logVm.journal.collectAsState()
    val attentionCount = remember(journal) { journal.count { needsAttention(it) } }

    data class DrawerItem(val title: String, val route: String, val icon: ImageVector)
    val drawerItems = listOf(
        DrawerItem("Profile", Routes.PROFILE, Icons.Outlined.Person),
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
                        icon = { Icon(imageVector = item.icon, contentDescription = item.title) }
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
                                else -> ""
                            }
                        )
                    },
                    navigationIcon = {
                        if (current != Routes.LOGIN && current != Routes.SIGNUP) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(imageVector = Icons.Outlined.Settings, contentDescription = "Settings")
                            }
                        }
                    },
                    actions = {
                        if (current != Routes.LOGIN && current != Routes.SIGNUP) {
                            IconButton(onClick = { nav.navigate(Routes.COMMUNITY) }) {
                                Icon(imageVector = Icons.Outlined.Groups, contentDescription = "Community")
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
                composable(Routes.JOURNAL) { JournalScreen(navController = nav, authVm = authVm, vm = logVm) }

                // Log flow
                composable(Routes.MIGRAINE) { LogHomeScreen(navController = nav, authVm = authVm, vm = logVm) }
                composable(Routes.TRIGGERS) {
                    TriggersScreen(
                        navController = nav,
                        vm = triggerVm,
                        authVm = authVm,
                        logVm = logVm
                    )
                }
                composable(Routes.ADJUST_TRIGGERS) {
                    AdjustTriggersScreen(
                        navController = nav,
                        vm = triggerVm,
                        authVm = authVm
                    )
                }

                // Medicines
                composable(Routes.MEDICINES) {
                    MedicinesScreen(
                        navController = nav,
                        vm = medVm,
                        authVm = authVm,
                        logVm = logVm
                    )
                }
                composable(Routes.ADJUST_MEDICINES) {
                    AdjustMedicinesScreen(
                        navController = nav,
                        vm = medVm,
                        authVm = authVm
                    )
                }

                // Reliefs
                composable(Routes.RELIEFS) {
                    ReliefsScreen(
                        navController = nav,
                        vm = reliefVm,
                        authVm = authVm,
                        logVm = logVm
                    )
                }
                composable(Routes.ADJUST_RELIEFS) {
                    AdjustReliefsScreen(
                        navController = nav,
                        vm = reliefVm,
                        authVm = authVm
                    )
                }

                composable(Routes.REVIEW) { ReviewLogScreen(navController = nav, authVm = authVm, vm = logVm) }

                // Edit screens
                composable("${Routes.EDIT_MIGRAINE}/{id}") { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("id") ?: return@composable
                    EditMigraineScreen(navController = nav, authVm = authVm, vm = logVm, id = id)
                }
                composable("${Routes.EDIT_TRIGGER}/{id}") { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("id") ?: return@composable
                    EditTriggerScreen(navController = nav, authVm = authVm, vm = logVm, id = id)
                }
                composable("${Routes.EDIT_MEDICINE}/{id}") { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("id") ?: return@composable
                    EditMedicineScreen(navController = nav, authVm = authVm, vm = logVm, id = id)
                }
                composable("${Routes.EDIT_RELIEF}/{id}") { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("id") ?: return@composable
                    EditReliefScreen(navController = nav, authVm = authVm, vm = logVm, id = id)
                }

                // Adjust migraines (now passes MigraineViewModel)
                composable(Routes.ADJUST_MIGRAINES) {
                    AdjustMigrainesScreen(
                        navController = nav,
                        vm = migraineVm,
                        authVm = authVm
                    )
                }

                // Auth
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
                composable(Routes.PROFILE) { ProfileScreen(authVm = authVm) }
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
        }
    }
}

/** Same logic as JournalScreen’s top-right icon */
private fun needsAttention(ev: JournalEvent): Boolean {
    return when (ev) {
        is JournalEvent.Migraine -> ev.row.startAt.isNullOrBlank() || ev.row.endAt.isNullOrBlank() || ev.row.severity == null
        is JournalEvent.Trigger -> ev.row.startAt.isNullOrBlank()
        is JournalEvent.Medicine -> ev.row.amount.isNullOrBlank() || ev.row.startAt.isNullOrBlank()
        is JournalEvent.Relief -> ev.row.durationMinutes == null || ev.row.startAt.isNullOrBlank()
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
