package com.migraineme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

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

    Scaffold(
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(onClick = { navController.navigate(Routes.MIGRAINE) }) {
                    Text("Back")
                }
                Button(onClick = { navController.navigate(Routes.MEDICINES) }) {
                    Text("Next")
                }
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
            // Selected now
            if (draft.triggers.isNotEmpty()) {
                item {
                    Column {
                        Text("Selected now", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        draft.triggers.asReversed().forEach { t ->
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
                                                d.triggers.forEach { tt ->
                                                    if (tt !== t) {
                                                        logVm.addTriggerDraft(tt.type, startAtIso = tt.startAtIso, note = tt.note)
                                                    }
                                                }
                                                d.meds.forEach { m ->
                                                    m.name?.let { nm ->
                                                        logVm.addMedicineDraft(nm, m.amount, m.notes, m.startAtIso)
                                                    }
                                                }
                                                d.rels.forEach { r ->
                                                    logVm.addReliefDraft(r.type, r.durationMinutes, r.notes, r.startAtIso)
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Remove trigger")
                                        }
                                    }

                                    Text("Time: ${t.startAtIso ?: "Not set"}")
                                    if (!t.note.isNullOrBlank()) Text("Notes: ${t.note}")
                                    Spacer(Modifier.height(8.dp))

                                    AppDateTimePicker(
                                        label = t.startAtIso ?: "Set time",
                                        onDateTimeSelected = { iso ->
                                            val d = draft
                                            logVm.clearDraft()
                                            logVm.setMigraineDraft(
                                                d.migraine?.type, d.migraine?.severity,
                                                d.migraine?.beganAtIso, d.migraine?.endedAtIso, d.migraine?.note
                                            )
                                            d.triggers.forEach { tt ->
                                                val newIso = if (tt === t) iso else tt.startAtIso
                                                logVm.addTriggerDraft(tt.type, startAtIso = newIso, note = tt.note)
                                            }
                                            d.meds.forEach { m ->
                                                m.name?.let { nm ->
                                                    logVm.addMedicineDraft(nm, m.amount, m.notes, m.startAtIso)
                                                }
                                            }
                                            d.rels.forEach { r ->
                                                logVm.addReliefDraft(r.type, r.durationMinutes, r.notes, r.startAtIso)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Frequent header + Manage on same row
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
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        frequent.forEach { pref ->
                            val label = pref.trigger?.label ?: return@forEach
                            AssistChip(
                                onClick = { logVm.addTriggerDraft(trigger = label) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            // All triggers chips
            item { Text("All Triggers", style = MaterialTheme.typography.titleMedium) }
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    remaining.forEach { trig ->
                        AssistChip(
                            onClick = { logVm.addTriggerDraft(trigger = trig.label) },
                            label = { Text(trig.label) }
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}
