package com.migraineme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController

@Composable
fun InsightsContextScreen(
    navController: NavHostController,
    vm: InsightsViewModel = viewModel()
) {
    val contextItems by vm.contextItems.collectAsState()
    val migraines by vm.migraines.collectAsState()
    val overallAvgSeverity = remember(migraines) {
        val severities = migraines.mapNotNull { it.severity }
        if (severities.isEmpty()) 5f else severities.average().toFloat()
    }

    val scrollState = rememberScrollState()

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Outlined.ArrowBack, "Back", tint = AppTheme.BodyTextColor)
                }
            }

            ContextCard(contextItems, overallAvgSeverity)
        }
    }
}
