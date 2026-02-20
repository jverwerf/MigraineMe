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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// ── Forum Post Card (used by CommunityScreen ForumPage) ──

@Composable
fun ForumPostCard(
    post: ForumPostRow,
    isOwn: Boolean,
    isFavorited: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    onReport: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = AppTheme.BaseCardShape,
        colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
        elevation = CardDefaults.cardElevation(0.dp),
        border = AppTheme.BaseCardBorder
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Author row
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarCircle(
                    avatarUrl = post.profiles?.avatarUrl,
                    displayName = post.profiles?.displayName,
                    size = 32
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        post.profiles?.displayName ?: "Anonymous",
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
                        Icon(Icons.Outlined.MoreVert, "More", tint = AppTheme.SubtleTextColor.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = Color(0xFF1A0628)
                    ) {
                        if (isOwn) {
                            DropdownMenuItem(
                                text = { Text("Delete", color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodySmall) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(16.dp)) },
                                onClick = { showMenu = false; onDelete() }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Report", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall) },
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
                    "${post.replyCount} ${if (post.replyCount == 1) "reply" else "replies"}",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(28.dp)) {
                    Icon(
                        if (isFavorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (isFavorited) "Unfavorite" else "Favorite",
                        tint = if (isFavorited) AppTheme.AccentPink else AppTheme.SubtleTextColor,
                        modifier = Modifier.size(18.dp)
                    )
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
    onSubmit: (reason: String) -> Unit
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

    if (submitted) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF1A0628),
            titleContentColor = Color.White,
            textContentColor = AppTheme.BodyTextColor,
            title = { Text("Report submitted", fontWeight = FontWeight.Bold) },
            text = { Text("Thanks for letting us know. We'll review this content shortly.", style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) { Text("Done") }
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
                    "Why are you reporting this?",
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
            ) { Text("Report") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = AppTheme.SubtleTextColor) }
        }
    )
}

// ── Create Forum Post Dialog ──

@Composable
fun CreateForumPostDialog(
    onDismiss: () -> Unit,
    onPost: (title: String, body: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A0628),
        titleContentColor = Color.White,
        textContentColor = AppTheme.BodyTextColor,
        title = { Text("New Discussion", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 200) title = it },
                    placeholder = { Text("Title...", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium) },
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
                    placeholder = { Text("What's on your mind?", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall) },
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
            }
        },
        confirmButton = {
            Button(
                onClick = { onPost(title.trim(), body.trim()) },
                enabled = title.isNotBlank() && body.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
            ) { Text("Post") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = AppTheme.SubtleTextColor) }
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
    onBack: () -> Unit
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
            Text("Discussion not found", color = AppTheme.SubtleTextColor)
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

    val isFavorited = postId in state.forumFavoriteIds

    // Report dialogs
    if (reportingPost && post != null) {
        ReportDialog(
            title = "Report post",
            onDismiss = { reportingPost = false },
            onSubmit = { reason ->
                accessToken?.let { vm.reportContent(it, postId = post.id, commentId = null, reason = reason) }
            }
        )
    }
    reportingComment?.let { comment ->
        ReportDialog(
            title = "Report comment",
            onDismiss = { reportingComment = null },
            onSubmit = { reason ->
                accessToken?.let { vm.reportContent(it, postId = null, commentId = comment.id, reason = reason) }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.FadeColor)
    ) {
        // Scrollable content
        Column(
            modifier = Modifier
                .weight(1f)
                .background(AppTheme.FadeColor)
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
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White)
                }
                Text(
                    "Discussion",
                    color = AppTheme.AccentPurple,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f)
                )
                if (accessToken != null) {
                    IconButton(onClick = { vm.toggleForumFavorite(accessToken, postId) }) {
                        Icon(
                            if (isFavorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (isFavorited) "Unfavorite" else "Favorite",
                            tint = if (isFavorited) AppTheme.AccentPink else AppTheme.SubtleTextColor
                        )
                    }
                    // Report post button (only for others' posts)
                    if (post != null && post.userId != currentUserId) {
                        IconButton(onClick = { reportingPost = true }) {
                            Icon(Icons.Outlined.Flag, "Report", tint = AppTheme.SubtleTextColor.copy(alpha = 0.6f))
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
                                pinnedTopic.title,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 24.sp
                                )
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                pinnedTopic.description,
                                color = AppTheme.BodyTextColor,
                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp)
                            )
                        }
                    }
                }
            } else if (post != null) {
                HeroCard {
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                        AvatarCircle(post.profiles?.avatarUrl, post.profiles?.displayName, 36)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                post.profiles?.displayName ?: "Anonymous",
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
                        }
                    }
                }
            }

            // ── Replies card ──
            BaseCard {
                Text(
                    "Replies (${state.forumComments.size})",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(8.dp))

                if (state.forumCommentsLoading) {
                    Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AppTheme.AccentPurple, modifier = Modifier.size(24.dp))
                    }
                } else if (topLevel.isEmpty()) {
                    Text("No replies yet — be the first!", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                } else {
                    topLevel.forEach { comment ->
                        ForumCommentBubble(
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
                IconButton(onClick = { replyingTo = null }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Outlined.Close, "Cancel", tint = AppTheme.SubtleTextColor, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // ── Comment input ──
        if (accessToken != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppTheme.FadeColor)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = commentText,
                    onValueChange = { if (it.length <= 2000) commentText = it },
                    placeholder = { Text("Write a reply...", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall) },
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
                        vm.postForumComment(accessToken, postId, text, replyingTo?.id)
                        commentText = ""
                        replyingTo = null
                        posting = false
                    },
                    enabled = commentText.isNotBlank() && !posting
                ) {
                    if (posting) {
                        CircularProgressIndicator(color = AppTheme.AccentPurple, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.Send, "Post", tint = if (commentText.isNotBlank()) AppTheme.AccentPurple else AppTheme.SubtleTextColor)
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
    onReport: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarCircle(comment.profiles?.avatarUrl, comment.profiles?.displayName, 28)
            Spacer(Modifier.width(8.dp))
            Text(comment.profiles?.displayName ?: "Anonymous", color = Color.White, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.width(8.dp))
            Text(formatForumDate(comment.createdAt), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            if (onReply != null) {
                IconButton(onClick = onReply, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Reply, "Reply", tint = AppTheme.SubtleTextColor, modifier = Modifier.size(16.dp))
                }
            }
            if (isOwn) {
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Delete, "Delete", tint = AppTheme.SubtleTextColor.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                }
            } else {
                IconButton(onClick = onReport, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Flag, "Report", tint = AppTheme.SubtleTextColor.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                }
            }
        }
        Text(comment.body, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp), modifier = Modifier.padding(start = 36.dp, top = 2.dp, end = 8.dp))
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
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m"
            hours < 24 -> "${hours}h"
            days < 7 -> "${days}d"
            else -> DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault()).format(instant)
        }
    } catch (_: Exception) { "" }
}
