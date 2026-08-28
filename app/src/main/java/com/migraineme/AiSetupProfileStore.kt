package com.migraineme

import android.content.Context
import android.util.Log
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * Persists the AI setup questionnaire answers + AI-generated config to Supabase.
 * Used later for community matching, article recommendations, cohort analysis, etc.
 */
object AiSetupProfileStore {

    private const val TAG = "AiSetupProfileStore"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * Upserts the user's questionnaire answers + AI config to ai_setup_profiles.
     * Call this right after AiSetupApplier.applyConfig() succeeds.
     */
    suspend fun save(
        context: Context,
        answers: DeterministicMapper.QuestionnaireAnswers,
        config: AiSetupService.AiConfig,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val accessToken = SessionStore.getValidAccessToken(appCtx)
            ?: return@withContext Result.failure(Exception("Not authenticated"))
        val userId = SessionStore.readUserId(appCtx)
            ?: return@withContext Result.failure(Exception("No user ID"))

        val answersJson = buildAnswersJson(answers)
        val configJson = json.encodeToString(config)

        val body = buildJsonObject {
            put("user_id", userId)
            put("answers", json.parseToJsonElement(answersJson))
            put("ai_config", json.parseToJsonElement(configJson))

            // Extracted fields for easy querying
            put("gender", answers.gender)
            put("age_range", answers.ageRange)
            put("frequency", answers.frequency)
            put("duration", answers.duration)
            put("experience", answers.experience)
            put("trajectory", answers.trajectory)
            put("seasonal_pattern", answers.seasonalPattern)
            put("tracks_cycle", answers.tracksCycle == "Yes")
            put("clinical_assessment", config.clinicalAssessment)
            put("summary", config.summary)

            // trigger_areas as JSON array for the text[] column
            val areas = buildTriggerAreas(answers)
            put("trigger_areas", JsonArray(areas.map { JsonPrimitive(it) }))
        }

        val client = HttpClient(io.ktor.client.engine.android.Android) {
            install(ContentNegotiation) { json(json) }
        }

        try {
            // on_conflict=user_id: without it PostgREST resolves duplicates on the
            // primary key only, and every SECOND save (redo, edit) died with 409
            // "ai_setup_profiles_user_id_unique" — silently, because the caller
            // treats this save as non-blocking. iOS always passed it.
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/ai_setup_profiles?on_conflict=user_id"
            val response = client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("Prefer", "resolution=merge-duplicates")  // upsert
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }

            if (response.status.value in 200..299) {
                Log.d(TAG, "AI setup profile saved successfully")
                Result.success(Unit)
            } else {
                val err = response.bodyAsText()
                Log.e(TAG, "Failed to save AI setup profile: ${response.status} $err")
                Result.failure(Exception("Save failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving AI setup profile", e)
            Result.failure(e)
        } finally {
            client.close()
        }
    }

    // ── Read back ────────────────────────────────────────────────────────

    /** The saved row, or null when the user has never finished setup. */
    data class SavedProfile(
        val row: JsonObject,
        /** `answers` — null when the row was created by an accepted proposal, not by setup. */
        val answers: JsonObject?,
    )

    suspend fun load(context: Context): SavedProfile? = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val accessToken = SessionStore.getValidAccessToken(appCtx) ?: return@withContext null
        val userId = SessionStore.readUserId(appCtx) ?: return@withContext null
        val client = HttpClient(io.ktor.client.engine.android.Android)
        try {
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/ai_setup_profiles" +
                "?user_id=eq.$userId&select=*&limit=1"
            val response = client.get(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            if (response.status.value !in 200..299) return@withContext null
            val arr = json.parseToJsonElement(response.bodyAsText()).jsonArray
            val row = arr.firstOrNull()?.jsonObject ?: return@withContext null
            val answers = (row["answers"] as? JsonObject)
            SavedProfile(row = row, answers = answers)
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
            null
        } finally {
            client.close()
        }
    }

    /**
     * Turns a saved `answers` object back into the questionnaire's pre-fill
     * shape so the flow opens with the user's own answers in place.
     *
     * The same column holds two spellings: this file writes snake_case
     * (`age_range`), iOS AiSetupFlow.saveAiSetupProfile writes camelCase
     * (`ageRange`) — and a user can have set up on either. Every read below
     * tries both, the way recalibrate/index.ts does with
     * `answers.avg_duration ?? answers.avgDuration`.
     */
    fun preFillFromAnswers(a: JsonObject): OnboardingPreFill {
        fun el(snake: String, camel: String): JsonElement? =
            (a[snake] ?: a[camel])?.takeIf { it !is JsonNull }
        fun str(snake: String, camel: String): String? =
            (el(snake, camel) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        fun cert(raw: String?): DeterministicMapper.Certainty? {
            val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val c = runCatching { DeterministicMapper.Certainty.valueOf(v.uppercase()) }.getOrNull()
                ?: CertaintyWords.fromLocalisedWord(v) ?: return null
            // NO is the questionnaire's unanswered state — leaving it null keeps
            // the chip untouched rather than pinning an explicit "No".
            return c.takeIf { it != DeterministicMapper.Certainty.NO }
        }
        fun certOf(snake: String, camel: String) = cert(str(snake, camel))
        fun strSet(snake: String, camel: String): Set<String> =
            (el(snake, camel) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }?.toSet()
                ?: emptySet()
        fun certMap(snake: String, camel: String): Map<String, DeterministicMapper.Certainty> {
            val obj = el(snake, camel) as? JsonObject ?: return emptyMap()
            return obj.entries.mapNotNull { (k, v) ->
                cert((v as? JsonPrimitive)?.contentOrNull)?.let { k to it }
            }.toMap()
        }

        return OnboardingPreFill(
            gender = str("gender", "gender"),
            ageRange = str("age_range", "ageRange"),
            frequency = str("frequency", "frequency"),
            duration = str("duration", "duration"),
            experience = str("experience", "experience"),
            trajectory = str("trajectory", "trajectory"),
            warningBefore = str("warning_signs_before", "warningBefore"),
            triggerDelay = str("trigger_delay", "triggerDelay"),
            dailyRoutine = str("daily_routine", "dailyRoutine"),
            seasonalPattern = str("seasonal_pattern", "seasonalPattern"),
            sleepHours = str("sleep_hours", "sleepHours"),
            sleepQuality = str("sleep_quality", "sleepQuality"),
            poorQualityTriggers = certOf("poor_sleep_quality_triggers", "poorQualityTriggers"),
            tooLittleSleepTriggers = certOf("too_little_sleep_triggers", "tooLittleSleepTriggers"),
            oversleepTriggers = certOf("oversleep_triggers", "oversleepTriggers"),
            sleepIssues = strSet("sleep_issues", "sleepIssues"),
            stressLevel = str("stress_level", "stressLevel"),
            stressChangeTriggers = certOf("stress_change_triggers", "stressChangeTriggers"),
            emotionalPatterns = certMap("emotional_patterns", "emotionalPatterns"),
            screenTimeDaily = str("screen_time_daily", "screenTimeDaily"),
            screenTimeTriggers = certOf("screen_time_triggers", "screenTimeTriggers"),
            lateScreenTriggers = certOf("late_screen_triggers", "lateScreenTriggers"),
            caffeineIntake = str("caffeine_intake", "caffeineIntake"),
            caffeineDirection = str("caffeine_direction", "caffeineDirection"),
            caffeineCertainty = certOf("caffeine_certainty", "caffeineCertainty"),
            alcoholFrequency = str("alcohol_frequency", "alcoholFrequency"),
            alcoholTriggers = certOf("alcohol_triggers", "alcoholTriggers"),
            specificDrinks = strSet("specific_drinks", "specificDrinks"),
            tyramineFoods = certMap("tyramine_foods", "tyramineFoods"),
            histamineFoods = certMap("histamine_foods", "histamineFoods"),
            glutenSensitivity = str("gluten_sensitivity", "glutenSensitivity"),
            glutenTriggers = certOf("gluten_triggers", "glutenTriggers"),
            eatingPatterns = certMap("eating_patterns", "eatingPatterns"),
            waterIntake = str("water_intake", "waterIntake"),
            tracksNutrition = str("tracks_nutrition", "tracksNutrition"),
            weatherTriggers = certOf("weather_triggers", "weatherTriggers"),
            specificWeather = certMap("specific_weather", "specificWeather"),
            environmentSensitivities = certMap("environment_sensitivities", "environmentSensitivities"),
            physicalFactors = certMap("physical_factors", "physicalFactors"),
            exerciseFrequency = str("exercise_frequency", "exerciseFrequency"),
            exerciseTriggers = certOf("exercise_triggers", "exerciseTriggers"),
            exercisePattern = strSet("exercise_pattern", "exercisePattern"),
            tracksCycle = str("tracks_cycle", "tracksCycle"),
            cyclePatterns = certMap("cycle_patterns", "cyclePatterns"),
            cycleLength = str("cycle_length", "cycleLength"),
            cycleMigraineTiming = strSet("cycle_migraine_timing", "cycleMigraineTiming"),
            lastPeriodDate = str("last_period_date", "lastPeriodDate"),
            usesContraception = str("uses_contraception", "usesContraception"),
            contraceptionEffect = str("contraception_effect", "contraceptionEffect"),
            physicalProdromes = certMap("physical_prodromes", "physicalProdromes"),
            moodProdromes = certMap("mood_prodromes", "moodProdromes"),
            sensoryProdromes = certMap("sensory_prodromes", "sensoryProdromes"),
            matchedTriggers = strSet("selected_triggers", "selectedTriggers"),
            matchedProdromes = strSet("selected_prodromes", "selectedProdromes"),
            matchedSymptoms = strSet("selected_symptoms", "selectedSymptoms"),
            matchedMedicines = strSet("selected_medicines", "selectedMedicines"),
            matchedReliefs = strSet("selected_reliefs", "selectedReliefs"),
            matchedActivities = strSet("selected_activities", "selectedActivities"),
            matchedMissedActivities = strSet("selected_missed_activities", "selectedMissedActivities"),
            matchedLocations = strSet("selected_locations", "selectedLocations"),
            matchedPostdromes = strSet("selected_postdromes", "selectedPostdromes"),
        )
    }

    /** `answers.free_text` — the one key both platforms already spell the same way. */
    fun freeText(a: JsonObject?): String? =
        (a?.get("free_text") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    // ── Build a JSON representation of all questionnaire answers ──

    private fun buildAnswersJson(a: DeterministicMapper.QuestionnaireAnswers): String {
        val obj = buildJsonObject {
            // Page 1
            put("gender", a.gender)
            put("age_range", a.ageRange)
            put("frequency", a.frequency)
            put("duration", a.duration)
            put("experience", a.experience)
            put("trajectory", a.trajectory)
            put("warning_signs_before", a.warningSignsBefore)
            put("trigger_delay", a.triggerDelay)
            put("daily_routine", a.dailyRoutine)
            put("seasonal_pattern", a.seasonalPattern)

            // Page 2
            put("sleep_hours", a.sleepHours)
            put("sleep_quality", a.sleepQuality)
            put("poor_sleep_quality_triggers", a.poorSleepQualityTriggers.name)
            put("too_little_sleep_triggers", a.tooLittleSleepTriggers.name)
            put("oversleep_triggers", a.oversleepTriggers.name)
            put("sleep_issues", JsonArray(a.sleepIssues.map { JsonPrimitive(it) }))

            // Page 3
            put("stress_level", a.stressLevel)
            put("stress_change_triggers", a.stressChangeTriggers.name)
            put("emotional_patterns", certaintyMapToJson(a.emotionalPatterns))
            put("screen_time_daily", a.screenTimeDaily)
            put("screen_time_triggers", a.screenTimeTriggers.name)
            put("late_screen_triggers", a.lateScreenTriggers.name)

            // Page 4
            put("caffeine_intake", a.caffeineIntake)
            put("caffeine_direction", a.caffeineDirection)
            put("caffeine_certainty", a.caffeineCertainty.name)
            put("alcohol_frequency", a.alcoholFrequency)
            put("alcohol_triggers", a.alcoholTriggers.name)
            put("specific_drinks", JsonArray(a.specificDrinks.map { JsonPrimitive(it) }))
            put("tyramine_foods", certaintyMapToJson(a.tyramineFoods))
            put("histamine_foods", certaintyMapToJson(a.histamineFoods))
            put("gluten_sensitivity", a.glutenSensitivity)
            put("gluten_triggers", a.glutenTriggers.name)
            put("eating_patterns", certaintyMapToJson(a.eatingPatterns))
            put("water_intake", a.waterIntake)
            put("tracks_nutrition", a.tracksNutrition)

            // Page 5
            put("weather_triggers", a.weatherTriggers.name)
            put("specific_weather", certaintyMapToJson(a.specificWeather))
            put("environment_sensitivities", certaintyMapToJson(a.environmentSensitivities))
            put("physical_factors", certaintyMapToJson(a.physicalFactors))

            // Page 6
            put("exercise_frequency", a.exerciseFrequency)
            put("exercise_triggers", a.exerciseTriggers.name)
            put("exercise_pattern", JsonArray(a.exercisePattern.map { JsonPrimitive(it) }))
            put("tracks_cycle", a.tracksCycle)
            put("cycle_patterns", certaintyMapToJson(a.cyclePatterns))
            // cycle_length / last_period_date / cycle_migraine_timing and the
            // trigger / prodrome / location / postdrome picks below were
            // collected, sent to the AI and then never written, so an
            // edit-from-Profile round trip lost them. iOS always kept them.
            put("cycle_length", a.cycleLength)
            put("last_period_date", a.lastPeriodDate)
            put("cycle_migraine_timing", JsonArray(a.cycleMigraineTiming.map { JsonPrimitive(it) }))
            put("uses_contraception", a.usesContraception)
            put("contraception_effect", a.contraceptionEffect)

            // Page 7
            put("physical_prodromes", certaintyMapToJson(a.physicalProdromes))
            put("mood_prodromes", certaintyMapToJson(a.moodProdromes))
            put("sensory_prodromes", certaintyMapToJson(a.sensoryProdromes))

            // Page 8
            put("selected_triggers", JsonArray(a.selectedTriggers.map { JsonPrimitive(it) }))
            put("selected_prodromes", JsonArray(a.selectedProdromes.map { JsonPrimitive(it) }))
            put("selected_symptoms", JsonArray(a.selectedSymptoms.map { JsonPrimitive(it) }))
            put("selected_medicines", JsonArray(a.selectedMedicines.map { JsonPrimitive(it) }))
            put("selected_reliefs", JsonArray(a.selectedReliefs.map { JsonPrimitive(it) }))
            put("selected_activities", JsonArray(a.selectedActivities.map { JsonPrimitive(it) }))
            put("selected_missed_activities", JsonArray(a.selectedMissedActivities.map { JsonPrimitive(it) }))
            put("selected_locations", JsonArray(a.selectedLocations.map { JsonPrimitive(it) }))
            put("selected_postdromes", JsonArray(a.selectedPostdromes.map { JsonPrimitive(it) }))
            put("free_text", a.freeText)
        }
        return obj.toString()
    }

    private fun certaintyMapToJson(map: Map<String, DeterministicMapper.Certainty>): JsonObject {
        return buildJsonObject {
            map.forEach { (key, certainty) -> put(key, certainty.name) }
        }
    }

    /**
     * Derive trigger area tags from the questionnaire answers.
     * These are the high-level categories useful for community matching.
     */
    internal fun buildTriggerAreas(a: DeterministicMapper.QuestionnaireAnswers): List<String> {
        val areas = mutableListOf<String>()
        if (a.poorSleepQualityTriggers != DeterministicMapper.Certainty.NO ||
            a.tooLittleSleepTriggers != DeterministicMapper.Certainty.NO ||
            a.oversleepTriggers != DeterministicMapper.Certainty.NO) areas.add("Sleep")
        if (a.stressChangeTriggers != DeterministicMapper.Certainty.NO ||
            a.emotionalPatterns.isNotEmpty()) areas.add("Stress")
        if (a.screenTimeTriggers != DeterministicMapper.Certainty.NO ||
            a.lateScreenTriggers != DeterministicMapper.Certainty.NO) areas.add("Screen time")
        if (a.weatherTriggers != DeterministicMapper.Certainty.NO) areas.add("Weather")
        if (a.alcoholTriggers != DeterministicMapper.Certainty.NO ||
            a.caffeineCertainty != DeterministicMapper.Certainty.NO ||
            a.glutenTriggers != DeterministicMapper.Certainty.NO ||
            a.eatingPatterns.isNotEmpty() ||
            a.tyramineFoods.isNotEmpty() ||
            a.histamineFoods.isNotEmpty()) areas.add("Diet")
        if (a.exerciseTriggers != DeterministicMapper.Certainty.NO) areas.add("Exercise")
        if (a.tracksCycle == "Yes" && a.cyclePatterns.isNotEmpty()) areas.add("Hormones")
        if (a.environmentSensitivities.isNotEmpty()) areas.add("Environment")
        if (a.physicalFactors.isNotEmpty()) areas.add("Physical")
        return areas
    }
}
