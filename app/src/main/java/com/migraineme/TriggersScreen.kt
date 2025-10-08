package com.migraineme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TriggersScreen(
    navController: NavController,
    vm: TriggerViewModel,        // Supabase-backed triggers/prefs
    authVm: AuthViewModel,       // SAME instance from MainActivity (has token)
    logVm: LogViewModel          // Draft flow (add trigger + adjust time)
) {
    // Supabase data
    val pool by vm.pool.collectAsState()
    val frequent by vm.frequent.collectAsState()

    // Auth state for token
    val authState by authVm.state.collectAsState()

    // Load from Supabase when token is present
    LaunchedEffect(authState.accessToken) {
        authState.accessToken?.let { token -> vm.loadAll(token) }
    }

    // Draft (what the user has added so far)
    val draft by logVm.draft.collectAsState()

    // Compute remaining (pool minus frequent)
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
            // Header + Manage
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Triggers", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { navController.navigate(Routes.ADJUST_TRIGGERS) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Manage Triggers")
                    }
                }
            }

            // Selected now (from the DRAFT, newest first), with the same picker used elsewhere
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
                                    Text("Trigger: ${t.type}", style = MaterialTheme.typography.bodyLarge)
                                    Text("Time: ${t.startAtIso ?: "Not set"}")
                                    if (!t.note.isNullOrBlank()) Text("Notes: ${t.note}")
                                    Spacer(Modifier.height(8.dp))

                                    // Use the same calendar+time picker you use in other screens
                                    AppDateTimePicker(
                                        label = t.startAtIso ?: "Set time",
                                        onDateTimeSelected = { iso ->
                                            // Update ONLY this item’s time in the draft
                                            val d = draft
                                            // rebuild draft preserving everything else
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

            // Frequent chips (compact, wrap-content)
            if (frequent.isNotEmpty()) {
                item { Text("Frequent", style = MaterialTheme.typography.titleMedium) }
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        frequent.forEach { pref ->
                            val label = pref.trigger?.label ?: return@forEach
                            AssistChip(
                                onClick = { logVm.addTriggerDraft(trigger = label) },
                                label = { Text(label) },
                                modifier = Modifier // wrap content
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
                            label = { Text(trig.label) },
                            modifier = Modifier // wrap content
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(80.dp)) } // space above bottom bar
        }
    }
}
