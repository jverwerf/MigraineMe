package com.migraineme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@Composable
fun ReliefsScreen(navController: NavController, vm: LogViewModel) {
    val draft by vm.draft.collectAsState()
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp)
    ) {
        draft.rels.asReversed().forEach { r ->
            ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Relief: ${r.type}")
                    Text("Duration: ${r.durationMinutes ?: "-"} minutes")
                    Text("Notes: ${r.notes ?: "-"}")
                    Text("Time: ${r.startAtIso ?: "-"}")
                }
            }
        }

        var type by remember { mutableStateOf("") }
        var duration by remember { mutableStateOf("") }
        var notes by remember { mutableStateOf("") }
        var timestamp by remember { mutableStateOf<String?>(null) }

        OutlinedTextField(
            value = type,
            onValueChange = { type = it },
            label = { Text("Type") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = duration,
            onValueChange = { duration = it },
            label = { Text("Duration (minutes)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        AppDateTimePicker(label = "Relief start") { timestamp = it }
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                if (type.isNotBlank()) {
                    vm.addReliefDraft(type, duration.toIntOrNull(), notes.ifBlank { null }, timestamp)
                    type = ""; duration = ""; notes = ""; timestamp = null
                }
            }
        ) {
            Text("Add Relief")
        }

        Spacer(Modifier.weight(1f))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(onClick = { navController.navigate(Routes.MEDICINES) }) {
                Text("Back")
            }
            Button(onClick = { navController.navigate(Routes.REVIEW) }) {
                Text("Next")
            }
        }
    }
}
