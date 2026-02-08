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

data class MentalDataEntry(
    val table: String,
    val label: String,
    val value: String,
    val source: String
)

// ─── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun MentalDataHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val scope = rememberCoroutineScope()

    var selectedDateStr by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val selectedDate = LocalDate.parse(selectedDateStr)
    val today = LocalDate.now()

    var entries by remember { mutableStateOf<List<MentalDataEntry>>(emptyList()) }
    val mentalConfig = remember { MentalCardConfigStore.load(context) }
    var isLoading by remember { mutableStateOf(true) }

    val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d")

    fun loadEntries() {
        scope.launch {
            isLoading = true
            val token = SessionStore.readAccessToken(context) ?: return@launch
            val userId = SessionStore.readUserId(context) ?: return@launch
            entries = fetchMentalEntriesForDate(selectedDateStr, token, userId)
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
                "Mental Health Data",
                color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "View mental health entries by day",
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
            } else {
                val tableToMetric = mapOf(
                    "stress_index_daily" to MentalCardConfig.METRIC_STRESS,
                    "screen_time_daily" to MentalCardConfig.METRIC_SCREEN_TIME,
                    "screen_time_late_night" to MentalCardConfig.METRIC_LATE_SCREEN_TIME,
                    "ambient_noise_index_daily" to MentalCardConfig.METRIC_NOISE,
                    "phone_brightness_daily" to MentalCardConfig.METRIC_BRIGHTNESS,
                    "phone_volume_daily" to MentalCardConfig.METRIC_VOLUME,
                    "phone_dark_mode_daily" to MentalCardConfig.METRIC_DARK_MODE,
                    "phone_unlock_daily" to MentalCardConfig.METRIC_UNLOCKS
                )

                val bestByMetric = mutableMapOf<String, MentalDataEntry>()
                entries.forEach { entry ->
                    val metricKey = tableToMetric[entry.table] ?: return@forEach
                    val existing = bestByMetric[metricKey]
                    if (existing == null || (existing.source == "manual" && entry.source != "manual")) {
                        bestByMetric[metricKey] = entry
                    }
                }

                val selectedMetrics = mentalConfig.mentalDisplayMetrics.take(3)
                val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))

                // Top 3 selected metrics
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    selectedMetrics.forEachIndexed { index, metric ->
                        val entry = bestByMetric[metric]
                        val value = entry?.value ?: "—"
                        val label = MentalCardConfig.labelFor(metric)
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

                val bestByTable = mutableMapOf<String, MentalDataEntry>()
                entries.forEach { entry ->
                    val existing = bestByTable[entry.table]
                    if (existing == null || (existing.source == "manual" && entry.source != "manual")) {
                        bestByTable[entry.table] = entry
                    }
                }

                // Show ALL metrics (not just ones with data), with "—" for missing
                val allMetricsExceptSelected = MentalCardConfig.ALL_MENTAL_METRICS
                    .filter { it !in selectedMetrics.toSet() }

                allMetricsExceptSelected.forEach { metric ->
                    val table = MentalCardConfig.metricToTable(metric)
                    val entry = bestByTable[table]
                    MentalDataRow(
                        entry = entry ?: MentalDataEntry(
                            table = table,
                            label = MentalCardConfig.labelFor(metric),
                            value = "—",
                            source = ""
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun MentalDataRow(entry: MentalDataEntry) {
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

private suspend fun fetchMentalEntriesForDate(
    date: String,
    accessToken: String,
    userId: String
): List<MentalDataEntry> = withContext(Dispatchers.IO) {
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val key = BuildConfig.SUPABASE_ANON_KEY
    val entries = mutableListOf<MentalDataEntry>()
    val client = okhttp3.OkHttpClient()

    data class TableDef(val table: String, val label: String, val select: String)

    val tables = listOf(
        TableDef("stress_index_daily", "Stress", "date,value"),
        TableDef("screen_time_daily", "Screen Time", "date,source,total_hours"),
        TableDef("screen_time_late_night", "Late Screen Time", "date,source,value_hours"),
        TableDef("ambient_noise_index_daily", "Noise", "date,source,day_max_lmax,day_mean_lmean,day_min_lmean"),
        TableDef("phone_brightness_daily", "Brightness", "date,source,value_mean"),
        TableDef("phone_volume_daily", "Volume", "date,source,value_mean_pct"),
        TableDef("phone_dark_mode_daily", "Dark Mode", "date,source,value_hours"),
        TableDef("phone_unlock_daily", "Unlocks", "date,source,value_count")
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
                    val value = formatMentalEntryValue(td.table, obj)
                    entries.add(MentalDataEntry(td.table, td.label, value, src))
                }
            }
        } catch (e: Exception) {
            Log.e("MentalDataHistory", "Failed to fetch ${td.table}: ${e.message}")
        }
    }

    // Live samples fallback for today — phone behavior + noise
    val today = java.time.LocalDate.now().toString()
    if (date == today) {
        val existingTables = entries.map { it.table }.toSet()

        // Screen time: fall back to screen_time_live for today
        if ("screen_time_daily" !in existingTables) {
            try {
                val url = "$base/rest/v1/screen_time_live?user_id=eq.$userId&date=eq.$date&select=value_hours"
                val request = okhttp3.Request.Builder().url(url).get()
                    .addHeader("apikey", key)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val arr = org.json.JSONArray(body)
                    if (arr.length() > 0) {
                        val v = arr.getJSONObject(0).optDouble("value_hours")
                        if (!v.isNaN()) {
                            entries.add(MentalDataEntry("screen_time_daily", "Screen Time", String.format("%.1fh", v), "live"))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MentalDataHistory", "screen_time_live fallback failed: ${e.message}")
            }
        }

        data class SamplesDef(
            val dailyTable: String,
            val samplesTable: String,
            val label: String,
            val tsColumn: String,
            val aggregation: String, // "avg", "max_int", "dark_mode", "noise"
            val column: String
        )

        val sampleFallbacks = listOf(
            SamplesDef("phone_brightness_daily", "phone_brightness_samples", "Brightness", "sampled_at", "avg", "value"),
            SamplesDef("phone_volume_daily", "phone_volume_samples", "Volume", "sampled_at", "avg", "value_pct"),
            SamplesDef("phone_unlock_daily", "phone_unlock_samples", "Unlocks", "sampled_at", "max_int", "value_count"),
            SamplesDef("phone_dark_mode_daily", "phone_dark_mode_samples", "Dark Mode", "sampled_at", "dark_mode", "is_dark"),
            SamplesDef("ambient_noise_index_daily", "ambient_noise_samples", "Noise", "start_ts", "noise", "l_mean")
        )

        for (sf in sampleFallbacks) {
            if (sf.dailyTable in existingTables) continue
            try {
                val url = "$base/rest/v1/${sf.samplesTable}?user_id=eq.$userId&${sf.tsColumn}=gte.${date}T00:00:00&${sf.tsColumn}=lt.${date}T23:59:59&select=${sf.column}"
                val request = okhttp3.Request.Builder().url(url).get()
                    .addHeader("apikey", key)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val arr = org.json.JSONArray(body)
                    if (arr.length() > 0) {
                        val value = when (sf.aggregation) {
                            "avg" -> {
                                val vals = (0 until arr.length()).mapNotNull {
                                    arr.getJSONObject(it).optDouble(sf.column).takeIf { v -> !v.isNaN() }
                                }
                                if (vals.isNotEmpty()) {
                                    if (sf.dailyTable == "phone_volume_daily") "${vals.average().toInt()}%"
                                    else String.format("%.0f", vals.average())
                                } else null
                            }
                            "max_int" -> {
                                val vals = (0 until arr.length()).mapNotNull {
                                    val v = arr.getJSONObject(it).optInt(sf.column, Int.MIN_VALUE)
                                    if (v != Int.MIN_VALUE) v else null
                                }
                                vals.maxOrNull()?.toString()
                            }
                            "dark_mode" -> {
                                val total = arr.length()
                                val darkCount = (0 until total).count { arr.getJSONObject(it).optBoolean("is_dark", false) }
                                val hours = (darkCount.toDouble() / total) * 24.0
                                String.format("%.1fh", hours)
                            }
                            "noise" -> {
                                val lMeans = (0 until arr.length()).mapNotNull {
                                    arr.getJSONObject(it).optDouble("l_mean").takeIf { v -> !v.isNaN() }
                                }
                                if (lMeans.isNotEmpty()) {
                                    String.format("Avg: %.0f dB", lMeans.average())
                                } else null
                            }
                            else -> null
                        }
                        if (value != null) {
                            entries.add(MentalDataEntry(sf.dailyTable, sf.label, value, "live"))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MentalDataHistory", "Samples fallback failed for ${sf.samplesTable}: ${e.message}")
            }
        }
    }

    entries
}

private fun formatMentalEntryValue(table: String, obj: org.json.JSONObject): String {
    return when (table) {
        "stress_index_daily" -> {
            val v = obj.optDouble("value")
            if (!v.isNaN()) String.format("%.0f", v) else "—"
        }
        "screen_time_daily" -> {
            val v = obj.optDouble("total_hours")
            if (!v.isNaN()) String.format("%.1fh", v) else "—"
        }
        "screen_time_late_night" -> {
            val v = obj.optDouble("value_hours")
            if (!v.isNaN()) String.format("%.1fh", v) else "—"
        }
        "ambient_noise_index_daily" -> {
            val high = obj.optDouble("day_max_lmax")
            val avg = obj.optDouble("day_mean_lmean")
            val low = obj.optDouble("day_min_lmean")
            if (!avg.isNaN()) {
                val parts = mutableListOf<String>()
                if (!low.isNaN()) parts.add("Low: ${String.format("%.0f", low)} dB")
                parts.add("Avg: ${String.format("%.0f", avg)} dB")
                if (!high.isNaN()) parts.add("High: ${String.format("%.0f", high)} dB")
                parts.joinToString(" • ")
            } else "—"
        }
        "phone_brightness_daily" -> {
            val v = obj.optDouble("value_mean")
            if (!v.isNaN()) String.format("%.0f", v) else "—"
        }
        "phone_volume_daily" -> {
            val v = obj.optDouble("value_mean_pct")
            if (!v.isNaN()) "${v.toInt()}%" else "—"
        }
        "phone_dark_mode_daily" -> {
            val v = obj.optDouble("value_hours")
            if (!v.isNaN()) String.format("%.1fh", v) else "—"
        }
        "phone_unlock_daily" -> {
            val v = obj.optInt("value_count", -1)
            if (v >= 0) "$v" else "—"
        }
        else -> "—"
    }
}
