// FILE: app/src/main/java/com/migraineme/PremiumGate.kt
package com.migraineme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Wraps premium content with a blur overlay + CTA when the user is on the free tier.
 *
 * During trial or with an active subscription, content renders normally.
 * After trial expiry (FREE tier), content is blurred with an upgrade prompt.
 *
 * Usage:
 *   PremiumGate(
 *       message = "Unlock all treatment insights",
 *       onUpgrade = { navController.navigate("paywall") }
 *   ) {
 *       SpiderChart(data)  // your premium content
 *   }
 */
@Composable
fun PremiumGate(
    modifier: Modifier = Modifier,
    message: String = "Unlock with Premium",
    subtitle: String? = null,
    blurRadius: Dp = 10.dp,
    showTeaser: Boolean = true,
    onUpgrade: () -> Unit,
    content: @Composable () -> Unit
) {
    val premiumState by PremiumManager.state.collectAsState()

    // While loading, show content (don't flash paywall on app start)
    if (premiumState.isLoading || premiumState.isPremium) {
        content()
        return
    }

    // FREE tier: render content blurred, overlay inside card bounds
    Box(modifier = modifier) {
        // Blurred content — defines the card size/shape
        if (showTeaser) {
            Box(modifier = Modifier.blur(blurRadius)) {
                content()
            }
        }

        // Overlay clipped to card shape, sitting inside the card bounds
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(1.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onUpgrade),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = "Locked",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(32.dp)
                )

                Text(
                    text = message,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    textAlign = TextAlign.Center
                )

                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(4.dp))

                Button(
                    onClick = onUpgrade,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppTheme.AccentPurple,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
                ) {
                    Icon(
                        Icons.Outlined.Star,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Upgrade", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Simpler gate that just hides content entirely (no blur teaser).
 * Use for features where showing a blurred preview doesn't make sense
 * (e.g. PDF export button, risk weight customisation).
 */
@Composable
fun PremiumFeatureButton(
    label: String,
    modifier: Modifier = Modifier,
    isPremiumAction: Boolean = true,
    onUpgrade: () -> Unit,
    onAction: () -> Unit
) {
    val premiumState by PremiumManager.state.collectAsState()

    if (!isPremiumAction || premiumState.isPremium) {
        // Normal button
        Button(
            onClick = onAction,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = AppTheme.AccentPurple,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    } else {
        // Locked button → opens paywall
        OutlinedButton(
            onClick = onUpgrade,
            modifier = modifier,
            border = ButtonDefaults.outlinedButtonBorder.copy(
                brush = Brush.horizontalGradient(
                    listOf(AppTheme.AccentPurple, Color(0xFFFF7BB0))
                )
            ),
            shape = RoundedCornerShape(24.dp)
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = AppTheme.AccentPurple,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "$label (Premium)",
                color = AppTheme.AccentPurple,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Trial banner shown at the top of premium screens during the trial period.
 * Becomes more prominent in the last 7 days.
 */
@Composable
fun TrialBanner(
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier
) {
    val premiumState by PremiumManager.state.collectAsState()

    if (!premiumState.showTrialBanner) return

    val isUrgent = premiumState.isTrialUrgent
    val days = premiumState.trialDaysRemaining

    val bgColor = if (isUrgent) {
        Color(0xFFFF8A65).copy(alpha = 0.5f)
    } else {
        AppTheme.AccentPurple.copy(alpha = 0.5f)
    }

    val textColor = if (isUrgent) Color(0xFFFF8A65) else AppTheme.AccentPurple

    val text = when {
        days <= 1 -> "Trial ends today \u2014 subscribe to keep your insights"
        days <= 3 -> "$days days left \u2014 subscribe to keep your insights"
        days <= 7 -> "$days days of Premium remaining \u2014 subscribe to keep access"
        else -> "Premium trial \u2014 $days days left"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable(onClick = onUpgrade)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                Icons.Outlined.Star,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                color = textColor,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (isUrgent) FontWeight.SemiBold else FontWeight.Normal
                )
            )
        }

        if (isUrgent) {
            TextButton(
                onClick = onUpgrade,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(
                    "Subscribe",
                    color = textColor,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

/**
 * Recovery prompt shown to free users in the Insights screen.
 * Shows growing data counts to increase urgency over time.
 *
 * Only shown once per week (tracked via SharedPreferences).
 */
@Composable
fun PremiumRecoveryPrompt(
    migraineCount: Int,
    triggerCount: Int,
    treatmentCount: Int,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier
) {
    val premiumState by PremiumManager.state.collectAsState()

    // Only show for FREE users who have meaningful data
    if (premiumState.isPremium || premiumState.isLoading) return
    if (migraineCount < 3) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onUpgrade)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Outlined.Star,
            contentDescription = null,
            tint = AppTheme.AccentPurple,
            modifier = Modifier.size(24.dp)
        )
        Text(
            "You have $migraineCount migraines, $triggerCount triggers, and $treatmentCount treatments logged.",
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
        Text(
            "Unlock Premium to see what your data reveals.",
            color = AppTheme.AccentPurple,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center
        )
    }
}
