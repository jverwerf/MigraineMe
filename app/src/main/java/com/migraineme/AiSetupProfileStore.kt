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
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/ai_setup_profiles"
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
            put("uses_contraception", a.usesContraception)
            put("contraception_effect", a.contraceptionEffect)

            // Page 7
            put("physical_prodromes", certaintyMapToJson(a.physicalProdromes))
            put("mood_prodromes", certaintyMapToJson(a.moodProdromes))
            put("sensory_prodromes", certaintyMapToJson(a.sensoryProdromes))

            // Page 8
            put("selected_symptoms", JsonArray(a.selectedSymptoms.map { JsonPrimitive(it) }))
            put("selected_medicines", JsonArray(a.selectedMedicines.map { JsonPrimitive(it) }))
            put("selected_reliefs", JsonArray(a.selectedReliefs.map { JsonPrimitive(it) }))
            put("selected_activities", JsonArray(a.selectedActivities.map { JsonPrimitive(it) }))
            put("selected_missed_activities", JsonArray(a.selectedMissedActivities.map { JsonPrimitive(it) }))
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
    private fun buildTriggerAreas(a: DeterministicMapper.QuestionnaireAnswers): List<String> {
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
            a.tyramineFoods.isNotEmpty()) areas.add("Diet")
        if (a.exerciseTriggers != DeterministicMapper.Certainty.NO) areas.add("Exercise")
        if (a.tracksCycle == "Yes" && a.cyclePatterns.isNotEmpty()) areas.add("Hormones")
        if (a.environmentSensitivities.isNotEmpty()) areas.add("Environment")
        if (a.physicalFactors.isNotEmpty()) areas.add("Physical")
        return areas
    }
}
