// app/src/main/java/com/migraineme/GraphAttachmentPicker.kt
package com.migraineme

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun GraphAttachmentPicker(
    accessToken: String,
    insightsVm: InsightsViewModel,
    onAttach: (JsonObject) -> Unit,
    onDismiss: () -> Unit
) {
    // ── Premium check ──
    val premiumState by PremiumManager.state.collectAsState()

    // ── Exactly the same as InsightsScreen: load data ──
    val ctx: Context = LocalContext.current.applicationContext
    LaunchedEffect(accessToken) {
        if (accessToken.isNotBlank()) insightsVm.load(ctx, accessToken)
    }

    val migraines by insightsVm.migraines.collectAsState()
    val allDailyMetrics by insightsVm.allDailyMetrics.collectAsState()
    val linkedItems by insightsVm.selectedLinkedItems.collectAsState()
    val linkedLoading by insightsVm.linkedItemsLoading.collectAsState()
    val zone = ZoneId.systemDefault()
    val sorted = remember(migraines) { migraines.sortedByDescending { it.start } }
    val selIdx by insightsVm.selectedMigraineIndex.collectAsState()
    LaunchedEffect(sorted.size) { if (selIdx >= sorted.size && sorted.isNotEmpty()) insightsVm.selectMigraine(0) }
    val sel = sorted.getOrNull(selIdx)
    LaunchedEffect(sel?.id) { sel?.id?.let { insightsVm.loadLinkedItems(it) } }

    // Window days
    val wBefore by insightsVm.windowDaysBefore.collectAsState()
    val wAfter by insightsVm.windowDaysAfter.collectAsState()
    val wStart = sel?.start?.minus(Duration.ofDays(wBefore))
    val wEnd = (sel?.end ?: sel?.start)?.plus(Duration.ofDays(wAfter))
    val windowDates = remember(wStart, wEnd) {
        if (wStart == null || wEnd == null) emptySet()
        else {
            val f = LocalDate.ofInstant(wStart, zone)
            val t = LocalDate.ofInstant(wEnd, zone)
            generateSequence(f) { it.plusDays(1) }.takeWhile { !it.isAfter(t) }.map { it.toString() }.toSet()
        }
    }

    val allMissed by insightsVm.allMissedActivities.collectAsState()
    val allActs by insightsVm.allActivities.collectAsState()

    // Events from linked items
    val windowEvents = remember(linkedItems, allMissed, allActs, sel?.id) {
        val actsForMigraine = allActs.filter { it.migraineId == sel?.id }
        val missedForMigraine = allMissed.filter { it.migraineId == sel?.id }
        buildEventMarkers(linkedItems, actsForMigraine, missedForMigraine)
    }

    // Auto-select metrics
    val templateMap by insightsVm.labelToMetricMap.collectAsState()
    val autoSelectedKeys = remember(windowEvents, templateMap) {
        windowEvents
            .filter { it.isAutomated }
            .mapNotNull { ev -> insightsVm.labelToMetricKey(ev.name) }
            .toSet()
    }

    val available = remember(allDailyMetrics, windowDates) {
        AllMetricDefs.filter { d ->
            allDailyMetrics[d.key]?.any { it.date in windowDates } == true
        }
    }

    val userToggledKeys by insightsVm.userToggledMetrics.collectAsState()
    val userDisabledKeysTop by insightsVm.userDisabledMetrics.collectAsState()
    val enabledKeys = (autoSelectedKeys - userDisabledKeysTop) + userToggledKeys

    val enabledSeries = remember(available, enabledKeys, allDailyMetrics, windowDates) {
        available.filter { it.key in enabledKeys }.map { d ->
            MetricSeries(d.key, d.label, d.unit, d.color,
                allDailyMetrics[d.key]!!
                    .filter { it.date in windowDates }
                    .map { DailyMetricPoint(it.date, it.value) })
        }
    }

    val windowMigs = remember(migraines, wStart, wEnd) {
        if (wStart == null || wEnd == null) listOfNotNull(sel)
        else migraines.filter { m ->
            val e = m.end ?: m.start
            !m.start.isAfter(wEnd) && !e.isBefore(wStart)
        }
    }

    // ── UI ──
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
        border = AppTheme.BaseCardBorder
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Attach graph", color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = AppTheme.SubtleTextColor, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            if (!premiumState.isPremium) {
                PremiumGate(
                    message = "Share your migraine graphs",
                    subtitle = "Attach detailed graphs to community posts",
                    onUpgrade = onDismiss
                ) {}
            } else if (sorted.isEmpty()) {
                Text("No migraines logged yet", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
            } else {
                // ── Migraine selector — exact same as InsightsScreen ──
                PickerMigraineSelector(sorted, selIdx, sel,
                    onPrev = { if (selIdx < sorted.size - 1) insightsVm.selectMigraine(selIdx + 1) },
                    onNext = { if (selIdx > 0) insightsVm.selectMigraine(selIdx - 1) }
                )

                Spacer(Modifier.height(8.dp))

                if (linkedLoading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppTheme.AccentPurple)
                    }
                } else {
                    // Event category chips
                    val catCounts = remember(windowEvents) {
                        windowEvents.groupBy { it.category }.mapValues { it.value.size }
                    }
                    if (catCounts.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            catCounts.forEach { (c, n) ->
                                PickerChip(n, c, EventCategoryColors[c] ?: AppTheme.AccentPurple)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ── Graph — exact same InsightsTimelineGraph ──
                InsightsTimelineGraph(
                    windowMigs, windowEvents, enabledSeries,
                    wStart, wEnd, sel?.start,
                    Modifier.fillMaxWidth().height(180.dp)
                )

                Spacer(Modifier.height(4.dp))

                // Window days control
                WindowDaysControl(wBefore, wAfter, onChanged = { b, a -> insightsVm.setWindowDays(b, a) })

                // Metric picker chips
                if (available.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Overlay Metrics", color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(2.dp))
                    Text("Tap to toggle. \u26A1 = auto-detected.",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(6.dp))

                    val userDisabledKeys by insightsVm.userDisabledMetrics.collectAsState()
                    val effectiveEnabled = (autoSelectedKeys - userDisabledKeys) + userToggledKeys

                    val groups = available.groupBy { it.group }
                    groups.forEach { (group, defs) ->
                        Text(group, color = AppTheme.SubtleTextColor.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            defs.forEach { d ->
                                val isAuto = d.key in autoSelectedKeys
                                val isEnabled = d.key in effectiveEnabled
                                PickerMetricToggle(
                                    label = d.label,
                                    color = d.color,
                                    active = isEnabled,
                                    isAutoSelected = isAuto,
                                    onClick = { insightsVm.toggleMetric(d.key, isEnabled) }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
            }

            // ── Attach button (premium only) ──
            if (premiumState.isPremium) {
                Button(
                    onClick = {
                        if (wStart != null && wEnd != null) {
                            val json = serializeGraphToJson(wStart, wEnd, windowMigs, windowEvents, enabledSeries)
                            onAttach(json)
                        }
                    },
                    enabled = wStart != null && wEnd != null && (windowMigs.isNotEmpty() || windowEvents.isNotEmpty() || enabledSeries.isNotEmpty()),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) {
                    Text("Attach to comment", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

// ── Migraine selector (same as InsightsScreen's private one) ──

@Composable
private fun PickerMigraineSelector(
    sorted: List<MigraineSpan>, idx: Int, sel: MigraineSpan?,
    onPrev: () -> Unit, onNext: () -> Unit
) {
    val z = ZoneId.systemDefault()
    val df = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(z)
    val tf = DateTimeFormatter.ofPattern("h:mm a").withZone(z)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev, enabled = idx < sorted.size - 1) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Older",
                tint = if (idx < sorted.size - 1) AppTheme.AccentPurple
                else AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                modifier = Modifier.size(22.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
            Text(sel?.label ?: "Migraine", color = Color.White,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sel != null) {
                Text(df.format(sel.start) + " • " + tf.format(sel.start),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                val e = sel.end
                if (e != null) {
                    val d = Duration.between(sel.start, e)
                    val hStr = if (d.toHours() > 0) "${d.toHours()}h " else ""
                    Text("$hStr${d.minusHours(d.toHours()).toMinutes()}m • Severity: ${sel.severity ?: "—"}/10",
                        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                } else {
                    Text("Severity: ${sel.severity ?: "—"}/10",
                        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text("${idx + 1} of ${sorted.size}", color = AppTheme.AccentPurple,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
        }
        IconButton(onClick = onNext, enabled = idx > 0) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Newer",
                tint = if (idx > 0) AppTheme.AccentPurple
                else AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun PickerChip(n: Int, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text("$n", color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.width(2.dp))
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
    }
}

// ── Serialise visible graph to JSON for storage on comment ──

private fun colorToHex(color: Color): String {
    val argb = color.hashCode()
    return "#${(argb and 0xFFFFFF).toString(16).padStart(6, '0')}"
}

private fun serializeGraphToJson(
    windowStart: java.time.Instant,
    windowEnd: java.time.Instant,
    migraines: List<MigraineSpan>,
    events: List<EventMarker>,
    metricSeries: List<MetricSeries>
): JsonObject = buildJsonObject {
    put("start", windowStart.toString())
    put("end", windowEnd.toString())

    put("migraines", buildJsonArray {
        for (m in migraines) {
            add(buildJsonObject {
                put("start", m.start.toString())
                m.end?.let { put("end", it.toString()) }
                m.severity?.let { put("severity", it) }
                m.label?.let { put("label", it) }
            })
        }
    })

    put("events", buildJsonArray {
        for (ev in events) {
            add(buildJsonObject {
                put("at", ev.at.toString())
                ev.endAt?.let { put("end", it.toString()) }
                put("name", ev.name)
                put("category", ev.category)
                put("color", colorToHex(ev.color))
            })
        }
    })

    put("metrics", buildJsonArray {
        for (s in metricSeries) {
            add(buildJsonObject {
                put("label", s.label)
                put("unit", s.unit)
                put("color", colorToHex(s.color))
                put("points", buildJsonArray {
                    for (pt in s.points) {
                        add(buildJsonObject {
                            put("date", pt.date)
                            put("value", pt.value)
                        })
                    }
                })
            })
        }
    })
}

@Composable
private fun PickerMetricToggle(
    label: String, color: Color, active: Boolean,
    isAutoSelected: Boolean, onClick: () -> Unit
) {
    val bg = if (active) color.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.04f)
    val tc = if (active) color else AppTheme.SubtleTextColor.copy(alpha = 0.5f)
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isAutoSelected) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFFFD54F)))
        } else {
            Box(Modifier.size(6.dp).clip(CircleShape)
                .background(if (active) color else color.copy(alpha = 0.3f)))
        }
        Spacer(Modifier.width(4.dp))
        Text(label, color = tc,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium))
    }
}
