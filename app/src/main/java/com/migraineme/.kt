package com.migraineme

import android.content.Context
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Service for computing and reading correlation statistics.
 *
 * Two responsibilities:
 * 1. Trigger the compute-correlation-stats edge function (fire-and-forget after migraine close)
 * 2. Read results from correlation_stats + gauge_accuracy tables via PostgREST
 */
class CorrelationService {

    private val TAG = "CorrelationService"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // computation can take 10-20s
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    // ═══════════════════════════════════════════════════════════════
    // Data classes for reading correlation_stats
    // ═══════════════════════════════════════════════════════════════

    @Serializable
    data class CorrelationStat(
        val id: String = "",
        @SerialName("factor_name") val factorName: String,
        @SerialName("factor_type") val factorType: String,   // trigger, metric, interaction
        @SerialName("factor_b") val factorB: String? = null,
        @SerialName("best_lag_days") val bestLagDays: Int = 0,
        @SerialName("lift_ratio") val liftRatio: Float = 0f,
        @SerialName("pct_migraine_windows") val pctMigraineWindows: Float = 0f,
        @SerialName("pct_control_windows") val pctControlWindows: Float = 0f,
        @SerialName("sample_size") val sampleSize: Int = 0,
        @SerialName("p_value") val pValue: Float = 1f,
        @SerialName("suggested_threshold") val suggestedThreshold: Float? = null,
        @SerialName("current_threshold") val currentThreshold: Float? = null,
        @SerialName("updated_at") val updatedAt: String = "",
    ) {
        /** Human-readable description of this finding */
        fun toInsightText(): String = when (factorType) {
            "trigger" -> {
                val lagText = if (bestLagDays == 0) "on the same day" else "$bestLagDays day${if (bestLagDays > 1) "s" else ""} before onset"
                val pctText = "${pctMigraineWindows.toInt()}% of your migraines"
                "${factorName} appeared before $pctText ($lagText). That's ${String.format("%.1f", liftRatio)}x more than normal days."
            }
            "metric" -> {
                if (suggestedThreshold != null && currentThreshold != null &&
                    kotlin.math.abs(suggestedThreshold - currentThreshold) > currentThreshold * 0.05f) {
                    "Your migraine risk jumps when ${factorName.lowercase()} crosses ${fmtThreshold(suggestedThreshold, factorName)} — your current alert is set at ${fmtThreshold(currentThreshold, factorName)}."
                } else if (suggestedThreshold != null) {
                    "Your migraines cluster around ${factorName.lowercase()} of ${fmtThreshold(suggestedThreshold, factorName)} (${String.format("%.1f", liftRatio)}x lift)."
                } else {
                    "${factorName} shows a ${String.format("%.1f", liftRatio)}x difference on pre-migraine days vs normal days."
                }
            }
            "interaction" -> {
                "${factorName} + ${factorB ?: "?"} together preceded ${pctMigraineWindows.toInt()}% of your migraines — ${String.format("%.1f", liftRatio)}x more likely than either alone."
            }
            else -> "${factorName}: ${String.format("%.1f", liftRatio)}x lift"
        }

        /** Is this finding statistically meaningful? */
        fun isSignificant(): Boolean = pValue < 0.1f && liftRatio > 1.3f

        companion object {
            fun fmtThreshold(value: Float, metricLabel: String): String {
                val lower = metricLabel.lowercase()
                return when {
                    "sleep" in lower && "duration" in lower -> "${String.format("%.1f", value)}hrs"
                    "hrv" in lower -> "${value.toInt()}ms"
                    "pressure" in lower -> "${value.toInt()}hPa"
                    "humidity" in lower -> "${value.toInt()}%"
                    "temperature" in lower || "temp" in lower -> "${String.format("%.1f", value)}°C"
                    "screen" in lower -> "${String.format("%.1f", value)}hrs"
                    "recovery" in lower || "efficiency" in lower || "score" in lower -> "${value.toInt()}%"
                    "stress" in lower -> "${value.toInt()}"
                    "noise" in lower -> "${value.toInt()}%"
                    "heart" in lower || "hr" in lower -> "${value.toInt()}bpm"
                    else -> String.format("%.1f", value)
                }
            }
        }
    }

    @Serializable
    data class GaugeAccuracy(
        @SerialName("true_positives") val truePositives: Int = 0,
        @SerialName("false_positives") val falsePositives: Int = 0,
        @SerialName("false_negatives") val falseNegatives: Int = 0,
        @SerialName("true_negatives") val trueNegatives: Int = 0,
        @SerialName("total_days") val totalDays: Int = 0,
        @SerialName("sensitivity_pct") val sensitivityPct: Int = 0,
        @SerialName("specificity_pct") val specificityPct: Int = 0,
        @SerialName("false_alarm_rate_pct") val falseAlarmRatePct: Int = 0,
        @SerialName("updated_at") val updatedAt: String = "",
    ) {
        fun catchRateText(): String = "Your gauge caught ${sensitivityPct}% of your migraines"
        fun falseAlarmText(): String = "False alarm rate: ${falseAlarmRatePct}%"
    }

    // ═══════════════════════════════════════════════════════════════
    // 1. TRIGGER computation (call the edge function)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Fire-and-forget: triggers the edge function to recompute correlations.
     * Call this after a migraine is saved/closed.
     * Returns true if the function was invoked successfully.
     */
    suspend fun triggerComputation(context: Context): Boolean {
        val appCtx = context.applicationContext
        val accessToken = SessionStore.getValidAccessToken(appCtx) ?: return false

        val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/compute-correlation-stats"

        val requestBody = "{}".toRequestBody("application/json".toMediaType())

        val request = Request.Builder().url(url).post(requestBody)
            .header("Authorization", "Bearer $accessToken")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .build()

        return try {
            val response = client.newCall(request).execute()
            response.use {
                val ok = it.isSuccessful
                if (!ok) {
                    Log.w(TAG, "compute-correlation-stats failed: ${it.code} ${it.body?.string()}")
                }
                ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "triggerComputation exception", e)
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. READ results from PostgREST
    // ═══════════════════════════════════════════════════════════════

    /**
     * Fetch the top correlation stats for the current user.
     * Filters to significant findings (p < 0.1, lift > 1.3) sorted by lift.
     */
    suspend fun getTopCorrelations(
        context: Context,
        limit: Int = 10
    ): List<CorrelationStat> {
        val appCtx = context.applicationContext
        val accessToken = SessionStore.getValidAccessToken(appCtx) ?: return emptyList()

        // PostgREST query: filter to significant, order by lift desc
        val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/correlation_stats" +
            "?p_value=lt.0.1&lift_ratio=gt.1.3" +
            "&order=lift_ratio.desc" +
            "&limit=$limit" +
            "&select=id,factor_name,factor_type,factor_b,best_lag_days,lift_ratio," +
            "pct_migraine_windows,pct_control_windows,sample_size,p_value," +
            "suggested_threshold,current_threshold,updated_at"

        val request = Request.Builder().url(url).get()
            .header("Authorization", "Bearer $accessToken")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .build()

        return try {
            val response = client.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) {
                    Log.w(TAG, "getTopCorrelations failed: ${it.code}")
                    return emptyList()
                }
                val body = it.body?.string() ?: "[]"
                json.decodeFromString<List<CorrelationStat>>(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getTopCorrelations exception", e)
            emptyList()
        }
    }

    /**
     * Fetch all correlation stats (including non-significant) for a specific type.
     */
    suspend fun getCorrelationsByType(
        context: Context,
        factorType: String // "trigger", "metric", "interaction"
    ): List<CorrelationStat> {
        val appCtx = context.applicationContext
        val accessToken = SessionStore.getValidAccessToken(appCtx) ?: return emptyList()

        val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/correlation_stats" +
            "?factor_type=eq.$factorType" +
            "&order=lift_ratio.desc" +
            "&select=id,factor_name,factor_type,factor_b,best_lag_days,lift_ratio," +
            "pct_migraine_windows,pct_control_windows,sample_size,p_value," +
            "suggested_threshold,current_threshold,updated_at"

        val request = Request.Builder().url(url).get()
            .header("Authorization", "Bearer $accessToken")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .build()

        return try {
            val response = client.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) return emptyList()
                val body = it.body?.string() ?: "[]"
                json.decodeFromString<List<CorrelationStat>>(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCorrelationsByType exception", e)
            emptyList()
        }
    }

    /**
     * Fetch gauge accuracy for the current user.
     */
    suspend fun getGaugeAccuracy(context: Context): GaugeAccuracy? {
        val appCtx = context.applicationContext
        val accessToken = SessionStore.getValidAccessToken(appCtx) ?: return null

        val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/gauge_accuracy" +
            "?select=*" +
            "&limit=1"

        val request = Request.Builder().url(url).get()
            .header("Authorization", "Bearer $accessToken")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .header("Accept", "application/vnd.pgrst.object+json")
            .build()

        return try {
            val response = client.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) return null
                val body = it.body?.string() ?: return null
                json.decodeFromString<GaugeAccuracy>(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getGaugeAccuracy exception", e)
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

}
