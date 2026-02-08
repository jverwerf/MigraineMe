package com.migraineme

import android.content.Context
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Data class representing a single day's weather data.
 */
data class WeatherDayData(
    val date: String,
    val tempMean: Double,
    val pressureMean: Double,
    val humidityMean: Double,
    val windSpeedMean: Double,
    val uvIndexMax: Double,
    val weatherCode: Int,
    val isThunderstormDay: Boolean,
    val altitudeMaxM: Double? = null,
    val altitudeChangeM: Double? = null
)

/**
 * Result from getWeatherHistory() containing days + all-time min/max for normalization.
 */
data class WeatherHistoryResult(
    val days: List<WeatherDayData>,
    val allTimeMin: Map<String, Float>,
    val allTimeMax: Map<String, Float>
)

/**
 * Service for fetching weather history from user_weather_daily table.
 */
class WeatherHistoryService(context: Context) {
    private val supabaseUrl = BuildConfig.SUPABASE_URL
    private val supabaseKey = BuildConfig.SUPABASE_ANON_KEY

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private val appContext = context.applicationContext

    @Serializable
    private data class WeatherRow(
        val date: String? = null,
        @SerialName("temp_c_mean") val tempMean: Double? = null,
        @SerialName("pressure_hpa_mean") val pressureMean: Double? = null,
        @SerialName("humidity_pct_mean") val humidityMean: Double? = null,
        @SerialName("wind_speed_mps_mean") val windSpeedMean: Double? = null,
        @SerialName("uv_index_max") val uvIndexMax: Double? = null,
        @SerialName("weather_code") val weatherCode: Int? = null,
        @SerialName("is_thunderstorm_day") val isThunderstormDay: Boolean? = null
    )

    @Serializable
    private data class LocationAltitudeRow(
        val date: String? = null,
        val altitude_max_m: Double? = null,
        val altitude_change_m: Double? = null
    )

    /**
     * Fetches weather history for the last N days.
     * Also calculates all-time min/max for normalization.
     */
    suspend fun getWeatherHistory(days: Int): WeatherHistoryResult = getWeatherHistory(days, LocalDate.now())

    /**
     * Fetches weather history for the given window.
     * endDate is the last date to include; startDate = endDate - days + 1.
     */
    suspend fun getWeatherHistory(days: Int, endDate: LocalDate): WeatherHistoryResult = withContext(Dispatchers.IO) {
        try {
            val token = SessionStore.getValidAccessToken(appContext)
                ?: return@withContext WeatherHistoryResult(emptyList(), emptyMap(), emptyMap())

            val userId = SessionStore.readUserId(appContext)
                ?: return@withContext WeatherHistoryResult(emptyList(), emptyMap(), emptyMap())

            val startDate = endDate.minusDays(days.toLong() - 1)

            // Fetch data for the requested window
            val response = client.get("$supabaseUrl/rest/v1/user_weather_daily") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header("apikey", supabaseKey)
                parameter("user_id", "eq.$userId")
                parameter("date", "gte.${startDate}")
                parameter("date", "lte.${endDate}")
                parameter("select", "date,temp_c_mean,pressure_hpa_mean,humidity_pct_mean,wind_speed_mps_mean,uv_index_max,weather_code,is_thunderstorm_day")
                parameter("order", "date.asc")
            }

            if (!response.status.isSuccess()) {
                return@withContext WeatherHistoryResult(emptyList(), emptyMap(), emptyMap())
            }

            val rows: List<WeatherRow> = response.body()

            // Fetch altitude for the same date range
            val altitudeMap = fetchAltitudeMap(token, userId, startDate, endDate)

            // Fetch ALL data for all-time min/max calculation
            val allResponse = client.get("$supabaseUrl/rest/v1/user_weather_daily") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header("apikey", supabaseKey)
                parameter("user_id", "eq.$userId")
                parameter("select", "temp_c_mean,pressure_hpa_mean,humidity_pct_mean,wind_speed_mps_mean,uv_index_max")
                parameter("order", "date.asc")
            }

            val allRows: List<WeatherRow> = if (allResponse.status.isSuccess()) {
                allResponse.body()
            } else {
                rows
            }

            // Fetch all-time altitude for min/max
            val allAltResponse = client.get("$supabaseUrl/rest/v1/user_location_daily") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header("apikey", supabaseKey)
                parameter("user_id", "eq.$userId")
                parameter("select", "altitude_max_m,altitude_change_m")
                parameter("altitude_max_m", "not.is.null")
                parameter("order", "date.asc")
            }
            val allAltRows: List<LocationAltitudeRow> = if (allAltResponse.status.isSuccess()) {
                runCatching { allAltResponse.body<List<LocationAltitudeRow>>() }.getOrDefault(emptyList())
            } else emptyList()

            // Calculate all-time min/max
            val allTimeMin = mutableMapOf<String, Float>()
            val allTimeMax = mutableMapOf<String, Float>()

            val tempValues = allRows.mapNotNull { it.tempMean?.toFloat() }
            val pressureValues = allRows.mapNotNull { it.pressureMean?.toFloat() }
            val humidityValues = allRows.mapNotNull { it.humidityMean?.toFloat() }
            val windValues = allRows.mapNotNull { it.windSpeedMean?.toFloat() }
            val uvValues = allRows.mapNotNull { it.uvIndexMax?.toFloat() }
            val altMaxValues = allAltRows.mapNotNull { it.altitude_max_m?.toFloat() }
            val altChangeValues = allAltRows.mapNotNull { it.altitude_change_m?.toFloat() }

            if (tempValues.isNotEmpty()) {
                allTimeMin["temp_c_mean"] = tempValues.minOrNull() ?: 0f
                allTimeMax["temp_c_mean"] = tempValues.maxOrNull() ?: 1f
            }
            if (pressureValues.isNotEmpty()) {
                allTimeMin["pressure_hpa_mean"] = pressureValues.minOrNull() ?: 0f
                allTimeMax["pressure_hpa_mean"] = pressureValues.maxOrNull() ?: 1f
            }
            if (humidityValues.isNotEmpty()) {
                allTimeMin["humidity_pct_mean"] = humidityValues.minOrNull() ?: 0f
                allTimeMax["humidity_pct_mean"] = humidityValues.maxOrNull() ?: 1f
            }
            if (windValues.isNotEmpty()) {
                allTimeMin["wind_speed_mps_mean"] = windValues.minOrNull() ?: 0f
                allTimeMax["wind_speed_mps_mean"] = windValues.maxOrNull() ?: 1f
            }
            if (uvValues.isNotEmpty()) {
                allTimeMin["uv_index_max"] = uvValues.minOrNull() ?: 0f
                allTimeMax["uv_index_max"] = uvValues.maxOrNull() ?: 1f
            }
            if (altMaxValues.isNotEmpty()) {
                allTimeMin["altitude_m"] = altMaxValues.minOrNull() ?: 0f
                allTimeMax["altitude_m"] = altMaxValues.maxOrNull() ?: 1f
            }
            if (altChangeValues.isNotEmpty()) {
                allTimeMin["altitude_change_m"] = altChangeValues.minOrNull() ?: 0f
                allTimeMax["altitude_change_m"] = altChangeValues.maxOrNull() ?: 1f
            }

            // Convert to WeatherDayData (filter out rows without date)
            val dayData = rows.filter { it.date != null }.map { row ->
                WeatherDayData(
                    date = row.date!!,
                    tempMean = row.tempMean ?: 0.0,
                    pressureMean = row.pressureMean ?: 0.0,
                    humidityMean = row.humidityMean ?: 0.0,
                    windSpeedMean = row.windSpeedMean ?: 0.0,
                    uvIndexMax = row.uvIndexMax ?: 0.0,
                    weatherCode = row.weatherCode ?: 0,
                    isThunderstormDay = row.isThunderstormDay ?: false,
                    altitudeMaxM = altitudeMap[row.date]?.maxM,
                    altitudeChangeM = altitudeMap[row.date]?.changeM
                )
            }

            WeatherHistoryResult(
                days = dayData,
                allTimeMin = allTimeMin,
                allTimeMax = allTimeMax
            )
        } catch (e: Exception) {
            android.util.Log.e("WeatherHistoryService", "Error fetching weather history", e)
            WeatherHistoryResult(emptyList(), emptyMap(), emptyMap())
        }
    }

    /**
     * Get today's weather summary including altitude.
     */
    suspend fun getTodayWeather(): WeatherDayData? = withContext(Dispatchers.IO) {
        try {
            val token = SessionStore.getValidAccessToken(appContext) ?: return@withContext null
            val userId = SessionStore.readUserId(appContext) ?: return@withContext null
            val today = LocalDate.now().toString()

            val response = client.get("$supabaseUrl/rest/v1/user_weather_daily") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header("apikey", supabaseKey)
                parameter("user_id", "eq.$userId")
                parameter("date", "eq.$today")
                parameter("select", "date,temp_c_mean,pressure_hpa_mean,humidity_pct_mean,wind_speed_mps_mean,uv_index_max,weather_code,is_thunderstorm_day")
                parameter("limit", "1")
            }

            if (!response.status.isSuccess()) return@withContext null

            val rows: List<WeatherRow> = response.body()
            val row = rows.firstOrNull() ?: return@withContext null
            if (row.date == null) return@withContext null

            // Fetch today's altitude
            val altAgg = fetchAltitudeForDate(token, userId, today)

            WeatherDayData(
                date = row.date,
                tempMean = row.tempMean ?: 0.0,
                pressureMean = row.pressureMean ?: 0.0,
                humidityMean = row.humidityMean ?: 0.0,
                windSpeedMean = row.windSpeedMean ?: 0.0,
                uvIndexMax = row.uvIndexMax ?: 0.0,
                weatherCode = row.weatherCode ?: 0,
                isThunderstormDay = row.isThunderstormDay ?: false,
                altitudeMaxM = altAgg?.maxM,
                altitudeChangeM = altAgg?.changeM
            )
        } catch (e: Exception) {
            android.util.Log.e("WeatherHistoryService", "Error fetching today's weather", e)
            null
        }
    }

    private data class AltitudeAggData(val maxM: Double?, val changeM: Double?)

    /**
     * Fetch altitude aggregation for a date range from user_location_daily.
     */
    private suspend fun fetchAltitudeMap(
        token: String,
        userId: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<String, AltitudeAggData> {
        return try {
            val resp = client.get("$supabaseUrl/rest/v1/user_location_daily") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header("apikey", supabaseKey)
                parameter("user_id", "eq.$userId")
                parameter("date", "gte.$startDate")
                parameter("date", "lte.$endDate")
                parameter("select", "date,altitude_max_m,altitude_change_m")
                parameter("order", "date.asc")
            }
            if (!resp.status.isSuccess()) return emptyMap()
            val rows: List<LocationAltitudeRow> = resp.body()
            rows.filter { it.date != null }
                .associate { it.date!! to AltitudeAggData(it.altitude_max_m, it.altitude_change_m) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Fetch altitude for a single date.
     */
    private suspend fun fetchAltitudeForDate(
        token: String,
        userId: String,
        date: String
    ): AltitudeAggData? {
        return try {
            val resp = client.get("$supabaseUrl/rest/v1/user_location_daily") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header("apikey", supabaseKey)
                parameter("user_id", "eq.$userId")
                parameter("date", "eq.$date")
                parameter("select", "altitude_max_m,altitude_change_m")
                parameter("limit", "1")
            }
            if (!resp.status.isSuccess()) return null
            val rows: List<LocationAltitudeRow> = resp.body()
            val row = rows.firstOrNull() ?: return null
            AltitudeAggData(row.altitude_max_m, row.altitude_change_m)
        } catch (_: Exception) {
            null
        }
    }
}
