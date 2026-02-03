package com.migraineme

/**
 * Weather metric constants and labels.
 * Mirrors MonitorCardConfig structure for consistency.
 */
object WeatherCardConfig {
    // Metric keys
    const val METRIC_TEMPERATURE = "temp_c_mean"
    const val METRIC_PRESSURE = "pressure_hpa_mean"
    const val METRIC_HUMIDITY = "humidity_pct_mean"
    const val METRIC_WIND_SPEED = "wind_speed_mps_mean"
    const val METRIC_UV_INDEX = "uv_index_max"

    // All available weather metrics
    val ALL_WEATHER_METRICS = listOf(
        METRIC_TEMPERATURE,
        METRIC_PRESSURE,
        METRIC_HUMIDITY,
        METRIC_WIND_SPEED,
        METRIC_UV_INDEX
    )

    // Default metrics to display
    val DEFAULT_DISPLAY_METRICS = listOf(
        METRIC_TEMPERATURE,
        METRIC_PRESSURE,
        METRIC_HUMIDITY
    )

    // Human-readable labels
    val WEATHER_METRIC_LABELS = mapOf(
        METRIC_TEMPERATURE to "Temperature",
        METRIC_PRESSURE to "Pressure",
        METRIC_HUMIDITY to "Humidity",
        METRIC_WIND_SPEED to "Wind Speed",
        METRIC_UV_INDEX to "UV Index"
    )

    // Units for each metric
    val WEATHER_METRIC_UNITS = mapOf(
        METRIC_TEMPERATURE to "°C",
        METRIC_PRESSURE to "hPa",
        METRIC_HUMIDITY to "%",
        METRIC_WIND_SPEED to "m/s",
        METRIC_UV_INDEX to ""
    )
}