package com.migraineme

// Journal tab — reference implementation of the approved redesign.
//
// One shared filter/search state drives three views of the same entries:
//   Stream   — entries grouped by day, newest first, compact boxed rows.
//   Days     — one summary card per day, tap to jump into the Stream.
//   Calendar — month grid with per-day category dots, attack flags, and the
//              selected day's entries underneath.
// Everything is STATIC — no animations, by product rule (motion is a symptom
// trigger for this audience).

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit

// ── Categories ──

/** Attack-day flag color (Days stripe, Calendar ring, "· attack" text). */
private val AttackRed = Color(0xFFE57373)

/** One journal category: chip label, per-row tag word, and its color. */
data class JournalCategory(
    val key: String,
    val chipLabel: String,   // English key for the outlined multi-toggle chip
    val tagLabel: String,    // English key for the small per-row tag word
    val color: Color
)

/** Display order everywhere: chips, count lines, calendar dots. */
val JOURNAL_CATEGORIES: List<JournalCategory> = listOf(
    JournalCategory("migraine", "Migraines", "migraine", Color(0xFFF48FB1)),
    JournalCategory("trigger", "Triggers", "trigger", Color(0xFFFFB74D)),
    JournalCategory("prodrome", "Prodromes", "prodrome", Color(0xFFCE93D8)),
    JournalCategory("medicine", "Medicines", "medicine", Color(0xFF4FC3F7)),
    JournalCategory("relief", "Reliefs", "relief", Color(0xFF81C784)),
    JournalCategory("activity", "Activities", "activity", Color(0xFFFF8A65)),
    JournalCategory("location", "Locations", "location", Color(0xFF78909C)),
    JournalCategory("missed", "Missed activities", "missed", Color(0xFFFF7043)),
)

private val CATEGORY_BY_KEY = JOURNAL_CATEGORIES.associateBy { it.key }

fun journalEventCategory(ev: JournalEvent): JournalCategory = when (ev) {
    is JournalEvent.Migraine -> CATEGORY_BY_KEY.getValue("migraine")
    is JournalEvent.Trigger -> CATEGORY_BY_KEY.getValue("trigger")
    is JournalEvent.Prodrome -> CATEGORY_BY_KEY.getValue("prodrome")
    is JournalEvent.Medicine -> CATEGORY_BY_KEY.getValue("medicine")
    is JournalEvent.Relief -> CATEGORY_BY_KEY.getValue("relief")
    is JournalEvent.Activity -> CATEGORY_BY_KEY.getValue("activity")
    is JournalEvent.Location -> CATEGORY_BY_KEY.getValue("location")
    is JournalEvent.MissedActivity -> CATEGORY_BY_KEY.getValue("missed")
}

// ── Screen ──

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JournalScreen(navController: NavHostController, authVm: AuthViewModel, vm: LogViewModel) {
    val authState by authVm.state.collectAsState()
    val journal by vm.journal.collectAsState()
    val journalLoading by vm.journalLoading.collectAsState()
    val journalLoadingMore by vm.journalLoadingMore.collectAsState()
    val journalHasMore by vm.journalHasMore.collectAsState()
    val triggerLabelMap by vm.triggerLabelMap.collectAsState()
    val premiumState by PremiumManager.state.collectAsState()

    // ── Shared filter/search state (drives all three views) ──
    var viewMode by rememberSaveable { mutableStateOf("Stream") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // Multi-toggle category chips; empty = all categories active.
    var selectedCategoriesCsv by rememberSaveable { mutableStateOf("") }
    val selectedCategories = remember(selectedCategoriesCsv) {
        selectedCategoriesCsv.split(",").filter { it.isNotBlank() }.toSet()
    }
    var selectedType by rememberSaveable { mutableStateOf("All") }
    var needsAttentionOnly by rememberSaveable { mutableStateOf(false) }
    var selectedSourceFilter by rememberSaveable { mutableStateOf("All") }
    var selectedTimeframe by rememberSaveable { mutableStateOf("All") }
    var customFromDate by rememberSaveable { mutableStateOf<Long?>(null) }
    var customToDate by rememberSaveable { mutableStateOf<Long?>(null) }
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }
    // Day the Stream is focused on after tapping a Days card; null = all days.
    var streamDayFocus by rememberSaveable { mutableStateOf<String?>(null) }
    // Calendar state.
    var calendarMonth by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var calendarSelected by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }

    val now = Instant.now()
    val effectiveTimeframe = if (premiumState.isPremium) selectedTimeframe else "7d"

    val timeframeCutoff = when (effectiveTimeframe) {
        "7d" -> now.minus(7, ChronoUnit.DAYS)
        "30d" -> now.minus(30, ChronoUnit.DAYS)
        "90d" -> now.minus(90, ChronoUnit.DAYS)
        "Custom" -> customFromDate?.let { Instant.ofEpochMilli(it) } ?: Instant.EPOCH
        else -> Instant.EPOCH
    }
    val timeframeCeiling = when (effectiveTimeframe) {
        "Custom" -> customToDate?.let { Instant.ofEpochMilli(it).plus(1, ChronoUnit.DAYS) } ?: now.plus(1, ChronoUnit.DAYS)
        else -> now.plus(1, ChronoUnit.DAYS)
    }

    // Loader filter: only what can be pushed down without starving another view
    // of data. Type is always "All" — category chips are multi-select, and the
    // Days/Calendar attack flags need migraine rows even while a category chip
    // filters the rows on screen. Category/type/search settle in memory below.
    val loaderFilter = remember(selectedSourceFilter, effectiveTimeframe, customFromDate, customToDate) {
        JournalFilter(
            type = "All",
            source = selectedSourceFilter,
            sinceIso = if (effectiveTimeframe == "All") null else timeframeCutoff.toString(),
            untilIso = if (effectiveTimeframe == "Custom") timeframeCeiling.toString() else null
        )
    }
    LaunchedEffect(authState.accessToken, loaderFilter) {
        val token = authState.accessToken
        if (!token.isNullOrBlank()) vm.applyJournalFilter(token, loaderFilter)
    }

    if (journalLoading) {
        // Top-aligned, not centred: the onboarding tour parks its card over the
        // lower half of the screen, and a centred spinner sat exactly behind it,
        // so a still-loading Journal read as a blank page during the tour step.
        Box(Modifier.fillMaxSize().padding(top = 56.dp), contentAlignment = Alignment.TopCenter) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = AppTheme.AccentPurple)
                Spacer(Modifier.height(8.dp))
                Text(t("Loading journal…"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }

    // ── Client-side filter pipeline ──
    val zone = ZoneId.systemDefault()

    val timeframed = remember(journal, effectiveTimeframe, timeframeCutoff, timeframeCeiling) {
        if (effectiveTimeframe == "All") journal
        else journal.filter { ev ->
            val at = journalEventStartAt(ev)?.let { parseJournalInstant(it) } ?: return@filter false
            at.isAfter(timeframeCutoff) && at.isBefore(timeframeCeiling)
        }
    }

    // Attack days: migraines within the timeframe, regardless of the other
    // filters — the flag is day-level truth, not a filtered row.
    val attackDays: Map<LocalDate, Int?> = remember(timeframed) {
        timeframed.filterIsInstance<JournalEvent.Migraine>()
            .mapNotNull { ev -> journalEventDay(ev, zone)?.let { it to ev.row.severity } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, sevs) -> sevs.filterNotNull().maxOrNull() }
    }

    val presentCategories = remember(timeframed) {
        val keys = timeframed.map { journalEventCategory(it).key }.toSet()
        JOURNAL_CATEGORIES.filter { it.key in keys }
    }

    // Cascading filters: the chips narrow what the popups offer. The type
    // popup only lists types of the active categories, and a chosen type is
    // dropped again when its category is toggled off.
    val categoryScoped = remember(timeframed, selectedCategories) {
        if (selectedCategories.isEmpty()) timeframed
        else timeframed.filter { journalEventCategory(it).key in selectedCategories }
    }
    val distinctTypes = remember(categoryScoped, triggerLabelMap) {
        categoryScoped.map { entryRowInfo(it, triggerLabelMap).title }.distinct().sorted()
    }
    LaunchedEffect(distinctTypes) {
        if (selectedType != "All" && selectedType !in distinctTypes) {
            selectedType = "All"
        }
    }
    // Count for the leading "Needs attention" chip — scoped like the type
    // popup, so it narrows with the category chips.
    val needsAttentionCount = remember(categoryScoped) { categoryScoped.count { journalNeedsAttention(it) } }

    val visible = remember(categoryScoped, selectedType, needsAttentionOnly, selectedSourceFilter, searchQuery, triggerLabelMap) {
        val q = searchQuery.trim().lowercase()
        categoryScoped
            .filter { !needsAttentionOnly || journalNeedsAttention(it) }
            .filter { ev ->
                when (selectedType) {
                    "All" -> true
                    else -> entryRowInfo(ev, triggerLabelMap).title == selectedType
                }
            }
            .filter { ev ->
                when (selectedSourceFilter) {
                    "Manual" -> journalEventSource(ev) == "manual"
                    "Auto" -> journalEventSource(ev) != "manual"
                    else -> true
                }
            }
            .filter { ev ->
                if (q.isEmpty()) true else {
                    val info = entryRowInfo(ev, triggerLabelMap)
                    val hay = listOfNotNull(
                        info.title, info.sub, journalEventNotes(ev),
                        tSync(info.cat.chipLabel), tSync(info.cat.tagLabel)
                    ).joinToString(" ").lowercase()
                    hay.contains(q)
                }
            }
    }

    // Newest day first; entries with no timestamp surface in a "No date" group
    // on top so a broken entry stays reachable (it is also what "Needs
    // attention" flags).
    val dayGroups: List<Pair<LocalDate?, List<JournalEvent>>> = remember(visible) {
        visible.groupBy { journalEventDay(it, zone) }
            .toList()
            .sortedWith(compareByDescending { it.first ?: LocalDate.MAX })
            .map { (day, evs) -> day to evs.sortedByDescending { journalEventStartAt(it) ?: "" } }
    }

    // Shared delete handler — the exact flows the old per-card buttons ran.
    val doDelete: (JournalEvent) -> Unit = { event ->
        val token = authState.accessToken
        if (!token.isNullOrBlank()) when (event) {
            is JournalEvent.Migraine -> vm.removeMigraine(token, event.row.id)
            is JournalEvent.Trigger -> vm.removeTrigger(token, event.row.id)
            is JournalEvent.Medicine -> vm.removeMedicine(token, event.row.id)
            is JournalEvent.Relief -> vm.removeRelief(token, event.row.id)
            is JournalEvent.Prodrome -> vm.removeProdrome(token, event.row.id)
            is JournalEvent.Location -> vm.removeLocation(token, event.row.id)
            is JournalEvent.Activity -> vm.removeActivity(token, event.row.id)
            is JournalEvent.MissedActivity -> vm.removeMissedActivity(token, event.row.id)
        }
    }
    val doNavigate: (String) -> Unit = { route -> navController.navigate(route) }

    // ── Pagination ──
    val listState = rememberLazyListState()
    // Each view starts at its top — carrying the Stream's scroll offset into
    // Days/Calendar strands the user mid-list. Instant jump, not a scroll.
    LaunchedEffect(viewMode, streamDayFocus) { listState.scrollToItem(0) }
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 &&
                (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, journalHasMore, journalLoadingMore, viewMode) {
        val token = authState.accessToken
        if (nearEnd && journalHasMore && !journalLoadingMore && !token.isNullOrBlank()) {
            vm.loadMoreJournal(token)
        }
    }
    // Calendar months older than the loaded pages: keep pulling history until
    // the displayed month is covered (bounded by the timeframe window).
    val shownMonth = remember(calendarMonth) { runCatching { YearMonth.parse(calendarMonth) }.getOrElse { YearMonth.now() } }
    LaunchedEffect(viewMode, shownMonth, journal.size, journalHasMore, journalLoadingMore) {
        val token = authState.accessToken
        if (viewMode == "Calendar" && journalHasMore && !journalLoadingMore && !token.isNullOrBlank()) {
            val oldest = journal.mapNotNull { journalEventStartAt(it)?.let { s -> parseJournalInstant(s) } }.minOrNull()
            val monthStart = shownMonth.atDay(1).atStartOfDay(zone).toInstant()
            if (oldest == null || oldest.isAfter(monthStart)) vm.loadMoreJournal(token)
        }
    }

    // ── Layout ──
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header: title + 3-segment view switch.
        item(key = "header") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    t("Journal"),
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                JournalViewSwitch(viewMode) { viewMode = it; if (it != "Stream") streamDayFocus = null }
            }
        }

        // Toolbar: search + category chips + popup chips (premium), or the
        // 7-day pill + upgrade prompt (free), same gate as before.
        item(key = "toolbar") {
            if (premiumState.isPremium) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    JournalSearchField(searchQuery) { searchQuery = it }
                    if (presentCategories.isNotEmpty() || needsAttentionCount > 0 || needsAttentionOnly) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Incomplete entries lead the toolbar: their own
                            // amber toggle, first, with a live count.
                            if (needsAttentionCount > 0 || needsAttentionOnly) {
                                val amber = Color(0xFFFFB74D)
                                Text(
                                    "${t("Needs attention")} · $needsAttentionCount",
                                    color = amber.copy(alpha = if (needsAttentionOnly) 1f else 0.85f),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (needsAttentionOnly) amber.copy(alpha = 0.18f) else Color.Transparent)
                                        .border(1.dp, amber.copy(alpha = if (needsAttentionOnly) 1f else 0.55f), RoundedCornerShape(14.dp))
                                        .clickable { needsAttentionOnly = !needsAttentionOnly }
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                            // Uniform accent chips on purpose — per-category
                            // rainbow chips read as noise for this audience.
                            // Category colors stay on the row tags instead.
                            presentCategories.forEach { cat ->
                                val active = selectedCategories.isEmpty() || cat.key in selectedCategories
                                Text(
                                    t(cat.chipLabel),
                                    color = if (active) AppTheme.AccentPurple else AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .border(
                                            1.dp,
                                            if (active) AppTheme.AccentPurple else AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                                            RoundedCornerShape(14.dp)
                                        )
                                        .clickable {
                                            val next = if (cat.key in selectedCategories) selectedCategories - cat.key
                                            else selectedCategories + cat.key
                                            selectedCategoriesCsv = next.joinToString(",")
                                        }
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Type popup: every distinct logged type within the
                            // active categories (cascading narrowing).
                            JournalPopupChip(
                                display = if (selectedType == "All") t("All types") else selectedType,
                                options = listOf("All" to t("All types")) + distinctTypes.map { it to it },
                                current = selectedType,
                                onSelect = { selectedType = it }
                            )
                            JournalPopupChip(
                                display = if (selectedSourceFilter == "All") t("All sources") else t(selectedSourceFilter),
                                options = listOf("All" to t("All sources"), "Manual" to t("Manual"), "Auto" to t("Auto")),
                                current = selectedSourceFilter,
                                onSelect = { selectedSourceFilter = it }
                            )
                            val customLabel = if (selectedTimeframe == "Custom" && customFromDate != null && customToDate != null) {
                                val fmt = DateTimeFormatter.ofPattern("dd MMM", appLocale())
                                "${LocalDate.ofEpochDay(customFromDate!! / 86400000).format(fmt)} → ${LocalDate.ofEpochDay(customToDate!! / 86400000).format(fmt)}"
                            } else t("Custom range…")
                            JournalPopupChip(
                                display = when (selectedTimeframe) {
                                    "All" -> t("All time")
                                    "Custom" -> customLabel
                                    else -> selectedTimeframe
                                },
                                options = listOf(
                                    "7d" to "7d", "30d" to "30d", "90d" to "90d",
                                    "All" to t("All time"), "Custom" to t("Custom range…")
                                ),
                                current = selectedTimeframe,
                                onSelect = {
                                    selectedTimeframe = it
                                    if (it == "Custom") showFromPicker = true
                                }
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            t("%s entries", visible.size),
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            } else {
                BaseCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(AppTheme.AccentPurple.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(t("Last 7 days"), color = AppTheme.AccentPurple, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(t("%s entries", visible.size), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                    // The 7-day window above is just a fact about what is shown.
                    // This line is a sales pitch, so it waits until we know the
                    // user is actually on the free tier.
                    if (premiumState.access == PremiumAccess.NOT_ENTITLED) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { navController.navigate(Routes.PAYWALL) },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.FilterList, contentDescription = null, tint = AppTheme.AccentPurple, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("Upgrade to filter & search your full history"), color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // Stream focus chip (set by tapping a Days card).
        val focusDay = streamDayFocus?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (viewMode == "Stream" && focusDay != null) {
            item(key = "focus") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        journalDayTitle(focusDay),
                        color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(AppTheme.AccentPurple.copy(alpha = 0.18f))
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                    Icon(
                        Icons.Filled.Close, contentDescription = t("Show all days"),
                        tint = AppTheme.SubtleTextColor,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable { streamDayFocus = null }
                            .padding(3.dp)
                    )
                }
            }
        }

        val streamGroups = if (viewMode == "Stream" && focusDay != null)
            dayGroups.filter { it.first == focusDay } else dayGroups

        when (viewMode) {
            "Stream" -> {
                // Incomplete entries lead the page: a pinned amber section
                // above the day groups, gone when nothing is flagged (or when
                // the stream is already filtered down to flagged entries).
                val flagged = visible.filter { journalNeedsAttention(it) }
                if (focusDay == null && !needsAttentionOnly && flagged.isNotEmpty()) {
                    item(key = "attention") {
                        val amber = Color(0xFFFFB74D)
                        Column {
                            Text(
                                "${t("Needs attention")} · ${flagged.size}".uppercase(appLocale()),
                                color = amber,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
                            )
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = AppTheme.BaseCardShape,
                                colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
                                elevation = CardDefaults.cardElevation(0.dp),
                                border = AppTheme.BaseCardBorder
                            ) {
                                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                    Box(
                                        Modifier
                                            .padding(start = 5.dp, top = 10.dp, bottom = 10.dp)
                                            .width(3.dp)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(amber)
                                    )
                                    Column(
                                        Modifier.weight(1f).padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        flagged.forEach { ev ->
                                            JournalEntryRow(ev, triggerLabelMap, withMenu = true, doNavigate, doDelete)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (streamGroups.isEmpty()) item(key = "empty") { JournalEmptyState() }
                streamGroups.forEach { (day, events) ->
                    item(key = "day-${day ?: "none"}") {
                        Column {
                            Text(
                                journalDayHeader(day, events.size),
                                color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
                            )
                            BaseCard(contentPadding = 8.dp) {
                                events.forEach { ev ->
                                    JournalEntryRow(ev, triggerLabelMap, withMenu = true, doNavigate, doDelete)
                                }
                            }
                        }
                    }
                }
            }
            "Days" -> {
                if (streamGroups.isEmpty()) item(key = "empty") { JournalEmptyState() }
                streamGroups.forEach { (day, events) ->
                    item(key = "days-${day ?: "none"}") {
                        JournalDaySummaryCard(
                            day = day,
                            events = events,
                            attackSeverity = if (day != null && attackDays.containsKey(day)) attackDays[day] to true else null to false,
                            labels = triggerLabelMap,
                            onOpen = {
                                day?.let { streamDayFocus = it.toString(); viewMode = "Stream" }
                            }
                        )
                    }
                }
            }
            "Calendar" -> {
                item(key = "calendar") {
                    JournalCalendarCard(
                        month = shownMonth,
                        onMonth = { calendarMonth = it.toString() },
                        events = visible,
                        attackDays = attackDays,
                        selected = runCatching { LocalDate.parse(calendarSelected) }.getOrNull(),
                        onSelect = { calendarSelected = it.toString() },
                        zone = zone
                    )
                }
                val selDay = runCatching { LocalDate.parse(calendarSelected) }.getOrNull()
                if (selDay != null) {
                    val dayEvents = dayGroups.firstOrNull { it.first == selDay }?.second ?: emptyList()
                    item(key = "cal-day") {
                        Column {
                            Text(
                                journalDayHeader(selDay, dayEvents.size),
                                color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
                            )
                            if (dayEvents.isEmpty()) {
                                BaseCard {
                                    Text(t("No entries this day"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                                }
                            } else {
                                BaseCard(contentPadding = 8.dp) {
                                    dayEvents.forEach { ev ->
                                        JournalEntryRow(ev, triggerLabelMap, withMenu = true, doNavigate, doDelete)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Older pages arrive underneath; everything above stays put.
        if (journalLoadingMore) {
            item(key = "more") {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppTheme.AccentPurple, modifier = Modifier.size(24.dp))
                }
            }
        }
    }

    // ── Custom range pickers (the journal's existing flow, unchanged) ──
    val dpColors = DatePickerDefaults.colors(
        containerColor = Color(0xFF1E0A2E),
        titleContentColor = AppTheme.TitleColor,
        headlineContentColor = Color.White,
        weekdayContentColor = AppTheme.SubtleTextColor,
        subheadContentColor = AppTheme.SubtleTextColor,
        yearContentColor = Color.White,
        currentYearContentColor = AppTheme.AccentPurple,
        selectedYearContainerColor = AppTheme.AccentPurple,
        selectedYearContentColor = Color.White,
        dayContentColor = Color.White,
        selectedDayContainerColor = AppTheme.AccentPurple,
        selectedDayContentColor = Color.White,
        todayContentColor = AppTheme.AccentPurple,
        todayDateBorderColor = AppTheme.AccentPurple,
        navigationContentColor = Color.White,
    )
    if (showFromPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = customFromDate)
        DatePickerDialog(
            onDismissRequest = { showFromPicker = false },
            confirmButton = { TextButton(onClick = { customFromDate = state.selectedDateMillis; showFromPicker = false; showToPicker = true }) { Text(t("Next"), color = AppTheme.AccentPurple) } },
            dismissButton = { TextButton(onClick = { showFromPicker = false }) { Text(t("Cancel"), color = AppTheme.SubtleTextColor) } },
            colors = DatePickerDefaults.colors(containerColor = Color(0xFF1E0A2E))
        ) { DatePicker(state = state, colors = dpColors, title = { Text(t("From date"), color = AppTheme.TitleColor, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) }) }
    }
    if (showToPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = customToDate)
        DatePickerDialog(
            onDismissRequest = { showToPicker = false },
            confirmButton = { TextButton(onClick = { customToDate = state.selectedDateMillis; showToPicker = false }) { Text(t("Done"), color = AppTheme.AccentPurple) } },
            dismissButton = { TextButton(onClick = { showToPicker = false }) { Text(t("Cancel"), color = AppTheme.SubtleTextColor) } },
            colors = DatePickerDefaults.colors(containerColor = Color(0xFF1E0A2E))
        ) { DatePicker(state = state, colors = dpColors, title = { Text(t("To date"), color = AppTheme.TitleColor, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) }) }
    }
}

// ── Shared helpers (also used by the feed loader) ──

// Shared with the feed loader, which has to answer the same two questions to
// decide whether a fetched row is one the screen would actually draw.
fun journalEventSource(ev: JournalEvent): String? = when (ev) {
    is JournalEvent.Migraine -> "manual"
    is JournalEvent.Trigger -> ev.row.source
    is JournalEvent.Medicine -> ev.row.source
    is JournalEvent.Relief -> ev.row.source
    is JournalEvent.Prodrome -> ev.row.source
    is JournalEvent.Location -> ev.row.source
    is JournalEvent.Activity -> ev.row.source
    is JournalEvent.MissedActivity -> "manual"
}

fun journalNeedsAttention(event: JournalEvent): Boolean = missingFields(event).isNotEmpty()

private fun missingFields(event: JournalEvent): List<String> = when (event) {
    is JournalEvent.Migraine -> buildList {
        if (event.row.startAt.isNullOrBlank()) add("start time")
        if (event.row.endAt.isNullOrBlank()) add("end time")
        if (event.row.severity == null) add("severity")
    }
    is JournalEvent.Trigger -> buildList {
        if (event.row.startAt.isNullOrBlank()) add("time")
    }
    is JournalEvent.Medicine -> buildList {
        if (event.row.amount.isNullOrBlank()) add("amount")
        if (event.row.startAt.isNullOrBlank()) add("time")
    }
    is JournalEvent.Relief -> buildList {
        if (event.row.startAt.isNullOrBlank()) add("start time")
        if (event.row.endAt.isNullOrBlank() || event.row.endAt == event.row.startAt) add("end time")
    }
    is JournalEvent.Prodrome -> buildList {
        if (event.row.startAt.isNullOrBlank()) add("time")
    }
    is JournalEvent.Activity -> buildList {
        if (event.row.startAt.isNullOrBlank()) add("time")
    }
    is JournalEvent.Location -> emptyList()
    is JournalEvent.MissedActivity -> emptyList()
}

private fun journalEventNotes(ev: JournalEvent): String? = when (ev) {
    is JournalEvent.Migraine -> ev.row.notes
    is JournalEvent.Trigger -> ev.row.notes
    is JournalEvent.Medicine -> ev.row.notes
    is JournalEvent.Relief -> ev.row.notes
    is JournalEvent.Prodrome -> ev.row.notes
    is JournalEvent.Location -> ev.row.notes
    is JournalEvent.Activity -> ev.row.notes
    is JournalEvent.MissedActivity -> ev.row.notes
}

/** Timestamps arrive both offset-style and Z-style; accept either. */
private fun parseJournalInstant(iso: String): Instant? =
    runCatching { Instant.parse(iso) }.getOrElse {
        runCatching { ZonedDateTime.parse(iso).toInstant() }.getOrNull()
    }

private fun journalEventDay(ev: JournalEvent, zone: ZoneId): LocalDate? =
    journalEventStartAt(ev)?.let { parseJournalInstant(it) }?.atZone(zone)?.toLocalDate()

private fun timeLabel(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String =
    iso?.let { parseJournalInstant(it) }
        ?.atZone(zone)?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "–"

private fun prettyType(s: String?): String =
    s?.replace("_", " ")?.replaceFirstChar { it.uppercase() } ?: "-"

private fun durationLabel(startIso: String?, endIso: String?): String? {
    val s = startIso?.let { parseJournalInstant(it) } ?: return null
    val e = endIso?.let { parseJournalInstant(it) } ?: return null
    val mins = Duration.between(s, e).toMinutes()
    if (mins <= 0) return null
    val h = mins / 60; val m = mins % 60
    return when {
        h == 0L -> "${m}m"
        m == 0L -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

/** "Today" / "Thu 14 Aug", in the app language. */
private fun journalDayTitle(day: LocalDate?): String {
    if (day == null) return tSync("No date")
    if (day == LocalDate.now()) return tSync("Today")
    return day.format(DateTimeFormatter.ofPattern("EEE d MMM", appLocale()))
}

/** Uppercase stream group header: "TODAY · 3 ENTRIES". */
private fun journalDayHeader(day: LocalDate?, count: Int): String {
    val entries = if (count == 1) tSync("1 entry") else tSync("%s entries", count)
    return "${journalDayTitle(day)} · $entries".uppercase(appLocale())
}

// ── Row model ──

private data class EntryRowInfo(
    val title: String,
    val sub: String?,
    val cat: JournalCategory,
    val timeIso: String?,
    val needsAttention: Boolean,
    val editRoute: String?,
    val canDelete: Boolean,
    val iconLabel: String?,
)

/**
 * The one-line shape of an entry: name, a single sub-note
 * (dose / duration / severity / auto-detection, whichever exists), category
 * and where its existing edit flow lives. The rich per-field breakdown moved
 * into those edit screens; the row stays compact by design.
 */
private fun entryRowInfo(ev: JournalEvent, labels: Map<String, String>): EntryRowInfo = when (ev) {
    is JournalEvent.Migraine -> {
        // Severity, end time and the rest render as full detail lines under
        // the row (rich mode) — the sub-note only carries the at-a-glance bits
        // that aren't in those lines.
        val missing = missingFields(ev)
        val sub = buildList {
            ev.row.severity?.let { add(tSync("Sev %s/10", it)) }
            durationLabel(ev.row.startAt, ev.row.endAt)?.let { add(it) }
            if (missing.isNotEmpty()) add(tSync("missing %s", missing.joinToString(", ")))
        }.joinToString(" · ").ifBlank { ev.row.notes }
        EntryRowInfo(
            title = tSync("Migraine"),
            sub = sub,
            cat = journalEventCategory(ev),
            timeIso = ev.row.startAt,
            needsAttention = missing.isNotEmpty(),
            editRoute = "${Routes.EDIT_MIGRAINE}/${ev.row.id}",
            canDelete = true,
            iconLabel = ev.row.type ?: "migraine"
        )
    }
    is JournalEvent.Trigger -> {
        val isMenstruation = ev.row.type == "menstruation" && ev.row.source == "system"
        EntryRowInfo(
            title = ev.row.type?.let { labels[it] ?: prettyType(it) } ?: "-",
            sub = when {
                ev.row.source == "system" -> tSync("Auto-detected")
                !ev.row.notes.isNullOrBlank() -> ev.row.notes
                else -> null
            },
            cat = journalEventCategory(ev),
            timeIso = ev.row.startAt,
            needsAttention = journalNeedsAttention(ev),
            // The system menstruation trigger has no edit/delete, as before.
            editRoute = if (isMenstruation) null else "${Routes.EDIT_TRIGGER}/${ev.row.id}",
            canDelete = !isMenstruation,
            iconLabel = ev.row.type
        )
    }
    is JournalEvent.Medicine -> EntryRowInfo(
        title = ev.row.name ?: "-",
        sub = ev.row.amount?.takeIf { it.isNotBlank() }
            ?: ev.row.doseValue?.let { v -> "${if (v % 1.0 == 0.0) v.toInt() else v} ${ev.row.doseUnit ?: ""}".trim() }
            ?: ev.row.notes,
        cat = journalEventCategory(ev),
        timeIso = ev.row.startAt,
        needsAttention = journalNeedsAttention(ev),
        editRoute = "${Routes.EDIT_MEDICINE}/${ev.row.id}",
        canDelete = true,
        iconLabel = ev.row.name
    )
    is JournalEvent.Relief -> EntryRowInfo(
        title = prettyType(ev.row.type),
        sub = durationLabel(ev.row.startAt, ev.row.endAt)
            ?: ev.row.durationMinutes?.takeIf { it > 0 }?.let { "${it}m" }
            ?: ev.row.notes,
        cat = journalEventCategory(ev),
        timeIso = ev.row.startAt,
        needsAttention = journalNeedsAttention(ev),
        editRoute = "${Routes.EDIT_RELIEF}/${ev.row.id}",
        canDelete = true,
        iconLabel = ev.row.type
    )
    is JournalEvent.Prodrome -> EntryRowInfo(
        title = prettyType(ev.row.type),
        sub = if (ev.row.source == "system") tSync("Auto-detected") else ev.row.notes,
        cat = journalEventCategory(ev),
        timeIso = ev.row.startAt,
        needsAttention = journalNeedsAttention(ev),
        editRoute = "${Routes.EDIT_PRODROME}/${ev.row.id}",
        canDelete = true,
        iconLabel = ev.row.type
    )
    is JournalEvent.Location -> EntryRowInfo(
        title = prettyType(ev.row.type),
        sub = if (ev.row.source == "system") tSync("Auto-detected") else ev.row.notes,
        cat = journalEventCategory(ev),
        timeIso = ev.row.startAt,
        needsAttention = false,
        editRoute = "${Routes.EDIT_LOCATION}/${ev.row.id}",
        canDelete = true,
        iconLabel = ev.row.type
    )
    is JournalEvent.Activity -> EntryRowInfo(
        title = prettyType(ev.row.type),
        sub = durationLabel(ev.row.startAt, ev.row.endAt)
            ?: (if (ev.row.source == "system") tSync("Auto-detected") else ev.row.notes),
        cat = journalEventCategory(ev),
        timeIso = ev.row.startAt,
        needsAttention = journalNeedsAttention(ev),
        editRoute = "${Routes.EDIT_ACTIVITY}/${ev.row.id}",
        canDelete = true,
        iconLabel = ev.row.type
    )
    is JournalEvent.MissedActivity -> EntryRowInfo(
        title = prettyType(ev.row.type),
        sub = ev.row.notes,
        cat = journalEventCategory(ev),
        timeIso = ev.row.startAt,
        needsAttention = false,
        editRoute = null, // missed activities never had an edit flow
        canDelete = true,
        iconLabel = ev.row.type
    )
}

// ── UI components ──

/** 3-segment Stream / Days / Calendar switch. */
@Composable
private fun JournalViewSwitch(mode: String, onMode: (String) -> Unit) {
    val segments = listOf(
        Triple("Stream", Icons.Outlined.ViewAgenda, t("Stream")),
        Triple("Days", Icons.Outlined.GridView, t("Days")),
        Triple("Calendar", Icons.Outlined.CalendarMonth, t("Calendar")),
    )
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        segments.forEach { (key, icon, label) ->
            val sel = mode == key
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (sel) AppTheme.AccentPurple.copy(alpha = 0.25f) else Color.Transparent)
                    .clickable { onMode(key) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    icon, contentDescription = label,
                    tint = if (sel) AppTheme.AccentPurple else AppTheme.SubtleTextColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** Compact search field over type/name/notes, case-insensitive. */
@Composable
private fun JournalSearchField(query: String, onQuery: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, AppTheme.SubtleTextColor.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = AppTheme.SubtleTextColor, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(t("Search entries…"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                cursorBrush = SolidColor(AppTheme.AccentPurple),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                Icons.Filled.Close, contentDescription = t("Clear search"),
                tint = AppTheme.SubtleTextColor,
                modifier = Modifier.size(16.dp).clickable { onQuery("") }
            )
        }
    }
}

/** Anchored popup chip, same look as the Insights SortChipMenu. */
@Composable
private fun JournalPopupChip(
    display: String,
    options: List<Pair<String, String>>,
    current: String,
    onSelect: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            "$display ▾",
            color = AppTheme.AccentPurple,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { open = true }
                .background(Color.White.copy(alpha = 0.05f))
                .padding(horizontal = 9.dp, vertical = 5.dp)
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(Color(0xFF1E0A2E))
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            color = if (value == current) AppTheme.AccentPurple else Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    onClick = { onSelect(value); open = false }
                )
            }
        }
    }
}

/**
 * One compact boxed entry row: blobbed Brainy icon + name over a faint
 * sub-note, time + colored category tag on the right, and (optionally) a ⋯
 * menu carrying the existing edit/delete flows.
 */
@Composable
private fun JournalEntryRow(
    ev: JournalEvent,
    labels: Map<String, String>,
    withMenu: Boolean,
    onNavigate: (String) -> Unit,
    onDelete: (JournalEvent) -> Unit,
    // Rich mode: attacks keep the old journal's full breakdown under the row.
    // Off in the Days previews, where the card is a summary by design.
    rich: Boolean = withMenu,
) {
    val info = remember(ev, labels) { entryRowInfo(ev, labels) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val hasMenu = withMenu && (info.editRoute != null || info.canDelete)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrainyRowIcon(info.iconLabel, category = info.cat.key, size = 18.dp)
        Column(Modifier.weight(1f)) {
            Text(
                info.title,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (!info.sub.isNullOrBlank()) {
                Text(
                    info.sub,
                    color = AppTheme.SubtleTextColor.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (info.needsAttention) {
                    Icon(Icons.Filled.Error, contentDescription = t("Needs attention"), tint = Color(0xFFFFB74D), modifier = Modifier.size(12.dp))
                }
                Text(timeLabel(info.timeIso), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(info.cat.color))
                Spacer(Modifier.width(4.dp))
                Text(
                    t(info.cat.tagLabel),
                    color = info.cat.color,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        if (hasMenu) {
            Spacer(Modifier.width(2.dp))
            Box {
                Icon(
                    Icons.Filled.MoreHoriz,
                    contentDescription = t("Entry actions"),
                    tint = AppTheme.SubtleTextColor,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .clickable { menuOpen = true }
                        .padding(4.dp)
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.background(Color(0xFF1E0A2E))
                ) {
                    if (info.editRoute != null) {
                        DropdownMenuItem(
                            text = { Text(t("Edit"), color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall) },
                            onClick = { menuOpen = false; onNavigate(info.editRoute) }
                        )
                    }
                    if (info.canDelete) {
                        DropdownMenuItem(
                            text = { Text(t("Delete"), color = AttackRed, style = MaterialTheme.typography.bodySmall) },
                            onClick = { menuOpen = false; confirmDelete = true }
                        )
                    }
                }
            }
        }
    }
    if (rich && ev is JournalEvent.Migraine) MigraineDetailLines(ev, labels)
    }

    if (confirmDelete) {
        DeleteDialog(
            onDismiss = { confirmDelete = false },
            onConfirm = { onDelete(ev); confirmDelete = false }
        )
    }
}

/**
 * The old journal's full attack breakdown, kept data-complete under the
 * compact row: type, symptom intensities, end time, pain timeline, aura,
 * notes and every linked item list.
 */
@Composable
private fun MigraineDetailLines(ev: JournalEvent.Migraine, labels: Map<String, String>) {
    Spacer(Modifier.height(2.dp))
    val missing = missingFields(ev)
    if (missing.isNotEmpty()) JournalDetail("⚠ Missing", missing.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } })
    ev.row.type?.let { JournalDetail("Type", it.replaceFirstChar { c -> c.uppercase() }) }
    // Only the symptoms the user actually rated — Type above already lists them all.
    val ratedSymptoms = ev.linked.postdromes.filter { it.severity != null }
    if (ratedSymptoms.isNotEmpty()) {
        JournalDetail(
            "Symptom intensity",
            ratedSymptoms.joinToString(", ") { "${it.type ?: "?"} (${it.severity!!.lowercase()})" }
        )
    }
    if (!ev.row.endAt.isNullOrBlank()) JournalDetail("End", formatTimestamp(ev.row.endAt!!))
    if (ev.linked.painPoints.isNotEmpty()) {
        // Timestamped pain timeline: rows sharing start_at are one entry
        // (same moment/severity, N locations).
        ev.linked.painPoints.groupBy { it.startAt }.toSortedMap().entries.forEachIndexed { i, (startAt, rows) ->
            val sev = rows.mapNotNull { it.severity }.maxOrNull()
            val locs = rows.joinToString(", ") { ALL_PAIN_POINTS_MAP[it.locationId] ?: it.locationId }
            JournalDetail(
                if (i == 0) "Pain" else "Then",
                "${formatTimestamp(startAt)}${sev?.let { " • $it/10" } ?: ""} • $locs"
            )
        }
    } else if (ev.row.painLocations?.isNotEmpty() == true) {
        JournalDetail("Pain location", ev.row.painLocations!!.joinToString(", ") { ALL_PAIN_POINTS_MAP[it] ?: it.replaceFirstChar { c -> c.uppercase() } })
    }
    if (ev.row.auraLocations?.isNotEmpty() == true) {
        JournalDetail("Aura", ev.row.auraLocations!!.joinToString(", ") { AuraZones.label(it) })
    }
    ev.row.auraDurationMinutes?.let { JournalDetail("Aura duration", formatAuraDuration(it)) }
    if (!ev.row.notes.isNullOrBlank()) JournalDetail("Notes", ev.row.notes!!)
    val linked = ev.linked
    if (linked.triggers.isNotEmpty()) JournalDetail("Triggers", linked.triggers.mapNotNull { it.type }.joinToString(", ") { labels[it] ?: it.replace("_", " ").replaceFirstChar { c -> c.uppercase() } })
    if (linked.medicines.isNotEmpty()) JournalDetail("Medicines", linked.medicines.mapNotNull { it.name }.joinToString(", "))
    if (linked.reliefs.isNotEmpty()) JournalDetail("Reliefs", linked.reliefs.mapNotNull { it.type }.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } })
    if (linked.prodromes.isNotEmpty()) JournalDetail("Prodromes", linked.prodromes.mapNotNull { it.type }.joinToString(", ") { it.replace("_", " ").replaceFirstChar { c -> c.uppercase() } })
    if (linked.activities.isNotEmpty()) JournalDetail("Activities", linked.activities.mapNotNull { it.type }.joinToString(", ") { it.replace("_", " ").replaceFirstChar { c -> c.uppercase() } })
    if (linked.locations.isNotEmpty()) JournalDetail("Locations", linked.locations.mapNotNull { it.type }.joinToString(", ") { it.replace("_", " ").replaceFirstChar { c -> c.uppercase() } })
}

@Composable
private fun JournalDetail(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(t(label), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.38f))
        Text(value, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(0.62f), textAlign = TextAlign.End)
    }
}

private fun formatTimestamp(iso: String): String = try {
    ZonedDateTime.parse(iso).format(DateTimeFormatter.ofPattern("d MMM, HH:mm", appLocale()))
} catch (_: Exception) { iso.take(16).replace("T", " ") }

@Composable
private fun JournalEmptyState() {
    BaseCard {
        Text(t("No entries found"), color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(4.dp))
        Text(t("Start logging migraines, triggers, medicines, and reliefs to see them here."), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
    }
}

private fun categoryCountLabel(key: String, n: Int): String = when (key) {
    "migraine" -> if (n == 1) tSync("%s migraine", n) else tSync("%s migraines", n)
    "trigger" -> if (n == 1) tSync("%s trigger", n) else tSync("%s triggers", n)
    "prodrome" -> if (n == 1) tSync("%s prodrome", n) else tSync("%s prodromes", n)
    "medicine" -> if (n == 1) tSync("%s medicine", n) else tSync("%s medicines", n)
    "relief" -> if (n == 1) tSync("%s relief", n) else tSync("%s reliefs", n)
    "activity" -> if (n == 1) tSync("%s activity", n) else tSync("%s activities", n)
    "location" -> if (n == 1) tSync("%s location", n) else tSync("%s locations", n)
    else -> if (n == 1) tSync("%s missed activity", n) else tSync("%s missed activities", n)
}

/** Days view: one summary card per day; tap jumps to the Stream on that day. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JournalDaySummaryCard(
    day: LocalDate?,
    events: List<JournalEvent>,
    attackSeverity: Pair<Int?, Boolean>, // (max severity, isAttackDay)
    labels: Map<String, String>,
    onOpen: () -> Unit,
) {
    val (sev, isAttack) = attackSeverity
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = AppTheme.BaseCardShape,
        colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
        elevation = CardDefaults.cardElevation(0.dp),
        border = AppTheme.BaseCardBorder
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            if (isAttack) {
                // Inset attack stripe.
                Box(
                    Modifier
                        .padding(start = 5.dp, top = 10.dp, bottom = 10.dp)
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(AttackRed)
                )
            }
            Column(Modifier.weight(1f).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Day blob: same soft gradient shape as the Brainy row icons.
                    Box(
                        modifier = Modifier
                            .size(width = 30.dp, height = 28.dp)
                            .background(
                                brush = Brush.linearGradient(listOf(Color(0x57CE93D8), Color(0x24B388FF))),
                                shape = RoundedCornerShape(
                                    topStartPercent = 46, topEndPercent = 54,
                                    bottomEndPercent = 42, bottomStartPercent = 58
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            day?.dayOfMonth?.toString() ?: "?",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                journalDayTitle(day),
                                color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            if (isAttack) {
                                Text(
                                    " · ${t("attack")}" + (sev?.let { " · ${t("sev %s", it)}" } ?: ""),
                                    color = AttackRed,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }
                        // Per-category counts, colored.
                        val counts = JOURNAL_CATEGORIES.mapNotNull { cat ->
                            val n = events.count { journalEventCategory(it).key == cat.key }
                            if (n > 0) cat to n else null
                        }
                        FlowRow {
                            counts.forEachIndexed { i, (cat, n) ->
                                if (i > 0) Text(" · ", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                                Text(
                                    categoryCountLabel(cat.key, n),
                                    color = cat.color.copy(alpha = 0.9f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
                events.take(3).forEach { ev ->
                    JournalEntryRow(ev, labels, withMenu = false, onNavigate = {}, onDelete = {})
                }
                val extra = events.size - 3
                if (extra > 0) {
                    Text(
                        t("+%s more", extra),
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}

/** Calendar view: Mon-first month grid with category dots and attack flags. */
@Composable
private fun JournalCalendarCard(
    month: YearMonth,
    onMonth: (YearMonth) -> Unit,
    events: List<JournalEvent>,
    attackDays: Map<LocalDate, Int?>,
    selected: LocalDate?,
    onSelect: (LocalDate) -> Unit,
    zone: ZoneId,
) {
    // Filters apply to the dots too: build day → present category colors from
    // the already-filtered entries.
    val dotColors: Map<LocalDate, List<Color>> = remember(events) {
        events.mapNotNull { ev -> journalEventDay(ev, zone)?.let { it to journalEventCategory(ev) } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, cats) ->
                JOURNAL_CATEGORIES.filter { c -> cats.any { it.key == c.key } }.map { it.color }.take(3)
            }
    }

    BaseCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.ChevronLeft, contentDescription = t("Previous month"),
                tint = AppTheme.SubtleTextColor,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable { onMonth(month.minusMonths(1)) }
                    .padding(2.dp)
            )
            Text(
                month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", appLocale()))
                    .replaceFirstChar { it.uppercase() },
                color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Filled.ChevronRight, contentDescription = t("Next month"),
                tint = AppTheme.SubtleTextColor,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable { onMonth(month.plusMonths(1)) }
                    .padding(2.dp)
            )
        }
        Spacer(Modifier.height(2.dp))
        // Weekday header, Monday first.
        Row(Modifier.fillMaxWidth()) {
            (0..6).forEach { i ->
                val dow = DayOfWeek.of(i + 1)
                Text(
                    dow.getDisplayName(TextStyle.SHORT, appLocale()),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        val lead = month.atDay(1).dayOfWeek.value - 1 // Mon = 0
        val cells: List<LocalDate?> = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day -> JournalCalendarCell(day, dotColors, attackDays, selected, onSelect, Modifier.weight(1f)) }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun JournalCalendarCell(
    day: LocalDate?,
    dotColors: Map<LocalDate, List<Color>>,
    attackDays: Map<LocalDate, Int?>,
    selected: LocalDate?,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier,
) {
    if (day == null) { Spacer(modifier.height(44.dp)); return }
    val isAttack = attackDays.containsKey(day)
    val isSelected = day == selected
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .padding(2.dp)
            .height(42.dp)
            .clip(shape)
            .background(if (isAttack) AttackRed.copy(alpha = 0.10f) else Color.Transparent)
            .border(
                width = if (isSelected) 1.5.dp else if (isAttack) 1.dp else 0.dp,
                color = when {
                    isSelected -> AppTheme.AccentPurple
                    isAttack -> AttackRed.copy(alpha = 0.6f)
                    else -> Color.Transparent
                },
                shape = shape
            )
            .clickable { onSelect(day) },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                day.dayOfMonth.toString(),
                color = if (isAttack) AttackRed else Color.White,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (isAttack) FontWeight.Bold else FontWeight.Normal
                )
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                (dotColors[day] ?: emptyList()).forEach { c ->
                    Box(Modifier.size(4.dp).clip(CircleShape).background(c))
                }
            }
        }
    }
}

@Composable
private fun DeleteDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E0A2E),
        titleContentColor = AppTheme.TitleColor,
        textContentColor = AppTheme.SubtleTextColor,
        title = { Text(t("Delete entry?")) },
        text = { Text(t("This action can't be undone.")) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(t("Delete"), color = AttackRed) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel"), color = AppTheme.SubtleTextColor) } }
    )
}
