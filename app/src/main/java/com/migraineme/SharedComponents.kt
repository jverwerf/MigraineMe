package com.migraineme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                        horizontalAlignment = Alignment.CenterHorizontally,
                        content = content
                    )
                }
            }
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
