package com.migraineme

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

@Composable
fun ReviewLogScreen(navController: NavHostController, authVm: AuthViewModel, vm: LogViewModel) {
    val authState by authVm.state.collectAsState()
    val draft by vm.draft.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Review Log", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        draft.migraine?.let {
            Text("Migraine")
            Text("Type: ${it.type ?: "-"}")
            Text("Severity: ${it.severity ?: "-"}")
            Text("Start: ${it.beganAtIso ?: "-"}")
            Text("End: ${it.endedAtIso ?: "-"}")
            Text("Note: ${it.note ?: "-"}")
            Spacer(Modifier.height(12.dp))
        }

        if (draft.triggers.isNotEmpty()) {
            Text("Triggers")
            draft.triggers.forEach {
                Text("- ${it.type} (${it.startAtIso ?: "-"}) ${it.note ?: ""}")
            }
            Spacer(Modifier.height(12.dp))
        }

        if (draft.meds.isNotEmpty()) {
            Text("Medicines")
            draft.meds.forEach {
                Text("- ${it.name ?: "-"} ${it.amount ?: ""} (${it.startAtIso ?: "-"}) ${it.notes ?: ""}")
            }
            Spacer(Modifier.height(12.dp))
        }

        if (draft.rels.isNotEmpty()) {
            Text("Reliefs")
            draft.rels.forEach {
                Text("- ${it.type} ${it.durationMinutes ?: "-"}min (${it.startAtIso ?: "-"}) ${it.notes ?: ""}")
            }
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                val token = authState.accessToken ?: return@Button
                val migraine = draft.migraine
                if (migraine != null) {
                    vm.addFull(
                        accessToken = token,
                        type = migraine.type,
                        severity = migraine.severity,
                        beganAtIso = migraine.beganAtIso ?: "",
                        endedAtIso = migraine.endedAtIso,
                        note = migraine.note,
                        meds = draft.meds,
                        rels = draft.rels
                    )
                }
                vm.clearDraft()
                navController.popBackStack("journal", inclusive = false)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Log")
        }
    }
}
