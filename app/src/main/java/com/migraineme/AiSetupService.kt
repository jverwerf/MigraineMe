package com.migraineme

import android.content.Context
import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.json.JSONArray

/**
 * AI-powered full app configuration via GPT-4o-mini, proxied through Supabase Edge Function.
 * The OpenAI API key lives server-side in Supabase vault — never touches the client.
 * Cost: ~$0.003 per call.
 *
 * Collects: questionnaire answers + connected data sources + available pool items
 * Returns:  trigger/prodrome severities, favorites, decay weights, gauge thresholds,
 *           medicine/relief/activity favorites, and data gap warnings.
 */
object AiSetupService {

    private const val TAG = "AiSetupService"

    // ═══════════════════════════════════════════════════════════════════════
    // Input data classes
    // ═══════════════════════════════════════════════════════════════════════

    data class UserAnswers(
        // Page 1 — Migraine Basics
        val frequency: String? = null,
        val avgDuration: String? = null,
        val avgSeverity: String? = null,
        val usualTiming: String? = null,
        val warningBefore: String? = null,
        val familyHistory: String? = null,

        // Page 2 — Triggers & Lifestyle
        val knownTriggerAreas: Set<String> = emptySet(),
        val knownSpecificTriggers: String? = null,
        val warningSignsExperienced: List<String> = emptyList(),
        val typicalSleepHours: String? = null,
        val sleepQuality: String? = null,
        val screenTimeDaily: String? = null,
        val caffeineIntake: String? = null,
        val alcoholIntake: String? = null,
        val exerciseFrequency: String? = null,
        val stressLevel: String? = null,
        val trackCycle: String? = null,
        val cycleRelated: String? = null,
        val waterIntake: String? = null,

        // Page 3 — Medicines, Reliefs & Activities
        val selectedSymptoms: String? = null,
        val currentMedicines: String? = null,
        val preventiveMedicines: String? = null,
        val helpfulReliefs: String? = null,
        val activitiesDuringMigraine: String? = null,
        val missedActivities: String? = null,
        val additionalNotes: String? = null,
    )

    data class DataContext(
        val whoopConnected: Boolean,
        val healthConnectConnected: Boolean,
        val enabledMetrics: Map<String, Boolean>,   // metric name → enabled
    )

    data class AvailableItems(
        val triggers: List<PoolLabel>,
        val prodromes: List<PoolLabel>,
        val symptoms: List<PoolLabel>,
        val medicines: List<PoolLabel>,
        val reliefs: List<PoolLabel>,
        val activities: List<PoolLabel>,
        val missedActivities: List<PoolLabel>,
        /** Individual trigger labels (pre-group-collapse) that have a metric_table in Supabase = auto-detected */
        val autoTriggerLabels: Set<String> = emptySet(),
        /** Individual prodrome labels that have a metric_table = auto-detected */
        val autoProdromeLabels: Set<String> = emptySet(),
    )

    data class PoolLabel(val label: String, val category: String? = null, val iconKey: String? = null, val isAuto: Boolean = false)

    // ═══════════════════════════════════════════════════════════════════════
    // Output data classes
    // ═══════════════════════════════════════════════════════════════════════

    @Serializable
    data class AiDecayWeights(
        val severity: String = "",
        val day0: Double = 0.0,
        val day1: Double = 0.0,
        val day2: Double = 0.0,
        val day3: Double = 0.0,
        val day4: Double = 0.0,
        val day5: Double = 0.0,
        val day6: Double = 0.0,
        val reasoning: String = "",
    )

    @Serializable
    data class AiConfig(
        val triggers: List<AiTriggerRec> = emptyList(),
        val prodromes: List<AiProdromeRec> = emptyList(),
        val symptoms: List<AiFavoriteRec> = emptyList(),
        val medicines: List<AiFavoriteRec> = emptyList(),
        val reliefs: List<AiFavoriteRec> = emptyList(),
        val activities: List<AiFavoriteRec> = emptyList(),
        @SerialName("missed_activities") val missedActivities: List<AiFavoriteRec> = emptyList(),
        @SerialName("gauge_thresholds") val gaugeThresholds: AiGaugeThresholds = AiGaugeThresholds(),
        @SerialName("decay_weights") val decayWeights: List<AiDecayWeights> = emptyList(),
        @SerialName("data_warnings") val dataWarnings: List<AiDataWarning> = emptyList(),
        val summary: String = "",
        @SerialName("clinical_assessment") val clinicalAssessment: String = "",
        @SerialName("calibration_notes") val calibrationNotes: String = "",
    )

    @Serializable
    data class AiTriggerRec(
        val label: String,
        val severity: String,
        @SerialName("decay_days") val decayDays: Int = 1,
        val favorite: Boolean = false,
        val reasoning: String = "",
        @SerialName("default_threshold") val defaultThreshold: Double? = null,
    )

    @Serializable
    data class AiProdromeRec(
        val label: String,
        val severity: String,
        val favorite: Boolean = false,
        val reasoning: String = "",
        @SerialName("default_threshold") val defaultThreshold: Double? = null,
    )

    @Serializable
    data class AiFavoriteRec(
        val label: String,
        val favorite: Boolean = true,
        val reasoning: String = "",
    )

    @Serializable
    data class AiGaugeThresholds(
        val low: Int = 3,
        val mild: Int = 8,
        val high: Int = 15,
        val reasoning: String = "",
    )

    @Serializable
    data class AiDataWarning(
        val type: String = "",        // "missing_data", "missing_connection", "suggestion"
        val message: String = "",
        val metric: String? = null,   // which metric to enable
        val severity: String = "medium",  // "high", "medium", "low"
    )

    // ═══════════════════════════════════════════════════════════════════════
    // System prompt
    // ═══════════════════════════════════════════════════════════════════════

    private fun buildSystemPrompt(): String = """
You are a migraine specialist AI configuring the MigraineMe app for a new user.

RULES:
1. Use ONLY exact label strings from the AVAILABLE ITEMS lists provided. Never invent labels.
2. Rate EVERY trigger and prodrome — use "NONE" for irrelevant ones.
3. Mark 5-8 most relevant items per category as favorite=true (these go in the quick-log bar).
4. For medicines/reliefs/activities/missed_activities: ONLY include items the user mentioned or that are clearly relevant. Set favorite=true for those.
5. Be conservative — start with fewer HIGH ratings. Users can adjust up.
6. Decay weights control how quickly a trigger's risk contribution fades over days (day0=today, day6=6 days ago).
   Return 3 rows: HIGH, MILD, LOW. Each has day0-day6 values.
   - Frequent migraines: steeper curves (high day0, rapid drop) — triggers hit hard but fade fast
   - Infrequent migraines: flatter curves (moderate day0, slower drop) — cumulative buildup matters more
   - Defaults: HIGH=10/5/2.5/1/0/0/0, MILD=6/3/1.5/0.5/0/0/0, LOW=3/1.5/0/0/0/0/0
7. NEVER set favorite=true on auto-detected triggers or prodromes (marked with [AUTO] in the items list). These are collected automatically from connected devices and do not need manual logging.

SEVERITY SCALING by migraine frequency:
- Daily → more HIGH ratings, tighter gauge thresholds (low:3, mild:8, high:15)
- Weekly → balanced mix, medium thresholds (low:4, mild:10, high:18)
- Monthly → more LOW ratings, wider thresholds (low:5, mild:12, high:22)
- Rarely → mostly NONE/LOW, widest thresholds (low:5, mild:15, high:25)

DECAY_DAYS (how many days a trigger stays active after occurring):
- Weather/barometric: 1 day (immediate effect)
- Diet/caffeine/alcohol: 1 day (immediate)
- Sleep quality/duration: 1-2 days (next-day effect)  
- Screen time/noise: 1 day (immediate)
- Physical exertion: 1-2 days (delayed onset)
- Stress/cognitive: 2-3 days (cumulative)
- Hormonal/menstrual: 3-4 days (lingering cycle effect)
- Dehydration: 1-2 days

DATA WARNINGS: Compare the user's trigger areas against their connected data sources and enabled metrics. Flag any mismatch where a trigger is rated HIGH or MILD but the relevant data isn't being collected. Also suggest connections that would improve predictions.

Respond with ONLY valid JSON (no markdown fences, no preamble). Use this exact schema:
{
  "triggers": [{"label":"...","severity":"HIGH|MILD|LOW|NONE","decay_days":1-4,"favorite":true/false,"reasoning":"..."}],
  "prodromes": [{"label":"...","severity":"HIGH|MILD|LOW|NONE","favorite":true/false,"reasoning":"..."}],
  "symptoms": [{"label":"...","favorite":true,"reasoning":"..."}],
  "medicines": [{"label":"...","favorite":true,"reasoning":"..."}],
  "reliefs": [{"label":"...","favorite":true,"reasoning":"..."}],
  "activities": [{"label":"...","favorite":true,"reasoning":"..."}],
  "missed_activities": [{"label":"...","favorite":true,"reasoning":"..."}],
  "gauge_thresholds": {"low":3-5,"mild":8-15,"high":15-30,"reasoning":"..."},
  "decay_weights": [
    {"severity":"HIGH","day0":10,"day1":5,"day2":2.5,"day3":1,"day4":0,"day5":0,"day6":0,"reasoning":"..."},
    {"severity":"MILD","day0":6,"day1":3,"day2":1.5,"day3":0.5,"day4":0,"day5":0,"day6":0,"reasoning":"..."},
    {"severity":"LOW","day0":3,"day1":1.5,"day2":0,"day3":0,"day4":0,"day5":0,"day6":0,"reasoning":"..."}
  ],
  "data_warnings": [{"type":"missing_data|missing_connection|suggestion","message":"...","metric":"...or null","severity":"high|medium|low"}],
  "summary": "2-3 sentence personalised summary of the configuration"
}
""".trimIndent()

    // ═══════════════════════════════════════════════════════════════════════
    // User message builder
    // ═══════════════════════════════════════════════════════════════════════

    private fun buildUserMessage(
        answers: UserAnswers,
        data: DataContext,
        items: AvailableItems
    ): String = buildString {
        appendLine("=== USER'S MIGRAINE PROFILE ===")
        appendLine()

        // Page 1
        appendLine("MIGRAINE BASICS:")
        answers.frequency?.let { appendLine("- Frequency: $it") }
        answers.avgDuration?.let { appendLine("- Average duration: $it") }
        answers.avgSeverity?.let { appendLine("- Average severity: $it") }
        answers.usualTiming?.let { appendLine("- Usual timing: $it") }
        answers.warningBefore?.let { appendLine("- Gets warning signs: $it") }
        answers.familyHistory?.let { appendLine("- Family history: $it") }
        appendLine()

        // Page 2
        appendLine("TRIGGERS & LIFESTYLE:")
        if (answers.knownTriggerAreas.isNotEmpty()) appendLine("- Known trigger areas: ${answers.knownTriggerAreas.joinToString(", ")}")
        answers.knownSpecificTriggers?.let { appendLine("- Specific known triggers: $it") }
        if (answers.warningSignsExperienced.isNotEmpty()) appendLine("- Warning signs experienced: ${answers.warningSignsExperienced.joinToString(", ")}")
        answers.typicalSleepHours?.let { appendLine("- Typical sleep: $it hours") }
        answers.sleepQuality?.let { appendLine("- Sleep quality: $it") }
        answers.screenTimeDaily?.let { appendLine("- Daily screen time: $it") }
        answers.caffeineIntake?.let { appendLine("- Caffeine: $it") }
        answers.alcoholIntake?.let { appendLine("- Alcohol: $it") }
        answers.exerciseFrequency?.let { appendLine("- Exercise: $it") }
        answers.stressLevel?.let { appendLine("- Stress level: $it") }
        answers.waterIntake?.let { appendLine("- Water intake: $it") }
        answers.trackCycle?.let { appendLine("- Tracks menstrual cycle: $it") }
        answers.cycleRelated?.let { appendLine("- Migraines cycle-related: $it") }
        appendLine()

        // Page 3
        appendLine("SYMPTOMS, MEDICINES & RELIEFS:")
        answers.selectedSymptoms?.let { appendLine("- Symptoms experienced: $it") }
        answers.currentMedicines?.let { appendLine("- Current medicines: $it") }
        answers.preventiveMedicines?.let { appendLine("- Preventive medicines: $it") }
        answers.helpfulReliefs?.let { appendLine("- What helps: $it") }
        answers.activitiesDuringMigraine?.let { appendLine("- Activities during migraines: $it") }
        answers.missedActivities?.let { appendLine("- Missed activities: $it") }
        answers.additionalNotes?.let {
            appendLine()
            appendLine("ADDITIONAL NOTES FROM USER:")
            appendLine(it)
        }
        appendLine()

        // Data context
        appendLine("=== CONNECTED DATA SOURCES ===")
        appendLine("WHOOP connected: ${data.whoopConnected}")
        appendLine("Health Connect connected: ${data.healthConnectConnected}")
        appendLine("Enabled metrics:")
        data.enabledMetrics.forEach { (metric, enabled) ->
            appendLine("  - $metric: ${if (enabled) "ON" else "OFF"}")
        }
        appendLine()

        // Available items
        appendLine("=== AVAILABLE ITEMS (use EXACT labels) ===")
        appendLine()
        appendLine("TRIGGERS:")
        items.triggers.groupBy { it.category ?: "Other" }.forEach { (cat, list) ->
            appendLine("  [$cat] ${list.joinToString(", ") { "${it.label}${if (it.isAuto) " [AUTO]" else ""}" }}")
        }
        appendLine()
        appendLine("PRODROMES:")
        items.prodromes.groupBy { it.category ?: "Other" }.forEach { (cat, list) ->
            appendLine("  [$cat] ${list.joinToString(", ") { "${it.label}${if (it.isAuto) " [AUTO]" else ""}" }}")
        }
        appendLine()
        appendLine("MEDICINES: ${items.medicines.joinToString(", ") { it.label }}")
        appendLine()
        appendLine("RELIEFS: ${items.reliefs.joinToString(", ") { it.label }}")
        appendLine()
        appendLine("SYMPTOMS: ${items.symptoms.joinToString(", ") { it.label }}")
        appendLine()
        appendLine("ACTIVITIES: ${items.activities.joinToString(", ") { it.label }}")
        appendLine()
        appendLine("MISSED ACTIVITIES: ${items.missedActivities.joinToString(", ") { it.label }}")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Data context builder (reads from device state)
    // ═══════════════════════════════════════════════════════════════════════

    suspend fun buildDataContext(context: Context): DataContext = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val whoopConnected = WhoopTokenStore(appCtx).load() != null

        // Check Health Connect permissions
        val hcConnected = try {
            val hc = androidx.health.connect.client.HealthConnectClient.getOrCreate(appCtx)
            hc.permissionController.getGrantedPermissions().isNotEmpty()
        } catch (_: Exception) { false }

        // Fetch metric settings
        val metrics = try {
            EdgeFunctionsService().getMetricSettings(appCtx)
                .associate { it.metric to it.enabled }
        } catch (_: Exception) { emptyMap() }

        DataContext(
            whoopConnected = whoopConnected,
            healthConnectConnected = hcConnected,
            enabledMetrics = metrics,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Available items builder (reads from Supabase)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Fetch auto-detect labels from the global template table (trigger_templates or prodrome_templates).
     * These are the source of truth for which labels have metric_table (= auto-detected).
     * Returns a Set of lowercase label strings.
     */
    private suspend fun fetchAutoLabelsFromTemplate(accessToken: String, table: String): Set<String> {
        val client = HttpClient(io.ktor.client.engine.android.Android)
        return try {
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/$table?select=label&metric_table=not.is.null"
            val response = client.get(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            if (!response.status.isSuccess()) {
                Log.w(TAG, "Failed to fetch $table auto labels: ${response.status}")
                emptySet()
            } else {
                val body = response.bodyAsText()
                val arr = JSONArray(body)
                val labels = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    val label = arr.getJSONObject(i).optString("label", "")
                    if (label.isNotBlank()) labels.add(label.lowercase())
                }
                Log.d(TAG, "Fetched ${labels.size} auto labels from $table")
                labels
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching auto labels from $table", e)
            emptySet()
        } finally {
            client.close()
        }
    }

    suspend fun buildAvailableItems(context: Context): AvailableItems = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val accessToken = SessionStore.getValidAccessToken(appCtx)
            ?: throw IllegalStateException("No access token")
        val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)

        // Collapse display_group items: GPT sees group names instead of individual labels
        val rawTriggers = db.getAllTriggerPool(accessToken)

        // Build auto-detect lookup from TEMPLATE tables (global source of truth)
        // user_triggers may not have metric_table populated — templates always do
        val autoTriggerLabels = fetchAutoLabelsFromTemplate(accessToken, "trigger_templates")
        val autoProdromeLabels = fetchAutoLabelsFromTemplate(accessToken, "prodrome_templates")

        val triggerStandalone = rawTriggers.filter { it.displayGroup == null }
            .map { PoolLabel(it.label, it.category, it.iconKey, isAuto = it.metricTable != null) }
        val triggerGrouped = rawTriggers.filter { it.displayGroup != null }
            .groupBy { it.displayGroup!! }
            .map { (groupName, members) ->
                PoolLabel(groupName, members.first().category, members.first().iconKey, isAuto = members.first().metricTable != null)
            }
        val triggers = triggerStandalone + triggerGrouped

        val rawProdromes = db.getAllProdromePool(accessToken)

        val prodromeStandalone = rawProdromes.filter { it.displayGroup == null }
            .map { PoolLabel(it.label, it.category, it.iconKey, isAuto = it.metricTable != null) }
        val prodromeGrouped = rawProdromes.filter { it.displayGroup != null }
            .groupBy { it.displayGroup!! }
            .map { (groupName, members) ->
                PoolLabel(groupName, members.first().category, members.first().iconKey, isAuto = members.first().metricTable != null)
            }
        val prodromes = prodromeStandalone + prodromeGrouped

        val symptoms = db.getAllSymptomPool(accessToken).map { PoolLabel(it.label, it.category, it.iconKey) }
        val medicines = db.getAllMedicinePool(accessToken).map { PoolLabel(it.label, it.category) }
        val reliefs = db.getAllReliefPool(accessToken).map { PoolLabel(it.label, it.category, it.iconKey) }
        val activities = db.getAllActivityPool(accessToken).map { PoolLabel(it.label, it.category, it.iconKey) }
        val missedActivities = db.getAllMissedActivityPool(accessToken).map { PoolLabel(it.label, it.category, it.iconKey) }

        AvailableItems(triggers, prodromes, symptoms, medicines, reliefs, activities, missedActivities, autoTriggerLabels, autoProdromeLabels)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // API call (via Supabase Edge Function)
    // ═══════════════════════════════════════════════════════════════════════

    suspend fun generateConfig(
        context: Context,
        answers: UserAnswers,
        dataContext: DataContext,
        availableItems: AvailableItems,
    ): Result<AiConfig> = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val accessToken = SessionStore.getValidAccessToken(appCtx)
            ?: return@withContext Result.failure(Exception("Not authenticated"))

        val systemPrompt = buildSystemPrompt()
        val userMessage = buildUserMessage(answers, dataContext, availableItems)

        Log.d(TAG, "Sending to ai-setup edge function. System: ${systemPrompt.length} chars, User: ${userMessage.length} chars")

        val requestBody = buildJsonObject {
            put("system_prompt", systemPrompt)
            put("user_message", userMessage)
        }

        val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/ai-setup"

        val client = HttpClient(io.ktor.client.engine.android.Android) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false })
            }
        }

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
                return@withContext Result.failure(Exception("AI setup failed (${response.status}): $responseText"))
            }

            // Edge function returns the AI config JSON directly
            val cleanJson = responseText
                .replace("```json", "").replace("```", "")
                .trim()

            val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
            val config = json.decodeFromString<AiConfig>(cleanJson)

            Log.d(TAG, "Parsed: ${config.triggers.size} triggers, ${config.prodromes.size} prodromes, " +
                    "${config.medicines.size} medicines, ${config.reliefs.size} reliefs, " +
                    "${config.dataWarnings.size} warnings")

            Result.success(config)

        } catch (e: Exception) {
            Log.e(TAG, "AI setup call failed", e)
            Result.failure(e)
        } finally {
            client.close()
        }
    }
}

