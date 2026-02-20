package com.migraineme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TriggersSettingsScreen(
    navController: NavController,
    authVm: AuthViewModel = viewModel()
) {
    val ctx = LocalContext.current.applicationContext
    val authState by authVm.state.collectAsState()
    val scrollState = rememberScrollState()

    // Load trigger stats
    var triggerCount by remember { mutableStateOf(0) }
    var autoTriggersEnabled by remember { mutableStateOf(0) }

    LaunchedEffect(authState.accessToken) {
        val token = authState.accessToken
        if (token.isNullOrBlank()) return@LaunchedEffect

        withContext(Dispatchers.IO) {
            // Count user's triggers
            try {
                val edge = EdgeFunctionsService()
                val triggerSettings = edge.getTriggerSettings(ctx)
                autoTriggersEnabled = triggerSettings.count { it.enabled }
            } catch (_: Exception) {}
        }
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
            // Customize Triggers Card (similar to Configure Monitor in MonitorScreen)
            HeroCard(
                modifier = Modifier.clickable { navController.navigate(Routes.CUSTOMIZE_TRIGGERS) }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = "Customize",
                        tint = AppTheme.AccentPurple,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Customize Triggers",
                            color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            "Configure automatic trigger detection",
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "→",
                        color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            // Summary Card
            HeroCard {
                Text(
                    "About Triggers",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Triggers are events or conditions that may contribute to your migraines. " +
                    "You can log triggers manually when recording a migraine, or enable automatic " +
                    "trigger detection based on your health data.",
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Automatic triggers are checked daily at 9 AM your local time and will " +
                    "be added to your journal when conditions are met.",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

