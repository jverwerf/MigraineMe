// app/src/main/java/com/migraineme/MonitorScreen.kt
package com.migraineme

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.ZoneId
import kotlin.math.abs

@Composable
fun MonitorScreen(
    weatherVm: WeatherViewModel = viewModel()
) {
    val ctx = LocalContext.current
    val ui by weatherVm.state.collectAsState()
    val cached by WeatherCache.flow(ctx).collectAsState(initial = null)
    var hasLocation by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 7-day history (hourly)
    var hist by remember { mutableStateOf<WeatherHistoryService.Series?>(null) }
    var histError by remember { mutableStateOf<String?>(null) }
    var loadingHist by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasLocation = granted
        if (granted) {
            scope.launch {
                val loc = requestAndLoadWeather(ctx, weatherVm)
                loc?.let { (lat, lon) ->
                    loadingHist = true
                    runCatching {
                        WeatherHistoryService.fetch(
                            lat, lon,
                            days = 7,
                            zoneId = ZoneId.systemDefault().id
                        )
                    }.onSuccess { hist = it; histError = null }
                        .onFailure { histError = it.message }
                    loadingHist = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        permLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()) // scrollable page
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Monitor", style = MaterialTheme.typography.titleLarge)

        if (ui.loading) Text("Loading latest weather…")
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        histError?.let { Text("History error: $it", color = MaterialTheme.colorScheme.error) }

        // Prefer live; fall back to cached
        val showPressure = ui.summary?.pressureHpa ?: cached?.pressureHpa
        val showPressureDelta24h = ui.summary?.pressureDelta24hHpa ?: cached?.pressureDelta24h
        val showTemp = ui.summary?.tempC ?: cached?.tempC
        val showTempDelta24h = ui.summary?.tempDelta24hC ?: cached?.tempDelta24h
        val showHum = ui.summary?.humidityPct ?: cached?.humidityPct
        val showHumMin24h = ui.summary?.humidityMin24hPct ?: cached?.humidityMin24h
        val showHumMax24h = ui.summary?.humidityMax24hPct ?: cached?.humidityMax24h

        // Compute Δ7d from history (latest − value 168 hours earlier), if available
        val delta7dPressure = hist?.pressureHpa?.let { sevenDayDelta(it) }
        val delta7dTemp = hist?.temperatureC?.let { sevenDayDelta(it) }
        val delta7dHum = hist?.humidityPct?.let { sevenDayDelta(it) }

        // Compute Humidity Δ24h as RANGE delta = max - min over last 24h
        val humRangeDelta24h: Double? = if (showHumMin24h != null && showHumMax24h != null) {
            showHumMax24h - showHumMin24h
        } else null

        // -------- Pressure card --------
        MetricCard(
            title = "Atmospheric Pressure",
            statRow = {
                StatPill(
                    title = "Today",
                    value = showPressure?.toInt()?.let { "$it hPa" } ?: "—",
                    modifier = Modifier.weight(1f)
                )
                StatPill(
                    title = "Δ 24h",
                    value = showPressureDelta24h?.let { deltaText(it, "hPa") } ?: "—",
                    modifier = Modifier.weight(1f)
                )
                StatPill(
                    title = "Δ 7d",
                    value = delta7dPressure?.let { deltaText(it, "hPa") } ?: "—",
                    modifier = Modifier.weight(1f)
                )
            }
        ) {
            hist?.let { series ->
                BandLineChart(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp), // compact, same across all cards
                    points = series.pressureHpa,
                    timeIso = series.timeIso
                )
            }
        }

        // -------- Temperature card --------
        MetricCard(
            title = "Temperature",
            statRow = {
                StatPill(
                    title = "Today",
                    value = showTemp?.let { "${round1(it)} °C" } ?: "—",
                    modifier = Modifier.weight(1f)
                )
                StatPill(
                    title = "Δ 24h",
                    value = showTempDelta24h?.let { deltaText(it, "°C") } ?: "—",
                    modifier = Modifier.weight(1f)
                )
                StatPill(
                    title = "Δ 7d",
                    value = delta7dTemp?.let { deltaText(it, "°C") } ?: "—",
                    modifier = Modifier.weight(1f)
                )
            }
        ) {
            hist?.let { series ->
                BandLineChart(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp),
                    points = series.temperatureC,
                    timeIso = series.timeIso
                )
            }
        }

        // -------- Humidity card --------
        MetricCard(
            title = "Humidity",
            statRow = {
                StatPill(
                    title = "Today",
                    value = showHum?.toInt()?.let { "$it%" } ?: "—",
                    modifier = Modifier.weight(1f)
                )
                // As requested: make 24h "range" a delta = max - min
                StatPill(
                    title = "Δ 24h",
                    value = humRangeDelta24h?.let { deltaText(it, "%") } ?: "—",
                    modifier = Modifier.weight(1f)
                )
                StatPill(
                    title = "Δ 7d",
                    value = delta7dHum?.let { deltaText(it, "%") } ?: "—",
                    modifier = Modifier.weight(1f)
                )
            }
        ) {
            hist?.let { series ->
                BandLineChart(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp),
                    points = series.humidityPct,
                    timeIso = series.timeIso
                )
            }
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    statRow: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                statRow() // exactly three StatPill with equal weight
            }
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun StatPill(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(44.dp) // uniform height
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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

private fun sevenDayDelta(series: List<Double>): Double? {
    if (series.isEmpty()) return null
    val lastIdx = series.lastIndex
    val window = 168 // 7 days * 24 hours
    val idx7d = lastIdx - window
    if (idx7d < 0) return null
    val now = series[lastIdx]
    val then = series[idx7d]
    return now - then
}

private fun round1(v: Double): String = String.format("%.1f", v)

private fun deltaText(delta: Double, unit: String): String {
    val sign = if (delta >= 0) "+" else "−"
    val mag = abs(delta)
    return "$sign${round1(mag)} $unit"
}

@SuppressLint("MissingPermission")
private suspend fun requestAndLoadWeather(ctx: Context, vm: WeatherViewModel): Pair<Double, Double>? {
    val fused = LocationServices.getFusedLocationProviderClient(ctx)
    val last = runCatching { fused.lastLocation.await() }.getOrNull()
    val loc = last ?: runCatching {
        fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
    }.getOrNull()

    val zone = ZoneId.systemDefault().id
    return if (loc != null) {
        LocationPrefs.save(ctx, loc.latitude, loc.longitude, zone)
        vm.load(loc.latitude, loc.longitude, zone)
        loc.latitude to loc.longitude
    } else {
        vm.load(51.5074, -0.1278, zone)
        null
    }
}
