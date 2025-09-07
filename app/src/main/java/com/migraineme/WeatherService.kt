// app/src/main/java/com/migraineme/WeatherService.kt
package com.migraineme

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Uses Open-Meteo (free, no key) to fetch hourly:
 * surface_pressure (hPa), temperature_2m (°C), relative_humidity_2m (%).
 * Returns "now" values + deltas/extremes over last 24h.
 */
object WeatherService {

    // Single shared HttpClient (reused app-wide)
    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = true
                }
            )
        }
    }

    // Expose the shared client so other services can reuse it (e.g., WeatherHistoryService).
    fun client(): HttpClient = client

    @Serializable
    private data class Hourly(
        val time: List<String>,
        @SerialName("temperature_2m") val temperature: List<Double>? = null,
        @SerialName("relative_humidity_2m") val humidity: List<Double>? = null,
        @SerialName("surface_pressure") val pressure: List<Double>? = null
    )

    @Serializable
    private data class ForecastResponse(
        val timezone: String,
        val hourly: Hourly
    )

    data class Summary(
        val timestampIso: String,
        val tempC: Double?,                // now
        val tempDelta24hC: Double?,        // now - 24h ago
        val pressureHpa: Double?,          // now
        val pressureDelta24hHpa: Double?,  // now - 24h ago
        val humidityPct: Double?,          // now
        val humidityMin24hPct: Double?,    // min last 24h
        val humidityMax24hPct: Double?     // max last 24h
    )

    suspend fun getSummary(
        latitude: Double,
        longitude: Double,
        zoneId: String = ZoneId.systemDefault().id
    ): Summary {
        // Ask for the last 24 hours only; times are aligned to the given timezone.
        val resp: ForecastResponse = client.get("https://api.open-meteo.com/v1/forecast") {
            parameter("latitude", latitude)
            parameter("longitude", longitude)
            parameter("hourly", "temperature_2m,relative_humidity_2m,surface_pressure")
            parameter("timezone", zoneId)
            parameter("past_hours", 24)
            parameter("forecast_hours", 0)
        }.body()

        val times = resp.hourly.time
        val temps = resp.hourly.temperature.orEmpty()
        val hums  = resp.hourly.humidity.orEmpty()
        val press = resp.hourly.pressure.orEmpty()

        if (times.isEmpty()) {
            return Summary(
                timestampIso = ZonedDateTime.now(ZoneId.of(zoneId)).toString(),
                tempC = null, tempDelta24hC = null,
                pressureHpa = null, pressureDelta24hHpa = null,
                humidityPct = null, humidityMin24hPct = null, humidityMax24hPct = null
            )
        }

        val nowIdx = times.lastIndex
        val idx24  = (nowIdx - 24).coerceAtLeast(0)

        val nowTemp = temps.getOrNull(nowIdx)
        val prevTemp = temps.getOrNull(idx24)
        val nowHum = hums.getOrNull(nowIdx)
        val nowPress = press.getOrNull(nowIdx)
        val prevPress = press.getOrNull(idx24)

        val humWindow = if (idx24 <= nowIdx) hums.subList(idx24, nowIdx + 1) else emptyList()

        return Summary(
            timestampIso = ZonedDateTime.now(ZoneId.of(zoneId)).toString(),
            tempC = nowTemp,
            tempDelta24hC = nowTemp?.let { t -> prevTemp?.let { t - it } },
            pressureHpa = nowPress,
            pressureDelta24hHpa = nowPress?.let { p -> prevPress?.let { p - it } },
            humidityPct = nowHum,
            humidityMin24hPct = humWindow.minOrNull(),
            humidityMax24hPct = humWindow.maxOrNull()
        )
    }
}
