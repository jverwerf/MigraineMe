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
    const val METRIC_THUNDERSTORM = "is_thunderstorm_day"
    const val METRIC_ALTITUDE = "altitude_m"
    const val METRIC_ALTITUDE_CHANGE = "altitude_change_m"
    const val METRIC_POLLEN = "pollen_overall_index"
    const val METRIC_POLLEN_TREE = "pollen_tree_index"
    const val METRIC_POLLEN_GRASS = "pollen_grass_index"
    const val METRIC_POLLEN_WEED = "pollen_weed_index"
    const val METRIC_PM25 = "pm2_5_mean"
    const val METRIC_PM10 = "pm10_mean"
    const val METRIC_OZONE = "ozone_max"

    // All available weather metrics
    val ALL_WEATHER_METRICS = listOf(
        METRIC_TEMPERATURE,
        METRIC_PRESSURE,
        METRIC_HUMIDITY,
        METRIC_WIND_SPEED,
        METRIC_UV_INDEX,
        METRIC_THUNDERSTORM,
        METRIC_ALTITUDE,
        METRIC_ALTITUDE_CHANGE,
        METRIC_POLLEN,
        METRIC_POLLEN_TREE,
        METRIC_POLLEN_GRASS,
        METRIC_POLLEN_WEED,
        METRIC_PM25,
        METRIC_PM10,
        METRIC_OZONE
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
        METRIC_UV_INDEX to "UV Index",
        METRIC_THUNDERSTORM to "Thunderstorm",
        METRIC_ALTITUDE to "Altitude",
        METRIC_ALTITUDE_CHANGE to "Altitude Change",
        METRIC_POLLEN to "Pollen",
        METRIC_POLLEN_TREE to "Tree Pollen",
        METRIC_POLLEN_GRASS to "Grass Pollen",
        METRIC_POLLEN_WEED to "Weed Pollen",
        METRIC_PM25 to "PM2.5",
        METRIC_PM10 to "PM10",
        METRIC_OZONE to "Ozone"
    )

    // Units for each metric
    val WEATHER_METRIC_UNITS = mapOf(
        METRIC_TEMPERATURE to "°C",
        METRIC_PRESSURE to "hPa",
        METRIC_HUMIDITY to "%",
        METRIC_WIND_SPEED to "m/s",
        METRIC_UV_INDEX to "",
        METRIC_THUNDERSTORM to "",
        METRIC_ALTITUDE to "m",
        METRIC_ALTITUDE_CHANGE to "m",
        METRIC_POLLEN to "",
        METRIC_POLLEN_TREE to "",
        METRIC_POLLEN_GRASS to "",
        METRIC_POLLEN_WEED to "",
        METRIC_PM25 to "µg/m³",
        METRIC_PM10 to "µg/m³",
        METRIC_OZONE to "µg/m³"
    )
}
