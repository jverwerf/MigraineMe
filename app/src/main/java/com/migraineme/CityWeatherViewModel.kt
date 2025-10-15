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

    /** Entry point used by InsightsWeatherPanel */
    fun loadNearestAndDaily(location: Location?) {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val refLat: Double
                val refLon: Double
                if (location != null) {
                    refLat = location.latitude
                    refLon = location.longitude
                } else {
                    // London fallback when device has no location
                    refLat = 51.5074
                    refLon = -0.1278
                }
                Log.d("CityWX", "Ref location lat=$refLat lon=$refLon")

                val cities: List<CityRow> = withContext(Dispatchers.IO) {
                    // 1) Try near-box first (±2° ≈ ~220 km at mid-latitudes)
                    val nearby = fetchCitiesNear(refLat, refLon, 2.0)
                    if (nearby.isNotEmpty()) {
                        Log.d("CityWX", "Nearby cities: ${nearby.size}")
                        // Also fetch the global list only if nearby is tiny, to avoid missing close borders
                        if (nearby.size < 5) {
                            val all = fetchAllCities()
                            mergeUnique(nearby, all)
                        } else {
                            nearby
                        }
                    } else {
                        Log.d("CityWX", "Nearby empty. Falling back to all cities.")
                        fetchAllCities()
                    }
                }

                if (cities.isEmpty()) {
                    _state.value = _state.value.copy(loading = false, error = "No cities available")
                    return@launch
                }

                // Sort by distance and pick nearest
                val sorted = cities
                    .asSequence()
                    .map { c -> c to haversineKm(refLat, refLon, c.lat, c.lon) }
                    .sortedBy { it.second }
                    .toList()

                for ((i, p) in sorted.take(5).withIndex()) {
                    val c = p.first
                    Log.d("CityWX", "Nearest[$i]: ${c.label} (id=${c.id}) lat=${c.lat} lon=${c.lon} dist=${"%.2f".format(p.second)} km tz=${c.timezone}")
                }

                val nearest = sorted.first().first
                Log.d("CityWX", "Chosen: ${nearest.label} (id=${nearest.id})")

                val days = withContext(Dispatchers.IO) { fetchDaily(nearest.id) }

                _state.value = CityWeatherState(
                    loading = false,
                    error = null,
                    nearestCity = nearest,
                    days = days
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Error")
            }
        }
    }

    /** GET /rest/v1/city?select=id,label:name,lat,lon,timezone&limit=5000 */
    private fun fetchAllCities(): List<CityRow> {
        val qp = "select=id,label:name,lat,lon,timezone&limit=5000"
        val (code, body) = httpGet("$supabaseUrl/rest/v1/city?$qp")
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
    private fun fetchCitiesNear(lat: Double, lon: Double, deg: Double): List<CityRow> {
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
        val (code, body) = httpGet("$supabaseUrl/rest/v1/city?$qp")
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

    /**
     * Primary: T−2..T+6 window. Fallback: recent rows if window empty.
     */
    private fun fetchDaily(cityId: Long): List<DailyRow> {
        val today = LocalDate.now()
        val from = today.minusDays(2).format(DateTimeFormatter.ISO_DATE)
        val to = today.plusDays(6).format(DateTimeFormatter.ISO_DATE)
        val base = "select=day,temp_c_mean,pressure_hpa_mean,humidity_pct_mean&city_id=eq.$cityId"

        val qpWindow = "$base&order=day.asc&day=gte.$from&day=lte.$to"
        val primary = parseDaily(httpGet("$supabaseUrl/rest/v1/city_weather_daily?$qpWindow"))
        if (primary.isNotEmpty()) return primary

        val qpRecent = "$base&order=day.desc&limit=9"
        val recentDesc = parseDaily(httpGet("$supabaseUrl/rest/v1/city_weather_daily?$qpRecent"))
        if (recentDesc.isNotEmpty()) return recentDesc.asReversed()

        return emptyList()
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

    private fun applyHeaders(conn: HttpURLConnection) {
        conn.setRequestProperty("apikey", supabaseKey)
        conn.setRequestProperty("Authorization", "Bearer $supabaseKey")
    }

    private fun httpGet(urlStr: String): Pair<Int, String> {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            applyHeaders(this)
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

    /** Haversine distance in km */
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
}
