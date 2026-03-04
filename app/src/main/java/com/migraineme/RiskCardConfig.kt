package com.migraineme

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RiskCardConfigData(
    val favOfFavs: List<String> = emptyList()
)

object RiskCardConfigStore {
    private const val PREFS_NAME = "risk_card_config"
    private const val KEY_CONFIG = "config_json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(context: Context): RiskCardConfigData {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_CONFIG, null)
        return if (jsonStr != null) {
            try { json.decodeFromString<RiskCardConfigData>(jsonStr) }
            catch (_: Exception) { RiskCardConfigData() }
        } else { RiskCardConfigData() }
    }

    fun save(context: Context, config: RiskCardConfigData) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONFIG, json.encodeToString(config)).apply()
    }
}

data class FavPoolEntry(val key: String, val label: String, val category: String)

/** Build pool of all favorites from all category configs. */
fun buildFavoritesPool(context: Context): List<FavPoolEntry> {
    val pool = mutableListOf<FavPoolEntry>()

    // Helper: read from MetricDisplayStore, convert to legacy keys, fall back to old store
    fun addGroup(group: String, categoryLabel: String, oldStoreMetrics: List<String>, labelFn: (String) -> String) {
        val newKeys = MetricDisplayStore.getDisplayMetrics(context, group)
        if (newKeys.isNotEmpty()) {
            newKeys.forEach { registryKey ->
                val legacyKey = MetricRegistry.toLegacyKey(registryKey)
                val label = MetricRegistry.get(registryKey)?.label ?: labelFn(legacyKey)
                pool.add(FavPoolEntry("${group}:$legacyKey", label, categoryLabel))
            }
        } else {
            oldStoreMetrics.forEach {
                pool.add(FavPoolEntry("${categoryLabel.lowercase()}:$it", labelFn(it), categoryLabel))
            }
        }
    }

    // Use "environment" group but category label "Weather" for backward compat with resolveValue keys
    val envKeys = MetricDisplayStore.getDisplayMetrics(context, "environment")
    if (envKeys.isNotEmpty()) {
        envKeys.forEach { registryKey ->
            val legacyKey = MetricRegistry.toLegacyKey(registryKey)
            val label = MetricRegistry.get(registryKey)?.label ?: legacyKey
            pool.add(FavPoolEntry("weather:$legacyKey", label, "Weather"))
        }
    } else {
        WeatherCardConfigStore.load(context).weatherDisplayMetrics.forEach {
            pool.add(FavPoolEntry("weather:$it", WeatherCardConfig.WEATHER_METRIC_LABELS[it] ?: it, "Weather"))
        }
    }

    addGroup("sleep", "Sleep", SleepCardConfigStore.load(context).sleepDisplayMetrics) { SleepCardConfig.labelFor(it) }
    addGroup("physical", "Physical", PhysicalCardConfigStore.load(context).physicalDisplayMetrics) { PhysicalCardConfig.labelFor(it) }
    addGroup("mental", "Mental", MentalCardConfigStore.load(context).mentalDisplayMetrics) { MentalCardConfig.labelFor(it) }

    // Nutrition stays on old system
    MonitorCardConfigStore.load(context).nutritionDisplayMetrics.forEach {
        pool.add(FavPoolEntry("nutrition:$it", MonitorCardConfig.NUTRITION_METRIC_LABELS[it] ?: it, "Nutrition"))
    }
    return pool
}

/**
 * Get the effective 3 fav-of-favs. If none saved or saved ones are stale,
 * auto-pick first 3 from pool.
 */
fun getEffectiveFavOfFavs(context: Context): List<FavPoolEntry> {
    val pool = buildFavoritesPool(context)
    val saved = RiskCardConfigStore.load(context).favOfFavs
    val valid = saved.mapNotNull { key -> pool.find { it.key == key } }
    return if (valid.size >= 3) valid.take(3)
    else if (valid.isNotEmpty()) {
        // Pad with pool entries not already selected
        val remaining = pool.filter { it.key !in saved }
        (valid + remaining).take(3)
    } else {
        pool.take(3)
    }
}
