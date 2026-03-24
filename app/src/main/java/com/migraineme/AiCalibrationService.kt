package com.migraineme

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
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
 * Two-call AI calibration service.
 *
 * CALL 1 — "The Neurologist":
 *   Receives the full user profile as a narrative + locked Layer 1 ratings.
 *   Returns: clinical_assessment (shown to user), trigger/prodrome adjustments,
 *   data warnings.
 *
 * CALL 2 — "The Gauge Calibrator":
 *   Receives the final trigger stats (counts, severity distribution, score ranges)
 *   plus the clinical assessment for context.
 *   Returns: gauge_thresholds, decay_weights, calibration_notes (shown to user),
 *   combined summary.
 *
 * Layer 1 (DeterministicMapper) ratings are FLOOR values — never lowered by AI.
 */
object AiCalibrationService {

    private const val TAG = "AiCalibration"

    // ═════════════════════════════════════════════════════════════════════
    // Intermediate data classes
    // ═════════════════════════════════════════════════════════════════════

    data class TriggerStats(
        val totalActive: Int,
        val countHigh: Int,
        val countMild: Int,
        val countLow: Int,
        val highLabels: List<String>,
        val mildLabels: List<String>,
        val lowLabels: List<String>,
    )

    data class Call1Result(
        val clinicalAssessment: String,
        val adjustments: List<Triple<String, String, String>>, // label, toSeverity, reason
        val dataWarnings: List<AiSetupService.AiDataWarning>,
    )

    data class Call2Result(
        val gauge: AiSetupService.AiGaugeThresholds,
        val decays: List<AiSetupService.AiDecayWeights>,
        val calibrationNotes: String,
        val summary: String,
    )

    // ═════════════════════════════════════════════════════════════════════
    // Main entry point
    // ═════════════════════════════════════════════════════════════════════

    suspend fun calibrate(
        context: Context,
        mapping: DeterministicMapper.MappingResult,
        items: AiSetupService.AvailableItems?,
        answers: DeterministicMapper.QuestionnaireAnswers? = null,
        dataContext: AiSetupService.DataContext? = null,
        onProgress: ((String) -> Unit)? = null,
    ): Result<AiSetupService.AiConfig> = withContext(Dispatchers.IO) {
        try {
            val appCtx = context.applicationContext
            val accessToken = SessionStore.getValidAccessToken(appCtx)
                ?: return@withContext Result.failure(Exception("Not authenticated"))

            // ── CALL 1: The Neurologist ──
            onProgress?.invoke("Analysing your migraine profile…")
            val call1Result = executeCall1(accessToken, mapping, items, answers, dataContext)

            // ── Merge Call 1 adjustments with Layer 1 ──
            onProgress?.invoke("Building your personalised configuration…")
            val mergedTriggers = applyAdjustments(mapping.triggers, call1Result.adjustments)

            // ── Classify triggers for Call 2 ──
            val classified = classifyTriggers(mergedTriggers, items?.autoTriggerLabels ?: emptySet())
            Log.d(TAG, "classifyTriggers: autoTriggerLabels size=${items?.autoTriggerLabels?.size}, mergedTriggers size=${mergedTriggers.size}")
            Log.d(TAG, "classifyTriggers result: auto=${classified.autoHigh.size + classified.autoMild.size + classified.autoLow.size} (H=${classified.autoHigh.size},M=${classified.autoMild.size},L=${classified.autoLow.size}), manual=${classified.manualHigh.size + classified.manualMild.size + classified.manualLow.size}")
            val prodromeStats = calculateProdromeStats(mapping.prodromes)

            // ── CALL 2: The Statistician ──
            onProgress?.invoke("Calibrating your risk gauge…")
            val call2Result = executeCall2(
                accessToken, mapping.profileContext,
                call1Result.clinicalAssessment,
                classified, prodromeStats, dataContext
            )

            // ── CALL 3: Companion Matcher ──
            onProgress?.invoke("Matching AI companions…")
            val recommendedCompanions = try {
                executeCall3(accessToken, call1Result.clinicalAssessment, mapping.profileContext, classified, prodromeStats, mapping.favorites)
            } catch (e: Exception) {
                Log.w(TAG, "Call 3 (companions) failed, will show no pre-selection: ${e.message}")
                emptyList()
            }

            // ── Merge everything into final AiConfig ──
            val config = buildFinalConfig(
                mapping, mergedTriggers, call1Result, call2Result,
                recommendedCompanions = recommendedCompanions,
                autoTriggerLabels = items?.autoTriggerLabels ?: emptySet(),
                autoProdromeLabels = items?.autoProdromeLabels ?: emptySet(),
            )

            Log.d(TAG, "Three-call calibration complete: ${config.triggers.size} triggers, " +
                    "${config.prodromes.size} prodromes, gauge=${config.gaugeThresholds.low}/${config.gaugeThresholds.mild}/${config.gaugeThresholds.high}, " +
                    "companions=${config.recommendedCompanions}")
            Result.success(config)

        } catch (e: Exception) {
            Log.e(TAG, "Calibration failed", e)
            // Fallback: deterministic mapping alone with safe defaults
            try {
                val fallback = buildFallbackConfig(
                    mapping,
                    autoTriggerLabels = items?.autoTriggerLabels ?: emptySet(),
                    autoProdromeLabels = items?.autoProdromeLabels ?: emptySet(),
                )
                Result.success(fallback)
            } catch (e2: Exception) {
                Result.failure(e)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // CALL 1 — The Neurologist
    // System prompt lives server-side under context_type "calibration_call1".
    // ═════════════════════════════════════════════════════════════════════

    private fun buildCall1UserMessage(
        mapping: DeterministicMapper.MappingResult,
        items: AiSetupService.AvailableItems?,
        answers: DeterministicMapper.QuestionnaireAnswers?,
        dataContext: AiSetupService.DataContext?,
    ): String = buildString {
        val p = mapping.profileContext

        // ── Narrative profile ──
        appendLine("=== PATIENT PROFILE ===")
        appendLine()
        appendLine("This is a ${p.gender ?: "unknown gender"}, ${p.ageRange ?: "unknown age"} patient who has been experiencing migraines for ${p.experience ?: "an unknown period"}.")
        appendLine("They currently get migraines ${p.frequency ?: "at unknown frequency"} lasting ${p.duration ?: "unknown duration"}, and report the pattern is ${p.trajectory ?: "unknown"}.")
        appendLine("They ${p.warningSignsBefore ?: "have unknown warning sign status"} get warning signs before an attack.")
        appendLine("After a trigger exposure, migraines typically come ${p.triggerDelay ?: "at unknown delay"}.")
        appendLine("They work ${p.dailyRoutine ?: "an unknown schedule"} and ${p.seasonalPattern ?: "have no known seasonal pattern"}.")
        appendLine()

        // ── Lifestyle detail from answers ──
        if (answers != null) {
            appendLine("LIFESTYLE DETAIL:")
            answers.sleepHours?.let { appendLine("- Sleep: $it per night") }
            answers.sleepQuality?.let { appendLine("- Sleep quality: $it") }
            if (answers.sleepIssues.isNotEmpty()) appendLine("- Sleep issues: ${answers.sleepIssues.joinToString(", ")}")
            answers.stressLevel?.let { appendLine("- Stress level: $it") }
            answers.screenTimeDaily?.let { appendLine("- Screen time: $it daily") }
            answers.caffeineIntake?.let { appendLine("- Caffeine: $it") }
            answers.caffeineDirection?.let { appendLine("- Caffeine pattern: $it") }
            answers.alcoholFrequency?.let { appendLine("- Alcohol: $it") }
            if (answers.specificDrinks.isNotEmpty()) appendLine("- Problematic drinks: ${answers.specificDrinks.joinToString(", ")}")
            answers.waterIntake?.let { appendLine("- Water intake: $it") }
            answers.exerciseFrequency?.let { appendLine("- Exercise: $it") }
            if (answers.exercisePattern.isNotEmpty()) appendLine("- Exercise pattern: ${answers.exercisePattern.joinToString(", ")}")
            answers.tracksCycle?.let { appendLine("- Tracks menstrual cycle: $it") }
            answers.usesContraception?.let { appendLine("- Contraception: $it") }
            answers.contraceptionEffect?.let { appendLine("- Contraception effect on migraines: $it") }
            answers.glutenSensitivity?.let { appendLine("- Gluten sensitivity: $it") }
            answers.tracksNutrition?.let { appendLine("- Tracks nutrition: $it") }
            answers.freeText?.let {
                if (it.isNotBlank()) {
                    appendLine()
                    appendLine("PATIENT'S OWN NOTES:")
                    appendLine(it)
                }
            }
            appendLine()
        }

        // ── Locked trigger ratings (Layer 1) ──
        val high = mapping.triggers.filter { it.value.severity == "HIGH" }
        val mild = mapping.triggers.filter { it.value.severity == "MILD" }
        val low = mapping.triggers.filter { it.value.severity == "LOW" }
        val none = mapping.triggers.filter { it.value.severity == "NONE" }

        appendLine("=== LOCKED TRIGGER RATINGS (from patient's direct answers — NEVER lower these) ===")
        appendLine("HIGH (${high.size}): ${high.keys.joinToString(", ").ifEmpty { "none" }}")
        appendLine("MILD (${mild.size}): ${mild.keys.joinToString(", ").ifEmpty { "none" }}")
        appendLine("LOW (${low.size}): ${low.keys.joinToString(", ").ifEmpty { "none" }}")
        appendLine("NONE (${none.size}): ${none.keys.joinToString(", ").ifEmpty { "none" }}")
        appendLine()

        // ── Locked prodrome ratings ──
        val prHigh = mapping.prodromes.filter { it.value.severity == "HIGH" }
        val prMild = mapping.prodromes.filter { it.value.severity == "MILD" }
        val prLow = mapping.prodromes.filter { it.value.severity == "LOW" }

        appendLine("=== LOCKED PRODROME RATINGS ===")
        appendLine("HIGH: ${prHigh.keys.joinToString(", ").ifEmpty { "none" }}")
        appendLine("MILD: ${prMild.keys.joinToString(", ").ifEmpty { "none" }}")
        appendLine("LOW: ${prLow.keys.joinToString(", ").ifEmpty { "none" }}")
        appendLine()

        // ── Connected data ──
        if (dataContext != null) {
            appendLine("=== CONNECTED DATA SOURCES ===")
            appendLine("WHOOP: ${if (dataContext.whoopConnected) "connected" else "not connected"}")
            appendLine("Oura: ${if (dataContext.ouraConnected) "connected" else "not connected"}")
            appendLine("Polar: ${if (dataContext.polarConnected) "connected" else "not connected"}")
            appendLine("Garmin: ${if (dataContext.garminConnected) "connected" else "not connected"}")
            appendLine("Health Connect: ${if (dataContext.healthConnectConnected) "connected" else "not connected"}")
            appendLine("Enabled metrics: ${dataContext.enabledMetrics.filter { it.value }.keys.joinToString(", ").ifEmpty { "none" }}")
            appendLine("Disabled metrics: ${dataContext.enabledMetrics.filter { !it.value }.keys.joinToString(", ").ifEmpty { "none" }}")
            if (dataContext.metricSources.isNotEmpty()) {
                appendLine("Metric sources: ${dataContext.metricSources.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
            }
            if (dataContext.ouraConnected) {
                appendLine("NOTE: Oura stress_index_daily is computed as stress_high/(stress_high+recovery_high)*100, a 0-100 index.")
            }
            if (dataContext.polarConnected) {
                appendLine("NOTE: Polar sleep_efficiency is derived from continuity (1-5 scale), not a direct percentage.")
                appendLine("NOTE: Polar sleep_disturbances_daily is total interruption duration in minutes, not a wake-up count.")
                appendLine("NOTE: Polar does not provide a stress metric — stress_index_daily uses computed HRV/RHR z-scores.")
                appendLine("NOTE: Polar skin_temp_daily and spo2_daily require Elixir™-compatible devices and may not always be available.")
            }
            if (dataContext.garminConnected) {
                appendLine("NOTE: Garmin provides direct stress scores (1–100) via HRV algorithms — no proxy computation needed.")
                appendLine("NOTE: Garmin HRV is from overnight sleep window; reports 7-day average and last-night values.")
                appendLine("NOTE: Garmin data is only retained for 7 days — backfill is limited to the past week.")
                appendLine("NOTE: Garmin access tokens expire every 24h; refresh is handled server-side automatically.")
                appendLine("NOTE: Garmin skin_temp_daily and spo2_daily require compatible devices (Venu 3, Fenix 8, etc.).")
            }
            appendLine()
        }

        // ── Available items (for adjustments) ──
        if (items != null) {
            appendLine("=== AVAILABLE TRIGGER LABELS (use EXACT labels for adjustments) ===")
            items.triggers.groupBy { it.category ?: "Other" }.forEach { (cat, list) ->
                appendLine("  [$cat] ${list.joinToString(", ") { it.label }}")
            }
            appendLine()
            appendLine("=== AVAILABLE PRODROME LABELS ===")
            items.prodromes.groupBy { it.category ?: "Other" }.forEach { (cat, list) ->
                appendLine("  [$cat] ${list.joinToString(", ") { it.label }}")
            }
        }
    }

    private suspend fun executeCall1(
        accessToken: String,
        mapping: DeterministicMapper.MappingResult,
        items: AiSetupService.AvailableItems?,
        answers: DeterministicMapper.QuestionnaireAnswers?,
        dataContext: AiSetupService.DataContext?,
    ): Call1Result {
        val userMessage = buildCall1UserMessage(mapping, items, answers, dataContext)
        Log.d(TAG, "Call 1 user message: ${userMessage.length} chars")

        val responseJson = callEdgeFunction(accessToken, "calibration_call1", userMessage)
        return parseCall1Response(responseJson)
    }

    private fun parseCall1Response(json: String): Call1Result {
        Log.d(TAG, "Call 1 raw response: ${json.take(500)}")
        return try {
            val obj = JSONObject(json)

            val assessment = obj.optString("clinical_assessment", "")
            Log.d(TAG, "Call 1 clinical_assessment length: ${assessment.length}, blank: ${assessment.isBlank()}")

            val adjustments = mutableListOf<Triple<String, String, String>>()
            val adjArr = obj.optJSONArray("adjustments")
            if (adjArr != null) {
                for (i in 0 until adjArr.length()) {
                    val a = adjArr.getJSONObject(i)
                    adjustments.add(Triple(
                        a.optString("label", ""),
                        a.optString("to", a.optString("severity", "")),
                        a.optString("reasoning", ""),
                    ))
                }
            }

            val warnings = mutableListOf<AiSetupService.AiDataWarning>()
            val warnArr = obj.optJSONArray("data_warnings")
            if (warnArr != null) {
                for (i in 0 until warnArr.length()) {
                    val w = warnArr.getJSONObject(i)
                    warnings.add(AiSetupService.AiDataWarning(
                        type = w.optString("type", "suggestion"),
                        message = w.optString("message", ""),
                        metric = w.optString("metric", null),
                        severity = w.optString("severity", "medium"),
                    ))
                }
            }

            Call1Result(assessment, adjustments, warnings)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Call 1 response", e)
            Call1Result("", emptyList(), emptyList())
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Merge Call 1 adjustments with Layer 1 (only elevations)
    // ═════════════════════════════════════════════════════════════════════

    private val SEVERITY_RANK = mapOf("NONE" to 0, "LOW" to 1, "MILD" to 2, "HIGH" to 3)

    private fun applyAdjustments(
        triggers: Map<String, DeterministicMapper.TriggerSetting>,
        adjustments: List<Triple<String, String, String>>,
    ): Map<String, DeterministicMapper.TriggerSetting> {
        val merged = triggers.toMutableMap()
        for ((label, toSev, _) in adjustments) {
            val existing = merged[label]
            if (existing != null) {
                val currentRank = SEVERITY_RANK[existing.severity] ?: 0
                val newRank = SEVERITY_RANK[toSev] ?: 0
                // Only elevate, never lower
                if (newRank > currentRank) {
                    merged[label] = existing.copy(severity = toSev)
                    Log.d(TAG, "Elevated '$label': ${existing.severity} → $toSev")
                }
            } else if (toSev != "NONE") {
                // AI activated a trigger that was NONE/missing — add it
                merged[label] = DeterministicMapper.TriggerSetting(
                    label = label, severity = toSev, favorite = false
                )
                Log.d(TAG, "Activated '$label': NONE → $toSev")
            }
        }
        return merged
    }

    // ═════════════════════════════════════════════════════════════════════
    // Prodrome stats for Call 2
    // ═════════════════════════════════════════════════════════════════════

    private fun calculateProdromeStats(
        prodromes: Map<String, DeterministicMapper.ProdromeSetting>
    ): TriggerStats {
        val active = prodromes.filter { it.value.severity != "NONE" }
        val high = active.filter { it.value.severity == "HIGH" }
        val mild = active.filter { it.value.severity == "MILD" }
        val low = active.filter { it.value.severity == "LOW" }
        return TriggerStats(
            totalActive = active.size,
            countHigh = high.size,
            countMild = mild.size,
            countLow = low.size,
            highLabels = high.keys.toList(),
            mildLabels = mild.keys.toList(),
            lowLabels = low.keys.toList(),
        )
    }

    // ═════════════════════════════════════════════════════════════════════
    // Classify triggers: auto-detected vs manual (from Supabase pool data)
    // ═════════════════════════════════════════════════════════════════════

    data class ClassifiedTriggers(
        val autoHigh: List<String>,
        val autoMild: List<String>,
        val autoLow: List<String>,
        val manualHigh: List<String>,
        val manualMild: List<String>,
        val manualLow: List<String>,
        val totalActive: Int,
    )

    /**
     * Classifies active triggers into auto-detected vs manual.
     * Auto-detected = has a numeric threshold (created by trigHigh/trigLow in DeterministicMapper).
     * Manual = no threshold (created by trigManual).
     * This is the source of truth — no Supabase lookup needed.
     */
    private fun classifyTriggers(
        triggers: Map<String, DeterministicMapper.TriggerSetting>,
        autoTriggerLabels: Set<String>,
    ): ClassifiedTriggers {
        val autoHigh = mutableListOf<String>()
        val autoMild = mutableListOf<String>()
        val autoLow = mutableListOf<String>()
        val manualHigh = mutableListOf<String>()
        val manualMild = mutableListOf<String>()
        val manualLow = mutableListOf<String>()

        for ((_, trigger) in triggers) {
            if (trigger.severity == "NONE") continue
            val isAuto = trigger.label.lowercase() in autoTriggerLabels
            val list = when {
                isAuto && trigger.severity == "HIGH" -> autoHigh
                isAuto && trigger.severity == "MILD" -> autoMild
                isAuto && trigger.severity == "LOW" -> autoLow
                !isAuto && trigger.severity == "HIGH" -> manualHigh
                !isAuto && trigger.severity == "MILD" -> manualMild
                !isAuto && trigger.severity == "LOW" -> manualLow
                else -> manualLow
            }
            list.add(trigger.label)
        }

        return ClassifiedTriggers(
            autoHigh = autoHigh, autoMild = autoMild, autoLow = autoLow,
            manualHigh = manualHigh, manualMild = manualMild, manualLow = manualLow,
            totalActive = autoHigh.size + autoMild.size + autoLow.size +
                    manualHigh.size + manualMild.size + manualLow.size,
        )
    }

    // ═════════════════════════════════════════════════════════════════════
    // CALL 2 — The Statistician
    // System prompt lives server-side under context_type "calibration_call2".
    // Companion roster for call3 is fetched server-side.
    // ═════════════════════════════════════════════════════════════════════

    private fun buildCall2UserMessage(
        profile: DeterministicMapper.ProfileContext,
        clinicalAssessment: String,
        classified: ClassifiedTriggers,
        prodromeStats: TriggerStats,
        dataContext: AiSetupService.DataContext?,
    ): String = buildString {
        appendLine("=== PATIENT SUMMARY ===")
        appendLine("${profile.gender ?: "Unknown"}, ${profile.ageRange ?: "unknown age"}")
        appendLine("Migraines: ${profile.frequency ?: "unknown frequency"}, lasting ${profile.duration ?: "unknown"}")
        appendLine("Experience: ${profile.experience ?: "unknown"}, trajectory: ${profile.trajectory ?: "unknown"}")
        appendLine("Routine: ${profile.dailyRoutine ?: "unknown"}, seasonal: ${profile.seasonalPattern ?: "none"}")
        appendLine("Trigger delay: ${profile.triggerDelay ?: "unknown"}")
        appendLine()

        appendLine("=== CLINICAL ASSESSMENT (from neurologist) ===")
        appendLine(clinicalAssessment.ifBlank { "No clinical assessment available." })
        appendLine()

        appendLine("=== CONNECTED DATA SOURCES ===")
        if (dataContext != null) {
            appendLine("WHOOP: ${if (dataContext.whoopConnected) "YES (provides sleep, HRV, recovery, strain)" else "NO"}")
            appendLine("Oura: ${if (dataContext.ouraConnected) "YES (provides sleep, HRV, readiness, skin temp deviation, steps, stress)" else "NO"}")
            appendLine("Health Connect: ${if (dataContext.healthConnectConnected) "YES (provides steps, sleep, heart rate)" else "NO"}")
            val enabled = dataContext.enabledMetrics.filter { it.value }.keys
            val disabled = dataContext.enabledMetrics.filter { !it.value }.keys
            if (enabled.isNotEmpty()) appendLine("Enabled metrics: ${enabled.joinToString(", ")}")
            if (disabled.isNotEmpty()) appendLine("Disabled metrics: ${disabled.joinToString(", ")}")
            if (dataContext.ouraConnected) {
                appendLine("NOTE: Oura stress_index_daily is stress_high/(stress_high+recovery_high)*100, scale 0-100.")
            }
        } else {
            appendLine("No data source information available.")
        }
        appendLine()

        appendLine("=== AUTO-DETECTED TRIGGERS (fire automatically from data streams) ===")
        appendLine("These triggers fire when a connected metric crosses its threshold.")
        appendLine("Not all will fire every day — only when the value is abnormal for this user.")
        appendLine()
        appendLine("AUTO HIGH (${classified.autoHigh.size}): ${classified.autoHigh.joinToString(", ").ifEmpty { "none" }}")
        appendLine("AUTO MILD (${classified.autoMild.size}): ${classified.autoMild.joinToString(", ").ifEmpty { "none" }}")
        appendLine("AUTO LOW (${classified.autoLow.size}): ${classified.autoLow.joinToString(", ").ifEmpty { "none" }}")
        appendLine()

        appendLine("=== MANUAL TRIGGERS (user logs these when they occur) ===")
        appendLine("These only fire when the user actively logs them.")
        appendLine()
        appendLine("MANUAL HIGH (${classified.manualHigh.size}): ${classified.manualHigh.joinToString(", ").ifEmpty { "none" }}")
        appendLine("MANUAL MILD (${classified.manualMild.size}): ${classified.manualMild.joinToString(", ").ifEmpty { "none" }}")
        appendLine("MANUAL LOW (${classified.manualLow.size}): ${classified.manualLow.joinToString(", ").ifEmpty { "none" }}")
        appendLine()

        appendLine("=== PRODROMES ===")
        appendLine("HIGH (${prodromeStats.countHigh}): ${prodromeStats.highLabels.joinToString(", ").ifEmpty { "none" }}")
        appendLine("MILD (${prodromeStats.countMild}): ${prodromeStats.mildLabels.joinToString(", ").ifEmpty { "none" }}")
        appendLine("LOW (${prodromeStats.countLow}): ${prodromeStats.lowLabels.joinToString(", ").ifEmpty { "none" }}")
        appendLine()

        appendLine("=== TOTAL ACTIVE: ${classified.totalActive} triggers + ${prodromeStats.totalActive} prodromes ===")
        appendLine()
        appendLine("Remember: this patient gets migraines ${profile.frequency ?: "at unknown frequency"}. Most days should be GREEN. The gauge should only climb to warning levels when a genuine trigger buildup is happening.")
    }

    private suspend fun executeCall2(
        accessToken: String,
        profile: DeterministicMapper.ProfileContext,
        clinicalAssessment: String,
        classified: ClassifiedTriggers,
        prodromeStats: TriggerStats,
        dataContext: AiSetupService.DataContext?,
    ): Call2Result {
        val userMessage = buildCall2UserMessage(
            profile, clinicalAssessment, classified, prodromeStats, dataContext
        )
        Log.d(TAG, "Call 2 user message: ${userMessage.length} chars")

        val responseJson = callEdgeFunction(accessToken, "calibration_call2", userMessage)
        return parseCall2Response(responseJson)
    }

    private fun parseCall2Response(json: String): Call2Result {
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

            Call2Result(
                gauge = gauge,
                decays = decays,
                calibrationNotes = obj.optString("calibration_notes", ""),
                summary = obj.optString("summary", "Your migraine setup has been configured."),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Call 2 response", e)
            Call2Result(
                gauge = AiSetupService.AiGaugeThresholds(),
                decays = emptyList(),
                calibrationNotes = "",
                summary = "Your migraine setup has been configured.",
            )
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // CALL 3 — Companion Matcher
    // ═════════════════════════════════════════════════════════════════════

    private suspend fun executeCall3(
        accessToken: String,
        clinicalAssessment: String,
        profile: DeterministicMapper.ProfileContext,
        classified: ClassifiedTriggers,
        prodromeStats: TriggerStats,
        favorites: DeterministicMapper.Favorites,
    ): List<String> {
        val userMessage = buildString {
            appendLine("=== CLINICAL ASSESSMENT ===")
            appendLine(clinicalAssessment.ifBlank { "Not available" })
            appendLine()
            appendLine("=== TRIGGERS ===")
            val autoAll = classified.autoHigh + classified.autoMild + classified.autoLow
            val manualAll = classified.manualHigh + classified.manualMild + classified.manualLow
            if (autoAll.isNotEmpty()) appendLine("Auto-detected: ${autoAll.joinToString(", ")}")
            if (manualAll.isNotEmpty()) appendLine("Manual: ${manualAll.joinToString(", ")}")
            if (autoAll.isEmpty() && manualAll.isEmpty()) appendLine("None")
            appendLine()
            appendLine("=== PRODROMES ===")
            val allProdromes = prodromeStats.highLabels + prodromeStats.mildLabels + prodromeStats.lowLabels
            appendLine(if (allProdromes.isNotEmpty()) allProdromes.joinToString(", ") else "None")
            appendLine()
            appendLine("=== MEDICINES ===")
            appendLine(if (favorites.medicines.isNotEmpty()) favorites.medicines.joinToString(", ") else "None")
            appendLine()
            appendLine("=== RELIEFS ===")
            appendLine(if (favorites.reliefs.isNotEmpty()) favorites.reliefs.joinToString(", ") else "None")
            appendLine()
            appendLine("=== SYMPTOMS ===")
            appendLine(if (favorites.symptoms.isNotEmpty()) favorites.symptoms.joinToString(", ") else "None")
        }

        val responseJson = callEdgeFunction(accessToken, "calibration_call3", userMessage)
        return parseCall3Response(responseJson)
    }

    private fun parseCall3Response(json: String): List<String> {
        return try {
            val arr = org.json.JSONArray(json)
            val validSlugs = setOf("luna", "nora", "lena", "kai", "maya", "sam", "priya", "jake")
            val result = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val slug = arr.optString(i, "").lowercase().trim()
                if (slug in validSlugs) result.add(slug)
            }
            Log.d(TAG, "Call 3 recommended companions: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Call 3 response: $json", e)
            emptyList()
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Shared edge function caller
    // ═════════════════════════════════════════════════════════════════════

    private suspend fun callEdgeFunction(
        accessToken: String,
        contextType: String,
        userMessage: String,
    ): String {
        val requestBody = JSONObject().apply {
            put("context_type", contextType)
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
            Log.d(TAG, "Edge function response status: ${response.status}")

            if (!response.status.isSuccess()) {
                Log.e(TAG, "Edge function error: $responseText")
                throw Exception("AI call failed (${response.status}): $responseText")
            }

            return responseText
                .replace("```json", "").replace("```", "")
                .trim()
        } finally {
            client.close()
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Build final AiConfig
    // ═════════════════════════════════════════════════════════════════════

    private fun buildFinalConfig(
        mapping: DeterministicMapper.MappingResult,
        mergedTriggers: Map<String, DeterministicMapper.TriggerSetting>,
        call1: Call1Result,
        call2: Call2Result,
        recommendedCompanions: List<String> = emptyList(),
        autoTriggerLabels: Set<String> = emptySet(),
        autoProdromeLabels: Set<String> = emptySet(),
    ): AiSetupService.AiConfig {

        // Build adjustment reasoning lookup
        val adjustmentReasons = call1.adjustments.associate { (label, _, reason) ->
            label.lowercase() to reason
        }

        val triggerRecs = mergedTriggers.values.filter { it.severity != "NONE" }.map { t ->
            val isAuto = t.label.lowercase() in autoTriggerLabels
            AiSetupService.AiTriggerRec(
                label = t.label,
                severity = t.severity,
                decayDays = when (t.severity) { "HIGH" -> 3; "MILD" -> 2; else -> 1 },
                favorite = if (isAuto) false else (t.favorite || t.severity != "NONE"),
                reasoning = adjustmentReasons[t.label.lowercase()] ?: "",
                defaultThreshold = t.threshold,
            )
        }

        val prodromeRecs = mapping.prodromes.values.filter { it.severity != "NONE" }.map { p ->
            val isAuto = p.label.lowercase() in autoProdromeLabels
            AiSetupService.AiProdromeRec(
                label = p.label,
                severity = p.severity,
                favorite = if (isAuto) false else (p.favorite || p.severity != "NONE"),
                reasoning = "",
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
            gaugeThresholds = call2.gauge,
            decayWeights = call2.decays,
            dataWarnings = call1.dataWarnings,
            summary = call2.summary,
            clinicalAssessment = call1.clinicalAssessment,
            calibrationNotes = call2.calibrationNotes,
            recommendedCompanions = recommendedCompanions,
        )
    }

    private fun buildFallbackConfig(
        mapping: DeterministicMapper.MappingResult,
        autoTriggerLabels: Set<String> = emptySet(),
        autoProdromeLabels: Set<String> = emptySet(),
    ): AiSetupService.AiConfig {
        val triggerRecs = mapping.triggers.values.filter { it.severity != "NONE" }.map { t ->
            val isAuto = t.label.lowercase() in autoTriggerLabels
            AiSetupService.AiTriggerRec(
                label = t.label, severity = t.severity,
                decayDays = when (t.severity) { "HIGH" -> 3; "MILD" -> 2; else -> 1 },
                favorite = if (isAuto) false else (t.favorite || t.severity != "NONE"),
                reasoning = "",
                defaultThreshold = t.threshold,
            )
        }

        val prodromeRecs = mapping.prodromes.values.filter { it.severity != "NONE" }.map { p ->
            val isAuto = p.label.lowercase() in autoProdromeLabels
            AiSetupService.AiProdromeRec(
                label = p.label, severity = p.severity,
                favorite = if (isAuto) false else (p.favorite || p.severity != "NONE"),
                reasoning = "",
                defaultThreshold = p.threshold,
            )
        }

        val toFav = { labels: List<String> ->
            labels.map { label -> AiSetupService.AiFavoriteRec(label = label, favorite = true) }
        }

        // Smart fallback gauge based on trigger count
        val activeCount = mapping.triggers.count { it.value.severity != "NONE" }
        val highCount = mapping.triggers.count { it.value.severity == "HIGH" }
        val fallbackGauge = when {
            activeCount > 30 -> AiSetupService.AiGaugeThresholds(low = 12, mild = 25, high = 40, reasoning = "Fallback: high trigger count ($activeCount active)")
            activeCount > 15 -> AiSetupService.AiGaugeThresholds(low = 8, mild = 18, high = 30, reasoning = "Fallback: moderate trigger count ($activeCount active)")
            else -> AiSetupService.AiGaugeThresholds(low = 5, mild = 12, high = 20, reasoning = "Fallback: low trigger count ($activeCount active)")
        }

        return AiSetupService.AiConfig(
            triggers = triggerRecs,
            prodromes = prodromeRecs,
            symptoms = toFav(mapping.favorites.symptoms),
            medicines = toFav(mapping.favorites.medicines),
            reliefs = toFav(mapping.favorites.reliefs),
            activities = toFav(mapping.favorites.activities),
            missedActivities = toFav(mapping.favorites.missedActivities),
            gaugeThresholds = fallbackGauge,
            decayWeights = listOf(
                AiSetupService.AiDecayWeights("HIGH", 10.0, 5.0, 2.5, 1.0, 0.0, 0.0, 0.0, "Fallback defaults"),
                AiSetupService.AiDecayWeights("MILD", 6.0, 3.0, 1.5, 0.5, 0.0, 0.0, 0.0, "Fallback defaults"),
                AiSetupService.AiDecayWeights("LOW", 3.0, 1.5, 0.0, 0.0, 0.0, 0.0, 0.0, "Fallback defaults"),
            ),
            dataWarnings = emptyList(),
            summary = "Your migraine triggers and warning signs have been configured based on your answers.",
            clinicalAssessment = "",
            calibrationNotes = "",
        )
    }
}

