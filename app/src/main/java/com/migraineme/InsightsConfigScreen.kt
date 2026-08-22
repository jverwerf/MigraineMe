package com.migraineme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorderAfterLongPress
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable

/**
 * "Customize Insights" — show/hide and reorder the Insights tab cards.
 * Clone of MonitorConfigScreen. Full Report (top) and the medical disclaimer
 * (bottom) are pinned and never listed here.
 */
@Composable
fun InsightsConfigScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(InsightsCardConfigStore.load(context)) }

    fun updateConfig(newConfig: InsightsCardConfig) {
        config = newConfig
        InsightsCardConfigStore.save(context, newConfig)
    }

    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            // Adjust for header items (spacer + header = 2 items)
            val fromIndex = from.index - 2
            val toIndex = to.index - 2
            if (fromIndex >= 0 && toIndex >= 0) {
                updateConfig(config.moveCard(fromIndex, toIndex))
            }
        }
    )

    LazyColumn(
        state = reorderState.listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .reorderable(reorderState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top spacer for logo reveal area
        item {
        }

        // Header in HeroCard
        item {
            HeroCard {
                Text(
                    t("Customize your Insights"),
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    t("Long-press and drag to reorder cards"),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Card items
        itemsIndexed(
            items = config.cardOrder,
            key = { _, cardId -> cardId }
        ) { _, cardId ->
            ReorderableItem(reorderState, key = cardId) { isDragging ->
                val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "elevation")

                InsightsCardConfigItem(
                    cardId = cardId,
                    isVisible = config.isVisible(cardId),
                    elevation = elevation,
                    onToggleVisibility = {
                        updateConfig(config.toggleVisibility(cardId))
                    },
                    modifier = Modifier.detectReorderAfterLongPress(reorderState)
                )
            }
        }

        // Bottom spacer
        item {
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InsightsCardConfigItem(
    cardId: String,
    isVisible: Boolean,
    elevation: androidx.compose.ui.unit.Dp,
    onToggleVisibility: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = t(InsightsCardConfig.CARD_LABELS[cardId] ?: cardId)

    BaseCard(
        modifier = modifier
            .shadow(elevation, shape = AppTheme.BaseCardShape)
            .alpha(if (isVisible) 1f else 0.6f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Drag handle
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = t("Drag to reorder"),
                tint = AppTheme.SubtleTextColor,
                modifier = Modifier.size(24.dp)
            )

            Spacer(Modifier.width(12.dp))

            // Card icon — the same Brainy blob the card shows on the tab
            Box(Modifier.size(28.dp).alpha(if (isVisible) 1f else 0.5f)) {
                InlineBlobIcon(insightsCardSmallIcon(cardId))
            }

            Spacer(Modifier.width(12.dp))

            // Card name
            Text(
                text = label,
                color = if (isVisible) AppTheme.TitleColor else AppTheme.TitleColor.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f)
            )

            // Visibility toggle
            Switch(
                checked = isVisible,
                onCheckedChange = { onToggleVisibility() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AppTheme.AccentPurple,
                    checkedBorderColor = AppTheme.AccentPurple,
                    uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                    uncheckedTrackColor = AppTheme.TrackColor,
                    uncheckedBorderColor = AppTheme.SubtleTextColor.copy(alpha = 0.4f)
                )
            )
        }
    }
}

private fun insightsCardSmallIcon(cardId: String): Int = when (cardId) {
    InsightsCardConfig.CARD_RECOMMENDATIONS -> R.drawable.brainy_recs_small
    InsightsCardConfig.CARD_ACCURACY -> R.drawable.brainy_archer_small
    InsightsCardConfig.CARD_PATTERNS -> R.drawable.brainy_detective_small
    InsightsCardConfig.CARD_TREATMENTS -> R.drawable.brainy_shield_small
    InsightsCardConfig.CARD_HELPING -> R.drawable.brainy_gardener_small
    InsightsCardConfig.CARD_CHANGES -> R.drawable.brainy_risk_small
    InsightsCardConfig.CARD_CONTEXT -> R.drawable.brainy_runner_small
    InsightsCardConfig.CARD_IMPACT -> R.drawable.brainy_recover_small
    else -> R.drawable.brainy_detective_small
}
