// app/src/main/java/com/migraineme/CommunityEngagement.kt
//
// Community engagement composables:
// 1. DiscussionStarterCard — AI-powered contextual prompt
// 2. ThreadSummaryBanner — AI summary at top of thread detail

package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


// ═════════════════════════════════════════════════════════════════════════════
// 1. DISCUSSION STARTER CARD — contextual prompt based on user's top trigger
// ═════════════════════════════════════════════════════════════════════════════

@Composable
fun DiscussionStarterCard(
    starter: DiscussionStarter,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gradientBrush = remember {
        Brush.linearGradient(
            colors = listOf(
                AppTheme.AccentPurple.copy(alpha = 0.12f),
                AppTheme.AccentPink.copy(alpha = 0.08f)
            )
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        shape = AppTheme.BaseCardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush, AppTheme.BaseCardShape)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Outlined.Groups,
                        contentDescription = null,
                        tint = AppTheme.AccentPurple,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "Community",
                        color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    )
                }

                Text(
                    buildString {
                        append("Your #1 predicted trigger is ")
                        append(starter.triggerName)
                        append(". ")
                        if (starter.communityCount > 1) {
                            val formatted = if (starter.communityCount >= 1000)
                                "${starter.communityCount / 1000},${(starter.communityCount % 1000).toString().padStart(3, '0')}"
                            else
                                starter.communityCount.toString()
                            append("$formatted other users share this trigger.")
                        }
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        lineHeight = 22.sp
                    )
                )

                Text(
                    "See what works for them →",
                    color = AppTheme.AccentPink,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}


// ═════════════════════════════════════════════════════════════════════════════
// 2. THREAD SUMMARY BANNER — AI summary shown at top of thread detail
// ═════════════════════════════════════════════════════════════════════════════

@Composable
fun ThreadSummaryBanner(
    summary: String,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    val bgBrush = remember {
        Brush.linearGradient(
            colors = listOf(
                AppTheme.AccentPurple.copy(alpha = 0.10f),
                AppTheme.AccentPink.copy(alpha = 0.06f)
            )
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bgBrush, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = AppTheme.AccentPurple,
                modifier = Modifier.size(16.dp)
            )
            Text(
                "Community Summary",
                color = AppTheme.AccentPurple,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            )
        }

        Text(
            summary,
            color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.bodySmall.copy(
                lineHeight = 20.sp,
                fontStyle = FontStyle.Italic
            )
        )
    }
}
