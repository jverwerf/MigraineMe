package com.migraineme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Composable
fun EditReliefScreen(
    navController: NavHostController,
    authVm: AuthViewModel,
    vm: LogViewModel,
    id: String
) {
    val authState by authVm.state.collectAsState()

    LaunchedEffect(authState.accessToken, id) {
        val token = authState.accessToken
        if (!token.isNullOrBlank()) {
            vm.loadReliefById(token, id)
            vm.loadReliefOptions(token)
            vm.loadMigraines(token)
        }
    }

    val row by vm.editRelief.collectAsState()
    val frequent by vm.reliefOptionsFrequent.collectAsState()
    val all by vm.reliefOptionsAll.collectAsState()
    val migraines by vm.migraines.collectAsState()

    var type by rememberSaveable(row?.id) { mutableStateOf(row?.type ?: "") }
    var durationText by rememberSaveable(row?.id) { mutableStateOf(row?.durationMinutes?.toString() ?: "") }
    var startAt by rememberSaveable(row?.id) { mutableStateOf(row?.startAt ?: "") }
    var notes by rememberSaveable(row?.id) { mutableStateOf(row?.notes ?: "") }
    var migraineId by rememberSaveable(row?.id) { mutableStateOf(row?.migraineId ?: "") }

    var typeMenuOpen by rememberSaveable { mutableStateOf(false) }
    var migraineMenuOpen by rememberSaveable { mutableStateOf(false) }

    fun labelForMigraine(startIso: String?): String {
        if (startIso.isNullOrBlank()) return "Unknown"
        return try {
            val odt = runCatching { OffsetDateTime.parse(startIso) }.getOrNull()
            val ldt = odt?.toLocalDateTime() ?: LocalDateTime.parse(startIso)
            ldt.format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy • HH:mm"))
        } catch (_: Exception) { "Unknown" }
    }
    val selectedMigraineLabel = migraines
        .firstOrNull { it.id == migraineId }
        ?.let { labelForMigraine(it.startAt) }
        ?: "None"

    fun formatIsoDdMmHm(iso: String?): String {
        if (iso.isNullOrBlank()) return "Not set"
        return try {
            val odt = runCatching { OffsetDateTime.parse(iso) }.getOrNull()
            val ldt = odt?.toLocalDateTime() ?: LocalDateTime.parse(iso)
            ldt.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
        } catch (_: Exception) { "Not set" }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Edit Relief")

        // Type dropdown with Frequent / All
        if (frequent.isNotEmpty() || all.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = type,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Type") },
                    trailingIcon = {
                        IconButton(onClick = { typeMenuOpen = true }) {
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose type")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownMenu(
                    expanded = typeMenuOpen,
                    onDismissRequest = { typeMenuOpen = false }
                ) {
                    if (frequent.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Frequent", fontWeight = FontWeight.Bold) },
                            onClick = {},
                            enabled = false
                        )
                        frequent.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text(opt) },
                                onClick = {
                                    type = opt
                                    typeMenuOpen = false
                                }
                            )
                        }
                        if (all.isNotEmpty()) Divider()
                    }
                    if (all.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("All", fontWeight = FontWeight.Bold) },
                            onClick = {},
                            enabled = false
                        )
                        all.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text(opt) },
                                onClick = {
                                    type = opt
                                    typeMenuOpen = false
                                }
                            )
                        }
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = type,
                onValueChange = { type = it },
                label = { Text("Type") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Duration minutes numeric
        OutlinedTextField(
            value = durationText,
            onValueChange = { input -> durationText = input.filter { it.isDigit() }.take(4) },
            label = { Text("Duration minutes") },
            modifier = Modifier.fillMaxWidth()
        )

        // Start time using shared picker
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Start time: ${formatIsoDdMmHm(startAt)}")
            AppDateTimePicker(
                label = "Select time",
                onDateTimeSelected = { iso -> startAt = iso ?: "" }
            )
        }

        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes") },
            modifier = Modifier.fillMaxWidth()
        )

        // Linked Migraine dropdown with time/day labels
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = selectedMigraineLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text("Linked Migraine") },
                trailingIcon = {
                    IconButton(onClick = { migraineMenuOpen = true }) {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose migraine")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            DropdownMenu(
                expanded = migraineMenuOpen,
                onDismissRequest = { migraineMenuOpen = false }
            ) {
                DropdownMenuItem(
                    text = { Text("None") },
                    onClick = {
                        migraineId = ""
                        migraineMenuOpen = false
                    }
                )
                migraines
                    .sortedByDescending { it.startAt }
                    .forEach { m ->
                        val label = labelForMigraine(m.startAt)
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                migraineId = m.id
                                migraineMenuOpen = false
                            }
                        )
                    }
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                val token = authState.accessToken
                if (!token.isNullOrBlank()) {
                    val duration = durationText.toIntOrNull()
                    vm.updateRelief(
                        accessToken = token,
                        id = id,
                        type = type.ifBlank { null },
                        durationMinutes = duration,
                        startAt = startAt.ifBlank { null },
                        notes = notes.ifBlank { null },
                        migraineId = migraineId.ifBlank { null }
                    )
                    navController.popBackStack()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save")
        }

        TextButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancel")
        }
    }
}
