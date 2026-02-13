package com.migraineme

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToInt

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
     * Call this from HomeScreenRoot once we have context + auth token.
     */
    fun loadRisk(context: Context) {
        viewModelScope.launch {
            _state.value = HomeUiState(loading = true)

            try {
                val result = withContext(Dispatchers.IO) { calculateRisk(context) }
                _state.value = result
            } catch (e: Exception) {
                Log.e("HomeViewModel", "loadRisk error: ${e.message}", e)
                _state.value = HomeUiState(
                    loading = false,
                    error = "Failed to calculate risk: ${e.message}"
                )
            }
        }
    }

    private suspend fun calculateRisk(context: Context): HomeUiState {
        val appCtx = context.applicationContext
        val accessToken = SessionStore.getValidAccessToken(appCtx)
            ?: return HomeUiState(loading = false, error = "Not logged in")

        val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        val edge = EdgeFunctionsService()
        val today = LocalDate.now(ZoneId.systemDefault())

        // ── 1. Fetch decay weights ──
        val decayRows = edge.getRiskDecayWeights(appCtx)
        val decayMap: Map<String, List<Double>> = decayRows.associate { row ->
            row.severity.uppercase() to listOf(
                row.day0, row.day1, row.day2, row.day3, row.day4, row.day5, row.day6
            )
        }
        Log.d("HomeViewModel", "Decay weights: ${decayMap.keys}")

        // ── 2. Fetch gauge thresholds ──
        val thresholdRows = edge.getRiskGaugeThresholds(appCtx)
        val thresholdMap: Map<String, Double> = thresholdRows.associate { it.zone.uppercase() to it.minValue }
        val thresholdHigh = thresholdMap["HIGH"] ?: 10.0
        val thresholdMild = thresholdMap["MILD"] ?: 5.0
        val thresholdLow = thresholdMap["LOW"] ?: 3.0
        Log.d("HomeViewModel", "Thresholds: HIGH=$thresholdHigh, MILD=$thresholdMild, LOW=$thresholdLow")

        // ── 3. Fetch trigger pool (label → prediction_value) ──
        val triggerPool = db.getAllTriggerPool(accessToken)
        val triggerSeverityMap: Map<String, String> = triggerPool.associate {
            it.label.lowercase() to (it.predictionValue?.uppercase() ?: "NONE")
        }

        // ── 4. Fetch prodrome pool (label → prediction_value) ──
        val prodromePool = db.getAllProdromePool(accessToken)
        val prodromeSeverityMap: Map<String, String> = prodromePool.associate {
            it.label.lowercase() to (it.predictionValue?.uppercase() ?: "NONE")
        }

        // ── 5. Fetch triggers (last 7 days + next 7 days to cover forecast) ──
        val recentTriggers = db.getRecentTriggers(accessToken, daysBack = 7)
        // Also fetch future triggers (up to 7 days ahead)
        val futureTriggers = fetchFutureTriggers(db, accessToken, daysAhead = 7)
        // Deduplicate by ID (the two windows overlap around today)
        val allTriggers = (recentTriggers + futureTriggers).distinctBy { it.id }
        Log.d("HomeViewModel", "Triggers: ${recentTriggers.size} recent + ${futureTriggers.size} future = ${allTriggers.size} unique")

        // ── 6. Fetch prodromes (last 7 days + next 7 days) ──
        val recentProdromes = db.getRecentProdromes(accessToken, daysBack = 7)
        val futureProdromes = fetchFutureProdromes(db, accessToken, daysAhead = 7)
        // Deduplicate by ID
        val allProdromes = (recentProdromes + futureProdromes).distinctBy { it.id }
        Log.d("HomeViewModel", "Prodromes: ${recentProdromes.size} recent + ${futureProdromes.size} future = ${allProdromes.size} unique")

        // ── 7. Build list of all events with their date + severity ──
        data class ScoredEvent(val name: String, val severity: String, val eventDate: LocalDate)

        val events = mutableListOf<ScoredEvent>()

        for (trigger in allTriggers) {
            val label = trigger.type ?: continue
            val severity = triggerSeverityMap[label.lowercase()] ?: "NONE"
            if (severity == "NONE") continue
            val eventDate = parseEventDate(trigger.startAt) ?: continue
            events.add(ScoredEvent(label, severity, eventDate))
        }

        for (prodrome in allProdromes) {
            val label = prodrome.type ?: continue
            val severity = prodromeSeverityMap[label.lowercase()] ?: "NONE"
            if (severity == "NONE") continue
            val eventDate = parseEventDate(prodrome.startAt) ?: continue
            events.add(ScoredEvent(label, severity, eventDate))
        }

        // ── 8. Calculate score for today + next 6 days (7-day forecast) ──
        val gaugeMax = thresholdHigh * 1.2
        val forecastPercents = mutableListOf<Int>()
        val dayRisks = mutableListOf<DayRisk>()

        for (dayOffset in 0 until 7) {
            val perspectiveDate = today.plusDays(dayOffset.toLong())
            var dayScore = 0.0

            data class DayContribution(val name: String, val contribution: Double, val severity: String, val eventDate: LocalDate)
            val dayContributions = mutableListOf<DayContribution>()

            for (event in events) {
                val daysAgo = ChronoUnit.DAYS.between(event.eventDate, perspectiveDate).toInt()
                // Only count events in the past or on the perspective date (0..6)
                if (daysAgo < 0 || daysAgo > 6) continue
                val weights = decayMap[event.severity] ?: continue
                val weight = weights.getOrElse(daysAgo) { 0.0 }
                if (weight > 0.0) {
                    dayScore += weight
                    dayContributions.add(DayContribution(event.name, weight, event.severity, event.eventDate))
                }
            }

            val dayPercent = ((dayScore / gaugeMax) * 100).roundToInt().coerceIn(0, 100)
            forecastPercents.add(dayPercent)

            val dayZone = when {
                dayScore >= thresholdHigh -> RiskZone.HIGH
                dayScore >= thresholdMild -> RiskZone.MILD
                dayScore >= thresholdLow -> RiskZone.LOW
                else -> RiskZone.NONE
            }

            // Build top triggers for this day
            val dayGrouped = dayContributions.groupBy { it.name }
            val dayTopTriggers = dayGrouped.map { (name, items) ->
                val highestSev = when {
                    items.any { it.severity == "HIGH" } -> "HIGH"
                    items.any { it.severity == "MILD" } -> "MILD"
                    else -> "LOW"
                }
                val daysActive = items.map { it.eventDate }.distinct().size
                TriggerScore(
                    name = name,
                    score = items.sumOf { it.contribution }.roundToInt(),
                    severity = highestSev,
                    daysActive = daysActive
                )
            }.sortedByDescending { it.score }.take(5)

            dayRisks.add(DayRisk(
                date = perspectiveDate,
                score = dayScore,
                zone = dayZone,
                percent = dayPercent,
                topTriggers = dayTopTriggers
            ))

            if (dayOffset == 0) {
                Log.d("HomeViewModel", "Today (${perspectiveDate}): score=$dayScore zone=$dayZone")
            } else {
                Log.d("HomeViewModel", "Day +$dayOffset (${perspectiveDate}): score=$dayScore zone=$dayZone")
            }
        }

        val totalScore = dayRisks[0].score
        val zone = dayRisks[0].zone
        val percent = dayRisks[0].percent
        val topTriggers = dayRisks[0].topTriggers
        Log.d("HomeViewModel", "Total risk score: $totalScore")

        // ── 12. Generate recommendation ──
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

        return HomeUiState(
            loading = false,
            riskScore = totalScore,
            riskZone = zone,
            riskPercent = percent,
            triggersAtRisk = topTriggers,
            aiRecommendation = aiTip,
            gaugeMaxScore = gaugeMax,
            forecast = forecastPercents,
            dayRisks = dayRisks
        )
    }

    /** Parse start_at ISO string to LocalDate */
    private fun parseEventDate(startAt: String?): LocalDate? {
        if (startAt.isNullOrBlank()) return null
        return try {
            Instant.parse(startAt).atZone(ZoneId.systemDefault()).toLocalDate()
        } catch (_: Exception) {
            try {
                // Fallback: parse with offset
                java.time.OffsetDateTime.parse(startAt.replace(" ", "T")).toLocalDate()
            } catch (_: Exception) {
                try {
                    LocalDate.parse(startAt.substring(0, 10))
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /** Fetch triggers dated in the future (for forecast) */
    private suspend fun fetchFutureTriggers(
        db: SupabaseDbService,
        accessToken: String,
        daysAhead: Int
    ): List<SupabaseDbService.RecentTriggerRow> {
        // getRecentTriggers looks backward; we need to look forward too.
        // Use referenceDate set to today + daysAhead so the "recent" window covers the future.
        val futureRef = LocalDate.now(ZoneId.systemDefault()).plusDays(daysAhead.toLong())
        return try {
            db.getRecentTriggers(accessToken, daysBack = daysAhead, referenceDate = futureRef.toString())
        } catch (e: Exception) {
            Log.w("HomeViewModel", "fetchFutureTriggers error: ${e.message}")
            emptyList()
        }
    }

    /** Fetch prodromes dated in the future (for forecast) */
    private suspend fun fetchFutureProdromes(
        db: SupabaseDbService,
        accessToken: String,
        daysAhead: Int
    ): List<SupabaseDbService.ProdromeLogRow> {
        val futureRef = LocalDate.now(ZoneId.systemDefault()).plusDays(daysAhead.toLong())
        return try {
            db.getRecentProdromes(accessToken, daysBack = daysAhead, referenceDate = futureRef.toString())
        } catch (e: Exception) {
            Log.w("HomeViewModel", "fetchFutureProdromes error: ${e.message}")
            emptyList()
        }
    }

    fun formatTime(instant: Instant): String {
        val dt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        return dt.format(timeFmt)
    }
}
