package com.migraineme

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
fun MenstruationEditDialog(
    currentSettings: MenstruationSettings,
    onConfirm: (lastDate: LocalDate?, avgCycle: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var lastDateInput by remember(currentSettings) {
        mutableStateOf(currentSettings.lastMenstruationDate?.toString() ?: "")
    }
    var avgCycleInput by remember(currentSettings) {
        mutableStateOf(currentSettings.avgCycleLength.toString())
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Menstruation Settings") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = lastDateInput,
                    onValueChange = {
                        lastDateInput = it
                        errorMessage = null
                    },
                    label = { Text("Last Period Date") },
                    placeholder = { Text("YYYY-MM-DD (e.g., 2026-01-15)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = errorMessage != null
                )

                OutlinedTextField(
                    value = avgCycleInput,
                    onValueChange = {
                        if (it.all { char -> char.isDigit() } && it.length <= 2) {
                            avgCycleInput = it
                            errorMessage = null
                        }
                    },
                    label = { Text("Average Cycle (days)") },
                    placeholder = { Text("28") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text(
                    "Weighted average of last 6 cycles",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (errorMessage != null) {
                    Text(
                        errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val lastDate = try {
                        if (lastDateInput.isNotBlank()) {
                            LocalDate.parse(lastDateInput)
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        errorMessage = "Invalid date format. Use YYYY-MM-DD"
                        return@TextButton
                    }

                    val avgCycle = avgCycleInput.toIntOrNull()
                    if (avgCycle == null || avgCycle < 21 || avgCycle > 45) {
                        errorMessage = "Cycle length must be between 21-45 days"
                        return@TextButton
                    }

                    if (lastDate != null && lastDate.isAfter(LocalDate.now())) {
                        errorMessage = "Last period date cannot be in the future"
                        return@TextButton
                    }

                    onConfirm(lastDate, avgCycle)
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}