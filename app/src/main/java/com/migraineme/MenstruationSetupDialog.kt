package com.migraineme

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Calendar

/**
 * Setup dialog for menstruation tracking with date picker
 */
@Composable
fun MenstruationSetupDialog(
    onConfirm: (LocalDate?, Int, Boolean) -> Unit,
    onDismiss: () -> Unit,
    autoFetchHistory: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Loading state
    var isLoading by remember { mutableStateOf(autoFetchHistory) }
    var historicalData by remember { mutableStateOf<HealthConnectMenstruationHistoryFetcher.HistoricalData?>(null) }

    // Form state
    var lastDate by remember { mutableStateOf<LocalDate?>(null) }
    var lastDateDisplay by remember { mutableStateOf("Tap to select date") }
    var avgCycleText by remember { mutableStateOf("28") }
    var autoUpdate by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Fetch historical data on launch if requested
    LaunchedEffect(autoFetchHistory) {
        if (autoFetchHistory) {
            isLoading = true
            withContext(Dispatchers.IO) {
                try {
                    val data = HealthConnectMenstruationHistoryFetcher.fetchHistoricalData(context)
                    historicalData = data

                    // Pre-fill form with suggestions
                    withContext(Dispatchers.Main) {
                        if (data.suggestedLastDate != null) {
                            lastDate = data.suggestedLastDate
                            lastDateDisplay = data.suggestedLastDate.toString()
                        }
                        if (data.suggestedAvgCycle != null) {
                            avgCycleText = data.suggestedAvgCycle.toString()
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MenstruationSetup", "Failed to fetch history: ${e.message}")
                } finally {
                    withContext(Dispatchers.Main) {
                        isLoading = false
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Menstruation Tracking Setup") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isLoading) {
                    // Loading state
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "Analyzing your cycle history...",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "This may take up to 30 seconds",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // Show result message
                    if (historicalData != null) {
                        val data = historicalData!!
                        if (data.periods.isNotEmpty()) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "✓ Found ${data.periods.size} periods in your history!",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "We've pre-filled your information below. You can edit if needed.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        } else {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "We couldn't find any period data",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Please enter your information manually below.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    // Form fields
                    Text(
                        "Help us track your cycle by providing some basic information:",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    // Date picker button for last period
                    Column {
                        Text(
                            "Last Period Start Date",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = {
                                val cal = Calendar.getInstance()
                                if (lastDate != null) {
                                    cal.set(lastDate!!.year, lastDate!!.monthValue - 1, lastDate!!.dayOfMonth)
                                }

                                DatePickerDialog(
                                    context,
                                    { _, year, month, day ->
                                        lastDate = LocalDate.of(year, month + 1, day)
                                        lastDateDisplay = lastDate.toString()
                                        error = null
                                    },
                                    cal.get(Calendar.YEAR),
                                    cal.get(Calendar.MONTH),
                                    cal.get(Calendar.DAY_OF_MONTH)
                                ).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(lastDateDisplay)
                        }
                    }

                    OutlinedTextField(
                        value = avgCycleText,
                        onValueChange = {
                            avgCycleText = it
                            error = null
                        },
                        label = { Text("Average Cycle Length (days)") },
                        placeholder = { Text("28") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = error != null
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Auto-update average",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Recalculate when new periods are logged",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoUpdate,
                            onCheckedChange = { autoUpdate = it }
                        )
                    }

                    if (error != null) {
                        Text(
                            error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!isLoading) {
                TextButton(
                    onClick = {
                        // Validate
                        val avgCycle = avgCycleText.toIntOrNull()
                        if (avgCycle == null || avgCycle !in 14..60) {
                            error = "Average cycle must be between 14-60 days"
                            return@TextButton
                        }

                        onConfirm(lastDate, avgCycle, autoUpdate)
                    }
                ) {
                    Text("Start Tracking")
                }
            }
        },
        dismissButton = {
            if (!isLoading) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}