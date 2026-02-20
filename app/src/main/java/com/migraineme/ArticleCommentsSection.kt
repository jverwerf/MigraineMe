// app/src/main/java/com/migraineme/ArticleCommentsSection.kt
package com.migraineme

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private const val TAG = "Comments"

//  Models 

@Serializable
data class CommentRow(
    val id: String,
    @SerialName("article_id") val articleId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("parent_id") val parentId: String? = null,
    val body: String,
    val attachment: JsonObject? = null,
    @SerialName("created_at") val createdAt: String,
    val profiles: CommentProfile? = null
)

@Serializable
data class CommentProfile(
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null
)

//  Comments Section Composable 

@Composable
fun ArticleCommentsSection(
    articleId: String,
    accessToken: String?,
    currentUserId: String?,
    insightsVm: InsightsViewModel
) {
    val scope = rememberCoroutineScope()
    var comments by remember { mutableStateOf<List<CommentRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var commentText by remember { mutableStateOf("") }
    var replyingTo by remember { mutableStateOf<CommentRow?>(null) }
    var posting by remember { mutableStateOf(false) }

    val client = remember {
        HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
        }
    }

    val supabaseUrl = BuildConfig.SUPABASE_URL
    val supabaseKey = BuildConfig.SUPABASE_ANON_KEY

    // Fetch comments
    LaunchedEffect(articleId) {
        loading = true
        try {
            val response = client.get("$supabaseUrl/rest/v1/article_comments") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                parameter("article_id", "eq.$articleId")
                parameter("select", "id,article_id,user_id,parent_id,body,attachment,created_at,profiles(display_name,avatar_url)")
                parameter("order", "created_at.asc")
            }
            if (response.status.value in 200..299) {
                comments = response.body()
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetch comments error", e)
        }
        loading = false
    }

    val topLevel = comments.filter { it.parentId == null }
    val repliesMap = comments.filter { it.parentId != null }.groupBy { it.parentId }

    var showGraphPicker by remember { mutableStateOf(false) }
    var pendingAttachment by remember { mutableStateOf<JsonObject?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        //  Header 
        Text(
            "Comments (${comments.size})",
            color = AppTheme.TitleColor,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (loading) {
            Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppTheme.AccentPurple, modifier = Modifier.size(24.dp))
            }
        } else if (topLevel.isEmpty()) {
            Text(
                "No comments yet - be the first!",
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        } else {
            for (comment in topLevel) {
                CommentBubble(
                    comment = comment,
                    isOwn = comment.userId == currentUserId,
                    onReply = { replyingTo = comment },
                    onDelete = {
                        scope.launch {
                            try {
                                Log.d(TAG, "Deleting comment ${comment.id}")
                                val delResponse = client.delete("$supabaseUrl/rest/v1/article_comments") {
                                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                                    header("apikey", supabaseKey)
                                    parameter("id", "eq.${comment.id}")
                                }
                                Log.d(TAG, "Delete response: ${delResponse.status}")
                                if (delResponse.status.value in 200..299) {
                                    comments = comments.filter { it.id != comment.id && it.parentId != comment.id }
                                } else {
                                    Log.e(TAG, "Delete failed: ${delResponse.status} ${delResponse.bodyAsText()}")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "delete error", e)
                            }
                        }
                    }
                )

                // Replies
                val replies = repliesMap[comment.id] ?: emptyList()
                for (reply in replies) {
                    Row(modifier = Modifier.padding(start = 32.dp)) {
                        // Thread line
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(IntrinsicSize.Max)
                                .background(AppTheme.AccentPurple.copy(alpha = 0.2f))
                        )
                        Spacer(Modifier.width(8.dp))
                        CommentBubble(
                            comment = reply,
                            isOwn = reply.userId == currentUserId,
                            onReply = null,
                            onDelete = {
                                scope.launch {
                                    try {
                                        Log.d(TAG, "Deleting reply ${reply.id}")
                                        val delResponse = client.delete("$supabaseUrl/rest/v1/article_comments") {
                                            header(HttpHeaders.Authorization, "Bearer $accessToken")
                                            header("apikey", supabaseKey)
                                            parameter("id", "eq.${reply.id}")
                                        }
                                        Log.d(TAG, "Delete reply response: ${delResponse.status}")
                                        if (delResponse.status.value in 200..299) {
                                            comments = comments.filter { it.id != reply.id }
                                        } else {
                                            Log.e(TAG, "Delete reply failed: ${delResponse.status} ${delResponse.bodyAsText()}")
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "delete reply error", e)
                                    }
                                }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }

        //  Reply banner 
        if (replyingTo != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppTheme.AccentPurple.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Replying to ${replyingTo!!.profiles?.displayName ?: "user"}",
                    color = AppTheme.AccentPurple,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "X",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable { replyingTo = null }
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        //  Input 
        if (accessToken != null) {
            // Pending attachment preview
            if (pendingAttachment != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0628).copy(alpha = 0.6f)),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.ShowChart, contentDescription = null, tint = AppTheme.AccentPurple, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Graph attached", color = AppTheme.AccentPurple, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { pendingAttachment = null }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = "Remove", tint = AppTheme.SubtleTextColor, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // Graph picker
            if (showGraphPicker) {
                GraphAttachmentPicker(
                    accessToken = accessToken,
                    insightsVm = insightsVm,
                    onAttach = { json ->
                        pendingAttachment = json
                        showGraphPicker = false
                    },
                    onDismiss = { showGraphPicker = false }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Graph attach button
                IconButton(
                    onClick = { showGraphPicker = !showGraphPicker },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Outlined.ShowChart,
                        contentDescription = "Attach graph",
                        tint = if (showGraphPicker) AppTheme.AccentPurple else AppTheme.SubtleTextColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                OutlinedTextField(
                    value = commentText,
                    onValueChange = { if (it.length <= 2000) commentText = it },
                    placeholder = {
                        Text(
                            if (replyingTo != null) "Write a reply..." else "Write a comment...",
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.AccentPurple,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                        cursorColor = AppTheme.AccentPurple
                    ),
                    shape = RoundedCornerShape(14.dp),
                    maxLines = 4,
                    enabled = !posting
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val text = commentText.trim()
                        if (text.isEmpty() || posting) return@IconButton
                        posting = true
                        scope.launch {
                            try {
                                val body = buildJsonObject {
                                    put("article_id", articleId)
                                    put("body", text)
                                    replyingTo?.let { put("parent_id", it.id) }
                                    pendingAttachment?.let { put("attachment", it) }
                                }
                                Log.d(TAG, "Posting comment: ${body.toString().take(200)}")

                                val response = client.post("$supabaseUrl/rest/v1/article_comments") {
                                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                                    header("apikey", supabaseKey)
                                    header("Prefer", "return=minimal")
                                    contentType(ContentType.Application.Json)
                                    setBody(body.toString())
                                }
                                if (response.status.value in 200..299) {
                                    // Refetch all comments
                                    val fetchResponse = client.get("$supabaseUrl/rest/v1/article_comments") {
                                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                                        header("apikey", supabaseKey)
                                        parameter("article_id", "eq.$articleId")
                                        parameter("select", "id,article_id,user_id,parent_id,body,attachment,created_at,profiles(display_name,avatar_url)")
                                        parameter("order", "created_at.asc")
                                    }
                                    if (fetchResponse.status.value in 200..299) {
                                        comments = fetchResponse.body()
                                    }
                                    commentText = ""
                                    replyingTo = null
                                    pendingAttachment = null
                                } else {
                                    Log.e(TAG, "post comment failed: ${response.status} ${response.bodyAsText()}")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "post comment error", e)
                            }
                            posting = false
                        }
                    },
                    enabled = commentText.isNotBlank() && !posting
                ) {
                    if (posting) {
                        CircularProgressIndicator(
                            color = AppTheme.AccentPurple,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Send,
                            contentDescription = "Post",
                            tint = if (commentText.isNotBlank()) AppTheme.AccentPurple else AppTheme.SubtleTextColor
                        )
                    }
                }
            }
        }
    }
}

//  Single Comment Bubble 

@Composable
private fun CommentBubble(
    comment: CommentRow,
    isOwn: Boolean,
    onReply: (() -> Unit)?,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Avatar
            if (!comment.profiles?.avatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(comment.profiles?.avatarUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(AppTheme.AccentPurple.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        (comment.profiles?.displayName?.firstOrNull() ?: '?').uppercase(),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Text(
                comment.profiles?.displayName ?: "Anonymous",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
            )

            Spacer(Modifier.width(8.dp))

            Text(
                formatCommentDate(comment.createdAt),
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall
            )

            Spacer(Modifier.weight(1f))

            // Actions
            if (onReply != null) {
                IconButton(onClick = onReply, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Reply,
                        contentDescription = "Reply",
                        tint = AppTheme.SubtleTextColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (isOwn) {
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Delete",
                        tint = AppTheme.SubtleTextColor.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Comment body
        Text(
            comment.body,
            color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
            modifier = Modifier.padding(start = 36.dp, top = 2.dp, end = 8.dp)
        )

        //  Graph attachment 
        if (comment.attachment != null) {
            Spacer(Modifier.height(8.dp))
            AttachedGraphCard(comment.attachment)
        }
    }
}

//  Graph Attachment Card 
// Renders the graph from JSON using CommunityGraphViewModel + InsightsTimelineGraph

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachedGraphCard(attachment: JsonObject) {
    Log.d("GraphCard", "attachment keys: ${attachment.keys}, metrics count: ${attachment["metrics"]?.jsonArray?.size ?: 0}")
    val graphVm = remember(attachment) {
        CommunityGraphViewModel().apply { loadFromJson(attachment) }
    }

    val migraines by graphVm.migraines.collectAsState()
    val events by graphVm.events.collectAsState()
    val metricSeries by graphVm.metricSeries.collectAsState()
    val windowStart by graphVm.windowStart.collectAsState()
    val windowEnd by graphVm.windowEnd.collectAsState()

    if (windowStart == null || windowEnd == null) return

    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("d MMM").withZone(zone)
    val rangeLabel = "${fmt.format(windowStart)} - ${fmt.format(windowEnd)}"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 36.dp, end = 8.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0628).copy(alpha = 0.85f)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                rangeLabel,
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(4.dp))

            InsightsTimelineGraph(
                migraines = migraines,
                events = events,
                metricSeries = metricSeries,
                windowStart = windowStart,
                windowEnd = windowEnd,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            )

            // Legend
            if (metricSeries.isNotEmpty() || events.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    for (series in metricSeries) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(series.color))
                            Spacer(Modifier.width(4.dp))
                            Text(series.label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    val eventCats = events.map { it.category }.distinct()
                    for (cat in eventCats) {
                        val color = events.first { it.category == cat }.color
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                            Spacer(Modifier.width(4.dp))
                            Text(cat, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

private fun formatCommentDate(isoDate: String): String {
    return try {
        val instant = Instant.parse(isoDate)
        val now = Instant.now()
        val minutes = ChronoUnit.MINUTES.between(instant, now)
        val hours = ChronoUnit.HOURS.between(instant, now)
        val days = ChronoUnit.DAYS.between(instant, now)
        when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m"
            hours < 24 -> "${hours}h"
            days < 7 -> "${days}d"
            else -> {
                val fmt = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault())
                fmt.format(instant)
            }
        }
    } catch (_: Exception) { "" }
}

