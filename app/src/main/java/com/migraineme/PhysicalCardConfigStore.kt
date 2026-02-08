package com.migraineme

import android.content.Context

data class PhysicalCardConfigData(
    val physicalDisplayMetrics: List<String>
)

object PhysicalCardConfigStore {
    private const val PREFS_NAME = "physical_card_config"
    private const val KEY_DISPLAY_METRICS = "physical_display_metrics"

    fun load(context: Context): PhysicalCardConfigData {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val metricsStr = prefs.getString(KEY_DISPLAY_METRICS, null)
        val metrics: List<String> = if (metricsStr.isNullOrBlank()) {
            PhysicalCardConfig.DEFAULT_DISPLAY_METRICS
        } else {
            metricsStr.split(",").filter { it.isNotBlank() }
        }
        return PhysicalCardConfigData(physicalDisplayMetrics = metrics)
    }

    fun save(context: Context, config: PhysicalCardConfigData) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_DISPLAY_METRICS, config.physicalDisplayMetrics.joinToString(","))
            .apply()
    }
}
