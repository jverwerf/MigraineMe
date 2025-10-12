package com.migraineme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.layout.width


@Composable
fun JournalScreen(navController: NavHostController, authVm: AuthViewModel, vm: LogViewModel) {
    val authState by authVm.state.collectAsState()
    val journal by vm.journal.collectAsState()

    LaunchedEffect(authState.accessToken) {
        val token = authState.accessToken
        if (!token.isNullOrBlank()) vm.loadJournal(token)
    }

    // Filter state
    val filters = listOf("All", "Migraines", "Triggers", "Medicines", "Reliefs", "Needs attention")
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    var selectedFilter by rememberSaveable { mutableStateOf("All") }

    // Apply filter
    val filtered = when (selectedFilter) {
        "Migraines" -> journal.filterIsInstance<JournalEvent.Migraine>()
        "Triggers" -> journal.filterIsInstance<JournalEvent.Trigger>()
        "Medicines" -> journal.filterIsInstance<JournalEvent.Medicine>()
        "Reliefs" -> journal.filterIsInstance<JournalEvent.Relief>()
        "Needs attention" -> journal.filter { needsAttention(it) }
        else -> journal
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Replaces the duplicate "Journal" title: a filter control
        Box(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = selectedFilter,
                onValueChange = {},
                readOnly = true,
                label = { Text("Filter") },
                trailingIcon = {
                    IconButton(onClick = { filterOpen = true }) {
                        Text("▼")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            DropdownMenu(
                expanded = filterOpen,
                onDismissRequest = { filterOpen = false }
            ) {
                filters.forEachIndexed { i, f ->
                    if (i == 5) Divider()
                    DropdownMenuItem(
                        text = { Text(f) },
                        onClick = {
                            selectedFilter = f
                            filterOpen = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (filtered.isEmpty()) {
            Text("No logs.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(filtered) { ev ->
                    val needsAttn = needsAttention(ev)
                    var confirmDelete by rememberSaveable((ev as? Any)?.hashCode() ?: 0) { mutableStateOf(false) }

                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Box(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                when (ev) {
                                    is JournalEvent.Migraine -> {
                                        Text("Migraine", style = MaterialTheme.typography.titleMedium)
                                        rowLine("Type", ev.row.type ?: "-")
                                        rowLine("Severity", ev.row.severity?.toString() ?: "Not set")
                                        rowTime("Start", ev.row.startAt)
                                        rowTime("End", ev.row.endAt)
                                        if (!ev.row.notes.isNullOrBlank()) rowLine("Notes", ev.row.notes!!)
                                        CardActions(
                                            onEdit = { navController.navigate("${Routes.EDIT_MIGRAINE}/${ev.row.id}") },
                                            onDelete = { confirmDelete = true }
                                        )
                                        if (confirmDelete) {
                                            DeleteDialog(
                                                onDismiss = { confirmDelete = false },
                                                onConfirm = {
                                                    val token = authState.accessToken
                                                    if (!token.isNullOrBlank()) vm.removeMigraine(token, ev.row.id)
                                                    confirmDelete = false
                                                }
                                            )
                                        }
                                    }
                                    is JournalEvent.Trigger -> {
                                        Text("Trigger", style = MaterialTheme.typography.titleMedium)
                                        rowLine("Type", ev.row.type ?: "-")
                                        rowTime("Start", ev.row.startAt)
                                        if (!ev.row.notes.isNullOrBlank()) rowLine("Notes", ev.row.notes!!)
                                        CardActions(
                                            onEdit = { navController.navigate("${Routes.EDIT_TRIGGER}/${ev.row.id}") },
                                            onDelete = { confirmDelete = true }
                                        )
                                        if (confirmDelete) {
                                            DeleteDialog(
                                                onDismiss = { confirmDelete = false },
                                                onConfirm = {
                                                    val token = authState.accessToken
                                                    if (!token.isNullOrBlank()) vm.removeTrigger(token, ev.row.id)
                                                    confirmDelete = false
                                                }
                                            )
                                        }
                                    }
                                    is JournalEvent.Medicine -> {
                                        Text("Medicine", style = MaterialTheme.typography.titleMedium)
                                        rowLine("Name", ev.row.name ?: "-")
                                        rowAmount("Amount", ev.row.amount)
                                        rowTime("Start", ev.row.startAt)
                                        if (!ev.row.notes.isNullOrBlank()) rowLine("Notes", ev.row.notes!!)
                                        CardActions(
                                            onEdit = { navController.navigate("${Routes.EDIT_MEDICINE}/${ev.row.id}") },
                                            onDelete = { confirmDelete = true }
                                        )
                                        if (confirmDelete) {
                                            DeleteDialog(
                                                onDismiss = { confirmDelete = false },
                                                onConfirm = {
                                                    val token = authState.accessToken
                                                    if (!token.isNullOrBlank()) vm.removeMedicine(token, ev.row.id)
                                                    confirmDelete = false
                                                }
                                            )
                                        }
                                    }
                                    is JournalEvent.Relief -> {
                                        Text("Relief", style = MaterialTheme.typography.titleMedium)
                                        rowLine("Type", ev.row.type ?: "-")
                                        rowDuration("Duration", ev.row.durationMinutes)
                                        rowTime("Start", ev.row.startAt)
                                        if (!ev.row.notes.isNullOrBlank()) rowLine("Notes", ev.row.notes!!)
                                        CardActions(
                                            onEdit = { navController.navigate("${Routes.EDIT_RELIEF}/${ev.row.id}") },
                                            onDelete = { confirmDelete = true }
                                        )
                                        if (confirmDelete) {
                                            DeleteDialog(
                                                onDismiss = { confirmDelete = false },
                                                onConfirm = {
                                                    val token = authState.accessToken
                                                    if (!token.isNullOrBlank()) vm.removeRelief(token, ev.row.id)
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
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                )
                            }
                        }
                    }
                }
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
    }
}

/* ---------- UI helpers ---------- */

@Composable
private fun CardActions(
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(onClick = onEdit) { Text("Edit") }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onDelete) { Text("Delete") }
    }
}

@Composable
private fun DeleteDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Delete entry") },
        text = { Text("Are you sure you want to remove this entry?") }
    )
}

@Composable
private fun rowLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$label:")
        Text(value)
    }
}

@Composable
private fun rowTime(label: String, iso: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$label:")
        Text(formatIsoDdMmYyHm(iso))
    }
}

@Composable
private fun rowAmount(label: String, amount: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$label:")
        Text(if (amount.isNullOrBlank()) "Not set" else amount)
    }
}

@Composable
private fun rowDuration(label: String, minutes: Int?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$label:")
        Text(if (minutes == null) "Not set" else "$minutes min")
    }
}

/* ---------- Format helper ---------- */

private fun formatIsoDdMmYyHm(iso: String?): String {
    if (iso.isNullOrBlank()) return "Not set"
    return try {
        val odt = runCatching { OffsetDateTime.parse(iso) }.getOrNull()
        val ldt = odt?.toLocalDateTime() ?: LocalDateTime.parse(iso)
        ldt.format(DateTimeFormatter.ofPattern("dd/MM/yy HH:mm"))
    } catch (_: Exception) {
        "Not set"
    }
}
