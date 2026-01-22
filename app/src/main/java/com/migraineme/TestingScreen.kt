// FILE: app/src/main/java/com/migraineme/TestingScreen.kt
package com.migraineme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * PH-only debug screen.
 * NOW SHOWS:
 * - Stress Index (computed): stress_index_daily
 * - High HR Zones: time_in_high_hr_zones_daily
 */
@Composable
fun TestingScreen(
    authVm: AuthViewModel
) {
    val ctx = LocalContext.current
    val state by authVm.state.collectAsState()

    var stressIndex by remember {
        mutableStateOf<List<SupabasePhysicalHealthService.StressIndexDailyRead>>(emptyList())
    }
    var zones by remember {
        mutableStateOf<List<SupabasePhysicalHealthService.HighHrZonesDailyRead>>(emptyList())
    }

    val access = state.accessToken

    LaunchedEffect(access) {
        val token = access ?: return@LaunchedEffect
        val svc = SupabasePhysicalHealthService(ctx)

        stressIndex = svc.fetchStressIndexDaily(token)
        zones = svc.fetchHighHrDaily(token)
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
            "Stress Index (computed) — stress_index_daily",
            stressIndex.map { "${it.date}: value_pct=${it.value_pct}, source=${it.source ?: "-"}" }
        )

        DataSection(
            "High HR Zones — time_in_high_hr_zones_daily",
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
