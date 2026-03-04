package com.migraineme

import androidx.compose.ui.graphics.Color

/**
 * MetricPalette — Assigns colors to metrics for charts, chips, and monitor cards.
 *
 * Preserves all existing per-metric colors from the old config files so the
 * UI looks identical after migration. New metrics not in the explicit map
 * get a deterministic color from the palette based on their key hash.
 */
object MetricPalette {

    /**
     * Explicit color assignments — preserves the exact colors users see today.
     * Keyed by MetricRegistry.Metric.key (table or table::column).
     */
    private val METRIC_COLORS: Map<String, Color> = mapOf(
        // ── Sleep (from SleepCardConfig) ─────────────────────────────────────
        "sleep_duration_daily" to Color(0xFF7986CB),
        "fell_asleep_time_daily" to Color(0xFF9575CD),
        "woke_up_time_daily" to Color(0xFF64B5F6),
        "sleep_score_daily" to Color(0xFF4FC3F7),
        "sleep_efficiency_daily" to Color(0xFF81C784),
        "sleep_disturbances_daily" to Color(0xFFFF8A65),
        "sleep_stages_daily::value_sws_hm" to Color(0xFF3F51B5),
        "sleep_stages_daily::value_rem_hm" to Color(0xFF7986CB),
        "sleep_stages_daily::value_light_hm" to Color(0xFFB0BEC5),

        // ── Physical (from PhysicalCardConfig) ───────────────────────────────
        "recovery_score_daily" to Color(0xFF81C784),
        "hrv_daily" to Color(0xFF4FC3F7),
        "resting_hr_daily" to Color(0xFFFF8A65),
        "spo2_daily" to Color(0xFF7986CB),
        "skin_temp_daily" to Color(0xFFFFB74D),
        "respiratory_rate_daily" to Color(0xFF9575CD),
        "stress_index_daily" to Color(0xFFE57373),
        "time_in_high_hr_zones_daily" to Color(0xFFFF7043),
        "steps_daily" to Color(0xFF66BB6A),
        "weight_daily" to Color(0xFF78909C),
        "body_fat_daily" to Color(0xFFBA68C8),
        "blood_pressure_daily" to Color(0xFFEF5350),
        "blood_glucose_daily" to Color(0xFFFFCA28),

        // ── Mental (from MentalCardConfig) ───────────────────────────────────
        "screen_time_daily" to Color(0xFF4FC3F7),
        "screen_time_late_night" to Color(0xFF7986CB),
        "ambient_noise_index_daily" to Color(0xFFFFB74D),
        "phone_brightness_daily" to Color(0xFFFFD54F),
        "phone_volume_daily" to Color(0xFF9575CD),
        "phone_dark_mode_daily" to Color(0xFF78909C),
        "phone_unlock_daily" to Color(0xFFFF8A65),

        // ── Environment (from WeatherGraph colors) ───────────────────────────
        "user_weather_daily::temp_c_mean" to Color(0xFFFF7043),
        "user_weather_daily::pressure_hpa_mean" to Color(0xFF42A5F5),
        "user_weather_daily::humidity_pct_mean" to Color(0xFF26C6DA),
        "user_weather_daily::wind_speed_mps_mean" to Color(0xFF66BB6A),
        "user_weather_daily::uv_index_max" to Color(0xFFFFCA28),
        "user_location_daily::altitude_max_m" to Color(0xFFCE93D8),
        "user_location_daily::altitude_change_m" to Color(0xFFBA68C8),

        // ── Nutrition (from NutritionGraph colors) ───────────────────────────
        "nutrition_daily::total_calories" to Color(0xFFFFB74D),
        "nutrition_daily::total_protein_g" to Color(0xFF81C784),
        "nutrition_daily::total_carbs_g" to Color(0xFF64B5F6),
        "nutrition_daily::total_fat_g" to Color(0xFFE57373),
        "nutrition_daily::total_fiber_g" to Color(0xFFBA68C8),
        "nutrition_daily::total_sugar_g" to Color(0xFFFF8A65),
        "nutrition_daily::total_sodium_mg" to Color(0xFF4DD0E1),
        "nutrition_daily::total_caffeine_mg" to Color(0xFFAED581),
        "nutrition_daily::total_cholesterol_mg" to Color(0xFFFFD54F),
        "nutrition_daily::total_saturated_fat_g" to Color(0xFFEF5350),
        "nutrition_daily::total_unsaturated_fat_g" to Color(0xFF66BB6A),
        "nutrition_daily::total_trans_fat_g" to Color(0xFFEC407A),
        "nutrition_daily::total_potassium_mg" to Color(0xFF26C6DA),
        "nutrition_daily::total_calcium_mg" to Color(0xFFAB47BC),
        "nutrition_daily::total_iron_mg" to Color(0xFF8D6E63),
        "nutrition_daily::total_magnesium_mg" to Color(0xFF5C6BC0),
        "nutrition_daily::total_zinc_mg" to Color(0xFF78909C),
        "nutrition_daily::total_selenium_mcg" to Color(0xFF78909C), // same as zinc in original
        "nutrition_daily::total_phosphorus_mg" to Color(0xFF26C6DA),
        "nutrition_daily::total_copper_mg" to Color(0xFF8D6E63),
        "nutrition_daily::total_manganese_mg" to Color(0xFF78909C),
        "nutrition_daily::total_vitamin_a_mcg" to Color(0xFFFFA726),
        "nutrition_daily::total_vitamin_c_mg" to Color(0xFFFFEE58),
        "nutrition_daily::total_vitamin_d_mcg" to Color(0xFFFFCA28),
        "nutrition_daily::total_vitamin_e_mg" to Color(0xFF9CCC65),
        "nutrition_daily::total_vitamin_k_mcg" to Color(0xFF26A69A),
        "nutrition_daily::total_vitamin_b6_mg" to Color(0xFF7E57C2),
        "nutrition_daily::total_vitamin_b12_mcg" to Color(0xFFEC407A),
        "nutrition_daily::total_thiamin_mg" to Color(0xFF42A5F5),
        "nutrition_daily::total_riboflavin_mg" to Color(0xFF66BB6A),
        "nutrition_daily::total_niacin_mg" to Color(0xFFFFCA28),
        "nutrition_daily::total_folate_mcg" to Color(0xFF26C6DA),
        "nutrition_daily::total_biotin_mcg" to Color(0xFFAB47BC),
        "nutrition_daily::total_pantothenic_acid_mg" to Color(0xFF8D6E63),
        "nutrition_daily::max_tyramine_exposure" to Color(0xFFEF5350),
        "nutrition_daily::max_alcohol_exposure" to Color(0xFFFF8F00),
        "nutrition_daily::max_gluten_exposure" to Color(0xFF1E88E5),
    )

    /**
     * Fallback palette for any new metric not in the explicit map.
     * 20 visually distinct colors on dark backgrounds.
     */
    private val FALLBACK_PALETTE = listOf(
        Color(0xFF7986CB), Color(0xFF81C784), Color(0xFF4FC3F7),
        Color(0xFFFF8A65), Color(0xFF9575CD), Color(0xFFFFB74D),
        Color(0xFF66BB6A), Color(0xFFE57373), Color(0xFFBA68C8),
        Color(0xFF4DD0E1), Color(0xFFFF7043), Color(0xFFAED581),
        Color(0xFF78909C), Color(0xFFFFD54F), Color(0xFF5C6BC0),
        Color(0xFFEF5350), Color(0xFF26C6DA), Color(0xFFEC407A),
        Color(0xFF8D6E63), Color(0xFFFFCA28)
    )

    /** Get the color for a metric. Explicit map first, then deterministic fallback. */
    fun colorFor(metricKey: String): Color {
        METRIC_COLORS[metricKey]?.let { return it }
        val index = (metricKey.hashCode() and Int.MAX_VALUE) % FALLBACK_PALETTE.size
        return FALLBACK_PALETTE[index]
    }

    /** Companion noise graph colors (not in MetricRegistry, display-only) */
    val NOISE_HIGH_COLOR = Color(0xFFFF7043)
    val NOISE_AVG_COLOR = Color(0xFFFFB74D)
    val NOISE_LOW_COLOR = Color(0xFF66BB6A)
}
