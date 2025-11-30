package com.migraineme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Divider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/**
 * PH-only debug screen.
 * Loads PH tables from Supabase → shows rows in a table.
 * No sleep. No buttons. Read-only.
 */
@Composable
fun TestingScreen(
    authVm: AuthViewModel
) {
    val ctx = LocalContext.current
    val state by authVm.state.collectAsState()

    var recovery by remember { mutableStateOf(emptyList<SupabasePhysicalHealthService.RecoveryScoreDailyRead>()) }
    var rhr by remember { mutableStateOf(emptyList<SupabasePhysicalHealthService.RestingHrDailyRead>()) }
    var hrv by remember { mutableStateOf(emptyList<SupabasePhysicalHealthService.HrvDailyRead>()) }
    var skin by remember { mutableStateOf(emptyList<SupabasePhysicalHealthService.SkinTempDailyRead>()) }
    var spo2 by remember { mutableStateOf(emptyList<SupabasePhysicalHealthService.Spo2DailyRead>()) }
    var zones by remember { mutableStateOf(emptyList<SupabasePhysicalHealthService.HighHrZonesDailyRead>()) }

    val access = state.accessToken

    LaunchedEffect(access) {
        if (access == null) return@LaunchedEffect

        val svc = SupabasePhysicalHealthService(ctx)

        recovery = svc.fetchRecoveryScoreDaily(access)
        rhr = svc.fetchRestingHrDaily(access)
        hrv = svc.fetchHrvDaily(access)
        skin = svc.fetchSkinTempDaily(access)
        spo2 = svc.fetchSpo2Daily(access)
        zones = svc.fetchHighHrDaily(access)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start
    ) {
        Text("Physical Health Debug", textAlign = TextAlign.Start)

        Spacer(Modifier.height(20.dp))

        DataSection("Recovery Score", recovery.map { "${it.date}: ${it.value_pct}" })
        DataSection("Resting HR", rhr.map { "${it.date}: ${it.value_bpm}" })
        DataSection("HRV (RMSSD)", hrv.map { "${it.date}: ${it.value_rmssd_ms}" })
        DataSection("Skin Temp (°C)", skin.map { "${it.date}: ${it.value_celsius}" })
        DataSection("SPO2 (%)", spo2.map { "${it.date}: ${it.value_pct}" })
        DataSection("High HR Zones", zones.map { "${it.date}: total=${it.value_minutes}, z4=${it.zone_four_minutes}, z5=${it.zone_five_minutes}, z6=${it.zone_six_minutes}" })
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
