// app/src/main/java/com/migraineme/BlogScreen.kt
package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

// =====================================================
// BLOGS CONTENT (standalone tab content)
// =====================================================

@Composable
fun BlogsContent(
    vm: CommunityViewModel,
    state: CommunityState,
    accessToken: String?,
    navController: androidx.navigation.NavController
) {
    // ── Loading ──
    if (state.loading && state.blogs.isEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = AppTheme.AccentPurple)
                Spacer(Modifier.height(8.dp))
                Text("Loading blogs…", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else if (state.blogs.isEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No blogs yet", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text("Check back soon!", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.blogs.forEach { blog ->
                BlogCard(
                    blog = blog,
                    onOpen = { navController.navigate("${Routes.BLOG_DETAIL}/${blog.id}") },
                    modifier = Modifier
                )
            }
        }
    }
}

@Composable
private fun BlogCard(
    blog: BlogRow,
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
            if (!blog.coverImageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(blog.coverImageUrl)
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
                // Tag pill
                if (!blog.tag.isNullOrBlank()) {
                    val tagShape = RoundedCornerShape(6.dp)
                    Box(
                        modifier = Modifier
                            .background(AppTheme.AccentPurple.copy(alpha = 0.15f), tagShape)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            blog.tag,
                            color = AppTheme.AccentPurple,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Text(
                    blog.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (!blog.excerpt.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        blog.excerpt,
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (blog.readMinutes != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${blog.readMinutes} min read",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}
