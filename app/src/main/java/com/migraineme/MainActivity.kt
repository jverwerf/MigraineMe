package com.migraineme

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AppRoot() } }
    }
}

object Routes {
    const val MONITOR = "monitor"
    const val INSIGHTS = "insights"
    const val HOME = "home"
    const val COMMUNITY = "community"

    const val LOG_HOME = "log_home"
    const val LOG_FULL = LOG_HOME
    const val LOG_MED = LOG_HOME
    const val LOG_RELIEF = LOG_HOME
    const val LOG_HISTORY = LOG_HOME

    const val LOGIN = "login"
    const val SIGNUP = "signup"

    const val PROFILE = "profile"
    const val TRIGGERS = "triggers"
    const val LOGOUT = "logout"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Shared VMs
    val authVm: AuthViewModel = viewModel()
    val logVm: LogViewModel = viewModel()

    val authState by authVm.state.collectAsState()
    LaunchedEffect(authState.accessToken) {
        authState.accessToken?.let {
            logVm.preloadForNew(it)
            logVm.loadHistory(it)
        }
    }

    data class DrawerItem(val title: String, val route: String, val icon: ImageVector)
    val drawerItems = listOf(
        DrawerItem("Profile", Routes.PROFILE, Icons.Outlined.Person),
        DrawerItem("Adjust triggers", Routes.TRIGGERS, Icons.Outlined.Tune),
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
                        icon = { Icon(imageVector = item.icon, contentDescription = null) }
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
                                Routes.LOG_HOME -> "Log"
                                Routes.COMMUNITY -> "Community"
                                Routes.LOGIN -> "Sign in"
                                Routes.SIGNUP -> "Create account"
                                Routes.PROFILE -> "Profile"
                                Routes.TRIGGERS -> "Adjust triggers"
                                Routes.LOGOUT -> "Logout"
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
                    }
                )
            },
            bottomBar = {
                if (current != Routes.LOGIN && current != Routes.SIGNUP) {
                    BottomBar(nav, logVm)
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
                        onNavigateToMigraine = { nav.navigate(Routes.LOG_HOME) },
                        authVm = authVm,
                        logVm = logVm
                    )
                }
                composable(Routes.COMMUNITY) { CommunityScreen() }

                composable(Routes.LOG_HOME) {
                    LogHomeScreen(navController = nav, authVm = authVm, vm = logVm)
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

                composable(Routes.PROFILE) { ProfileScreen(authVm = authVm) }
                composable(Routes.TRIGGERS) { AdjustTriggersScreen() }
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

@Composable
private fun BottomBar(
    nav: androidx.navigation.NavHostController,
    logVm: LogViewModel
) {
    data class BottomItem(val route: String, val label: String, val icon: ImageVector)
    val items = listOf(
        BottomItem(Routes.MONITOR, "Monitor", Icons.Outlined.Timeline),
        BottomItem(Routes.INSIGHTS, "Insights", Icons.Outlined.BarChart),
        BottomItem(Routes.HOME, "Home", Icons.Outlined.Home),
        BottomItem(Routes.LOG_HOME, "Log", Icons.Outlined.NoteAlt),
        BottomItem(Routes.COMMUNITY, "Community", Icons.Outlined.Groups)
    )
    NavigationBar {
        val backStack by nav.currentBackStackEntryAsState()
        val currentRoute = backStack?.destination?.route
        val logState by logVm.state.collectAsState()
        val totalAlerts = logState.missingTimeCount + logState.missingAmountCount + logState.missingDurationCount
        items.forEach { item ->
            val withBadge = item.route == Routes.LOG_HOME && totalAlerts > 0
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
                    if (withBadge) {
                        BadgedBox(badge = { Badge { Text("$totalAlerts") } }) {
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
