package com.migraineme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A container that fades from transparent to a solid color as the user scrolls.
 * Used by Home and Insights screens to fade out the background image.
 *
 * @param scrollState The scroll state to track
 * @param fadeColor The color to fade to (default: AppTheme.FadeColor)
 * @param fadeDistance How much scroll distance before fully opaque (default: AppTheme.FadeDistance)
 * @param content The screen content
 */
@Composable
fun ScrollFadeContainer(
    scrollState: ScrollState = rememberScrollState(),
    fadeColor: Color = AppTheme.FadeColor,
    fadeDistance: Dp = AppTheme.FadeDistance,
    content: @Composable (ScrollState) -> Unit
) {
    val density = LocalDensity.current
    
    val fadeAlpha by remember(scrollState, density) {
        derivedStateOf {
            val fadePx = with(density) { fadeDistance.toPx() }
            if (fadePx <= 0f) 1f else (scrollState.value / fadePx).coerceIn(0f, 1f)
        }
    }
    
    Box(Modifier.fillMaxSize()) {
        // Fade overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(fadeColor.copy(alpha = fadeAlpha))
        )
        
        content(scrollState)
    }
}

/**
 * Standard scrollable content layout for Home/Insights screens.
 * Includes the logo reveal spacer at the top.
 *
 * @param scrollState The scroll state
 * @param logoRevealHeight Height of spacer to show background (default: AppTheme.LogoRevealHeight)
 * @param content The cards/content to display
 */
@Composable
fun ScrollableScreenContent(
    scrollState: ScrollState,
    logoRevealHeight: Dp = AppTheme.LogoRevealHeight,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Space at top to reveal background image
        Spacer(Modifier.height(logoRevealHeight))
        
        content()
    }
}

/**
 * A hero card with gradient border, used for primary content on Home/Insights.
 * Features:
 * - Gradient border (purple to pink)
 * - Top gradient accent line
 * - Inner radial glow
 *
 * @param modifier Modifier for the card
 * @param content The card content
 */
@Composable
fun HeroCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val glowColor = remember { AppTheme.AccentPurple.copy(alpha = 0.20f) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                // Soft outer glow
                drawRoundRect(
                    color = glowColor,
                    topLeft = Offset(-6.dp.toPx(), -4.dp.toPx()),
                    size = Size(size.width + 12.dp.toPx(), size.height + 8.dp.toPx()),
                    cornerRadius = CornerRadius(28.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Fill,
                    alpha = 0.35f
                )
            },
        shape = AppTheme.HeroCardShape,
        colors = CardDefaults.cardColors(containerColor = AppTheme.HeroCardContainer),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content
            )
        }
    }
}

/**
 * A standard base card for secondary content on Home/Insights.
 * Simpler than HeroCard - just a semi-transparent card with subtle border.
 *
 * @param modifier Modifier for the card
 * @param content The card content
 */
@Composable
fun BaseCard(
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

/**
 * Info card that can be swiped away to dismiss. State persisted in SharedPreferences.
 */
@Composable
fun DismissableInfoCard(
    key: String,
    text: String,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("info_cards", android.content.Context.MODE_PRIVATE) }
    var dismissed by remember { mutableStateOf(prefs.getBoolean("dismissed_$key", false)) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    AnimatedVisibility(
        visible = !dismissed,
        exit = slideOutHorizontally { it } + fadeOut()
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = AppTheme.AccentPurple.copy(alpha = 0.1f)),
            shape = RoundedCornerShape(12.dp),
            modifier = modifier
                .offset(x = offsetX.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (kotlin.math.abs(offsetX) > 80f) {
                                dismissed = true
                                prefs.edit().putBoolean("dismissed_$key", true).apply()
                            } else {
                                offsetX = 0f
                            }
                        },
                        onHorizontalDrag = { _, dragAmount -> offsetX += dragAmount }
                    )
                }
        ) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Info, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp))
                Text(text, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
