// app/src/main/java/com/migraineme/CompanionsScreen.kt
package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest

// ═════════════════════════════════════════════════════════════════════════════
// 1. SETTINGS SCREEN: Manage AI Companion Subscriptions
// ═════════════════════════════════════════════════════════════════════════════

@Composable
fun CompanionsSettingsScreen(
    accessToken: String?,
    onBack: () -> Unit,
    vm: CompanionsViewModel = viewModel()
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(accessToken) {
        accessToken?.let { vm.loadAll(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.FadeColor)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White)
            }
            Text(
                "AI Companions",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }

        Text(
            "AI Companions are curators that flag relevant articles for your migraine profile. Subscribe to the ones that match you and they'll surface useful content directly to your feed.",
            color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp)
        )

        if (state.loading) {
            Box(
                Modifier.fillMaxWidth().height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AppTheme.AccentPurple)
            }
        } else {
            state.companions.forEach { companion ->
                CompanionCard(
                    companion = companion,
                    isSubscribed = companion.id in state.subscribedIds,
                    onToggle = { accessToken?.let { vm.toggleSubscription(it, companion.id) } }
                )
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}


// ═════════════════════════════════════════════════════════════════════════════
// 2. ONBOARDING SCREEN: "Meet Your AI Companions"
// ═════════════════════════════════════════════════════════════════════════════

@Composable
fun CompanionsOnboardingScreen(
    accessToken: String?,
    recommendedSlugs: List<String> = emptyList(),
    onContinue: () -> Unit,
    vm: CompanionsViewModel = viewModel()
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(accessToken) {
        accessToken?.let { vm.loadAll(it) }
    }

    // Map AI-recommended slugs to companion IDs
    val recommendedIds = remember(state.companions, recommendedSlugs) {
        val slugToId = state.companions.associateBy({ it.slug }, { it.id })
        recommendedSlugs.mapNotNull { slugToId[it] }.toSet()
    }

    // Pre-select recommended ones
    var selectedIds by remember(recommendedIds) {
        mutableStateOf(recommendedIds)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.FadeColor)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(24.dp))

        // Title
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Outlined.SmartToy,
                contentDescription = null,
                tint = AppTheme.AccentPurple,
                modifier = Modifier.size(28.dp)
            )
            Text(
                "Meet Your AI Companions",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        }

        Text(
            "These AI curators will flag relevant articles for your migraine profile and add them to your feed automatically. We've pre-selected the ones that best match your profile.",
            color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp)
        )

        if (state.loading) {
            Box(
                Modifier.fillMaxWidth().height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AppTheme.AccentPurple)
            }
        } else {
            state.companions.forEach { companion ->
                val isSelected = companion.id in selectedIds
                val isRecommended = companion.id in recommendedIds

                CompanionSelectCard(
                    companion = companion,
                    isSelected = isSelected,
                    isRecommended = isRecommended,
                    onToggle = {
                        selectedIds = if (isSelected) selectedIds - companion.id
                        else selectedIds + companion.id
                    }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Continue button
        Button(
            onClick = {
                accessToken?.let { token ->
                    if (selectedIds.isNotEmpty()) {
                        vm.subscribeToMultiple(token, selectedIds)
                    }
                }
                onContinue()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
        ) {
            Text(
                if (selectedIds.isEmpty()) "Continue without companions" else "Continue with ${selectedIds.size} companion${if (selectedIds.size != 1) "s" else ""}",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}


// ═════════════════════════════════════════════════════════════════════════════
// SHARED: Companion Card Components
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Card used in Settings with a toggle switch.
 */
@Composable
private fun CompanionCard(
    companion: CompanionRow,
    isSubscribed: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppTheme.BaseCardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (isSubscribed) AppTheme.AccentPurple.copy(alpha = 0.08f)
            else AppTheme.BaseCardContainer
        ),
        elevation = CardDefaults.cardElevation(0.dp),
        border = androidx.compose.foundation.BorderStroke(
            if (isSubscribed) 1.5.dp else 1.dp,
            Brush.linearGradient(
                if (isSubscribed) listOf(AppTheme.AccentPurple.copy(alpha = 0.5f), AppTheme.AccentPink.copy(alpha = 0.3f))
                else listOf(AppTheme.AccentPurple.copy(alpha = 0.25f), AppTheme.AccentPink.copy(alpha = 0.15f))
            )
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Robot avatar
                BotAvatar(companion.slug, 44)

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    val displayName = companion.name.replace(" The AI", "").replace(" the AI", "")
                    Text(
                        displayName,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    if (companion.subtitle.isNotBlank()) {
                        Text(
                            companion.subtitle,
                            color = AppTheme.AccentPurple,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                Switch(
                    checked = isSubscribed,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = AppTheme.AccentPurple,
                        uncheckedThumbColor = AppTheme.SubtleTextColor,
                        uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                    )
                )
            }

            Spacer(Modifier.height(8.dp))

            // What this companion helps with
            val displayName = companion.name.replace(" The AI", "").replace(" the AI", "")
            val description = buildString {
                if (companion.triggers.isNotEmpty()) {
                    append("$displayName covers ${companion.triggers.joinToString(", ")}.")
                }
                if (companion.interests.isNotEmpty()) {
                    if (isNotEmpty()) append(" ")
                    append("Interested in ${companion.interests.joinToString(", ")}.")
                }
            }
            if (description.isNotBlank()) {
                Text(
                    description,
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp)
                )
            }
        }
    }
}

/**
 * Card used in onboarding with checkbox-style selection.
 */
@Composable
private fun CompanionSelectCard(
    companion: CompanionRow,
    isSelected: Boolean,
    isRecommended: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = AppTheme.BaseCardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) AppTheme.AccentPurple.copy(alpha = 0.10f)
            else AppTheme.BaseCardContainer
        ),
        elevation = CardDefaults.cardElevation(0.dp),
        border = androidx.compose.foundation.BorderStroke(
            if (isSelected) 1.5.dp else 1.dp,
            Brush.linearGradient(
                if (isSelected) listOf(AppTheme.AccentPurple, AppTheme.AccentPink.copy(alpha = 0.6f))
                else listOf(AppTheme.AccentPurple.copy(alpha = 0.25f), AppTheme.AccentPink.copy(alpha = 0.15f))
            )
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BotAvatar(companion.slug, 44)

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val displayName = companion.name.replace(" The AI", "").replace(" the AI", "")
                        Text(
                            displayName,
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        if (isRecommended) {
                            Box(
                                modifier = Modifier
                                    .background(AppTheme.AccentPurple.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    "Recommended",
                                    color = AppTheme.AccentPurple,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }
                    }
                    if (companion.triggers.isNotEmpty()) {
                        Text(
                            companion.triggers.joinToString(", "),
                            color = AppTheme.AccentPurple,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggle() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = AppTheme.AccentPurple,
                        uncheckedColor = AppTheme.SubtleTextColor.copy(alpha = 0.4f),
                        checkmarkColor = Color.White
                    )
                )
            }

            Spacer(Modifier.height(6.dp))

            val displayName = companion.name.replace(" The AI", "").replace(" the AI", "")
            val description = buildString {
                if (companion.triggers.isNotEmpty()) {
                    append("$displayName covers ${companion.triggers.joinToString(", ")}.")
                }
                if (companion.interests.isNotEmpty()) {
                    if (isNotEmpty()) append(" ")
                    append("Interested in ${companion.interests.joinToString(", ")}.")
                }
            }
            if (description.isNotBlank()) {
                Text(
                    description,
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp)
                )
            }
        }
    }
}


// ═════════════════════════════════════════════════════════════════════════════
// Bot Avatar
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Bot avatar — loads from Supabase storage, falls back to colored icon.
 */
@Composable
fun BotAvatar(slug: String, size: Int) {
    val color = when (slug) {
        "maya"  -> Color(0xFF4FC3F7)  // light blue
        "priya" -> Color(0xFFEF9A9A)  // soft red
        "lena"  -> Color(0xFFCE93D8)  // purple
        "kai"   -> Color(0xFF81C784)  // green
        "sam"   -> Color(0xFFFFB74D)  // orange
        "jake"  -> Color(0xFFFF8A65)  // deep orange
        else    -> AppTheme.AccentPurple
    }

    val avatarUrl = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/storage/v1/object/public/avatars/${slug}.png"
    var loadFailed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (!loadFailed) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(avatarUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "${slug} avatar",
                contentScale = ContentScale.Crop,
                onError = { loadFailed = true },
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
            )
        } else {
            Icon(
                Icons.Outlined.SmartToy,
                contentDescription = "${slug} bot",
                tint = color,
                modifier = Modifier.size((size * 0.55f).dp)
            )
        }
    }
}
