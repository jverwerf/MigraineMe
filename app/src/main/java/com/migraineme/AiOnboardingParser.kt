package com.migraineme

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import org.json.JSONObject

private const val TAG = "AiOnboardingParser"

/**
 * Pre-fill result from story parsing.
 * Only non-null / non-empty fields should be applied — null/empty means "not mentioned".
 */
data class OnboardingPreFill(
    // Page 1 — Migraine basics
    val gender: String? = null,
    val ageRange: String? = null,
    val frequency: String? = null,
    val duration: String? = null,
    val experience: String? = null,
    val trajectory: String? = null,
    val warningBefore: String? = null,
    val triggerDelay: String? = null,
    val dailyRoutine: String? = null,
    val seasonalPattern: String? = null,
    // Page 2 — Sleep
    val sleepHours: String? = null,
    val sleepQuality: String? = null,
    val poorQualityTriggers: DeterministicMapper.Certainty? = null,
    val tooLittleSleepTriggers: DeterministicMapper.Certainty? = null,
    val oversleepTriggers: DeterministicMapper.Certainty? = null,
    val sleepIssues: Set<String> = emptySet(),
    // Page 3 — Stress & screen
    val stressLevel: String? = null,
    val stressChangeTriggers: DeterministicMapper.Certainty? = null,
    val emotionalPatterns: Map<String, DeterministicMapper.Certainty> = emptyMap(),
    val screenTimeDaily: String? = null,
    val screenTimeTriggers: DeterministicMapper.Certainty? = null,
    val lateScreenTriggers: DeterministicMapper.Certainty? = null,
    // Page 4 — Diet & substances
    val caffeineIntake: String? = null,
    val caffeineDirection: String? = null,
    val caffeineCertainty: DeterministicMapper.Certainty? = null,
    val alcoholFrequency: String? = null,
    val alcoholTriggers: DeterministicMapper.Certainty? = null,
    val specificDrinks: Set<String> = emptySet(),
    val tyramineFoods: Map<String, DeterministicMapper.Certainty> = emptyMap(),
    val histamineFoods: Map<String, DeterministicMapper.Certainty> = emptyMap(),
    val glutenSensitivity: String? = null,
    val glutenTriggers: DeterministicMapper.Certainty? = null,
    val eatingPatterns: Map<String, DeterministicMapper.Certainty> = emptyMap(),
    val waterIntake: String? = null,
    val tracksNutrition: String? = null,
    // Page 5 — Weather, environment, physical
    val weatherTriggers: DeterministicMapper.Certainty? = null,
    val specificWeather: Map<String, DeterministicMapper.Certainty> = emptyMap(),
    val environmentSensitivities: Map<String, DeterministicMapper.Certainty> = emptyMap(),
    val physicalFactors: Map<String, DeterministicMapper.Certainty> = emptyMap(),
    // Page 6 — Exercise & hormones
    val exerciseFrequency: String? = null,
    val exerciseTriggers: DeterministicMapper.Certainty? = null,
    val exercisePattern: Set<String> = emptySet(),
    val tracksCycle: String? = null,
    val cyclePatterns: Map<String, DeterministicMapper.Certainty> = emptyMap(),
    val cycleLength: String? = null,
    val cycleMigraineTiming: Set<String> = emptySet(),
    val lastPeriodDate: String? = null,
    val usesContraception: String? = null,
    val contraceptionEffect: String? = null,
    // Page 7 — Prodromes
    val physicalProdromes: Map<String, DeterministicMapper.Certainty> = emptyMap(),
    val moodProdromes: Map<String, DeterministicMapper.Certainty> = emptyMap(),
    val sensoryProdromes: Map<String, DeterministicMapper.Certainty> = emptyMap(),
    // Pool matches — labels from the DB pools
    val matchedTriggers: Set<String> = emptySet(),
    val matchedProdromes: Set<String> = emptySet(),
    val matchedSymptoms: Set<String> = emptySet(),
    val matchedMedicines: Set<String> = emptySet(),
    val matchedReliefs: Set<String> = emptySet(),
    val matchedActivities: Set<String> = emptySet(),
    val matchedMissedActivities: Set<String> = emptySet(),
    val matchedLocations: Set<String> = emptySet(),
    val matchedPostdromes: Set<String> = emptySet(),
)

object AiOnboardingParser {

    // ── Allowed values for AI-inferred fields ────────────────────────────────
    // Used to discard hallucinated values silently. Must match AiSetupQuestions.kt option strings byte-for-byte.
    private val SLEEP_ISSUES_VALUES = setOf("Irregular schedule", "Sleep apnea", "Jet lag", "None of these")
    private val EMOTIONAL_PATTERNS_KEYS = setOf("Spike in stress", "Anxiety", "Anger", "Let-down", "Feeling low")
    private val CAFFEINE_DIRECTION_VALUES = setOf("Too much triggers it", "Missing caffeine triggers it", "Both ways", "Not sure", "No")
    private val SPECIFIC_DRINKS_VALUES = setOf("Red wine", "Beer", "White wine", "Spirits", "Any alcohol")
    private val TYRAMINE_FOODS_KEYS = setOf("Aged cheese", "Chocolate", "Cured meats", "Fermented foods")
    private val HISTAMINE_FOODS_KEYS = setOf("Aged or smoked fish", "Avocado", "Spinach", "Tomatoes", "Strawberries", "Vinegar")
    private val GLUTEN_SENSITIVITY_VALUES = setOf("Yes, diagnosed", "I suspect so", "No", "Not sure")
    private val EATING_PATTERNS_KEYS = setOf("Skipping meals", "Sugar", "Salty food", "Overeating", "Dehydration")
    private val WATER_INTAKE_VALUES = setOf("< 1L", "1-2L", "2-3L", "3L+")
    private val TRACKS_NUTRITION_VALUES = setOf("Yes, regularly", "Sometimes", "No")
    private val SPECIFIC_WEATHER_KEYS = setOf("Pressure changes", "Hot weather", "Cold weather", "Humidity", "Dry air", "Wind", "Sunshine", "Thunderstorms", "Not sure which")
    private val ENVIRONMENT_SENSITIVITIES_KEYS = setOf("Fluorescent lights", "Strong smells", "Loud noise", "Smoke", "Altitude")
    private val PHYSICAL_FACTORS_KEYS = setOf("Allergies", "Being ill", "Low blood sugar", "Medication change", "Motion sickness", "Tobacco", "Sexual activity")
    private val EXERCISE_PATTERN_VALUES = setOf("During or after intense exercise", "When I haven't exercised")
    private val CYCLE_PATTERNS_KEYS = setOf("Around my period", "Around ovulation")
    private val CYCLE_LENGTH_VALUES = setOf("< 25 days", "25-28 days", "28-32 days", "32-35 days", "> 35 days", "Irregular")
    private val CYCLE_TIMING_VALUES = setOf("1-2 days before", "3-5 days before", "During my period", "1-2 days after")
    private val USES_CONTRACEPTION_VALUES = setOf("Yes", "No")
    // Note: em-dash (—), not hyphen — must match AiSetupQuestions.kt:479 exactly.
    private val CONTRACEPTION_EFFECT_VALUES = setOf("Worse — every time", "Worse — sometimes", "No change", "Actually helps")
    private val PHYSICAL_PRODROMES_KEYS = setOf("Neck stiffness", "Yawning", "Urination", "Stuffy nose", "Watery eyes", "Muscle tension")
    private val MOOD_PRODROMES_KEYS = setOf("Concentrating", "Words", "Irritability", "Mood swings", "Feeling low", "Unusually happy", "Food cravings", "Loss of appetite")
    private val SENSORY_PRODROMES_KEYS = setOf("Light", "Sound", "Smell", "Tingling", "Numbness")

    /**
     * Deterministic pre-parse: match pool labels against the story text.
     * Same fuzzy-matching approach as deterministicParse in AiLogParser.
     */
    fun deterministicPreFill(
        text: String,
        triggerLabels: List<String>,
        prodromeLabels: List<String>,
        symptomLabels: List<String>,
        medicineLabels: List<String>,
        reliefLabels: List<String>,
        activityLabels: List<String>,
        missedActivityLabels: List<String>,
        locationLabels: List<String> = emptyList(),
        postdromeLabels: List<String> = emptyList(),
    ): OnboardingPreFill {
        val lower = text.lowercase()

        fun matchPool(labels: List<String>): Set<String> =
            labels.filter { hitExpanded(lower, it) }.toSet()

        // Simple keyword extraction for questionnaire answers
        val frequency = when {
            lower.contains("chronic") || lower.contains("every day") || lower.contains("daily migraine") -> "Chronic"
            lower.contains("every week") || lower.contains("weekly") -> "Weekly"
            lower.contains("few times a month") || lower.contains("1-3") || lower.contains("couple.*month".toRegex()) -> "1-3 per month"
            lower.contains("every month") || lower.contains("once a month") || lower.contains("every 1-2 month") -> "Every 1-2 months"
            lower.contains("few.*year".toRegex()) || lower.contains("rarely") -> "A few per year"
            else -> null
        }

        val duration = when {
            lower.contains("3+ day") || lower.contains("several day") || lower.contains("lasts days") -> "3+ days"
            lower.contains("1-3 day") || lower.contains("a day or two") || lower.contains("couple of day") -> "1-3 days"
            lower.contains("12-24") || lower.contains("all day") || lower.contains("most of the day") -> "12-24 hours"
            lower.contains("4-12") || lower.contains("half a day") || lower.contains("few hours") || lower.contains("several hours") -> "4-12 hours"
            lower.contains("< 4") || lower.contains("short") || lower.contains("under 4") -> "< 4 hours"
            else -> null
        }

        val experience = when {
            lower.contains("10+ year") || lower.contains("decades") || lower.contains("since childhood") || lower.contains("my whole life") -> "10+ years"
            lower.contains("5-10 year") || lower.contains("several year") -> "5-10 years"
            lower.contains("1-5 year") || lower.contains("few year") || lower.contains("couple year") -> "1-5 years"
            lower.contains("new") || lower.contains("recent") || lower.contains("just started") || lower.contains("first time") -> "New / recent"
            else -> null
        }

        val stressLevel = when {
            lower.contains("very stressed") || lower.contains("extremely stress") || lower.contains("very high stress") -> "Very high"
            lower.contains("high stress") || lower.contains("really stressed") || lower.contains("stressed out") -> "High"
            lower.contains("moderate stress") || lower.contains("some stress") || lower.contains("a bit stressed") -> "Moderate"
            lower.contains("low stress") || lower.contains("not stressed") || lower.contains("relaxed") -> "Low"
            else -> null
        }

        val caffeineIntake = when {
            lower.contains("no caffeine") || lower.contains("don't drink coffee") || lower.contains("caffeine free") -> "None"
            lower.contains("5+ cup") || lower.contains("lots of coffee") || lower.contains("too much coffee") -> "5+ cups"
            lower.contains("3-4 cup") || lower.contains("3 or 4 cup") -> "3-4 cups"
            lower.contains("1-2 cup") || lower.contains("a cup") || lower.contains("one cup") || lower.contains("a coffee") -> "1-2 cups"
            else -> null
        }

        val alcoholFrequency = when {
            lower.contains("don't drink") || lower.contains("no alcohol") || lower.contains("teetotal") -> "Never"
            lower.contains("daily") && (lower.contains("drink") || lower.contains("alcohol") || lower.contains("wine") || lower.contains("beer")) -> "Daily"
            lower.contains("weekly") && (lower.contains("drink") || lower.contains("alcohol")) -> "Weekly"
            lower.contains("occasional") || lower.contains("sometimes drink") || lower.contains("social drink") -> "Occasionally"
            else -> null
        }

        val exerciseFrequency = when {
            lower.contains("exercise daily") || lower.contains("work out every day") -> "Daily"
            lower.contains("exercise.*few.*week".toRegex()) || lower.contains("gym.*few.*week".toRegex()) -> "Few times/week"
            lower.contains("exercise weekly") || lower.contains("once a week") -> "Weekly"
            lower.contains("rarely exercise") || lower.contains("don't exercise") || lower.contains("sedentary") -> "Rarely"
            lower.contains("never exercise") -> "Never"
            else -> null
        }

        val tracksCycle = when {
            lower.contains("track.*cycle".toRegex()) || lower.contains("track.*period".toRegex()) || lower.contains("period trigger") || lower.contains("menstrual migraine") -> "Yes"
            lower.contains("male") || lower.contains("i'm a man") || lower.contains("i am a man") -> "Not applicable"
            else -> null
        }

        val gender = when {
            lower.contains("i'm female") || lower.contains("i am female") || lower.contains("i'm a woman") || lower.contains("woman") && lower.contains("i am") -> "Female"
            lower.contains("i'm male") || lower.contains("i am male") || lower.contains("i'm a man") || lower.contains("man") && lower.contains("i am") -> "Male"
            else -> null
        }

        return OnboardingPreFill(
            gender = gender,
            frequency = frequency,
            duration = duration,
            experience = experience,
            stressLevel = stressLevel,
            caffeineIntake = caffeineIntake,
            alcoholFrequency = alcoholFrequency,
            exerciseFrequency = exerciseFrequency,
            tracksCycle = tracksCycle,
            matchedTriggers = matchPool(triggerLabels),
            matchedProdromes = matchPool(prodromeLabels),
            matchedSymptoms = matchPool(symptomLabels),
            matchedMedicines = matchPool(medicineLabels),
            matchedReliefs = matchPool(reliefLabels),
            matchedActivities = matchPool(activityLabels),
            matchedMissedActivities = matchPool(missedActivityLabels),
            // Locations + postdromes deliberately get NO deterministic keyword pass —
            // they're semantic categories ("at work", "wiped out next day") that need GPT.
            matchedLocations = emptySet(),
            matchedPostdromes = emptySet(),
        )
    }

    /**
     * GPT-enhanced parse: sends the story + all pool labels to GPT
     * to extract both questionnaire answers and pool matches.
     */
    suspend fun gptPreFill(
        accessToken: String,
        text: String,
        triggerLabels: List<String>,
        prodromeLabels: List<String>,
        symptomLabels: List<String>,
        medicineLabels: List<String>,
        reliefLabels: List<String>,
        activityLabels: List<String>,
        missedActivityLabels: List<String>,
        locationLabels: List<String> = emptyList(),
        postdromeLabels: List<String> = emptyList(),
        deterministicResult: OnboardingPreFill,
    ): OnboardingPreFill? {
        // Build a summary of everything the deterministic parser already extracted
        val alreadyFound = buildString {
            deterministicResult.gender?.let { append("gender=$it, ") }
            deterministicResult.ageRange?.let { append("age_range=$it, ") }
            deterministicResult.frequency?.let { append("frequency=$it, ") }
            deterministicResult.duration?.let { append("duration=$it, ") }
            deterministicResult.experience?.let { append("experience=$it, ") }
            deterministicResult.trajectory?.let { append("trajectory=$it, ") }
            deterministicResult.warningBefore?.let { append("warning_before=$it, ") }
            deterministicResult.triggerDelay?.let { append("trigger_delay=$it, ") }
            deterministicResult.dailyRoutine?.let { append("daily_routine=$it, ") }
            deterministicResult.seasonalPattern?.let { append("seasonal_pattern=$it, ") }
            deterministicResult.sleepHours?.let { append("sleep_hours=$it, ") }
            deterministicResult.sleepQuality?.let { append("sleep_quality=$it, ") }
            deterministicResult.stressLevel?.let { append("stress_level=$it, ") }
            deterministicResult.screenTimeDaily?.let { append("screen_time_daily=$it, ") }
            deterministicResult.caffeineIntake?.let { append("caffeine_intake=$it, ") }
            deterministicResult.alcoholFrequency?.let { append("alcohol_frequency=$it, ") }
            deterministicResult.exerciseFrequency?.let { append("exercise_frequency=$it, ") }
            deterministicResult.tracksCycle?.let { append("tracks_cycle=$it, ") }
            if (deterministicResult.matchedTriggers.isNotEmpty()) append("triggers=${deterministicResult.matchedTriggers}, ")
            if (deterministicResult.matchedProdromes.isNotEmpty()) append("prodromes=${deterministicResult.matchedProdromes}, ")
            if (deterministicResult.matchedSymptoms.isNotEmpty()) append("symptoms=${deterministicResult.matchedSymptoms}, ")
            if (deterministicResult.matchedMedicines.isNotEmpty()) append("medicines=${deterministicResult.matchedMedicines}, ")
            if (deterministicResult.matchedReliefs.isNotEmpty()) append("reliefs=${deterministicResult.matchedReliefs}, ")
            if (deterministicResult.matchedActivities.isNotEmpty()) append("activities=${deterministicResult.matchedActivities}, ")
            if (deterministicResult.matchedMissedActivities.isNotEmpty()) append("missed_activities=${deterministicResult.matchedMissedActivities}, ")
        }

        // System prompt lives server-side under context_type "onboarding_parser".
        // user_message carries all dynamic data: user text, parser results, pool labels.
        return try {
            val userMessage = buildString {
                appendLine("User said: \"$text\"")
                appendLine()
                appendLine("Already found by deterministic parser: $alreadyFound")
                appendLine()
                appendLine("=== POOL ITEMS (only return labels from these EXACT lists) ===")
                appendLine("TRIGGERS: ${triggerLabels.joinToString(", ")}")
                appendLine("PRODROMES: ${prodromeLabels.joinToString(", ")}")
                appendLine("SYMPTOMS: ${symptomLabels.joinToString(", ")}")
                appendLine("MEDICINES: ${medicineLabels.joinToString(", ")}")
                appendLine("RELIEFS: ${reliefLabels.joinToString(", ")}")
                appendLine("ACTIVITIES: ${activityLabels.joinToString(", ")}")
                appendLine("MISSED_ACTIVITIES: ${missedActivityLabels.joinToString(", ")}")
                appendLine("LOCATIONS: ${locationLabels.joinToString(", ")}")
                appendLine("POSTDROMES: ${postdromeLabels.joinToString(", ")}")
                appendLine()
                appendLine("For LOCATIONS, infer from context (e.g. \"always at the office\" → Work; \"in the car\" → Commute/Car).")
                appendLine("For POSTDROMES, infer recovery-phase mentions (e.g. \"wiped out next day\" → Fatigue; \"foggy after\" → Brain fog).")
                appendLine("Return them as JSON arrays \"locations\" and \"postdromes\".")
            }
            val requestBody = JSONObject().apply {
                put("context_type", "onboarding_parser")
                put("user_message", userMessage)
            }
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/ai-setup"
            val client = HttpClient(Android)
            try {
                val response = client.post(url) {
                    header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody(requestBody.toString())
                }
                val responseText = response.bodyAsText()
                    .replace("```json", "").replace("```", "").trim()

                if (!response.status.isSuccess()) {
                    Log.e(TAG, "GPT call failed: ${response.status}")
                    return null
                }
                parseGptResponse(responseText, triggerLabels, prodromeLabels, symptomLabels,
                    medicineLabels, reliefLabels, activityLabels, missedActivityLabels,
                    locationLabels, postdromeLabels)
            } finally {
                client.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "GPT pre-fill failed: ${e.message}", e)
            null
        }
    }

    private fun parseGptResponse(
        json: String,
        triggerLabels: List<String>,
        prodromeLabels: List<String>,
        symptomLabels: List<String>,
        medicineLabels: List<String>,
        reliefLabels: List<String>,
        activityLabels: List<String>,
        missedActivityLabels: List<String>,
        locationLabels: List<String> = emptyList(),
        postdromeLabels: List<String> = emptyList(),
    ): OnboardingPreFill {
        val obj = JSONObject(json)

        fun optStr(key: String): String? = obj.optString(key, "").let { if (it == "null" || it.isBlank()) null else it }
        fun optStrIn(key: String, allowed: Set<String>): String? = optStr(key)?.takeIf { it in allowed }
        fun optCert(key: String): DeterministicMapper.Certainty? {
            val s = optStr(key) ?: return null
            return runCatching { DeterministicMapper.Certainty.valueOf(s) }.getOrNull()
        }
        fun optStrSet(key: String, allowed: Set<String>): Set<String> {
            val arr = obj.optJSONArray(key) ?: return emptySet()
            val out = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s in allowed) out.add(s)
            }
            return out
        }
        fun optCertMap(key: String, allowedKeys: Set<String>): Map<String, DeterministicMapper.Certainty> {
            val inner = obj.optJSONObject(key) ?: return emptyMap()
            val out = mutableMapOf<String, DeterministicMapper.Certainty>()
            val it = inner.keys()
            while (it.hasNext()) {
                val k = it.next()
                if (k !in allowedKeys) continue
                val cert = runCatching {
                    DeterministicMapper.Certainty.valueOf(inner.optString(k, "").trim())
                }.getOrNull() ?: continue
                out[k] = cert
            }
            return out
        }

        fun matchArray(key: String, validLabels: List<String>): Set<String> {
            val arr = obj.optJSONArray(key) ?: return emptySet()
            val labelMap = validLabels.associateBy { it.lowercase() }
            val result = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val label = arr.optString(i, "").trim()
                val matched = labelMap[label.lowercase()]
                if (matched != null) result.add(matched)
            }
            return result
        }

        return OnboardingPreFill(
            // Page 1
            gender = optStr("gender"),
            ageRange = optStr("age_range"),
            frequency = optStr("frequency"),
            duration = optStr("duration"),
            experience = optStr("experience"),
            trajectory = optStr("trajectory"),
            warningBefore = optStr("warning_before"),
            triggerDelay = optStr("trigger_delay"),
            dailyRoutine = optStr("daily_routine"),
            seasonalPattern = optStr("seasonal_pattern"),
            // Page 2
            sleepHours = optStr("sleep_hours"),
            sleepQuality = optStr("sleep_quality"),
            poorQualityTriggers = optCert("poor_quality_triggers"),
            tooLittleSleepTriggers = optCert("too_little_sleep_triggers"),
            oversleepTriggers = optCert("oversleep_triggers"),
            sleepIssues = optStrSet("sleep_issues", SLEEP_ISSUES_VALUES),
            // Page 3
            stressLevel = optStr("stress_level"),
            stressChangeTriggers = optCert("stress_change_triggers"),
            emotionalPatterns = optCertMap("emotional_patterns", EMOTIONAL_PATTERNS_KEYS),
            screenTimeDaily = optStr("screen_time_daily"),
            screenTimeTriggers = optCert("screen_time_triggers"),
            lateScreenTriggers = optCert("late_screen_triggers"),
            // Page 4
            caffeineIntake = optStr("caffeine_intake"),
            caffeineDirection = optStrIn("caffeine_direction", CAFFEINE_DIRECTION_VALUES),
            caffeineCertainty = optCert("caffeine_certainty"),
            alcoholFrequency = optStr("alcohol_frequency"),
            alcoholTriggers = optCert("alcohol_triggers"),
            specificDrinks = optStrSet("specific_drinks", SPECIFIC_DRINKS_VALUES),
            tyramineFoods = optCertMap("tyramine_foods", TYRAMINE_FOODS_KEYS),
            histamineFoods = optCertMap("histamine_foods", HISTAMINE_FOODS_KEYS),
            glutenSensitivity = optStrIn("gluten_sensitivity", GLUTEN_SENSITIVITY_VALUES),
            glutenTriggers = optCert("gluten_triggers"),
            eatingPatterns = optCertMap("eating_patterns", EATING_PATTERNS_KEYS),
            waterIntake = optStrIn("water_intake", WATER_INTAKE_VALUES),
            tracksNutrition = optStrIn("tracks_nutrition", TRACKS_NUTRITION_VALUES),
            // Page 5
            weatherTriggers = optCert("weather_triggers"),
            specificWeather = optCertMap("specific_weather", SPECIFIC_WEATHER_KEYS),
            environmentSensitivities = optCertMap("environment_sensitivities", ENVIRONMENT_SENSITIVITIES_KEYS),
            physicalFactors = optCertMap("physical_factors", PHYSICAL_FACTORS_KEYS),
            // Page 6
            exerciseFrequency = optStr("exercise_frequency"),
            exerciseTriggers = optCert("exercise_triggers"),
            exercisePattern = optStrSet("exercise_pattern", EXERCISE_PATTERN_VALUES),
            tracksCycle = optStr("tracks_cycle"),
            cyclePatterns = optCertMap("cycle_patterns", CYCLE_PATTERNS_KEYS),
            cycleLength = optStrIn("cycle_length", CYCLE_LENGTH_VALUES),
            cycleMigraineTiming = optStrSet("cycle_migraine_timing", CYCLE_TIMING_VALUES),
            lastPeriodDate = optStr("last_period_date")?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) },
            usesContraception = optStrIn("uses_contraception", USES_CONTRACEPTION_VALUES),
            contraceptionEffect = optStrIn("contraception_effect", CONTRACEPTION_EFFECT_VALUES),
            // Page 7
            physicalProdromes = optCertMap("physical_prodromes", PHYSICAL_PRODROMES_KEYS),
            moodProdromes = optCertMap("mood_prodromes", MOOD_PRODROMES_KEYS),
            sensoryProdromes = optCertMap("sensory_prodromes", SENSORY_PRODROMES_KEYS),
            // Pool labels
            matchedTriggers = matchArray("triggers", triggerLabels),
            matchedProdromes = matchArray("prodromes", prodromeLabels),
            matchedSymptoms = matchArray("symptoms", symptomLabels),
            matchedMedicines = matchArray("medicines", medicineLabels),
            matchedReliefs = matchArray("reliefs", reliefLabels),
            matchedActivities = matchArray("activities", activityLabels),
            matchedMissedActivities = matchArray("missed_activities", missedActivityLabels),
            matchedLocations = matchArray("locations", locationLabels),
            matchedPostdromes = matchArray("postdromes", postdromeLabels),
        )
    }

    /**
     * Merge deterministic + GPT results. GPT wins for scalar fields,
     * sets/maps are unioned (GPT values override deterministic for shared map keys).
     */
    fun merge(deter: OnboardingPreFill, gpt: OnboardingPreFill?): OnboardingPreFill {
        if (gpt == null) return deter
        return OnboardingPreFill(
            // Page 1
            gender = gpt.gender ?: deter.gender,
            ageRange = gpt.ageRange ?: deter.ageRange,
            frequency = gpt.frequency ?: deter.frequency,
            duration = gpt.duration ?: deter.duration,
            experience = gpt.experience ?: deter.experience,
            trajectory = gpt.trajectory ?: deter.trajectory,
            warningBefore = gpt.warningBefore ?: deter.warningBefore,
            triggerDelay = gpt.triggerDelay ?: deter.triggerDelay,
            dailyRoutine = gpt.dailyRoutine ?: deter.dailyRoutine,
            seasonalPattern = gpt.seasonalPattern ?: deter.seasonalPattern,
            // Page 2
            sleepHours = gpt.sleepHours ?: deter.sleepHours,
            sleepQuality = gpt.sleepQuality ?: deter.sleepQuality,
            poorQualityTriggers = gpt.poorQualityTriggers ?: deter.poorQualityTriggers,
            tooLittleSleepTriggers = gpt.tooLittleSleepTriggers ?: deter.tooLittleSleepTriggers,
            oversleepTriggers = gpt.oversleepTriggers ?: deter.oversleepTriggers,
            sleepIssues = deter.sleepIssues + gpt.sleepIssues,
            // Page 3
            stressLevel = gpt.stressLevel ?: deter.stressLevel,
            stressChangeTriggers = gpt.stressChangeTriggers ?: deter.stressChangeTriggers,
            emotionalPatterns = deter.emotionalPatterns + gpt.emotionalPatterns,
            screenTimeDaily = gpt.screenTimeDaily ?: deter.screenTimeDaily,
            screenTimeTriggers = gpt.screenTimeTriggers ?: deter.screenTimeTriggers,
            lateScreenTriggers = gpt.lateScreenTriggers ?: deter.lateScreenTriggers,
            // Page 4
            caffeineIntake = gpt.caffeineIntake ?: deter.caffeineIntake,
            caffeineDirection = gpt.caffeineDirection ?: deter.caffeineDirection,
            caffeineCertainty = gpt.caffeineCertainty ?: deter.caffeineCertainty,
            alcoholFrequency = gpt.alcoholFrequency ?: deter.alcoholFrequency,
            alcoholTriggers = gpt.alcoholTriggers ?: deter.alcoholTriggers,
            specificDrinks = deter.specificDrinks + gpt.specificDrinks,
            tyramineFoods = deter.tyramineFoods + gpt.tyramineFoods,
            histamineFoods = deter.histamineFoods + gpt.histamineFoods,
            glutenSensitivity = gpt.glutenSensitivity ?: deter.glutenSensitivity,
            glutenTriggers = gpt.glutenTriggers ?: deter.glutenTriggers,
            eatingPatterns = deter.eatingPatterns + gpt.eatingPatterns,
            waterIntake = gpt.waterIntake ?: deter.waterIntake,
            tracksNutrition = gpt.tracksNutrition ?: deter.tracksNutrition,
            // Page 5
            weatherTriggers = gpt.weatherTriggers ?: deter.weatherTriggers,
            specificWeather = deter.specificWeather + gpt.specificWeather,
            environmentSensitivities = deter.environmentSensitivities + gpt.environmentSensitivities,
            physicalFactors = deter.physicalFactors + gpt.physicalFactors,
            // Page 6
            exerciseFrequency = gpt.exerciseFrequency ?: deter.exerciseFrequency,
            exerciseTriggers = gpt.exerciseTriggers ?: deter.exerciseTriggers,
            exercisePattern = deter.exercisePattern + gpt.exercisePattern,
            tracksCycle = gpt.tracksCycle ?: deter.tracksCycle,
            cyclePatterns = deter.cyclePatterns + gpt.cyclePatterns,
            cycleLength = gpt.cycleLength ?: deter.cycleLength,
            cycleMigraineTiming = deter.cycleMigraineTiming + gpt.cycleMigraineTiming,
            lastPeriodDate = gpt.lastPeriodDate ?: deter.lastPeriodDate,
            usesContraception = gpt.usesContraception ?: deter.usesContraception,
            contraceptionEffect = gpt.contraceptionEffect ?: deter.contraceptionEffect,
            // Page 7
            physicalProdromes = deter.physicalProdromes + gpt.physicalProdromes,
            moodProdromes = deter.moodProdromes + gpt.moodProdromes,
            sensoryProdromes = deter.sensoryProdromes + gpt.sensoryProdromes,
            // Pool labels
            matchedTriggers = deter.matchedTriggers + gpt.matchedTriggers,
            matchedProdromes = deter.matchedProdromes + gpt.matchedProdromes,
            matchedSymptoms = deter.matchedSymptoms + gpt.matchedSymptoms,
            matchedMedicines = deter.matchedMedicines + gpt.matchedMedicines,
            matchedReliefs = deter.matchedReliefs + gpt.matchedReliefs,
            matchedActivities = deter.matchedActivities + gpt.matchedActivities,
            matchedMissedActivities = deter.matchedMissedActivities + gpt.matchedMissedActivities,
            matchedLocations = deter.matchedLocations + gpt.matchedLocations,
            matchedPostdromes = deter.matchedPostdromes + gpt.matchedPostdromes,
        )
    }
}
