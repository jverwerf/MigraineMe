// app/src/main/java/com/migraineme/ForumScreen.kt
package com.migraineme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.ShowChart
import kotlinx.serialization.json.JsonObject
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// ── Forum Post Card (used by CommunityScreen ForumPage) ──
// Heart = me too + follow. One action, shows count.

@Composable
fun ForumPostCard(
    post: ForumPostRow,
    isOwn: Boolean,
    isMeToo: Boolean = false,
    meTooCount: Int = 0,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onToggleMeToo: () -> Unit = {},
    onReport: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = AppTheme.BaseCardShape,
        colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardSolid),
        elevation = CardDefaults.cardElevation(0.dp),
        border = AppTheme.BaseCardBorder
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Author row
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarCircle(
                    avatarUrl = post.profiles?.avatarUrl,
                    displayName = post.profiles?.displayName,
                    size = 44
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        post.profiles?.displayName ?: t("Anonymous"),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        formatForumDate(post.createdAt),
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.MoreVert, t("More"), tint = AppTheme.SubtleTextColor.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = Color(0xFF1A0628)
                    ) {
                        if (isOwn) {
                            DropdownMenuItem(
                                text = { Text(t("Delete"), color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(16.dp)) },
                                onClick = { showMenu = false; onDelete() }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(t("Report"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall) },
                                leadingIcon = { Icon(Icons.Outlined.Flag, null, tint = AppTheme.SubtleTextColor, modifier = Modifier.size(16.dp)) },
                                onClick = { showMenu = false; onReport() }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                post.title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (post.body.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    post.body,
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ChatBubbleOutline, null, tint = AppTheme.SubtleTextColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    if (post.replyCount == 1) t("1 reply") else t("%s replies", post.replyCount),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall
                )

                Spacer(Modifier.weight(1f))

                // Heart = me too + follow
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (meTooCount > 0) {
                        Text(
                            "$meTooCount",
                            color = if (isMeToo) AppTheme.AccentPink else AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(Modifier.width(2.dp))
                    }
                    IconButton(onClick = onToggleMeToo, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (isMeToo) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (isMeToo) t("Unlike") else t("Me too"),
                            tint = if (isMeToo) AppTheme.AccentPink else AppTheme.SubtleTextColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Report Dialog ──

@Composable
fun ReportDialog(
    title: String = "Report content",
    onDismiss: () -> Unit,
    onSubmit: (reason: String) -> Unit,
    /** Block the author of the reported content (App Store guideline 1.2).
     *  null when the author is unknown or is the current user. */
    onBlock: (() -> Unit)? = null
) {
    val reasons = listOf(
        "Spam or self-promotion",
        "Harassment or bullying",
        "Misinformation",
        "Inappropriate content",
        "Other"
    )
    var selected by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }

    if (blocked) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF1A0628),
            titleContentColor = Color.White,
            textContentColor = AppTheme.BodyTextColor,
            title = { Text(t("User blocked"), fontWeight = FontWeight.Bold) },
            text = { Text(t("Their posts and replies are now hidden for you. We have been notified."), style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) { Text(t("Done")) }
            }
        )
        return
    }

    if (submitted) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF1A0628),
            titleContentColor = Color.White,
            textContentColor = AppTheme.BodyTextColor,
            title = { Text(t("Report submitted"), fontWeight = FontWeight.Bold) },
            text = { Text(t("Thanks for letting us know. We'll review this content shortly."), style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) { Text(t("Done")) }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A0628),
        titleContentColor = Color.White,
        textContentColor = AppTheme.BodyTextColor,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    t("Why are you reporting this?"),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
                reasons.forEach { reason ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = reason }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == reason,
                            onClick = { selected = reason },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = AppTheme.AccentPurple,
                                unselectedColor = AppTheme.SubtleTextColor
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(reason, color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (onBlock != null) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.12f))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onBlock(); blocked = true }
                            .padding(vertical = 6.dp)
                    ) {
                        Text(t("Block this user"), color = Color(0xFFE57373), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                        Text(t("Hides everything they post, right away."), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    submitted = true
                    onSubmit(selected)
                },
                enabled = selected.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
            ) { Text(t("Report")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("Cancel"), color = AppTheme.SubtleTextColor) }
        }
    )
}

// ── Create Forum Post Dialog ──

@Composable
fun CreateForumPostDialog(
    accessToken: String?,
    insightsVm: InsightsViewModel,
    onDismiss: () -> Unit,
    onPost: (title: String, body: String, attachment: JsonObject?) -> Unit,
    onUpgrade: () -> Unit = {},
    initialTitle: String = "",
    /** Picker tile to open on (from "Share to forum"); null = picker closed. */
    initialGraph: String? = null,
    initialDays: Int = 14
) {
    var title by remember { mutableStateOf(initialTitle) }
    var body by remember { mutableStateOf("") }
    var attachment by remember { mutableStateOf<JsonObject?>(null) }
    var showPicker by remember { mutableStateOf(initialGraph != null) }
    val premiumState by PremiumManager.state.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A0628),
        titleContentColor = Color.White,
        textContentColor = AppTheme.BodyTextColor,
        title = { Text(t("New Discussion"), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 200) title = it },
                    placeholder = { Text(t("Title..."), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.AccentPurple,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                        cursorColor = AppTheme.AccentPurple
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { if (it.length <= 5000) body = it },
                    placeholder = { Text(t("What's on your mind?"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.AccentPurple,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                        cursorColor = AppTheme.AccentPurple
                    ),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 8
                )

                // ── Add a graph (premium to attach, free to read) ──
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t("Add a graph"),
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f)
                    )
                    if (premiumState.access != PremiumAccess.LOADING && !premiumState.isPremium) {
                        Text(
                            "\uD83D\uDD12 " + t("Premium"),
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                val att = attachment
                when {
                    att != null -> {
                        AttachedGraphCard(att, startPadding = 0.dp)
                        Text(
                            t("Remove"),
                            color = AppTheme.AccentPurple,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp).clickable { attachment = null }
                        )
                    }
                    showPicker && accessToken != null -> {
                        GraphAttachmentPicker(
                            accessToken = accessToken,
                            insightsVm = insightsVm,
                            onAttach = { json -> attachment = json; showPicker = false },
                            onDismiss = { showPicker = false },
                            initialGraph = initialGraph,
                            initialDays = initialDays,
                            onUpgrade = onUpgrade,
                            attachLabel = t("Attach")
                        )
                    }
                    else -> {
                        OutlinedButton(
                            onClick = { showPicker = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Outlined.ShowChart, contentDescription = null, tint = AppTheme.AccentPurple, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("Add a graph"), color = AppTheme.AccentPurple, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onPost(title.trim(), body.trim(), attachment) },
                enabled = title.isNotBlank() && body.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
            ) { Text(t("Post")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("Cancel"), color = AppTheme.SubtleTextColor) }
        }
    )
}

// ── Forum Post Detail Screen ──

@Composable
fun ForumPostDetailScreen(
    postId: String,
    vm: CommunityViewModel,
    accessToken: String?,
    currentUserId: String?,
    insightsVm: InsightsViewModel,
    onBack: () -> Unit,
    onUpgrade: () -> Unit = {}
) {
    val state by vm.state.collectAsState()
    val pinnedTopic = PinnedTopics.find(postId)
    val isPinned = pinnedTopic != null
    val post = if (!isPinned) state.forumPosts.find { it.id == postId } else null

    LaunchedEffect(postId, accessToken) {
        accessToken?.let { vm.loadForumComments(it, postId) }
    }

    if (!isPinned && post == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LabelPlate { Text(t("Discussion not found"), color = AppTheme.SubtleTextColor) }
        }
        return
    }

    val topLevel = state.forumComments.filter { it.parentId == null }
    val repliesMap = state.forumComments.filter { it.parentId != null }.groupBy { it.parentId }

    var commentText by remember { mutableStateOf("") }
    var replyingTo by remember { mutableStateOf<ForumCommentRow?>(null) }
    var posting by remember { mutableStateOf(false) }
    var reportingPost by remember { mutableStateOf(false) }
    var reportingComment by remember { mutableStateOf<ForumCommentRow?>(null) }
    var pendingAttachment by remember { mutableStateOf<JsonObject?>(null) }
    var showGraphPicker by remember { mutableStateOf(false) }

    val isMeToo = postId in state.myMeTooIds
    val meTooCount = state.meTooCountMap[postId] ?: 0

    // Report dialogs
    if (reportingPost && post != null) {
        ReportDialog(
            title = t("Report post"),
            onDismiss = { reportingPost = false },
            onSubmit = { reason ->
                accessToken?.let { vm.reportContent(it, postId = post.id, commentId = null, reason = reason) }
            },
            onBlock = if (post.userId == null || post.userId == currentUserId) null else {
                { accessToken?.let { vm.blockUser(it, post.userId, postId = post.id) } }
            }
        )
    }
    reportingComment?.let { comment ->
        ReportDialog(
            title = t("Report comment"),
            onDismiss = { reportingComment = null },
            onSubmit = { reason ->
                accessToken?.let { vm.reportContent(it, postId = null, commentId = comment.id, reason = reason) }
            },
            onBlock = if (comment.userId == currentUserId) null else {
                { accessToken?.let { vm.blockUser(it, comment.userId, postId = postId, commentId = comment.id) } }
            }
        )
    }

    // Moderation alert
    if (state.showModerationAlert) {
        AlertDialog(
            onDismissRequest = { vm.dismissModerationAlert() },
            confirmButton = {
                TextButton(onClick = { vm.dismissModerationAlert() }) {
                    Text(t("OK"), color = AppTheme.AccentPurple)
                }
            },
            icon = { Icon(Icons.Outlined.Info, contentDescription = null, tint = AppTheme.AccentPurple) },
            title = { Text(t("Content under review"), color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text(t("Your post has been flagged by our moderation system and is currently under review. If this was a mistake, it will be restored shortly."), color = Color.White.copy(alpha = 0.7f)) },
            containerColor = Color(0xFF1A0029),
            shape = RoundedCornerShape(16.dp)
        )
    }

    Column(
    ) {
        // Scrollable content
        Column(
            modifier = Modifier
                .weight(1f)
                .clipToBounds()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Back + actions ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // One way back only: the app top bar. The label keeps the old 12dp inset.
                Spacer(Modifier.width(12.dp))
                Text(
                    t("Discussion"),
                    color = AppTheme.AccentPurple,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f)
                )
                if (accessToken != null) {
                    // Heart = me too + follow (with count)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (meTooCount > 0) {
                            Text(
                                "$meTooCount",
                                color = if (isMeToo) AppTheme.AccentPink else AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        IconButton(onClick = { vm.toggleMeToo(accessToken, postId) }) {
                            Icon(
                                if (isMeToo) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (isMeToo) t("Unlike") else t("Me too"),
                                tint = if (isMeToo) AppTheme.AccentPink else AppTheme.SubtleTextColor
                            )
                        }
                    }
                    // Report post button (only for others' posts)
                    if (post != null && post.userId != currentUserId) {
                        IconButton(onClick = { reportingPost = true }) {
                            Icon(Icons.Outlined.Flag, t("Report"), tint = AppTheme.SubtleTextColor.copy(alpha = 0.6f))
                        }
                    }
                }
            }

            // ── Hero card — post content ──
            if (isPinned && pinnedTopic != null) {
                HeroCard {
                    // Icon + title row
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AppTheme.AccentPurple.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(24.dp)) {
                                pinnedTopic.drawIcon(this, AppTheme.AccentPurple)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                t(pinnedTopic.title),
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 24.sp
                                )
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                t(pinnedTopic.description),
                                color = AppTheme.BodyTextColor,
                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp)
                            )
                        }
                    }
                }
            } else if (post != null) {
                HeroCard {
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                        AvatarCircle(post.profiles?.avatarUrl, post.profiles?.displayName, 44)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                post.profiles?.displayName ?: t("Anonymous"),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(formatForumDate(post.createdAt), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                post.title,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, lineHeight = 26.sp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(post.body, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp))
                            post.attachment?.let { att ->
                                Spacer(Modifier.height(10.dp))
                                AttachedGraphCard(att, startPadding = 0.dp, compare = accessToken?.let { GraphCompareContext(it, insightsVm, onUpgrade) })
                            }
                        }
                    }
                }
            }

            // ── AI thread summary (shown when 10+ comments) ──
            state.threadSummaries[postId]?.let { summary ->
                ThreadSummaryBanner(summary = summary)
            }

            // ── Replies card ──
            BaseCard {
                Text(
                    t("Replies (%s)", state.forumComments.size),
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(8.dp))

                if (state.forumCommentsLoading) {
                    Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AppTheme.AccentPurple, modifier = Modifier.size(24.dp))
                    }
                } else if (topLevel.isEmpty()) {
                    Text(t("No replies yet — be the first!"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                } else {
                    topLevel.forEach { comment ->
                        ForumCommentBubble(
                            compare = accessToken?.let { GraphCompareContext(it, insightsVm, onUpgrade) },
                            comment = comment,
                            isOwn = comment.userId == currentUserId,
                            onReply = { replyingTo = comment },
                            onDelete = { accessToken?.let { vm.deleteForumComment(it, comment.id, postId) } },
                            onReport = { reportingComment = comment }
                        )

                        val replies = repliesMap[comment.id] ?: emptyList()
                        for (reply in replies) {
                            Row(modifier = Modifier.padding(start = 32.dp)) {
                                Box(
                                    modifier = Modifier
                                        .width(2.dp)
                                        .height(IntrinsicSize.Max)
                                        .background(AppTheme.AccentPurple.copy(alpha = 0.2f))
                                )
                                Spacer(Modifier.width(8.dp))
                                ForumCommentBubble(
                                    compare = accessToken?.let { GraphCompareContext(it, insightsVm, onUpgrade) },
                                    comment = reply,
                                    isOwn = reply.userId == currentUserId,
                                    onReply = null,
                                    onDelete = { accessToken?.let { vm.deleteForumComment(it, reply.id, postId) } },
                                    onReport = { reportingComment = reply }
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            Spacer(Modifier.height(80.dp))
        }

        // ── Reply banner ──
        if (replyingTo != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(AppTheme.AccentPurple.copy(alpha = 0.1f).compositeOver(AppTheme.FadeColor), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    t("Replying to %s", replyingTo!!.profiles?.displayName ?: t("user")),
                    color = AppTheme.AccentPurple,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { replyingTo = null }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Outlined.Close, t("Cancel"), tint = AppTheme.SubtleTextColor, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // ── Comment input ──
        if (accessToken != null) {
            // Pending graph + picker sit above the input, scrolling on their own.
            if (pendingAttachment != null || showGraphPicker) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppTheme.FadeColor)
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                ) {
                    pendingAttachment?.let {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1A0628).copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .padding(8.dp),
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
                    if (showGraphPicker) {
                        GraphAttachmentPicker(
                            accessToken = accessToken,
                            insightsVm = insightsVm,
                            onAttach = { json -> pendingAttachment = json; showGraphPicker = false },
                            onDismiss = { showGraphPicker = false },
                            onUpgrade = onUpgrade,
                            attachLabel = t("Attach")
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppTheme.FadeColor)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showGraphPicker = !showGraphPicker }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.ShowChart,
                        contentDescription = t("Attach graph"),
                        tint = if (showGraphPicker || pendingAttachment != null) AppTheme.AccentPurple else AppTheme.SubtleTextColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                OutlinedTextField(
                    value = commentText,
                    onValueChange = { if (it.length <= 2000) commentText = it },
                    placeholder = { Text(t("Write a reply..."), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall) },
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
                        vm.postForumComment(accessToken, postId, text, replyingTo?.id, pendingAttachment)
                        commentText = ""
                        replyingTo = null
                        pendingAttachment = null
                        showGraphPicker = false
                        posting = false
                    },
                    enabled = commentText.isNotBlank() && !posting
                ) {
                    if (posting) {
                        CircularProgressIndicator(color = AppTheme.AccentPurple, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.Send, t("Post"), tint = if (commentText.isNotBlank()) AppTheme.AccentPurple else AppTheme.SubtleTextColor)
                    }
                }
            }
        }
    }
}

// ── Forum Comment Bubble ──

@Composable
private fun ForumCommentBubble(
    comment: ForumCommentRow,
    isOwn: Boolean,
    onReply: (() -> Unit)?,
    onDelete: () -> Unit,
    onReport: () -> Unit,
    compare: GraphCompareContext? = null
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarCircle(comment.profiles?.avatarUrl, comment.profiles?.displayName, 32)
            Spacer(Modifier.width(8.dp))
            Text(comment.profiles?.displayName ?: t("Anonymous"), color = Color.White, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.width(8.dp))
            Text(formatForumDate(comment.createdAt), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            if (onReply != null) {
                IconButton(onClick = onReply, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Reply, t("Reply"), tint = AppTheme.SubtleTextColor, modifier = Modifier.size(16.dp))
                }
            }
            if (isOwn) {
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Delete, t("Delete"), tint = AppTheme.SubtleTextColor.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                }
            } else {
                IconButton(onClick = onReport, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Flag, t("Report"), tint = AppTheme.SubtleTextColor.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                }
            }
        }
        Text(comment.body, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp), modifier = Modifier.padding(start = 40.dp, top = 2.dp, end = 8.dp))
        comment.attachment?.let { att ->
            Spacer(Modifier.height(6.dp))
            AttachedGraphCard(att, compare = compare)
        }
    }
}

// ── Avatar Circle ──

@Composable
fun AvatarCircle(avatarUrl: String?, displayName: String?, size: Int) {
    if (!avatarUrl.isNullOrBlank()) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(avatarUrl).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size.dp).clip(CircleShape)
        )
    } else {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(AppTheme.AccentPurple.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                (displayName?.firstOrNull() ?: '?').uppercase(),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

// ── Helpers ──

private fun formatForumDate(isoDate: String): String {
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
            else -> DateTimeFormatter.ofPattern("d MMM", appLocale())
                .withZone(ZoneId.systemDefault()).format(instant)
        }
    } catch (_: Exception) { "" }
}
