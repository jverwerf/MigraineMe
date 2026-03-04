// app/src/main/java/com/migraineme/CommunityGraphViewModel.kt
package com.migraineme

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * Mirror of InsightsViewModel but reads from a JSON attachment
 * instead of Supabase. Used to render graphs on community comments.
 */
class CommunityGraphViewModel : ViewModel() {

    private val _migraines = MutableStateFlow<List<MigraineSpan>>(emptyList())
    val migraines: StateFlow<List<MigraineSpan>> = _migraines

    private val _events = MutableStateFlow<List<EventMarker>>(emptyList())
    val events: StateFlow<List<EventMarker>> = _events

    private val _metricSeries = MutableStateFlow<List<MetricSeries>>(emptyList())
    val metricSeries: StateFlow<List<MetricSeries>> = _metricSeries

    private val _windowStart = MutableStateFlow<Instant?>(null)
    val windowStart: StateFlow<Instant?> = _windowStart

    private val _windowEnd = MutableStateFlow<Instant?>(null)
    val windowEnd: StateFlow<Instant?> = _windowEnd

    // ── Risk graph data ──
    private val _riskDays = MutableStateFlow<List<RiskDayPoint>>(emptyList())
    val riskDays: StateFlow<List<RiskDayPoint>> = _riskDays

    private val _isRiskGraph = MutableStateFlow(false)
    val isRiskGraph: StateFlow<Boolean> = _isRiskGraph

    /**
     * Parse JSON attachment into the same types InsightsTimelineGraph uses.
     * Detects "type": "risk" for risk graph attachments.
     * Default (no type field) = insights timeline graph:
     * { start, end, migraines[], events[], metrics[] }
     */
    fun loadFromJson(attachment: JsonObject) {
        val type = attachment["type"]?.jsonPrimitive?.content

        if (type == "risk") {
            loadRiskFromJson(attachment)
            return
        }

        _isRiskGraph.value = false
        _windowStart.value = attachment["start"]?.jsonPrimitive?.content
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        _windowEnd.value = attachment["end"]?.jsonPrimitive?.content
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }

        _migraines.value = (attachment["migraines"]?.jsonArray ?: emptyList()).mapNotNull { el ->
            val obj = el.jsonObject
            val s = obj["start"]?.jsonPrimitive?.content
                ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@mapNotNull null
            val e = obj["end"]?.jsonPrimitive?.content
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val sev = obj["severity"]?.jsonPrimitive?.content?.toIntOrNull()
            val label = obj["label"]?.jsonPrimitive?.content
            MigraineSpan(s, e, sev, label)
        }

        _events.value = (attachment["events"]?.jsonArray ?: emptyList()).mapNotNull { el ->
            val obj = el.jsonObject
            val at = obj["at"]?.jsonPrimitive?.content
                ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@mapNotNull null
            val endAt = obj["end"]?.jsonPrimitive?.content
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val name = obj["name"]?.jsonPrimitive?.content ?: ""
            val cat = obj["category"]?.jsonPrimitive?.content ?: "Trigger"
            val color = obj["color"]?.jsonPrimitive?.content?.let { parseHex(it) }
                ?: (EventCategoryColors[cat] ?: Color(0xFFFF8A65))
            EventMarker(at, endAt, name, cat, null, color)
        }

        _metricSeries.value = (attachment["metrics"]?.jsonArray ?: emptyList()).mapNotNull { el ->
            val obj = el.jsonObject
            val label = obj["label"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val unit = obj["unit"]?.jsonPrimitive?.content ?: ""
            val color = obj["color"]?.jsonPrimitive?.content?.let { parseHex(it) }
                ?: Color(0xFF90CAF9)
            val points = (obj["points"]?.jsonArray ?: emptyList()).mapNotNull { pt ->
                val po = pt.jsonObject
                val date = po["date"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val value = po["value"]?.jsonPrimitive?.double ?: return@mapNotNull null
                DailyMetricPoint(date, value)
            }
            if (points.isEmpty()) return@mapNotNull null
            MetricSeries(label.lowercase().replace(" ", "_"), label, unit, color, points)
        }
    }

    private fun loadRiskFromJson(attachment: JsonObject) {
        _isRiskGraph.value = true
        _riskDays.value = (attachment["days"]?.jsonArray ?: emptyList()).mapNotNull { el ->
            val obj = el.jsonObject
            val date = obj["date"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val score = obj["score"]?.jsonPrimitive?.double?.toFloat() ?: return@mapNotNull null
            val zone = obj["zone"]?.jsonPrimitive?.content ?: "LOW"
            RiskDayPoint(date, score, zone)
        }
        // Parse optional metric overlay lines
        _metricSeries.value = (attachment["metrics"]?.jsonArray ?: emptyList()).mapNotNull { el ->
            val obj = el.jsonObject
            val label = obj["label"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val color = obj["color"]?.jsonPrimitive?.content?.let { parseHex(it) } ?: Color(0xFF90CAF9)
            val points = (obj["points"]?.jsonArray ?: emptyList()).mapNotNull { pt ->
                val po = pt.jsonObject
                val date = po["date"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val value = po["value"]?.jsonPrimitive?.double ?: return@mapNotNull null
                DailyMetricPoint(date, value)
            }
            if (points.isEmpty()) return@mapNotNull null
            val key = obj["key"]?.jsonPrimitive?.content ?: label.lowercase().replace(" ", "_")
            MetricSeries(key, label, "", color, points)
        }
    }

    private fun parseHex(hex: String): Color? {
        return try {
            val clean = hex.removePrefix("#")
            val argb = when (clean.length) {
                6 -> (0xFF000000 or clean.toLong(16)).toInt()
                8 -> clean.toLong(16).toInt()
                else -> return null
            }
            Color(argb)
        } catch (_: Exception) { null }
    }
}
