package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SkipRow(
    @SerialName("event_id") val eventId: String,
    val title: String? = null,
    @SerialName("title_normalized") val titleNormalized: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

private data class SkipGroup(
    val key: String,                  // title_normalized
    val displayTitle: String,
    val count: Int,
    val lastSkippedAt: String?,
    val eventIds: List<String>,
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private fun buildClient(): HttpClient = HttpClient(Android) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; explicitNulls = false })
    }
}

@Composable
fun ManageCalendarSkipsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var groups by remember { mutableStateOf<List<SkipGroup>>(emptyList()) }
    var showInfo by remember { mutableStateOf(false) }

    val infoText = "Calendar events you've told MigraineMe to stop suggesting. Every time the Daily Check-In's \"From your calendar\" page surfaces an event and you tap dismiss instead of tagging it as a trigger, relief, or activity, the event title lands here.\n\n" +
        "For each entry:\n• Tap the trash icon to remove it from the opt-out list. The next time an event with that title shows up in your calendar, the Daily Check-In will suggest it again.\n\n" +
        "This is the inverse of the other Manage Items pools: instead of adding things you want to track, this is the list of things you've explicitly chosen NOT to track. Useful when you accidentally dismissed something important, or when a recurring meeting title becomes relevant (e.g. you used to dismiss \"Standup\" but now want to flag standup-stress as a trigger)."

    suspend fun reload() {
        loading = true
        error = null
        try {
            val token = SessionStore.getValidAccessToken(context.applicationContext)
            val userId = SessionStore.readUserId(context.applicationContext)
            if (token == null || userId == null) {
                error = "Not signed in"; loading = false; return
            }
            val client = buildClient()
            try {
                val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/calendar_event_mappings" +
                    "?user_id=eq.$userId&decision=eq.user_skipped" +
                    "&select=event_id,title,title_normalized,updated_at&order=updated_at.desc"
                val res = client.get(url) {
                    header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
                if (!res.status.isSuccess()) {
                    error = "Could not load (${res.status})"
                } else {
                    val rows: List<SkipRow> = res.body()
                    val byKey = rows.groupBy { (it.titleNormalized ?: "").ifEmpty { it.title ?: "" } }
                    groups = byKey.map { (key, list) ->
                        SkipGroup(
                            key = key,
                            displayTitle = list.firstOrNull()?.title ?: key,
                            count = list.size,
                            lastSkippedAt = list.firstOrNull()?.updatedAt,
                            eventIds = list.map { it.eventId },
                        )
                    }.sortedByDescending { it.lastSkippedAt ?: "" }
                }
            } finally {
                client.close()
            }
        } catch (e: Exception) {
            error = e.message ?: "Error"
        }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize().background(AppTheme.FadeColor)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().background(
                        Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp)
                    ).padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Outlined.CalendarMonth, null, tint = Color(0xFF64B5F6),
                        modifier = Modifier.size(36.dp))
                    Text("Calendar opt-outs", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Titles you've removed from the calendar mapper. Delete one to let it be auto-saved again.",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                IconButton(
                    onClick = { showInfo = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 10.dp, y = (-14).dp)
                        .size(34.dp)
                ) {
                    Icon(Icons.Outlined.Info, contentDescription = "About Calendar opt-outs",
                        tint = AppTheme.SubtleTextColor, modifier = Modifier.size(20.dp))
                }
            }

            when {
                loading -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp,
                    modifier = Modifier.padding(vertical = 24.dp).size(28.dp))
                error != null -> Text(error!!, color = Color(0xFFE57373),
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                groups.isEmpty() -> Text(
                    "Nothing here. When you tap Undo on a calendar event during your check-in, the title shows up here so you can revive it later.",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 24.dp, horizontal = 24.dp),
                )
                else -> groups.forEach { g ->
                    SkipRowCard(
                        group = g,
                        onDelete = {
                            scope.launch {
                                val token = SessionStore.getValidAccessToken(context.applicationContext)
                                val userId = SessionStore.readUserId(context.applicationContext)
                                if (token != null && userId != null) {
                                    val client = buildClient()
                                    try {
                                        val ids = g.eventIds.joinToString(",") { "\"$it\"" }
                                        val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/calendar_event_mappings" +
                                            "?user_id=eq.$userId&event_id=in.($ids)"
                                        client.delete(url) {
                                            header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                                            header(HttpHeaders.Authorization, "Bearer $token")
                                            header("Prefer", "return=minimal")
                                        }
                                    } finally { client.close() }
                                }
                                reload()
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            containerColor = Color(0xFF1E0A2E),
            title = {
                Text("About Calendar opt-outs", color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            },
            text = { Text(infoText, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Got it", color = AppTheme.AccentPurple) } }
        )
    }
}

@Composable
private fun SkipRowCard(group: SkipGroup, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(
            Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp)
        ).border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.CalendarMonth, null, tint = Color(0xFF64B5F6),
            modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(group.displayTitle, color = Color.White, fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium)
            Text(
                if (group.count == 1) "1 event" else "${group.count} events",
                color = AppTheme.SubtleTextColor, fontSize = 10.sp,
            )
        }
        Icon(
            Icons.Outlined.Delete, null,
            tint = Color(0xFFE57373),
            modifier = Modifier.size(20.dp).clickable { onDelete() },
        )
    }
}
