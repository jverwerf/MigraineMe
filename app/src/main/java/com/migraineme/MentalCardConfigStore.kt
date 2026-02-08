package com.migraineme

import android.content.Context

data class MentalCardConfigData(
    val mentalDisplayMetrics: List<String>
)

object MentalCardConfigStore {
    private const val PREFS_NAME = "mental_card_config"
    private const val KEY_DISPLAY_METRICS = "mental_display_metrics"

    fun load(context: Context): MentalCardConfigData {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val metricsStr = prefs.getString(KEY_DISPLAY_METRICS, null)
        val metrics: List<String> = if (metricsStr.isNullOrBlank()) {
            MentalCardConfig.DEFAULT_DISPLAY_METRICS
        } else {
            metricsStr.split(",").filter { it.isNotBlank() }
        }
        return MentalCardConfigData(mentalDisplayMetrics = metrics)
    }

    fun save(context: Context, config: MentalCardConfigData) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_DISPLAY_METRICS, config.mentalDisplayMetrics.joinToString(","))
            .apply()
    }
}
