package com.migraineme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/**
 * PH-only debug screen.
 * NOW ONLY SHOWS: High HR Zones
 * (Recovery, RHR, HRV, Skin Temp, SPO2 were moved to TestingScreenComplete)
 */
@Composable
fun TestingScreen(
    authVm: AuthViewModel
) {
    val ctx = LocalContext.current
    val state by authVm.state.collectAsState()

    // ONLY HR ZONES LEFT
    var zones by remember { mutableStateOf(emptyList<SupabasePhysicalHealthService.HighHrZonesDailyRead>()) }

    val access = state.accessToken

    LaunchedEffect(access) {
        if (access == null) return@LaunchedEffect

        val svc = SupabasePhysicalHealthService(ctx)
        zones = svc.fetchHighHrDaily(access)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start
    ) {
        Text("Physical Health Debug (Testing)", textAlign = TextAlign.Start)
        Spacer(Modifier.height(20.dp))

        DataSection(
            "High HR Zones",
            zones.map {
                "${it.date}: total=${it.value_minutes}, z4=${it.zone_four_minutes}, z5=${it.zone_five_minutes}, z6=${it.zone_six_minutes}"
            }
        )
    }
}

@Composable
private fun DataSection(title: String, rows: List<String>) {
    Text(title)
    Spacer(Modifier.height(6.dp))
    if (rows.isEmpty()) {
        Text("  (no data)")
    } else {
        for (line in rows) {
            Text("  $line")
        }
    }
    Divider(Modifier.padding(vertical = 12.dp))
}
