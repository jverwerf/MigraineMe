package com.migraineme

import android.content.Context

data class SleepCardConfigData(
    val sleepDisplayMetrics: List<String>
)

object SleepCardConfigStore {
    private const val PREFS_NAME = "sleep_card_config"
    private const val KEY_DISPLAY_METRICS = "sleep_display_metrics"

    fun load(context: Context): SleepCardConfigData {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val metricsStr = prefs.getString(KEY_DISPLAY_METRICS, null)
        val metrics: List<String> = if (metricsStr.isNullOrBlank()) {
            SleepCardConfig.DEFAULT_DISPLAY_METRICS
        } else {
            metricsStr.split(",").filter { it.isNotBlank() }
        }
        return SleepCardConfigData(sleepDisplayMetrics = metrics)
    }

    fun save(context: Context, config: SleepCardConfigData) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_DISPLAY_METRICS, config.sleepDisplayMetrics.joinToString(","))
            .apply()
    }
}
