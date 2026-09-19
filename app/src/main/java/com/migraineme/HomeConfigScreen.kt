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
 * "Customize Home" — show/hide and reorder the Home tab cards.
 * Clone of InsightsConfigScreen. The trial and recalibration banners (top), the
 * medical disclaimer and the Customize row (bottom) are pinned and never listed
 * here. Quick log and the risk gauge can be moved but not hidden.
 */
@Composable
fun HomeConfigScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(HomeCardConfigStore.load(context)) }

    fun updateConfig(newConfig: HomeCardConfig) {
        config = newConfig
        HomeCardConfigStore.save(context, newConfig)
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
                    t("Customize Home"),
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

                HomeCardConfigItem(
                    cardId = cardId,
                    isVisible = config.isVisible(cardId),
                    canHide = config.canHide(cardId),
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
private fun HomeCardConfigItem(
    cardId: String,
    isVisible: Boolean,
    canHide: Boolean,
    elevation: androidx.compose.ui.unit.Dp,
    onToggleVisibility: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = t(HomeCardConfig.CARD_LABELS[cardId] ?: cardId)

    BaseCard(
        modifier = modifier
            .shadow(elevation, shape = AppTheme.BaseCardShape)
    ) {
        // Hidden rows dim their CONTENT, not the card: on the lattice a see-through card shows the pattern
        Row(
            modifier = Modifier.fillMaxWidth().alpha(if (isVisible) 1f else 0.6f),
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

            // Card icon — a Brainy blob, same row style as Customize Insights
            Box(Modifier.size(28.dp).alpha(if (isVisible) 1f else 0.5f)) {
                InlineBlobIcon(homeCardSmallIcon(cardId))
            }

            Spacer(Modifier.width(12.dp))

            // Card name
            Text(
                text = label,
                color = if (isVisible) AppTheme.TitleColor else AppTheme.TitleColor.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f)
            )

            // Visibility toggle. Quick log and the risk gauge are always on:
            // the switch stays, locked, so the row still reads as "shown".
            Switch(
                checked = isVisible,
                onCheckedChange = { onToggleVisibility() },
                enabled = canHide,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AppTheme.AccentPurple,
                    checkedBorderColor = AppTheme.AccentPurple,
                    uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                    uncheckedTrackColor = AppTheme.TrackColor,
                    uncheckedBorderColor = AppTheme.SubtleTextColor.copy(alpha = 0.4f),
                    disabledCheckedThumbColor = Color.White.copy(alpha = 0.6f),
                    disabledCheckedTrackColor = AppTheme.AccentPurple.copy(alpha = 0.4f),
                    disabledCheckedBorderColor = Color.Transparent
                )
            )
        }
    }
}

private fun homeCardSmallIcon(cardId: String): Int = when (cardId) {
    HomeCardConfig.CARD_QUICKLOG -> R.drawable.brainy_migraines_small
    HomeCardConfig.CARD_RISK -> R.drawable.brainy_risk_small
    HomeCardConfig.CARD_LOCATION -> R.drawable.brainy_environment_small
    HomeCardConfig.CARD_ASK -> R.drawable.brainy_ask_small
    HomeCardConfig.CARD_WELLDONE -> R.drawable.brainy_gardener_small
    HomeCardConfig.CARD_INSIGHT -> R.drawable.brainy_recs_small
    HomeCardConfig.CARD_CONTRIBUTORS -> R.drawable.brainy_trigger_small
    HomeCardConfig.CARD_EXERCISES -> R.drawable.brainy_physical_small
    else -> R.drawable.brainy_risk_small
}
