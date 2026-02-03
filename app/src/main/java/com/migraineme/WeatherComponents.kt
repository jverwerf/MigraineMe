package com.migraineme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Row displaying a weather metric with label and value.
 */
@Composable
fun WeatherMetricRow(label: String, value: Double?, unit: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = AppTheme.TitleColor, style = MaterialTheme.typography.bodySmall)
        Text(
            if (value != null && value != 0.0) {
                if (unit == "hPa") String.format("%.0f%s", value, unit)
                else String.format("%.1f%s", value, unit)
            } else "—",
            color = AppTheme.AccentPurple,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
        )
    }
}

/**
 * Today's weather summary card content.
 */
@Composable
fun TodayWeatherSummary(
    weather: WeatherDayData?,
    displayMetrics: List<String>
) {
    if (weather == null) {
        Text(
            "No weather data for today",
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.bodySmall
        )
        return
    }

    Column {
        // Weather condition
        val condition = weatherCodeToCondition(weather.weatherCode)
        Text(
            condition,
            color = AppTheme.TitleColor,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )

        if (weather.isThunderstormDay) {
            Text(
                "⚡ Thunderstorm detected",
                color = Color(0xFFFFB74D),
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Display selected metrics
        displayMetrics.forEach { metric ->
            val label = WeatherCardConfig.WEATHER_METRIC_LABELS[metric] ?: metric
            val unit = WeatherCardConfig.WEATHER_METRIC_UNITS[metric] ?: ""
            val value = getWeatherValue(weather, metric)
            WeatherMetricRow(label, value, unit)
        }
    }
}

/**
 * Helper to get value from WeatherDayData by metric key.
 */
private fun getWeatherValue(day: WeatherDayData, metric: String): Double? {
    return when (metric) {
        WeatherCardConfig.METRIC_TEMPERATURE -> day.tempMean
        WeatherCardConfig.METRIC_PRESSURE -> day.pressureMean
        WeatherCardConfig.METRIC_HUMIDITY -> day.humidityMean
        WeatherCardConfig.METRIC_WIND_SPEED -> day.windSpeedMean
        WeatherCardConfig.METRIC_UV_INDEX -> day.uvIndexMax
        else -> null
    }
}

/**
 * Convert WMO weather code to human-readable condition.
 */
private fun weatherCodeToCondition(code: Int): String {
    return when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75 -> "Snow"
        77 -> "Snow grains"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown"
    }
}