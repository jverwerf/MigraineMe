package com.migraineme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown

@Composable
fun LogHomeScreen(navController: NavController, authVm: AuthViewModel, vm: LogViewModel) {
    val draft by vm.draft.collectAsState()
    val scroll = rememberScrollState()

    val authState by authVm.state.collectAsState()
    LaunchedEffect(authState.accessToken) {
        val token = authState.accessToken
        if (!token.isNullOrBlank()) vm.loadMigraineOptions(token)
    }
    val frequent by vm.migraineOptionsFrequent.collectAsState()
    val all by vm.migraineOptionsAll.collectAsState()

    // UI state
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var selectedLabel by rememberSaveable { mutableStateOf("Not logging migraine") }
    var severityValue by rememberSaveable { mutableStateOf(5f) } // 1..10
    var beganAt by rememberSaveable { mutableStateOf<String?>(null) }
    var endedAt by rememberSaveable { mutableStateOf<String?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }

    // Reflect existing draft once
    LaunchedEffect(draft.migraine) {
        draft.migraine?.let {
            selectedLabel = it.type ?: "Migraine"
            severityValue = (it.severity ?: 5).coerceIn(1, 10).toFloat()
            beganAt = it.beganAtIso
            endedAt = it.endedAtIso
            notes = it.note ?: ""
        } ?: run {
            selectedLabel = "Not logging migraine"
            severityValue = 5f
            beganAt = null
            endedAt = null
            notes = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp)
    ) {
        // Selector row: dropdown + Manage
        Column(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Migraine selection") },
                trailingIcon = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose migraine")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Not logging migraine") },
                    onClick = {
                        selectedLabel = "Not logging migraine"
                        menuOpen = false
                        // remove migraine from draft, preserve others
                        val d = draft
                        vm.clearDraft()
                        d.triggers.forEach { t -> vm.addTriggerDraft(t.type, t.startAtIso, t.note) }
                        d.meds.forEach { m -> m.name?.let { nm -> vm.addMedicineDraft(nm, m.amount, m.notes, m.startAtIso) } }
                        d.rels.forEach { r -> vm.addReliefDraft(r.type, r.durationMinutes, r.notes, r.startAtIso) }
                    }
                )
                if (frequent.isNotEmpty()) {
                    Divider()
                    DropdownMenuItem(text = { Text("Frequent") }, onClick = {}, enabled = false)
                    frequent.forEach { label ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                selectedLabel = label
                                menuOpen = false
                                vm.setMigraineDraft(
                                    type = label,
                                    severity = severityValue.toInt(),
                                    beganAtIso = beganAt,
                                    endedAtIso = endedAt,
                                    note = notes.ifBlank { null }
                                )
                            }
                        )
                    }
                }
                if (all.isNotEmpty()) {
                    Divider()
                    DropdownMenuItem(text = { Text("All") }, onClick = {}, enabled = false)
                    all.forEach { label ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                selectedLabel = label
                                menuOpen = false
                                vm.setMigraineDraft(
                                    type = label,
                                    severity = severityValue.toInt(),
                                    beganAtIso = beganAt,
                                    endedAtIso = endedAt,
                                    note = notes.ifBlank { null }
                                )
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { navController.navigate(Routes.ADJUST_MIGRAINES) }) {
                    Text("Manage")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Fields auto-commit if a selection exists
        val hasSelection = selectedLabel != "Not logging migraine"
        if (hasSelection) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Type") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            // Severity slider 1..10
            Text("Severity: ${severityValue.toInt()}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = severityValue,
                onValueChange = { v ->
                    severityValue = v.coerceIn(1f, 10f)
                    vm.setMigraineDraft(
                        type = selectedLabel,
                        severity = severityValue.toInt(),
                        beganAtIso = beganAt,
                        endedAtIso = endedAt,
                        note = notes.ifBlank { null }
                    )
                },
                valueRange = 1f..10f,
                steps = 8, // 2..9 midpoints
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            AppDateTimePicker(label = "Start time") {
                beganAt = it
                vm.setMigraineDraft(
                    type = selectedLabel,
                    severity = severityValue.toInt(),
                    beganAtIso = beganAt,
                    endedAtIso = endedAt,
                    note = notes.ifBlank { null }
                )
            }
            Spacer(Modifier.height(8.dp))

            AppDateTimePicker(label = "End time") {
                endedAt = it
                vm.setMigraineDraft(
                    type = selectedLabel,
                    severity = severityValue.toInt(),
                    beganAtIso = beganAt,
                    endedAtIso = endedAt,
                    note = notes.ifBlank { null }
                )
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = notes,
                onValueChange = { v ->
                    notes = v
                    vm.setMigraineDraft(
                        type = selectedLabel,
                        severity = severityValue.toInt(),
                        beganAtIso = beganAt,
                        endedAtIso = endedAt,
                        note = notes.ifBlank { null }
                    )
                },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.weight(1f))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(onClick = { navController.navigate(Routes.HOME) }) {
                Text("Back")
            }
            Button(onClick = { navController.navigate(Routes.TRIGGERS) }) {
                Text("Next")
            }
        }
    }
}

/* ---------- helpers ---------- */

private fun formatIsoDdMmYyHm(iso: String?): String {
    if (iso.isNullOrBlank()) return "-"
    return try {
        val odt = runCatching { OffsetDateTime.parse(iso) }.getOrNull()
        val ldt = odt?.toLocalDateTime() ?: LocalDateTime.parse(iso)
        ldt.format(DateTimeFormatter.ofPattern("dd/MM/yy HH:mm"))
    } catch (_: Exception) {
        "-"
    }
}
