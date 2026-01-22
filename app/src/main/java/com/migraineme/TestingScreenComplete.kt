package com.migraineme

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
fun TestingScreenComplete(
    authVm: AuthViewModel,
    vm: TestingViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val ctx = LocalContext.current.applicationContext
    val auth by authVm.state.collectAsState()

    // Load VM Data
    LaunchedEffect(auth.accessToken) {
        val token = auth.accessToken
        if (!token.isNullOrBlank()) vm.load(ctx, token)
        else vm.clear()
    }

    // -----------------------------------------
    // PHYSICAL HEALTH — MOVED FROM TestingScreen
    // -----------------------------------------
    var recovery by remember { mutableStateOf(emptyList<SupabasePhysicalHealthService.RecoveryScoreDailyRead>()) }
    var rhr by remember { mutableStateOf(emptyList<SupabasePhysicalHealthService.RestingHrDailyRead>()) }
    var hrv by remember { mutableStateOf(emptyList<SupabasePhysicalHealthService.HrvDailyRead>()) }
    var skin by remember { mutableStateOf(emptyList<SupabasePhysicalHealthService.SkinTempDailyRead>()) }
    var spo2 by remember { mutableStateOf(emptyList<SupabasePhysicalHealthService.Spo2DailyRead>()) }

    // High HR Zones (daily-style) + Activities (same table, different ordering)
    var zones by remember { mutableStateOf(emptyList<SupabasePhysicalHealthService.HighHrZonesDailyRead>()) }
    var zoneActivities by remember { mutableStateOf(emptyList<SupabasePhysicalHealthService.HighHrZonesDailyRead>()) }

    LaunchedEffect(auth.accessToken) {
        val token = auth.accessToken ?: return@LaunchedEffect
        val svc = SupabasePhysicalHealthService(ctx)

        recovery = svc.fetchRecoveryScoreDaily(token)
        rhr = svc.fetchRestingHrDaily(token)
        hrv = svc.fetchHrvDaily(token)
        skin = svc.fetchSkinTempDaily(token)
        spo2 = svc.fetchSpo2Daily(token)

        zones = svc.fetchHighHrDaily(token)
        zoneActivities = svc.fetchHighHrActivities(token)
    }

    // Existing VM fields
    val migraines by vm.migraines.collectAsState()
    val reliefs by vm.reliefs.collectAsState()
    val triggers by vm.triggers.collectAsState()
    val medicines by vm.medicines.collectAsState()

    val sleepDuration by vm.sleepDuration.collectAsState()
    val fellAsleep by vm.fellAsleep.collectAsState()
    val wokeUp by vm.wokeUp.collectAsState()
    val sleepDisturbances by vm.sleepDisturbances.collectAsState()
    val sleepStages by vm.sleepStages.collectAsState()
    val sleepScore by vm.sleepScore.collectAsState()
    val sleepEfficiency by vm.sleepEfficiency.collectAsState()

    val locations by vm.userLocations.collectAsState()
    val wxPerLoc by vm.weatherAtLocations.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Text("Testing (raw tables)", style = MaterialTheme.typography.headlineMedium)

        Spacer(Modifier.height(12.dp))

        // -----------------------------------
        // PHYSICAL HEALTH SECTION (NOW MOVED)
        // -----------------------------------
        SectionHeader("Physical Health (WHOOP)")

        TableCard("recovery_score_daily") {
            RowHeader("date", "value_pct")
            recovery.forEach { r -> RowLine(r.date, r.value_pct.toString()) }
        }

        TableCard("resting_hr_daily") {
            RowHeader("date", "value_bpm")
            rhr.forEach { r -> RowLine(r.date, r.value_bpm.toString()) }
        }

        TableCard("hrv_daily") {
            RowHeader("date", "rmssd_ms")
            hrv.forEach { r -> RowLine(r.date, r.value_rmssd_ms.toString()) }
        }

        TableCard("skin_temp_daily") {
            RowHeader("date", "value_celsius")
            skin.forEach { r -> RowLine(r.date, r.value_celsius.toString()) }
        }

        TableCard("spo2_daily") {
            RowHeader("date", "value_pct")
            spo2.forEach { r -> RowLine(r.date, r.value_pct.toString()) }
        }

        // NEW: High HR Zones (daily-style)
        TableCard("time_in_high_hr_zones_daily (daily)") {
            RowHeader("date", "total", "z3", "z4", "z5", "z6")
            zones.forEach { z ->
                RowLine(
                    z.date,
                    z.value_minutes.toString(),
                    z.zone_three_minutes.toString(),
                    z.zone_four_minutes.toString(),
                    z.zone_five_minutes.toString(),
                    z.zone_six_minutes.toString()
                )
            }
        }

        // NEW: Activities + full zone splits (same table)
        TableCard("time_in_high_hr_zones_daily (activities)") {
            RowHeader("start", "end", "activity", "total", "z0", "z1", "z2", "z3", "z4", "z5", "z6")
            zoneActivities.forEach { a ->
                RowLine(
                    a.start_at ?: "-",
                    a.end_at ?: "-",
                    a.activity_type ?: "-",
                    a.value_minutes.toString(),
                    (a.zone_zero_minutes ?: 0.0).toString(),
                    (a.zone_one_minutes ?: 0.0).toString(),
                    (a.zone_two_minutes ?: 0.0).toString(),
                    a.zone_three_minutes.toString(),
                    a.zone_four_minutes.toString(),
                    a.zone_five_minutes.toString(),
                    a.zone_six_minutes.toString()
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ----------------------------
        // Sleep tables (existing code)
        // ----------------------------
        SectionHeader("Wearable Sleep Metrics")

        TableCard("sleep_duration_daily") {
            RowHeader("date", "value_hours")
            sleepDuration.forEach { r -> RowLine(r.date, "%.2f".format(r.value_hours)) }
        }

        TableCard("sleep_score_daily") {
            RowHeader("date", "value_pct")
            sleepScore.forEach { r -> RowLine(r.date, "%.0f".format(r.value_pct)) }
        }

        TableCard("sleep_efficiency_daily") {
            RowHeader("date", "value_pct")
            sleepEfficiency.forEach { r -> RowLine(r.date, "%.0f".format(r.value_pct)) }
        }

        TableCard("fell_asleep_time_daily") {
            RowHeader("date", "value_at")
            fellAsleep.forEach { r -> RowLine(r.date, r.value_at) }
        }

        TableCard("woke_up_time_daily") {
            RowHeader("date", "value_at")
            wokeUp.forEach { r -> RowLine(r.date, r.value_at) }
        }

        TableCard("sleep_disturbances_daily") {
            RowHeader("date", "value_count")
            sleepDisturbances.forEach { r -> RowLine(r.date, r.value_count.toString()) }
        }

        TableCard("sleep_stages_daily") {
            RowHeader("date", "sws", "rem", "light")
            sleepStages.forEach { r ->
                RowLine(
                    r.date,
                    "%.2f".format(r.value_sws_hm),
                    "%.2f".format(r.value_rem_hm),
                    "%.2f".format(r.value_light_hm)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ----------------------------
        // Weather join
        // ----------------------------
        SectionHeader("Weather × Location Join")

        TableCard("weather_at_location") {
            RowHeader("date", "lat", "lon", "city", "temp", "press", "hum")
            wxPerLoc.forEach { r ->
                RowLine(
                    r.date,
                    "%.5f".format(r.latitude),
                    "%.5f".format(r.longitude),
                    r.cityLabel ?: "-",
                    r.tempMeanC?.let { "%.1f".format(it) } ?: "-",
                    r.pressureMeanHpa?.let { "%.1f".format(it) } ?: "-",
                    r.humidityMeanPct?.let { "%.0f".format(it) } ?: "-"
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ----------------------------
        // App tables
        // ----------------------------
        SectionHeader("App Data")

        TableCard("migraines") {
            RowHeader("start_at", "ended_at", "severity", "type")
            migraines.forEach { r ->
                RowLine(
                    r.startAt ?: "-",
                    r.endAt ?: "-",
                    r.severity?.toString() ?: "-",
                    r.type ?: "-"
                )
            }
        }

        TableCard("reliefs") {
            RowHeader("start_at", "duration", "type")
            reliefs.forEach { r ->
                RowLine(r.startAt ?: "-", r.durationMinutes?.toString() ?: "-", r.type ?: "-")
            }
        }

        TableCard("triggers") {
            RowHeader("start_at", "type")
            triggers.forEach { r -> RowLine(r.startAt ?: "-", r.type ?: "-") }
        }

        TableCard("medicines") {
            RowHeader("start_at", "name", "amount")
            medicines.forEach { r -> RowLine(r.startAt ?: "-", r.name ?: "-", r.amount ?: "-") }
        }
    }
}

/* ---------- UI HELPERS ---------- */

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun TableCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = androidx.compose.ui.graphics.Color(
                0xFFF7F7F9
            )
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
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

/* ---------- VIEWMODEL (unchanged) ---------- */

class TestingViewModel : ViewModel() {

    data class MigraineRow(
        val startAt: String?,
        val endAt: String?,
        val severity: Int?,
        val type: String?
    )

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

    private val _sleepDuration =
        MutableStateFlow<List<SupabaseMetricsService.SleepDurationDailyRead>>(emptyList())
    val sleepDuration: StateFlow<List<SupabaseMetricsService.SleepDurationDailyRead>> =
        _sleepDuration

    @Serializable
    data class FellAsleepRead(val date: String, val value_at: String)
    @Serializable
    data class WokeUpRead(val date: String, val value_at: String)

    private val _fellAsleep = MutableStateFlow<List<FellAsleepRead>>(emptyList())
    val fellAsleep: StateFlow<List<FellAsleepRead>> = _fellAsleep

    private val _wokeUp = MutableStateFlow<List<WokeUpRead>>(emptyList())
    val wokeUp: StateFlow<List<WokeUpRead>> = _wokeUp

    private val _sleepDisturbances =
        MutableStateFlow<List<SupabaseMetricsService.SleepDisturbancesDailyRead>>(emptyList())
    val sleepDisturbances: StateFlow<List<SupabaseMetricsService.SleepDisturbancesDailyRead>> =
        _sleepDisturbances

    private val _sleepStages =
        MutableStateFlow<List<SupabaseMetricsService.SleepStagesDailyRead>>(emptyList())
    val sleepStages: StateFlow<List<SupabaseMetricsService.SleepStagesDailyRead>> = _sleepStages

    private val _sleepScore =
        MutableStateFlow<List<SupabaseMetricsService.SleepScoreDailyRead>>(emptyList())
    val sleepScore: StateFlow<List<SupabaseMetricsService.SleepScoreDailyRead>> = _sleepScore

    private val _sleepEfficiency =
        MutableStateFlow<List<SupabaseMetricsService.SleepEfficiencyDailyRead>>(emptyList())
    val sleepEfficiency: StateFlow<List<SupabaseMetricsService.SleepEfficiencyDailyRead>> =
        _sleepEfficiency

    data class LocationRow(val date: String, val latitude: Double, val longitude: Double)

    private val _userLocations = MutableStateFlow<List<LocationRow>>(emptyList())
    val userLocations: StateFlow<List<LocationRow>> = _userLocations

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
                val migs = db.getMigraines(accessToken)
                _migraines.value =
                    migs.map { r -> MigraineRow(r.startAt, r.endAt, r.severity, r.type) }

                val rels = db.getAllReliefs(accessToken)
                _reliefs.value = rels.map { r -> ReliefRow(r.startAt, r.durationMinutes, r.type) }

                val trs = db.getAllTriggers(accessToken)
                _triggers.value = trs.map { r -> TriggerRow(r.startAt, r.type) }

                val meds = db.getAllMedicines(accessToken)
                _medicines.value = meds.map { r -> MedicineRow(r.startAt, r.name, r.amount) }

                val metrics = SupabaseMetricsService(context)
                _sleepDuration.value = metrics.fetchSleepDurationDaily(accessToken, limitDays = 180)
                _sleepDisturbances.value =
                    metrics.fetchSleepDisturbancesDaily(accessToken, limitDays = 180)
                _sleepStages.value = metrics.fetchSleepStagesDaily(accessToken, limitDays = 180)
                _sleepScore.value = metrics.fetchSleepScoreDaily(accessToken, limitDays = 180)
                _sleepEfficiency.value =
                    metrics.fetchSleepEfficiencyDaily(accessToken, limitDays = 180)
                _fellAsleep.value = fetchFellAsleep(accessToken)
                _wokeUp.value = fetchWokeUp(accessToken)

                val personal = SupabasePersonalService(context)
                val locs = personal.fetchUserLocationDaily(accessToken, limitDays = 180)
                val locRows = locs.map { r -> LocationRow(r.date, r.latitude, r.longitude) }
                _userLocations.value = locRows

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

    private suspend fun fetchFellAsleep(accessToken: String): List<FellAsleepRead> {
        val client =
            HttpClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val resp = client.get("${BuildConfig.SUPABASE_URL}/rest/v1/fell_asleep_time_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            parameter("select", "date,value_at")
            parameter("order", "date.desc")
            parameter("limit", "180")
        }
        return if (resp.status.isSuccess()) runCatching { resp.body<List<FellAsleepRead>>() }.getOrDefault(
            emptyList()
        )
        else emptyList()
    }

    private suspend fun fetchWokeUp(accessToken: String): List<WokeUpRead> {
        val client =
            HttpClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val resp = client.get("${BuildConfig.SUPABASE_URL}/rest/v1/woke_up_time_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            parameter("select", "date,value_at")
            parameter("order", "date.desc")
            parameter("limit", "180")
        }
        return if (resp.status.isSuccess()) runCatching { resp.body<List<WokeUpRead>>() }.getOrDefault(
            emptyList()
        )
        else emptyList()
    }

    private data class CityRow(val id: Long, val label: String, val lat: Double, val lon: Double)

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
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
        val body =
            BufferedReader(InputStreamReader(if (code in 200..299) conn.inputStream else conn.errorStream)).use { it.readText() }
        conn.disconnect()
        return code to body
    }

    private fun parseCities(body: String): List<CityRow> {
        val arr = JSONArray(body)
        val out = ArrayList<CityRow>()
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
        val qp =
            "select=id,name,lat,lon&lat=gte.${lat - deg}&lat=lte.${lat + deg}&lon=gte.${lon - deg}&lon=lte.${lon + deg}&limit=5000"
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

    private fun JSONObject.optDoubleOrNull(name: String): Double? {
        return if (isNull(name)) null else try {
            getDouble(name)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchWeatherForCityOnDate(
        cityId: Long,
        dayIso: String
    ): Pair<WeatherRow?, String?> {
        val base = "${BuildConfig.SUPABASE_URL}/rest/v1/city_weather_daily"
        val qp =
            "select=day,temp_c_mean,pressure_hpa_mean,humidity_pct_mean,city_id,city!inner(name)&city_id=eq.$cityId&day=eq.$dayIso&limit=1"
        val (code, body) = httpGet("$base?$qp")
        if (code !in 200..299 || body.isBlank() || body == "[]") return null to null
        return try {
            val arr = JSONArray(body)
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

    private suspend fun fetchWeatherForLocations(locs: List<LocationRow>): List<WeatherAtLocationRow> {
        if (locs.isEmpty()) return emptyList()
        data class Key(val la: Int, val lo: Int)

        val cache = HashMap<Key, CityRow?>()
        val out = ArrayList<WeatherAtLocationRow>()
        for (row in locs) {
            val key = Key((row.latitude * 10).toInt(), (row.longitude * 10).toInt())
            val city = cache.getOrPut(key) { findNearestCity(row.latitude, row.longitude) }
            if (city == null) {
                out.add(
                    WeatherAtLocationRow(
                        row.date,
                        row.latitude,
                        row.longitude,
                        null,
                        null,
                        null,
                        null
                    )
                )
                continue
            }
            val (w, lbl) = fetchWeatherForCityOnDate(city.id, row.date)
            out.add(
                WeatherAtLocationRow(
                    date = row.date,
                    latitude = row.latitude,
                    longitude = row.longitude,
                    cityLabel = lbl ?: city.label,
                    tempMeanC = w?.temp,
                    pressureMeanHpa = w?.pressure,
                    humidityMeanPct = w?.humidity
                )
            )
        }
        return out
    }
}
