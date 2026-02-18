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

            // ── Merge everything into final AiConfig ──
            val config = buildFinalConfig(
                mapping, mergedTriggers, call1Result, call2Result,
                autoTriggerLabels = items?.autoTriggerLabels ?: emptySet(),
                autoProdromeLabels = items?.autoProdromeLabels ?: emptySet(),
            )

            Log.d(TAG, "Two-call calibration complete: ${config.triggers.size} triggers, " +
                    "${config.prodromes.size} prodromes, gauge=${config.gaugeThresholds.low}/${config.gaugeThresholds.mild}/${config.gaugeThresholds.high}")
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
    // ═════════════════════════════════════════════════════════════════════

    private val CALL1_SYSTEM_PROMPT = """
You are a neurologist and migraine specialist assessing a new patient's profile to configure a migraine prediction app.

You will receive:
1. The patient's questionnaire answers as a narrative profile
2. LOCKED trigger/prodrome ratings from their direct answers (these are FLOOR values — NEVER lower them)
3. Their connected data sources and enabled metrics
4. Available trigger and prodrome labels you may reference

YOUR TASK — Two steps:

STEP 1 — CLINICAL ASSESSMENT:
Analyse this patient as a neurologist would. Write 2-3 short paragraphs covering:
- Key patterns you see in their profile
- Interactions between lifestyle factors (e.g. poor sleep + high stress + caffeine)
- Triggers or risks they may be underestimating based on the overall picture
- What their trajectory and experience tell you about their migraine evolution
Write this for the PATIENT to read — warm, clear, insightful. No medical jargon. Address them as "you/your". Keep it concise but meaningful.

STEP 2 — SEVERITY ADJUSTMENTS:
Based on your clinical assessment, identify triggers and prodromes that should be ELEVATED or ACTIVATED.

RULES:
- NEVER lower a LOCKED rating — these are the user's own reported experience
- You may ELEVATE locked ratings (LOW → MILD, MILD → HIGH) if clinical evidence supports it
- You may ACTIVATE triggers currently rated NONE if the profile strongly suggests them — but be conservative
- Use ONLY exact label strings from the AVAILABLE ITEMS lists
- For each adjustment, provide clear reasoning tied to your clinical assessment
- Limit adjustments to genuinely meaningful ones (typically 3-8, not dozens)

Also generate data_warnings where a HIGH or MILD trigger lacks the relevant data source.

Respond with ONLY valid JSON (no markdown fences):
{
  "clinical_assessment": "Your 2-3 paragraph assessment for the patient...",
  "adjustments": [
    {"label": "exact label from pool", "from": "CURRENT_SEVERITY", "to": "NEW_SEVERITY", "reasoning": "brief clinical justification"}
  ],
  "data_warnings": [
    {"type": "missing_data|missing_connection|suggestion", "message": "user-facing message", "metric": "metric_name|null", "severity": "high|medium|low"}
  ]
}
""".trimIndent()

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
            appendLine("Health Connect: ${if (dataContext.healthConnectConnected) "connected" else "not connected"}")
            appendLine("Enabled metrics: ${dataContext.enabledMetrics.filter { it.value }.keys.joinToString(", ").ifEmpty { "none" }}")
            appendLine("Disabled metrics: ${dataContext.enabledMetrics.filter { !it.value }.keys.joinToString(", ").ifEmpty { "none" }}")
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

        val responseJson = callEdgeFunction(accessToken, CALL1_SYSTEM_PROMPT, userMessage)
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
    // ═════════════════════════════════════════════════════════════════════

    private val CALL2_SYSTEM_PROMPT = """
You are a biostatistician calibrating a migraine risk gauge. You need to understand exactly how this app's scoring engine works, then set thresholds that make sense for this specific patient.

=== HOW THE SCORING ENGINE WORKS ===

Every day, the app calculates a RISK SCORE by looking at all trigger events from the last 7 days.

For each event, the score contribution = decay_weight[severity][days_ago].
Default decay weights:
  HIGH:  day0=10, day1=5, day2=2.5, day3=1, day4=0, day5=0, day6=0
  MILD:  day0=6,  day1=3, day2=1.5, day3=0.5, day4=0, day5=0, day6=0
  LOW:   day0=3,  day1=1.5, day2=0, day3=0, day4=0, day5=0, day6=0

The daily score = SUM of all event contributions.

Example: If today the user has 1 HIGH trigger (10pts) + 1 MILD trigger (6pts) + yesterday had 1 HIGH (5pts decay), today's score = 21.

The gauge zones are:
  score >= HIGH threshold → RED (genuine warning)
  score >= MILD threshold → YELLOW (building risk)
  score >= LOW threshold  → AMBER (worth noticing)
  score < LOW threshold   → GREEN (normal)

=== TWO TYPES OF TRIGGERS ===

AUTO-DETECTED triggers fire automatically when connected data (wearables, weather, nutrition tracking) crosses a threshold. These can fire EVERY DAY without the user doing anything. Examples: sleep duration, weather pressure, HRV, screen time.

MANUAL triggers are logged by the user when they experience them. These fire occasionally. Examples: stress, alcohol, skipped meals, strong smells.

This distinction is CRITICAL for calibration:
- A user with many auto-detected triggers will have a higher BASELINE score every day
- The thresholds must sit ABOVE that baseline, otherwise the gauge is permanently yellow/red
- Manual triggers stacking ON TOP of the auto baseline is what should push into warning zones

=== YOUR TASK ===

Given this patient's clinical profile, their trigger list (auto vs manual, with severities), and their migraine frequency:

1. THINK about what a normal day looks like for this patient score-wise:
   - How many auto triggers will realistically fire on a typical day? (Not all of them — only when metrics cross thresholds)
   - What's the baseline score from auto-detect alone?

2. THINK about what a pre-migraine buildup looks like:
   - They get migraines at [frequency]. So most days should be GREEN.
   - A few days per month should climb into yellow/red as triggers stack up before an attack.
   - Manual triggers (stress, alcohol, etc.) piling onto a bad auto day is what creates real risk.

3. SET THRESHOLDS so that:
   - Normal days (just routine auto-detect) → GREEN
   - A few extra triggers stacking → AMBER (LOW threshold)
   - Genuine buildup with multiple triggers converging → YELLOW (MILD threshold)  
   - Serious warning with many triggers + decay stacking from previous days → RED (HIGH threshold)
   - Someone who gets migraines "1-3 per month" should NOT be in red every day. Maybe 2-4 days/month.

4. SET DECAY CURVES:
   - Frequent migraines: steeper curves (triggers hit hard, fade fast)
   - Infrequent migraines: flatter curves (cumulative buildup matters more)

5. WRITE calibration_notes for the patient (2-3 paragraphs, warm, no jargon, address as "you/your"):
   - What their trigger setup looks like (auto vs manual breakdown)
   - How the gauge will behave for them day-to-day
   - What pushing into yellow/red actually means for them

6. WRITE a combined summary (2-3 sentences) covering the clinical insight + gauge setup.

Respond with ONLY valid JSON (no markdown fences):
{
  "gauge_thresholds": {"low": N, "mild": N, "high": N, "reasoning": "brief technical reasoning"},
  "decay_weights": [
    {"severity": "HIGH", "day0": N, "day1": N, "day2": N, "day3": N, "day4": N, "day5": N, "day6": N, "reasoning": "..."},
    {"severity": "MILD", "day0": N, "day1": N, "day2": N, "day3": N, "day4": N, "day5": N, "day6": N, "reasoning": "..."},
    {"severity": "LOW", "day0": N, "day1": N, "day2": N, "day3": N, "day4": N, "day5": N, "day6": N, "reasoning": "..."}
  ],
  "calibration_notes": "Your 2-3 paragraph explanation for the patient...",
  "summary": "2-3 sentence combined summary"
}
""".trimIndent()

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
            appendLine("Health Connect: ${if (dataContext.healthConnectConnected) "YES (provides steps, sleep, heart rate)" else "NO"}")
            val enabled = dataContext.enabledMetrics.filter { it.value }.keys
            val disabled = dataContext.enabledMetrics.filter { !it.value }.keys
            if (enabled.isNotEmpty()) appendLine("Enabled metrics: ${enabled.joinToString(", ")}")
            if (disabled.isNotEmpty()) appendLine("Disabled metrics: ${disabled.joinToString(", ")}")
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

        val responseJson = callEdgeFunction(accessToken, CALL2_SYSTEM_PROMPT, userMessage)
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
    // Shared edge function caller
    // ═════════════════════════════════════════════════════════════════════

    private suspend fun callEdgeFunction(
        accessToken: String,
        systemPrompt: String,
        userMessage: String,
    ): String {
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

