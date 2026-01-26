package com.migraineme

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * DIAGNOSTIC ACTIVITY FOR NUTRITION BACKFILL DEBUGGING
 *
 * This activity helps identify why the 7-day backfill isn't working by:
 * 1. Checking Health Connect permissions
 * 2. Reading nutrition data directly from Health Connect for the last 7 days
 * 3. Checking the local nutrition_outbox database
 * 4. Comparing what's in Health Connect vs what's in the outbox
 *
 * To use:
 * 1. Add this activity to AndroidManifest.xml
 * 2. Add a button in ThirdPartyConnectionsScreen to launch it
 * 3. Run diagnostics to see exactly what data exists where
 */
@OptIn(ExperimentalMaterial3Api::class)
class NutritionDiagnosticActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                DiagnosticScreen()
            }
        }
    }

    @Composable
    fun DiagnosticScreen() {
        val scope = rememberCoroutineScope()
        var diagnosticResults by remember { mutableStateOf<DiagnosticResults?>(null) }
        var isRunning by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Nutrition Backfill Diagnostic") })
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            isRunning = true
                            error = null
                            try {
                                diagnosticResults = runDiagnostics()
                            } catch (e: Exception) {
                                error = "Error: ${e.message}"
                                Log.e(TAG, "Diagnostic failed", e)
                            } finally {
                                isRunning = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRunning
                ) {
                    Text(if (isRunning) "Running..." else "Run Diagnostics")
                }

                error?.let { err ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(
                            text = err,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                diagnosticResults?.let { results ->
                    DiagnosticResultsDisplay(results)
                }
            }
        }
    }

    @Composable
    fun DiagnosticResultsDisplay(results: DiagnosticResults) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Summary Card
            item {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("SUMMARY", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))

                        ResultRow("Permission Granted", results.hasPermission.toString())
                        ResultRow("Health Connect Available", results.healthConnectAvailable.toString())
                        ResultRow("Sync Token Exists", results.syncTokenExists.toString())
                        ResultRow("Last Sync Token Update", results.lastTokenUpdate ?: "Never")
                        ResultRow("Last Outbox Push", results.lastPushRun ?: "Never")

                        Spacer(Modifier.height(8.dp))
                        Divider()
                        Spacer(Modifier.height(8.dp))

                        ResultRow(
                            "Health Connect Records (7d)",
                            "${results.healthConnectRecords.size}",
                            if (results.healthConnectRecords.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        ResultRow(
                            "Outbox Records",
                            "${results.outboxRecords.size}",
                            if (results.outboxRecords.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )

                        val missing = results.healthConnectRecords.filterNot { hcId ->
                            results.outboxRecords.any { it.healthConnectId == hcId }
                        }
                        ResultRow(
                            "Missing in Outbox",
                            "${missing.size}",
                            if (missing.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Health Connect Records
            item {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("HEALTH CONNECT RECORDS (Last 7 Days)", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))

                        if (results.healthConnectRecords.isEmpty()) {
                            Text("❌ No records found!", color = MaterialTheme.colorScheme.error)
                            Text(
                                "This means Cronometer (or other nutrition apps) haven't synced data to Health Connect, " +
                                        "OR the permission isn't granted properly.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }

            items(results.healthConnectRecordDetails) { detail ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (detail.inOutbox)
                            MaterialTheme.colorScheme.surfaceVariant
                        else
                            MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            detail.name ?: "Unknown Food",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(detail.time, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Calories: ${detail.calories ?: "N/A"} | ID: ${detail.id.take(8)}...",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            if (detail.inOutbox) "✓ In outbox" else "❌ MISSING FROM OUTBOX",
                            color = if (detail.inOutbox)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Database State
            item {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("DATABASE STATE", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))

                        if (results.syncTokenExists) {
                            Text("✓ Sync token exists")
                            Text("This means backfill already ran. Only NEW changes will be detected.",
                                style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("❌ No sync token - backfill should trigger on next worker run")
                        }
                    }
                }
            }

            // Recommendations
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("🔍 WHAT TO CHECK", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))

                        if (!results.hasPermission) {
                            Text("❌ ISSUE: Permission not granted!")
                            Text("→ Grant Health Connect permission first", style = MaterialTheme.typography.bodySmall)
                        } else if (results.healthConnectRecords.isEmpty()) {
                            Text("❌ ISSUE: No data in Health Connect!")
                            Text("→ Check if Cronometer is syncing to Health Connect", style = MaterialTheme.typography.bodySmall)
                            Text("→ Open Cronometer, go to Settings → Health Connect", style = MaterialTheme.typography.bodySmall)
                            Text("→ Ensure 'Sync to Health Connect' is enabled", style = MaterialTheme.typography.bodySmall)
                        } else if (results.outboxRecords.isEmpty() && !results.syncTokenExists) {
                            Text("⚠️ ISSUE: HC has data but outbox is empty and no sync token")
                            Text("→ The HealthConnectNutritionChangesWorker hasn't run yet", style = MaterialTheme.typography.bodySmall)
                            Text("→ It should trigger automatically within 1 hour", style = MaterialTheme.typography.bodySmall)
                            Text("→ Or you can trigger it manually (see code)", style = MaterialTheme.typography.bodySmall)
                        } else if (results.healthConnectRecordDetails.any { !it.inOutbox }) {
                            Text("⚠️ ISSUE: Some HC records missing from outbox")
                            Text("→ This indicates the backfill didn't capture everything", style = MaterialTheme.typography.bodySmall)
                            Text("→ You may need to force a re-backfill (see recommendations below)", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("✓ Data looks good!")
                            Text("→ If data still isn't in Supabase, check NutritionOutboxPushWorker logs", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ResultRow(label: String, value: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }

    private suspend fun runDiagnostics(): DiagnosticResults = withContext(Dispatchers.IO) {
        val results = DiagnosticResults()

        // 1. Check Health Connect availability and permissions
        try {
            val hc = HealthConnectClient.getOrCreate(applicationContext)
            results.healthConnectAvailable = true

            val granted = hc.permissionController.getGrantedPermissions()
            results.hasPermission = HealthPermission.getReadPermission(NutritionRecord::class) in granted

            Log.d(TAG, "Health Connect available: ${results.healthConnectAvailable}")
            Log.d(TAG, "Permission granted: ${results.hasPermission}")

            // 2. Read nutrition records from Health Connect for last 7 days
            if (results.hasPermission) {
                val end = Instant.now()
                val start = end.minus(7, ChronoUnit.DAYS)

                val request = ReadRecordsRequest(
                    recordType = NutritionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )

                val response = hc.readRecords(request)
                results.healthConnectRecords = response.records.map { it.metadata.id }

                Log.d(TAG, "Found ${results.healthConnectRecords.size} HC records in last 7 days")

                // Get details for display
                results.healthConnectRecordDetails = response.records.map { record ->
                    val date = record.startTime.atZone(ZoneOffset.UTC).toLocalDate()
                    val time = record.startTime.atZone(ZoneOffset.systemDefault())
                        .format(DateTimeFormatter.ofPattern("MMM dd, HH:mm"))

                    NutritionRecordDetail(
                        id = record.metadata.id,
                        name = record.name,
                        time = time,
                        calories = record.energy?.inKilocalories?.toInt()?.toString(),
                        inOutbox = false // Will be updated later
                    )
                }
            }

            // 3. Check database state
            val db = NutritionSyncDatabase.get(applicationContext)
            val dao = db.dao()

            val syncState = dao.getSyncState()
            results.syncTokenExists = !syncState?.nutritionChangesToken.isNullOrBlank()
            results.lastTokenUpdate = syncState?.lastHourlyRunAtEpochMs?.let { ms ->
                Instant.ofEpochMilli(ms).atZone(ZoneOffset.systemDefault())
                    .format(DateTimeFormatter.ofPattern("MMM dd HH:mm:ss"))
            }
            results.lastPushRun = syncState?.lastPushRunAtEpochMs?.let { ms ->
                Instant.ofEpochMilli(ms).atZone(ZoneOffset.systemDefault())
                    .format(DateTimeFormatter.ofPattern("MMM dd HH:mm:ss"))
            }

            // 4. Check outbox
            val outboxItems = dao.getOutboxBatch(limit = 1000)
            results.outboxRecords = outboxItems

            Log.d(TAG, "Outbox contains ${outboxItems.size} items")

            // 5. Mark which HC records are in the outbox
            val outboxIds = outboxItems.map { it.healthConnectId }.toSet()
            results.healthConnectRecordDetails = results.healthConnectRecordDetails.map { detail ->
                detail.copy(inOutbox = detail.id in outboxIds)
            }

            Log.d(TAG, "Diagnostic complete")

        } catch (e: Exception) {
            Log.e(TAG, "Diagnostic error", e)
            throw e
        }

        results
    }

    data class DiagnosticResults(
        var healthConnectAvailable: Boolean = false,
        var hasPermission: Boolean = false,
        var healthConnectRecords: List<String> = emptyList(),
        var healthConnectRecordDetails: List<NutritionRecordDetail> = emptyList(),
        var outboxRecords: List<NutritionOutboxEntity> = emptyList(),
        var syncTokenExists: Boolean = false,
        var lastTokenUpdate: String? = null,
        var lastPushRun: String? = null
    )

    data class NutritionRecordDetail(
        val id: String,
        val name: String?,
        val time: String,
        val calories: String?,
        val inOutbox: Boolean
    )

    companion object {
        private const val TAG = "NutritionDiagnostic"
    }
}