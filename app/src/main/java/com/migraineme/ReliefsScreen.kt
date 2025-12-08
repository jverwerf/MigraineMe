// ReliefsScreen.kt
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
fun ReliefsScreen(
    navController: NavController,
    vm: ReliefViewModel,
    authVm: AuthViewModel,
    logVm: LogViewModel
) {
    val authState by authVm.state.collectAsState()
    val draft by logVm.draft.collectAsState()
    val frequent by vm.frequent.collectAsState()
    val pool by vm.pool.collectAsState()

    LaunchedEffect(authState.accessToken) {
        authState.accessToken?.let { vm.loadAll(it) }
    }

    val frequentIds = remember(frequent) { frequent.map { it.reliefId }.toSet() }
    val remaining = remember(pool, frequentIds) { pool.filter { it.id !in frequentIds } }

    var showAddDialog by remember { mutableStateOf(false) }
    var pendingLabel by remember { mutableStateOf<String?>(null) }
    fun openAdd(label: String) { pendingLabel = label; showAddDialog = true }
    if (showAddDialog) {
        DurationTimeDialog(
            initialDuration = "",
            initialIso = null,
            onDismiss = { showAddDialog = false },
            onConfirm = { duration, iso ->
                val label = pendingLabel ?: return@DurationTimeDialog
                val dur = duration.toIntOrNull()
                logVm.addReliefDraft(type = label, durationMinutes = dur, notes = null, startAtIso = iso)
                showAddDialog = false
            }
        )
    }

    var showEditDuration by remember { mutableStateOf(false) }
    var durationEditIndex by remember { mutableStateOf<Int?>(null) }

    var showEditTime by remember { mutableStateOf(false) }
    var timeEditIndex by remember { mutableStateOf<Int?>(null) }

    if (showEditDuration && durationEditIndex != null && durationEditIndex in draft.rels.indices) {
        DurationOnlyDialog(
            initial = draft.rels[durationEditIndex!!].durationMinutes?.toString().orEmpty(),
            onDismiss = { showEditDuration = false },
            onConfirm = { newDurationStr ->
                val idx = durationEditIndex!!
                val newDur = newDurationStr.toIntOrNull()
                val d = draft
                logVm.clearDraft()
                logVm.setMigraineDraft(
                    d.migraine?.type, d.migraine?.severity,
                    d.migraine?.beganAtIso, d.migraine?.endedAtIso, d.migraine?.note
                )
                d.triggers.forEach { t ->
                    logVm.addTriggerDraft(t.type, startAtIso = t.startAtIso, note = t.note)
                }
                d.meds.forEach { m ->
                    m.name?.let { nm -> logVm.addMedicineDraft(nm, m.amount, m.notes, m.startAtIso) }
                }
                d.rels.forEachIndexed { i, r ->
                    val dur = if (i == idx) newDur else r.durationMinutes
                    logVm.addReliefDraft(r.type, dur, r.notes, r.startAtIso)
                }
                showEditDuration = false
            }
        )
    }

    if (showEditTime && timeEditIndex != null && timeEditIndex in draft.rels.indices) {
        TimeOnlyDialog(
            initialIso = draft.rels[timeEditIndex!!].startAtIso,
            onDismiss = { showEditTime = false },
            onConfirm = { newIso ->
                val idx = timeEditIndex!!
                val d = draft
                logVm.clearDraft()
                logVm.setMigraineDraft(
                    d.migraine?.type, d.migraine?.severity,
                    d.migraine?.beganAtIso, d.migraine?.endedAtIso, d.migraine?.note
                )
                d.triggers.forEach { t ->
                    logVm.addTriggerDraft(t.type, startAtIso = t.startAtIso, note = t.note)
                }
                d.meds.forEach { m ->
                    m.name?.let { nm -> logVm.addMedicineDraft(nm, m.amount, m.notes, m.startAtIso) }
                }
                d.rels.forEachIndexed { i, r ->
                    val iso = if (i == idx) newIso else r.startAtIso
                    logVm.addReliefDraft(r.type, r.durationMinutes, r.notes, iso)
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
                OutlinedButton(onClick = { navController.navigate("medicines") }) { Text("Back") }
                Button(onClick = { navController.navigate("review") }) { Text("Next") }
            }
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (frequent.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Frequent", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { navController.navigate("adjust_reliefs") }) {
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
                        TextButton(onClick = { navController.navigate("adjust_reliefs") }) {
                            Text("Manage")
                        }
                    }
                }
            }

            item {
                if (draft.rels.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Selected now", style = MaterialTheme.typography.titleMedium)
                        for (idx in draft.rels.indices.reversed()) {
                            val r = draft.rels[idx]

                            ElevatedCard(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.elevatedCardColors(),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Column(Modifier.padding(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(r.type, style = MaterialTheme.typography.bodyLarge)
                                        IconButton(
                                            onClick = {
                                                val d = draft
                                                logVm.clearDraft()
                                                logVm.setMigraineDraft(
                                                    d.migraine?.type, d.migraine?.severity,
                                                    d.migraine?.beganAtIso, d.migraine?.endedAtIso, d.migraine?.note
                                                )
                                                d.triggers.forEach { t ->
                                                    logVm.addTriggerDraft(t.type, startAtIso = t.startAtIso, note = t.note)
                                                }
                                                d.meds.forEach { m ->
                                                    m.name?.let { nm -> logVm.addMedicineDraft(nm, m.amount, m.notes, m.startAtIso) }
                                                }
                                                d.rels.forEachIndexed { i, rr ->
                                                    if (i != idx) {
                                                        logVm.addReliefDraft(rr.type, rr.durationMinutes, rr.notes, rr.startAtIso)
                                                    }
                                                }
                                            }
                                        ) { Icon(Icons.Filled.Delete, contentDescription = "Remove") }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            "Time: ${formatIsoDdMmHm(r.startAtIso)}",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            "Edit",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.clickable {
                                                timeEditIndex = idx
                                                showEditTime = true
                                            }
                                        )
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            r.durationMinutes?.let { "Duration: ${it} min" } ?: "Duration: not set",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            "Edit",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.clickable {
                                                durationEditIndex = idx
                                                showEditDuration = true
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (frequent.isNotEmpty()) {
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        frequent.forEach { pref ->
                            val label = pref.relief?.label ?: return@forEach
                            AssistChip(onClick = { openAdd(label) }, label = { Text(label) })
                        }
                    }
                }
            }

            if (remaining.isNotEmpty()) {
                item { Text("All Reliefs", style = MaterialTheme.typography.titleMedium) }
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        remaining.forEach { rel ->
                            AssistChip(onClick = { openAdd(rel.label) }, label = { Text(rel.label) })
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

/** Format ISO string to "dd/MM HH:mm". */
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
private fun DurationTimeDialog(
    initialDuration: String,
    initialIso: String?,
    onDismiss: () -> Unit,
    onConfirm: (durationMinutes: String, iso: String?) -> Unit
) {
    var duration by remember { mutableStateOf(initialDuration) }
    var pickedIso by remember { mutableStateOf(initialIso) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(duration.trim(), pickedIso) },
                enabled = duration.isNotBlank()
            ) { Text("Confirm") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add relief") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = duration,
                    onValueChange = { duration = it },
                    label = { Text("Duration (minutes)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                AppDateTimePicker(
                    label = pickedIso?.let { formatIsoDdMmHm(it) } ?: "Set time",
                    onDateTimeSelected = { iso -> pickedIso = iso }
                )
            }
        }
    )
}

@Composable
private fun DurationOnlyDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var duration by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(duration.trim()) },
                enabled = duration.isNotBlank()
            ) { Text("Confirm") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Edit duration") },
        text = {
            OutlinedTextField(
                value = duration,
                onValueChange = { duration = it },
                label = { Text("Duration (minutes)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
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
