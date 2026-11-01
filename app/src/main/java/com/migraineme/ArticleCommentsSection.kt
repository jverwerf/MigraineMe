// app/src/main/java/com/migraineme/ArticleCommentsSection.kt
package com.migraineme

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Info
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.Dp
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
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
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
    val profiles: CommentProfile? = null,
    @SerialName("article_comment_translations") val translations: List<CommunityTranslationRow>? = null
)

@Serializable
data class CommentProfile(
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null
)

/**
 * The article's comment thread, in the reader's language.
 *
 * Three call sites needed the identical query (first load, straight after
 * posting, and again once moderation has had a moment), and each one that
 * forgot the translation embed would silently snap the whole thread back to
 * English. One function, so that cannot happen.
 */
private suspend fun fetchArticleComments(
    client: HttpClient,
    supabaseUrl: String,
    supabaseKey: String,
    accessToken: String?,
    articleId: String
): List<CommentRow>? {
    val response = client.get("$supabaseUrl/rest/v1/article_comments") {
        header(HttpHeaders.Authorization, "Bearer $accessToken")
        header("apikey", supabaseKey)
        parameter("article_id", "eq.$articleId")
        parameter("status", "eq.active")
        parameter(
            "select",
            "id,article_id,user_id,parent_id,body,attachment,created_at," +
                "profiles(display_name,avatar_url)" +
                CommunityI18n.embed("article_comment_translations", "body")
        )
        translationLang("article_comment_translations")
        parameter("order", "created_at.asc")
    }
    if (response.status.value !in 200..299) return null
    return response.body<List<CommentRow>>().map { c ->
        c.copy(body = c.translations.one()?.body.orEnglish(c.body), translations = null)
    }
}

//  Comments Section Composable 

@Composable
fun ArticleCommentsSection(
    articleId: String,
    accessToken: String?,
    currentUserId: String?,
    insightsVm: InsightsViewModel,
    communityVm: CommunityViewModel? = null,
    onUpgrade: () -> Unit = {}
) {
    val compareCtx = accessToken?.let { GraphCompareContext(it, insightsVm, onUpgrade) }
    val scope = rememberCoroutineScope()
    var comments by remember { mutableStateOf<List<CommentRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var commentText by remember { mutableStateOf("") }
    var replyingTo by remember { mutableStateOf<CommentRow?>(null) }
    var posting by remember { mutableStateOf(false) }
    var reportingComment by remember { mutableStateOf<CommentRow?>(null) }
    var showModerationAlert by remember { mutableStateOf(false) }

    val client = remember {
        HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
        }
    }

    val supabaseUrl = BuildConfig.SUPABASE_URL
    val supabaseKey = BuildConfig.SUPABASE_ANON_KEY

    // Refetch when the language changes: the companions' comments are content,
    // translated server-side, so switching language has to go back for them the
    // same way it does for the article they sit under.
    val contentLang by LangPrefs.lang.collectAsState()

    // Fetch comments
    LaunchedEffect(articleId, contentLang) {
        loading = true
        try {
            fetchArticleComments(client, supabaseUrl, supabaseKey, accessToken, articleId)
                ?.let { comments = it }
        } catch (e: Exception) {
            Log.e(TAG, "fetch comments error", e)
        }
        loading = false
    }

    val topLevel = comments.filter { it.parentId == null }
    val repliesMap = comments.filter { it.parentId != null }.groupBy { it.parentId }

    var showGraphPicker by remember { mutableStateOf(false) }
    var pendingAttachment by remember { mutableStateOf<JsonObject?>(null) }

    // Report dialog
    reportingComment?.let { comment ->
        ReportDialog(
            title = t("Report comment"),
            onDismiss = { reportingComment = null },
            onSubmit = { reason ->
                accessToken?.let { communityVm?.reportContent(it, postId = null, commentId = null, reason = reason, articleCommentId = comment.id) }
                reportingComment = null
            }
        )
    }

    // Moderation alert
    if (showModerationAlert) {
        AlertDialog(
            onDismissRequest = { showModerationAlert = false },
            confirmButton = {
                TextButton(onClick = { showModerationAlert = false }) {
                    Text(t("OK"), color = AppTheme.AccentPurple)
                }
            },
            icon = { Icon(Icons.Outlined.Info, contentDescription = null, tint = AppTheme.AccentPurple) },
            title = { Text(t("Comment under review"), color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text(t("Your comment has been flagged by our moderation system and is currently under review. If this was a mistake, it will be restored shortly."), color = Color.White.copy(alpha = 0.7f)) },
            containerColor = Color(0xFF1A0029),
            shape = RoundedCornerShape(16.dp)
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        //  Header 
        Text(
            t("Comments (%s)", comments.size),
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
                t("No comments yet - be the first!"),
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        } else {
            for (comment in topLevel) {
                CommentBubble(
                    compare = compareCtx,
                    comment = comment,
                    isOwn = comment.userId == currentUserId,
                    onReply = { replyingTo = comment },
                    onReport = { reportingComment = comment },
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
                            compare = compareCtx,
                            comment = reply,
                            isOwn = reply.userId == currentUserId,
                            onReply = null,
                            onReport = { reportingComment = reply },
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
                    t("Replying to %s", replyingTo!!.profiles?.displayName ?: t("user")),
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
                        Text(t("Graph attached"), color = AppTheme.AccentPurple, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { pendingAttachment = null }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = t("Remove"), tint = AppTheme.SubtleTextColor, modifier = Modifier.size(14.dp))
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
                        contentDescription = t("Attach graph"),
                        tint = if (showGraphPicker) AppTheme.AccentPurple else AppTheme.SubtleTextColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                OutlinedTextField(
                    value = commentText,
                    onValueChange = { if (it.length <= 2000) commentText = it },
                    placeholder = {
                        Text(
                            if (replyingTo != null) t("Write a reply...") else t("Write a comment..."),
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
                        Log.d(TAG, "Send tapped — text='${text.take(30)}' posting=$posting enabled=${commentText.isNotBlank() && !posting}")
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
                                Log.d(TAG, "Post response: ${response.status}")
                                if (response.status.value in 200..299) {
                                    // Refetch all comments
                                    fetchArticleComments(client, supabaseUrl, supabaseKey, accessToken, articleId)
                                        ?.let { comments = it }
                                    commentText = ""
                                    replyingTo = null
                                    pendingAttachment = null
                                    // Delayed re-fetch after moderation has had time to process
                                    val preModCount = comments.size
                                    kotlinx.coroutines.delay(4000L)
                                    val updatedComments =
                                        fetchArticleComments(client, supabaseUrl, supabaseKey, accessToken, articleId)
                                    if (updatedComments != null) {
                                        comments = updatedComments
                                        if (updatedComments.size < preModCount) {
                                            showModerationAlert = true
                                        }
                                    }
                                    // Trigger AI summary generation if enough comments
                                    accessToken?.let { token ->
                                        communityVm?.triggerSummaryGeneration(token, articleId)
                                    }
                                } else {
                                    Log.e(TAG, "post comment failed: ${response.status} ${response.bodyAsText()}")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "post comment error", e)
                            } finally {
                                posting = false
                            }
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
                            contentDescription = t("Post"),
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
    onDelete: () -> Unit,
    onReport: () -> Unit = {},
    compare: GraphCompareContext? = null
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
                        .size(72.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(72.dp)
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
                comment.profiles?.displayName ?: t("Anonymous"),
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
                        contentDescription = t("Reply"),
                        tint = AppTheme.SubtleTextColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (isOwn) {
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = t("Delete"),
                        tint = AppTheme.SubtleTextColor.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                IconButton(onClick = onReport, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Outlined.Flag,
                        contentDescription = t("Report"),
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
            AttachedGraphCard(comment.attachment, compare = compare)
        }
    }
}

//  Graph Attachment Card 
// Renders the graph from JSON using CommunityGraphViewModel + InsightsTimelineGraph or RiskGraphCanvas

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttachedGraphCard(attachment: JsonObject, startPadding: Dp = 36.dp, compare: GraphCompareContext? = null) {
    Log.d("GraphCard", "attachment keys: ${attachment.keys}")
    val graphVm = remember(attachment) {
        CommunityGraphViewModel().apply { loadFromJson(attachment) }
    }

    val isRisk by graphVm.isRiskGraph.collectAsState()

    if (isRisk) {
        AttachedRiskGraphCard(graphVm, startPadding, compare)
    } else {
        AttachedInsightsGraphCard(graphVm, startPadding, compare)
    }
}

/** What "Compare mine" needs: the reader's own data + where the upsell goes. */
data class GraphCompareContext(
    val accessToken: String,
    val insightsVm: InsightsViewModel,
    val onUpgrade: () -> Unit
)

/** Small chip under a shared graph. Premium toggles the reader's own line; free taps go to the paywall. */
@Composable
private fun CompareMineChip(on: Boolean, loading: Boolean, onToggle: () -> Unit, onUpgrade: () -> Unit) {
    val premiumState by PremiumManager.state.collectAsState()
    if (premiumState.access == PremiumAccess.LOADING) return
    val premium = premiumState.isPremium
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) AppTheme.AccentPurple.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f))
            .clickable { if (premium) onToggle() else onUpgrade() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(color = AppTheme.AccentPurple, strokeWidth = 1.5.dp, modifier = Modifier.size(10.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            if (premium) t("Compare mine") else t("Compare mine") + " \uD83D\uDD12",
            color = if (on) AppTheme.AccentPurple else AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
        )
    }
}

/** The reader's own series for the metrics in a shared graph, over the shared dates. Matched by label (AllMetricDefs labels are unique). */
private fun readerSeriesFor(shared: List<MetricSeries>, mine: Map<String, List<InsightsViewModel.DailyValue>>, dates: Set<String>): List<MetricSeries> =
    shared.mapNotNull { s ->
        val def = AllMetricDefs.firstOrNull { it.key == s.key || it.label == s.label } ?: return@mapNotNull null
        val pts = mine[def.key]?.filter { it.date in dates }?.sortedBy { it.date }?.map { DailyMetricPoint(it.date, it.value) } ?: return@mapNotNull null
        if (pts.size < 2) null else MetricSeries(def.key, tSync("Mine") + " · " + tSync(def.label), def.unit, def.color.copy(alpha = 0.9f), pts, dashed = true)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachedInsightsGraphCard(graphVm: CommunityGraphViewModel, startPadding: Dp, compare: GraphCompareContext? = null) {
    val migraines by graphVm.migraines.collectAsState()
    val events by graphVm.events.collectAsState()
    val sharedSeries by graphVm.metricSeries.collectAsState()
    val windowStart by graphVm.windowStart.collectAsState()
    val windowEnd by graphVm.windowEnd.collectAsState()

    if (windowStart == null || windowEnd == null) return

    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("d MMM", appLocale()).withZone(zone)
    val rangeLabel = "${fmt.format(windowStart)} - ${fmt.format(windowEnd)}"

    // "Compare mine": overlay the reader's own line for the same metrics and dates.
    var compareOn by remember { mutableStateOf(false) }
    var compareLoading by remember { mutableStateOf(false) }
    val ctx = LocalContext.current.applicationContext
    val emptyMine = remember { kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, List<InsightsViewModel.DailyValue>>()) }
    val mineMetrics by (compare?.insightsVm?.allDailyMetrics ?: emptyMine).collectAsState()
    LaunchedEffect(compareOn) {
        if (compareOn && compare != null && mineMetrics.isEmpty()) {
            compareLoading = true
            compare.insightsVm.load(ctx, compare.accessToken)
        }
    }
    LaunchedEffect(mineMetrics) { if (mineMetrics.isNotEmpty()) compareLoading = false }
    val dates = remember(windowStart, windowEnd) {
        val f = java.time.LocalDate.ofInstant(windowStart, zone); val t = java.time.LocalDate.ofInstant(windowEnd, zone)
        generateSequence(f) { it.plusDays(1) }.takeWhile { !it.isAfter(t) }.map { it.toString() }.toSet()
    }
    val metricSeries = if (compareOn && compare != null) sharedSeries + readerSeriesFor(sharedSeries, mineMetrics, dates) else sharedSeries

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startPadding, end = if (startPadding > 0.dp) 8.dp else 0.dp),
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
                            Text(t(series.label), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
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
            if (compare != null && sharedSeries.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    CompareMineChip(on = compareOn, loading = compareOn && compareLoading, onToggle = { compareOn = !compareOn }, onUpgrade = compare.onUpgrade)
                }
                if (compareOn && !compareLoading && metricSeries.size == sharedSeries.size) {
                    Text(t("No data of yours for these dates"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachedRiskGraphCard(graphVm: CommunityGraphViewModel, startPadding: Dp, compare: GraphCompareContext? = null) {
    val riskDays by graphVm.riskDays.collectAsState()
    if (riskDays.isEmpty()) return

    // "Compare mine": the reader's own risk on the same dates.
    var compareOn by remember { mutableStateOf(false) }
    var compareLoading by remember { mutableStateOf(false) }
    var mine by remember { mutableStateOf<List<RiskDayPoint>>(emptyList()) }
    LaunchedEffect(compareOn) {
        if (compareOn && compare != null && mine.isEmpty()) {
            compareLoading = true
            val wanted = riskDays.map { it.date }.toSet()
            val rows = withContext(Dispatchers.IO) {
                try { SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY).getRiskScoreDaily(compare.accessToken, 400) } catch (_: Exception) { emptyList() }
            }
            mine = rows.filter { it.date in wanted }.map { RiskDayPoint(it.date, it.score.toFloat(), it.zone) }
            compareLoading = false
        }
    }
    val mineShown = if (compareOn) mine else emptyList()

    val fmt = DateTimeFormatter.ofPattern("d MMM", appLocale())
    val days = riskDays.sortedBy { it.date }
    val from = LocalDate.parse(days.first().date).format(fmt)
    val to = LocalDate.parse(days.last().date).format(fmt)
    val avg = days.map { it.score }.average().toFloat()
    val mineAvg = mineShown.takeIf { it.isNotEmpty() }?.map { it.score }?.average()?.toFloat()
    fun fmt1(v: Float) = if (v >= 10f || v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)
    val mineColor = Color.White.copy(alpha = 0.85f)

    // Same card as the Monitor Risk History graph: reading first, y values at
    // the side, the house curve, and one key line under the plot.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startPadding, end = if (startPadding > 0.dp) 8.dp else 0.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0628).copy(alpha = 0.85f)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            GraphCardHeader(
                title = t("Risk History"),
                subtitle = "$from \u2013 $to",
                readout = fmt1(avg),
                readoutUnit = "",
                readoutColor = riskZoneColor(avg),
                readoutCaption = t("%s-day average", days.size)
            )
            Spacer(Modifier.height(10.dp))

            val maxVal = maxOf(days.maxOf { it.score }, mineShown.maxOfOrNull { it.score } ?: 0f)
            val axisMax = (maxOf(RISK_GRAPH_HIGH_T, maxVal) * 1.15f).coerceAtLeast(12f)
            Row(Modifier.fillMaxWidth().height(150.dp)) {
                Column(
                    Modifier.width(34.dp).fillMaxHeight().padding(vertical = 2.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    Text(fmt1(axisMax), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    Text(fmt1(axisMax / 2f), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    Text("0", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(8.dp))
                Canvas(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                    val w = size.width; val h = size.height
                    val n = days.size
                    val chartH = h - 8f
                    fun yZ(v: Float) = 4f + chartH * (1f - v / axisMax)
                    fun xFor(i: Int) = if (n <= 1) w / 2f else (i.toFloat() / (n - 1)) * w

                    // Zone bands + thresholds, as on the Monitor graph
                    drawRect(Color(0xFF81C784).copy(alpha = 0.07f), Offset(0f, yZ(RISK_GRAPH_MILD_T)), Size(w, yZ(RISK_GRAPH_LOW_T) - yZ(RISK_GRAPH_MILD_T)))
                    drawRect(Color(0xFFFFB74D).copy(alpha = 0.07f), Offset(0f, yZ(RISK_GRAPH_HIGH_T)), Size(w, yZ(RISK_GRAPH_MILD_T) - yZ(RISK_GRAPH_HIGH_T)))
                    drawRect(Color(0xFFEF5350).copy(alpha = 0.07f), Offset(0f, 0f), Size(w, yZ(RISK_GRAPH_HIGH_T)))
                    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
                    for ((thr, c) in listOf(RISK_GRAPH_LOW_T to Color(0xFF81C784), RISK_GRAPH_MILD_T to Color(0xFFFFB74D), RISK_GRAPH_HIGH_T to Color(0xFFEF5350))) {
                        drawLine(c.copy(alpha = 0.25f), Offset(0f, yZ(thr)), Offset(w, yZ(thr)), strokeWidth = 1f, pathEffect = dash)
                    }
                    // Gridlines at the three y labels
                    for (f in listOf(0f, 0.5f, 1f)) {
                        val y = 4f + chartH * f
                        drawLine(Color.White.copy(alpha = 0.07f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                    }

                    // Poster's line: the house curve, coloured by zone per segment
                    val pts = days.mapIndexed { i, d -> Triple(xFor(i), yZ(d.score), d.score) }
                    val offsets = pts.map { Offset(it.first, it.second) }
                    if (pts.size >= 2) {
                        for (i in 0 until pts.size - 1) {
                            val c = riskZoneColor((pts[i].third + pts[i + 1].third) / 2f).copy(alpha = 0.9f)
                            val seg = smoothSegment(offsets, i)
                            drawPath(seg, c.copy(alpha = 0.12f), style = androidx.compose.ui.graphics.drawscope.Stroke(7f, cap = StrokeCap.Round))
                            drawPath(seg, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f, cap = StrokeCap.Round))
                        }
                    }
                    for ((x, y, v) in pts) drawCircle(riskZoneColor(v), radius = 3.5f, center = Offset(x, y))

                    // Reader's line, dashed white
                    if (mineShown.size >= 2) {
                        val byDate = mineShown.associateBy { it.date }
                        val mp = days.mapIndexedNotNull { i, d -> byDate[d.date]?.let { Offset(xFor(i), yZ(it.score)) } }
                        if (mp.size >= 2) {
                            drawPath(smoothPath(mp), mineColor, style = androidx.compose.ui.graphics.drawscope.Stroke(2f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))))
                        }
                    }
                }
            }
            // Dates under the plot
            Row(Modifier.fillMaxWidth().padding(start = 42.dp, top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(from, color = AppTheme.SubtleTextColor.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                if (days.size > 2) Text(LocalDate.parse(days[days.size / 2].date).format(fmt), color = AppTheme.SubtleTextColor.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                Text(to, color = AppTheme.SubtleTextColor.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }

            // One key line under the plot
            Spacer(Modifier.height(9.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            Spacer(Modifier.height(9.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFEF5350)))
                    Spacer(Modifier.width(5.dp))
                    Text(t("Risk Score") + " \u00b7 " + t("avg %s", fmt1(avg)), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                }
                if (mineAvg != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(width = 14.dp, height = 8.dp)) {
                            drawLine(mineColor, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
                        }
                        Spacer(Modifier.width(5.dp))
                        Text(t("Mine") + " \u00b7 " + t("avg %s", fmt1(mineAvg)), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    }
                }
                for (thr in listOf(RISK_GRAPH_MILD_T to t("Mild"), RISK_GRAPH_HIGH_T to t("High"))) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(riskZoneColor(thr.first).copy(alpha = 0.5f)))
                        Spacer(Modifier.width(5.dp))
                        Text(thr.second + " \u2265 " + fmt1(thr.first), color = AppTheme.SubtleTextColor.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (compare != null) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    CompareMineChip(on = compareOn, loading = compareOn && compareLoading, onToggle = { compareOn = !compareOn }, onUpgrade = compare.onUpgrade)
                }
                if (compareOn && !compareLoading && mine.size < 2) {
                    Text(t("No data of yours for these dates"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
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
            minutes < 1 -> tSync("just now")
            minutes < 60 -> tSync("%sm##forumage", minutes)
            hours < 24 -> tSync("%sh##forumage", hours)
            days < 7 -> tSync("%sd##forumage", days)
            else -> {
                val fmt = DateTimeFormatter.ofPattern("d MMM", appLocale()).withZone(ZoneId.systemDefault())
                fmt.format(instant)
            }
        }
    } catch (_: Exception) { "" }
}

