package com.migraineme

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun InsightsWeatherPanel(
    vm: CityWeatherViewModel = viewModel()
) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsState()

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        vm.loadNearestAndDaily(getLastKnownLocationPreferGps(ctx))
    }

    LaunchedEffect(Unit) {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            vm.loadNearestAndDaily(getLastKnownLocationPreferGps(ctx))
        } else {
            permLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Local weather", style = MaterialTheme.typography.titleMedium)

        when {
            state.loading -> {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            }
            state.error != null -> {
                Text(
                    state.error ?: "Error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            else -> {
                val city = state.nearestCity
                if (city != null) {
                    Text(
                        "Nearest: ${city.label}" + (city.timezone?.let { "  •  $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text("Nearest city unknown", style = MaterialTheme.typography.bodyMedium)
                }

                Divider()

                HeaderRow()

                Divider()

                state.days.forEach { d ->
                    DataRow(
                        day = d.day,
                        temp = d.tempMeanC,
                        pressure = d.pressureMeanHpa,
                        humidity = d.humidityMeanPct
                    )
                    Divider()
                }

                if (state.days.isEmpty()) {
                    Text("No daily data", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * Prefer GPS if available. Otherwise return the most recent of Network/Passive.
 * This avoids Mountain View or Brighton from stale network fixes.
 */
private fun getLastKnownLocationPreferGps(ctx: Context): Location? {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val gps = try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: SecurityException) { null }
    if (gps != null) return gps

    var best: Location? = null
    for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
        val loc = try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { null }
        if (loc != null && (best == null || loc.time > best!!.time)) {
            best = loc
        }
    }
    return best
}

@Composable
private fun HeaderRow() {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
        Text("Day", Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium)
        Text("Temp °C", Modifier.weight(0.8f), style = MaterialTheme.typography.labelMedium)
        Text("Pressure hPa", Modifier.weight(1.0f), style = MaterialTheme.typography.labelMedium)
        Text("Humidity %", Modifier.weight(0.8f), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun DataRow(day: String, temp: Double?, pressure: Double?, humidity: Double?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(day, Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
        Text(temp?.let { String.format("%.1f", it) } ?: "—", Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall)
        Text(pressure?.let { String.format("%.0f", it) } ?: "—", Modifier.weight(1.0f), style = MaterialTheme.typography.bodySmall)
        Text(humidity?.let { String.format("%.0f", it) } ?: "—", Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall)
    }
}
