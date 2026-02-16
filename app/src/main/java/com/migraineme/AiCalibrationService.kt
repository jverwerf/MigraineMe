package com.migraineme

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Calibration-only AI call. Deterministic mapping is done first, then this asks the AI
 * ONLY for gauge thresholds, decay curves, free text parsing, and summary.
 */
object AiCalibrationService {

    private const val TAG = "AiCalibration"

    suspend fun calibrate(
        context: Context,
        mapping: DeterministicMapper.MappingResult,
        items: AiSetupService.AvailableItems?,
    ): Result<AiSetupService.AiConfig> = withContext(Dispatchers.IO) {
        try {
            val appCtx = context.applicationContext
            val accessToken = SessionStore.getValidAccessToken(appCtx)
                ?: return@withContext Result.failure(Exception("Not authenticated"))

            val systemPrompt = CALIBRATION_SYSTEM_PROMPT
            val userMessage = buildCalibrationPrompt(mapping)

            Log.d(TAG, "Sending calibration. User msg: ${userMessage.length} chars")

            // Build request JSON manually (same as AiSetupService.generateConfig)
            val requestBody = JSONObject().apply {
                put("system_prompt", systemPrompt)
                put("user_message", userMessage)
            }

            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/ai-setup"

            val client = HttpClient(io.ktor.client.engine.android.Android)

            try {
                val response: HttpResponse = client.post(url) {
                    header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody(requestBody.toString())
                }

                val responseText = response.bodyAsText()
                Log.d(TAG, "Calibration response status: ${response.status}")

                if (!response.status.isSuccess()) {
                    Log.e(TAG, "Calibration error: $responseText")
                    return@withContext Result.failure(Exception("Calibration failed (${response.status})"))
                }

                val cleanJson = responseText
                    .replace("```json", "").replace("```", "")
                    .trim()

                val calibration = parseCalibrationResponse(cleanJson)
                val config = mergeToConfig(mapping, calibration)

                Log.d(TAG, "Merged: ${config.triggers.size} triggers, ${config.prodromes.size} prodromes")
                Result.success(config)
            } finally {
                client.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Calibration failed", e)
            // Fallback: deterministic mapping alone with safe defaults
            try {
                val fallback = mergeToConfig(mapping, CalibrationResult())
                Result.success(fallback)
            } catch (e2: Exception) {
                Result.failure(e)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════

    private const val CALIBRATION_SYSTEM_PROMPT = """You are calibrating a migraine prediction system. Triggers and prodromes are ALREADY set deterministically from the user's questionnaire. You only calibrate gauge thresholds, decay curves, and write a summary. Respond ONLY in valid JSON, no markdown."""

    private fun buildCalibrationPrompt(mapping: DeterministicMapper.MappingResult): String {
        val p = mapping.profileContext
        val t = mapping.triggers
        val pr = mapping.prodromes

        val high = t.filter { it.value.severity == "HIGH" }
        val mild = t.filter { it.value.severity == "MILD" }
        val low = t.filter { it.value.severity == "LOW" }

        return buildString {
            appendLine("=== USER PROFILE ===")
            appendLine("Gender: ${p.gender ?: "unknown"}, Age: ${p.ageRange ?: "unknown"}")
            appendLine("Frequency: ${p.frequency ?: "unknown"}, Duration: ${p.duration ?: "unknown"}")
            appendLine("Experience: ${p.experience ?: "unknown"}, Trajectory: ${p.trajectory ?: "unknown"}")
            appendLine("Warning signs: ${p.warningSignsBefore ?: "unknown"}, Trigger delay: ${p.triggerDelay ?: "unknown"}")
            appendLine("Routine: ${p.dailyRoutine ?: "unknown"}, Seasonal: ${p.seasonalPattern ?: "unknown"}")
            appendLine()
            appendLine("=== TRIGGERS (set deterministically) ===")
            appendLine("HIGH (${high.size}): ${high.keys.joinToString(", ").ifEmpty { "none" }}")
            appendLine("MILD (${mild.size}): ${mild.keys.joinToString(", ").ifEmpty { "none" }}")
            appendLine("LOW (${low.size}): ${low.keys.joinToString(", ").ifEmpty { "none" }}")
            appendLine()
            val prHigh = pr.filter { it.value.severity == "HIGH" }
            val prMild = pr.filter { it.value.severity == "MILD" }
            val prLow = pr.filter { it.value.severity == "LOW" }
            appendLine("=== PRODROMES ===")
            appendLine("HIGH: ${prHigh.keys.joinToString(", ").ifEmpty { "none" }}")
            appendLine("MILD: ${prMild.keys.joinToString(", ").ifEmpty { "none" }}")
            appendLine("LOW: ${prLow.keys.joinToString(", ").ifEmpty { "none" }}")
            appendLine()
            appendLine("=== RESPOND IN THIS EXACT JSON FORMAT ===")
            append("""{"gauge_thresholds":{"low":3,"mild":8,"high":15,"reasoning":"..."},"decay_weights":[{"severity":"HIGH","day0":10,"day1":5,"day2":2.5,"day3":1,"day4":0,"day5":0,"day6":0,"reasoning":"..."},{"severity":"MILD","day0":6,"day1":3,"day2":1.5,"day3":0.5,"day4":0,"day5":0,"day6":0,"reasoning":"..."},{"severity":"LOW","day0":3,"day1":1.5,"day2":0,"day3":0,"day4":0,"day5":0,"day6":0,"reasoning":"..."}],"adjustments":[],"summary":"Based on your profile..."}""")
        }
    }

    // ═════════════════════════════════════════════════════════════════════

    private data class CalibrationResult(
        val gauge: AiSetupService.AiGaugeThresholds = AiSetupService.AiGaugeThresholds(),
        val decays: List<AiSetupService.AiDecayWeights> = emptyList(),
        val adjustments: List<Triple<String, String, String>> = emptyList(),
        val summary: String = "Your migraine triggers and warning signs have been configured based on your answers.",
    )

    private fun parseCalibrationResponse(json: String): CalibrationResult {
        return try {
            val obj = JSONObject(json)

            val gauge = obj.optJSONObject("gauge_thresholds")?.let { g ->
                AiSetupService.AiGaugeThresholds(
                    low = g.optInt("low", 3),
                    mild = g.optInt("mild", 8),
                    high = g.optInt("high", 15),
                    reasoning = g.optString("reasoning", ""),
                )
            } ?: AiSetupService.AiGaugeThresholds()

            val decays = mutableListOf<AiSetupService.AiDecayWeights>()
            val arr = obj.optJSONArray("decay_weights")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val d = arr.getJSONObject(i)
                    decays.add(AiSetupService.AiDecayWeights(
                        severity = d.optString("severity", "MILD"),
                        day0 = d.optDouble("day0", 0.0),
                        day1 = d.optDouble("day1", 0.0),
                        day2 = d.optDouble("day2", 0.0),
                        day3 = d.optDouble("day3", 0.0),
                        day4 = d.optDouble("day4", 0.0),
                        day5 = d.optDouble("day5", 0.0),
                        day6 = d.optDouble("day6", 0.0),
                        reasoning = d.optString("reasoning", ""),
                    ))
                }
            }

            val adjustments = mutableListOf<Triple<String, String, String>>()
            val adjArr = obj.optJSONArray("adjustments")
            if (adjArr != null) {
                for (i in 0 until adjArr.length()) {
                    val a = adjArr.getJSONObject(i)
                    adjustments.add(Triple(
                        a.optString("label", ""),
                        a.optString("to", a.optString("severity", "")),
                        a.optString("reason", ""),
                    ))
                }
            }

            CalibrationResult(
                gauge = gauge,
                decays = decays,
                adjustments = adjustments,
                summary = obj.optString("summary", "Your migraine setup has been configured."),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse calibration", e)
            CalibrationResult()
        }
    }

    private fun mergeToConfig(
        mapping: DeterministicMapper.MappingResult,
        cal: CalibrationResult,
    ): AiSetupService.AiConfig {
        val adjusted = mapping.triggers.toMutableMap()
        for ((label, toSev, _) in cal.adjustments) {
            val existing = adjusted[label]
            if (existing != null) adjusted[label] = existing.copy(severity = toSev)
        }

        val triggerRecs = adjusted.values.filter { t -> t.severity != "NONE" }.map { t ->
            AiSetupService.AiTriggerRec(
                label = t.label, severity = t.severity,
                decayDays = when (t.severity) { "HIGH" -> 3; "MILD" -> 2; else -> 1 },
                favorite = t.favorite || t.severity != "NONE", reasoning = "",
                defaultThreshold = t.threshold,
            )
        }

        val prodromeRecs = mapping.prodromes.values.filter { p -> p.severity != "NONE" }.map { p ->
            AiSetupService.AiProdromeRec(
                label = p.label, severity = p.severity,
                favorite = p.favorite || p.severity != "NONE", reasoning = "",
                defaultThreshold = p.threshold,
            )
        }

        val toFav = { labels: List<String> ->
            labels.map { label -> AiSetupService.AiFavoriteRec(label = label, favorite = true) }
        }

        return AiSetupService.AiConfig(
            triggers = triggerRecs,
            prodromes = prodromeRecs,
            symptoms = toFav(mapping.favorites.symptoms),
            medicines = toFav(mapping.favorites.medicines),
            reliefs = toFav(mapping.favorites.reliefs),
            activities = toFav(mapping.favorites.activities),
            missedActivities = toFav(mapping.favorites.missedActivities),
            gaugeThresholds = cal.gauge,
            decayWeights = cal.decays,
            dataWarnings = emptyList(),
            summary = cal.summary,
        )
    }
}
