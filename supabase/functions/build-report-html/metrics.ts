// The metric registry, mirroring AllMetricDefs + TABLE_TO_KEY in
// InsightsViewModel.kt. It lives here so the report can pull its own series
// instead of depending on whatever the client happened to have charted.

export type MetricDef = {
  key: string;
  label: string;
  unit: string;
  color: string;
  table: string;
  column: string;
  group: string;
  /** How the raw column becomes a daily number. Absent = a plain numeric
   *  daily column. "time" is a timestamp read as hours-of-day (bedtime,
   *  wake-up). "record_sum" / "record_risk" live in nutrition_records —
   *  per-meal rows summed (or max risk) per day, the same aggregation the
   *  apps' Insights charts do. */
  kind?: "time" | "record_sum" | "record_risk";
  /** For kind "time": clock readings before noon are shifted +24h so a
   *  bedtime series doesn't zigzag across midnight. */
  shiftPastNoon?: boolean;
};

export const METRICS: MetricDef[] = [
  // Sleep
  { key: "sleep_dur",   label: "Sleep duration", unit: "h",  color: "#9575CD", table: "sleep_duration_daily",     column: "value_hours", group: "Sleep" },
  { key: "sleep_score", label: "Sleep score",    unit: "%",  color: "#7E57C2", table: "sleep_score_daily",        column: "value_pct",   group: "Sleep" },
  { key: "sleep_eff",   label: "Sleep efficiency", unit: "%", color: "#B39DDB", table: "sleep_efficiency_daily",  column: "value_pct",   group: "Sleep" },
  { key: "sleep_dist",  label: "Disturbances",   unit: "",   color: "#CE93D8", table: "sleep_disturbances_daily", column: "value_count", group: "Sleep" },
  { key: "sleep_deep",  label: "Deep sleep",     unit: "h",  color: "#5E35B1", table: "sleep_stages_daily",       column: "value_sws_hm", group: "Sleep" },
  { key: "sleep_rem",   label: "REM sleep",      unit: "h",  color: "#673AB7", table: "sleep_stages_daily",       column: "value_rem_hm", group: "Sleep" },
  { key: "sleep_light", label: "Light sleep",    unit: "h",  color: "#9575CD", table: "sleep_stages_daily",       column: "value_light_hm", group: "Sleep" },
  { key: "bedtime",     label: "Bedtime",        unit: "h",  color: "#3949AB", table: "fell_asleep_time_daily",   column: "value_at",    group: "Sleep", kind: "time", shiftPastNoon: true },
  { key: "wake_time",   label: "Wake time",      unit: "h",  color: "#26A69A", table: "woke_up_time_daily",       column: "value_at",    group: "Sleep", kind: "time" },

  // Body
  { key: "hrv",        label: "HRV",            unit: "ms", color: "#4DD0E1", table: "hrv_daily",             column: "value_rmssd_ms", group: "Body" },
  { key: "rhr",        label: "Resting HR",     unit: "bpm", color: "#EF5350", table: "resting_hr_daily",     column: "value_bpm",   group: "Body" },
  { key: "spo2",       label: "SpO2",           unit: "%",  color: "#4FC3F7", table: "spo2_daily",            column: "value_pct",   group: "Body" },
  { key: "resp_rate",  label: "Respiratory rate", unit: "bpm", color: "#26A69A", table: "respiratory_rate_daily", column: "value_bpm", group: "Body" },
  { key: "skin_temp",  label: "Skin temperature", unit: "°C", color: "#FF8A65", table: "skin_temp_daily",     column: "value_celsius", group: "Body" },
  { key: "recovery",   label: "Recovery",       unit: "%",  color: "#81C784", table: "recovery_score_daily",  column: "value_pct",   group: "Body" },
  { key: "stress",     label: "Stress index",   unit: "",   color: "#FFB74D", table: "stress_index_daily",    column: "value",       group: "Body" },
  { key: "steps",      label: "Steps",          unit: "",   color: "#AED581", table: "steps_daily",           column: "value_count", group: "Body" },
  { key: "strain",     label: "Strain",         unit: "kJ", color: "#FF7043", table: "strain_daily",          column: "value_kilojoule", group: "Body" },
  { key: "high_hr",    label: "High HR zones",  unit: "min", color: "#F06292", table: "time_in_high_hr_zones_daily", column: "value_minutes", group: "Body" },

  // Environment
  { key: "pressure",   label: "Pressure",       unit: "hPa", color: "#7986CB", table: "user_weather_daily", column: "pressure_hpa_mean", group: "Environment" },
  { key: "temp",       label: "Temperature",    unit: "°C",  color: "#FF8A65", table: "user_weather_daily", column: "temp_c_mean",       group: "Environment" },
  { key: "humidity",   label: "Humidity",       unit: "%",   color: "#4FC3F7", table: "user_weather_daily", column: "humidity_pct_mean", group: "Environment" },
  { key: "wind",       label: "Wind",           unit: "m/s", color: "#81C784", table: "user_weather_daily", column: "wind_speed_mps_mean", group: "Environment" },
  { key: "uv",         label: "UV index",       unit: "",    color: "#FFB74D", table: "user_weather_daily", column: "uv_index_max",      group: "Environment" },
  { key: "thunderstorm", label: "Thunderstorm", unit: "",    color: "#FFD54F", table: "user_weather_daily", column: "is_thunderstorm_day", group: "Environment" },
  { key: "altitude",   label: "Altitude",       unit: "m",   color: "#CE93D8", table: "user_location_daily", column: "altitude_max_m",   group: "Environment" },
  { key: "alt_change", label: "Altitude change", unit: "m",  color: "#BA68C8", table: "user_location_daily", column: "altitude_change_m", group: "Environment" },

  // Mind / phone
  { key: "screen_time", label: "Screen time",   unit: "h",  color: "#64B5F6", table: "screen_time_daily",        column: "total_hours",   group: "Cognitive" },
  { key: "late_screen", label: "Late screen",   unit: "h",  color: "#FF7043", table: "screen_time_late_night",   column: "value_hours",   group: "Cognitive" },
  { key: "noise",       label: "Noise",         unit: "",   color: "#A1887F", table: "ambient_noise_index_daily", column: "day_mean_lmean", group: "Cognitive" },
  { key: "brightness",  label: "Brightness",    unit: "%",  color: "#FFD54F", table: "phone_brightness_daily",   column: "value_mean",    group: "Cognitive" },
  { key: "volume",      label: "Phone volume",  unit: "%",  color: "#4DB6AC", table: "phone_volume_daily",       column: "value_mean_pct", group: "Cognitive" },
  { key: "unlocks",     label: "Phone unlocks", unit: "",   color: "#90A4AE", table: "phone_unlock_daily",       column: "value_count",   group: "Cognitive" },
  { key: "dark_mode",   label: "Dark mode",     unit: "h",  color: "#546E7A", table: "phone_dark_mode_daily",    column: "value_hours",   group: "Cognitive" },

  // Wellness / diet
  { key: "hydration",   label: "Hydration",     unit: "ml", color: "#4FC3F7", table: "hydration_daily",    column: "value_ml",         group: "Wellness" },
  { key: "mindfulness", label: "Mindfulness",   unit: "min", color: "#81C784", table: "mindfulness_daily", column: "duration_minutes", group: "Wellness" },

  // Diet — daily rollups (nutrition_daily)
  { key: "calories",    label: "Calories",      unit: "kcal", color: "#FFB74D", table: "nutrition_daily",  column: "total_calories",   group: "Diet" },
  { key: "protein",     label: "Protein",       unit: "g",  color: "#EF9A9A", table: "nutrition_daily",    column: "total_protein_g",  group: "Diet" },
  { key: "carbs",       label: "Carbs",         unit: "g",  color: "#FFCC80", table: "nutrition_daily",    column: "total_carbs_g",    group: "Diet" },
  { key: "fat",         label: "Fat",           unit: "g",  color: "#FFE082", table: "nutrition_daily",    column: "total_fat_g",      group: "Diet" },
  { key: "fiber",       label: "Fiber",         unit: "g",  color: "#A5D6A7", table: "nutrition_daily",    column: "total_fiber_g",    group: "Diet" },
  { key: "sugar",       label: "Sugar",         unit: "g",  color: "#F48FB1", table: "nutrition_daily",    column: "total_sugar_g",    group: "Diet" },
  { key: "sodium",      label: "Sodium",        unit: "mg", color: "#B39DDB", table: "nutrition_daily",    column: "total_sodium_mg",  group: "Diet" },
  { key: "caffeine",    label: "Caffeine",      unit: "mg", color: "#AED581", table: "nutrition_daily",    column: "total_caffeine_mg", group: "Diet" },

  // Diet — per-meal nutrients (nutrition_records, summed per day)
  { key: "cholesterol", label: "Cholesterol",   unit: "mg", color: "#FFAB91", table: "nutrition_records",  column: "cholesterol",      group: "Diet", kind: "record_sum" },
  { key: "sat_fat",     label: "Saturated fat", unit: "g",  color: "#EF9A9A", table: "nutrition_records",  column: "saturated_fat",    group: "Diet", kind: "record_sum" },
  { key: "unsat_fat",   label: "Unsaturated fat", unit: "g", color: "#FFCC80", table: "nutrition_records", column: "unsaturated_fat",  group: "Diet", kind: "record_sum" },
  { key: "trans_fat",   label: "Trans fat",     unit: "g",  color: "#E57373", table: "nutrition_records",  column: "trans_fat",        group: "Diet", kind: "record_sum" },
  { key: "potassium",   label: "Potassium",     unit: "mg", color: "#80CBC4", table: "nutrition_records",  column: "potassium",        group: "Diet", kind: "record_sum" },
  { key: "calcium",     label: "Calcium",       unit: "mg", color: "#B0BEC5", table: "nutrition_records",  column: "calcium",          group: "Diet", kind: "record_sum" },
  { key: "iron",        label: "Iron",          unit: "mg", color: "#BCAAA4", table: "nutrition_records",  column: "iron",             group: "Diet", kind: "record_sum" },
  { key: "magnesium",   label: "Magnesium",     unit: "mg", color: "#80DEEA", table: "nutrition_records",  column: "magnesium",        group: "Diet", kind: "record_sum" },
  { key: "zinc",        label: "Zinc",          unit: "mg", color: "#CE93D8", table: "nutrition_records",  column: "zinc",             group: "Diet", kind: "record_sum" },
  { key: "selenium",    label: "Selenium",      unit: "mcg", color: "#FFD54F", table: "nutrition_records", column: "selenium",         group: "Diet", kind: "record_sum" },
  { key: "phosphorus",  label: "Phosphorus",    unit: "mg", color: "#A5D6A7", table: "nutrition_records",  column: "phosphorus",       group: "Diet", kind: "record_sum" },
  { key: "copper",      label: "Copper",        unit: "mg", color: "#FFAB91", table: "nutrition_records",  column: "copper",           group: "Diet", kind: "record_sum" },
  { key: "manganese",   label: "Manganese",     unit: "mg", color: "#B39DDB", table: "nutrition_records",  column: "manganese",        group: "Diet", kind: "record_sum" },
  { key: "vitamin_a",   label: "Vitamin A",     unit: "mcg", color: "#FFCC80", table: "nutrition_records", column: "vitamin_a",        group: "Diet", kind: "record_sum" },
  { key: "vitamin_c",   label: "Vitamin C",     unit: "mg", color: "#FFE082", table: "nutrition_records",  column: "vitamin_c",        group: "Diet", kind: "record_sum" },
  { key: "vitamin_d",   label: "Vitamin D",     unit: "mcg", color: "#FFF176", table: "nutrition_records", column: "vitamin_d",        group: "Diet", kind: "record_sum" },
  { key: "vitamin_e",   label: "Vitamin E",     unit: "mg", color: "#A5D6A7", table: "nutrition_records",  column: "vitamin_e",        group: "Diet", kind: "record_sum" },
  { key: "vitamin_k",   label: "Vitamin K",     unit: "mcg", color: "#81C784", table: "nutrition_records", column: "vitamin_k",        group: "Diet", kind: "record_sum" },
  { key: "vitamin_b6",  label: "Vitamin B6",    unit: "mg", color: "#80CBC4", table: "nutrition_records",  column: "vitamin_b6",       group: "Diet", kind: "record_sum" },
  { key: "vitamin_b12", label: "Vitamin B12",   unit: "mcg", color: "#4FC3F7", table: "nutrition_records", column: "vitamin_b12",      group: "Diet", kind: "record_sum" },
  { key: "thiamin",     label: "Thiamin",       unit: "mg", color: "#90CAF9", table: "nutrition_records",  column: "thiamin",          group: "Diet", kind: "record_sum" },
  { key: "riboflavin",  label: "Riboflavin",    unit: "mg", color: "#7986CB", table: "nutrition_records",  column: "riboflavin",       group: "Diet", kind: "record_sum" },
  { key: "niacin",      label: "Niacin",        unit: "mg", color: "#5C6BC0", table: "nutrition_records",  column: "niacin",           group: "Diet", kind: "record_sum" },
  { key: "folate",      label: "Folate",        unit: "mcg", color: "#7E57C2", table: "nutrition_records", column: "folate",           group: "Diet", kind: "record_sum" },
  { key: "biotin",      label: "Biotin",        unit: "mcg", color: "#9575CD", table: "nutrition_records", column: "biotin",           group: "Diet", kind: "record_sum" },
  { key: "panto_acid",  label: "Pantothenic acid", unit: "mg", color: "#BA68C8", table: "nutrition_records", column: "pantothenic_acid", group: "Diet", kind: "record_sum" },

  // Diet — food-risk exposure (0 none · 1 low · 2 medium · 3 high, max per day)
  { key: "tyramine",    label: "Tyramine",      unit: "risk", color: "#FFAB40", table: "nutrition_records", column: "tyramine_exposure", group: "Diet", kind: "record_risk" },
  { key: "alcohol",     label: "Alcohol",       unit: "risk", color: "#EF5350", table: "nutrition_records", column: "alcohol_exposure",  group: "Diet", kind: "record_risk" },
  { key: "gluten",      label: "Gluten",        unit: "risk", color: "#FFD54F", table: "nutrition_records", column: "gluten_exposure",   group: "Diet", kind: "record_risk" },
  { key: "histamine",   label: "Histamine",     unit: "risk", color: "#AB47BC", table: "nutrition_records", column: "histamine_exposure", group: "Diet", kind: "record_risk" },
];
