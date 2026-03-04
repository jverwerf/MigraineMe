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
 * Only non-null fields should be applied — null means "not mentioned".
 */
data class OnboardingPreFill(
    // Page 1
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
    // Page 2
    val sleepHours: String? = null,
    val sleepQuality: String? = null,
    // Page 3
    val stressLevel: String? = null,
    val screenTimeDaily: String? = null,
    // Page 4
    val caffeineIntake: String? = null,
    val alcoholFrequency: String? = null,
    // Page 5 — no simple pre-fills (certainty items)
    // Page 6
    val exerciseFrequency: String? = null,
    val tracksCycle: String? = null,
    // Pool matches — labels from the DB pools
    val matchedTriggers: Set<String> = emptySet(),
    val matchedProdromes: Set<String> = emptySet(),
    val matchedSymptoms: Set<String> = emptySet(),
    val matchedMedicines: Set<String> = emptySet(),
    val matchedReliefs: Set<String> = emptySet(),
    val matchedActivities: Set<String> = emptySet(),
    val matchedMissedActivities: Set<String> = emptySet(),
)

object AiOnboardingParser {

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
        deterministicResult: OnboardingPreFill,
    ): OnboardingPreFill? {
        val alreadyFound = buildString {
            deterministicResult.frequency?.let { append("frequency=$it, ") }
            deterministicResult.duration?.let { append("duration=$it, ") }
            deterministicResult.experience?.let { append("experience=$it, ") }
            if (deterministicResult.matchedTriggers.isNotEmpty()) append("triggers=${deterministicResult.matchedTriggers}, ")
            if (deterministicResult.matchedProdromes.isNotEmpty()) append("prodromes=${deterministicResult.matchedProdromes}, ")
            if (deterministicResult.matchedMedicines.isNotEmpty()) append("medicines=${deterministicResult.matchedMedicines}, ")
            if (deterministicResult.matchedReliefs.isNotEmpty()) append("reliefs=${deterministicResult.matchedReliefs}, ")
            if (deterministicResult.matchedSymptoms.isNotEmpty()) append("symptoms=${deterministicResult.matchedSymptoms}, ")
        }

        val systemPrompt = """
You are helping a migraine patient set up their tracking app. They've described their migraine history in natural language. Extract everything you can.

A deterministic parser already found: $alreadyFound
ADD anything it missed. Only use values from the EXACT option lists provided.

=== QUESTIONNAIRE FIELDS (use EXACT option values or null) ===
gender: "Female", "Male", "Prefer not to say"
age_range: "18-25", "26-35", "36-45", "46-55", "56+"
frequency: "A few per year", "Every 1-2 months", "1-3 per month", "Weekly", "Chronic"
duration: "< 4 hours", "4-12 hours", "12-24 hours", "1-3 days", "3+ days"
experience: "New / recent", "1-5 years", "5-10 years", "10+ years"
trajectory: "Getting worse", "Getting better", "About the same", "Just started"
warning_before: "Yes, always", "Sometimes", "Rarely", "Never"
trigger_delay: "Within hours", "Next day", "Within 2-3 days", "Up to a week", "Not sure"
daily_routine: "Regular 9-5", "Shift work / rotating", "Irregular / freelance", "Student", "Stay at home"
seasonal_pattern: "Worse in winter", "Worse in summer", "Worse in spring", "No pattern", "Not sure"
sleep_hours: "< 5h", "5-6h", "6-7h", "7-8h", "8-9h", "9+h"
sleep_quality: "Good", "OK", "Poor", "Varies a lot"
stress_level: "Low", "Moderate", "High", "Very high"
screen_time_daily: "< 2h", "2-4h", "4-8h", "8-12h", "12h+"
caffeine_intake: "None", "1-2 cups", "3-4 cups", "5+ cups"
alcohol_frequency: "Never", "Occasionally", "Weekly", "Daily"
exercise_frequency: "Daily", "Few times/week", "Weekly", "Rarely", "Never"
tracks_cycle: "Yes", "No", "Not applicable"

=== POOL ITEMS (only return labels from these EXACT lists) ===
TRIGGERS: ${triggerLabels.joinToString(", ")}
PRODROMES: ${prodromeLabels.joinToString(", ")}
SYMPTOMS: ${symptomLabels.joinToString(", ")}
MEDICINES: ${medicineLabels.joinToString(", ")}
RELIEFS: ${reliefLabels.joinToString(", ")}
ACTIVITIES: ${activityLabels.joinToString(", ")}
MISSED_ACTIVITIES: ${missedActivityLabels.joinToString(", ")}

Respond with ONLY valid JSON, no markdown:
{
  "gender": "..." or null,
  "age_range": "..." or null,
  "frequency": "..." or null,
  "duration": "..." or null,
  "experience": "..." or null,
  "trajectory": "..." or null,
  "warning_before": "..." or null,
  "trigger_delay": "..." or null,
  "daily_routine": "..." or null,
  "seasonal_pattern": "..." or null,
  "sleep_hours": "..." or null,
  "sleep_quality": "..." or null,
  "stress_level": "..." or null,
  "screen_time_daily": "..." or null,
  "caffeine_intake": "..." or null,
  "alcohol_frequency": "..." or null,
  "exercise_frequency": "..." or null,
  "tracks_cycle": "..." or null,
  "triggers": ["exact label", ...],
  "prodromes": ["exact label", ...],
  "symptoms": ["exact label", ...],
  "medicines": ["exact label", ...],
  "reliefs": ["exact label", ...],
  "activities": ["exact label", ...],
  "missed_activities": ["exact label", ...]
}
""".trimIndent()

        return try {
            val requestBody = JSONObject().apply {
                put("system_prompt", systemPrompt)
                put("user_message", text)
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
                    medicineLabels, reliefLabels, activityLabels, missedActivityLabels)
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
    ): OnboardingPreFill {
        val obj = JSONObject(json)

        fun optStr(key: String): String? = obj.optString(key, "").let { if (it == "null" || it.isBlank()) null else it }

        fun matchArray(key: String, validLabels: List<String>): Set<String> {
            val arr = obj.optJSONArray(key) ?: return emptySet()
            val validSet = validLabels.map { it.lowercase() }.toSet()
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
            sleepHours = optStr("sleep_hours"),
            sleepQuality = optStr("sleep_quality"),
            stressLevel = optStr("stress_level"),
            screenTimeDaily = optStr("screen_time_daily"),
            caffeineIntake = optStr("caffeine_intake"),
            alcoholFrequency = optStr("alcohol_frequency"),
            exerciseFrequency = optStr("exercise_frequency"),
            tracksCycle = optStr("tracks_cycle"),
            matchedTriggers = matchArray("triggers", triggerLabels),
            matchedProdromes = matchArray("prodromes", prodromeLabels),
            matchedSymptoms = matchArray("symptoms", symptomLabels),
            matchedMedicines = matchArray("medicines", medicineLabels),
            matchedReliefs = matchArray("reliefs", reliefLabels),
            matchedActivities = matchArray("activities", activityLabels),
            matchedMissedActivities = matchArray("missed_activities", missedActivityLabels),
        )
    }

    /**
     * Merge deterministic + GPT results. GPT wins for questionnaire fields,
     * pool matches are unioned.
     */
    fun merge(deter: OnboardingPreFill, gpt: OnboardingPreFill?): OnboardingPreFill {
        if (gpt == null) return deter
        return OnboardingPreFill(
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
            sleepHours = gpt.sleepHours ?: deter.sleepHours,
            sleepQuality = gpt.sleepQuality ?: deter.sleepQuality,
            stressLevel = gpt.stressLevel ?: deter.stressLevel,
            screenTimeDaily = gpt.screenTimeDaily ?: deter.screenTimeDaily,
            caffeineIntake = gpt.caffeineIntake ?: deter.caffeineIntake,
            alcoholFrequency = gpt.alcoholFrequency ?: deter.alcoholFrequency,
            exerciseFrequency = gpt.exerciseFrequency ?: deter.exerciseFrequency,
            tracksCycle = gpt.tracksCycle ?: deter.tracksCycle,
            matchedTriggers = deter.matchedTriggers + gpt.matchedTriggers,
            matchedProdromes = deter.matchedProdromes + gpt.matchedProdromes,
            matchedSymptoms = deter.matchedSymptoms + gpt.matchedSymptoms,
            matchedMedicines = deter.matchedMedicines + gpt.matchedMedicines,
            matchedReliefs = deter.matchedReliefs + gpt.matchedReliefs,
            matchedActivities = deter.matchedActivities + gpt.matchedActivities,
            matchedMissedActivities = deter.matchedMissedActivities + gpt.matchedMissedActivities,
        )
    }
}
