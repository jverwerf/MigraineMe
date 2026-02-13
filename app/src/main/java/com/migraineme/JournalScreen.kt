package com.migraineme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Composable
fun JournalScreen(navController: NavHostController, authVm: AuthViewModel, vm: LogViewModel) {
    val authState by authVm.state.collectAsState()
    val journal by vm.journal.collectAsState()
    val triggerLabelMap by vm.triggerLabelMap.collectAsState()

    LaunchedEffect(authState.accessToken) {
        val token = authState.accessToken
        if (!token.isNullOrBlank()) vm.loadJournal(token)
    }

    // Filter state
    val filters = listOf("All", "Migraines", "Triggers", "Prodromes", "Medicines", "Reliefs", "Activities", "Locations", "Needs attention")
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    var selectedFilter by rememberSaveable { mutableStateOf("All") }

    // Apply filter
    val filtered = when (selectedFilter) {
        "Migraines" -> journal.filterIsInstance<JournalEvent.Migraine>()
        "Triggers" -> journal.filterIsInstance<JournalEvent.Trigger>()
        "Prodromes" -> journal.filterIsInstance<JournalEvent.Prodrome>()
        "Medicines" -> journal.filterIsInstance<JournalEvent.Medicine>()
        "Reliefs" -> journal.filterIsInstance<JournalEvent.Relief>()
        "Activities" -> journal.filterIsInstance<JournalEvent.Activity>()
        "Missed Activities" -> journal.filterIsInstance<JournalEvent.MissedActivity>()
        "Locations" -> journal.filterIsInstance<JournalEvent.Location>()
        "Needs attention" -> journal.filter { needsAttention(it) }
        else -> journal
    }

    val scrollState = rememberScrollState()

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll) {
            // Filter Card (Hero style like Home)
            HeroCard {
                Text(
                    "Filter",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )

                Box(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = selectedFilter,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = {
                            IconButton(onClick = { filterOpen = true }) {
                                Text("▼", color = Color.White)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AppTheme.AccentPurple,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                    DropdownMenu(
                        expanded = filterOpen,
                        onDismissRequest = { filterOpen = false },
                        modifier = Modifier.background(Color(0xFF1E0A2E))
                    ) {
                        filters.forEachIndexed { i, f ->
                            if (i == filters.size - 1) Divider(color = Color.White.copy(alpha = 0.1f))
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        f,
                                        color = if (f == selectedFilter) AppTheme.AccentPurple else Color.White,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (f == selectedFilter) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    )
                                },
                                onClick = {
                                    selectedFilter = f
                                    filterOpen = false
                                },
                                modifier = Modifier.background(
                                    if (f == selectedFilter) AppTheme.AccentPurple.copy(alpha = 0.1f) else Color.Transparent
                                )
                            )
                        }
                    }
                }

                Text(
                    "${filtered.size} entries",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Journal Entries
            if (filtered.isEmpty()) {
                BaseCard {
                    Text(
                        "No logs found",
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Start logging migraines, triggers, medicines, and reliefs to see them here.",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                filtered.forEach { ev ->
                    JournalEntryCard(
                        event = ev,
                        authState = authState,
                        navController = navController,
                        vm = vm,
                        triggerLabelMap = triggerLabelMap
                    )
                }
            }
        }
    }
}

@Composable
private fun JournalEntryCard(
    event: JournalEvent,
    authState: AuthState,
    navController: NavHostController,
    vm: LogViewModel,
    triggerLabelMap: Map<String, String> = emptyMap()
) {
    val needsAttn = needsAttention(event)
    var confirmDelete by rememberSaveable((event as? Any)?.hashCode() ?: 0) { mutableStateOf(false) }

    BaseCard {
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (event) {
                    is JournalEvent.Migraine -> {
                        Text(
                            "Migraine",
                            color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        JournalRowLine("Type", event.row.type ?: "-")
                        JournalRowLine("Severity", event.row.severity?.toString() ?: "Not set")
                        JournalRowTime("Start", event.row.startAt)
                        JournalRowTime("End", event.row.endAt)
                        if (!event.row.notes.isNullOrBlank()) JournalRowLine("Notes", event.row.notes!!)

                        // ── Linked items ──
                        val linked = event.linked
                        val hasLinked = linked.triggers.isNotEmpty() || linked.medicines.isNotEmpty() ||
                                linked.reliefs.isNotEmpty() || linked.prodromes.isNotEmpty() ||
                                linked.activities.isNotEmpty() || linked.locations.isNotEmpty()
                        if (hasLinked) {
                            Spacer(Modifier.height(4.dp))
                            Divider(color = Color.White.copy(alpha = 0.1f))
                            Spacer(Modifier.height(4.dp))
                            if (linked.triggers.isNotEmpty()) {
                                LinkedSection("Triggers", linked.triggers.mapNotNull { t ->
                                    val label = t.type?.let { triggerLabelMap[it] ?: it.replace("_", " ").replaceFirstChar { c -> c.uppercase() } }
                                    label
                                })
                            }
                            if (linked.prodromes.isNotEmpty()) {
                                LinkedSection("Prodromes", linked.prodromes.mapNotNull { it.type?.replace("_", " ")?.replaceFirstChar { c -> c.uppercase() } })
                            }
                            if (linked.medicines.isNotEmpty()) {
                                LinkedSection("Medicines", linked.medicines.mapNotNull { m ->
                                    listOfNotNull(m.name, m.amount?.let { "($it)" }).joinToString(" ")
                                })
                            }
                            if (linked.reliefs.isNotEmpty()) {
                                LinkedSection("Reliefs", linked.reliefs.mapNotNull { it.type?.replace("_", " ")?.replaceFirstChar { c -> c.uppercase() } })
                            }
                            if (linked.activities.isNotEmpty()) {
                                LinkedSection("Activities", linked.activities.mapNotNull { it.type?.replace("_", " ")?.replaceFirstChar { c -> c.uppercase() } })
                            }
                            if (linked.locations.isNotEmpty()) {
                                LinkedSection("Locations", linked.locations.mapNotNull { it.type?.replace("_", " ")?.replaceFirstChar { c -> c.uppercase() } })
                            }
                        }

                        JournalCardActions(
                            onEdit = { navController.navigate("${Routes.EDIT_MIGRAINE}/${event.row.id}") },
                            onDelete = { confirmDelete = true }
                        )
                        if (confirmDelete) {
                            DeleteDialog(
                                onDismiss = { confirmDelete = false },
                                onConfirm = {
                                    val token = authState.accessToken
                                    if (!token.isNullOrBlank()) vm.removeMigraine(token, event.row.id)
                                    confirmDelete = false
                                }
                            )
                        }
                    }
                    is JournalEvent.Trigger -> {
                        val isPredicted = event.row.type == "menstruation_predicted"
                        
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Trigger",
                                color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            if (isPredicted) {
                                Text(
                                    "Predicted",
                                    color = AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        val triggerDisplayLabel = event.row.type?.let { t ->
                            triggerLabelMap[t] ?: t.replace("_", " ")
                                .replaceFirstChar { c -> c.uppercase() }
                        } ?: "-"
                        JournalRowLine("Type", triggerDisplayLabel)
                        if (event.row.source == "system") {
                            JournalRowLine("Source", "Auto-detected")
                        }
                        JournalRowTime("Start", event.row.startAt)
                        if (!event.row.notes.isNullOrBlank()) JournalRowLine("Notes", event.row.notes!!)
                        
                        // Only show Edit/Delete for non-predicted triggers
                        if (!isPredicted) {
                            JournalCardActions(
                                onEdit = { navController.navigate("${Routes.EDIT_TRIGGER}/${event.row.id}") },
                                onDelete = { confirmDelete = true }
                            )
                            if (confirmDelete) {
                                DeleteDialog(
                                    onDismiss = { confirmDelete = false },
                                    onConfirm = {
                                        val token = authState.accessToken
                                        if (!token.isNullOrBlank()) vm.removeTrigger(token, event.row.id)
                                        confirmDelete = false
                                    }
                                )
                            }
                        }
                    }
                    is JournalEvent.Medicine -> {
                        Text(
                            "Medicine",
                            color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        JournalRowLine("Name", event.row.name ?: "-")
                        JournalRowAmount("Amount", event.row.amount)
                        JournalRowTime("Start", event.row.startAt)
                        if (!event.row.notes.isNullOrBlank()) JournalRowLine("Notes", event.row.notes!!)
                        JournalCardActions(
                            onEdit = { navController.navigate("${Routes.EDIT_MEDICINE}/${event.row.id}") },
                            onDelete = { confirmDelete = true }
                        )
                        if (confirmDelete) {
                            DeleteDialog(
                                onDismiss = { confirmDelete = false },
                                onConfirm = {
                                    val token = authState.accessToken
                                    if (!token.isNullOrBlank()) vm.removeMedicine(token, event.row.id)
                                    confirmDelete = false
                                }
                            )
                        }
                    }
                    is JournalEvent.Relief -> {
                        Text(
                            "Relief",
                            color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        JournalRowLine("Type", event.row.type ?: "-")
                        JournalRowDuration("Duration", event.row.durationMinutes)
                        JournalRowTime("Start", event.row.startAt)
                        if (!event.row.endAt.isNullOrBlank()) JournalRowTime("End", event.row.endAt)
                        if (!event.row.notes.isNullOrBlank()) JournalRowLine("Notes", event.row.notes!!)
                        JournalCardActions(
                            onEdit = { navController.navigate("${Routes.EDIT_RELIEF}/${event.row.id}") },
                            onDelete = { confirmDelete = true }
                        )
                        if (confirmDelete) {
                            DeleteDialog(
                                onDismiss = { confirmDelete = false },
                                onConfirm = {
                                    val token = authState.accessToken
                                    if (!token.isNullOrBlank()) vm.removeRelief(token, event.row.id)
                                    confirmDelete = false
                                }
                            )
                        }
                    }
                    is JournalEvent.Prodrome -> {
                        Text("Prodrome", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        JournalRowLine("Type", event.row.type?.replace("_", " ")?.replaceFirstChar { it.uppercase() } ?: "-")
                        JournalRowTime("Start", event.row.startAt)
                        if (!event.row.notes.isNullOrBlank()) JournalRowLine("Notes", event.row.notes!!)
                        JournalCardActions(
                            onEdit = { navController.navigate("${Routes.EDIT_PRODROME}/${event.row.id}") },
                            onDelete = { confirmDelete = true }
                        )
                        if (confirmDelete) {
                            DeleteDialog(
                                onDismiss = { confirmDelete = false },
                                onConfirm = {
                                    val token = authState.accessToken
                                    if (!token.isNullOrBlank()) vm.removeProdrome(token, event.row.id)
                                    confirmDelete = false
                                }
                            )
                        }
                    }
                    is JournalEvent.Location -> {
                        Text("Location", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        JournalRowLine("Type", event.row.type?.replace("_", " ")?.replaceFirstChar { it.uppercase() } ?: "-")
                        JournalRowTime("Start", event.row.startAt)
                        if (!event.row.notes.isNullOrBlank()) JournalRowLine("Notes", event.row.notes!!)
                        JournalCardActions(
                            onEdit = { navController.navigate("${Routes.EDIT_LOCATION}/${event.row.id}") },
                            onDelete = { confirmDelete = true }
                        )
                        if (confirmDelete) {
                            DeleteDialog(
                                onDismiss = { confirmDelete = false },
                                onConfirm = {
                                    val token = authState.accessToken
                                    if (!token.isNullOrBlank()) vm.removeLocation(token, event.row.id)
                                    confirmDelete = false
                                }
                            )
                        }
                    }
                    is JournalEvent.Activity -> {
                        Text("Activity", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        JournalRowLine("Type", event.row.type?.replace("_", " ")?.replaceFirstChar { it.uppercase() } ?: "-")
                        JournalRowTime("Start", event.row.startAt)
                        if (!event.row.endAt.isNullOrBlank()) JournalRowTime("End", event.row.endAt)
                        if (!event.row.notes.isNullOrBlank()) JournalRowLine("Notes", event.row.notes!!)
                        JournalCardActions(
                            onEdit = { navController.navigate("${Routes.EDIT_ACTIVITY}/${event.row.id}") },
                            onDelete = { confirmDelete = true }
                        )
                        if (confirmDelete) {
                            DeleteDialog(
                                onDismiss = { confirmDelete = false },
                                onConfirm = {
                                    val token = authState.accessToken
                                    if (!token.isNullOrBlank()) vm.removeActivity(token, event.row.id)
                                    confirmDelete = false
                                }
                            )
                        }
                    }
                    is JournalEvent.MissedActivity -> {
                        Text("Missed Activity", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        JournalRowLine("Type", event.row.type?.replace("_", " ")?.replaceFirstChar { it.uppercase() } ?: "-")
                        JournalRowTime("Start", event.row.startAt)
                        if (!event.row.notes.isNullOrBlank()) JournalRowLine("Notes", event.row.notes!!)
                        JournalCardActions(
                            onEdit = null,
                            onDelete = { confirmDelete = true }
                        )
                        if (confirmDelete) {
                            DeleteDialog(
                                onDismiss = { confirmDelete = false },
                                onConfirm = {
                                    val token = authState.accessToken
                                    if (!token.isNullOrBlank()) vm.removeMissedActivity(token, event.row.id)
                                    confirmDelete = false
                                }
                            )
                        }
                    }
                }
            }

            if (needsAttn) {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = "Missing data",
                    tint = AppTheme.AccentPink,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                )
            }
        }
    }
}

/* ---------- attention logic ---------- */

private fun needsAttention(ev: JournalEvent): Boolean {
    return when (ev) {
        is JournalEvent.Migraine -> ev.row.startAt.isNullOrBlank() || ev.row.endAt.isNullOrBlank() || ev.row.severity == null
        is JournalEvent.Trigger -> ev.row.startAt.isNullOrBlank()
        is JournalEvent.Medicine -> ev.row.amount.isNullOrBlank() || ev.row.startAt.isNullOrBlank()
        is JournalEvent.Relief -> ev.row.durationMinutes == null || ev.row.startAt.isNullOrBlank()
        is JournalEvent.Prodrome -> ev.row.startAt.isNullOrBlank()
        is JournalEvent.Location -> false
        is JournalEvent.Activity -> ev.row.startAt.isNullOrBlank()
        is JournalEvent.MissedActivity -> false
    }
}

/* ---------- UI helpers ---------- */

@Composable
private fun JournalCardActions(
    onEdit: (() -> Unit)?,
    onDelete: () -> Unit
) {
    Spacer(Modifier.height(8.dp))
    Divider(color = Color.White.copy(alpha = 0.1f))
    Spacer(Modifier.height(4.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        if (onEdit != null) {
            TextButton(onClick = onEdit) {
                Text("Edit", color = AppTheme.AccentPurple)
            }
            Spacer(Modifier.width(8.dp))
        }
        TextButton(onClick = onDelete) {
            Text("Delete", color = Color(0xFFFF6B6B))
        }
    }
}

@Composable
private fun LinkedSection(label: String, items: List<String>) {
    if (items.isEmpty()) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
        Text(
            items.joinToString(", "),
            color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

@Composable
private fun JournalRowLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun JournalRowTime(label: String, isoTime: String?) {
    val display = isoTime?.let {
        try {
            val zdt = ZonedDateTime.parse(it)
            zdt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        } catch (_: Exception) {
            it
        }
    } ?: "-"
    JournalRowLine(label, display)
}

@Composable
private fun JournalRowAmount(label: String, amount: String?) {
    JournalRowLine(label, amount ?: "-")
}

@Composable
private fun JournalRowDuration(label: String, minutes: Int?) {
    val display = minutes?.let {
        when {
            it >= 60 -> "${it / 60}h ${it % 60}m"
            else -> "${it}m"
        }
    } ?: "-"
    JournalRowLine(label, display)
}

@Composable
private fun DeleteDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Entry") },
        text = { Text("Are you sure you want to delete this entry? This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = Color(0xFFFF6B6B))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}


