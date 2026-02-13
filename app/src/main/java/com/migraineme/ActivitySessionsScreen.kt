package com.migraineme

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

// ─── Data model ───────────────────────────────────────────────────────────────

data class ActivitySession(
    val date: String,
    val activityType: String,
    val durationMinutes: Double,
    val startAt: String?,
    val endAt: String?,
    val zoneZero: Double?,
    val zoneOne: Double?,
    val zoneTwo: Double?,
    val zoneThree: Double,
    val zoneFour: Double,
    val zoneFive: Double,
    val zoneSix: Double,
    val source: String?
)

// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivitySessionsScreen(
    date: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<ActivitySession>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(date) {
        scope.launch {
            sessions = fetchActivitySessions(context, date)
            isLoading = false
        }
    }

    val displayDate = try {
        LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
    } catch (_: Exception) { date }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.FadeColor)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = AppTheme.BodyTextColor)
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Activities", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(displayDate, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(16.dp))

        if (isLoading) {
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), AppTheme.AccentPurple, strokeWidth = 2.dp)
            }
        } else if (sessions.isEmpty()) {
            Text(
                "No activities recorded for this day",
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp)
            )
        } else {
            // Summary
            val totalDuration = sessions.sumOf { it.durationMinutes }
            val totalHighZone = sessions.sumOf { it.zoneThree + it.zoneFour + it.zoneFive + it.zoneSix }

            BaseCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    SummaryItem("Activities", "${sessions.size}", Color(0xFFFF7043))
                    SummaryItem("Total Time", "${totalDuration.toInt()} min", Color(0xFF4FC3F7))
                    SummaryItem("High HR", "${totalHighZone.toInt()} min", Color(0xFFE57373))
                }
            }

            Spacer(Modifier.height(12.dp))

            // Individual sessions
            sessions.forEachIndexed { index, session ->
                ActivitySessionCard(session)
                if (index < sessions.size - 1) Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SummaryItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ActivitySessionCard(session: ActivitySession) {
    val activityLabel = formatActivityType(session.activityType)
    val activityIcon = activityEmoji(session.activityType)
    val timeRange = formatTimeRange(session.startAt, session.endAt)

    BaseCard {
        // Activity header
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(activityIcon, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(activityLabel, color = AppTheme.TitleColor, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                    if (timeRange != null) {
                        Text(timeRange, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(
                "${session.durationMinutes.toInt()} min",
                color = Color(0xFFFF7043),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
            )
        }

        Spacer(Modifier.height(10.dp))

        // Zone breakdown bars
        val zones = listOf(
            ZoneData("Zone 0", session.zoneZero ?: 0.0, Color(0xFF90CAF9)),
            ZoneData("Zone 1", session.zoneOne ?: 0.0, Color(0xFF81C784)),
            ZoneData("Zone 2", session.zoneTwo ?: 0.0, Color(0xFFFFEB3B)),
            ZoneData("Zone 3", session.zoneThree, Color(0xFFFFB74D)),
            ZoneData("Zone 4", session.zoneFour, Color(0xFFFF8A65)),
            ZoneData("Zone 5", session.zoneFive, Color(0xFFE57373)),
            ZoneData("Zone 6", session.zoneSix, Color(0xFFEF5350))
        )

        val maxZone = zones.maxOfOrNull { it.minutes }?.coerceAtLeast(1.0) ?: 1.0

        zones.filter { it.minutes > 0 }.forEach { zone ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    zone.label,
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(48.dp)
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(AppTheme.BaseCardContainer)
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((zone.minutes / maxZone).toFloat().coerceIn(0.02f, 1f))
                            .clip(RoundedCornerShape(4.dp))
                            .background(zone.color)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    "${String.format("%.1f", zone.minutes)} min",
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(52.dp),
                    textAlign = TextAlign.End
                )
            }
        }

        // High HR total
        val highTotal = session.zoneThree + session.zoneFour + session.zoneFive + session.zoneSix
        if (highTotal > 0) {
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.15f))
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("High HR Zones (3-6)", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                Text(
                    "${String.format("%.1f", highTotal)} min",
                    color = Color(0xFFE57373),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }

        // Source
        if (session.source != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Source: ${session.source.uppercase()}",
                color = AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private data class ZoneData(val label: String, val minutes: Double, val color: Color)

// ─── Helpers ──────────────────────────────────────────────────────────────────

fun formatActivityType(raw: String?): String {
    if (raw.isNullOrBlank() || raw == "daily_total") return "Workout"
    return raw.replace("_", " ").split(" ").joinToString(" ") { word ->
        word.lowercase().replaceFirstChar { it.uppercase() }
    }
}

fun activityEmoji(type: String?): String {
    val t = type?.lowercase() ?: ""
    return when {
        "run" in t -> "🏃"
        "cycling" in t || "biking" in t -> "🚴"
        "swim" in t -> "🏊"
        "yoga" in t -> "🧘"
        "weight" in t || "strength" in t -> "🏋️"
        "hiking" in t -> "🥾"
        "walk" in t -> "🚶"
        "basketball" in t -> "🏀"
        "soccer" in t || "football" in t -> "⚽"
        "tennis" in t -> "🎾"
        "boxing" in t || "martial" in t -> "🥊"
        "rowing" in t -> "🚣"
        "ski" in t || "snowboard" in t -> "⛷️"
        "dance" in t -> "💃"
        "golf" in t -> "⛳"
        "stretch" in t || "pilates" in t -> "🤸"
        "hiit" in t || "high_intensity" in t || "boot_camp" in t -> "🔥"
        "cricket" in t -> "🏏"
        "climbing" in t -> "🧗"
        "surf" in t -> "🏄"
        else -> "💪"
    }
}

private fun formatTimeRange(startAt: String?, endAt: String?): String? {
    if (startAt.isNullOrBlank()) return null
    val fmt = DateTimeFormatter.ofPattern("h:mm a")
    return try {
        val start = if (startAt.contains("T")) {
            java.time.LocalDateTime.parse(startAt.substringBefore("Z").substringBefore("+")).format(fmt)
        } else startAt

        val end = if (!endAt.isNullOrBlank() && endAt.contains("T")) {
            java.time.LocalDateTime.parse(endAt.substringBefore("Z").substringBefore("+")).format(fmt)
        } else null

        if (end != null) "$start – $end" else start
    } catch (_: Exception) { startAt }
}

// ─── Data fetching ────────────────────────────────────────────────────────────

suspend fun fetchActivitySessions(
    context: android.content.Context,
    date: String
): List<ActivitySession> = withContext(Dispatchers.IO) {
    try {
        val token = SessionStore.getValidAccessToken(context) ?: return@withContext emptyList()
        val userId = SessionStore.readUserId(context) ?: return@withContext emptyList()
        val client = okhttp3.OkHttpClient()

        val url = "${BuildConfig.SUPABASE_URL}/rest/v1/time_in_high_hr_zones_daily" +
            "?user_id=eq.$userId&date=eq.$date" +
            "&activity_type=neq.daily_total" +
            "&select=date,value_minutes,zone_zero_minutes,zone_one_minutes,zone_two_minutes,zone_three_minutes,zone_four_minutes,zone_five_minutes,zone_six_minutes,activity_type,start_at,end_at,source" +
            "&order=start_at.asc"

        val request = okhttp3.Request.Builder().url(url).get()
            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer $token")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string()
        response.close()

        if (!response.isSuccessful || body.isNullOrBlank()) return@withContext emptyList()

        val arr = org.json.JSONArray(body)
        val sessions = mutableListOf<ActivitySession>()

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            sessions.add(
                ActivitySession(
                    date = obj.optString("date", date),
                    activityType = obj.optString("activity_type", "workout"),
                    durationMinutes = obj.optDouble("value_minutes", 0.0),
                    startAt = obj.optString("start_at", null),
                    endAt = obj.optString("end_at", null),
                    zoneZero = obj.optDouble("zone_zero_minutes").takeIf { !it.isNaN() },
                    zoneOne = obj.optDouble("zone_one_minutes").takeIf { !it.isNaN() },
                    zoneTwo = obj.optDouble("zone_two_minutes").takeIf { !it.isNaN() },
                    zoneThree = obj.optDouble("zone_three_minutes", 0.0),
                    zoneFour = obj.optDouble("zone_four_minutes", 0.0),
                    zoneFive = obj.optDouble("zone_five_minutes", 0.0),
                    zoneSix = obj.optDouble("zone_six_minutes", 0.0),
                    source = obj.optString("source", null)
                )
            )
        }

        sessions
    } catch (e: Exception) {
        Log.e("ActivitySessions", "Failed to fetch: ${e.message}", e)
        emptyList()
    }
}

// Fetch activity count for a date (used in log screen)
suspend fun fetchActivityCount(
    context: android.content.Context,
    date: String
): Int = withContext(Dispatchers.IO) {
    try {
        val token = SessionStore.getValidAccessToken(context) ?: return@withContext 0
        val userId = SessionStore.readUserId(context) ?: return@withContext 0
        val client = okhttp3.OkHttpClient()

        val url = "${BuildConfig.SUPABASE_URL}/rest/v1/time_in_high_hr_zones_daily" +
            "?user_id=eq.$userId&date=eq.$date" +
            "&activity_type=neq.daily_total" +
            "&select=date"

        val request = okhttp3.Request.Builder().url(url).get()
            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Prefer", "count=exact")
            .addHeader("Range", "0-0")
            .build()

        val response = client.newCall(request).execute()
        val contentRange = response.header("content-range") ?: ""
        response.close()

        // content-range: 0-0/5 -> 5
        val total = contentRange.substringAfter("/", "0").toIntOrNull() ?: 0
        total
    } catch (e: Exception) {
        Log.e("ActivitySessions", "Failed to count: ${e.message}", e)
        0
    }
}
