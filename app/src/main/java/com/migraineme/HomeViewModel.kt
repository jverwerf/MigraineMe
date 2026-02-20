package com.migraineme

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class MigraineLog(
    val id: String = UUID.randomUUID().toString(),
    val time: Instant = Instant.now(),
    val severity: Int, // 0..10
    val note: String? = null,
    val type: String = "Headache"
)

data class TriggerScore(
    val name: String,
    val score: Int,          // total contribution points
    val severity: String = "LOW",   // highest severity (HIGH/MILD/LOW)
    val daysActive: Int = 1         // how many of the last 7 days this trigger appeared
)

/** Risk zone derived from gauge thresholds */
enum class RiskZone(val label: String) {
    NONE("None"),
    LOW("Low"),
    MILD("Mild"),
    HIGH("High")
}

/** Per-day risk breakdown for the 7-day forecast */
data class DayRisk(
    val date: LocalDate,
    val score: Double,
    val zone: RiskZone,
    val percent: Int,
    val topTriggers: List<TriggerScore>
)

data class HomeUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val riskScore: Double = 0.0,            // raw summed score
    val riskZone: RiskZone = RiskZone.NONE,
    val riskPercent: Int = 0,               // 0..100 for gauge animation (mapped from score)
    val triggersAtRisk: List<TriggerScore> = emptyList(),
    val aiRecommendation: String = "",
    val dailyInsight: String? = null,       // from daily_insights table (premium)
    val recentLogs: List<MigraineLog> = emptyList(),
    // Gauge thresholds for display
    val gaugeMaxScore: Double = 10.0,       // the HIGH threshold — used as gauge max
    val forecast: List<Int> = listOf(0, 0, 0, 0, 0, 0, 0),  // 7-day risk % (today + next 6 days)
    val dayRisks: List<DayRisk> = emptyList()  // full per-day breakdown
)

class HomeViewModel : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState(loading = true))
    val state: StateFlow<HomeUiState> = _state

    private val timeFmt = DateTimeFormatter.ofPattern("EEE d MMM • HH:mm")

    /**
     * Read pre-computed score from risk_score_live.
     * If missing or stale >2h, trigger edge function then re-read.
     */
    fun loadRisk(context: Context) {
        viewModelScope.launch {
            _state.value = HomeUiState(loading = true)

            try {
                val result = withContext(Dispatchers.IO) { loadFromLiveScore(context) }
                _state.value = result
            } catch (e: Exception) {
                Log.e(TAG, "loadRisk error: ${e.message}", e)
                _state.value = HomeUiState(
                    loading = false,
                    riskScore = 0.0,
                    riskZone = RiskZone.NONE,
                    riskPercent = 0,
                    triggersAtRisk = emptyList(),
                    aiRecommendation = "Unable to load risk data right now. Pull down to refresh.",
                    forecast = listOf(0, 0, 0, 0, 0, 0, 0),
                    dayRisks = emptyList()
                )
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // Read from risk_score_live (DB only)
    // ═════════════════════════════════════════════════════════════════

    private suspend fun loadFromLiveScore(context: Context): HomeUiState {
        val appCtx = context.applicationContext
        val accessToken = SessionStore.getValidAccessToken(appCtx)
            ?: return HomeUiState(
                loading = false,
                riskScore = 0.0,
                riskZone = RiskZone.NONE,
                riskPercent = 0,
                triggersAtRisk = emptyList(),
                aiRecommendation = "Sign in to see your risk score.",
                forecast = listOf(0, 0, 0, 0, 0, 0, 0),
                dayRisks = emptyList()
            )

        val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        val edge = EdgeFunctionsService()
        val userId = SessionStore.readUserId(appCtx)
        Log.d(TAG, "Logged in userId=$userId")

        // Try reading pre-computed score
        var live = db.getRiskScoreLive(accessToken)
        Log.d(TAG, "Live score read: ${if (live != null) "rowUserId=${live.userId} score=${live.score} zone=${live.zone}" else "NULL"}")

        // If no data or stale (>2 hours), trigger on-demand calculation
        if (live == null || isStale(live.updatedAt, hours = 2)) {
            Log.d(TAG, "Live score ${if (live == null) "missing" else "stale (updatedAt=${live.updatedAt})"}, triggering on-demand calculation")
            if (userId != null) {
                try {
                    edge.triggerRiskCalculation(appCtx, userId)
                    // Re-read after calculation
                    live = db.getRiskScoreLive(accessToken)
                    Log.d(TAG, "After on-demand: ${if (live != null) "score=${live.score}" else "still NULL"}")
                } catch (e: Exception) {
                    Log.w(TAG, "On-demand calc failed: ${e.message}")
                }
            }
        }

        if (live != null) {
            return mapLiveToUiState(live, edge, appCtx)
        }

        // No data at all — still show cards with zero values so the UI is never empty
        Log.w(TAG, "No live score available at all")
        return HomeUiState(
            loading = false,
            riskScore = 0.0,
            riskZone = RiskZone.NONE,
            riskPercent = 0,
            triggersAtRisk = emptyList(),
            aiRecommendation = "No risk data yet. Your score will appear once triggers are detected.",
            forecast = listOf(0, 0, 0, 0, 0, 0, 0),
            dayRisks = emptyList()
        )
    }

    private fun isStale(updatedAt: String?, hours: Int): Boolean {
        if (updatedAt == null) return true
        return try {
            // PostgREST may return various timestamptz formats
            val normalized = updatedAt
                .replace(" ", "T")           // "2026-02-15 14:10:21" → "2026-02-15T14:10:21"
                .let { if (it.endsWith("+00")) "${it}:00" else it } // "+00" → "+00:00"
            val updated = Instant.parse(normalized)
            val age = java.time.Duration.between(updated, Instant.now())
            age.toHours() >= hours
        } catch (_: Exception) {
            Log.w(TAG, "isStale: could not parse updatedAt='$updatedAt', treating as stale")
            true
        }
    }

    private suspend fun mapLiveToUiState(
        live: SupabaseDbService.RiskScoreLiveRow,
        edge: EdgeFunctionsService,
        appCtx: Context
    ): HomeUiState {
        val zone = when (live.zone.uppercase()) {
            "HIGH" -> RiskZone.HIGH; "MILD" -> RiskZone.MILD; "LOW" -> RiskZone.LOW
            else -> RiskZone.NONE
        }

        val topTriggers = parseTopTriggers(live.topTriggers)
        val forecast = parseForecast(live.forecast)
        val dayRisks = parseDayRisks(live.dayRisks)

        // Fetch gauge thresholds for gaugeMax display
        val thresholdRows = edge.getRiskGaugeThresholds(appCtx)
        val thresholdHigh = thresholdRows.find { it.zone.uppercase() == "HIGH" }?.minValue ?: 10.0
        val gaugeMax = thresholdHigh * 1.2

        val aiTip = when (zone) {
            RiskZone.HIGH ->
                "High risk: consider taking early abortive medication, reduce screen time, stay hydrated, and avoid known triggers today."
            RiskZone.MILD ->
                "Moderate risk: keep regular meals, take breaks every 45–60 min, and monitor for early symptoms."
            RiskZone.LOW ->
                "Low risk: maintain your routine. Stay hydrated and keep stress levels in check."
            RiskZone.NONE ->
                "Looking good! No significant active triggers. Keep up your healthy habits."
        }

        // Fetch AI daily insight (premium feature)
        var dailyInsight: String? = null
        try {
            val token = SessionStore.getValidAccessToken(appCtx)
            if (token != null) {
                val today = java.time.LocalDate.now().toString()
                Log.d(TAG, "Fetching daily insight for date=$today")
                val client = io.ktor.client.HttpClient(io.ktor.client.engine.android.Android)
                try {
                    val response = client.get("${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/daily_insights") {
                        header("Authorization", "Bearer $token")
                        header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                        parameter("select", "insight")
                        parameter("date", "eq.$today")
                        parameter("limit", "1")
                        header(io.ktor.http.HttpHeaders.Accept, "application/json")
                    }
                    val body = response.bodyAsText()
                    Log.d(TAG, "Daily insight response: status=${response.status}, body=$body")
                    if (response.status.isSuccess()) {
                        val arr = org.json.JSONArray(body)
                        if (arr.length() > 0) {
                            dailyInsight = arr.getJSONObject(0).optString("insight", null)
                            Log.d(TAG, "Daily insight loaded: ${dailyInsight?.take(60)}...")
                        } else {
                            Log.d(TAG, "Daily insight: empty array returned")
                        }
                    }
                } finally {
                    client.close()
                }
            } else {
                Log.d(TAG, "Daily insight: no access token")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Daily insight fetch failed: ${e.message}", e)
        }

        return HomeUiState(
            loading = false,
            riskScore = live.score,
            riskZone = zone,
            riskPercent = live.percent,
            triggersAtRisk = topTriggers,
            aiRecommendation = dailyInsight ?: aiTip,
            dailyInsight = dailyInsight,
            gaugeMaxScore = gaugeMax,
            forecast = forecast,
            dayRisks = dayRisks,
        )
    }

    // ═════════════════════════════════════════════════════════════════
    // JSON Parsing helpers for risk_score_live columns
    // ═════════════════════════════════════════════════════════════════

    private fun parseTopTriggers(json: String?): List<TriggerScore> {
        if (json.isNullOrBlank() || json == "[]" || json == "null") return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                TriggerScore(
                    name = obj.getString("name"),
                    score = obj.optInt("score", 0),
                    severity = obj.optString("severity", "LOW"),
                    daysActive = obj.optInt("days_active", 1),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseTopTriggers error: ${e.message}")
            emptyList()
        }
    }

    private fun parseForecast(json: String?): List<Int> {
        if (json.isNullOrBlank() || json == "[]" || json == "null") return listOf(0, 0, 0, 0, 0, 0, 0)
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getInt(it) }
        } catch (e: Exception) {
            Log.w(TAG, "parseForecast error: ${e.message}")
            listOf(0, 0, 0, 0, 0, 0, 0)
        }
    }

    private fun parseDayRisks(json: String?): List<DayRisk> {
        if (json.isNullOrBlank() || json == "[]" || json == "null") return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val dateStr = obj.getString("date")
                val date = LocalDate.parse(dateStr.substring(0, 10))
                val zoneStr = obj.optString("zone", "NONE").uppercase()
                DayRisk(
                    date = date,
                    score = obj.getDouble("score"),
                    zone = when (zoneStr) {
                        "HIGH" -> RiskZone.HIGH; "MILD" -> RiskZone.MILD; "LOW" -> RiskZone.LOW
                        else -> RiskZone.NONE
                    },
                    percent = obj.optInt("percent", 0),
                    topTriggers = try {
                        val ta = obj.getJSONArray("top_triggers")
                        (0 until ta.length()).map { j ->
                            val to = ta.getJSONObject(j)
                            TriggerScore(to.getString("name"), to.optInt("score", 0), to.optString("severity", "LOW"), to.optInt("days_active", 1))
                        }
                    } catch (_: Exception) { emptyList() }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseDayRisks error: ${e.message}")
            emptyList()
        }
    }

    fun formatTime(instant: Instant): String {
        val dt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        return dt.format(timeFmt)
    }

    companion object {
        private const val TAG = "HomeViewModel"
    }
}

