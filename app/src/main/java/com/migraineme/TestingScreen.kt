// FILE: app/src/main/java/com/migraineme/TestingScreen.kt
package com.migraineme

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun TestingScreen(
    authVm: AuthViewModel,
    vm: TestingViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val ctx: Context = LocalContext.current.applicationContext
    val auth by authVm.state.collectAsState()

    LaunchedEffect(auth.accessToken) {
        val token = auth.accessToken
        if (!token.isNullOrBlank()) {
            vm.load(ctx, token)
        } else {
            vm.clear()
        }
    }

    val scroll = rememberScrollState()

    // App data
    val migraines by vm.migraines.collectAsState()
    val reliefs by vm.reliefs.collectAsState()
    val triggers by vm.triggers.collectAsState()
    val medicines by vm.medicines.collectAsState()

    // Wearable (WHOOP)
    val sleepDuration by vm.sleepDuration.collectAsState()
    val fellAsleep by vm.fellAsleep.collectAsState()
    val wokeUp by vm.wokeUp.collectAsState()
    val sleepDisturbances by vm.sleepDisturbances.collectAsState()
    val sleepStages by vm.sleepStages.collectAsState()
    val sleepScore by vm.sleepScore.collectAsState()
    val sleepEfficiency by vm.sleepEfficiency.collectAsState()

    // CellPhone
    val locations by vm.userLocations.collectAsState()

    // Weather per location point
    val wxPerLoc by vm.weatherAtLocations.collectAsState()

    // Local nudge for status recompute (not passed into SleepSyncStatus anymore)
    val (statusTick, bumpStatus) = remember {
        val s = mutableIntStateOf(0)
        Pair(s) { s.intValue++ }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(12.dp)
    ) {
        Text("Testing (raw tables)", style = MaterialTheme.typography.headlineMedium)

        Spacer(Modifier.padding(6.dp))
        SleepSyncStatus(
            accessToken = auth.accessToken,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val token = auth.accessToken ?: return@Button
                    vm.launchBackfillThenToday(ctx, token) {
                        vm.load(ctx, token)
                        bumpStatus()
                    }
                }
            ) { Text("Backfill now") }
            Button(onClick = { bumpStatus() }) { Text("Refresh status") }
        }

        Spacer(Modifier.padding(6.dp))

        // ----- Wearable (WHOOP) -----
        SectionHeader("Wearable")
        TableCard(title = "sleep_duration_daily") {
            RowHeader("date", "value_hours")
            sleepDuration.take(180).forEach { r ->
                RowLine(r.date, String.format("%.2f", r.value_hours))
            }
        }
        Spacer(Modifier.padding(6.dp))
        TableCard(title = "sleep_score_daily") {
            RowHeader("date", "value_pct")
            sleepScore.take(180).forEach { r -> RowLine(r.date, String.format("%.0f", r.value_pct)) }
        }
        Spacer(Modifier.padding(6.dp))
        TableCard(title = "sleep_efficiency_daily") {
            RowHeader("date", "value_pct")
            sleepEfficiency.take(180).forEach { r -> RowLine(r.date, String.format("%.0f", r.value_pct)) }
        }
        Spacer(Modifier.padding(6.dp))
        TableCard(title = "fell_asleep_time_daily") {
            RowHeader("date", "value_at")
            fellAsleep.take(180).forEach { r -> RowLine(r.date, r.value_at) }
        }
        Spacer(Modifier.padding(6.dp))
        TableCard(title = "woke_up_time_daily") {
            RowHeader("date", "value_at")
            wokeUp.take(180).forEach { r -> RowLine(r.date, r.value_at) }
        }
        Spacer(Modifier.padding(6.dp))
        TableCard(title = "sleep_disturbances_daily") {
            RowHeader("date", "value_count")
            sleepDisturbances.take(180).forEach { r -> RowLine(r.date, r.value_count.toString()) }
        }
        Spacer(Modifier.padding(6.dp))
        TableCard(title = "sleep_stages_daily") {
            RowHeader("date", "sws(h.mm)", "rem(h.mm)", "light(h.mm)")
            sleepStages.take(180).forEach { r ->
                RowLine(
                    r.date,
                    String.format("%.2f", r.value_sws_hm),
                    String.format("%.2f", r.value_rem_hm),
                    String.format("%.2f", r.value_light_hm)
                )
            }
        }

        // ----- Weather API by location points (ONLY) -----
        Spacer(Modifier.padding(10.dp))
        SectionHeader("Weather API by location points")
        TableCard(title = "joined: user_location_daily × city_weather_daily (nearest city per point)") {
            RowHeader("date", "lat", "lon", "city", "temp_c_mean", "pressure_hpa_mean", "humidity_pct_mean")
            wxPerLoc.take(400).forEach { r ->
                RowLine(
                    r.date,
                    String.format("%.5f", r.latitude),
                    String.format("%.5f", r.longitude),
                    r.cityLabel ?: "-",
                    r.tempMeanC?.let { String.format("%.1f", it) } ?: "-",
                    r.pressureMeanHpa?.let { String.format("%.1f", it) } ?: "-",
                    r.humidityMeanPct?.let { String.format("%.0f", it) } ?: "-"
                )
            }
        }

        // ----- CellPhone -----
        Spacer(Modifier.padding(10.dp))
        SectionHeader("CellPhone")
        TableCard(title = "user_location_daily") {
            RowHeader("date", "lat", "lon")
            locations.take(180).forEach { r ->
                RowLine(
                    r.date,
                    String.format("%.5f", r.latitude),
                    String.format("%.5f", r.longitude)
                )
            }
        }

        // ----- App data (domain tables) -----
        Spacer(Modifier.padding(10.dp))
        SectionHeader("App data")
        TableCard(title = "migraines") {
            RowHeader("start_at", "ended_at", "severity", "type")
            migraines.take(50).forEach { r ->
                RowLine(r.startAt ?: "-", r.endAt ?: "-", r.severity?.toString() ?: "-", r.type ?: "-")
            }
        }
        Spacer(Modifier.padding(6.dp))
        TableCard(title = "reliefs") {
            RowHeader("start_at", "duration_minutes", "type")
            reliefs.take(50).forEach { r -> RowLine(r.startAt ?: "-", r.durationMinutes?.toString() ?: "-", r.type ?: "-") }
        }
        Spacer(Modifier.padding(6.dp))
        TableCard(title = "triggers") {
            RowHeader("start_at", "type")
            triggers.take(50).forEach { r -> RowLine(r.startAt ?: "-", r.type ?: "-") }
        }
        Spacer(Modifier.padding(6.dp))
        TableCard(title = "medicines") {
            RowHeader("start_at", "name", "amount")
            medicines.take(50).forEach { r ->
                RowLine(r.startAt ?: "-", r.name ?: "-", r.amount ?: "-")
            }
        }
    }
}

/* ---------- UI helpers ---------- */

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.padding(6.dp))
}

@Composable
private fun TableCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFFF7F7F9))
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.padding(4.dp))
            content()
        }
    }
}

@Composable
private fun RowHeader(vararg cols: String) {
    Row(Modifier.fillMaxWidth()) {
        cols.forEach { c -> Text(c, modifier = Modifier.weight(1f)) }
    }
}

@Composable
private fun RowLine(vararg cols: String) {
    Row(Modifier.fillMaxWidth()) {
        cols.forEach { c -> Text(c, modifier = Modifier.weight(1f)) }
    }
}

/* ---------- ViewModel for app + wearable + cellphone tables + per-location weather ---------- */

class TestingViewModel : ViewModel() {

    // App data rows from SupabaseDbService
    data class MigraineRow(val startAt: String?, val endAt: String?, val severity: Int?, val type: String?)
    data class ReliefRow(val startAt: String?, val durationMinutes: Int?, val type: String?)
    data class TriggerRow(val startAt: String?, val type: String?)
    data class MedicineRow(val startAt: String?, val name: String?, val amount: String?)

    private val _migraines = MutableStateFlow<List<MigraineRow>>(emptyList())
    val migraines: StateFlow<List<MigraineRow>> = _migraines

    private val _reliefs = MutableStateFlow<List<ReliefRow>>(emptyList())
    val reliefs: StateFlow<List<ReliefRow>> = _reliefs

    private val _triggers = MutableStateFlow<List<TriggerRow>>(emptyList())
    val triggers: StateFlow<List<TriggerRow>> = _triggers

    private val _medicines = MutableStateFlow<List<MedicineRow>>(emptyList())
    val medicines: StateFlow<List<MedicineRow>> = _medicines

    // Wearable (WHOOP)
    private val _sleepDuration = MutableStateFlow<List<SupabaseMetricsService.SleepDurationDailyRead>>(emptyList())
    val sleepDuration: StateFlow<List<SupabaseMetricsService.SleepDurationDailyRead>> = _sleepDuration

    @Serializable data class FellAsleepRead(val date: String, val value_at: String)
    @Serializable data class WokeUpRead(val date: String, val value_at: String)

    private val _fellAsleep = MutableStateFlow<List<FellAsleepRead>>(emptyList())
    val fellAsleep: StateFlow<List<FellAsleepRead>> = _fellAsleep

    private val _wokeUp = MutableStateFlow<List<WokeUpRead>>(emptyList())
    val wokeUp: StateFlow<List<WokeUpRead>> = _wokeUp

    private val _sleepDisturbances = MutableStateFlow<List<SupabaseMetricsService.SleepDisturbancesDailyRead>>(emptyList())
    val sleepDisturbances: StateFlow<List<SupabaseMetricsService.SleepDisturbancesDailyRead>> = _sleepDisturbances

    private val _sleepStages = MutableStateFlow<List<SupabaseMetricsService.SleepStagesDailyRead>>(emptyList())
    val sleepStages: StateFlow<List<SupabaseMetricsService.SleepStagesDailyRead>> = _sleepStages

    private val _sleepScore = MutableStateFlow<List<SupabaseMetricsService.SleepScoreDailyRead>>(emptyList())
    val sleepScore: StateFlow<List<SupabaseMetricsService.SleepScoreDailyRead>> = _sleepScore

    private val _sleepEfficiency = MutableStateFlow<List<SupabaseMetricsService.SleepEfficiencyDailyRead>>(emptyList())
    val sleepEfficiency: StateFlow<List<SupabaseMetricsService.SleepEfficiencyDailyRead>> = _sleepEfficiency

    // CellPhone
    data class LocationRow(val date: String, val latitude: Double, val longitude: Double)
    private val _userLocations = MutableStateFlow<List<LocationRow>>(emptyList())
    val userLocations: StateFlow<List<LocationRow>> = _userLocations

    // Weather joined per location point
    data class WeatherAtLocationRow(
        val date: String,
        val latitude: Double,
        val longitude: Double,
        val cityLabel: String?,
        val tempMeanC: Double?,
        val pressureMeanHpa: Double?,
        val humidityMeanPct: Double?
    )
    private val _weatherAtLocations = MutableStateFlow<List<WeatherAtLocationRow>>(emptyList())
    val weatherAtLocations: StateFlow<List<WeatherAtLocationRow>> = _weatherAtLocations

    private lateinit var db: SupabaseDbService

    fun load(context: Context, accessToken: String) {
        db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        viewModelScope.launch {
            try {
                // App tables
                val migs = db.getMigraines(accessToken)
                _migraines.value = migs.map { r -> MigraineRow(r.startAt, r.endAt, r.severity, r.type) }

                val rels = db.getAllReliefs(accessToken)
                _reliefs.value = rels.map { r -> ReliefRow(r.startAt, r.durationMinutes, r.type) }

                val trs = db.getAllTriggers(accessToken)
                _triggers.value = trs.map { r -> TriggerRow(r.startAt, r.type) }

                val meds = db.getAllMedicines(accessToken)
                _medicines.value = meds.map { r -> MedicineRow(r.startAt, r.name, r.amount) }

                // Wearable (WHOOP)
                val metrics = SupabaseMetricsService(context)
                _sleepDuration.value = metrics.fetchSleepDurationDaily(accessToken, limitDays = 180)
                _sleepDisturbances.value = metrics.fetchSleepDisturbancesDaily(accessToken, limitDays = 180)
                _sleepStages.value = metrics.fetchSleepStagesDaily(accessToken, limitDays = 180)
                _sleepScore.value = metrics.fetchSleepScoreDaily(accessToken, limitDays = 180)
                _sleepEfficiency.value = metrics.fetchSleepEfficiencyDaily(accessToken, limitDays = 180)
                _fellAsleep.value = fetchFellAsleep(accessToken)
                _wokeUp.value = fetchWokeUp(accessToken)

                // CellPhone
                val personal = SupabasePersonalService(context)
                val locs = personal.fetchUserLocationDaily(accessToken, limitDays = 180)
                val locRows = locs.map { r -> LocationRow(r.date, r.latitude, r.longitude) }
                _userLocations.value = locRows

                // Weather by location points (nearest city on that day)
                _weatherAtLocations.value = withContext(Dispatchers.IO) {
                    fetchWeatherForLocations(locs = locRows)
                }
            } catch (_: Exception) {
                clear()
            }
        }
    }

    fun clear() {
        _migraines.value = emptyList()
        _reliefs.value = emptyList()
        _triggers.value = emptyList()
        _medicines.value = emptyList()
        _sleepDuration.value = emptyList()
        _fellAsleep.value = emptyList()
        _wokeUp.value = emptyList()
        _sleepDisturbances.value = emptyList()
        _sleepStages.value = emptyList()
        _sleepScore.value = emptyList()
        _sleepEfficiency.value = emptyList()
        _userLocations.value = emptyList()
        _weatherAtLocations.value = emptyList()
    }

    /** Trigger backfill (latest+1..yesterday) and then write today. */
    fun launchBackfillThenToday(context: Context, accessToken: String, onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try { WhoopDailySyncWorkerSleepFields.backfillUpToYesterday(context, accessToken) } catch (_: Throwable) {}
            try { WhoopDailySyncWorkerSleepFields.runOnceNow(context) } catch (_: Throwable) {}
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    private suspend fun fetchFellAsleep(accessToken: String): List<FellAsleepRead> {
        val supabaseUrl = BuildConfig.SUPABASE_URL
        val supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        val client = HttpClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val resp = client.get("$supabaseUrl/rest/v1/fell_asleep_time_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "date,value_at")
            parameter("order", "date.desc")
            parameter("limit", "180")
        }
        return if (resp.status.isSuccess()) runCatching { resp.body<List<FellAsleepRead>>() }.getOrDefault(emptyList()) else emptyList()
    }

    private suspend fun fetchWokeUp(accessToken: String): List<WokeUpRead> {
        val supabaseUrl = BuildConfig.SUPABASE_URL
        val supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        val client = HttpClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val resp = client.get("$supabaseUrl/rest/v1/woke_up_time_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "date,value_at")
            parameter("order", "date.desc")
            parameter("limit", "180")
        }
        return if (resp.status.isSuccess()) runCatching { resp.body<List<WokeUpRead>>() }.getOrDefault(emptyList()) else emptyList()
    }

    /* --------- Weather per location point: nearest city + day match --------- */

    private data class CityRow(val id: Long, val label: String, val lat: Double, val lon: Double)

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lat2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    private fun httpGet(urlStr: String): Pair<Int, String> {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }
        conn.disconnect()
        return code to body
    }

    private fun parseCities(body: String): List<CityRow> {
        val arr = JSONArray(body)
        val out = ArrayList<CityRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                CityRow(
                    id = o.getLong("id"),
                    label = o.optString("name").ifBlank { o.optString("label", "") },
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon")
                )
            )
        }
        return out
    }

    private fun fetchCitiesNear(lat: Double, lon: Double, deg: Double): List<CityRow> {
        val base = "${BuildConfig.SUPABASE_URL}/rest/v1/city"
        val qp = buildString {
            append("select=id,name,lat,lon")
            append("&lat=gte.${lat - deg}&lat=lte.${lat + deg}")
            append("&lon=gte.${lon - deg}&lon=lte.${lon + deg}")
            append("&limit=5000")
        }
        val (code, body) = httpGet("$base?$qp")
        if (code !in 200..299) return emptyList()
        return parseCities(body)
    }

    private fun fetchAllCities(): List<CityRow> {
        val base = "${BuildConfig.SUPABASE_URL}/rest/v1/city"
        val qp = "select=id,name,lat,lon&limit=5000"
        val (code, body) = httpGet("$base?$qp")
        if (code !in 200..299) return emptyList()
        return parseCities(body)
    }

    private fun findNearestCity(lat: Double, lon: Double): CityRow? {
        val nearby = fetchCitiesNear(lat, lon, 2.0)
        val pool = if (nearby.isNotEmpty()) nearby else fetchAllCities()
        if (pool.isEmpty()) return null
        return pool.minByOrNull { c -> haversineKm(lat, lon, c.lat, c.lon) }
    }

    private data class WeatherRow(val temp: Double?, val pressure: Double?, val humidity: Double?)

    private fun fetchWeatherForCityOnDate(cityId: Long, dayIso: String): Pair<WeatherRow?, String?> {
        val base = "${BuildConfig.SUPABASE_URL}/rest/v1/city_weather_daily"
        val qp = buildString {
            append("select=day,temp_c_mean,pressure_hpa_mean,humidity_pct_mean,city_id,city!inner(name)")
            append("&city_id=eq.$cityId")
            append("&day=eq.$dayIso")
            append("&limit=1")
        }
        val (code, body) = httpGet("$base?$qp")
        if (code !in 200..299 || body.isBlank() || body == "[]") return null to null
        return try {
            val arr = JSONArray(body)
            if (arr.length() == 0) return null to null
            val o = arr.getJSONObject(0)
            val cityName = o.optJSONObject("city")?.optString("name")
            val w = WeatherRow(
                temp = o.optDoubleOrNull("temp_c_mean"),
                pressure = o.optDoubleOrNull("pressure_hpa_mean"),
                humidity = o.optDoubleOrNull("humidity_pct_mean")
            )
            w to cityName
        } catch (_: Throwable) {
            null to null
        }
    }

    private fun JSONObject.optDoubleOrNull(name: String): Double? {
        return if (isNull(name)) null else try { getDouble(name) } catch (_: Exception) { null }
    }

    private suspend fun fetchWeatherForLocations(locs: List<LocationRow>): List<WeatherAtLocationRow> {
        if (locs.isEmpty()) return emptyList()
        data class Key(val la: Int, val lo: Int)
        val cityCache = HashMap<Key, CityRow?>()
        val out = ArrayList<WeatherAtLocationRow>(locs.size)
        for (row in locs) {
            val key = Key((row.latitude * 10).toInt(), (row.longitude * 10).toInt())
            val city = cityCache.getOrPut(key) { findNearestCity(row.latitude, row.longitude) }
            if (city == null) {
                out.add(WeatherAtLocationRow(row.date, row.latitude, row.longitude, null, null, null, null))
                continue
            }
            val (w, cityName) = fetchWeatherForCityOnDate(city.id, row.date)
            out.add(
                WeatherAtLocationRow(
                    date = row.date,
                    latitude = row.latitude,
                    longitude = row.longitude,
                    cityLabel = cityName ?: city.label,
                    tempMeanC = w?.temp,
                    pressureMeanHpa = w?.pressure,
                    humidityMeanPct = w?.humidity
                )
            )
        }
        return out
    }
}
