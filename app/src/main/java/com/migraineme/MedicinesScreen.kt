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
fun MedicinesScreen(navController: NavController, vm: LogViewModel) {
    val draft by vm.draft.collectAsState()
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp)
    ) {
        draft.meds.asReversed().forEach { m ->
            ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Medicine: ${m.name ?: "-"}")
                    Text("Amount: ${m.amount ?: "-"}")
                    Text("Notes: ${m.notes ?: "-"}")
                    Text("Time: ${m.startAtIso ?: "-"}")
                }
            }
        }

        var name by remember { mutableStateOf("") }
        var amount by remember { mutableStateOf("") }
        var notes by remember { mutableStateOf("") }
        var timestamp by remember { mutableStateOf<String?>(null) }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text("Amount") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        AppDateTimePicker(label = "Taken at") { timestamp = it }
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
                if (name.isNotBlank()) {
                    vm.addMedicineDraft(name, amount.ifBlank { null }, notes.ifBlank { null }, timestamp)
                    name = ""; amount = ""; notes = ""; timestamp = null
                }
            }
        ) {
            Text("Add Medicine")
        }

        Spacer(Modifier.weight(1f))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(onClick = { navController.navigate(Routes.TRIGGERS) }) {
                Text("Back")
            }
            Button(onClick = { navController.navigate(Routes.RELIEFS) }) {
                Text("Next")
            }
        }
    }
}
