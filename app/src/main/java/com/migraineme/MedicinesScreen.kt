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
fun MedicinesScreen(
    navController: NavController,
    vm: MedicineViewModel,
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

    val frequentIds = remember(frequent) { frequent.map { it.medicineId }.toSet() }
    val remaining = remember(pool, frequentIds) { pool.filter { it.id !in frequentIds } }

    // Add dialog (amount + time when adding new)
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingLabel by remember { mutableStateOf<String?>(null) }
    fun openAdd(label: String) {
        pendingLabel = label
        showAddDialog = true
    }
    if (showAddDialog) {
        AmountTimeDialog(
            initialAmount = "",
            initialIso = null,
            onDismiss = { showAddDialog = false },
            onConfirm = { amount, iso ->
                val label = pendingLabel ?: return@AmountTimeDialog
                logVm.addMedicineDraft(name = label, amount = amount, notes = null, startAtIso = iso)
                showAddDialog = false
            }
        )
    }

    // Edit dialogs for existing cards
    var showEditAmount by remember { mutableStateOf(false) }
    var amountEditIndex by remember { mutableStateOf<Int?>(null) }

    var showEditTime by remember { mutableStateOf(false) }
    var timeEditIndex by remember { mutableStateOf<Int?>(null) }

    if (showEditAmount && amountEditIndex != null && amountEditIndex in draft.meds.indices) {
        AmountOnlyDialog(
            initial = draft.meds[amountEditIndex!!].amount ?: "",
            onDismiss = { showEditAmount = false },
            onConfirm = { newAmount ->
                val idx = amountEditIndex!!
                val d = draft
                logVm.clearDraft()
                logVm.setMigraineDraft(
                    d.migraine?.type, d.migraine?.severity,
                    d.migraine?.beganAtIso, d.migraine?.endedAtIso, d.migraine?.note
                )
                d.triggers.forEach { t ->
                    logVm.addTriggerDraft(t.type, startAtIso = t.startAtIso, note = t.note)
                }
                d.meds.forEachIndexed { i, mm ->
                    mm.name?.let { nm ->
                        val amt = if (i == idx) newAmount else mm.amount
                        logVm.addMedicineDraft(nm, amt, mm.notes, mm.startAtIso)
                    }
                }
                d.rels.forEach { r ->
                    logVm.addReliefDraft(r.type, r.durationMinutes, r.notes, r.startAtIso)
                }
                showEditAmount = false
            }
        )
    }

    if (showEditTime && timeEditIndex != null && timeEditIndex in draft.meds.indices) {
        TimeOnlyDialog(
            initialIso = draft.meds[timeEditIndex!!].startAtIso,
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
                d.meds.forEachIndexed { i, mm ->
                    mm.name?.let { nm ->
                        val iso = if (i == idx) newIso else mm.startAtIso
                        logVm.addMedicineDraft(nm, mm.amount, mm.notes, iso)
                    }
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
                OutlinedButton(onClick = { navController.navigate(Routes.TRIGGERS) }) { Text("Back") }
                Button(onClick = { navController.navigate(Routes.RELIEFS) }) { Text("Next") }
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
            // Frequent header + Manage
            if (frequent.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Frequent", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { navController.navigate(Routes.ADJUST_MEDICINES) }) {
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
                        TextButton(onClick = { navController.navigate(Routes.ADJUST_MEDICINES) }) {
                            Text("Manage")
                        }
                    }
                }
            }

            // Selected now — compact card; time/amount as text + plain clickable "Edit"
            item {
                if (draft.meds.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Selected now", style = MaterialTheme.typography.titleMedium)
                        for (idx in draft.meds.indices.reversed()) {
                            val m = draft.meds[idx]

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
                                        Text(m.name ?: "?", style = MaterialTheme.typography.bodyLarge)
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
                                                d.meds.forEachIndexed { i, mm ->
                                                    mm.name?.let { nm ->
                                                        if (i != idx) {
                                                            logVm.addMedicineDraft(nm, mm.amount, mm.notes, mm.startAtIso)
                                                        }
                                                    }
                                                }
                                                d.rels.forEach { r ->
                                                    logVm.addReliefDraft(r.type, r.durationMinutes, r.notes, r.startAtIso)
                                                }
                                            }
                                        ) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
                                    }

                                    // Time line
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            "Time: ${formatIsoDdMmHm(m.startAtIso)}",
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

                                    // Amount line
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            if (m.amount.isNullOrBlank()) "Amount: not set" else "Amount: ${m.amount}",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            "Edit",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.clickable {
                                                amountEditIndex = idx
                                                showEditAmount = true
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Frequent chips → add dialog
            if (frequent.isNotEmpty()) {
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        frequent.forEach { pref ->
                            val label = pref.medicine?.label ?: return@forEach
                            AssistChip(onClick = { openAdd(label) }, label = { Text(label) })
                        }
                    }
                }
            }

            // All medicines chips → add dialog
            if (remaining.isNotEmpty()) {
                item { Text("All Medicines", style = MaterialTheme.typography.titleMedium) }
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        remaining.forEach { med ->
                            AssistChip(onClick = { openAdd(med.label) }, label = { Text(med.label) })
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

/** Format ISO string to "dd/MM HH:mm". Falls back to "Not set" if parse fails. */
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
private fun AmountTimeDialog(
    initialAmount: String,
    initialIso: String?,
    onDismiss: () -> Unit,
    onConfirm: (amount: String, iso: String?) -> Unit
) {
    var amount by remember { mutableStateOf(initialAmount) }
    var pickedIso by remember { mutableStateOf(initialIso) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(amount.trim(), pickedIso) },
                enabled = amount.isNotBlank()
            ) { Text("Confirm") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add medicine") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount") },
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
private fun AmountOnlyDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var amount by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(amount.trim()) },
                enabled = amount.isNotBlank()
            ) { Text("Confirm") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Edit amount") },
        text = {
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("Amount") },
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
                // Picker shown immediately in the dialog
                AppDateTimePicker(
                    label = "Select new time",
                    onDateTimeSelected = { iso -> pickedIso = iso }
                )
            }
        }
    )
}
