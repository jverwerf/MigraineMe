package com.migraineme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

@Composable
fun JournalScreen(navController: NavHostController, authVm: AuthViewModel, vm: LogViewModel) {
    val authState by authVm.state.collectAsState()
    val journal by vm.journal.collectAsState()

    LaunchedEffect(authState.accessToken) {
        val token = authState.accessToken
        if (!token.isNullOrBlank()) {
            vm.loadJournal(token)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Journal", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        if (journal.isEmpty()) {
            Text("No logs yet.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(journal) { ev ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            when (ev) {
                                is JournalEvent.Migraine -> {
                                    Text("Migraine", style = MaterialTheme.typography.titleMedium)
                                    Text("Type: ${ev.row.type ?: "-"}")
                                    Text("Severity: ${ev.row.severity ?: "-"}")
                                    Text("Start: ${ev.row.startAt}")
                                    Text("End: ${ev.row.endAt ?: "-"}")
                                    if (!ev.row.notes.isNullOrBlank()) {
                                        Text("Notes: ${ev.row.notes}")
                                    }
                                }

                                is JournalEvent.Trigger -> {
                                    Text("Trigger", style = MaterialTheme.typography.titleMedium)
                                    Text("Type: ${ev.row.type ?: "-"}")
                                    Text("Start: ${ev.row.startAt}")
                                    if (!ev.row.notes.isNullOrBlank()) {
                                        Text("Notes: ${ev.row.notes}")
                                    }
                                }

                                is JournalEvent.Medicine -> {
                                    Text("Medicine", style = MaterialTheme.typography.titleMedium)
                                    Text("Name: ${ev.row.name ?: "-"}")
                                    Text("Amount: ${ev.row.amount ?: "-"}")
                                    Text("Start: ${ev.row.startAt}")
                                    if (!ev.row.notes.isNullOrBlank()) {
                                        Text("Notes: ${ev.row.notes}")
                                    }
                                }

                                is JournalEvent.Relief -> {
                                    Text("Relief", style = MaterialTheme.typography.titleMedium)
                                    Text("Type: ${ev.row.type ?: "-"}")
                                    Text("Duration: ${ev.row.durationMinutes ?: "-"} min")
                                    Text("Start: ${ev.row.startAt}")
                                    if (!ev.row.notes.isNullOrBlank()) {
                                        Text("Notes: ${ev.row.notes}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
