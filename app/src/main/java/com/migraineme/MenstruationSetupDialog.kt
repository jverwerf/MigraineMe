package com.migraineme

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Dialog shown on first menstruation toggle ON
 *
 * Reads historical periods from Health Connect
 * Calculates weighted average (last 6 cycles)
 * Allows user to confirm or edit values
 */
@Composable
fun MenstruationSetupDialog(
    onConfirm: (lastDate: LocalDate?, avgCycle: Int, autoUpdate: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var calculatedLastDate by remember { mutableStateOf<LocalDate?>(null) }
    var calculatedAvgCycle by remember { mutableIntStateOf(28) }
    var hasHistoricalData by remember { mutableStateOf(false) }

    var lastDateInput by remember { mutableStateOf("") }
    var avgCycleInput by remember { mutableStateOf("28") }
    var autoUpdate by remember { mutableStateOf(true) }

    var showDatePicker by remember { mutableStateOf(false) }

    // Load historical data from Health Connect
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val hc = HealthConnectClient.getOrCreate(context)
                val granted = hc.permissionController.getGrantedPermissions()

                if (HealthPermission.getReadPermission(MenstruationPeriodRecord::class) !in granted) {
                    isLoading = false
                    return@withContext
                }

                // Read last 2 years of periods
                val end = Instant.now()
                val start = end.minus(730, ChronoUnit.DAYS)

                val request = ReadRecordsRequest(
                    recordType = MenstruationPeriodRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )

                val response = hc.readRecords(request)

                if (response.records.isNotEmpty()) {
                    hasHistoricalData = true

                    // Convert to MenstruationPeriod objects
                    val periods = response.records.map { record ->
                        MenstruationPeriod(
                            startDate = record.startTime.atZone(ZoneOffset.UTC).toLocalDate(),
                            endDate = record.endTime.atZone(ZoneOffset.UTC).toLocalDate()
                        )
                    }.sortedBy { it.startDate }

                    // Find most recent period
                    calculatedLastDate = periods.maxByOrNull { it.startDate }?.startDate

                    // Calculate weighted average (last 6 cycles)
                    if (periods.size >= 2) {
                        calculatedAvgCycle = MenstruationCalculator.calculateWeightedAverage(periods)
                    }

                    // Set input fields
                    lastDateInput = calculatedLastDate?.toString() ?: ""
                    avgCycleInput = calculatedAvgCycle.toString()

                    android.util.Log.d("MenstruationSetup", "Found ${periods.size} historical periods")
                    android.util.Log.d("MenstruationSetup", "Last period: $calculatedLastDate, Avg cycle: $calculatedAvgCycle")
                }

            } catch (e: Exception) {
                android.util.Log.e("MenstruationSetup", "Failed to load history: ${e.message}", e)
            } finally {
                isLoading = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Menstruation Cycle Setup") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator()
                    Text("Loading your period data from Health Connect...")
                } else {
                    if (hasHistoricalData) {
                        Text(
                            "Based on your last 6 cycles",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            "No historical data found. Please enter manually.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Last Period Date
                    OutlinedTextField(
                        value = lastDateInput,
                        onValueChange = { lastDateInput = it },
                        label = { Text("Last Period Date") },
                        placeholder = { Text("YYYY-MM-DD") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Average Cycle Length
                    OutlinedTextField(
                        value = avgCycleInput,
                        onValueChange = {
                            if (it.all { char -> char.isDigit() } && it.length <= 2) {
                                avgCycleInput = it
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

                    Spacer(Modifier.height(8.dp))

                    // Auto-update toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Auto-update average",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = autoUpdate,
                            onCheckedChange = { autoUpdate = it }
                        )
                    }

                    Text(
                        "Recalculate average when new periods are logged",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isLoading,
                onClick = {
                    val lastDate = try {
                        if (lastDateInput.isNotBlank()) LocalDate.parse(lastDateInput) else null
                    } catch (e: Exception) {
                        null
                    }

                    val avgCycle = avgCycleInput.toIntOrNull() ?: 28

                    onConfirm(lastDate, avgCycle, autoUpdate)
                }
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}