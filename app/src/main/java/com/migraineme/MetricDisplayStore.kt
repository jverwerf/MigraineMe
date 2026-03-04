package com.migraineme

import android.content.Context
import android.util.Log

/**
 * MetricDisplayStore — Stores the user's chosen display metrics per monitor group.
 *
 * Each monitor card shows up to 3 selected metrics. This replaces:
 * - SleepCardConfigStore
 * - PhysicalCardConfigStore
 * - MentalCardConfigStore
 * - WeatherCardConfigStore
 * - MonitorCardConfig.nutritionDisplayMetrics
 *
 * Storage format: SharedPreferences with key "display_{group}" → comma-separated metric keys.
 * Metric keys use the MetricRegistry convention (table or table::column).
 */
object MetricDisplayStore {

    private const val TAG = "MetricDisplayStore"
    private const val PREFS_NAME = "metric_display_prefs"
    private const val MAX_DISPLAY_METRICS = 3

    // ── Read / Write ─────────────────────────────────────────────────────────

    /**
     * Get the user's chosen display metrics for a monitor group.
     * Returns up to [MAX_DISPLAY_METRICS] metric keys.
     * Falls back to MetricRegistry defaults if no preference is saved.
     */
    fun getDisplayMetrics(context: Context, group: String): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val csv = prefs.getString("display_$group", null)
        if (!csv.isNullOrBlank()) {
            val keys = csv.split(",").filter { it.isNotBlank() }
            // Validate that saved keys still exist in MetricRegistry
            val valid = keys.filter { MetricRegistry.get(it) != null }
            if (valid.isNotEmpty()) return valid.take(MAX_DISPLAY_METRICS)
        }
        // Fallback: first 3 metrics from the registry for this group
        return MetricRegistry.defaultDisplayKeys(group)
    }

    /**
     * Save the user's chosen display metrics for a monitor group.
     */
    fun setDisplayMetrics(context: Context, group: String, keys: List<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("display_$group", keys.take(MAX_DISPLAY_METRICS).joinToString(","))
            .apply()
    }

    /**
     * Toggle a metric on/off for a monitor group's display.
     * Returns the updated list.
     */
    fun toggleMetric(context: Context, group: String, metricKey: String): List<String> {
        val current = getDisplayMetrics(context, group).toMutableList()
        if (metricKey in current) {
            current.remove(metricKey)
        } else if (current.size < MAX_DISPLAY_METRICS) {
            current.add(metricKey)
        }
        setDisplayMetrics(context, group, current)
        return current
    }

    // ── Migration from old per-category stores ───────────────────────────────

    /**
     * One-time migration from the old per-category SharedPreferences to the new
     * unified store. Call once on app update. Safe to call multiple times — it
     * checks if migration has already been done.
     *
     * Old stores:
     * - "sleep_card_config" / "sleep_display_metrics" → keys like "duration", "fell_asleep"
     * - "physical_card_config" / "physical_display_metrics" → keys like "recovery", "hrv"
     * - "mental_card_config" / "mental_display_metrics" → keys like "stress", "screen_time"
     * - "weather_card_config" / "weather_display_metrics" → keys like "temp_c_mean", "pressure_hpa_mean"
     * - "monitor_card_config" / "config_json" → JSON with nutritionDisplayMetrics list
     */
    fun migrateFromOldStores(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean("migration_done_v1", false)) return

        Log.d(TAG, "Starting migration from old card config stores")

        migrateCategoryStore(context, "sleep_card_config", "sleep_display_metrics", "sleep", OLD_SLEEP_KEY_MAP)
        migrateCategoryStore(context, "physical_card_config", "physical_display_metrics", "physical", OLD_PHYSICAL_KEY_MAP)
        migrateCategoryStore(context, "mental_card_config", "mental_display_metrics", "mental", OLD_MENTAL_KEY_MAP)
        migrateCategoryStore(context, "weather_card_config", "weather_display_metrics", "environment", OLD_WEATHER_KEY_MAP)
        migrateNutritionStore(context)

        prefs.edit().putBoolean("migration_done_v1", true).apply()
        Log.d(TAG, "Migration complete")
    }

    private fun migrateCategoryStore(
        context: Context,
        oldPrefsName: String,
        oldKey: String,
        newGroup: String,
        keyMap: Map<String, String>
    ) {
        try {
            val oldPrefs = context.getSharedPreferences(oldPrefsName, Context.MODE_PRIVATE)
            val oldCsv = oldPrefs.getString(oldKey, null) ?: return
            val oldKeys = oldCsv.split(",").filter { it.isNotBlank() }
            val newKeys = oldKeys.mapNotNull { keyMap[it] }
            if (newKeys.isNotEmpty()) {
                setDisplayMetrics(context, newGroup, newKeys)
                Log.d(TAG, "Migrated $newGroup: $oldKeys → $newKeys")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Migration failed for $newGroup: ${e.message}")
        }
    }

    private fun migrateNutritionStore(context: Context) {
        try {
            val oldPrefs = context.getSharedPreferences("monitor_card_config", Context.MODE_PRIVATE)
            val jsonStr = oldPrefs.getString("config_json", null) ?: return
            // Parse nutritionDisplayMetrics from the JSON
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val oldConfig = json.decodeFromString<MonitorCardConfig>(jsonStr)
            val newKeys = oldConfig.nutritionDisplayMetrics.mapNotNull { OLD_NUTRITION_KEY_MAP[it] }
            if (newKeys.isNotEmpty()) {
                setDisplayMetrics(context, "nutrition", newKeys)
                Log.d(TAG, "Migrated nutrition: ${oldConfig.nutritionDisplayMetrics} → $newKeys")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Nutrition migration failed: ${e.message}")
        }
    }

    // ── Old key → new key mapping ────────────────────────────────────────────
    // Old keys were config-specific strings. New keys are metric_table or table::column.

    private val OLD_SLEEP_KEY_MAP = mapOf(
        "duration" to "sleep_duration_daily",
        "fell_asleep" to "fell_asleep_time_daily",
        "woke_up" to "woke_up_time_daily",
        "score" to "sleep_score_daily",
        "efficiency" to "sleep_efficiency_daily",
        "disturbances" to "sleep_disturbances_daily",
        "stages_deep" to "sleep_stages_daily::value_sws_hm",
        "stages_rem" to "sleep_stages_daily::value_rem_hm",
        "stages_light" to "sleep_stages_daily::value_light_hm",
    )

    private val OLD_PHYSICAL_KEY_MAP = mapOf(
        "recovery" to "recovery_score_daily",
        "hrv" to "hrv_daily",
        "resting_hr" to "resting_hr_daily",
        "spo2" to "spo2_daily",
        "skin_temp" to "skin_temp_daily",
        "respiratory_rate" to "respiratory_rate_daily",
        "stress" to "stress_index_daily",
        "high_hr_zones" to "time_in_high_hr_zones_daily",
        "steps" to "steps_daily",
        "weight" to "weight_daily",
        "body_fat" to "body_fat_daily",
        "blood_pressure" to "blood_pressure_daily",
        "blood_glucose" to "blood_glucose_daily",
    )

    private val OLD_MENTAL_KEY_MAP = mapOf(
        "stress" to "stress_index_daily",
        "screen_time" to "screen_time_daily",
        "late_screen_time" to "screen_time_late_night",
        "noise" to "ambient_noise_index_daily",
        "brightness" to "phone_brightness_daily",
        "volume" to "phone_volume_daily",
        "dark_mode" to "phone_dark_mode_daily",
        "unlocks" to "phone_unlock_daily",
    )

    private val OLD_WEATHER_KEY_MAP = mapOf(
        "temp_c_mean" to "user_weather_daily::temp_c_mean",
        "pressure_hpa_mean" to "user_weather_daily::pressure_hpa_mean",
        "humidity_pct_mean" to "user_weather_daily::humidity_pct_mean",
        "wind_speed_mps_mean" to "user_weather_daily::wind_speed_mps_mean",
        "uv_index_max" to "user_weather_daily::uv_index_max",
        "altitude_m" to "user_location_daily::altitude_max_m",
        "altitude_change_m" to "user_location_daily::altitude_change_m",
    )

    private val OLD_NUTRITION_KEY_MAP = mapOf(
        "calories" to "nutrition_daily::total_calories",
        "protein" to "nutrition_daily::total_protein_g",
        "carbs" to "nutrition_daily::total_carbs_g",
        "fat" to "nutrition_daily::total_fat_g",
        "fiber" to "nutrition_daily::total_fiber_g",
        "sugar" to "nutrition_daily::total_sugar_g",
        "sodium" to "nutrition_daily::total_sodium_mg",
        "caffeine" to "nutrition_daily::total_caffeine_mg",
        "cholesterol" to "nutrition_daily::total_cholesterol_mg",
        "saturated_fat" to "nutrition_daily::total_saturated_fat_g",
        "unsaturated_fat" to "nutrition_daily::total_unsaturated_fat_g",
        "trans_fat" to "nutrition_daily::total_trans_fat_g",
        "potassium" to "nutrition_daily::total_potassium_mg",
        "calcium" to "nutrition_daily::total_calcium_mg",
        "iron" to "nutrition_daily::total_iron_mg",
        "magnesium" to "nutrition_daily::total_magnesium_mg",
        "zinc" to "nutrition_daily::total_zinc_mg",
        "selenium" to "nutrition_daily::total_selenium_mcg",
        "phosphorus" to "nutrition_daily::total_phosphorus_mg",
        "copper" to "nutrition_daily::total_copper_mg",
        "manganese" to "nutrition_daily::total_manganese_mg",
        "vitamin_a" to "nutrition_daily::total_vitamin_a_mcg",
        "vitamin_c" to "nutrition_daily::total_vitamin_c_mg",
        "vitamin_d" to "nutrition_daily::total_vitamin_d_mcg",
        "vitamin_e" to "nutrition_daily::total_vitamin_e_mg",
        "vitamin_k" to "nutrition_daily::total_vitamin_k_mcg",
        "vitamin_b6" to "nutrition_daily::total_vitamin_b6_mg",
        "vitamin_b12" to "nutrition_daily::total_vitamin_b12_mcg",
        "thiamin" to "nutrition_daily::total_thiamin_mg",
        "riboflavin" to "nutrition_daily::total_riboflavin_mg",
        "niacin" to "nutrition_daily::total_niacin_mg",
        "folate" to "nutrition_daily::total_folate_mcg",
        "biotin" to "nutrition_daily::total_biotin_mcg",
        "pantothenic_acid" to "nutrition_daily::total_pantothenic_acid_mg",
        "tyramine_exposure" to "nutrition_daily::max_tyramine_exposure",
        "alcohol_exposure" to "nutrition_daily::max_alcohol_exposure",
        "gluten_exposure" to "nutrition_daily::max_gluten_exposure",
    )

    // ── Favorites migration ──────────────────────────────────────────────────

    /**
     * Migrate a favorites key from old format ("sleep:duration") to new format
     * ("sleep_duration_daily"). Returns null if unmappable.
     */
    fun migrateOldFavoriteKey(oldKey: String): String? {
        val parts = oldKey.split(":", limit = 2)
        if (parts.size != 2) return null
        val (group, metricKey) = parts
        return when (group) {
            "sleep" -> OLD_SLEEP_KEY_MAP[metricKey]
            "physical" -> OLD_PHYSICAL_KEY_MAP[metricKey]
            "mental" -> OLD_MENTAL_KEY_MAP[metricKey]
            "weather" -> OLD_WEATHER_KEY_MAP[metricKey]
            "nutrition" -> OLD_NUTRITION_KEY_MAP[metricKey]
            else -> null
        }
    }
}
