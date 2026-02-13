package com.migraineme

/**
 * Defines the structure of DataSettings sections and rows.
 * 
 * SINGLE SOURCE OF TRUTH for what metrics appear in the UI.
 * Update this file when adding/removing/reordering metrics.
 */
object DataSettingsSections {

    /**
     * Weather metrics that depend on location being enabled.
     * These get greyed out when location is off.
     */
    val weatherMetrics = setOf(
        "temperature_daily",
        "pressure_daily",
        "humidity_daily",
        "wind_daily",
        "uv_daily",
        "thunderstorm_daily"
    )

    /**
     * All sections displayed in the Data Settings screen.
     */
    fun getAllSections(): List<DataSection> = listOf(
        DataSection(
            title = "Sleep",
            rows = listOf(
                phoneOrWearableRow("sleep_duration_daily", "Sleep duration"),
                wearableRow("sleep_score_daily", "Sleep score"),
                wearableRow("sleep_efficiency_daily", "Sleep efficiency"),
                wearableRow("sleep_stages_daily", "Sleep stages"),
                wearableRow("sleep_disturbances_daily", "Sleep disturbances"),
                phoneOrWearableRow("fell_asleep_time_daily", "Fell asleep time"),
                phoneOrWearableRow("woke_up_time_daily", "Woke up time")
            )
        ),
        DataSection(
            title = "Physical Health",
            rows = listOf(
                wearableRow("recovery_score_daily", "Recovery score"),
                wearableRow("resting_hr_daily", "Resting heart rate"),
                wearableRow("hrv_daily", "HRV"),
                wearableRow("skin_temp_daily", "Skin temperature"),
                wearableRow("spo2_daily", "Blood oxygen (SpO2)"),
                wearableRow("time_in_high_hr_zones_daily", "Time in high HR zones"),
                wearableRow("activity_hr_zones_sessions", "Workout HR zones"),
                wearableRow("steps_daily", "Steps"),
                wearableRow("strain_daily", "Strain")
            )
        ),
        DataSection(
            title = "Mental Health",
            rows = listOf(
                computedRow("stress_index_daily", "Stress index"),
                phoneRow("screen_time_daily", "Phone screen time tracking"),
                phoneRow("screen_time_late_night", "Late night screen time"),
                phoneRow("ambient_noise_samples", "Noise Sampling"),
                phoneRow("phone_brightness_daily", "Phone brightness"),
                phoneRow("phone_volume_daily", "Phone volume"),
                phoneRow("phone_dark_mode_daily", "Dark mode usage"),
                phoneRow("phone_unlock_daily", "Phone unlocks")
            )
        ),
        DataSection(
            title = "Environment",
            rows = listOf(
                phoneRow("user_location_daily", "Location"),
                referenceRow("temperature_daily", "Temperature"),
                referenceRow("pressure_daily", "Pressure"),
                referenceRow("humidity_daily", "Humidity"),
                referenceRow("wind_daily", "Wind"),
                referenceRow("uv_daily", "UV index"),
                referenceRow("thunderstorm_daily", "Thunderstorms")
            )
        ),
        DataSection(
            title = "Diet",
            rows = listOf(
                phoneRow("nutrition", "Nutrition")
            )
        ),
        DataSection(
            title = "Menstruation",
            rows = listOf(
                phoneRow("menstruation", "Menstruation")
            )
        )
    )
}
