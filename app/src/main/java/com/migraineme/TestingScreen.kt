// FILE: app/src/main/java/com/migraineme/TestingScreen.kt
package com.migraineme

import android.content.Context
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

    val access = state.accessToken

    LaunchedEffect(access) {
        val token = access ?: return@LaunchedEffect
        val phSvc = SupabasePhysicalHealthService(ctx)
        val noiseSvc = SupabaseNoiseIndexService(ctx)

        stressIndex = phSvc.fetchStressIndexDaily(token)
        noiseIndex = noiseSvc.fetchAmbientNoiseIndexDaily(token)
        zones = phSvc.fetchHighHrDaily(token)
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
