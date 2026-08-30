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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Swallows every gesture before the children below can see it, without
 * changing the layout. Used to make a placeholder's underlying content inert
 * while entitlement is still resolving — the content is there only to hold the
 * card's size, and must not be tappable through the placeholder.
 */
private fun Modifier.consumeAllPointerInput(): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/**
 * The neutral middle state: neither the premium content nor a lock.
 *
 * [content] is kept in the layout at zero opacity so the card holds its real
 * size and nothing jumps when entitlement lands, and is covered by a quiet
 * placeholder. Deliberately carries no lock, price or "Upgrade" — the user may
 * well be a subscriber and we do not yet know.
 */
@Composable
private fun PremiumLoadingPlaceholder(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .alpha(0f)
                .consumeAllPointerInput()
                .clearAndSetSemantics { }
        ) {
            content()
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(1.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(AppTheme.BaseCardContainer),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = AppTheme.AccentPurple,
                strokeWidth = 2.dp,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Wraps premium content with a blur overlay + CTA when the user is on the free tier.
 *
 * Three states, never two — see [PremiumAccess]:
 *  - ENTITLED (trial or paid): content renders normally.
 *  - NOT_ENTITLED (free tier): content is blurred under an upgrade prompt.
 *  - LOADING: neither. Entitlement is still resolving, so showing content would
 *    hand every gated surface to free users for the length of the window, and
 *    showing the lock would wall off people who are paying. A quiet placeholder
 *    holds the card's shape until the answer arrives.
 *
 * Usage:
 *   PremiumGate(
 *       message = t("Unlock all treatment insights"),
 *       onUpgrade = { navController.navigate("paywall") }
 *   ) {
 *       SpiderChart(data)  // your premium content
 *   }
 */
@Composable
fun PremiumGate(
    modifier: Modifier = Modifier,
    message: String = t("Unlock with Premium"),
    subtitle: String? = null,
    blurRadius: Dp = 10.dp,
    showTeaser: Boolean = true,
    /** Single-row upsell (lock + [message], no subtitle, no Upgrade button) for
     *  small controls such as a button, where the full card upsell would tower
     *  over the thing it covers. The whole area taps through to [onUpgrade]. */
    compact: Boolean = false,
    onUpgrade: () -> Unit,
    content: @Composable () -> Unit
) {
    val premiumState by PremiumManager.state.collectAsState()

    when (premiumState.access) {
        PremiumAccess.ENTITLED -> {
            content()
            return
        }
        PremiumAccess.LOADING -> {
            PremiumLoadingPlaceholder(modifier = modifier, content = content)
            return
        }
        PremiumAccess.NOT_ENTITLED -> Unit // falls through to the blurred upsell
    }

    // FREE tier: render content blurred, overlay inside card bounds.
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Blurred content — defines the card size/shape
        if (showTeaser) {
            Box(modifier = Modifier.blur(blurRadius)) {
                content()
            }
        }

        // Scrim clipped to card shape, sitting inside the card bounds
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(1.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onUpgrade)
        )

        if (compact) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.clickable(onClick = onUpgrade).padding(8.dp)
            ) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = t("Locked"),
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = message,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                )
            }
            return
        }

        // Kept OUT of the scrim so it is measured, not clipped: a card shorter
        // than the upsell (Treatments is three rows) used to cut the Upgrade
        // button down to a few pixels of glyph.
        Box(
            modifier = Modifier.clickable(onClick = onUpgrade),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = t("Locked"),
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
                    Text(t("Upgrade"), fontWeight = FontWeight.SemiBold)
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

    if (isPremiumAction && premiumState.access == PremiumAccess.LOADING) {
        // Entitlement unresolved: the button keeps its place and its label but
        // does nothing. No "(Premium)" suffix and no lock — that is a claim
        // about the user we are not yet entitled to make.
        Button(
            onClick = {},
            enabled = false,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = AppTheme.AccentPurple,
                contentColor = Color.White,
                disabledContainerColor = AppTheme.AccentPurple.copy(alpha = 0.35f),
                disabledContentColor = Color.White.copy(alpha = 0.6f)
            ),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text(t(label), fontWeight = FontWeight.SemiBold)
        }
    } else if (!isPremiumAction || premiumState.isPremium) {
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
            Text(t(label), fontWeight = FontWeight.SemiBold)
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
                t("%s (Premium)", label),
                color = AppTheme.AccentPurple,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Whole-screen premium gate for a nav destination.
 *
 * Same three states as [PremiumGate], expressed for a route:
 *  - ENTITLED: the screen.
 *  - NOT_ENTITLED: bounce to the paywall via [onDenied].
 *  - LOADING: a spinner. Not the screen — that is the free ride the inline gate
 *    used to give away — and not the bounce, which would throw a paying user
 *    out of a screen they just opened and land them on a price list.
 *
 * The route stays mounted while loading, so when entitlement resolves the user
 * is already where they asked to be.
 */
@Composable
fun PremiumRoute(
    onDenied: () -> Unit,
    content: @Composable () -> Unit
) {
    val premiumState by PremiumManager.state.collectAsState()

    when (premiumState.access) {
        PremiumAccess.ENTITLED -> content()
        PremiumAccess.NOT_ENTITLED -> LaunchedEffect(Unit) { onDenied() }
        PremiumAccess.LOADING -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = AppTheme.AccentPurple,
                strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/**
 * The "History →" affordance that sits in the header of every Monitor card.
 *
 * Was copy-pasted across seven screens, each reading the non-observable
 * `PremiumManager.isPremium` snapshot — so the padlock latched at first
 * composition, when nothing had loaded yet, and never went away for a
 * subscriber until the screen was rebuilt. One observable copy, three states.
 */
@Composable
fun PremiumHistoryLabel(
    access: PremiumAccess,
    textStyle: TextStyle = MaterialTheme.typography.bodySmall,
    iconSize: Dp = 14.dp
) {
    when (access) {
        PremiumAccess.ENTITLED ->
            Text(t("History →"), color = AppTheme.AccentPurple, style = textStyle)

        // Same word, no lock and no arrow: it says nothing about whether this
        // user may have it, because we do not know yet.
        PremiumAccess.LOADING ->
            Text(t("History"), color = AppTheme.SubtleTextColor, style = textStyle)

        PremiumAccess.NOT_ENTITLED -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = t("Premium"),
                tint = AppTheme.AccentPurple,
                modifier = Modifier.size(iconSize)
            )
            Text(t("History"), color = AppTheme.AccentPurple, style = textStyle)
        }
    }
}

/**
 * Click behaviour for a gated row: open it, sell it, or — while entitlement is
 * unresolved — do nothing at all. A tap that lands on the paywall because the
 * answer had not arrived yet is the fail-closed half of the same bug.
 */
@Composable
fun premiumGatedClickable(
    access: PremiumAccess,
    onOpen: () -> Unit,
    onUpgrade: () -> Unit
): Modifier = when (access) {
    PremiumAccess.ENTITLED -> Modifier.clickable(onClick = onOpen)
    PremiumAccess.NOT_ENTITLED -> Modifier.clickable(onClick = onUpgrade)
    PremiumAccess.LOADING -> Modifier
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
        days <= 1 -> t("Trial ends today \u2014 subscribe to keep your insights")
        days <= 3 -> t("%s days left \u2014 subscribe to keep your insights", days)
        days <= 7 -> t("%s days of Premium remaining \u2014 subscribe to keep access", days)
        else -> t("Premium trial \u2014 %s days left", days)
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
                    t("Subscribe"),
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
            t("You have %1\$s migraines, %2\$s triggers, and %3\$s treatments logged.", migraineCount, triggerCount, treatmentCount),
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
        Text(
            t("Unlock Premium to see what your data reveals."),
            color = AppTheme.AccentPurple,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center
        )
    }
}
