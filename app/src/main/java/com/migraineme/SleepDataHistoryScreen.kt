package com.migraineme

import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ─── Data models ─────────────────────────────────────────────────────────────

data class SleepDataEntry(
    val table: String,
    val label: String,
    val value: String,
    val source: String
)

@Serializable
private data class SleepRowGeneric(
    val date: String? = null,
    val source: String? = null,
    @SerialName("value_hours") val valueHours: Double? = null,
    @SerialName("value_at") val valueAt: String? = null,
    @SerialName("value_count") val valueCount: Int? = null,
    @SerialName("value_pct") val valuePct: Double? = null,
    @SerialName("value_sws_hm") val valueSwsHm: Double? = null,
    @SerialName("value_rem_hm") val valueRemHm: Double? = null,
    @SerialName("value_light_hm") val valueLightHm: Double? = null
)

// ─── Supabase helpers ────────────────────────────────────────────────────────

private val client = HttpClient {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}

private suspend fun fetchSleepEntriesForDate(
    date: String,
    accessToken: String,
    userId: String
): List<SleepDataEntry> = withContext(Dispatchers.IO) {
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val key = BuildConfig.SUPABASE_ANON_KEY
    val entries = mutableListOf<SleepDataEntry>()

    data class TableDef(val table: String, val label: String, val select: String)

    val tables = listOf(
        TableDef("sleep_duration_daily", "Duration", "date,source,value_hours"),
        TableDef("fell_asleep_time_daily", "Fell Asleep", "date,source,value_at"),
        TableDef("woke_up_time_daily", "Woke Up", "date,source,value_at"),
        TableDef("sleep_score_daily", "Score", "date,source,value_pct"),
        TableDef("sleep_efficiency_daily", "Efficiency", "date,source,value_pct"),
        TableDef("sleep_disturbances_daily", "Disturbances", "date,source,value_count"),
        TableDef("sleep_stages_daily", "Sleep Stages", "date,source,value_sws_hm,value_rem_hm,value_light_hm")
    )

    for (td in tables) {
        try {
            val rows: List<SleepRowGeneric> = client.get("$base/rest/v1/${td.table}") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", key)
                parameter("user_id", "eq.$userId")
                parameter("date", "eq.$date")
                parameter("select", td.select)
            }.body()

            for (row in rows) {
                val src = row.source ?: "unknown"
                val value = when {
                    row.valueHours != null -> {
                        val totalMin = (row.valueHours * 60).toInt()
                        "${totalMin / 60}h ${totalMin % 60}m"
                    }
                    row.valueAt != null -> {
                        try {
                            row.valueAt.substringAfter("T").take(5)
                        } catch (_: Exception) { row.valueAt }
                    }
                    row.valuePct != null -> "${row.valuePct.toInt()}%"
                    row.valueCount != null -> "${row.valueCount}"
                    row.valueSwsHm != null -> {
                        fun fmt(h: Double): String { val m = (h * 60).toInt(); return "${m / 60}h ${m % 60}m" }
                        "Deep ${fmt(row.valueSwsHm)} · REM ${fmt(row.valueRemHm ?: 0.0)} · Light ${fmt(row.valueLightHm ?: 0.0)}"
                    }
                    else -> "—"
                }
                entries.add(SleepDataEntry(td.table, td.label, value, src))
            }
        } catch (e: Exception) {
            Log.e("SleepDataHistory", "Failed to fetch ${td.table}: ${e.message}")
        }
    }
    entries
}

private suspend fun deleteSleepEntry(
    table: String,
    date: String,
    accessToken: String,
    userId: String
): Boolean = withContext(Dispatchers.IO) {
    try {
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val resp = client.delete("$base/rest/v1/$table") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            parameter("user_id", "eq.$userId")
            parameter("date", "eq.$date")
            parameter("source", "eq.manual")
        }
        resp.status.isSuccess()
    } catch (e: Exception) {
        Log.e("SleepDataHistory", "Delete failed: ${e.message}")
        false
    }
}

private suspend fun upsertSleepManualEntry(
    table: String,
    date: String,
    valueField: String,
    value: Any,
    accessToken: String
): Boolean = withContext(Dispatchers.IO) {
    try {
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val body = mapOf("date" to date, "source" to "manual", valueField to value)
        val resp = client.post("$base/rest/v1/$table") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            header("Prefer", "resolution=merge-duplicates,return=minimal")
            parameter("on_conflict", "user_id,source,date")
            contentType(ContentType.Application.Json)
            setBody(listOf(body))
        }
        resp.status.isSuccess()
    } catch (e: Exception) {
        Log.e("SleepDataHistory", "Upsert failed: ${e.message}")
        false
    }
}

// ─── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun SleepDataHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val scope = rememberCoroutineScope()

    var selectedDateStr by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val selectedDate = LocalDate.parse(selectedDateStr)
    val today = LocalDate.now()

    var entries by remember { mutableStateOf<List<SleepDataEntry>>(emptyList()) }
    val sleepConfig = remember { SleepCardConfigStore.load(context) }
    var isLoading by remember { mutableStateOf(true) }

    var editEntry by remember { mutableStateOf<SleepDataEntry?>(null) }
    var editValue by remember { mutableStateOf("") }

    val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d")

    fun loadEntries() {
        scope.launch {
            isLoading = true
            val token = SessionStore.readAccessToken(context) ?: return@launch
            val userId = SessionStore.readUserId(context) ?: return@launch
            entries = fetchSleepEntriesForDate(selectedDateStr, token, userId)
            isLoading = false
        }
    }

    LaunchedEffect(selectedDateStr) { loadEntries() }

    // Edit dialog
    if (editEntry != null) {
        val entry = editEntry!!
        AlertDialog(
            onDismissRequest = { editEntry = null },
            title = { Text("Edit ${entry.label}", color = AppTheme.TitleColor) },
            text = {
                Column {
                    Text(
                        "Current: ${entry.value}",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        when (entry.table) {
                            "sleep_duration_daily" -> "Enter hours (e.g. 7.5)"
                            "fell_asleep_time_daily", "woke_up_time_daily" -> "Enter time (e.g. 23:30)"
                            "sleep_score_daily", "sleep_efficiency_daily" -> "Enter percentage (e.g. 85)"
                            "sleep_disturbances_daily" -> "Enter count (e.g. 3)"
                            else -> "Enter value"
                        },
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editValue,
                        onValueChange = { editValue = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppTheme.AccentPurple,
                            unfocusedBorderColor = AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                            focusedTextColor = AppTheme.TitleColor,
                            unfocusedTextColor = AppTheme.TitleColor
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Delete this entry",
                        color = Color(0xFFE57373),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clickable {
                                scope.launch {
                                    val token = SessionStore.readAccessToken(context) ?: return@launch
                                    val userId = SessionStore.readUserId(context) ?: return@launch
                                    deleteSleepEntry(entry.table, selectedDateStr, token, userId)
                                    editEntry = null
                                    loadEntries()
                                }
                            }
                            .padding(vertical = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val token = SessionStore.readAccessToken(context) ?: return@launch
                        val input = editValue.trim()
                        val (field, parsedValue) = when (entry.table) {
                            "sleep_duration_daily" -> "value_hours" to (input.toDoubleOrNull() ?: return@launch)
                            "fell_asleep_time_daily" -> "value_at" to "${selectedDateStr}T${input}:00"
                            "woke_up_time_daily" -> "value_at" to "${selectedDateStr}T${input}:00"
                            "sleep_score_daily" -> "value_pct" to (input.toDoubleOrNull() ?: return@launch)
                            "sleep_efficiency_daily" -> "value_pct" to (input.toDoubleOrNull() ?: return@launch)
                            "sleep_disturbances_daily" -> "value_count" to (input.toIntOrNull() ?: return@launch)
                            else -> return@launch
                        }
                        upsertSleepManualEntry(entry.table, selectedDateStr, field, parsedValue, token)
                        editEntry = null
                        loadEntries()
                    }
                }) { Text("Save", color = AppTheme.AccentPurple) }
            },
            dismissButton = {
                TextButton(onClick = { editEntry = null }) {
                    Text("Cancel", color = AppTheme.SubtleTextColor)
                }
            },
            containerColor = Color(0xFF1E0A2E)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.FadeColor)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            }

            HeroCard {
                Text(
                    "Sleep Data",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "View and edit sleep entries",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Date navigation
            BaseCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selectedDateStr = selectedDate.minusDays(1).toString() }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous day", tint = AppTheme.AccentPurple)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            when {
                                selectedDate == today -> "Today"
                                selectedDate == today.minusDays(1) -> "Yesterday"
                                else -> selectedDate.format(dateFormatter)
                            },
                            color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                    IconButton(
                        onClick = { selectedDateStr = selectedDate.plusDays(1).toString() },
                        enabled = selectedDate < today
                    ) {
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = "Next day",
                            tint = if (selectedDate < today) AppTheme.AccentPurple else AppTheme.SubtleTextColor.copy(alpha = 0.3f)
                        )
                    }
                }
            }

            // Entries
            BaseCard {
                if (isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AppTheme.AccentPurple, strokeWidth = 2.dp)
                    }
                } else if (entries.isEmpty()) {
                    Text(
                        "No sleep data for this day",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                    )
                } else {
                    // Map table names to config metric keys
                    val tableToMetric = mapOf(
                        "sleep_duration_daily" to SleepCardConfig.METRIC_DURATION,
                        "fell_asleep_time_daily" to SleepCardConfig.METRIC_FELL_ASLEEP,
                        "woke_up_time_daily" to SleepCardConfig.METRIC_WOKE_UP,
                        "sleep_score_daily" to SleepCardConfig.METRIC_SCORE,
                        "sleep_efficiency_daily" to SleepCardConfig.METRIC_EFFICIENCY,
                        "sleep_disturbances_daily" to SleepCardConfig.METRIC_DISTURBANCES,
                        "sleep_stages_daily" to SleepCardConfig.METRIC_STAGES_DEEP
                    )

                    // Best value per metric (prefer non-manual first, fallback manual)
                    val bestByMetric = mutableMapOf<String, SleepDataEntry>()
                    entries.forEach { entry ->
                        val metricKey = tableToMetric[entry.table] ?: return@forEach
                        val existing = bestByMetric[metricKey]
                        if (existing == null || (existing.source == "manual" && entry.source != "manual")) {
                            bestByMetric[metricKey] = entry
                        }
                    }

                    val selectedMetrics = sleepConfig.sleepDisplayMetrics.take(3)
                    val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))

                    // Top 3 selected metrics
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        selectedMetrics.forEachIndexed { index, metric ->
                            val entry = bestByMetric[metric]
                            val value = entry?.value ?: "—"
                            val label = SleepCardConfig.labelFor(metric)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(value, color = slotColors.getOrElse(index) { slotColors.last() }, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.2f))
                    Spacer(Modifier.height(8.dp))
                    Text("All Metrics", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(4.dp))

                    // Group by source
                    val grouped = entries.groupBy { it.source }
                    val sourceOrder = listOf("manual") + grouped.keys.filter { it != "manual" }.sorted()

                    sourceOrder.forEach { source ->
                        val items = grouped[source] ?: return@forEach
                        Text(
                            sourceLabel(source),
                            color = if (source == "manual") AppTheme.AccentPurple else AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(Modifier.height(4.dp))

                        items.forEach { entry ->
                            SleepDataRow(
                                entry = entry,
                                valueColor = AppTheme.SubtleTextColor,
                                onEdit = if (source == "manual") {
                                    {
                                        editEntry = entry
                                        editValue = ""
                                    }
                                } else null,
                                onDelete = if (source == "manual") {
                                    {
                                        scope.launch {
                                            val token = SessionStore.readAccessToken(context) ?: return@launch
                                            val userId = SessionStore.readUserId(context) ?: return@launch
                                            deleteSleepEntry(entry.table, selectedDateStr, token, userId)
                                            loadEntries()
                                        }
                                    }
                                } else null
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
@Composable
private fun SleepDataRow(
    entry: SleepDataEntry,
    valueColor: Color = AppTheme.SubtleTextColor,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.label, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
            Text(entry.value, color = valueColor, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
        }
        if (onEdit != null) {
            Text(
                "✎", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clickable { onEdit() }.padding(8.dp)
            )
        }
        if (onDelete != null) {
            Text(
                "✕", color = Color(0xFFE57373), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clickable { onDelete() }.padding(8.dp)
            )
        }
    }
}

private fun sourceLabel(source: String): String = when (source) {
    "manual" -> "Manual"
    "whoop" -> "WHOOP"
    "health_connect" -> "Health Connect"
    else -> source.replaceFirstChar { it.uppercase() }
}
