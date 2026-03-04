package com.migraineme

import android.util.Log

/**
 * MetricRegistry — Single source of truth for metric metadata.
 *
 * Populated on login from existing getAllTriggerPool() + getAllProdromePool() data.
 * Replaces SleepCardConfig, PhysicalCardConfig, MentalCardConfig, WeatherCardConfig,
 * and the metric portions of MonitorCardConfig.
 *
 * Key convention: metric_table for single-column tables,
 *                 metric_table::metric_column for multi-column tables.
 */
object MetricRegistry {

    private const val TAG = "MetricRegistry"

    data class Metric(
        val table: String,
        val column: String,
        val label: String,
        val unit: String,
        val category: String,
        val monitorGroup: String,
        val iconKey: String?,
        val source: String,          // "trigger" or "prodrome"
        val displayOrder: Int = 100, // lower = shown first in group
    ) {
        /** Unique key used everywhere: prefs, favorites, lookups */
        val key: String get() = if (isMultiColumnTable(table)) "$table::$column" else table
    }

    // ── State ────────────────────────────────────────────────────────────────

    private var byKey: Map<String, Metric> = emptyMap()
    private var byGroup: Map<String, List<Metric>> = emptyMap()
    private var byTableColumn: Map<String, Metric> = emptyMap()

    fun isLoaded(): Boolean = byKey.isNotEmpty()

    // ── Initialization ───────────────────────────────────────────────────────

    /**
     * Populate from the trigger + prodrome pools the app already fetches.
     * Call once after login / on app init.
     */
    fun init(
        triggers: List<SupabaseDbService.UserTriggerRow>,
        prodromes: List<SupabaseDbService.UserProdromeRow>
    ) {
        val metrics = mutableMapOf<String, Metric>()

        // Process triggers
        for (t in triggers) {
            val table = t.metricTable ?: continue
            val column = t.metricColumn ?: continue
            val category = t.category ?: continue
            val tcKey = "$table::$column"
            if (tcKey in metrics) continue  // deduplicate (high/low share same table::column)

            val metric = buildMetric(
                table = table,
                column = column,
                strippedLabel = extractStrippedLabel(t.label, t.direction),
                unit = t.unit ?: "",
                category = category,
                iconKey = t.iconKey,
                source = "trigger"
            )
            metrics[tcKey] = metric
        }

        // Process prodromes (may add new metrics or fill gaps)
        for (p in prodromes) {
            val table = p.metricTable ?: continue
            val column = p.metricColumn ?: continue
            val category = p.category ?: continue
            val tcKey = "$table::$column"
            if (tcKey in metrics) continue

            val metric = buildMetric(
                table = table,
                column = column,
                strippedLabel = extractStrippedLabel(p.label, p.direction),
                unit = p.unit ?: "",
                category = category,
                iconKey = p.iconKey,
                source = "prodrome"
            )
            metrics[tcKey] = metric
        }

        // Build lookup maps
        val allMetrics = metrics.values.toList()
        byKey = allMetrics.associateBy { it.key }
        byTableColumn = allMetrics.associateBy { "${it.table}::${it.column}" }
        byGroup = allMetrics.groupBy { it.monitorGroup }
            .mapValues { (_, list) -> list.sortedBy { it.displayOrder } }

        Log.d(TAG, "Initialized with ${allMetrics.size} metrics across ${byGroup.size} groups")
    }

    // ── Lookups ──────────────────────────────────────────────────────────────

    fun get(key: String): Metric? = byKey[key]

    fun label(key: String): String = byKey[key]?.label ?: key

    fun unit(key: String): String = byKey[key]?.unit ?: ""

    fun table(key: String): String = byKey[key]?.table ?: ""

    fun column(key: String): String = byKey[key]?.column ?: ""

    fun byGroup(group: String): List<Metric> = byGroup[group] ?: emptyList()

    fun allMetrics(): List<Metric> = byKey.values.toList()

    fun allGroups(): List<String> = byGroup.keys.toList()

    fun findByTableColumn(table: String, column: String): Metric? =
        byTableColumn["$table::$column"]

    fun findByTable(table: String): List<Metric> =
        byKey.values.filter { it.table == table }

    /** All graphable metrics for a group (currently: all of them) */
    fun graphableByGroup(group: String): List<Metric> = byGroup(group)

    /** Default display metrics for a group (first 3) */
    fun defaultDisplayKeys(group: String): List<String> =
        byGroup(group).take(3).map { it.key }

    // ── Data Settings integration ───────────────────────────────────────────

    /**
     * Maps registry key → data_settings metric key.
     *
     * For most metrics these are identical (e.g. "sleep_duration_daily" → "sleep_duration_daily").
     * Weather metrics differ because multiple registry keys share the user_weather_daily table
     * but have individual data_settings toggles (temperature_daily, pressure_daily, etc.).
     * Location metrics similarly map user_location_daily columns → "user_location_daily".
     */
    private val REGISTRY_TO_SETTINGS = mapOf(
        // Environment: weather columns → individual settings toggles
        "user_weather_daily::temp_c_mean"        to "temperature_daily",
        "user_weather_daily::pressure_hpa_mean"  to "pressure_daily",
        "user_weather_daily::humidity_pct_mean"   to "humidity_daily",
        "user_weather_daily::wind_speed_mps_mean" to "wind_daily",
        "user_weather_daily::uv_index_max"        to "uv_daily",
        "user_location_daily::altitude_max_m"     to "user_location_daily",
        "user_location_daily::altitude_change_m"  to "user_location_daily",
        // Mental: noise index table differs from settings key
        "ambient_noise_index_daily::day_mean_lmean" to "ambient_noise_samples"
    )

    /** Returns the data_settings key for a registry key. Falls back to metric.table. */
    fun dataSettingsKey(registryKey: String): String {
        REGISTRY_TO_SETTINGS[registryKey]?.let { return it }
        // Default: single-column tables use table name directly as settings key
        return get(registryKey)?.table ?: registryKey
    }

    /**
     * Given a list of MetricSettingResponse from the backend, returns the set of
     * registry keys that are currently enabled. Metrics with no setting default to enabled.
     */
    fun enabledKeys(
        settings: List<EdgeFunctionsService.MetricSettingResponse>,
        group: String? = null
    ): Set<String> {
        val settingsMap = settings.associateBy { it.metric }
        val metrics = if (group != null) byGroup(group) else allMetrics()
        return metrics.filter { m ->
            val settingsKey = dataSettingsKey(m.key)
            val setting = settingsMap[settingsKey]
            setting == null || setting.enabled  // default enabled if no setting exists
        }.map { it.key }.toSet()
    }

    /**
     * Converts a registry key to the legacy WeatherCardConfig key format.
     * Used during migration while MonitorScreen still uses old-style keys.
     * Remove once MonitorScreen is fully migrated.
     */
    private val REGISTRY_TO_LEGACY = mapOf(
        // Environment
        "user_location_daily::altitude_max_m" to "altitude_m",
        // Mental
        "stress_index_daily" to "stress",
        "screen_time_daily" to "screen_time",
        "screen_time_late_night" to "late_screen_time",
        "ambient_noise_index_daily::day_mean_lmean" to "noise",
        "phone_brightness_daily" to "brightness",
        "phone_volume_daily" to "volume",
        "phone_dark_mode_daily" to "dark_mode",
        "phone_unlock_daily" to "unlocks",
        // Sleep
        "sleep_duration_daily" to "duration",
        "fell_asleep_time_daily" to "fell_asleep",
        "woke_up_time_daily" to "woke_up",
        "sleep_score_daily" to "score",
        "sleep_efficiency_daily" to "efficiency",
        "sleep_disturbances_daily" to "disturbances",
        "sleep_stages_daily::value_sws_hm" to "stages_deep",
        "sleep_stages_daily::value_rem_hm" to "stages_rem",
        "sleep_stages_daily::value_light_hm" to "stages_light",
        // Physical
        "recovery_score_daily" to "recovery",
        "hrv_daily" to "hrv",
        "resting_hr_daily" to "resting_hr",
        "spo2_daily" to "spo2",
        "skin_temp_daily" to "skin_temp",
        "respiratory_rate_daily" to "respiratory_rate",
        "time_in_high_hr_zones_daily" to "high_hr_zones",
        "steps_daily" to "steps",
        "weight_daily" to "weight",
        "body_fat_daily" to "body_fat",
        "blood_pressure_daily::systolic_mmhg" to "blood_pressure",
        "blood_glucose_daily" to "blood_glucose"
    )

    fun toLegacyKey(registryKey: String): String {
        REGISTRY_TO_LEGACY[registryKey]?.let { return it }
        // Default: strip table prefix → "user_weather_daily::pressure_hpa_mean" → "pressure_hpa_mean"
        return registryKey.substringAfter("::", registryKey)
    }

    fun fromLegacyKey(legacyKey: String): String? {
        // Reverse lookup
        REGISTRY_TO_LEGACY.entries.firstOrNull { it.value == legacyKey }?.let { return it.key }
        // Try to find by column match
        return byKey.values.firstOrNull { it.column == legacyKey }?.key
    }

    // ── Multi-column tables ──────────────────────────────────────────────────

    private val MULTI_COLUMN_TABLES = setOf(
        "sleep_stages_daily",
        "user_weather_daily",
        "user_location_daily",
        "nutrition_daily",
        "ambient_noise_index_daily",  // has mean, but graph also uses max/min
        "blood_pressure_daily"        // has systolic, display also uses diastolic
    )

    fun isMultiColumnTable(table: String): Boolean = table in MULTI_COLUMN_TABLES

    /**
     * Companion columns: display-only columns from the same table that aren't
     * independent triggers/prodromes but are needed for display.
     *
     * Returns extra columns to fetch alongside a metric's primary column.
     */
    fun companionColumns(table: String, column: String): List<String> = when {
        // Blood pressure: always fetch diastolic alongside systolic
        table == "blood_pressure_daily" && column == "systolic_mmhg" ->
            listOf("value_diastolic")
        // Noise: graph shows high/low range alongside average
        table == "ambient_noise_index_daily" && column == "day_mean_lmean" ->
            listOf("day_max_lmax", "day_min_lmean")
        else -> emptyList()
    }

    // ── Category → Monitor group mapping ─────────────────────────────────────

    fun categoryToGroup(category: String): String = when (category) {
        "Sleep" -> "sleep"
        "Body" -> "physical"
        "Physical" -> "physical"
        "Cognitive" -> "mental"
        "Sensory" -> "mental"
        "Environment" -> "environment"
        "Diet" -> "nutrition"
        "Menstrual Cycle" -> "menstruation"
        else -> "physical"
    }

    // ── Display order within groups ──────────────────────────────────────────
    // Lower number = shown first. Metrics not listed get 100.

    private val DISPLAY_ORDER: Map<String, Int> = mapOf(
        // Sleep — defaults: Duration, Efficiency, Disturbances
        "sleep_duration_daily" to 1,
        "sleep_efficiency_daily" to 2,
        "sleep_disturbances_daily" to 3,
        "sleep_score_daily" to 4,
        "fell_asleep_time_daily" to 5,
        "woke_up_time_daily" to 6,
        "sleep_stages_daily::value_sws_hm" to 7,
        "sleep_stages_daily::value_rem_hm" to 8,
        "sleep_stages_daily::value_light_hm" to 9,
        // Physical — defaults: Recovery, HRV, High HR Zones
        "recovery_score_daily" to 1,
        "hrv_daily" to 2,
        "time_in_high_hr_zones_daily" to 3,
        "resting_hr_daily" to 4,
        "spo2_daily" to 5,
        "skin_temp_daily" to 6,
        "respiratory_rate_daily" to 7,
        "stress_index_daily" to 8,
        "steps_daily" to 9,
        "weight_daily" to 10,
        "body_fat_daily" to 11,
        "blood_pressure_daily::systolic_mmhg" to 12,
        "blood_glucose_daily" to 13,
        // Mental — defaults: Ambient Noise, Late Night Screen, Screen Time
        "ambient_noise_index_daily::day_mean_lmean" to 1,
        "screen_time_late_night" to 2,
        "screen_time_daily" to 3,
        "phone_unlock_daily" to 4,
        "phone_brightness_daily" to 5,
        "phone_volume_daily" to 6,
        "phone_dark_mode_daily" to 7,
        // Environment — defaults: Temperature, Humidity, Pressure
        "user_weather_daily::temp_c_mean" to 1,
        "user_weather_daily::humidity_pct_mean" to 2,
        "user_weather_daily::pressure_hpa_mean" to 3,
        "user_weather_daily::wind_speed_mps_mean" to 4,
        "user_weather_daily::uv_index_max" to 5,
        "user_location_daily::altitude_max_m" to 6,
        "user_location_daily::altitude_change_m" to 7,
        // Nutrition — defaults: Tyramine Exposure, Magnesium, Caffeine
        "nutrition_daily::max_tyramine_exposure" to 1,
        "nutrition_daily::total_magnesium_mg" to 2,
        "nutrition_daily::total_caffeine_mg" to 3,
        "nutrition_daily::total_calories" to 4,
        "nutrition_daily::total_protein_g" to 5,
        "nutrition_daily::total_carbs_g" to 6,
        "nutrition_daily::total_fat_g" to 7,
        "nutrition_daily::total_fiber_g" to 8,
        "nutrition_daily::total_sugar_g" to 9,
        "nutrition_daily::total_sodium_mg" to 10,
        "nutrition_daily::total_cholesterol_mg" to 11,
        "nutrition_daily::total_saturated_fat_g" to 12,
        "nutrition_daily::total_unsaturated_fat_g" to 13,
        "nutrition_daily::total_trans_fat_g" to 14,
        "nutrition_daily::total_potassium_mg" to 15,
        "nutrition_daily::total_calcium_mg" to 16,
        "nutrition_daily::total_iron_mg" to 17,
        "nutrition_daily::total_zinc_mg" to 18,
        "nutrition_daily::total_selenium_mcg" to 19,
        "nutrition_daily::total_phosphorus_mg" to 20,
        "nutrition_daily::total_copper_mg" to 21,
        "nutrition_daily::total_manganese_mg" to 22,
        "nutrition_daily::total_vitamin_a_mcg" to 23,
        "nutrition_daily::total_vitamin_c_mg" to 24,
        "nutrition_daily::total_vitamin_d_mcg" to 25,
        "nutrition_daily::total_vitamin_e_mg" to 26,
        "nutrition_daily::total_vitamin_k_mcg" to 27,
        "nutrition_daily::total_vitamin_b6_mg" to 28,
        "nutrition_daily::total_vitamin_b12_mcg" to 29,
        "nutrition_daily::total_thiamin_mg" to 30,
        "nutrition_daily::total_riboflavin_mg" to 31,
        "nutrition_daily::total_niacin_mg" to 32,
        "nutrition_daily::total_folate_mcg" to 33,
        "nutrition_daily::total_biotin_mcg" to 34,
        "nutrition_daily::total_pantothenic_acid_mg" to 35,
        "nutrition_daily::max_alcohol_exposure" to 36,
        "nutrition_daily::max_gluten_exposure" to 37,
    )

    // ── Label overrides ──────────────────────────────────────────────────────
    // Where stripped_label.titleCase() doesn't match the desired display label.

    private val LABEL_OVERRIDES: Map<String, String> = mapOf(
        "hrv_daily" to "HRV",
        "resting_hr_daily" to "Resting HR",
        "spo2_daily" to "SpO2",
        "respiratory_rate_daily" to "Resp. Rate",
        "time_in_high_hr_zones_daily" to "High HR Zones",
        "sleep_stages_daily::value_rem_hm" to "REM Sleep",
        "fell_asleep_time_daily" to "Fell Asleep",
        "woke_up_time_daily" to "Woke Up",
        "sleep_duration_daily" to "Duration",
        "sleep_score_daily" to "Score",
        "sleep_efficiency_daily" to "Efficiency",
        "sleep_disturbances_daily" to "Disturbances",
        "screen_time_late_night" to "Late Screen",
        "phone_unlock_daily" to "Unlocks",
        "phone_brightness_daily" to "Brightness",
        "phone_volume_daily" to "Volume",
        "phone_dark_mode_daily" to "Dark Mode",
        "screen_time_daily" to "Screen Time",
        "ambient_noise_index_daily" to "Noise",
        // Nutrition B-vitamin display names
        "nutrition_daily::total_thiamin_mg" to "Thiamin (B1)",
        "nutrition_daily::total_riboflavin_mg" to "Riboflavin (B2)",
        "nutrition_daily::total_niacin_mg" to "Niacin (B3)",
        "nutrition_daily::total_pantothenic_acid_mg" to "Pantothenic (B5)",
        "nutrition_daily::total_biotin_mcg" to "Biotin (B7)",
        "nutrition_daily::total_folate_mcg" to "Folate (B9)",
        "nutrition_daily::total_saturated_fat_g" to "Sat. Fat",
        "nutrition_daily::total_unsaturated_fat_g" to "Unsat. Fat",
        "nutrition_daily::max_tyramine_exposure" to "Tyramine",
        "nutrition_daily::max_alcohol_exposure" to "Alcohol",
        "nutrition_daily::max_gluten_exposure" to "Gluten",
    )

    // ── Unit normalisation ───────────────────────────────────────────────────
    // Template units → display units (only where they differ)

    private fun normaliseUnit(templateUnit: String): String = when (templateUnit) {
        "hours" -> "h"
        "count" -> ""
        "time" -> "time"   // keep as marker for formatter
        "kcal" -> ""        // calories displayed without unit suffix
        else -> templateUnit
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Extract the base label from a template label like "Sleep duration high" or "HRV low".
     * Strips the direction suffix (high/low/early/late) to get the metric name.
     */
    private fun extractStrippedLabel(label: String, direction: String?): String {
        if (direction == null) return label
        val suffixes = listOf(" high", " low", " early", " late")
        var result = label
        for (suffix in suffixes) {
            if (result.lowercase().endsWith(suffix)) {
                result = result.dropLast(suffix.length)
                break
            }
        }
        return result
    }

    private fun buildMetric(
        table: String,
        column: String,
        strippedLabel: String,
        unit: String,
        category: String,
        iconKey: String?,
        source: String
    ): Metric {
        val group = categoryToGroup(category)
        val key = if (isMultiColumnTable(table)) "$table::$column" else table
        val displayLabel = LABEL_OVERRIDES[key]
            ?: strippedLabel.split(" ").joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercase() }
            }
        val order = DISPLAY_ORDER[key] ?: 100

        return Metric(
            table = table,
            column = column,
            label = displayLabel,
            unit = normaliseUnit(unit),
            category = category,
            monitorGroup = group,
            iconKey = iconKey,
            source = source,
            displayOrder = order
        )
    }

    // ── Nutrition legacy key bridge ───────────────────────────────────────────
    // Maps registry keys ↔ old MonitorCardConfig metric identifiers used by
    // NutritionLogItem.metricValue() and nutrition UI.

    private val REGISTRY_TO_LEGACY_NUTRITION: Map<String, String> = mapOf(
        "nutrition_daily::total_calories" to "calories",
        "nutrition_daily::total_protein_g" to "protein",
        "nutrition_daily::total_carbs_g" to "carbs",
        "nutrition_daily::total_fat_g" to "fat",
        "nutrition_daily::total_fiber_g" to "fiber",
        "nutrition_daily::total_sugar_g" to "sugar",
        "nutrition_daily::total_sodium_mg" to "sodium",
        "nutrition_daily::total_caffeine_mg" to "caffeine",
        "nutrition_daily::total_cholesterol_mg" to "cholesterol",
        "nutrition_daily::total_saturated_fat_g" to "saturated_fat",
        "nutrition_daily::total_unsaturated_fat_g" to "unsaturated_fat",
        "nutrition_daily::total_trans_fat_g" to "trans_fat",
        "nutrition_daily::total_potassium_mg" to "potassium",
        "nutrition_daily::total_calcium_mg" to "calcium",
        "nutrition_daily::total_iron_mg" to "iron",
        "nutrition_daily::total_magnesium_mg" to "magnesium",
        "nutrition_daily::total_zinc_mg" to "zinc",
        "nutrition_daily::total_selenium_mcg" to "selenium",
        "nutrition_daily::total_phosphorus_mg" to "phosphorus",
        "nutrition_daily::total_copper_mg" to "copper",
        "nutrition_daily::total_manganese_mg" to "manganese",
        "nutrition_daily::total_vitamin_a_mcg" to "vitamin_a",
        "nutrition_daily::total_vitamin_c_mg" to "vitamin_c",
        "nutrition_daily::total_vitamin_d_mcg" to "vitamin_d",
        "nutrition_daily::total_vitamin_e_mg" to "vitamin_e",
        "nutrition_daily::total_vitamin_k_mcg" to "vitamin_k",
        "nutrition_daily::total_vitamin_b6_mg" to "vitamin_b6",
        "nutrition_daily::total_vitamin_b12_mcg" to "vitamin_b12",
        "nutrition_daily::total_thiamin_mg" to "thiamin",
        "nutrition_daily::total_riboflavin_mg" to "riboflavin",
        "nutrition_daily::total_niacin_mg" to "niacin",
        "nutrition_daily::total_folate_mcg" to "folate",
        "nutrition_daily::total_biotin_mcg" to "biotin",
        "nutrition_daily::total_pantothenic_acid_mg" to "pantothenic_acid",
        "nutrition_daily::max_tyramine_exposure" to "tyramine_exposure",
        "nutrition_daily::max_alcohol_exposure" to "alcohol_exposure",
        "nutrition_daily::max_gluten_exposure" to "gluten_exposure",
    )

    private val LEGACY_TO_REGISTRY_NUTRITION: Map<String, String> =
        REGISTRY_TO_LEGACY_NUTRITION.entries.associate { (k, v) -> v to k }

    /** Convert a registry key to the legacy nutrition key used by NutritionLogItem.metricValue(). */
    fun nutritionLegacyKey(registryKey: String): String =
        REGISTRY_TO_LEGACY_NUTRITION[registryKey] ?: registryKey

    /** Convert a legacy nutrition key to a registry key. */
    fun nutritionRegistryKey(legacyKey: String): String =
        LEGACY_TO_REGISTRY_NUTRITION[legacyKey] ?: legacyKey
}
