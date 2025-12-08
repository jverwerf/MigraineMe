package com.migraineme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TriggersScreen(
    navController: NavController,
    vm: TriggerViewModel,
    authVm: AuthViewModel,
    logVm: LogViewModel
) {
    val pool by vm.pool.collectAsState()
    val frequent by vm.frequent.collectAsState()
    val authState by authVm.state.collectAsState()
    val draft by logVm.draft.collectAsState()

    LaunchedEffect(authState.accessToken) {
        authState.accessToken?.let { token -> vm.loadAll(token) }
    }

    val frequentIds = remember(frequent) { frequent.map { it.triggerId }.toSet() }
    val remaining = remember(pool, frequentIds) { pool.filter { it.id !in frequentIds } }

    // Add dialog: pick time before adding a trigger
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingLabel by remember { mutableStateOf<String?>(null) }
    fun openAdd(label: String) {
        pendingLabel = label
        showAddDialog = true
    }
    if (showAddDialog) {
        TimeAddDialog(
            initialIso = null,
            onDismiss = { showAddDialog = false },
            onConfirm = { iso ->
                val label = pendingLabel ?: return@TimeAddDialog
                logVm.addTriggerDraft(trigger = label, startAtIso = iso)
                showAddDialog = false
            }
        )
    }

    // Edit time dialog for existing cards
    var showEditTime by remember { mutableStateOf(false) }
    var timeEditIndex by remember { mutableStateOf<Int?>(null) }
    if (showEditTime && timeEditIndex != null && timeEditIndex in draft.triggers.indices) {
        TimeOnlyDialog(
            initialIso = draft.triggers[timeEditIndex!!].startAtIso,
            onDismiss = { showEditTime = false },
            onConfirm = { newIso ->
                val idx = timeEditIndex!!
                val d = draft
                logVm.clearDraft()
                logVm.setMigraineDraft(
                    d.migraine?.type, d.migraine?.severity,
                    d.migraine?.beganAtIso, d.migraine?.endedAtIso, d.migraine?.note
                )
                d.triggers.forEachIndexed { i, tt ->
                    val iso = if (i == idx) newIso else tt.startAtIso
                    logVm.addTriggerDraft(tt.type, startAtIso = iso, note = tt.note)
                }
                d.meds.forEach { m ->
                    m.name?.let { nm -> logVm.addMedicineDraft(nm, m.amount, m.notes, m.startAtIso) }
                }
                d.rels.forEach { r ->
                    logVm.addReliefDraft(r.type, r.durationMinutes, r.notes, r.startAtIso)
                }
                showEditTime = false
            }
        )
    }

    Scaffold(
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(onClick = { navController.navigate(Routes.MIGRAINE) }) { Text("Back") }
                Button(onClick = { navController.navigate(Routes.MEDICINES) }) { Text("Next") }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header + Manage aligned with Frequent
            if (frequent.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Frequent", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { navController.navigate(Routes.ADJUST_TRIGGERS) }) {
                            Text("Manage")
                        }
                    }
                }
            } else {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { navController.navigate(Routes.ADJUST_TRIGGERS) }) {
                            Text("Manage")
                        }
                    }
                }
            }

            // Selected now
            if (draft.triggers.isNotEmpty()) {
                item {
                    Column {
                        Text("Selected now", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        draft.triggers.asReversed().forEachIndexed { revIndex, t ->
                            val actualIndex = draft.triggers.lastIndex - revIndex
                            ElevatedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Trigger: ${t.type}", style = MaterialTheme.typography.bodyLarge)
                                        IconButton(
                                            onClick = {
                                                val d = draft
                                                logVm.clearDraft()
                                                logVm.setMigraineDraft(
                                                    d.migraine?.type, d.migraine?.severity,
                                                    d.migraine?.beganAtIso, d.migraine?.endedAtIso, d.migraine?.note
                                                )
                                                d.triggers.forEachIndexed { i, tt ->
                                                    if (i != actualIndex) {
                                                        logVm.addTriggerDraft(tt.type, startAtIso = tt.startAtIso, note = tt.note)
                                                    }
                                                }
                                                d.meds.forEach { m ->
                                                    m.name?.let { nm -> logVm.addMedicineDraft(nm, m.amount, m.notes, m.startAtIso) }
                                                }
                                                d.rels.forEach { r ->
                                                    logVm.addReliefDraft(r.type, r.durationMinutes, r.notes, r.startAtIso)
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Remove trigger")
                                        }
                                    }

                                    // Time row: plain clickable "Edit"
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            "Time: ${formatIsoDdMmHm(t.startAtIso)}",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            "Edit",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.clickable {
                                                timeEditIndex = actualIndex
                                                showEditTime = true
                                            }
                                        )
                                    }

                                    if (!t.note.isNullOrBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text("Notes: ${t.note}", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Frequent chips → open time-add dialog
            if (frequent.isNotEmpty()) {
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        frequent.forEach { pref ->
                            val label = pref.trigger?.label ?: return@forEach
                            AssistChip(
                                onClick = { openAdd(label) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            // All triggers chips → open time-add dialog
            if (remaining.isNotEmpty()) {
                item { Text("All Triggers", style = MaterialTheme.typography.titleMedium) }
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        remaining.forEach { trig ->
                            AssistChip(
                                onClick = { openAdd(trig.label) },
                                label = { Text(trig.label) }
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

/** Format ISO string to "dd/MM HH:mm". Falls back to "Not set". */
private fun formatIsoDdMmHm(iso: String?): String {
    if (iso.isNullOrBlank()) return "Not set"
    return try {
        val odt = runCatching { OffsetDateTime.parse(iso) }.getOrNull()
        val ldt = odt?.toLocalDateTime() ?: LocalDateTime.parse(iso)
        ldt.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
    } catch (_: Exception) {
        "Not set"
    }
}

@Composable
private fun TimeAddDialog(
    initialIso: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    var pickedIso by remember { mutableStateOf(initialIso) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(pickedIso) }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add trigger") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Time: ${formatIsoDdMmHm(pickedIso)}")
                AppDateTimePicker(
                    label = "Select time",
                    onDateTimeSelected = { iso -> pickedIso = iso }
                )
            }
        }
    )
}

@Composable
private fun TimeOnlyDialog(
    initialIso: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    var pickedIso by remember { mutableStateOf(initialIso) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(pickedIso) }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Edit time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Current: ${formatIsoDdMmHm(pickedIso)}")
                AppDateTimePicker(
                    label = "Select new time",
                    onDateTimeSelected = { iso -> pickedIso = iso }
                )
            }
        }
    )
}
