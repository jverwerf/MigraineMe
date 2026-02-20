// app/src/main/java/com/migraineme/CommunityScreen.kt
package com.migraineme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(
    authVm: AuthViewModel,
    navController: androidx.navigation.NavController,
    vm: CommunityViewModel = viewModel()
) {
    val authState by authVm.state.collectAsState()
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    LaunchedEffect(authState.accessToken) {
        authState.accessToken?.let {
            vm.loadAll(it)
            vm.markAllRead(it)
        }
    }

    // Fade alpha based on scroll
    val fadeAlpha by remember {
        derivedStateOf {
            val fadePx = with(density) { AppTheme.FadeDistance.toPx() }
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / fadePx).coerceIn(0f, 1f)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Fade overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppTheme.FadeColor.copy(alpha = fadeAlpha))
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Community HeroCard with tabs + content ──
            item("community_hero") {
                CommunityHeroCard {
                    // ── Top-level tab row: Articles / Forum ──
                    SegmentedTabRow(
                        tabs = listOf("Articles", "Forum"),
                        selectedIndex = state.topTab,
                        onSelect = { vm.selectTopTab(it) },
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )

                    // ── Tab content ──
                    when (state.topTab) {
                        0 -> ArticlesContent(vm, state, authState.accessToken, navController)
                        1 -> ForumContent(vm, state, authState.accessToken, authState.userId, navController)
                    }
                }
            }

            // Bottom padding
            item("bottom") { Spacer(Modifier.height(80.dp)) }
        }

        // Forum FAB — only when Forum tab active
        if (state.topTab == 1 && authState.accessToken != null) {
            var showCreateDialog by remember { mutableStateOf(false) }

            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = AppTheme.AccentPurple,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 88.dp)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "New discussion")
            }

            if (showCreateDialog) {
                CreateForumPostDialog(
                    onDismiss = { showCreateDialog = false },
                    onPost = { title, body ->
                        vm.createForumPost(authState.accessToken!!, title, body)
                        showCreateDialog = false
                    }
                )
            }
        }
    }
}

// =====================================================
// ARTICLES CONTENT (inside HeroCard)
// =====================================================

@Composable
private fun ArticlesContent(
    vm: CommunityViewModel,
    state: CommunityState,
    accessToken: String?,
    navController: androidx.navigation.NavController
) {
    val articleSubTabs = listOf("For You", "Latest", "Browse")

    // Derive article list based on sub-tab
    val articles = when (state.selectedTab) {
        0 -> vm.getForYouArticles()
        1 -> vm.getLatestArticles()
        2 -> state.articles // browse shows all, or filtered by tag
        3 -> state.articles.filter { it.id in state.favoriteIds }
        else -> state.articles
    }

    var browseTagId by remember { mutableStateOf<String?>(null) }
    val displayArticles = if (state.selectedTab == 2 && browseTagId != null) {
        vm.getArticlesByTag(browseTagId!!)
    } else articles

    // ── Article sub-tabs ──
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        articleSubTabs.forEachIndexed { index, label ->
            val selected = state.selectedTab == index
            FilterChip(
                selected = selected,
                onClick = { vm.selectTab(index) },
                label = {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AppTheme.AccentPurple.copy(alpha = 0.25f),
                    selectedLabelColor = Color.White,
                    containerColor = Color.White.copy(alpha = 0.04f),
                    labelColor = AppTheme.SubtleTextColor
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = Color.White.copy(alpha = 0.08f),
                    selectedBorderColor = AppTheme.AccentPurple.copy(alpha = 0.4f),
                    enabled = true,
                    selected = selected
                )
            )
        }

        // Saved tab — heart icon
        val savedSelected = state.selectedTab == 3
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .then(
                    if (savedSelected) Modifier
                        .background(AppTheme.AccentPurple.copy(alpha = 0.25f))
                        .border(1.dp, AppTheme.AccentPurple.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    else Modifier
                        .background(Color.White.copy(alpha = 0.04f))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                )
                .clickable { vm.selectTab(3) },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(18.dp)) {
                drawSavedHeart(
                    if (savedSelected) AppTheme.AccentPink else AppTheme.SubtleTextColor,
                    filled = savedSelected
                )
            }
        }
    }

    // ── Loading ──
    if (state.loading) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = AppTheme.AccentPurple)
                Spacer(Modifier.height(8.dp))
                Text("Loading articles\u2026", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else {
        // ── For You hero (personalise prompt) ──
        if (state.selectedTab == 0 && state.userMatchingTagIds.isEmpty()) {
            PersonaliseCard(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(
                    "Personalise your feed",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    "Log your triggers, medicines, and symptoms to get article recommendations tailored to your migraine profile.",
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // ── Browse tags ──
        if (state.selectedTab == 2) {
            BrowseTagsSection(
                vm = vm,
                selectedTagId = browseTagId,
                onTagSelected = { browseTagId = it }
            )
        }

        // ── Empty saved state ──
        if (state.selectedTab == 3 && displayArticles.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(modifier = Modifier.size(48.dp)) {
                        drawSavedHeart(AppTheme.SubtleTextColor.copy(alpha = 0.4f), filled = false)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("No saved articles yet", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text("Tap the heart on any article to save it", color = AppTheme.SubtleTextColor.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ── Article cards ──
        if (displayArticles.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                displayArticles.forEach { article ->
                    ArticleCard(
                        article = article,
                        isFavorite = article.id in state.favoriteIds,
                        matchingTagIds = state.userMatchingTagIds,
                        commentCount = state.commentCounts[article.id] ?: 0,
                        onFavorite = { accessToken?.let { vm.toggleFavorite(it, article.id) } },
                        onOpen = { navController.navigate("${Routes.COMMUNITY}/article/${article.id}") },
                        modifier = Modifier
                    )
                }
            }
        } else if (!state.loading && state.selectedTab != 3) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No articles yet", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text("Check back soon!", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// =====================================================
// FORUM CONTENT (inside HeroCard)
// =====================================================

/** Hardcoded pinned discussion topics — always shown at top */
@Composable
private fun ForumContent(
    vm: CommunityViewModel,
    state: CommunityState,
    accessToken: String?,
    currentUserId: String?,
    navController: androidx.navigation.NavController
) {
    val pinnedIds = PinnedTopics.all.map { it.id }.toSet()
    val regularPosts = state.forumPosts.filter { it.id !in pinnedIds }
    var reportingPost by remember { mutableStateOf<ForumPostRow?>(null) }

    reportingPost?.let { post ->
        ReportDialog(
            title = "Report post",
            onDismiss = { reportingPost = null },
            onSubmit = { reason ->
                accessToken?.let { vm.reportContent(it, postId = post.id, commentId = null, reason = reason) }
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ── Pinned topics — always visible ──
        PinnedTopics.all.forEach { topic ->
            PinnedTopicCard(
                topic = topic,
                isFavorited = topic.id in state.forumFavoriteIds,
                onOpen = { navController.navigate("${Routes.COMMUNITY}/forum/${topic.id}") },
                onToggleFavorite = { accessToken?.let { vm.toggleForumFavorite(it, topic.id) } }
            )
        }

        // ── Loading ──
        if (state.loading && state.forumPosts.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AppTheme.AccentPurple)
                    Spacer(Modifier.height(8.dp))
                    Text("Loading discussions\u2026", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else if (regularPosts.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No discussions yet", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text("Tap + to start the conversation!", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            regularPosts.forEach { post ->
                ForumPostCard(
                    post = post,
                    isOwn = post.userId == currentUserId,
                    isFavorited = post.id in state.forumFavoriteIds,
                    onOpen = { navController.navigate("${Routes.COMMUNITY}/forum/${post.id}") },
                    onDelete = { accessToken?.let { vm.deleteForumPost(it, post.id) } },
                    onToggleFavorite = { accessToken?.let { vm.toggleForumFavorite(it, post.id) } },
                    onReport = { reportingPost = post },
                    modifier = Modifier
                )
            }
        }
    }
}

@Composable
private fun PinnedTopicCard(
    topic: PinnedTopicData,
    isFavorited: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val borderBrush = remember {
        Brush.linearGradient(
            colors = listOf(
                AppTheme.AccentPurple.copy(alpha = 0.50f),
                AppTheme.AccentPink.copy(alpha = 0.35f)
            )
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = AppTheme.BaseCardShape,
        colors = CardDefaults.cardColors(
            containerColor = AppTheme.AccentPurple.copy(alpha = 0.08f)
        ),
        elevation = CardDefaults.cardElevation(0.dp),
        border = BorderStroke(1.dp, borderBrush)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // HubIcon circle
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppTheme.AccentPurple.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(24.dp)) {
                    topic.drawIcon(this, AppTheme.AccentPurple)
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "PINNED",
                        color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    )
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    topic.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )

                Spacer(Modifier.height(2.dp))

                Text(
                    topic.description,
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    if (isFavorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (isFavorited) "Unfavorite" else "Favorite",
                    tint = if (isFavorited) AppTheme.AccentPink else AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// =====================================================
// COMMUNITY HERO CARD (matches HeroCard styling from HomeScreen)
// =====================================================

@Composable
private fun CommunityHeroCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val borderBrush = remember {
        Brush.linearGradient(
            colors = listOf(
                AppTheme.AccentPurple.copy(alpha = 0.60f),
                AppTheme.AccentPink.copy(alpha = 0.55f)
            )
        )
    }

    Box(modifier = modifier.fillMaxWidth()) {
        // Gradient border
        Canvas(modifier = Modifier.matchParentSize()) {
            val strokePx = 2.dp.toPx()
            val cr = CornerRadius(24.dp.toPx(), 24.dp.toPx())
            drawRoundRect(
                brush = borderBrush,
                topLeft = Offset(strokePx / 2f, strokePx / 2f),
                size = Size(size.width - strokePx, size.height - strokePx),
                cornerRadius = cr,
                style = Stroke(width = strokePx)
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = AppTheme.HeroCardShape,
            colors = CardDefaults.cardColors(containerColor = AppTheme.HeroCardContainer),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Top gradient accent line
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(AppTheme.AccentPurple, AppTheme.AccentPink)
                            )
                        )
                )

                // Content with inner glow
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Subtle inner glow (visual only)
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        AppTheme.AccentPurple.copy(alpha = 0.18f),
                                        Color.Transparent
                                    ),
                                    center = Offset(0f, 0f),
                                    radius = 800f
                                )
                            )
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        content = content
                    )
                }
            }
        }
    }
}

// =====================================================
// SEGMENTED TAB ROW
// =====================================================

@Composable
private fun SegmentedTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
    ) {
        tabs.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val pillShape = RoundedCornerShape(12.dp)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(4.dp)
                    .clip(pillShape)
                    .then(
                        if (selected) Modifier
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        AppTheme.AccentPurple.copy(alpha = 0.45f),
                                        AppTheme.AccentPink.copy(alpha = 0.20f)
                                    )
                                ),
                                pillShape
                            )
                            .border(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f), pillShape)
                        else Modifier
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (selected) Color.White else AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                )
            }
        }
    }
}

// =====================================================
// HEART ICON
// =====================================================

private fun DrawScope.drawSavedHeart(color: Color, filled: Boolean = false) {
    val w = size.width
    val h = size.height
    val heart = Path().apply {
        moveTo(w * 0.50f, h * 0.90f)
        cubicTo(w * 0.10f, h * 0.65f, w * 0.00f, h * 0.35f, w * 0.18f, h * 0.18f)
        cubicTo(w * 0.30f, h * 0.08f, w * 0.44f, h * 0.12f, w * 0.50f, h * 0.28f)
        cubicTo(w * 0.56f, h * 0.12f, w * 0.70f, h * 0.08f, w * 0.82f, h * 0.18f)
        cubicTo(w * 1.00f, h * 0.35f, w * 0.90f, h * 0.65f, w * 0.50f, h * 0.90f)
        close()
    }
    if (filled) drawPath(heart, color, style = Fill)
    drawPath(heart, color, style = Stroke(width = w * 0.07f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// =====================================================
// BROWSE TAGS
// =====================================================

@Composable
private fun BrowseTagsSection(
    vm: CommunityViewModel,
    selectedTagId: String?,
    onTagSelected: (String?) -> Unit
) {
    val allTags = vm.getAllTags()
    val tagsByCategory = allTags.groupBy { it.category ?: "other" }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppTheme.BaseCardShape,
        colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
        elevation = CardDefaults.cardElevation(0.dp),
        border = AppTheme.BaseCardBorder
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            for ((category, tags) in tagsByCategory) {
                val catColor = tagCategoryColor(category)
                Text(
                    category.replaceFirstChar { it.uppercase() },
                    color = catColor,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(bottom = 4.dp, top = 8.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (tag in tags) {
                        val isSelected = selectedTagId == tag.id
                        val chipShape = RoundedCornerShape(6.dp)
                        Box(
                            modifier = Modifier
                                .then(
                                    if (isSelected) Modifier.border(
                                        1.5.dp,
                                        Brush.linearGradient(listOf(AppTheme.AccentPurple, AppTheme.AccentPink)),
                                        chipShape
                                    ) else Modifier
                                )
                                .background(
                                    if (isSelected) catColor.copy(alpha = 0.25f) else catColor.copy(alpha = 0.10f),
                                    chipShape
                                )
                                .clickable { onTagSelected(if (isSelected) null else tag.id) }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                tag.name,
                                color = if (isSelected) Color.White else catColor,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

// =====================================================
// SHARED COMPOSABLES
// =====================================================

@Composable
private fun PersonaliseCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppTheme.BaseCardShape,
        colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
        elevation = CardDefaults.cardElevation(0.dp),
        border = AppTheme.BaseCardBorder
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun ArticleCard(
    article: ArticleRow,
    isFavorite: Boolean,
    matchingTagIds: Set<String> = emptySet(),
    commentCount: Int = 0,
    onFavorite: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = AppTheme.BaseCardShape,
        colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
        elevation = CardDefaults.cardElevation(0.dp),
        border = AppTheme.BaseCardBorder
    ) {
        Column {
            if (!article.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(article.imageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                )
            }

            Column(modifier = Modifier.padding(16.dp)) {
                // Source + date
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        formatSource(article.source),
                        color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    if (article.publishedAt != null) {
                        Text(
                            formatRelativeDate(article.publishedAt),
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                Text(
                    article.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(10.dp))

                // Tags
                val allTags = article.articleTags?.mapNotNull { it.tags } ?: emptyList()
                if (allTags.isNotEmpty()) {
                    val matched = allTags.filter { it.id in matchingTagIds }
                    val unmatched = allTags.filter { it.id !in matchingTagIds }
                    val sortedTags = matched + unmatched

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (tag in sortedTags.take(6)) {
                            val catColor = tagCategoryColor(tag.category)
                            val isMatched = tag.id in matchingTagIds
                            val tagShape = RoundedCornerShape(6.dp)
                            val tagModifier = if (isMatched) {
                                Modifier
                                    .border(1.5.dp, Brush.linearGradient(listOf(AppTheme.AccentPurple, AppTheme.AccentPink)), tagShape)
                                    .background(catColor.copy(alpha = 0.20f), tagShape)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            } else {
                                Modifier
                                    .background(catColor.copy(alpha = 0.12f), tagShape)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            }
                            Box(modifier = tagModifier) {
                                Text(
                                    tag.name,
                                    color = if (isMatched) Color.White else catColor,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (isMatched) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                )
                            }
                        }
                        if (sortedTags.size > 6) {
                            Box(
                                modifier = Modifier
                                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text("+${sortedTags.size - 6}", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Bottom actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clickable(onClick = onFavorite),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(18.dp)) {
                                drawSavedHeart(
                                    if (isFavorite) AppTheme.AccentPink else AppTheme.SubtleTextColor,
                                    filled = isFavorite
                                )
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        if (commentCount > 0) {
                            Icon(Icons.Outlined.ChatBubbleOutline, null, tint = AppTheme.SubtleTextColor, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("$commentCount", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onOpen)) {
                        Text("Read", color = AppTheme.AccentPurple, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Outlined.OpenInNew, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

// ── Helpers ──

private fun formatRelativeDate(isoDate: String): String {
    return try {
        val instant = Instant.parse(isoDate)
        val now = Instant.now()
        val days = ChronoUnit.DAYS.between(instant, now)
        when {
            days == 0L -> "Today"
            days == 1L -> "Yesterday"
            days < 7L -> "${days}d ago"
            days < 30L -> "${days / 7}w ago"
            days < 365L -> DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault()).format(instant)
            else -> DateTimeFormatter.ofPattern("d MMM yy").withZone(ZoneId.systemDefault()).format(instant)
        }
    } catch (e: Exception) { "" }
}



