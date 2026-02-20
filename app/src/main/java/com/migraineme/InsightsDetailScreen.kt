// FILE: InsightsDetailScreen.kt
package com.migraineme

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ======= Filter category colors (matching the card / spider colors) =======

private val FilterCatColors = mapOf(
    "Severity" to Color(0xFFFF7043),
    "Symptom" to Color(0xFFCE93D8),
    "Pain Location" to Color(0xFFFF8A80),
    "Trigger" to Color(0xFFFF8A65),
    "Prodrome" to Color(0xFFFFD54F),
    "Medicine" to Color(0xFF4FC3F7),
    "Relief" to Color(0xFF81C784),
    "Activity" to Color(0xFFBA68C8),
    "Location" to Color(0xFF4DD0E1),
    "Missed Activity" to Color(0xFFEF9A9A)
)

// ======= Ordered categories to match the migraine log flow =======

private val FilterCatOrder = listOf(
    "Severity", "Symptom", "Pain Location", "Trigger", "Prodrome",
    "Medicine", "Relief", "Activity", "Location", "Missed Activity"
)

@Composable
fun InsightsDetailScreen(
    navController: NavController,
    vm: InsightsViewModel = viewModel()
) {
    val owner = LocalContext.current as ViewModelStoreOwner
    val ctx: Context = LocalContext.current.applicationContext
    val authVm: AuthViewModel = viewModel(owner)
    val auth by authVm.state.collectAsState()
    LaunchedEffect(auth.accessToken) {
        auth.accessToken?.takeIf { it.isNotBlank() }?.let { vm.load(ctx, it) }
    }

    val migraines by vm.migraines.collectAsState()
    val allDailyMetrics by vm.allDailyMetrics.collectAsState()
    val linkedItems by vm.selectedLinkedItems.collectAsState()
    val linkedLoading by vm.linkedItemsLoading.collectAsState()
    val scrollState = rememberScrollState()
    val zone = ZoneId.systemDefault()

    //  Filter state 
    val tagIndex by vm.migraineTagIndex.collectAsState()
    val availableTags by vm.availableFilterTags.collectAsState()
    val activeFilters by vm.activeFilters.collectAsState()
    val timeFrame by vm.timeFrame.collectAsState()
    val customRange by vm.customRange.collectAsState()

    //  Filtered migraines 
    val sorted = remember(migraines) { migraines.sortedByDescending { it.start } }
    val filteredSorted = remember(sorted, activeFilters, tagIndex, timeFrame, customRange) {
        val cutoff = when {
            timeFrame == InsightsViewModel.TimeFrame.CUSTOM && customRange != null -> {
                customRange!!.from.atStartOfDay(zone).toInstant()
            }
            timeFrame.days != null -> Instant.now().minus(Duration.ofDays(timeFrame.days!!.toLong()))
            else -> null
        }
        val upperBound = if (timeFrame == InsightsViewModel.TimeFrame.CUSTOM && customRange != null) {
            customRange!!.to.plusDays(1).atStartOfDay(zone).toInstant()
        } else null

        sorted.filter { m ->
            if (cutoff != null && m.start.isBefore(cutoff)) return@filter false
            if (upperBound != null && m.start.isAfter(upperBound)) return@filter false
            if (activeFilters.isEmpty()) true
            else {
                val tags = tagIndex[m.id] ?: emptySet()
                activeFilters.all { it in tags }
            }
        }
    }

    val selIdx by vm.selectedMigraineIndex.collectAsState()
    // Clamp to filtered list
    val clampedIdx = selIdx.coerceIn(0, (filteredSorted.size - 1).coerceAtLeast(0))
    LaunchedEffect(filteredSorted.size, selIdx) {
        if (filteredSorted.isNotEmpty() && selIdx >= filteredSorted.size) vm.selectMigraine(0)
    }
    val sel = filteredSorted.getOrNull(clampedIdx)
    LaunchedEffect(sel?.id) { sel?.id?.let { vm.loadLinkedItems(it) } }

    // ±7 day window
    // Window days (shared with report screen)
    val wBefore by vm.windowDaysBefore.collectAsState()
    val wAfter by vm.windowDaysAfter.collectAsState()
    val wStart = sel?.start?.minus(Duration.ofDays(wBefore))
    val wEnd = (sel?.end ?: sel?.start)?.plus(Duration.ofDays(wAfter))
    val windowDates = remember(wStart, wEnd) {
        if (wStart == null || wEnd == null) emptySet()
        else {
            val f = LocalDate.ofInstant(wStart, zone)
            val t = LocalDate.ofInstant(wEnd, zone)
            generateSequence(f) { it.plusDays(1) }
                .takeWhile { !it.isAfter(t) }
                .map { it.toString() }.toSet()
        }
    }

    val allMissed by vm.allMissedActivities.collectAsState()
    val allActs by vm.allActivities.collectAsState()

    val windowEvents = remember(linkedItems, allMissed, allActs, sel?.id) {
        val actsForMigraine = allActs.filter { it.migraineId == sel?.id }
        val missedForMigraine = allMissed.filter { it.migraineId == sel?.id }
        buildEventMarkers(linkedItems, actsForMigraine, missedForMigraine)
    }

    val templateMap by vm.labelToMetricMap.collectAsState()
    val autoSelectedKeys = remember(windowEvents, templateMap) {
        windowEvents.filter { it.isAutomated }
            .mapNotNull { ev: EventMarker -> vm.labelToMetricKey(ev.name) }
            .toSet()
    }

    val available = remember(allDailyMetrics, windowDates) {
        AllMetricDefs.filter { d ->
            allDailyMetrics[d.key]?.any { it.date in windowDates } == true
        }
    }

    val userToggledKeys by vm.userToggledMetrics.collectAsState()
    val userDisabledKeys by vm.userDisabledMetrics.collectAsState()
    val enabledKeys = (autoSelectedKeys - userDisabledKeys) + userToggledKeys

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

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            //  Back arrow 
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back", tint = Color.White)
                }
            }

            // ========== 1. FILTER CARD (collapsible) ==========
            FilterCard(
                availableTags = availableTags,
                activeFilters = activeFilters,
                timeFrame = timeFrame,
                customRange = customRange,
                totalCount = sorted.size,
                filteredCount = filteredSorted.size,
                onToggle = { vm.toggleFilter(it) },
                onTimeFrame = { vm.setTimeFrame(it) },
                onCustomRange = { from, to -> vm.setCustomRange(from, to) },
                onClear = { vm.clearFilters() }
            )

            // ========== 2. GRAPH CARD ==========
            HeroCard {
                Text("Migraine Timeline", color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))

                if (filteredSorted.isEmpty()) {
                    Text(
                        if (sorted.isEmpty()) "No migraines logged yet"
                        else "No migraines match filters",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    return@HeroCard
                }

                // Migraine selector (navigates filtered list)
                DetailMigraineSelector(filteredSorted, clampedIdx, sel,
                    onPrev = { if (clampedIdx < filteredSorted.size - 1) vm.selectMigraine(clampedIdx + 1) },
                    onNext = { if (clampedIdx > 0) vm.selectMigraine(clampedIdx - 1) })

                if (linkedLoading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(16.dp),
                            strokeWidth = 2.dp, color = AppTheme.AccentPurple)
                    }
                } else {
                    val catCounts = remember(windowEvents) {
                        windowEvents.groupBy { it.category }.mapValues { it.value.size }
                    }
                    if (catCounts.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            catCounts.forEach { (c, n) ->
                                DetailChip(n, c, EventCategoryColors[c] ?: AppTheme.AccentPurple)
                            }
                        }
                    }
                }

                // Graph
                InsightsTimelineGraph(
                    windowMigs, windowEvents, enabledSeries,
                    wStart, wEnd, sel?.start,
                    Modifier.fillMaxWidth().height(360.dp))

                Text("Window around migraine", color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

                // Window adjustment
                WindowDaysControl(wBefore, wAfter, onChanged = { b, a -> vm.setWindowDays(b, a) })
            }

            // ========== 3. METRIC PICKER (below graph) ==========
            if (available.isNotEmpty()) {
                BaseCard {
                    Text("Overlay Metrics", color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(2.dp))
                    Text("Tap to toggle metric lines on the graph. * = auto-detected from triggers.",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))

                    val groups = available.groupBy { it.group }
                    groups.forEach { (group, defs) ->
                        Text(group, color = AppTheme.SubtleTextColor.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            defs.forEach { d ->
                                val isAuto = d.key in autoSelectedKeys
                                val isEnabled = d.key in enabledKeys
                                DetailToggle(
                                    label = d.label,
                                    color = d.color,
                                    active = isEnabled,
                                    isAutoSelected = isAuto,
                                    onClick = { vm.toggleMetric(d.key, isEnabled) }
                                )
                            }
                        }
                    }
                }
            }

        }
    }
}

// ======= Filter card =======

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterCard(
    availableTags: Map<String, List<String>>,
    activeFilters: Set<InsightsViewModel.FilterTag>,
    timeFrame: InsightsViewModel.TimeFrame,
    customRange: InsightsViewModel.CustomRange?,
    totalCount: Int,
    filteredCount: Int,
    onToggle: (InsightsViewModel.FilterTag) -> Unit,
    onTimeFrame: (InsightsViewModel.TimeFrame) -> Unit,
    onCustomRange: (java.time.LocalDate, java.time.LocalDate) -> Unit,
    onClear: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val hasFilters = activeFilters.isNotEmpty() || timeFrame != InsightsViewModel.TimeFrame.ALL
    val activeCount = activeFilters.size + if (timeFrame != InsightsViewModel.TimeFrame.ALL) 1 else 0

    // Custom date picker state
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }
    var customFrom by remember(customRange) {
        mutableStateOf(customRange?.from ?: java.time.LocalDate.now().minusDays(30))
    }
    var customTo by remember(customRange) {
        mutableStateOf(customRange?.to ?: java.time.LocalDate.now())
    }

    BaseCard {
        // Header row – always visible, tappable
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.FilterList,
                contentDescription = "Filter",
                tint = if (hasFilters) AppTheme.AccentPurple else AppTheme.SubtleTextColor,
                modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (hasFilters) "Filters ($activeCount)"
                    else "Filter Migraines",
                    color = if (hasFilters) Color.White else AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                if (hasFilters) {
                    Text("$filteredCount of $totalCount migraines",
                        color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            if (hasFilters) {
                Text("Clear",
                    color = AppTheme.AccentPurple.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onClear() }
                        .padding(horizontal = 8.dp, vertical = 4.dp))
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp
                else Icons.Filled.KeyboardArrowDown,
                contentDescription = "Expand",
                tint = AppTheme.SubtleTextColor,
                modifier = Modifier.size(20.dp))
        }

        // Active filter chips (shown when collapsed + has filters)
        if (!expanded && hasFilters) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (timeFrame != InsightsViewModel.TimeFrame.ALL) {
                    val timeLabel = if (timeFrame == InsightsViewModel.TimeFrame.CUSTOM && customRange != null) {
                        val df = java.time.format.DateTimeFormatter.ofPattern("MMM d")
                        "${df.format(customRange.from)} — ${df.format(customRange.to)}"
                    } else timeFrame.label
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(AppTheme.AccentPurple.copy(alpha = 0.2f))
                            .clickable { onTimeFrame(InsightsViewModel.TimeFrame.ALL) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(timeLabel, color = AppTheme.AccentPurple,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium))
                        Spacer(Modifier.width(4.dp))
                        Text("✕", color = AppTheme.AccentPurple.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                activeFilters.sortedBy { it.key }.forEach { tag ->
                    val color = FilterCatColors[tag.category] ?: AppTheme.AccentPurple
                    ActiveFilterChip(tag, color, onToggle)
                }
            }
        }

        // Expanded: timeframe + all categories
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(Modifier.padding(top = 8.dp)) {
                // Timeframe selector
                Text("Time Range", color = AppTheme.AccentPurple.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(bottom = 2.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InsightsViewModel.TimeFrame.entries.forEach { tf ->
                        val isActive = tf == timeFrame
                        FilterOptionChip(
                            label = tf.label,
                            color = AppTheme.AccentPurple,
                            active = isActive,
                            onClick = {
                                if (tf == InsightsViewModel.TimeFrame.CUSTOM) {
                                    showFromPicker = true
                                } else {
                                    onTimeFrame(tf)
                                }
                            }
                        )
                    }
                }

                // Custom date range row (shown when custom is active or being set)
                AnimatedVisibility(visible = timeFrame == InsightsViewModel.TimeFrame.CUSTOM) {
                    val df = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // From button
                        Column(Modifier.weight(1f)) {
                            Text("From", color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelSmall)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .clickable { showFromPicker = true }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(df.format(customFrom), color = Color.White,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("—", color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp))
                        // To button
                        Column(Modifier.weight(1f)) {
                            Text("To", color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelSmall)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .clickable { showToPicker = true }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(df.format(customTo), color = Color.White,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                // Tag categories
                val orderedCats = FilterCatOrder.filter { it in availableTags }
                orderedCats.forEach { cat ->
                    val labels = availableTags[cat] ?: return@forEach
                    val color = FilterCatColors[cat] ?: AppTheme.AccentPurple
                    Text(cat, color = color.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        labels.forEach { label ->
                            val tag = InsightsViewModel.FilterTag(cat, label)
                            val isActive = tag in activeFilters
                            FilterOptionChip(label, color, isActive) { onToggle(tag) }
                        }
                    }
                }
            }
        }
    }

    //  Date picker dialogs 
    if (showFromPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = customFrom.atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showFromPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        customFrom = java.time.Instant.ofEpochMilli(ms)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        if (customFrom.isAfter(customTo)) customTo = customFrom
                        onCustomRange(customFrom, customTo)
                    }
                    showFromPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showFromPicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = state)
        }
    }

    if (showToPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = customTo.atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showToPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        customTo = java.time.Instant.ofEpochMilli(ms)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        if (customTo.isBefore(customFrom)) customFrom = customTo
                        onCustomRange(customFrom, customTo)
                    }
                    showToPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showToPicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun ActiveFilterChip(
    tag: InsightsViewModel.FilterTag, color: Color,
    onRemove: (InsightsViewModel.FilterTag) -> Unit
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.2f))
            .clickable { onRemove(tag) }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(tag.label, color = color,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(4.dp))
        Text("✕", color = color.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FilterOptionChip(
    label: String, color: Color, active: Boolean, onClick: () -> Unit
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
        Box(Modifier.size(6.dp).clip(CircleShape)
            .background(if (active) color else color.copy(alpha = 0.3f)))
        Spacer(Modifier.width(4.dp))
        Text(label, color = tc,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ======= Shared helper to build event markers from linked items =======

internal fun buildEventMarkers(
    linkedItems: SupabaseDbService.MigraineLinkedItems,
    extraActivities: List<SupabaseDbService.ActivityLogRow> = emptyList(),
    missedActivities: List<SupabaseDbService.MissedActivityLogRow> = emptyList()
): List<EventMarker> {
    val ev = mutableListOf<EventMarker>()
    linkedItems.triggers.forEach { row ->
        parseInstant(row.startAt)?.let { t ->
            ev += EventMarker(t, null, row.type ?: "Trigger", "Trigger", row.notes,
                EventCategoryColors["Trigger"]!!, row.source == "system")
        }
    }
    linkedItems.prodromes.forEach { row ->
        parseInstant(row.startAt)?.let { t ->
            ev += EventMarker(t, null, row.type ?: "Prodrome", "Prodrome", row.notes,
                EventCategoryColors["Prodrome"]!!, row.source == "system")
        }
    }
    linkedItems.medicines.forEach { row ->
        parseInstant(row.startAt)?.let { t ->
            val d = listOfNotNull(
                row.amount,
                row.reliefScale?.takeIf { it != "NONE" }?.let { "Relief: $it" }
            ).joinToString(" • ")
            ev += EventMarker(t, null, row.name ?: "Medicine", "Medicine",
                d.ifEmpty { null }, EventCategoryColors["Medicine"]!!, row.source == "system")
        }
    }
    linkedItems.reliefs.forEach { row ->
        parseInstant(row.startAt)?.let { s ->
            val e = row.endAt?.let { parseInstant(it) }
                ?: row.durationMinutes?.let { s.plusSeconds(it.toLong() * 60) }
            val d = row.reliefScale?.takeIf { it != "NONE" }?.let { "Relief: $it" }
            ev += EventMarker(s, e, row.type ?: "Relief", "Relief", d,
                EventCategoryColors["Relief"]!!, row.source == "system")
        }
    }
    // Merge linked activities + extra activities (from allActivities), deduplicate by ID
    val linkedIds = linkedItems.activities.map { it.id }.toSet()
    val allActs = linkedItems.activities + extraActivities.filter { it.id !in linkedIds }
    allActs.forEach { row ->
        parseInstant(row.startAt)?.let { t ->
            ev += EventMarker(t, null, row.type ?: "Activity", "Activity", null,
                EventCategoryColors["Activity"]!!, row.source == "system")
        }
    }
    linkedItems.locations.forEach { row ->
        parseInstant(row.startAt)?.let { t ->
            ev += EventMarker(t, null, row.type ?: "Location", "Location", null,
                EventCategoryColors["Location"]!!, row.source == "system")
        }
    }
    missedActivities.forEach { row ->
        parseInstant(row.startAt)?.let { t ->
            ev += EventMarker(t, null, row.type ?: "Missed Activity", "Missed Activity", null,
                EventCategoryColors["Missed Activity"]!!, row.source == "system")
        }
    }
    return ev.sortedBy { it.at }
}

internal fun parseInstant(iso: String?): Instant? =
    iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

// ======= Private composables =======

@Composable
private fun DetailMigraineSelector(
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
private fun DetailChip(n: Int, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text("$n", color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.width(2.dp))
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DetailToggle(
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



