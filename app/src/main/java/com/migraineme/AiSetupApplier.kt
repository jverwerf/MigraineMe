package com.migraineme

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Applies the AI-generated configuration to the user's Supabase data:
 * - Trigger/prodrome severities (prediction_value)
 * - Favorites (trigger_preferences, medicine_preferences, etc.)
 * - Gauge thresholds (risk_gauge_thresholds)
 *
 * Reports progress via callback so the UI can show a progress bar.
 */
object AiSetupApplier {

    private const val TAG = "AiSetupApplier"

    data class ApplyProgress(
        val current: Int,
        val total: Int,
        val label: String,
    )

    suspend fun applyConfig(
        context: Context,
        config: AiSetupService.AiConfig,
        onProgress: (ApplyProgress) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val accessToken = SessionStore.getValidAccessToken(appCtx)
            ?: return@withContext Result.failure(Exception("Not authenticated"))
        val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        val edge = EdgeFunctionsService()

        val totalOps = config.triggers.count { it.severity != "NONE" } +
                config.prodromes.count { it.severity != "NONE" } +
                config.triggers.count { it.favorite } +
                config.prodromes.count { it.favorite } +
                config.symptoms.count { it.favorite } +
                config.medicines.count { it.favorite } +
                config.reliefs.count { it.favorite } +
                config.activities.count { it.favorite } +
                config.missedActivities.count { it.favorite } +
                3 + // gauge thresholds
                config.decayWeights.size // decay weight curves
        var done = 0

        fun progress(label: String) {
            done++
            onProgress(ApplyProgress(done, totalOps, label))
        }

        try {
            // ── Fetch current pools to get IDs ──
            val triggerPool = db.getAllTriggerPool(accessToken)
            val prodromePool = db.getAllProdromePool(accessToken)
            val symptomPool = db.getAllSymptomPool(accessToken)
            val medicinePool = db.getAllMedicinePool(accessToken)
            val reliefPool = db.getAllReliefPool(accessToken)
            val activityPool = db.getAllActivityPool(accessToken)
            val missedActivityPool = db.getAllMissedActivityPool(accessToken)

            val triggerIdMap = triggerPool.associate { it.label.lowercase() to it.id }
            val prodromeIdMap = prodromePool.associate { it.label.lowercase() to it.id }
            val symptomIdMap = symptomPool.associate { it.label.lowercase() to it.id }
            val medicineIdMap = medicinePool.associate { it.label.lowercase() to it.id }
            val reliefIdMap = reliefPool.associate { it.label.lowercase() to it.id }
            val activityIdMap = activityPool.associate { it.label.lowercase() to it.id }
            val missedActivityIdMap = missedActivityPool.associate { it.label.lowercase() to it.id }

            Log.d(TAG, "Pools loaded: ${triggerPool.size}T ${prodromePool.size}P ${medicinePool.size}M ${reliefPool.size}R ${activityPool.size}A ${missedActivityPool.size}MA")

            // ── Trigger severities (group-aware) ──
            val triggerGroupMembers: Map<String, List<String>> = triggerPool
                .filter { it.displayGroup != null }
                .groupBy { it.displayGroup!!.lowercase() }
                .mapValues { entry -> entry.value.map { it.id } }

            for (rec in config.triggers) {
                if (rec.severity == "NONE") continue
                val groupIds = triggerGroupMembers[rec.label.lowercase()]
                if (groupIds != null) {
                    for (memberId in groupIds) {
                        runCatching { db.updateTriggerPoolItem(accessToken, memberId, predictionValue = rec.severity, defaultThreshold = rec.defaultThreshold) }
                            .onFailure { Log.w(TAG, "Failed trigger group '${rec.label}': ${it.message}") }
                    }
                } else {
                    val id = triggerIdMap[rec.label.lowercase()]
                    if (id != null) {
                        runCatching { db.updateTriggerPoolItem(accessToken, id, predictionValue = rec.severity, defaultThreshold = rec.defaultThreshold) }
                            .onFailure { Log.w(TAG, "Failed trigger '${rec.label}': ${it.message}") }
                    } else Log.w(TAG, "Trigger not found: '${rec.label}'")
                }
                progress("Trigger: ${rec.label}")
            }

            // ── Prodrome severities (group-aware) ──
            val prodromeGroupMembers: Map<String, List<String>> = prodromePool
                .filter { it.displayGroup != null }
                .groupBy { it.displayGroup!!.lowercase() }
                .mapValues { entry -> entry.value.map { it.id } }

            for (rec in config.prodromes) {
                if (rec.severity == "NONE") continue
                val groupIds = prodromeGroupMembers[rec.label.lowercase()]
                if (groupIds != null) {
                    for (memberId in groupIds) {
                        runCatching { db.updateProdromePoolItem(accessToken, memberId, predictionValue = rec.severity, defaultThreshold = rec.defaultThreshold) }
                            .onFailure { Log.w(TAG, "Failed prodrome group '${rec.label}': ${it.message}") }
                    }
                } else {
                    val id = prodromeIdMap[rec.label.lowercase()]
                    if (id != null) {
                        runCatching { db.updateProdromePoolItem(accessToken, id, predictionValue = rec.severity, defaultThreshold = rec.defaultThreshold) }
                            .onFailure { Log.w(TAG, "Failed prodrome '${rec.label}': ${it.message}") }
                    } else Log.w(TAG, "Prodrome not found: '${rec.label}'")
                }
                progress("Prodrome: ${rec.label}")
            }

            // ── Trigger favorites (group-aware: use first member as representative) ──
            // Build group name → first member ID lookup
            val triggerGroupFirstId: Map<String, String> = triggerPool
                .filter { it.displayGroup != null }
                .groupBy { it.displayGroup!!.lowercase() }
                .mapValues { entry -> entry.value.first().id }

            var pos = 0
            for (rec in config.triggers.filter { it.favorite }) {
                val id = triggerIdMap[rec.label.lowercase()]
                    ?: triggerGroupFirstId[rec.label.lowercase()]
                if (id != null) {
                    runCatching { db.insertTriggerPref(accessToken, id, pos++, "frequent") }
                        .onFailure { Log.w(TAG, "Failed trigger fav '${rec.label}': ${it.message}") }
                }
                progress("★ ${rec.label}")
            }

            // ── Prodrome favorites (group-aware) ──
            val prodromeGroupFirstId: Map<String, String> = prodromePool
                .filter { it.displayGroup != null }
                .groupBy { it.displayGroup!!.lowercase() }
                .mapValues { entry -> entry.value.first().id }

            pos = 0
            for (rec in config.prodromes.filter { it.favorite }) {
                val id = prodromeIdMap[rec.label.lowercase()]
                    ?: prodromeGroupFirstId[rec.label.lowercase()]
                if (id != null) {
                    runCatching { db.insertProdromePref(accessToken, id, pos++, "frequent") }
                        .onFailure { Log.w(TAG, "Failed prodrome fav '${rec.label}': ${it.message}") }
                }
                progress("★ ${rec.label}")
            }

            // ── Medicine favorites ──
            pos = 0
            for (rec in config.medicines.filter { it.favorite }) {
                medicineIdMap[rec.label.lowercase()]?.let { id ->
                    runCatching { db.insertMedicinePref(accessToken, id, pos++, "frequent") }
                        .onFailure { Log.w(TAG, "Failed medicine fav '${rec.label}': ${it.message}") }
                }
                progress("★ ${rec.label}")
            }

            // ── Symptom favorites ──
            pos = 0
            for (rec in config.symptoms.filter { it.favorite }) {
                symptomIdMap[rec.label.lowercase()]?.let { id ->
                    runCatching { db.insertSymptomPref(accessToken, id, pos++, "frequent") }
                        .onFailure { Log.w(TAG, "Failed symptom fav '${rec.label}': ${it.message}") }
                }
                progress("★ ${rec.label}")
            }

            // ── Relief favorites ──
            pos = 0
            for (rec in config.reliefs.filter { it.favorite }) {
                reliefIdMap[rec.label.lowercase()]?.let { id ->
                    runCatching { db.insertReliefPref(accessToken, id, pos++, "frequent") }
                        .onFailure { Log.w(TAG, "Failed relief fav '${rec.label}': ${it.message}") }
                }
                progress("★ ${rec.label}")
            }

            // ── Activity favorites ──
            pos = 0
            for (rec in config.activities.filter { it.favorite }) {
                activityIdMap[rec.label.lowercase()]?.let { id ->
                    runCatching { db.insertActivityPref(accessToken, id, pos++, "frequent") }
                        .onFailure { Log.w(TAG, "Failed activity fav '${rec.label}': ${it.message}") }
                }
                progress("★ ${rec.label}")
            }

            // ── Missed activity favorites ──
            pos = 0
            for (rec in config.missedActivities.filter { it.favorite }) {
                missedActivityIdMap[rec.label.lowercase()]?.let { id ->
                    runCatching { db.insertMissedActivityPref(accessToken, id, pos++, "frequent") }
                        .onFailure { Log.w(TAG, "Failed missed fav '${rec.label}': ${it.message}") }
                }
                progress("★ ${rec.label}")
            }

            // ── Gauge thresholds ──
            val t = config.gaugeThresholds
            runCatching {
                edge.upsertRiskGaugeThreshold(appCtx, "LOW", t.low.toDouble())
                progress("Gauge: LOW")
                edge.upsertRiskGaugeThreshold(appCtx, "MILD", t.mild.toDouble())
                progress("Gauge: MILD")
                edge.upsertRiskGaugeThreshold(appCtx, "HIGH", t.high.toDouble())
                progress("Gauge: HIGH")
            }.onFailure { Log.w(TAG, "Failed gauge thresholds: ${it.message}") }

            // ── Decay weights ──
            for (dw in config.decayWeights) {
                runCatching {
                    edge.upsertRiskDecayWeight(appCtx, dw.severity, dw.day0, dw.day1, dw.day2, dw.day3, dw.day4, dw.day5, dw.day6)
                    Log.d(TAG, "Decay weight ${dw.severity}: ${dw.day0}/${dw.day1}/${dw.day2}/${dw.day3}/${dw.day4}/${dw.day5}/${dw.day6}")
                }.onFailure { Log.w(TAG, "Failed decay weight ${dw.severity}: ${it.message}") }
                progress("Decay: ${dw.severity}")
            }

            Log.d(TAG, "AI config applied. $done/$totalOps operations.")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Apply failed", e)
            Result.failure(e)
        }
    }
}

