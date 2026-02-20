// app/src/main/java/com/migraineme/ArticleDetailScreen.kt
package com.migraineme

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun ArticleDetailScreen(
    articleId: String,
    vm: CommunityViewModel,
    accessToken: String?,
    currentUserId: String?,
    insightsVm: InsightsViewModel,
    onBack: () -> Unit
) {
    val state by vm.state.collectAsState()
    val article = state.articles.find { it.id == articleId }
    val context = LocalContext.current

    if (article == null) {
        Box(
            Modifier
                .fillMaxSize()
                .background(AppTheme.FadeColor),
            contentAlignment = Alignment.Center
        ) {
            Text("Article not found", color = AppTheme.SubtleTextColor)
        }
        return
    }

    val isFavorite = article.id in state.favoriteIds
    val allTags = article.articleTags?.mapNotNull { it.tags } ?: emptyList()
    val matchedTags = allTags.filter { it.id in state.userMatchingTagIds }
    val unmatchedTags = allTags.filter { it.id !in state.userMatchingTagIds }
    val sortedTags = matchedTags + unmatchedTags

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }

            Text(
                formatSource(article.source),
                color = AppTheme.AccentPurple,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = {
                accessToken?.let { vm.toggleFavorite(it, article.id) }
            }) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (isFavorite) AppTheme.AccentPink else AppTheme.SubtleTextColor
                )
            }

            IconButton(onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, article.url)
                    putExtra(Intent.EXTRA_SUBJECT, article.title)
                }
                context.startActivity(Intent.createChooser(intent, "Share article"))
            }) {
                Icon(
                    Icons.Outlined.Share,
                    contentDescription = "Share",
                    tint = AppTheme.SubtleTextColor
                )
            }
        }

        // ── Main card with image + title ──
        BaseCard {
            // Image
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
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(12.dp))
            }

            // Date
            if (article.publishedAt != null) {
                Text(
                    formatFullDate(article.publishedAt),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(6.dp))
            }

            // Title
            Text(
                article.title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    lineHeight = 26.sp
                )
            )

            // Summary
            if (!article.aiSummary.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    article.aiSummary,
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp)
                )
            }

            Spacer(Modifier.height(12.dp))

            // Tags
            if (sortedTags.isNotEmpty()) {
                Text(
                    "Topics",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (tag in sortedTags) {
                        val catColor = tagCategoryColor(tag.category)
                        val isMatched = tag.id in state.userMatchingTagIds
                        val tagShape = RoundedCornerShape(6.dp)

                        val tagModifier = if (isMatched) {
                            Modifier
                                .border(
                                    width = 1.5.dp,
                                    brush = Brush.linearGradient(
                                        colors = listOf(AppTheme.AccentPurple, AppTheme.AccentPink)
                                    ),
                                    shape = tagShape
                                )
                                .background(catColor.copy(alpha = 0.20f), tagShape)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        } else {
                            Modifier
                                .background(catColor.copy(alpha = 0.12f), tagShape)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
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
                }
                Spacer(Modifier.height(12.dp))
            }

            // Read link
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(article.url)))
                }
            ) {
                Text(
                    "Read full article",
                    color = AppTheme.AccentPurple,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = AppTheme.AccentPurple,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // ── Comments card ──
        BaseCard {
            ArticleCommentsSection(
                articleId = articleId,
                accessToken = accessToken,
                currentUserId = currentUserId,
                insightsVm = insightsVm
            )
        }

        Spacer(Modifier.height(80.dp))
    }
}

// ── Shared helpers ──

internal fun tagCategoryColor(category: String?): Color {
    return when (category) {
        "trigger" -> Color(0xFFFF8A65)
        "medicine" -> Color(0xFF4FC3F7)
        "relief" -> Color(0xFF81C784)
        "symptom" -> Color(0xFFEF9A9A)
        "prodrome" -> Color(0xFFFFD54F)
        "migraine_type" -> Color(0xFFCE93D8)
        "profile" -> Color(0xFF80CBC4)
        else -> Color(0xFFB0BEC5)
    }
}

internal fun formatSource(source: String?): String {
    return when (source) {
        "migraine_trust" -> "The Migraine Trust"
        "migraine_trust_news" -> "Migraine Trust News"
        "nhf" -> "National Headache Foundation"
        "migraine_strong" -> "Migraine Strong"
        "miles_migraine" -> "Miles for Migraine"
        "dizzy_cook" -> "The Dizzy Cook"
        "pubmed" -> "PubMed"
        else -> source?.replace("_", " ")?.replaceFirstChar { it.uppercase() } ?: "Article"
    }
}

private fun formatFullDate(isoDate: String): String {
    return try {
        val instant = java.time.Instant.parse(isoDate)
        val formatter = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy")
            .withZone(java.time.ZoneId.systemDefault())
        formatter.format(instant)
    } catch (e: Exception) {
        ""
    }
}

