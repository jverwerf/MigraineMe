// FILE: app/src/main/java/com/migraineme/TestingScreen.kt
package com.migraineme

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * PH-only debug screen.
 * NOW SHOWS:
 * - Stress Index (computed): stress_index_daily
 * - Noise Index (computed): ambient_noise_index_daily
 * - High HR Zones: time_in_high_hr_zones_daily
 * - Screen Time: screen_time_daily
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
    var noiseIndex by remember {
        mutableStateOf<List<SupabaseNoiseIndexService.AmbientNoiseIndexDailyRead>>(emptyList())
    }
    var zones by remember {
        mutableStateOf<List<SupabasePhysicalHealthService.HighHrZonesDailyRead>>(emptyList())
    }
    var screenTimeDaily by remember {
        mutableStateOf<List<SupabaseScreenTimeService.ScreenTimeDailyRead>>(emptyList())
    }

    val access = state.accessToken

    LaunchedEffect(access) {
        val token = access ?: return@LaunchedEffect
        val phSvc = SupabasePhysicalHealthService(ctx)
        val noiseSvc = SupabaseNoiseIndexService(ctx)
        val screenTimeSvc = SupabaseScreenTimeService(ctx)

        stressIndex = phSvc.fetchStressIndexDaily(token)
        noiseIndex = noiseSvc.fetchAmbientNoiseIndexDaily(token)
        zones = phSvc.fetchHighHrDaily(token)
        screenTimeDaily = screenTimeSvc.fetchScreenTimeDaily(token)
    }

    var buttonStatus by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start
    ) {
        Text("Physical Health Debug (Testing)", textAlign = TextAlign.Start)
        Spacer(Modifier.height(20.dp))

        // Permission status
        val hasPermission = remember { ScreenTimeCollector.hasUsageStatsPermission(ctx) }
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (hasPermission)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (hasPermission) "✓ Screen Time Permission Granted" else "✗ Screen Time Permission Required",
                    style = MaterialTheme.typography.titleSmall
                )
                if (!hasPermission) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Enable in Settings → Apps → Special Access → Usage Access",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Manual trigger button for screen time worker
        Button(
            onClick = {
                if (!ScreenTimeCollector.hasUsageStatsPermission(ctx)) {
                    buttonStatus = "❌ Permission not granted - enable in Settings first"
                } else {
                    buttonStatus = "🔄 Triggering worker... Check Logcat for 'ScreenTimeDailySync'"
                    ScreenTimeDailySyncWorker.runOnceNow(ctx)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Trigger Screen Time Collection Now")
        }

        if (buttonStatus.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                buttonStatus,
                style = MaterialTheme.typography.bodySmall,
                color = if (buttonStatus.startsWith("❌"))
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(16.dp))

        DataSection(
            "Stress Index (computed) — stress_index_daily",
            stressIndex.map { "${it.date}: value=${it.value}, computed_at=${it.computed_at ?: "-"}" }
        )

        DataSection(
            "Noise Index (computed) — ambient_noise_index_daily",
            noiseIndex.map {
                "${it.date}: value_index_pct=${it.value_index_pct}, samples=${it.samples_count ?: 0}, source=${it.source ?: "-"}"
            }
        )

        DataSection(
            "High HR Zones — time_in_high_hr_zones_daily",
            zones.map {
                "${it.date}: total=${it.value_minutes}, z4=${it.zone_four_minutes}, z5=${it.zone_five_minutes}, z6=${it.zone_six_minutes}"
            }
        )

        DataSection(
            "Screen Time — screen_time_daily",
            screenTimeDaily.map {
                "${it.date}: ${it.total_hours}h, source=${it.source ?: "android"}"
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

/**
 * Small read-only helper for ambient_noise_index_daily used by TestingScreen.
 * Keeps the same REST auth pattern used elsewhere:
 * - Authorization: Bearer <access>
 * - apikey: SUPABASE_ANON_KEY
 */
private class SupabaseNoiseIndexService(context: Context) {

    private val supabaseUrl = BuildConfig.SUPABASE_URL
    private val supabaseKey = BuildConfig.SUPABASE_ANON_KEY

    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    @Serializable
    data class AmbientNoiseIndexDailyRead(
        val date: String,
        @SerialName("value_index_pct") val value_index_pct: Double,
        @SerialName("samples_count") val samples_count: Int? = null,
        val source: String? = null,
        @SerialName("computed_at") val computed_at: String? = null
    )

    suspend fun fetchAmbientNoiseIndexDaily(access: String, days: Int = 14): List<AmbientNoiseIndexDailyRead> {
        val resp = client.get("$supabaseUrl/rest/v1/ambient_noise_index_daily") {
            header(HttpHeaders.Authorization, "Bearer $access")
            header("apikey", supabaseKey)
            parameter("select", "date,value_index_pct,samples_count,source,computed_at")
            parameter("order", "date.desc")
            parameter("limit", days.toString())
        }
        if (!resp.status.isSuccess()) return emptyList()
        return runCatching { resp.body<List<AmbientNoiseIndexDailyRead>>() }.getOrDefault(emptyList())
    }
}

/**
 * Small read-only helper for screen_time_daily used by TestingScreen.
 */
private class SupabaseScreenTimeService(context: Context) {

    private val supabaseUrl = BuildConfig.SUPABASE_URL
    private val supabaseKey = BuildConfig.SUPABASE_ANON_KEY

    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    @Serializable
    data class ScreenTimeDailyRead(
        val date: String,
        @SerialName("total_hours") val total_hours: Double,
        val source: String? = null,
        @SerialName("computed_at") val computed_at: String? = null
    )

    suspend fun fetchScreenTimeDaily(access: String, days: Int = 14): List<ScreenTimeDailyRead> {
        val resp = client.get("$supabaseUrl/rest/v1/screen_time_daily") {
            header(HttpHeaders.Authorization, "Bearer $access")
            header("apikey", supabaseKey)
            parameter("select", "date,total_hours,source,computed_at")
            parameter("order", "date.desc")
            parameter("limit", days.toString())
        }
        if (!resp.status.isSuccess()) return emptyList()
        return runCatching { resp.body<List<ScreenTimeDailyRead>>() }.getOrDefault(emptyList())
    }
}