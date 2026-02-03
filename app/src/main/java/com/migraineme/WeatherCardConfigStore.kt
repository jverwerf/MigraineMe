package com.migraineme

import android.content.Context

/**
 * SharedPreferences store for weather display configuration.
 * Stores which weather metrics user wants to see on Monitor screen.
 */
data class WeatherCardConfigData(
    val weatherDisplayMetrics: List<String>
)

object WeatherCardConfigStore {
    private const val PREFS_NAME = "weather_card_config"
    private const val KEY_DISPLAY_METRICS = "weather_display_metrics"

    fun load(context: Context): WeatherCardConfigData {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val metricsStr = prefs.getString(KEY_DISPLAY_METRICS, null)
        val metrics = if (metricsStr.isNullOrBlank()) {
            WeatherCardConfig.DEFAULT_DISPLAY_METRICS
        } else {
            metricsStr.split(",").filter { it.isNotBlank() }
        }

        return WeatherCardConfigData(weatherDisplayMetrics = metrics)
    }

    fun save(context: Context, config: WeatherCardConfigData) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_DISPLAY_METRICS, config.weatherDisplayMetrics.joinToString(","))
            .apply()
    }
}