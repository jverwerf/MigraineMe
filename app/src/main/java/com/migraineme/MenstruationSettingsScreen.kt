package com.migraineme

import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Screen to view/edit menstruation_settings.
 *
 * Uses existing patterns:
 * - Token retrieval: SessionStore.getValidAccessToken(context)
 * - Read: SupabaseMenstruationService.getSettings(accessToken)
 * - Write: MenstruationTrackingHelper.updateSettingsOnly(...)
 *
 * This screen does NOT enable/disable tracking or schedule workers; it only edits settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenstruationSettingsScreen(
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val loading = remember { mutableStateOf(true) }
    val saving = remember { mutableStateOf(false) }
    val errorText = remember { mutableStateOf<String?>(null) }

    val loadedSettings: MutableState<MenstruationSettings?> = remember { mutableStateOf(null) }

    // Editable UI state
    val lastDateText = remember { mutableStateOf("") }
    val avgCycleText = remember { mutableStateOf("28") }
    val autoUpdateAvg = remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading.value = true
        errorText.value = null

        val result = withContext(Dispatchers.IO) {
            runCatching {
                val token = SessionStore.getValidAccessToken(context.applicationContext)
                    ?: return@runCatching Pair<MenstruationSettings?, String?>(
                        null,
                        "Please sign in again to load menstruation settings."
                    )

                val service = SupabaseMenstruationService(context.applicationContext)
                val s = service.getSettings(token)
                Pair(s, null)
            }.getOrElse { e ->
                Pair<MenstruationSettings?, String?>(
                    null,
                    "Failed to load menstruation settings: ${e.message ?: "Unknown error"}"
                )
            }
        }

        val settings = result.first
        val err = result.second

        loadedSettings.value = settings
        errorText.value = err

        if (settings != null) {
            lastDateText.value = settings.lastMenstruationDate?.toString() ?: ""
            avgCycleText.value = settings.avgCycleLength.toString()
            autoUpdateAvg.value = settings.autoUpdateAverage
        }

        loading.value = false
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Menstruation Settings") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors()
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (loading.value) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            errorText.value?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Text(
                text = "Edit the values stored in menstruation_settings.",
                style = MaterialTheme.typography.bodyMedium
            )

            Divider()

            OutlinedTextField(
                value = lastDateText.value,
                onValueChange = { lastDateText.value = it },
                label = { Text("Last period date") },
                placeholder = { Text("YYYY-MM-DD (optional)") },
                supportingText = {
                    Text("Leave blank if unknown. Format: YYYY-MM-DD")
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !saving.value
            )

            OutlinedTextField(
                value = avgCycleText.value,
                onValueChange = { new ->
                    if (new.isEmpty() || new.all { it.isDigit() }) {
                        // Keep it simple; validation happens on Save.
                        avgCycleText.value = new
                    }
                },
                label = { Text("Average cycle length (days)") },
                placeholder = { Text("28") },
                supportingText = {
                    Text("Used for prediction and averaging. Typical range 15–60.")
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !saving.value
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-update average",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "When enabled, background sync can recompute avg cycle length from recent periods.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoUpdateAvg.value,
                    onCheckedChange = { autoUpdateAvg.value = it },
                    enabled = !saving.value
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            saving.value = true
                            val (parsedLast, parsedAvg, validationError) =
                                validateInputs(
                                    lastDateText = lastDateText.value,
                                    avgCycleText = avgCycleText.value
                                )

                            if (validationError != null) {
                                withContext(Dispatchers.Main) {
                                    saving.value = false
                                    Toast.makeText(context, validationError, Toast.LENGTH_LONG)
                                        .show()
                                }
                                return@launch
                            }

                            val ok = MenstruationTrackingHelper.updateSettingsOnly(
                                context = context.applicationContext,
                                lastDate = parsedLast,
                                avgCycle = parsedAvg,
                                autoUpdate = autoUpdateAvg.value
                            )

                            withContext(Dispatchers.Main) {
                                saving.value = false
                                if (ok) {
                                    loadedSettings.value = MenstruationSettings(
                                        lastMenstruationDate = parsedLast,
                                        avgCycleLength = parsedAvg,
                                        autoUpdateAverage = autoUpdateAvg.value
                                    )
                                    Toast.makeText(context, "Menstruation settings saved.", Toast.LENGTH_SHORT)
                                        .show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Failed to save. Please sign in again and retry.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    },
                    enabled = !saving.value,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (saving.value) "Saving..." else "Save")
                }

                TextButton(
                    onClick = {
                        // Reset to last loaded values
                        val s = loadedSettings.value
                        if (s != null) {
                            lastDateText.value = s.lastMenstruationDate?.toString() ?: ""
                            avgCycleText.value = s.avgCycleLength.toString()
                            autoUpdateAvg.value = s.autoUpdateAverage
                        } else {
                            lastDateText.value = ""
                            avgCycleText.value = "28"
                            autoUpdateAvg.value = true
                        }
                    },
                    enabled = !saving.value,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Reset")
                }
            }

            loadedSettings.value?.let { s ->
                Spacer(Modifier.height(10.dp))
                Divider()
                Text(
                    text = "Current saved values",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text("Last period: ${s.lastMenstruationDate ?: "—"}", style = MaterialTheme.typography.bodySmall)
                Text("Avg cycle: ${s.avgCycleLength} days", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Auto-update average: ${if (s.autoUpdateAverage) "On" else "Off"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun validateInputs(
    lastDateText: String,
    avgCycleText: String
): Triple<LocalDate?, Int, String?> {
    val lastDate = lastDateText.trim().takeIf { it.isNotBlank() }?.let { raw ->
        // Expect ISO (YYYY-MM-DD) consistent with existing dialogs / toString()
        try {
            LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: Exception) {
            return Triple(null, 0, "Invalid last period date. Use YYYY-MM-DD (e.g. 2026-01-15).")
        }
    }

    val avg = avgCycleText.trim().toIntOrNull()
        ?: return Triple(null, 0, "Average cycle length must be a number (e.g. 28).")

    // Conservative range validation (can be adjusted if your existing logic expects wider).
    if (avg < 15 || avg > 60) {
        return Triple(null, 0, "Average cycle length looks wrong. Use a value between 15 and 60.")
    }

    return Triple(lastDate, avg, null)
}
