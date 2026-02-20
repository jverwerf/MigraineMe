package com.migraineme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JournalScreen(navController: NavHostController, authVm: AuthViewModel, vm: LogViewModel) {
    val authState by authVm.state.collectAsState()
    val journal by vm.journal.collectAsState()
    val journalLoading by vm.journalLoading.collectAsState()
    val triggerLabelMap by vm.triggerLabelMap.collectAsState()

    LaunchedEffect(authState.accessToken) {
        val token = authState.accessToken
        if (!token.isNullOrBlank()) vm.loadJournal(token)
    }

    if (journalLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = AppTheme.AccentPurple)
                Spacer(Modifier.height(8.dp))
                Text("Loading journal…", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }

    val premiumState by PremiumManager.state.collectAsState()

    val typeFilters = listOf("All", "Migraines", "Triggers", "Prodromes", "Medicines", "Reliefs", "Activities", "Locations", "Needs attention")
    var selectedTypeFilter by rememberSaveable { mutableStateOf("All") }
    var selectedSourceFilter by rememberSaveable { mutableStateOf("All") }
    var selectedTimeframe by rememberSaveable { mutableStateOf("All") }
    var customFromDate by rememberSaveable { mutableStateOf<Long?>(null) }
    var customToDate by rememberSaveable { mutableStateOf<Long?>(null) }
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }

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

    val filtered = journal
        .let { list ->
            when (selectedTypeFilter) {
                "Migraines" -> list.filterIsInstance<JournalEvent.Migraine>()
                "Triggers" -> list.filterIsInstance<JournalEvent.Trigger>()
                "Prodromes" -> list.filterIsInstance<JournalEvent.Prodrome>()
                "Medicines" -> list.filterIsInstance<JournalEvent.Medicine>()
                "Reliefs" -> list.filterIsInstance<JournalEvent.Relief>()
                "Activities" -> list.filterIsInstance<JournalEvent.Activity>()
                "Missed Activities" -> list.filterIsInstance<JournalEvent.MissedActivity>()
                "Locations" -> list.filterIsInstance<JournalEvent.Location>()
                "Needs attention" -> list.filter { needsAttention(it) }
                else -> list
            }
        }
        .let { list ->
            when (selectedSourceFilter) {
                "Manual" -> list.filter { eventSource(it) == "manual" }
                "Auto" -> list.filter { eventSource(it) != "manual" }
                else -> list
            }
        }
        .let { list ->
            if (effectiveTimeframe == "All") list
            else list.filter { ev ->
                val startAt = eventStartAt(ev) ?: return@filter false
                val t = Instant.parse(startAt)
                t.isAfter(timeframeCutoff) && t.isBefore(timeframeCeiling)
            }
        }

    val scrollState = rememberScrollState()

    ScrollableScreenContent(scrollState = scrollState, logoRevealHeight = 0.dp) {

            // ── Filter Card ──
            HeroCard {
                // Title + entry count
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Journal",
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!premiumState.isPremium) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AppTheme.AccentPurple.copy(alpha = 0.2f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Last 7 days", color = AppTheme.AccentPurple, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text("${filtered.size} entries", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (premiumState.isPremium) {
                    Spacer(Modifier.height(10.dp))
                    Divider(color = Color.White.copy(alpha = 0.08f))
                    Spacer(Modifier.height(10.dp))

                    // Type dropdown
                    var typeExpanded by remember { mutableStateOf(false) }
                    Text("Type", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(4.dp))
                    Box {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .border(1.dp, AppTheme.SubtleTextColor.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                .clickable { typeExpanded = true }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(selectedTypeFilter, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                Icon(
                                    if (typeExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Expand",
                                    tint = AppTheme.SubtleTextColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = typeExpanded,
                            onDismissRequest = { typeExpanded = false },
                            modifier = Modifier
                                .background(Color(0xFF1E0A2E))
                                .border(1.dp, AppTheme.SubtleTextColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        ) {
                            typeFilters.forEach { f ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = {
                                        Text(
                                            f,
                                            color = if (selectedTypeFilter == f) AppTheme.AccentPurple else Color.White,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (selectedTypeFilter == f) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = { selectedTypeFilter = f; typeExpanded = false }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Divider(color = Color.White.copy(alpha = 0.08f))
                    Spacer(Modifier.height(10.dp))

                    // Source chips
                    Text("Source", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("All", "Manual").forEach { s ->
                            val sel = selectedSourceFilter == s
                            FilterChip(
                                selected = sel,
                                onClick = { selectedSourceFilter = s },
                                label = { Text(s, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AppTheme.AccentPurple.copy(alpha = 0.25f),
                                    selectedLabelColor = AppTheme.AccentPurple,
                                    containerColor = Color.Transparent,
                                    labelColor = AppTheme.SubtleTextColor
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = AppTheme.SubtleTextColor.copy(alpha = 0.25f),
                                    selectedBorderColor = AppTheme.AccentPurple.copy(alpha = 0.5f),
                                    enabled = true, selected = sel
                                )
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Divider(color = Color.White.copy(alpha = 0.08f))
                    Spacer(Modifier.height(10.dp))

                    // Timeframe chips
                    Text("Timeframe", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("7d", "30d", "90d", "All").forEach { tf ->
                            val sel = selectedTimeframe == tf
                            FilterChip(
                                selected = sel,
                                onClick = { selectedTimeframe = tf },
                                label = { Text(tf, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AppTheme.AccentPurple.copy(alpha = 0.25f),
                                    selectedLabelColor = AppTheme.AccentPurple,
                                    containerColor = Color.Transparent,
                                    labelColor = AppTheme.SubtleTextColor
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = AppTheme.SubtleTextColor.copy(alpha = 0.25f),
                                    selectedBorderColor = AppTheme.AccentPurple.copy(alpha = 0.5f),
                                    enabled = true, selected = sel
                                )
                            )
                        }
                    }

                    // Custom range chip
                    Spacer(Modifier.height(4.dp))
                    val custSel = selectedTimeframe == "Custom"
                    FilterChip(
                        selected = custSel,
                        onClick = { selectedTimeframe = "Custom"; showFromPicker = true },
                        label = {
                            val lbl = if (custSel && customFromDate != null && customToDate != null) {
                                val fmt = DateTimeFormatter.ofPattern("dd MMM")
                                "${LocalDate.ofEpochDay(customFromDate!! / 86400000).format(fmt)} → ${LocalDate.ofEpochDay(customToDate!! / 86400000).format(fmt)}"
                            } else "Custom range…"
                            Text(lbl, style = MaterialTheme.typography.labelSmall)
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppTheme.AccentPink.copy(alpha = 0.25f),
                            selectedLabelColor = AppTheme.AccentPink,
                            containerColor = Color.Transparent,
                            labelColor = AppTheme.SubtleTextColor
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = AppTheme.SubtleTextColor.copy(alpha = 0.25f),
                            selectedBorderColor = AppTheme.AccentPink.copy(alpha = 0.5f),
                            enabled = true, selected = custSel
                        )
                    )

                    // Date pickers
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
                            confirmButton = { TextButton(onClick = { customFromDate = state.selectedDateMillis; showFromPicker = false; showToPicker = true }) { Text("Next", color = AppTheme.AccentPurple) } },
                            dismissButton = { TextButton(onClick = { showFromPicker = false }) { Text("Cancel", color = AppTheme.SubtleTextColor) } },
                            colors = DatePickerDefaults.colors(containerColor = Color(0xFF1E0A2E))
                        ) { DatePicker(state = state, colors = dpColors, title = { Text("From date", color = AppTheme.TitleColor, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) }) }
                    }
                    if (showToPicker) {
                        val state = rememberDatePickerState(initialSelectedDateMillis = customToDate)
                        DatePickerDialog(
                            onDismissRequest = { showToPicker = false },
                            confirmButton = { TextButton(onClick = { customToDate = state.selectedDateMillis; showToPicker = false }) { Text("Done", color = AppTheme.AccentPurple) } },
                            dismissButton = { TextButton(onClick = { showToPicker = false }) { Text("Cancel", color = AppTheme.SubtleTextColor) } },
                            colors = DatePickerDefaults.colors(containerColor = Color(0xFF1E0A2E))
                        ) { DatePicker(state = state, colors = dpColors, title = { Text("To date", color = AppTheme.TitleColor, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) }) }
                    }

                } else {
                    Spacer(Modifier.height(8.dp))
                    Divider(color = Color.White.copy(alpha = 0.08f))
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { navController.navigate(Routes.PAYWALL) },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.FilterList, contentDescription = null, tint = AppTheme.AccentPurple, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Upgrade to filter & search your full history", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ── Empty state ──
            if (filtered.isEmpty()) {
                BaseCard {
                    Text("No entries found", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(4.dp))
                    Text("Start logging migraines, triggers, medicines, and reliefs to see them here.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
            }

            // ── Journal Entries ──
            for (event in filtered) {
                var confirmDelete by rememberSaveable(event.hashCode()) { mutableStateOf(false) }

                BaseCard {
                    when (event) {
                        is JournalEvent.Migraine -> {
                            JournalEntryHeader("Migraine", AppTheme.AccentPink, event.row.startAt, needsAttention(event))
                            Spacer(Modifier.height(8.dp))
                            event.row.severity?.let { JournalDetail("Severity", "$it / 10") }
                            event.row.type?.let { JournalDetail("Type", it.replaceFirstChar { c -> c.uppercase() }) }
                            if (!event.row.endAt.isNullOrBlank()) JournalDetail("End", formatTimestamp(event.row.endAt!!))
                            if (event.row.painLocations?.isNotEmpty() == true)
                                JournalDetail("Pain location", event.row.painLocations!!.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } })
                            if (!event.row.notes.isNullOrBlank()) JournalDetail("Notes", event.row.notes!!)
                            val linked = event.linked
                            if (linked.triggers.isNotEmpty()) JournalDetail("Triggers", linked.triggers.mapNotNull { it.type }.joinToString(", ") { triggerLabelMap[it] ?: it.replace("_", " ").replaceFirstChar { c -> c.uppercase() } })
                            if (linked.medicines.isNotEmpty()) JournalDetail("Medicines", linked.medicines.mapNotNull { it.name }.joinToString(", "))
                            if (linked.reliefs.isNotEmpty()) JournalDetail("Reliefs", linked.reliefs.mapNotNull { it.type }.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } })
                            if (linked.prodromes.isNotEmpty()) JournalDetail("Prodromes", linked.prodromes.mapNotNull { it.type }.joinToString(", ") { it.replace("_", " ").replaceFirstChar { c -> c.uppercase() } })
                            if (linked.activities.isNotEmpty()) JournalDetail("Activities", linked.activities.mapNotNull { it.type }.joinToString(", ") { it.replace("_", " ").replaceFirstChar { c -> c.uppercase() } })
                            if (linked.locations.isNotEmpty()) JournalDetail("Locations", linked.locations.mapNotNull { it.type }.joinToString(", ") { it.replace("_", " ").replaceFirstChar { c -> c.uppercase() } })
                            JournalActions({ navController.navigate("${Routes.EDIT_MIGRAINE}/${event.row.id}") }) { confirmDelete = true }
                        }
                        is JournalEvent.Trigger -> {
                            val isMenstruation = event.row.type == "menstruation" && event.row.source == "system"
                            JournalEntryHeader("Trigger", Color(0xFFFFB74D), event.row.startAt)
                            Spacer(Modifier.height(8.dp))
                            JournalDetail("Type", event.row.type?.let { triggerLabelMap[it] ?: it.replace("_", " ").replaceFirstChar { c -> c.uppercase() } } ?: "-")
                            if (event.row.source == "system") JournalDetail("Source", "Auto-detected")
                            if (!event.row.notes.isNullOrBlank()) JournalDetail("Notes", event.row.notes!!)
                            if (!isMenstruation) JournalActions({ navController.navigate("${Routes.EDIT_TRIGGER}/${event.row.id}") }) { confirmDelete = true }
                        }
                        is JournalEvent.Medicine -> {
                            JournalEntryHeader("Medicine", Color(0xFF4FC3F7), event.row.startAt)
                            Spacer(Modifier.height(8.dp))
                            JournalDetail("Name", event.row.name ?: "-")
                            if (!event.row.amount.isNullOrBlank()) JournalDetail("Amount", event.row.amount!!)
                            if (!event.row.notes.isNullOrBlank()) JournalDetail("Notes", event.row.notes!!)
                            JournalActions({ navController.navigate("${Routes.EDIT_MEDICINE}/${event.row.id}") }) { confirmDelete = true }
                        }
                        is JournalEvent.Relief -> {
                            JournalEntryHeader("Relief", Color(0xFF81C784), event.row.startAt)
                            Spacer(Modifier.height(8.dp))
                            JournalDetail("Type", event.row.type?.replaceFirstChar { c -> c.uppercase() } ?: "-")
                            if (!event.row.notes.isNullOrBlank()) JournalDetail("Notes", event.row.notes!!)
                            JournalActions({ navController.navigate("${Routes.EDIT_RELIEF}/${event.row.id}") }) { confirmDelete = true }
                        }
                        is JournalEvent.Prodrome -> {
                            JournalEntryHeader("Prodrome", AppTheme.AccentPurple, event.row.startAt)
                            Spacer(Modifier.height(8.dp))
                            JournalDetail("Type", event.row.type?.replace("_", " ")?.replaceFirstChar { c -> c.uppercase() } ?: "-")
                            if (!event.row.notes.isNullOrBlank()) JournalDetail("Notes", event.row.notes!!)
                            JournalActions({ navController.navigate("${Routes.EDIT_PRODROME}/${event.row.id}") }) { confirmDelete = true }
                        }
                        is JournalEvent.Location -> {
                            JournalEntryHeader("Location", Color(0xFFCE93D8), event.row.startAt)
                            Spacer(Modifier.height(8.dp))
                            JournalDetail("Name", event.row.type?.replace("_", " ")?.replaceFirstChar { c -> c.uppercase() } ?: "-")
                            if (!event.row.notes.isNullOrBlank()) JournalDetail("Notes", event.row.notes!!)
                            JournalActions({ navController.navigate("${Routes.EDIT_LOCATION}/${event.row.id}") }) { confirmDelete = true }
                        }
                        is JournalEvent.Activity -> {
                            JournalEntryHeader("Activity", Color(0xFFFF8A65), event.row.startAt)
                            Spacer(Modifier.height(8.dp))
                            JournalDetail("Type", event.row.type?.replace("_", " ")?.replaceFirstChar { c -> c.uppercase() } ?: "-")
                            if (!event.row.notes.isNullOrBlank()) JournalDetail("Notes", event.row.notes!!)
                            JournalActions({ navController.navigate("${Routes.EDIT_ACTIVITY}/${event.row.id}") }) { confirmDelete = true }
                        }
                        is JournalEvent.MissedActivity -> {
                            JournalEntryHeader("Missed Activity", Color(0xFFEF9A9A), event.row.startAt)
                            Spacer(Modifier.height(8.dp))
                            JournalDetail("Type", event.row.type?.replace("_", " ")?.replaceFirstChar { c -> c.uppercase() } ?: "-")
                            if (!event.row.notes.isNullOrBlank()) JournalDetail("Notes", event.row.notes!!)
                            JournalActions(onEdit = null) { confirmDelete = true }
                        }
                    }

                    if (confirmDelete) {
                        DeleteDialog(
                            onDismiss = { confirmDelete = false },
                            onConfirm = {
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
                                confirmDelete = false
                            }
                        )
                    }
                }
            }
        }
}

// ── Helpers ──

private fun eventSource(ev: JournalEvent): String? = when (ev) {
    is JournalEvent.Migraine -> "manual"
    is JournalEvent.Trigger -> ev.row.source
    is JournalEvent.Medicine -> ev.row.source
    is JournalEvent.Relief -> ev.row.source
    is JournalEvent.Prodrome -> ev.row.source
    is JournalEvent.Location -> ev.row.source
    is JournalEvent.Activity -> ev.row.source
    is JournalEvent.MissedActivity -> "manual"
}

private fun eventStartAt(ev: JournalEvent): String? = when (ev) {
    is JournalEvent.Migraine -> ev.row.startAt
    is JournalEvent.Trigger -> ev.row.startAt
    is JournalEvent.Medicine -> ev.row.startAt
    is JournalEvent.Relief -> ev.row.startAt
    is JournalEvent.Prodrome -> ev.row.startAt
    is JournalEvent.Location -> ev.row.startAt
    is JournalEvent.Activity -> ev.row.startAt
    is JournalEvent.MissedActivity -> ev.row.startAt
}

private fun needsAttention(event: JournalEvent): Boolean = when (event) {
    is JournalEvent.Migraine -> event.linked.triggers.isEmpty() && event.linked.medicines.isEmpty() && event.linked.reliefs.isEmpty()
    else -> false
}

private fun formatTimestamp(iso: String): String = try {
    ZonedDateTime.parse(iso).format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))
} catch (_: Exception) { iso.take(16).replace("T", " ") }

// ── UI components ──

@Composable
private fun JournalEntryHeader(
    label: String,
    color: Color,
    timestamp: String?,
    showAttention: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(color.copy(alpha = 0.15f))
                .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp)
        ) {
            Text(label, color = color, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (showAttention) Icon(Icons.Filled.Error, contentDescription = "Needs attention", tint = Color(0xFFFFB74D), modifier = Modifier.size(16.dp))
            if (!timestamp.isNullOrBlank()) Text(formatTimestamp(timestamp), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun JournalDetail(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.38f))
        Text(value, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(0.62f), textAlign = TextAlign.End)
    }
}

@Composable
private fun JournalActions(onEdit: (() -> Unit)?, onDelete: () -> Unit) {
    Spacer(Modifier.height(4.dp))
    Divider(color = Color.White.copy(alpha = 0.06f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (onEdit != null) {
            TextButton(onClick = onEdit) {
                Text("Edit", color = AppTheme.AccentPurple, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
            }
        }
        TextButton(onClick = onDelete) {
            Text("Delete", color = Color(0xFFE57373), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
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
        title = { Text("Delete entry?") },
        text = { Text("This action can't be undone.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = Color(0xFFE57373)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = AppTheme.SubtleTextColor) } }
    )
}

