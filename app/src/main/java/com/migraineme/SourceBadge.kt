package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * Reusable source badge row — shows "Sources: [Garmin] [WHOOP]" etc.
 */
@Composable
fun SourceBadgeRow(sources: List<String>) {
    if (sources.isEmpty()) return
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            Icons.Outlined.Sensors,
            contentDescription = "Sources",
            tint = AppTheme.SubtleTextColor,
            modifier = Modifier.size(12.dp)
        )
        Text(
            "Sources:",
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall
        )
        sources.forEach { src ->
            Text(
                text = sourceDisplayLabel(src, context),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(sourceColor(src))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

/**
 * Map raw source string to display label.
 * For Garmin, uses GarminDeviceNameProvider to show device model when available.
 */
fun sourceDisplayLabel(raw: String, context: android.content.Context? = null): String = when (raw.lowercase()) {
    "garmin" -> if (context != null) GarminDeviceNameProvider.getLabel(context) else "Garmin"
    "whoop" -> "WHOOP"
    "oura" -> "Oura"
    "polar" -> "Polar"
    "phone" -> "Phone"
    "health_connect" -> "Health Connect"
    "manual_usda" -> "Manual"
    else -> raw.replaceFirstChar { it.uppercase() }
}

/**
 * Brand color per source.
 */
fun sourceColor(raw: String): Color = when (raw.lowercase()) {
    "garmin" -> Color(0xFF007CC3)
    "whoop" -> Color(0xFF44A8B3)
    "oura" -> Color(0xFFD4A574)
    "polar" -> Color(0xFFD32F2F)
    "phone" -> AppTheme.AccentPurple
    "health_connect" -> Color(0xFF4CAF50)
    else -> Color(0xFF666666)
}

/**
 * Fetch distinct sources from one or more Supabase tables for a given date.
 * Call from withContext(Dispatchers.IO).
 */
suspend fun fetchSourcesForDate(
    ctx: android.content.Context,
    token: String,
    date: String,
    tables: List<String>
): List<String> = withContext(Dispatchers.IO) {
    val sources = mutableSetOf<String>()
    val client = OkHttpClient()
    val userId = SessionStore.readUserId(ctx) ?: return@withContext emptyList()

    for (table in tables) {
        try {
            val url = "${BuildConfig.SUPABASE_URL}/rest/v1/$table?" +
                "user_id=eq.$userId&date=eq.$date&select=source&limit=1"
            val request = Request.Builder().url(url).get()
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()
            if (!body.isNullOrBlank()) {
                val arr = JSONArray(body)
                if (arr.length() > 0) {
                    val src = arr.getJSONObject(0).optString("source", "")
                    if (src.isNotBlank()) sources.add(src)
                }
            }
        } catch (_: Exception) {}
    }
    sources.sorted()
}
