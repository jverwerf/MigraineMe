package com.migraineme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    val score: Int // 0..100, higher = more risky
)

data class HomeUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val riskPercent: Int = 0,               // 0..100
    val triggersAtRisk: List<TriggerScore> = emptyList(),
    val aiRecommendation: String = "",
    val recentLogs: List<MigraineLog> = emptyList()
)

class HomeViewModel : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState(loading = true))
    val state: StateFlow<HomeUiState> = _state

    private val timeFmt = DateTimeFormatter.ofPattern("EEE d MMM • HH:mm")

    init {
        // Demo data; replace with Supabase reads soon.
        viewModelScope.launch {
            delay(300) // tiny shimmer time
            val now = Instant.now()
            val logs = listOf(
                MigraineLog(time = now.minusSeconds(2 * 3600), severity = 6, note = "Behind left eye"),
                MigraineLog(time = now.minusSeconds(8 * 3600), severity = 3, note = "Mild aura"),
                MigraineLog(time = now.minusSeconds(26 * 3600), severity = 7, note = "Stressy day"),
            )
            val risk = (logs.take(3).mapIndexed { i, l ->
                val w = when (i) { 0 -> 0.5; 1 -> 0.3; else -> 0.2 }
                w * (l.severity / 10.0)
            }.sum() * 100).roundToInt().coerceIn(10, 95)

            val triggers = listOf(
                TriggerScore("Poor sleep", 82),
                TriggerScore("Screen time", 74),
                TriggerScore("Hydration", 61),
                TriggerScore("Weather", 45),
                TriggerScore("Stress", 38),
            ).sortedByDescending { it.score }.take(3)

            val aiTip = when {
                triggers.firstOrNull()?.name == "Poor sleep" ->
                    "Try a 20–30 min nap and hydrate. Avoid caffeine after 3pm. Wind-down 1h before bed."
                risk >= 70 ->
                    "High risk today: schedule a short outdoor break, drink water now, consider early abortive if symptoms start."
                else ->
                    "Keep regular meals and short screen breaks every 45–60 min to stay in the low-risk zone."
            }

            _state.value = HomeUiState(
                loading = false,
                riskPercent = risk,
                triggersAtRisk = triggers,
                aiRecommendation = aiTip,
                recentLogs = logs
            )
        }
    }

    fun formatTime(instant: Instant): String {
        val dt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        return dt.format(timeFmt)
    }
}
