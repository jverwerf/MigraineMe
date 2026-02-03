package com.migraineme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shared theme constants for Home, Insights, and other screens with the purple sky background.
 * Centralizes all the duplicated color/shape definitions.
 */
object AppTheme {
    
    // Card shapes
    val BaseCardShape = RoundedCornerShape(18.dp)
    val HeroCardShape = RoundedCornerShape(24.dp)
    
    // Card colors
    val BaseCardContainer = Color(0xFF2A0C3C).copy(alpha = 0.65f)
    val HeroCardContainer = Color(0xFF2A0C3C).copy(alpha = 0.78f)
    
    // Card border
    val BaseCardBorder = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    
    // Text colors
    val TitleColor = Color(0xFFDCCEFF)
    val BodyTextColor = Color.White.copy(alpha = 0.82f)
    val SubtleTextColor = Color.White.copy(alpha = 0.62f)
    
    // Accent colors
    val AccentPurple = Color(0xFFB97BFF)
    val AccentPink = Color(0xFFFF7BB0)
    
    // Track/progress colors
    val TrackColor = Color.White.copy(alpha = 0.12f)
    
    // Background fade
    val FadeColor = Color(0xFF2A003D)
    val FadeDistance = 220.dp
    
    // Logo reveal spacing (space at top of scrollable content to show background)
    val LogoRevealHeight = 220.dp
}
