// app/src/main/java/com/migraineme/MonitorScreen.kt
package com.migraineme

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.ZoneId
import kotlin.math.abs

@Composable
fun MonitorScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var summary by remember { mutableStateOf<WeatherService.Summary?>(null) }
    var hist by remember { mutableStateOf<WeatherHistoryService.Series?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        scope.launch {
            loadWeatherNow(
                ctx = ctx,
                fallback = !granted,
                onSummary = { summary = it },
                onHist = { hist = it },
                onError = { error = it },
                setLoading = { loading = it }
            )
        }
    }

    LaunchedEffect(Unit) {
        permLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (loading) Text("Loading weather…")
        error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }

        val p = summary?.pressureHpa
        val pD24 = summary?.pressureDelta24hHpa
        val t = summary?.tempC
        val tD24 = summary?.tempDelta24hC
        val h = summary?.humidityPct
        val hMin = summary?.humidityMin24hPct
        val hMax = summary?.humidityMax24hPct
        val hRangeDelta24 = if (hMin != null && hMax != null) (hMax - hMin) else null

        val d7p = hist?.pressureHpa?.let { sevenDayDelta(it) }
        val d7t = hist?.temperatureC?.let { sevenDayDelta(it) }
        val d7h = hist?.humidityPct?.let { sevenDayDelta(it) }

        SectionCard(title = "Atmospheric Pressure") {
            StatRowOneLine(
                aTitle = "Today", aValue = p?.toInt()?.let { "$it hPa" } ?: "—",
                bTitle = "Δ 24h", bValue = pD24?.let { deltaText(it, "hPa") } ?: "—",
                cTitle = "Δ 7d", cValue = d7p?.let { deltaText(it, "hPa") } ?: "—"
            )
            Spacer(Modifier.height(6.dp))
            hist?.let { s ->
                BandLineChart(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp),
                    points = s.pressureHpa,
                    timeIso = s.timeIso
                )
            }
        }

        SectionCard(title = "Temperature") {
            StatRowOneLine(
                aTitle = "Today", aValue = t?.let { "${round1(it)} °C" } ?: "—",
                bTitle = "Δ 24h", bValue = tD24?.let { deltaText(it, "°C") } ?: "—",
                cTitle = "Δ 7d", cValue = d7t?.let { deltaText(it, "°C") } ?: "—"
            )
            Spacer(Modifier.height(6.dp))
            hist?.let { s ->
                BandLineChart(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp),
                    points = s.temperatureC,
                    timeIso = s.timeIso
                )
            }
        }

        SectionCard(title = "Humidity") {
            StatRowOneLine(
                aTitle = "Today", aValue = h?.toInt()?.let { "$it%" } ?: "—",
                bTitle = "Δ 24h", bValue = hRangeDelta24?.let { deltaText(it, "%") } ?: "—",
                cTitle = "Δ 7d", cValue = d7h?.let { deltaText(it, "%") } ?: "—"
            )
            Spacer(Modifier.height(6.dp))
            hist?.let { s ->
                BandLineChart(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp),
                    points = s.humidityPct,
                    timeIso = s.timeIso
                )
            }
        }
    }
}

/* --- LogScreen-like stat row (kept), but SectionCard now shared from UiKitAdditions --- */

@Composable
private fun StatRowOneLine(
    aTitle: String, aValue: String,
    bTitle: String, bValue: String,
    cTitle: String, cValue: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatPill(title = aTitle, value = aValue, modifier = Modifier.weight(1f))
        StatPill(title = bTitle, value = bValue, modifier = Modifier.weight(1f))
        StatPill(title = cTitle, value = cValue, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatPill(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/* --- Weather helpers --- */

private fun sevenDayDelta(series: List<Double>): Double? {
    if (series.isEmpty()) return null
    val lastIdx = series.lastIndex
    val idx7d = lastIdx - 168
    if (idx7d < 0) return null
    return series[lastIdx] - series[idx7d]
}

private fun round1(v: Double): String = String.format("%.1f", v)
private fun deltaText(delta: Double, unit: String): String {
    val sign = if (delta >= 0) "+" else "−"
    val mag = abs(delta)
    return "$sign${round1(mag)} $unit"
}

@SuppressLint("MissingPermission")
private suspend fun loadWeatherNow(
    ctx: Context,
    fallback: Boolean,
    onSummary: (WeatherService.Summary) -> Unit,
    onHist: (WeatherHistoryService.Series) -> Unit,
    onError: (String) -> Unit,
    setLoading: (Boolean) -> Unit
) {
    setLoading(true)
    try {
        val (lat, lon) = if (!fallback) {
            val fused = LocationServices.getFusedLocationProviderClient(ctx)
            val last = runCatching { fused.lastLocation.await() }.getOrNull()
            val loc = last ?: runCatching {
                fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
            }.getOrNull()
            if (loc != null) loc.latitude to loc.longitude else 51.5074 to -0.1278
        } else 51.5074 to -0.1278

        val zone = ZoneId.systemDefault().id
        val s = WeatherService.getSummary(lat, lon, zone)
        onSummary(s)
        val h = WeatherHistoryService.fetch(lat, lon, days = 7, zoneId = zone)
        onHist(h)
    } catch (e: Exception) {
        onError(e.message ?: "Unknown error")
    } finally {
        setLoading(false)
    }
}
