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

data class WeatherDataEntry(
    val table: String,
    val label: String,
    val value: String,
    val unit: String,
    val source: String
)

@Serializable
private data class WeatherRowGeneric(
    val date: String? = null,
    val source: String? = null,
    @SerialName("temp_c_mean") val tempMean: Double? = null,
    @SerialName("pressure_hpa_mean") val pressureMean: Double? = null,
    @SerialName("humidity_pct_mean") val humidityMean: Double? = null,
    @SerialName("wind_speed_mps_mean") val windSpeedMean: Double? = null,
    @SerialName("uv_index_max") val uvIndexMax: Double? = null,
    @SerialName("is_thunderstorm_day") val isThunderstormDay: Boolean? = null,
    @SerialName("weather_code") val weatherCode: Int? = null
)

// ─── Supabase helpers ────────────────────────────────────────────────────────

private val client = HttpClient {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}

private val weatherFields = listOf(
    Triple("temp_c_mean", "Temperature", "°C"),
    Triple("pressure_hpa_mean", "Pressure", "hPa"),
    Triple("humidity_pct_mean", "Humidity", "%"),
    Triple("wind_speed_mps_mean", "Wind Speed", "m/s"),
    Triple("uv_index_max", "UV Index", "")
)

private suspend fun fetchWeatherEntriesForDate(
    date: String,
    accessToken: String,
    userId: String
): List<WeatherDataEntry> = withContext(Dispatchers.IO) {
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val entries = mutableListOf<WeatherDataEntry>()

    try {
        val rows: List<WeatherRowGeneric> = client.get("$base/rest/v1/user_weather_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            parameter("user_id", "eq.$userId")
            parameter("date", "eq.$date")
            parameter("select", "date,source,temp_c_mean,pressure_hpa_mean,humidity_pct_mean,wind_speed_mps_mean,uv_index_max,is_thunderstorm_day")
        }.body()

        for (row in rows) {
            val src = row.source ?: "api"
            row.tempMean?.let {
                entries.add(WeatherDataEntry("user_weather_daily", "Temperature", String.format("%.1f", it), "°C", src))
            }
            row.pressureMean?.let {
                entries.add(WeatherDataEntry("user_weather_daily", "Pressure", String.format("%.0f", it), "hPa", src))
            }
            row.humidityMean?.let {
                entries.add(WeatherDataEntry("user_weather_daily", "Humidity", String.format("%.0f", it), "%", src))
            }
            row.windSpeedMean?.let {
                entries.add(WeatherDataEntry("user_weather_daily", "Wind Speed", String.format("%.1f", it), "m/s", src))
            }
            row.uvIndexMax?.let {
                entries.add(WeatherDataEntry("user_weather_daily", "UV Index", String.format("%.0f", it), "", src))
            }
            row.isThunderstormDay?.let {
                entries.add(WeatherDataEntry("user_weather_daily", "Thunderstorm", if (it) "Yes" else "No", "", src))
            }
        }
    } catch (e: Exception) {
        Log.e("EnvDataHistory", "Failed to fetch weather: ${e.message}")
    }
    entries
}

private suspend fun deleteWeatherEntry(
    date: String,
    accessToken: String,
    userId: String
): Boolean = withContext(Dispatchers.IO) {
    try {
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val resp = client.delete("$base/rest/v1/user_weather_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            parameter("user_id", "eq.$userId")
            parameter("date", "eq.$date")
            parameter("source", "eq.manual")
        }
        resp.status.isSuccess()
    } catch (e: Exception) {
        Log.e("EnvDataHistory", "Delete failed: ${e.message}")
        false
    }
}

private suspend fun upsertWeatherManualEntry(
    date: String,
    fields: Map<String, Any>,
    accessToken: String
): Boolean = withContext(Dispatchers.IO) {
    try {
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val body = mutableMapOf<String, Any>("date" to date, "source" to "manual")
        body.putAll(fields)
        val resp = client.post("$base/rest/v1/user_weather_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            header("Prefer", "resolution=merge-duplicates,return=minimal")
            parameter("on_conflict", "user_id,source,date")
            contentType(ContentType.Application.Json)
            setBody(listOf(body))
        }
        resp.status.isSuccess()
    } catch (e: Exception) {
        Log.e("EnvDataHistory", "Upsert failed: ${e.message}")
        false
    }
}

// ─── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun EnvironmentDataHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val scope = rememberCoroutineScope()

    var selectedDateStr by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val selectedDate = LocalDate.parse(selectedDateStr)
    val today = LocalDate.now()

    var entries by remember { mutableStateOf<List<WeatherDataEntry>>(emptyList()) }
    val weatherConfig = remember { WeatherCardConfigStore.load(context) }
    var isLoading by remember { mutableStateOf(true) }

    var showEditDialog by remember { mutableStateOf(false) }
    var editTemp by remember { mutableStateOf("") }
    var editPressure by remember { mutableStateOf("") }
    var editHumidity by remember { mutableStateOf("") }
    var editWind by remember { mutableStateOf("") }
    var editUv by remember { mutableStateOf("") }

    val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d")

    fun loadEntries() {
        scope.launch {
            isLoading = true
            val token = SessionStore.readAccessToken(context) ?: return@launch
            val userId = SessionStore.readUserId(context) ?: return@launch
            entries = fetchWeatherEntriesForDate(selectedDateStr, token, userId)
            isLoading = false
        }
    }

    LaunchedEffect(selectedDateStr) { loadEntries() }

    // Edit dialog — weather is a single row so we edit all fields at once
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Environment Data", color = AppTheme.TitleColor) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WeatherEditField("Temperature (°C)", editTemp) { editTemp = it }
                    WeatherEditField("Pressure (hPa)", editPressure) { editPressure = it }
                    WeatherEditField("Humidity (%)", editHumidity) { editHumidity = it }
                    WeatherEditField("Wind Speed (m/s)", editWind) { editWind = it }
                    WeatherEditField("UV Index", editUv) { editUv = it }

                    Spacer(Modifier.height(8.dp))

                    // Delete manual entry
                    val hasManual = entries.any { it.source == "manual" }
                    if (hasManual) {
                        Text(
                            "Delete manual entry",
                            color = Color(0xFFE57373),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .clickable {
                                    scope.launch {
                                        val token = SessionStore.readAccessToken(context) ?: return@launch
                                        val userId = SessionStore.readUserId(context) ?: return@launch
                                        deleteWeatherEntry(selectedDateStr, token, userId)
                                        showEditDialog = false
                                        loadEntries()
                                    }
                                }
                                .padding(vertical = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val token = SessionStore.readAccessToken(context) ?: return@launch
                        val fields = mutableMapOf<String, Any>()
                        editTemp.toDoubleOrNull()?.let { fields["temp_c_mean"] = it }
                        editPressure.toDoubleOrNull()?.let { fields["pressure_hpa_mean"] = it }
                        editHumidity.toDoubleOrNull()?.let { fields["humidity_pct_mean"] = it }
                        editWind.toDoubleOrNull()?.let { fields["wind_speed_mps_mean"] = it }
                        editUv.toDoubleOrNull()?.let { fields["uv_index_max"] = it }
                        if (fields.isNotEmpty()) {
                            upsertWeatherManualEntry(selectedDateStr, fields, token)
                        }
                        showEditDialog = false
                        loadEntries()
                    }
                }) { Text("Save", color = AppTheme.AccentPurple) }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
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
                    "Environment Data",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "View and edit environment entries",
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
                        "No environment data for this day",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                    )
                } else {
                    // Map labels to metric keys
                    val labelToMetric = mapOf(
                        "Temperature" to WeatherCardConfig.METRIC_TEMPERATURE,
                        "Pressure" to WeatherCardConfig.METRIC_PRESSURE,
                        "Humidity" to WeatherCardConfig.METRIC_HUMIDITY,
                        "Wind Speed" to WeatherCardConfig.METRIC_WIND_SPEED,
                        "UV Index" to WeatherCardConfig.METRIC_UV_INDEX
                    )

                    // Best value per metric (prefer non-manual)
                    val bestByMetric = mutableMapOf<String, WeatherDataEntry>()
                    entries.forEach { entry ->
                        val metricKey = labelToMetric[entry.label] ?: return@forEach
                        val existing = bestByMetric[metricKey]
                        if (existing == null || (existing.source == "manual" && entry.source != "manual")) {
                            bestByMetric[metricKey] = entry
                        }
                    }

                    val selectedMetrics = weatherConfig.weatherDisplayMetrics.take(3)
                    val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))

                    // Top 3 selected metrics
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        selectedMetrics.forEachIndexed { index, metric ->
                            val entry = bestByMetric[metric]
                            val value = if (entry != null) "${entry.value}${if (entry.unit.isNotEmpty()) entry.unit else ""}" else "—"
                            val label = WeatherCardConfig.WEATHER_METRIC_LABELS[metric] ?: metric
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                weatherSourceLabel(source),
                                color = if (source == "manual") AppTheme.AccentPurple else AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            if (source == "manual") {
                                Text(
                                    "✎",
                                    color = AppTheme.AccentPurple,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier
                                        .clickable {
                                            editTemp = items.find { it.label == "Temperature" }?.value ?: ""
                                            editPressure = items.find { it.label == "Pressure" }?.value ?: ""
                                            editHumidity = items.find { it.label == "Humidity" }?.value ?: ""
                                            editWind = items.find { it.label == "Wind Speed" }?.value ?: ""
                                            editUv = items.find { it.label == "UV Index" }?.value ?: ""
                                            showEditDialog = true
                                        }
                                        .padding(8.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))

                        items.forEach { entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(entry.label, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${entry.value}${if (entry.unit.isNotEmpty()) " ${entry.unit}" else ""}",
                                    color = AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }

@Composable
private fun WeatherEditField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AppTheme.AccentPurple,
            unfocusedBorderColor = AppTheme.SubtleTextColor.copy(alpha = 0.3f),
            focusedTextColor = AppTheme.TitleColor,
            unfocusedTextColor = AppTheme.TitleColor,
            focusedLabelColor = AppTheme.AccentPurple,
            unfocusedLabelColor = AppTheme.SubtleTextColor
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

private fun envMetricColor(metric: String): Color = when (metric) {
    WeatherCardConfig.METRIC_TEMPERATURE -> Color(0xFFFF8A65)
    WeatherCardConfig.METRIC_PRESSURE -> Color(0xFF7986CB)
    WeatherCardConfig.METRIC_HUMIDITY -> Color(0xFF4FC3F7)
    WeatherCardConfig.METRIC_WIND_SPEED -> Color(0xFF81C784)
    WeatherCardConfig.METRIC_UV_INDEX -> Color(0xFFFFB74D)
    else -> Color(0xFF4FC3F7)
}

private fun weatherSourceLabel(source: String): String = when (source) {
    "manual" -> "Manual"
    "api" -> "Weather API"
    else -> source.replaceFirstChar { it.uppercase() }
}
