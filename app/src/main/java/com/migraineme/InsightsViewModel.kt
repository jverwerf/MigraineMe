// FILE: InsightsViewModel.kt
package com.migraineme

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class CategoryBreakdown(
    val categoryName: String,
    val totalCount: Int,
    val items: List<Pair<String, Int>>
)

data class SpiderData(
    val logType: String,
    val axes: List<SpiderAxis>,
    val totalLogged: Int,
    val breakdown: List<CategoryBreakdown>
)

class InsightsViewModel : ViewModel() {

    // ======= Core flows =======

    private val _migraines = MutableStateFlow<List<MigraineSpan>>(emptyList())
    val migraines: StateFlow<List<MigraineSpan>> = _migraines

    private val _reliefs = MutableStateFlow<List<ReliefSpan>>(emptyList())
    val reliefs: StateFlow<List<ReliefSpan>> = _reliefs

    private val _triggers = MutableStateFlow<List<TriggerPoint>>(emptyList())
    val triggers: StateFlow<List<TriggerPoint>> = _triggers

    private val _medicines = MutableStateFlow<List<MedicinePoint>>(emptyList())
    val medicines: StateFlow<List<MedicinePoint>> = _medicines

    // Sleep
    data class SleepDurationRow(val date: String, val hours: Double)
    data class SleepDisturbancesRow(val date: String, val count: Int)
    data class SleepStagesRow(val date: String, val swsHm: Double, val remHm: Double, val lightHm: Double)

    private val _sleepDuration = MutableStateFlow<List<SleepDurationRow>>(emptyList())
    val sleepDuration: StateFlow<List<SleepDurationRow>> = _sleepDuration

    private val _sleepDisturbances = MutableStateFlow<List<SleepDisturbancesRow>>(emptyList())
    val sleepDisturbances: StateFlow<List<SleepDisturbancesRow>> = _sleepDisturbances

    private val _sleepStages = MutableStateFlow<List<SleepStagesRow>>(emptyList())
    val sleepStages: StateFlow<List<SleepStagesRow>> = _sleepStages

    // Location
    data class UserLocationRow(val date: String, val latitude: Double, val longitude: Double)
    private val _userLocations = MutableStateFlow<List<UserLocationRow>>(emptyList())
    val userLocations: StateFlow<List<UserLocationRow>> = _userLocations

    private val _latestSleepDate = MutableStateFlow<String?>(null)
    val latestSleepDate: StateFlow<String?> = _latestSleepDate

    // ======= Spider chart flows =======

    private val _triggerSpider = MutableStateFlow<SpiderData?>(null)
    val triggerSpider: StateFlow<SpiderData?> = _triggerSpider

    private val _prodromeSpider = MutableStateFlow<SpiderData?>(null)
    val prodromeSpider: StateFlow<SpiderData?> = _prodromeSpider

    private val _symptomSpider = MutableStateFlow<SpiderData?>(null)
    val symptomSpider: StateFlow<SpiderData?> = _symptomSpider

    private val _painCharSpider = MutableStateFlow<SpiderData?>(null)
    val painCharSpider: StateFlow<SpiderData?> = _painCharSpider

    private val _accompSpider = MutableStateFlow<SpiderData?>(null)
    val accompSpider: StateFlow<SpiderData?> = _accompSpider

    private val _painLocationSpider = MutableStateFlow<SpiderData?>(null)
    val painLocationSpider: StateFlow<SpiderData?> = _painLocationSpider

    private val _severityCounts = MutableStateFlow<List<Pair<Int, Int>>>(emptyList())
    val severityCounts: StateFlow<List<Pair<Int, Int>>> = _severityCounts

    data class DurationStats(
        val avgHours: Float, val minHours: Float, val maxHours: Float, val durations: List<Float>
    )
    private val _durationStats = MutableStateFlow<DurationStats?>(null)
    val durationStats: StateFlow<DurationStats?> = _durationStats

    private val _painLocationCounts = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val painLocationCounts: StateFlow<List<Pair<String, Int>>> = _painLocationCounts

    private val _medicineSpider = MutableStateFlow<SpiderData?>(null)
    val medicineSpider: StateFlow<SpiderData?> = _medicineSpider

    private val _reliefSpider = MutableStateFlow<SpiderData?>(null)
    val reliefSpider: StateFlow<SpiderData?> = _reliefSpider

    data class ReliefEffectiveness(val category: String, val count: Int, val avgRelief: Float)

    private val _medicineEffectiveness = MutableStateFlow<List<ReliefEffectiveness>>(emptyList())
    val medicineEffectiveness: StateFlow<List<ReliefEffectiveness>> = _medicineEffectiveness

    private val _reliefEffectiveness = MutableStateFlow<List<ReliefEffectiveness>>(emptyList())
    val reliefEffectiveness: StateFlow<List<ReliefEffectiveness>> = _reliefEffectiveness

    private val _medicineItemEffectiveness = MutableStateFlow<Map<String, Float>>(emptyMap())
    val medicineItemEffectiveness: StateFlow<Map<String, Float>> = _medicineItemEffectiveness

    private val _reliefItemEffectiveness = MutableStateFlow<Map<String, Float>>(emptyMap())
    val reliefItemEffectiveness: StateFlow<Map<String, Float>> = _reliefItemEffectiveness

    private val _activitySpider = MutableStateFlow<SpiderData?>(null)
    val activitySpider: StateFlow<SpiderData?> = _activitySpider

    private val _missedActivitySpider = MutableStateFlow<SpiderData?>(null)
    val missedActivitySpider: StateFlow<SpiderData?> = _missedActivitySpider

    private val _locationSpider = MutableStateFlow<SpiderData?>(null)
    val locationSpider: StateFlow<SpiderData?> = _locationSpider

    private val _spiderLoading = MutableStateFlow(true)
    val spiderLoading: StateFlow<Boolean> = _spiderLoading

    // ======= Per-migraine linked items =======

    private val _selectedLinkedItems = MutableStateFlow(SupabaseDbService.MigraineLinkedItems())
    val selectedLinkedItems: StateFlow<SupabaseDbService.MigraineLinkedItems> = _selectedLinkedItems

    private val _linkedItemsLoading = MutableStateFlow(false)
    val linkedItemsLoading: StateFlow<Boolean> = _linkedItemsLoading

    private val _allMissedActivities = MutableStateFlow<List<SupabaseDbService.MissedActivityLogRow>>(emptyList())
    val allMissedActivities: StateFlow<List<SupabaseDbService.MissedActivityLogRow>> = _allMissedActivities

    private val _allActivities = MutableStateFlow<List<SupabaseDbService.ActivityLogRow>>(emptyList())
    val allActivities: StateFlow<List<SupabaseDbService.ActivityLogRow>> = _allActivities

    private val _rawMigraineRows = MutableStateFlow<List<SupabaseDbService.MigraineRow>>(emptyList())

    // Raw linked items stored for filtered spider computation
    private val _allTriggers = MutableStateFlow<List<SupabaseDbService.TriggerRow>>(emptyList())
    private val _allMedicines = MutableStateFlow<List<SupabaseDbService.MedicineRow>>(emptyList())
    private val _allReliefs = MutableStateFlow<List<SupabaseDbService.ReliefRow>>(emptyList())
    private val _allProdromes = MutableStateFlow<List<SupabaseDbService.ProdromeLogRow>>(emptyList())
    private val _allLocations = MutableStateFlow<List<SupabaseDbService.LocationLogRow>>(emptyList())

    // Category maps (label.lowercase -> category)
    private val _catMaps = MutableStateFlow<CatMaps>(CatMaps())
    data class CatMaps(
        val trigger: Map<String, String> = emptyMap(),
        val prodrome: Map<String, String> = emptyMap(),
        val symptom: Map<String, String> = emptyMap(),
        val medicine: Map<String, String> = emptyMap(),
        val relief: Map<String, String> = emptyMap(),
        val activity: Map<String, String> = emptyMap(),
        val missedActivity: Map<String, String> = emptyMap(),
        val location: Map<String, String> = emptyMap()
    )

    /** Build spiders for a filtered set of migraine IDs */
    data class FilteredSpiders(
        val triggers: SpiderData? = null,
        val prodromes: SpiderData? = null,
        val symptoms: SpiderData? = null,
        val painChar: SpiderData? = null,
        val accompanying: SpiderData? = null,
        val painLocations: SpiderData? = null,
        val medicines: SpiderData? = null,
        val reliefs: SpiderData? = null,
        val activities: SpiderData? = null,
        val locations: SpiderData? = null,
        val missedActivities: SpiderData? = null,
        val severityCounts: List<Pair<Int, Int>> = emptyList(),
        val durationStats: DurationStats? = null,
        val painLocationCounts: List<Pair<String, Int>> = emptyList(),
        val medicineEffectiveness: List<ReliefEffectiveness> = emptyList(),
        val reliefEffectiveness: List<ReliefEffectiveness> = emptyList(),
        val medicineItemEffectiveness: Map<String, Float> = emptyMap(),
        val reliefItemEffectiveness: Map<String, Float> = emptyMap()
    )

    fun buildFilteredSpiders(migraineIds: Set<String>): FilteredSpiders {
        val cm = _catMaps.value
        val rows = _rawMigraineRows.value.filter { it.id in migraineIds }

        val fT = _allTriggers.value.filter { it.migraineId in migraineIds }
        val fM = _allMedicines.value.filter { it.migraineId in migraineIds }
        val fR = _allReliefs.value.filter { it.migraineId in migraineIds }
        val fP = _allProdromes.value.filter { it.migraineId in migraineIds }
        val fA = _allActivities.value.filter { it.migraineId in migraineIds }
        val fL = _allLocations.value.filter { it.migraineId in migraineIds }
        val fMs = _allMissedActivities.value.filter { it.migraineId in migraineIds }

        val allSym = rows.flatMap { row ->
            row.type?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() && it != "Migraine" } ?: emptyList()
        }
        val painCharLabels = allSym.filter { cm.symptom[it.lowercase()] == "pain_character" }
        val accompLabels = allSym.filter { cm.symptom[it.lowercase()] == "accompanying" }
        val allPainLocs = rows.flatMap { it.painLocations ?: emptyList() }

        // Duration stats
        val durations = rows.mapNotNull { row ->
            val endAt = row.endAt ?: return@mapNotNull null
            try {
                java.time.Duration.between(
                    Instant.parse(row.startAt), Instant.parse(endAt)
                ).toMinutes() / 60f
            } catch (_: Exception) { null }
        }
        val durStats = if (durations.isNotEmpty()) DurationStats(
            avgHours = durations.average().toFloat(),
            minHours = durations.min(),
            maxHours = durations.max(),
            durations = durations.sorted()
        ) else null

        return FilteredSpiders(
            triggers = if (fT.isNotEmpty()) buildSpider("Triggers", fT.mapNotNull { it.type }, cm.trigger) else null,
            prodromes = if (fP.isNotEmpty()) buildSpider("Prodromes", fP.mapNotNull { it.type }, cm.prodrome) else null,
            symptoms = if (allSym.isNotEmpty()) buildSpider("Symptoms", allSym, cm.symptom) else null,
            painChar = if (painCharLabels.isNotEmpty()) buildFlatSpider("Pain Character", painCharLabels) else null,
            accompanying = if (accompLabels.isNotEmpty()) buildFlatSpider("Accompanying", accompLabels) else null,
            painLocations = if (allPainLocs.isNotEmpty()) buildFlatSpider("Pain Locations", allPainLocs) else null,
            medicines = if (fM.isNotEmpty()) buildSpider("Medicines", fM.mapNotNull { it.name }, cm.medicine) else null,
            reliefs = if (fR.isNotEmpty()) buildSpider("Reliefs", fR.mapNotNull { it.type }, cm.relief) else null,
            activities = if (fA.isNotEmpty()) buildSpider("Activities", fA.mapNotNull { it.type }, cm.activity) else null,
            locations = if (fL.isNotEmpty()) buildSpider("Locations", fL.mapNotNull { it.type }, cm.location) else null,
            missedActivities = if (fMs.isNotEmpty()) buildSpider("Missed Activities", fMs.mapNotNull { it.type }, cm.missedActivity) else null,
            severityCounts = rows.mapNotNull { it.severity }
                .groupingBy { it }.eachCount().toList().sortedBy { it.first },
            durationStats = durStats,
            painLocationCounts = allPainLocs.groupingBy { it }.eachCount().toList().sortedByDescending { it.second },
            medicineEffectiveness = fM
                .groupBy { cm.medicine[it.name?.lowercase()] ?: "Other" }
                .map { entry ->
                    ReliefEffectiveness(entry.key, entry.value.size,
                        entry.value.map { reliefToNum(it.reliefScale) }.average().toFloat())
                }.sortedByDescending { it.count },
            reliefEffectiveness = fR
                .groupBy { cm.relief[it.type?.lowercase()] ?: "Other" }
                .map { entry ->
                    ReliefEffectiveness(entry.key, entry.value.size,
                        entry.value.map { reliefToNum(it.reliefScale) }.average().toFloat())
                }.sortedByDescending { it.count },
            medicineItemEffectiveness = fM
                .groupBy { it.name?.lowercase() ?: "unknown" }
                .mapValues { entry -> entry.value.map { reliefToNum(it.reliefScale) }.average().toFloat() },
            reliefItemEffectiveness = fR
                .groupBy { it.type?.lowercase() ?: "unknown" }
                .mapValues { entry -> entry.value.map { reliefToNum(it.reliefScale) }.average().toFloat() }
        )
    }

    // ======= ALL daily metrics =======

    data class DailyValue(val date: String, val value: Double)

    private val _allDailyMetrics = MutableStateFlow<Map<String, List<DailyValue>>>(emptyMap())
    val allDailyMetrics: StateFlow<Map<String, List<DailyValue>>> = _allDailyMetrics

    // ======= Shared UI state (used by both InsightsScreen and InsightsDetailScreen) =======

    private val _selectedMigraineIndex = MutableStateFlow(0)
    val selectedMigraineIndex: StateFlow<Int> = _selectedMigraineIndex

    private val _userToggledMetrics = MutableStateFlow<Set<String>>(emptySet())
    val userToggledMetrics: StateFlow<Set<String>> = _userToggledMetrics

    private val _userDisabledMetrics = MutableStateFlow<Set<String>>(emptySet())
    val userDisabledMetrics: StateFlow<Set<String>> = _userDisabledMetrics

    private val _windowDaysBefore = MutableStateFlow(7L)
    val windowDaysBefore: StateFlow<Long> = _windowDaysBefore

    private val _windowDaysAfter = MutableStateFlow(2L)
    val windowDaysAfter: StateFlow<Long> = _windowDaysAfter

    fun selectMigraine(index: Int) {
        _selectedMigraineIndex.value = index
        _userToggledMetrics.value = emptySet()
        _userDisabledMetrics.value = emptySet()
    }

    fun setWindowDays(before: Long, after: Long) {
        _windowDaysBefore.value = before
        _windowDaysAfter.value = after
    }

    fun toggleMetric(key: String, isCurrentlyEnabled: Boolean) {
        val toggled = _userToggledMetrics.value
        val disabled = _userDisabledMetrics.value
        if (isCurrentlyEnabled) {
            // Turn it off: add to disabled, remove from toggled
            _userDisabledMetrics.value = disabled + key
            _userToggledMetrics.value = toggled - key
        } else {
            // Turn it on: remove from disabled, add to toggled
            _userDisabledMetrics.value = disabled - key
            _userToggledMetrics.value = toggled + key
        }
    }

    // ======= Dynamic template -> metric mapping =======

    /**
     * Maps Supabase metric_table names -> VM metric keys.
     * This is the ONLY hardcoded mapping. Everything else is driven by DB templates.
     * When a new metric table is added to the DB AND to loadAllDailyMetrics, add its entry here.
     */
    companion object {
        val TABLE_TO_KEY: Map<String, String> = mapOf(
            // Sleep
            "sleep_duration_daily" to "sleep_dur",
            "sleep_score_daily" to "sleep_score",
            "sleep_efficiency_daily" to "sleep_eff",
            "sleep_disturbances_daily" to "sleep_dist",
            "sleep_stages_daily" to "sleep_deep",
            "fell_asleep_time_daily" to "bedtime",
            "woke_up_time_daily" to "wake_time",
            // Body / Physical
            "recovery_score_daily" to "recovery",
            "stress_index_daily" to "stress",
            "time_in_high_hr_zones_daily" to "high_hr",
            "steps_daily" to "steps",
            "weight_daily" to "weight",
            "body_fat_daily" to "body_fat",
            "blood_pressure_daily" to "bp_sys",
            "blood_glucose_daily" to "glucose",
            "strain_daily" to "strain",
            // Physical prodromes
            "hrv_daily" to "hrv",
            "resting_hr_daily" to "rhr",
            "spo2_daily" to "spo2",
            "skin_temp_daily" to "skin_temp",
            "respiratory_rate_daily" to "resp_rate",
            // Environment
            "user_weather_daily" to "pressure",
            "user_location_daily" to "altitude",
            // Mental / Cognitive
            "screen_time_daily" to "screen_time",
            "screen_time_late_night" to "late_screen",
            "ambient_noise_index_daily" to "noise",
            "phone_brightness_daily" to "brightness",
            "phone_volume_daily" to "volume",
            "phone_unlock_daily" to "unlocks",
            "phone_dark_mode_daily" to "dark_mode",
            // Wellness
            "hydration_daily" to "hydration",
            "mindfulness_daily" to "mindfulness",
            // Diet
            "nutrition_daily" to "calories",
        )

        /**
         * For tables with multiple metric columns, use "table:column" to pick the right VM key.
         */
        val TABLE_COL_TO_KEY: Map<String, String> = mapOf(
            // Weather
            "user_weather_daily:temp_c_mean" to "temp",
            "user_weather_daily:pressure_hpa_mean" to "pressure",
            "user_weather_daily:humidity_pct_mean" to "humidity",
            "user_weather_daily:wind_speed_mps_mean" to "wind",
            "user_weather_daily:uv_index_max" to "uv",
            // Location
            "user_location_daily:altitude_max_m" to "altitude",
            "user_location_daily:altitude_change_m" to "alt_change",
            // Sleep stages
            "sleep_stages_daily:value_sws_hm" to "sleep_deep",
            "sleep_stages_daily:value_rem_hm" to "sleep_rem",
            "sleep_stages_daily:value_light_hm" to "sleep_light",
            // Nutrition
            "nutrition_daily:total_calories" to "calories",
            "nutrition_daily:total_protein_g" to "protein",
            "nutrition_daily:total_carbs_g" to "carbs",
            "nutrition_daily:total_fat_g" to "fat",
            "nutrition_daily:total_fiber_g" to "fiber",
            "nutrition_daily:total_sugar_g" to "sugar",
            "nutrition_daily:total_sodium_mg" to "sodium",
            "nutrition_daily:total_caffeine_mg" to "caffeine",
            "nutrition_daily:total_saturated_fat_g" to "sat_fat",
            "nutrition_daily:total_unsaturated_fat_g" to "unsat_fat",
            "nutrition_daily:total_trans_fat_g" to "trans_fat",
            "nutrition_daily:total_cholesterol_mg" to "cholesterol",
            "nutrition_daily:total_potassium_mg" to "potassium",
            "nutrition_daily:total_calcium_mg" to "calcium",
            "nutrition_daily:total_iron_mg" to "iron",
            "nutrition_daily:total_magnesium_mg" to "magnesium",
            "nutrition_daily:total_zinc_mg" to "zinc",
            "nutrition_daily:total_selenium_mcg" to "selenium",
        )
    }

    /**
     * Dynamic map: normalised trigger/prodrome label -> VM metric key.
     * Built at startup from trigger_templates + prodrome_templates fetched from Supabase.
     */
    private val _labelToMetricKey = MutableStateFlow<Map<String, String>>(emptyMap())
    val labelToMetricMap: StateFlow<Map<String, String>> = _labelToMetricKey

    /** Group name -> all associated VM metric keys (e.g. "poor sleep" -> {sleep_dur, sleep_score, ...}) */
    private val _groupToMetricKeys = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** Fetch trigger_templates + prodrome_templates and build label -> metric key map */
    private fun loadTemplateMap(client: OkHttpClient, base: String, key: String, token: String) {
        val map = mutableMapOf<String, String>()
        val groupMap = mutableMapOf<String, MutableSet<String>>()
        for (table in listOf("trigger_templates", "prodrome_templates")) {
            try {
                val url = "$base/rest/v1/$table?select=label,metric_table,metric_column,display_group&metric_table=not.is.null"
                val req = Request.Builder().url(url).get()
                    .addHeader("apikey", key)
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) continue
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val label = o.optString("label", "").takeIf { it.isNotBlank() } ?: continue
                    val metricTable = o.optString("metric_table", "").takeIf { it.isNotBlank() } ?: continue
                    val metricCol = o.optString("metric_column", "")
                    val displayGroup = o.optString("display_group", "").takeIf { it.isNotBlank() }
                    val vmKey = if (metricCol.isNotBlank()) {
                        TABLE_COL_TO_KEY["$metricTable:$metricCol"] ?: TABLE_TO_KEY[metricTable]
                    } else {
                        TABLE_TO_KEY[metricTable]
                    }
                    if (vmKey != null) {
                        map[normaliseLabel(label)] = vmKey
                        // If this belongs to a display_group, also map group -> keys
                        if (displayGroup != null) {
                            val normGroup = normaliseLabel(displayGroup)
                            map[normGroup] = vmKey
                            groupMap.getOrPut(normGroup) { mutableSetOf() }.add(vmKey)
                        }
                    }
                }
            } catch (_: Exception) { /* templates optional */ }
        }
        _labelToMetricKey.value = map
        _groupToMetricKeys.value = groupMap
    }

    /** Normalise a trigger/prodrome label for lookup: lowercase, strip colons, strip direction suffixes */
    private fun normaliseLabel(raw: String): String {
        return raw.lowercase()
            .replace(":", "")
            .trim()
            .replace(Regex("\\s+(low|high|short|long|late|early|many|few)\\b.*"), "")
            .trim()
    }

    /** Resolve a trigger/prodrome type string to a VM metric key using the dynamic template map */
    fun labelToMetricKey(label: String): String? {
        return _labelToMetricKey.value[normaliseLabel(label)]
    }

    /** Resolve a trigger/prodrome type to ALL associated VM metric keys.
     *  For grouped triggers (e.g. "Poor sleep"), returns all member keys.
     *  For individual triggers, returns the single key in a set. */
    fun metricKeysForLabel(label: String): Set<String> {
        val norm = normaliseLabel(label)
        val groupKeys = _groupToMetricKeys.value[norm]
        if (!groupKeys.isNullOrEmpty()) return groupKeys
        val single = _labelToMetricKey.value[norm]
        return if (single != null) setOf(single) else emptySet()
    }

    /** Return auto-detected metric keys from ALL triggers/prodromes linked to the given migraine IDs. */
    fun autoMetricKeysForMigraines(migraineIds: Set<String>): Set<String> {
        val keys = mutableSetOf<String>()
        _allTriggers.value
            .filter { it.migraineId in migraineIds }
            .forEach { keys.addAll(metricKeysForLabel(it.type ?: "")) }
        _allProdromes.value
            .filter { it.migraineId in migraineIds }
            .forEach { keys.addAll(metricKeysForLabel(it.type ?: "")) }
        _allMedicines.value
            .filter { it.migraineId in migraineIds }
            .forEach { keys.addAll(metricKeysForLabel(it.name ?: "")) }
        _allReliefs.value
            .filter { it.migraineId in migraineIds }
            .forEach { keys.addAll(metricKeysForLabel(it.type ?: "")) }
        return keys
    }

    // ======= Filtering =======

    /** Tag = "category:label", e.g. "Trigger:Pressure Drop", "Symptom:Nausea" */
    data class FilterTag(val category: String, val label: String) {
        val key get() = "$category:$label"
    }

    /** Map of migraineId -> set of tags for that migraine */
    private val _migraineTagIndex = MutableStateFlow<Map<String, Set<FilterTag>>>(emptyMap())
    val migraineTagIndex: StateFlow<Map<String, Set<FilterTag>>> = _migraineTagIndex

    /** All available tags across all migraines, grouped by category */
    private val _availableFilterTags = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val availableFilterTags: StateFlow<Map<String, List<String>>> = _availableFilterTags

    /** Currently active filters */
    private val _activeFilters = MutableStateFlow<Set<FilterTag>>(emptySet())
    val activeFilters: StateFlow<Set<FilterTag>> = _activeFilters

    /** Time range filter */
    enum class TimeFrame(val label: String, val days: Int?) {
        NONE("None", null),
        ALL("All Time", null),
        WEEK_1("7 Days", 7),
        WEEK_2("14 Days", 14),
        MONTH_1("30 Days", 30),
        MONTH_3("3 Months", 90),
        MONTH_6("6 Months", 180),
        YEAR_1("1 Year", 365),
        CUSTOM("Custom", null)
    }

    private val _timeFrame = MutableStateFlow(TimeFrame.ALL)
    val timeFrame: StateFlow<TimeFrame> = _timeFrame

    /** Custom date range (only used when timeFrame == CUSTOM) */
    data class CustomRange(val from: java.time.LocalDate, val to: java.time.LocalDate)

    private val _customRange = MutableStateFlow<CustomRange?>(null)
    val customRange: StateFlow<CustomRange?> = _customRange

    fun setTimeFrame(tf: TimeFrame) {
        _timeFrame.value = tf
        if (tf != TimeFrame.CUSTOM) _customRange.value = null
        _selectedMigraineIndex.value = 0
    }

    fun setCustomRange(from: java.time.LocalDate, to: java.time.LocalDate) {
        _customRange.value = CustomRange(from, to)
        _timeFrame.value = TimeFrame.CUSTOM
        _selectedMigraineIndex.value = 0
    }

    fun toggleFilter(tag: FilterTag) {
        val c = _activeFilters.value
        _activeFilters.value = if (tag in c) c - tag else c + tag
        _selectedMigraineIndex.value = 0
    }

    fun clearFilters() {
        _activeFilters.value = emptySet()
        _timeFrame.value = TimeFrame.ALL
        _customRange.value = null
        _selectedMigraineIndex.value = 0
    }

    private fun buildMigraineTagIndex(
        rawRows: List<SupabaseDbService.MigraineRow>,
        allTriggers: List<SupabaseDbService.TriggerRow>,
        allMedicines: List<SupabaseDbService.MedicineRow>,
        allReliefs: List<SupabaseDbService.ReliefRow>,
        allProdromes: List<SupabaseDbService.ProdromeLogRow>,
        allActivities: List<SupabaseDbService.ActivityLogRow>,
        allLocations: List<SupabaseDbService.LocationLogRow>,
        allMissed: List<SupabaseDbService.MissedActivityLogRow>
    ) {
        val index = mutableMapOf<String, MutableSet<FilterTag>>()
        val allTags = mutableSetOf<FilterTag>()

        for (m in rawRows) {
            val tags = index.getOrPut(m.id) { mutableSetOf() }
            // Symptoms from type field (comma-separated)
            m.type?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() && it != "Migraine" }?.forEach {
                val t = FilterTag("Symptom", it); tags += t; allTags += t
            }
            // Pain locations
            m.painLocations?.forEach {
                val t = FilterTag("Pain Location", it); tags += t; allTags += t
            }
            // Severity bucket
            m.severity?.let { sev ->
                val bucket = when {
                    sev <= 3 -> "Mild (1-3)"
                    sev <= 6 -> "Moderate (4-6)"
                    else -> "Severe (7-10)"
                }
                val t = FilterTag("Severity", bucket); tags += t; allTags += t
            }
        }

        fun <T> indexItems(items: List<T>, cat: String, getId: (T) -> String?, getType: (T) -> String?) {
            for (item in items) {
                val mid = getId(item) ?: continue
                val type = getType(item)?.takeIf { it.isNotBlank() } ?: continue
                val t = FilterTag(cat, type)
                index.getOrPut(mid) { mutableSetOf() } += t; allTags += t
            }
        }

        indexItems(allTriggers, "Trigger", { it.migraineId }, { it.type })
        indexItems(allMedicines, "Medicine", { it.migraineId }, { it.name })
        indexItems(allReliefs, "Relief", { it.migraineId }, { it.type })
        indexItems(allProdromes, "Prodrome", { it.migraineId }, { it.type })
        indexItems(allActivities, "Activity", { it.migraineId }, { it.type })
        indexItems(allLocations, "Location", { it.migraineId }, { it.type })
        indexItems(allMissed, "Missed Activity", { it.migraineId }, { it.type })

        _migraineTagIndex.value = index
        _availableFilterTags.value = allTags.groupBy({ it.category }, { it.label })
            .mapValues { it.value.distinct().sorted() }
    }

    // ======= Internal =======

    private val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
    private var cachedToken: String? = null

    // ======= Public API =======

    fun load(context: Context, accessToken: String) {
        cachedToken = accessToken
        viewModelScope.launch {
            try {
                val now = Instant.now()

                // Load template -> metric mapping FIRST (fast, needed for auto-selection)
                withContext(Dispatchers.IO) {
                    val client = OkHttpClient()
                    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
                    val apiKey = BuildConfig.SUPABASE_ANON_KEY
                    loadTemplateMap(client, base, apiKey, accessToken)
                }

                val migs = db.getMigraines(accessToken)
                _rawMigraineRows.value = migs
                _migraines.value = migs.map { row ->
                    MigraineSpan(
                        start = Instant.parse(row.startAt),
                        end = row.endAt?.let { Instant.parse(it) },
                        severity = row.severity,
                        label = row.type,
                        id = row.id
                    )
                }

                val rels = db.getAllReliefs(accessToken)
                _reliefs.value = rels.map { row ->
                    val s = Instant.parse(row.startAt)
                    val e = row.durationMinutes?.let { s.plus(it.toLong(), ChronoUnit.MINUTES) } ?: now
                    ReliefSpan(s, e, null, row.type ?: "Relief")
                }

                val meds = db.getAllMedicines(accessToken)
                _medicines.value = meds.map { row ->
                    MedicinePoint(Instant.parse(row.startAt), row.name ?: "Medicine", row.amount)
                }

                val trigs = db.getAllTriggers(accessToken)
                _triggers.value = trigs.map { row ->
                    TriggerPoint(Instant.parse(row.startAt), row.type ?: "Trigger")
                }

                // Populate raw linked items immediately so auto-metric detection works
                _allTriggers.value = trigs
                _allMedicines.value = meds
                _allReliefs.value = rels
                val prods = db.getAllProdromeLog(accessToken)
                _allProdromes.value = prods

                // Sleep
                val metrics = SupabaseMetricsService(context)
                val dur = metrics.fetchSleepDurationDaily(accessToken, 180)
                _sleepDuration.value = dur.map { SleepDurationRow(it.date, it.value_hours) }
                _latestSleepDate.value = dur.firstOrNull()?.date

                val dst = metrics.fetchSleepDisturbancesDaily(accessToken, 180)
                _sleepDisturbances.value = dst.map { SleepDisturbancesRow(it.date, it.value_count) }

                val stg = metrics.fetchSleepStagesDaily(accessToken, 180)
                _sleepStages.value = stg.map { SleepStagesRow(it.date, it.value_sws_hm, it.value_rem_hm, it.value_light_hm) }

                // Location
                val personal = SupabasePersonalService(context)
                val locs = personal.fetchUserLocationDaily(accessToken, 180)
                _userLocations.value = locs.map { UserLocationRow(it.date, it.latitude, it.longitude) }

                // ALL daily metrics from Supabase
                withContext(Dispatchers.IO) { loadAllDailyMetrics(context, accessToken) }

                // Spider data
                loadSpiderData(accessToken, trigs, meds, rels, prods)
            } catch (_: Exception) {
                _migraines.value = emptyList()
                _reliefs.value = emptyList()
                _triggers.value = emptyList()
                _medicines.value = emptyList()
                _sleepDuration.value = emptyList()
                _sleepDisturbances.value = emptyList()
                _sleepStages.value = emptyList()
                _userLocations.value = emptyList()
                _latestSleepDate.value = null
                _spiderLoading.value = false
            }
        }
    }

    fun loadLinkedItems(migraineId: String) {
        val token = cachedToken ?: return
        viewModelScope.launch {
            _linkedItemsLoading.value = true
            _selectedLinkedItems.value = try {
                db.getLinkedItems(token, migraineId)
            } catch (_: Exception) {
                SupabaseDbService.MigraineLinkedItems()
            }
            _linkedItemsLoading.value = false
        }
    }

    /** Load linked items for a specific migraine (for PDF batch rendering) */
    suspend fun getLinkedItemsFor(migraineId: String): SupabaseDbService.MigraineLinkedItems {
        val token = cachedToken ?: return SupabaseDbService.MigraineLinkedItems()
        return try {
            db.getLinkedItems(token, migraineId)
        } catch (_: Exception) {
            SupabaseDbService.MigraineLinkedItems()
        }
    }

    // ======= Load EVERY daily metric table via direct Supabase REST =======

    private fun loadAllDailyMetrics(context: Context, token: String) {
        val userId = SessionStore.readUserId(context) ?: return
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val key = BuildConfig.SUPABASE_ANON_KEY
        val client = OkHttpClient()
        val cutoff = LocalDate.now().minusDays(180).toString()
        val map = mutableMapOf<String, List<DailyValue>>()

        //  Weather (user_weather_daily) 
        val weatherArr = fetchArr(client, base, key, token, userId, "user_weather_daily",
            "date,temp_c_mean,pressure_hpa_mean,humidity_pct_mean,wind_speed_mps_mean,uv_index_max", cutoff)
        if (weatherArr != null) {
            map["pressure"] = parseDoubleCol(weatherArr, "pressure_hpa_mean")
            map["temp"] = parseDoubleCol(weatherArr, "temp_c_mean")
            map["humidity"] = parseDoubleCol(weatherArr, "humidity_pct_mean")
            map["wind"] = parseDoubleCol(weatherArr, "wind_speed_mps_mean")
            map["uv"] = parseDoubleCol(weatherArr, "uv_index_max")
        }

        //  Location/Altitude (user_location_daily) 
        val locArr = fetchArr(client, base, key, token, userId, "user_location_daily",
            "date,altitude_max_m,altitude_change_m", cutoff)
        if (locArr != null) {
            map["altitude"] = parseDoubleCol(locArr, "altitude_max_m")
            map["alt_change"] = parseDoubleCol(locArr, "altitude_change_m")
        }

        //  Physical health metrics (each in its own table) 
        map["recovery"] = fetchAndParse(client, base, key, token, userId, "recovery_score_daily", "value_pct", cutoff)
        map["hrv"] = fetchAndParse(client, base, key, token, userId, "hrv_daily", "value_rmssd_ms", cutoff)
        map["rhr"] = fetchAndParse(client, base, key, token, userId, "resting_hr_daily", "value_bpm", cutoff)
        map["spo2"] = fetchAndParse(client, base, key, token, userId, "spo2_daily", "value_pct", cutoff)
        map["skin_temp"] = fetchAndParse(client, base, key, token, userId, "skin_temp_daily", "value_celsius", cutoff)
        map["resp_rate"] = fetchAndParse(client, base, key, token, userId, "respiratory_rate_daily", "value_bpm", cutoff)
        map["stress"] = fetchAndParse(client, base, key, token, userId, "stress_index_daily", "value", cutoff)
        map["high_hr"] = fetchAndParse(client, base, key, token, userId, "time_in_high_hr_zones_daily", "value_minutes", cutoff)
        map["steps"] = fetchAndParseInt(client, base, key, token, userId, "steps_daily", "value_count", cutoff)
        map["weight"] = fetchAndParse(client, base, key, token, userId, "weight_daily", "value_kg", cutoff)
        map["body_fat"] = fetchAndParse(client, base, key, token, userId, "body_fat_daily", "value_pct", cutoff)
        map["bp_sys"] = fetchAndParse(client, base, key, token, userId, "blood_pressure_daily", "value_systolic", cutoff)
        map["glucose"] = fetchAndParse(client, base, key, token, userId, "blood_glucose_daily", "value_mgdl", cutoff)

        //  Sleep metrics 
        map["sleep_dur"] = fetchAndParse(client, base, key, token, userId, "sleep_duration_daily", "value_hours", cutoff)
        map["sleep_score"] = fetchAndParse(client, base, key, token, userId, "sleep_score_daily", "value_pct", cutoff)
        map["sleep_eff"] = fetchAndParse(client, base, key, token, userId, "sleep_efficiency_daily", "value_pct", cutoff)
        map["sleep_dist"] = fetchAndParseInt(client, base, key, token, userId, "sleep_disturbances_daily", "value_count", cutoff)

        val stagesArr = fetchArr(client, base, key, token, userId, "sleep_stages_daily",
            "date,value_sws_hm,value_rem_hm,value_light_hm", cutoff)
        if (stagesArr != null) {
            map["sleep_deep"] = parseDoubleCol(stagesArr, "value_sws_hm")
            map["sleep_rem"] = parseDoubleCol(stagesArr, "value_rem_hm")
            map["sleep_light"] = parseDoubleCol(stagesArr, "value_light_hm")
        }

        //  Screen time (only total from screen_time_daily; other phone metrics from their own tables below) 
        val screenArr = fetchArr(client, base, key, token, userId, "screen_time_daily",
            "date,value_minutes", cutoff)
        if (screenArr != null) {
            map["screen_time"] = parseDoubleCol(screenArr, "value_minutes")
        }

        //  Nutrition / Diet 
        // nutrition_daily has basic 8 fields; nutrition_records has ALL nutrients per-record.
        // We query nutrition_records and aggregate by date for the full picture.
        loadNutritionMetrics(client, base, key, token, userId, cutoff, map)

        // Also load nutrition_daily as fallback for dates that may not have records
        val nutritionArr = fetchArr(client, base, key, token, userId, "nutrition_daily",
            "date,total_calories,total_protein_g,total_carbs_g,total_fat_g,total_fiber_g,total_sugar_g,total_sodium_mg,total_caffeine_mg", cutoff)
        if (nutritionArr != null) {
            // Only fill in keys that weren't already populated from nutrition_records
            fun fillIfEmpty(key: String, col: String) {
                if (map[key].isNullOrEmpty()) map[key] = parseDoubleCol(nutritionArr, col)
            }
            fillIfEmpty("calories", "total_calories")
            fillIfEmpty("protein", "total_protein_g")
            fillIfEmpty("carbs", "total_carbs_g")
            fillIfEmpty("fat", "total_fat_g")
            fillIfEmpty("fiber", "total_fiber_g")
            fillIfEmpty("sugar", "total_sugar_g")
            fillIfEmpty("sodium", "total_sodium_mg")
            fillIfEmpty("caffeine", "total_caffeine_mg")
        }

        //  Bedtime / Wake time (timestamp -> 24h decimal, bedtime shifted +24 if < 12 for smooth plotting) 
        fun parseTimestampToHours(arr: JSONArray, col: String, shiftPastNoon: Boolean): List<DailyValue> {
            val list = mutableListOf<DailyValue>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val date = o.optString("date", "").takeIf { it.length >= 10 } ?: continue
                val ts = o.optString(col, "").takeIf { it.isNotBlank() } ?: continue
                try {
                    val inst = Instant.parse(ts)
                    val zoned = inst.atZone(java.time.ZoneId.systemDefault())
                    var hours = zoned.hour + zoned.minute / 60.0
                    if (shiftPastNoon && hours < 12.0) hours += 24.0  // e.g. 00:30 -> 24.5
                    list += DailyValue(date, hours)
                } catch (_: Exception) { /* skip unparseable */ }
            }
            return list
        }

        val bedtimeArr = fetchArr(client, base, key, token, userId, "fell_asleep_time_daily", "date,value_at", cutoff)
        if (bedtimeArr != null) {
            map["bedtime"] = parseTimestampToHours(bedtimeArr, "value_at", shiftPastNoon = true)
        }
        val wakeArr = fetchArr(client, base, key, token, userId, "woke_up_time_daily", "date,value_at", cutoff)
        if (wakeArr != null) {
            map["wake_time"] = parseTimestampToHours(wakeArr, "value_at", shiftPastNoon = false)
        }

        //  Additional physical 
        map["strain"] = fetchAndParse(client, base, key, token, userId, "strain_daily", "value_strain", cutoff)

        //  Additional mental / phone 
        map["late_screen"] = fetchAndParse(client, base, key, token, userId, "screen_time_late_night", "value_hours", cutoff)
        map["noise"] = fetchAndParse(client, base, key, token, userId, "ambient_noise_index_daily", "day_mean_lmean", cutoff)
        map["brightness"] = fetchAndParse(client, base, key, token, userId, "phone_brightness_daily", "value_mean", cutoff)
        map["volume"] = fetchAndParse(client, base, key, token, userId, "phone_volume_daily", "value_mean_pct", cutoff)
        map["unlocks"] = fetchAndParseInt(client, base, key, token, userId, "phone_unlock_daily", "value_count", cutoff)
        map["dark_mode"] = fetchAndParse(client, base, key, token, userId, "phone_dark_mode_daily", "value_hours", cutoff)

        //  Wellness 
        map["hydration"] = fetchAndParse(client, base, key, token, userId, "hydration_daily", "value_ml", cutoff)
        map["mindfulness"] = fetchAndParse(client, base, key, token, userId, "mindfulness_daily", "duration_minutes", cutoff)

        _allDailyMetrics.value = map.filterValues { it.isNotEmpty() }
    }

    /** Aggregate ALL nutrient columns from nutrition_records by date. */
    private fun loadNutritionMetrics(
        client: OkHttpClient, base: String, key: String, token: String,
        userId: String, cutoff: String, map: MutableMap<String, List<DailyValue>>
    ) {
        try {
            val cols = "timestamp,calories,protein,total_carbohydrate,total_fat,dietary_fiber,sugar," +
                "sodium,caffeine,cholesterol,saturated_fat,unsaturated_fat,trans_fat," +
                "potassium,calcium,iron,magnesium,zinc,selenium,phosphorus,copper,manganese," +
                "vitamin_a,vitamin_c,vitamin_d,vitamin_e,vitamin_k,vitamin_b6,vitamin_b12," +
                "thiamin,riboflavin,niacin,folate,biotin,pantothenic_acid," +
                "tyramine_exposure,alcohol_exposure,gluten_exposure"
            val url = "$base/rest/v1/nutrition_records?user_id=eq.$userId&timestamp=gte.${cutoff}T00:00:00Z&select=$cols&order=timestamp.desc&limit=5000"
            val req = Request.Builder().url(url).get()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $token")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string()
            if (!resp.isSuccessful || body.isNullOrBlank()) return
            val arr = JSONArray(body)
            if (arr.length() == 0) return

            // Group by date and sum each nutrient column
            val colKeys = listOf(
                "calories" to "calories", "protein" to "protein", "carbs" to "total_carbohydrate",
                "fat" to "total_fat", "fiber" to "dietary_fiber", "sugar" to "sugar",
                "sodium" to "sodium", "caffeine" to "caffeine", "cholesterol" to "cholesterol",
                "sat_fat" to "saturated_fat", "unsat_fat" to "unsaturated_fat", "trans_fat" to "trans_fat",
                "potassium" to "potassium", "calcium" to "calcium", "iron" to "iron",
                "magnesium" to "magnesium", "zinc" to "zinc", "selenium" to "selenium",
                "phosphorus" to "phosphorus", "copper" to "copper", "manganese" to "manganese",
                "vitamin_a" to "vitamin_a", "vitamin_c" to "vitamin_c", "vitamin_d" to "vitamin_d",
                "vitamin_e" to "vitamin_e", "vitamin_k" to "vitamin_k", "vitamin_b6" to "vitamin_b6",
                "vitamin_b12" to "vitamin_b12", "thiamin" to "thiamin", "riboflavin" to "riboflavin",
                "niacin" to "niacin", "folate" to "folate", "biotin" to "biotin",
                "panto_acid" to "pantothenic_acid"
            )

            // date -> (metricKey -> sum)
            val daySums = mutableMapOf<String, MutableMap<String, Double>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ts = o.optString("timestamp", "") // ISO timestamp
                val date = ts.take(10) // "YYYY-MM-DD"
                if (date.length < 10) continue
                val sums = daySums.getOrPut(date) { mutableMapOf() }
                for ((metKey, dbCol) in colKeys) {
                    val v = o.optDouble(dbCol)
                    if (!v.isNaN() && v > 0.0) {
                        sums[metKey] = (sums[metKey] ?: 0.0) + v
                    }
                }
            }

            // Convert to DailyValue lists
            for ((metKey, _) in colKeys) {
                val list = mutableListOf<DailyValue>()
                for ((date, sums) in daySums) {
                    val v = sums[metKey]
                    if (v != null && v > 0.0) list += DailyValue(date, v)
                }
                if (list.isNotEmpty()) {
                    map[metKey] = list.sortedByDescending { it.date }
                }
            }

            // Risk metrics (tyramine, alcohol, gluten) – string "none"/"low"/"medium"/"high" -> 0/1/2/3
            val riskKeys = listOf(
                "tyramine" to "tyramine_exposure",
                "alcohol" to "alcohol_exposure",
                "gluten" to "gluten_exposure"
            )
            fun riskToNum(s: String?): Double? = when (s?.lowercase()) {
                "high" -> 3.0; "medium" -> 2.0; "low" -> 1.0; "none" -> 0.0; else -> null
            }
            val riskDaySums = mutableMapOf<String, MutableMap<String, Double>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ts = o.optString("timestamp", "")
                val date = ts.take(10)
                if (date.length < 10) continue
                val sums = riskDaySums.getOrPut(date) { mutableMapOf() }
                for ((metKey, dbCol) in riskKeys) {
                    val raw = if (o.has(dbCol) && !o.isNull(dbCol)) o.optString(dbCol) else null
                    val v = riskToNum(raw)
                    if (v != null && v > (sums[metKey] ?: -1.0)) {
                        sums[metKey] = v // take max risk level per day
                    }
                }
            }
            for ((metKey, _) in riskKeys) {
                val list = mutableListOf<DailyValue>()
                for ((date, sums) in riskDaySums) {
                    val v = sums[metKey]
                    if (v != null && v > 0.0) list += DailyValue(date, v)
                }
                if (list.isNotEmpty()) {
                    map[metKey] = list.sortedByDescending { it.date }
                }
            }
        } catch (_: Exception) { /* nutrition data optional */ }
    }

    //  REST helpers 

    private fun fetchArr(
        client: OkHttpClient, base: String, key: String, token: String,
        userId: String, table: String, select: String, cutoff: String
    ): JSONArray? {
        return try {
            val url = "$base/rest/v1/$table?user_id=eq.$userId&date=gte.$cutoff&select=$select&order=date.desc&limit=365"
            val req = Request.Builder().url(url).get()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $token")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string()
            if (resp.isSuccessful && !body.isNullOrBlank()) JSONArray(body) else null
        } catch (_: Exception) { null }
    }

    private fun parseDoubleCol(arr: JSONArray, col: String): List<DailyValue> {
        val list = mutableListOf<DailyValue>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val v = o.optDouble(col)
            if (!v.isNaN()) list += DailyValue(o.getString("date"), v)
        }
        return list
    }

    private fun parseIntCol(arr: JSONArray, col: String): List<DailyValue> {
        val list = mutableListOf<DailyValue>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val v = o.optInt(col, Int.MIN_VALUE)
            if (v != Int.MIN_VALUE) list += DailyValue(o.getString("date"), v.toDouble())
        }
        return list
    }

    private fun fetchAndParse(
        client: OkHttpClient, base: String, key: String, token: String,
        userId: String, table: String, col: String, cutoff: String
    ): List<DailyValue> {
        val arr = fetchArr(client, base, key, token, userId, table, "date,$col", cutoff) ?: return emptyList()
        return parseDoubleCol(arr, col)
    }

    private fun fetchAndParseInt(
        client: OkHttpClient, base: String, key: String, token: String,
        userId: String, table: String, col: String, cutoff: String
    ): List<DailyValue> {
        val arr = fetchArr(client, base, key, token, userId, table, "date,$col", cutoff) ?: return emptyList()
        return parseIntCol(arr, col)
    }

    // ======= Spider data =======

    private suspend fun loadSpiderData(
        accessToken: String,
        allTriggers: List<SupabaseDbService.TriggerRow>,
        allMedicines: List<SupabaseDbService.MedicineRow>,
        allReliefs: List<SupabaseDbService.ReliefRow>,
        allProdromes: List<SupabaseDbService.ProdromeLogRow>
    ) {
        try {
            _spiderLoading.value = true

            val triggerPool = db.getAllTriggerPool(accessToken)
            val prodromePool = db.getAllProdromePool(accessToken)
            val symptomPool = db.getAllSymptomPool(accessToken)
            val medicinePool = db.getAllMedicinePool(accessToken)
            val reliefPool = db.getAllReliefPool(accessToken)
            val activityPool = db.getAllActivityPool(accessToken)
            val missedActivityPool = db.getAllMissedActivityPool(accessToken)
            val locationPool = db.getAllLocationPool(accessToken)

            val linkedT = allTriggers.filter { it.migraineId != null }
            val linkedM = allMedicines.filter { it.migraineId != null }
            val linkedR = allReliefs.filter { it.migraineId != null }

            val allP = allProdromes
            val linkedP = allP.filter { it.migraineId != null }

            val allA = db.getAllActivityLog(accessToken)
            val linkedA = allA.filter { it.migraineId != null }
            _allActivities.value = allA

            val allL = db.getAllLocationLog(accessToken)
            val linkedL = allL.filter { it.migraineId != null }

            val allMs = db.getAllMissedActivityLog(accessToken)
            val linkedMs = allMs.filter { it.migraineId != null }
            _allMissedActivities.value = allMs

            _allActivities.value = allA
            _allLocations.value = allL

            val tcBase = triggerPool.associate { it.label.lowercase() to (it.category ?: "Other") }
            // Also map display_group names to their category (use first member's category)
            val tcGroups = mutableMapOf<String, String>()
            for (row in triggerPool) {
                val group = row.displayGroup
                if (group != null && group !in tcGroups) {
                    tcGroups[group.lowercase()] = row.category ?: "Other"
                }
            }
            val tc: Map<String, String> = tcBase + tcGroups
            val pc = prodromePool.associate { it.label.lowercase() to (it.category ?: "Other") }
            val sc = symptomPool.associate { it.label.lowercase() to (it.category ?: "Other") }
            val mc = medicinePool.associate { it.label.lowercase() to (it.category ?: "Other") }
            val rc = reliefPool.associate { it.label.lowercase() to (it.category ?: "Other") }
            val ac = activityPool.associate { it.label.lowercase() to (it.category ?: "Other") }
            val msc = missedActivityPool.associate { it.label.lowercase() to (it.category ?: "Other") }
            val lc = locationPool.associate { it.label.lowercase() to (it.category ?: "Other") }

            _catMaps.value = CatMaps(tc, pc, sc, mc, rc, ac, msc, lc)

            _triggerSpider.value = buildSpider("Triggers", linkedT.mapNotNull { it.type }, tc)
            _prodromeSpider.value = buildSpider("Prodromes", linkedP.mapNotNull { it.type }, pc)
            _medicineSpider.value = buildSpider("Medicines", linkedM.mapNotNull { it.name }, mc)
            _reliefSpider.value = buildSpider("Reliefs", linkedR.mapNotNull { it.type }, rc)

            _medicineEffectiveness.value = linkedM
                .groupBy { mc[it.name?.lowercase()] ?: "Other" }
                .map { entry ->
                    ReliefEffectiveness(
                        entry.key, entry.value.size,
                        entry.value.map { reliefToNum(it.reliefScale) }.average().toFloat()
                    )
                }
                .sortedByDescending { it.count }

            _medicineItemEffectiveness.value = linkedM
                .groupBy { it.name?.lowercase() ?: "unknown" }
                .mapValues { entry -> entry.value.map { reliefToNum(it.reliefScale) }.average().toFloat() }

            _reliefEffectiveness.value = linkedR
                .groupBy { rc[it.type?.lowercase()] ?: "Other" }
                .map { entry ->
                    ReliefEffectiveness(
                        entry.key, entry.value.size,
                        entry.value.map { reliefToNum(it.reliefScale) }.average().toFloat()
                    )
                }
                .sortedByDescending { it.count }

            _reliefItemEffectiveness.value = linkedR
                .groupBy { it.type?.lowercase() ?: "unknown" }
                .mapValues { entry -> entry.value.map { reliefToNum(it.reliefScale) }.average().toFloat() }

            _activitySpider.value = buildSpider("Activities", linkedA.mapNotNull { it.type }, ac)
            _locationSpider.value = buildSpider("Locations", linkedL.mapNotNull { it.type }, lc)
            _missedActivitySpider.value = buildSpider("Missed Activities", linkedMs.mapNotNull { it.type }, msc)

            // Symptoms from migraine type field
            val migs = db.getMigraines(accessToken)
            val allSym = migs.flatMap { row ->
                row.type?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() && it != "Migraine" } ?: emptyList()
            }
            _symptomSpider.value = buildSpider("Symptoms", allSym, sc)

            val painCharLabels = allSym.filter { sc[it.lowercase()] == "pain_character" }
            val accompLabels = allSym.filter { sc[it.lowercase()] == "accompanying" }

            _painCharSpider.value = buildFlatSpider("Pain Character", painCharLabels)
            _accompSpider.value = buildFlatSpider("Accompanying", accompLabels)

            val allPainLocs = migs.flatMap { it.painLocations ?: emptyList() }
            _painLocationSpider.value = buildFlatSpider("Pain Locations", allPainLocs)

            _severityCounts.value = migs.mapNotNull { it.severity }
                .groupingBy { it }.eachCount().toList().sortedBy { it.first }

            val durations = migs.mapNotNull { row ->
                val endAt = row.endAt ?: return@mapNotNull null
                try {
                    val hours = java.time.Duration.between(
                        Instant.parse(row.startAt), Instant.parse(endAt)
                    ).toMinutes() / 60f
                    if (hours > 0f) hours else null
                } catch (_: Exception) { null }
            }
            if (durations.isNotEmpty()) {
                _durationStats.value = DurationStats(
                    avgHours = durations.average().toFloat(),
                    minHours = durations.min(),
                    maxHours = durations.max(),
                    durations = durations.sorted()
                )
            }

            // Build filter tag index
            buildMigraineTagIndex(
                _rawMigraineRows.value,
                allTriggers, allMedicines, allReliefs,
                allP, allA, allL, allMs
            )
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            _spiderLoading.value = false
        }
    }

    //  Spider helpers 

    internal fun buildSpider(
        logType: String,
        labels: List<String>,
        catMap: Map<String, String>
    ): SpiderData {
        val labelCounts = labels.groupingBy { it.lowercase() }.eachCount()
        val catGroups = mutableMapOf<String, MutableList<Pair<String, Int>>>()
        for ((label, count) in labelCounts) {
            val cat = catMap[label] ?: "Other"
            catGroups.getOrPut(cat) { mutableListOf() }
                .add(label.replaceFirstChar { it.uppercase() } to count)
        }
        val breakdowns = catGroups.map { (cat, items) ->
            CategoryBreakdown(cat, items.sumOf { it.second }, items.sortedByDescending { it.second })
        }.sortedByDescending { it.totalCount }

        return SpiderData(
            logType = logType,
            axes = breakdowns.map { SpiderAxis(it.categoryName, it.totalCount.toFloat()) },
            totalLogged = labels.size,
            breakdown = breakdowns
        )
    }

    /** Build SpiderData for flat lists (no category grouping). */
    internal fun buildFlatSpider(logType: String, labels: List<String>): SpiderData {
        val counts = labels.groupingBy { it }.eachCount()
        val breakdowns = counts.map { (label, count) ->
            CategoryBreakdown(label, count, listOf(label to count))
        }.sortedByDescending { it.totalCount }

        return SpiderData(
            logType = logType,
            axes = breakdowns.map { SpiderAxis(it.categoryName, it.totalCount.toFloat()) },
            totalLogged = labels.size,
            breakdown = breakdowns
        )
    }

    private fun reliefToNum(scale: String?): Float = when (scale?.uppercase()) {
        "HIGH" -> 3f
        "MILD" -> 2f
        "LOW" -> 1f
        else -> 0f
    }
}



