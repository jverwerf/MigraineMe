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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ─── Data model ──────────────────────────────────────────────────────────────

data class PhysicalDataEntry(
    val table: String,
    val label: String,
    val value: String,
    val source: String
)

// ─── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun PhysicalDataHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val scope = rememberCoroutineScope()

    var selectedDateStr by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val selectedDate = LocalDate.parse(selectedDateStr)
    val today = LocalDate.now()

    var entries by remember { mutableStateOf<List<PhysicalDataEntry>>(emptyList()) }
    val physicalConfig = remember { PhysicalCardConfigStore.load(context) }
    var isLoading by remember { mutableStateOf(true) }

    val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d")

    fun loadEntries() {
        scope.launch {
            isLoading = true
            val token = SessionStore.readAccessToken(context) ?: return@launch
            val userId = SessionStore.readUserId(context) ?: return@launch
            entries = fetchPhysicalEntriesForDate(selectedDateStr, token, userId)
            isLoading = false
        }
    }

    LaunchedEffect(selectedDateStr) { loadEntries() }

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
                "Physical Health Data",
                color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "View physical health entries by day",
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
                    "No physical health data for this day",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                )
            } else {
                // Map table names to config metric keys
                val tableToMetric = mapOf(
                    "recovery_score_daily" to PhysicalCardConfig.METRIC_RECOVERY,
                    "hrv_daily" to PhysicalCardConfig.METRIC_HRV,
                    "resting_hr_daily" to PhysicalCardConfig.METRIC_RESTING_HR,
                    "spo2_daily" to PhysicalCardConfig.METRIC_SPO2,
                    "skin_temp_daily" to PhysicalCardConfig.METRIC_SKIN_TEMP,
                    "respiratory_rate_daily" to PhysicalCardConfig.METRIC_RESPIRATORY_RATE,
                    "stress_index_daily" to PhysicalCardConfig.METRIC_STRESS,
                    "time_in_high_hr_zones_daily" to PhysicalCardConfig.METRIC_HIGH_HR_ZONES,
                    "steps_daily" to PhysicalCardConfig.METRIC_STEPS,
                    "weight_daily" to PhysicalCardConfig.METRIC_WEIGHT,
                    "body_fat_daily" to PhysicalCardConfig.METRIC_BODY_FAT,
                    "blood_pressure_daily" to PhysicalCardConfig.METRIC_BLOOD_PRESSURE,
                    "blood_glucose_daily" to PhysicalCardConfig.METRIC_BLOOD_GLUCOSE
                )

                // Best value per metric (prefer non-manual first, fallback manual)
                val bestByMetric = mutableMapOf<String, PhysicalDataEntry>()
                entries.forEach { entry ->
                    val metricKey = tableToMetric[entry.table] ?: return@forEach
                    val existing = bestByMetric[metricKey]
                    if (existing == null || (existing.source == "manual" && entry.source != "manual")) {
                        bestByMetric[metricKey] = entry
                    }
                }

                val selectedMetrics = physicalConfig.physicalDisplayMetrics.take(3)
                val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))

                // Top 3 selected metrics
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    selectedMetrics.forEachIndexed { index, metric ->
                        val entry = bestByMetric[metric]
                        val value = entry?.value ?: "—"
                        val label = PhysicalCardConfig.labelFor(metric)
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

                // All metrics — flat list, no source grouping
                // Deduplicate: keep best entry per table (prefer non-manual)
                val bestByTable = mutableMapOf<String, PhysicalDataEntry>()
                entries.forEach { entry ->
                    val existing = bestByTable[entry.table]
                    if (existing == null || (existing.source == "manual" && entry.source != "manual")) {
                        bestByTable[entry.table] = entry
                    }
                }

                bestByTable.values
                    .filter { tableToMetric[it.table] !in selectedMetrics.toSet() }
                    .forEach { entry ->
                        PhysicalDataRow(entry = entry)
                    }
            }
        }
    }
}

@Composable
private fun PhysicalDataRow(
    entry: PhysicalDataEntry
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.label, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
            Text(entry.value, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
        }
    }
}

// ─── Supabase fetch ──────────────────────────────────────────────────────────

private suspend fun fetchPhysicalEntriesForDate(
    date: String,
    accessToken: String,
    userId: String
): List<PhysicalDataEntry> = withContext(Dispatchers.IO) {
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val key = BuildConfig.SUPABASE_ANON_KEY
    val entries = mutableListOf<PhysicalDataEntry>()
    val client = okhttp3.OkHttpClient()

    data class TableDef(val table: String, val label: String, val select: String)

    val tables = listOf(
        TableDef("recovery_score_daily", "Recovery", "date,source,value_pct"),
        TableDef("hrv_daily", "HRV", "date,source,value_rmssd_ms"),
        TableDef("resting_hr_daily", "Resting HR", "date,source,value_bpm"),
        TableDef("spo2_daily", "SpO2", "date,source,value_pct"),
        TableDef("skin_temp_daily", "Skin Temp", "date,source,value_celsius"),
        TableDef("respiratory_rate_daily", "Resp. Rate", "date,source,value_bpm"),
        TableDef("stress_index_daily", "Stress", "date,source,value"),
        TableDef("time_in_high_hr_zones_daily", "High HR Zones", "date,source,value_minutes"),
        TableDef("steps_daily", "Steps", "date,source,value_count"),
        TableDef("weight_daily", "Weight", "date,source,value_kg"),
        TableDef("body_fat_daily", "Body Fat", "date,source,value_pct"),
        TableDef("blood_pressure_daily", "Blood Pressure", "date,source,value_systolic,value_diastolic"),
        TableDef("blood_glucose_daily", "Blood Glucose", "date,source,value_mgdl")
    )

    for (td in tables) {
        try {
            val url = "$base/rest/v1/${td.table}?user_id=eq.$userId&date=eq.$date&select=${td.select}"
            val request = okhttp3.Request.Builder().url(url).get()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (response.isSuccessful && !body.isNullOrBlank()) {
                val arr = org.json.JSONArray(body)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val src = obj.optString("source", "unknown")
                    val value = formatPhysicalEntryValue(td.table, obj)
                    entries.add(PhysicalDataEntry(td.table, td.label, value, src))
                }
            }
        } catch (e: Exception) {
            Log.e("PhysicalDataHistory", "Failed to fetch ${td.table}: ${e.message}")
        }
    }
    entries
}

private fun formatPhysicalEntryValue(table: String, obj: org.json.JSONObject): String {
    return when (table) {
        "recovery_score_daily" -> {
            val v = obj.optDouble("value_pct")
            if (!v.isNaN()) "${v.toInt()}%" else "—"
        }
        "hrv_daily" -> {
            val v = obj.optDouble("value_rmssd_ms")
            if (!v.isNaN()) "${v.toInt()} ms" else "—"
        }
        "resting_hr_daily" -> {
            val v = obj.optDouble("value_bpm")
            if (!v.isNaN()) "${v.toInt()} bpm" else "—"
        }
        "spo2_daily" -> {
            val v = obj.optDouble("value_pct")
            if (!v.isNaN()) "${v.toInt()}%" else "—"
        }
        "skin_temp_daily" -> {
            val v = obj.optDouble("value_celsius")
            if (!v.isNaN()) String.format("%.1f°C", v) else "—"
        }
        "respiratory_rate_daily" -> {
            val v = obj.optDouble("value_bpm")
            if (!v.isNaN()) String.format("%.1f bpm", v) else "—"
        }
        "stress_index_daily" -> {
            val v = obj.optDouble("value")
            if (!v.isNaN()) String.format("%.0f", v) else "—"
        }
        "time_in_high_hr_zones_daily" -> {
            val v = obj.optDouble("value_minutes")
            if (!v.isNaN()) "${v.toInt()} min" else "—"
        }
        "steps_daily" -> {
            val v = obj.optInt("value_count", -1)
            if (v >= 0) "%,d".format(v) else "—"
        }
        "weight_daily" -> {
            val v = obj.optDouble("value_kg")
            if (!v.isNaN()) String.format("%.1f kg", v) else "—"
        }
        "body_fat_daily" -> {
            val v = obj.optDouble("value_pct")
            if (!v.isNaN()) String.format("%.1f%%", v) else "—"
        }
        "blood_pressure_daily" -> {
            val sys = obj.optDouble("value_systolic")
            val dia = obj.optDouble("value_diastolic")
            if (!sys.isNaN() && !dia.isNaN()) "${sys.toInt()}/${dia.toInt()} mmHg" else "—"
        }
        "blood_glucose_daily" -> {
            val v = obj.optDouble("value_mgdl")
            if (!v.isNaN()) String.format("%.0f mg/dL", v) else "—"
        }
        else -> "—"
    }
}
