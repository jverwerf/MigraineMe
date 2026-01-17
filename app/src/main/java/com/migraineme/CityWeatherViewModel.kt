// FILE: C:\Users\verwe\Projects\MigraineMe\app\src\main\java\com\migraineme\CityWeatherViewModel.kt
package com.migraineme

import android.app.Application
import android.location.Location
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class CityRow(
    val id: Long,
    val label: String,
    val lat: Double,
    val lon: Double,
    val timezone: String?
)

data class DailyRow(
    val day: String,
    val tempMeanC: Double?,
    val pressureMeanHpa: Double?,
    val humidityMeanPct: Double?
)

data class CityWeatherState(
    val loading: Boolean = false,
    val error: String? = null,
    val nearestCity: CityRow? = null,
    val days: List<DailyRow> = emptyList()
)

class CityWeatherViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(CityWeatherState(loading = false))
    val state: StateFlow<CityWeatherState> = _state

    private val supabaseUrl: String = BuildConfig.SUPABASE_URL
    private val supabaseKey: String = BuildConfig.SUPABASE_ANON_KEY

    /**
     * Entry point used by InsightsWeatherPanel.
     *
     * CHANGE:
     * - Prefer Supabase-resolved city (RPC: resolve_user_city_today) using the user's session token.
     * - This removes local nearest-city computation from the client (Option 1 backend ownership).
     * - If no session token exists (edge case), fall back to the old local computation so UI still works.
     */
    fun loadNearestAndDaily(location: Location?) {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null)

        viewModelScope.launch {
            try {
                val appCtx = getApplication<Application>().applicationContext
                val accessToken = withContext(Dispatchers.IO) {
                    runCatching { SessionStore.getValidAccessToken(appCtx) }.getOrNull()
                }

                val nearest: CityRow = if (!accessToken.isNullOrBlank()) {
                    // Backend-owned city resolution
                    withContext(Dispatchers.IO) { resolveCityToday(accessToken) }
                } else {
                    // Fallback: preserve existing behavior if no session (should be rare in-app)
                    val (refLat, refLon) = extractRefLatLonOrFallback(location)
                    Log.d("CityWX", "No session token; fallback to local nearest. lat=$refLat lon=$refLon")

                    val cities: List<CityRow> = withContext(Dispatchers.IO) {
                        val nearby = fetchCitiesNear(refLat, refLon, 2.0, accessToken = null)
                        if (nearby.isNotEmpty()) {
                            if (nearby.size < 5) mergeUnique(nearby, fetchAllCities(accessToken = null)) else nearby
                        } else {
                            fetchAllCities(accessToken = null)
                        }
                    }

                    if (cities.isEmpty()) {
                        _state.value = _state.value.copy(loading = false, error = "No cities available")
                        return@launch
                    }

                    pickNearest(refLat, refLon, cities)
                }

                val days = withContext(Dispatchers.IO) { fetchDaily(nearest.id, accessToken = null) }
                _state.value = CityWeatherState(loading = false, error = null, nearestCity = nearest, days = days)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Error")
            }
        }
    }

    /**
     * Range-aware loader (kept).
     *
     * NOTE:
     * This still uses the same nearest city selection, but now prefers the backend city resolution
     * when accessToken is available.
     */
    fun loadNearestAndDailyByUserHistory(
        accessToken: String,
        location: Location?
    ) {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val personal = SupabasePersonalService(getApplication())

                val earliestStr = withContext(Dispatchers.IO) {
                    runCatching { personal.earliestUserLocationDate(accessToken, source = "device") }.getOrNull()
                }

                val today = LocalDate.now()
                val start = runCatching { earliestStr?.let { LocalDate.parse(it) } }.getOrNull()
                    ?.let { if (it.isAfter(today.minusDays(30))) it else today.minusDays(30) }
                    ?: today.minusDays(30)

                val fromIso = start.format(DateTimeFormatter.ISO_DATE)
                val toIso = today.format(DateTimeFormatter.ISO_DATE)

                Log.d("CityWX", "History range from=$fromIso to=$toIso (earliest=$earliestStr)")

                val nearest: CityRow = withContext(Dispatchers.IO) {
                    // Backend-owned city resolution (preferred)
                    runCatching { resolveCityToday(accessToken) }.getOrElse {
                        // Fallback to old behavior if RPC fails
                        val (refLat, refLon) = extractRefLatLonOrFallback(location)
                        val nearby = fetchCitiesNear(refLat, refLon, 2.0, accessToken = null)
                        val cities =
                            if (nearby.isNotEmpty()) {
                                if (nearby.size < 5) mergeUnique(nearby, fetchAllCities(accessToken = null)) else nearby
                            } else {
                                fetchAllCities(accessToken = null)
                            }
                        pickNearest(refLat, refLon, cities)
                    }
                }

                val days = withContext(Dispatchers.IO) {
                    val ranged = fetchDailyRange(nearest.id, fromIso, toIso, accessToken = null)
                    if (ranged.isNotEmpty()) ranged else fetchDaily(nearest.id, accessToken = null)
                }

                _state.value = CityWeatherState(loading = false, error = null, nearestCity = nearest, days = days)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Error")
            }
        }
    }

    /* ---------------- Backend city resolution ---------------- */

    /**
     * Calls Supabase RPC: POST /rest/v1/rpc/resolve_user_city_today
     *
     * Expected response:
     * - Either a JSON object: { id, label, lat, lon, timezone }
     * - Or an array with one object: [ { ... } ]
     */
    private fun resolveCityToday(accessToken: String): CityRow {
        val (code, body) = httpPostJson(
            urlStr = "$supabaseUrl/rest/v1/rpc/resolve_user_city_today",
            jsonBody = "{}",
            accessToken = accessToken
        )

        if (code !in 200..299) {
            val msg = parseErrorMessage(body) ?: "HTTP $code resolving user city"
            throw IllegalStateException(msg)
        }

        val obj = parseRpcObject(body)
        return CityRow(
            id = obj.getLong("id"),
            label = obj.optString("label", obj.optString("name", "")),
            lat = obj.getDouble("lat"),
            lon = obj.getDouble("lon"),
            timezone = if (obj.isNull("timezone")) null else obj.getString("timezone")
        )
    }

    private fun parseRpcObject(body: String): JSONObject {
        val trimmed = body.trim()
        return when {
            trimmed.startsWith("{") -> JSONObject(trimmed)
            trimmed.startsWith("[") -> {
                val arr = JSONArray(trimmed)
                if (arr.length() == 0) throw IllegalStateException("RPC returned empty result")
                arr.getJSONObject(0)
            }
            else -> throw IllegalStateException("Unexpected RPC response: ${trimmed.take(140)}")
        }
    }

    /* ---------------- Internals ---------------- */

    private fun extractRefLatLonOrFallback(location: Location?): Pair<Double, Double> {
        return if (location != null) location.latitude to location.longitude else 51.5074 to -0.1278 // London fallback
    }

    /** GET /rest/v1/city?select=id,label:name,lat,lon,timezone&limit=5000 */
    private fun fetchAllCities(accessToken: String?): List<CityRow> {
        val qp = "select=id,label:name,lat,lon,timezone&limit=5000"
        val (code, body) = httpGet("$supabaseUrl/rest/v1/city?$qp", accessToken = accessToken)
        if (code !in 200..299) {
            val msg = parseErrorMessage(body) ?: "HTTP $code loading city"
            throw IllegalStateException(msg)
        }
        return parseCities(body)
    }

    /**
     * Bounding-box query around device location to ensure local coverage.
     * GET /rest/v1/city?select=id,label:name,lat,lon,timezone&lat=gte.{minLat}&lat=lte.{maxLat}&lon=gte.{minLon}&lon=lte.{maxLon}
     */
    private fun fetchCitiesNear(lat: Double, lon: Double, deg: Double, accessToken: String?): List<CityRow> {
        val minLat = lat - deg
        val maxLat = lat + deg
        val minLon = lon - deg
        val maxLon = lon + deg
        val qp = buildString {
            append("select=id,label:name,lat,lon,timezone")
            append("&lat=gte.$minLat&lat=lte.$maxLat")
            append("&lon=gte.$minLon&lon=lte.$maxLon")
            append("&limit=5000")
        }
        val (code, body) = httpGet("$supabaseUrl/rest/v1/city?$qp", accessToken = accessToken)
        if (code !in 200..299) {
            val msg = parseErrorMessage(body) ?: "HTTP $code loading nearby city"
            throw IllegalStateException(msg)
        }
        return parseCities(body)
    }

    private fun parseCities(body: String): List<CityRow> {
        val arr = JSONArray(body)
        val out = ArrayList<CityRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                CityRow(
                    id = o.getLong("id"),
                    label = o.optString("label", ""),
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    timezone = if (o.isNull("timezone")) null else o.getString("timezone")
                )
            )
        }
        return out
    }

    private fun mergeUnique(a: List<CityRow>, b: List<CityRow>): List<CityRow> {
        val seen = HashSet<Long>(a.size + b.size)
        val out = ArrayList<CityRow>(a.size + b.size)
        for (c in a) if (seen.add(c.id)) out.add(c)
        for (c in b) if (seen.add(c.id)) out.add(c)
        return out
    }

    /** Original: T−2..T+6 window with fallback to most recent rows. */
    private fun fetchDaily(cityId: Long, accessToken: String?): List<DailyRow> {
        val today = LocalDate.now()
        val from = today.minusDays(2).format(DateTimeFormatter.ISO_DATE)
        val to = today.plusDays(6).format(DateTimeFormatter.ISO_DATE)
        val base = "select=day,temp_c_mean,pressure_hpa_mean,humidity_pct_mean&city_id=eq.$cityId"

        val qpWindow = "$base&order=day.asc&day=gte.$from&day=lte.$to"
        val primary = parseDaily(httpGet("$supabaseUrl/rest/v1/city_weather_daily?$qpWindow", accessToken = accessToken))
        if (primary.isNotEmpty()) return primary

        val qpRecent = "$base&order=day.desc&limit=9"
        val recentDesc = parseDaily(httpGet("$supabaseUrl/rest/v1/city_weather_daily?$qpRecent", accessToken = accessToken))
        if (recentDesc.isNotEmpty()) return recentDesc.asReversed()

        return emptyList()
    }

    private fun fetchDailyRange(cityId: Long, fromIso: String, toIso: String, accessToken: String?): List<DailyRow> {
        val base = "select=day,temp_c_mean,pressure_hpa_mean,humidity_pct_mean&city_id=eq.$cityId"
        val qp = "$base&order=day.asc&day=gte.$fromIso&day=lte.$toIso"
        return parseDaily(httpGet("$supabaseUrl/rest/v1/city_weather_daily?$qp", accessToken = accessToken))
    }

    private fun parseDaily(resp: Pair<Int, String>): List<DailyRow> {
        val (code, body) = resp
        if (code !in 200..299) {
            val msg = parseErrorMessage(body) ?: "HTTP $code loading city_weather_daily"
            throw IllegalStateException(msg)
        }
        val arr = JSONArray(body)
        val out = ArrayList<DailyRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                DailyRow(
                    day = o.getString("day"),
                    tempMeanC = o.optDoubleOrNull("temp_c_mean"),
                    pressureMeanHpa = o.optDoubleOrNull("pressure_hpa_mean"),
                    humidityMeanPct = o.optDoubleOrNull("humidity_pct_mean")
                )
            )
        }
        return out
    }

    private fun applyHeaders(conn: HttpURLConnection, accessToken: String?) {
        conn.setRequestProperty("apikey", supabaseKey)
        // IMPORTANT:
        // - For user-specific calls (RPC), use the session access token.
        // - For public/reference calls, keep existing behavior.
        val bearer = accessToken ?: supabaseKey
        conn.setRequestProperty("Authorization", "Bearer $bearer")
    }

    private fun httpGet(urlStr: String, accessToken: String?): Pair<Int, String> {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            applyHeaders(this, accessToken)
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }
        conn.disconnect()
        return code to body
    }

    private fun httpPostJson(urlStr: String, jsonBody: String, accessToken: String?): Pair<Int, String> {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            applyHeaders(this, accessToken)
        }
        conn.outputStream.use { os ->
            os.write(jsonBody.toByteArray(Charsets.UTF_8))
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }
        conn.disconnect()
        return code to body
    }

    private fun parseErrorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val o = JSONObject(body)
            val msg = o.optString("message", "")
            if (msg.isNotBlank()) msg else {
                val err = o.optString("error", "")
                if (err.isNotBlank()) err else null
            }
        } catch (_: Exception) {
            body.take(140)
        }
    }

    private fun JSONObject.optDoubleOrNull(name: String): Double? {
        return if (isNull(name)) null else try { getDouble(name) } catch (_: Exception) { null }
    }

    /** Haversine distance in km (kept only for fallback path). */
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

    private fun pickNearest(refLat: Double, refLon: Double, cities: List<CityRow>): CityRow {
        return cities
            .asSequence()
            .map { c -> c to haversineKm(refLat, refLon, c.lat, c.lon) }
            .sortedBy { it.second }
            .first().first
    }
}
