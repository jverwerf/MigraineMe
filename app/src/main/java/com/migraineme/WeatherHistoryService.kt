package com.migraineme

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId

object WeatherHistoryService {

    @Serializable
    private data class HourlyBlock(
        val time: List<String> = emptyList(),
        @SerialName("temperature_2m") val temperature: List<Double>? = null,
        @SerialName("relative_humidity_2m") val humidity: List<Double>? = null,
        @SerialName("surface_pressure") val pressure: List<Double>? = null
    )

    @Serializable private data class ArchiveResponse(val hourly: HourlyBlock? = null)
    @Serializable private data class ForecastResponse(val hourly: HourlyBlock? = null)

    data class Series(
        val timeIso: List<String>,
        val temperatureC: List<Double>,
        val pressureHpa: List<Double>,
        val humidityPct: List<Double>
    )

    suspend fun fetch(
        lat: Double,
        lon: Double,
        days: Int = 7,
        zoneId: String = ZoneId.systemDefault().id
    ): Series {
        runCatching { fetchArchive(lat, lon, days, zoneId) }
            .onSuccess { return it }
        return fetchForecastPastDays(lat, lon, days, zoneId)
    }

    private suspend fun fetchArchive(
        lat: Double,
        lon: Double,
        days: Int,
        zoneId: String
    ): Series {
        val today = LocalDate.now(ZoneId.of(zoneId))
        val start = today.minusDays(days.toLong())

        val http = WeatherService.client()
        val resp: ArchiveResponse = http.get("https://archive-api.open-meteo.com/v1/archive") {
            parameter("latitude", lat)
            parameter("longitude", lon)
            parameter("start_date", start.toString())
            parameter("end_date", today.toString())
            parameter("hourly", "temperature_2m,relative_humidity_2m,surface_pressure")
            parameter("timezone", zoneId)
        }.body()

        val h = resp.hourly ?: error("Archive missing hourly")
        return normalize(h)
    }

    private suspend fun fetchForecastPastDays(
        lat: Double,
        lon: Double,
        days: Int,
        zoneId: String
    ): Series {
        val http = WeatherService.client()
        val resp: ForecastResponse = http.get("https://api.open-meteo.com/v1/forecast") {
            parameter("latitude", lat)
            parameter("longitude", lon)
            parameter("hourly", "temperature_2m,relative_humidity_2m,surface_pressure")
            parameter("timezone", zoneId)
            parameter("past_days", days)
        }.body()

        val h = resp.hourly ?: error("Forecast missing hourly")
        return normalize(h)
    }

    private fun normalize(h: HourlyBlock): Series {
        val times = h.time
        val temps = h.temperature ?: emptyList()
        val press = h.pressure ?: emptyList()
        val hums  = h.humidity ?: emptyList()
        val n = listOf(times.size, temps.size, press.size, hums.size).minOrNull() ?: 0
        return Series(
            timeIso = times.take(n),
            temperatureC = temps.take(n),
            pressureHpa   = press.take(n),
            humidityPct   = hums.take(n)
        )
    }
}
