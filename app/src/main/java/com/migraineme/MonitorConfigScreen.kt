package com.migraineme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.BubbleChart
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorderAfterLongPress
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable

@Composable
fun MonitorConfigScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(MonitorCardConfigStore.load(context)) }

    fun updateConfig(newConfig: MonitorCardConfig) {
        config = newConfig
        MonitorCardConfigStore.save(context, newConfig)
    }

    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            // Adjust for header items (spacer + back button + header = 3 items)
            val fromIndex = from.index - 3
            val toIndex = to.index - 3
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
            Spacer(Modifier.height(AppTheme.LogoRevealHeight))
        }

        // Back button
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            }
        }

        // Header in HeroCard
        item {
            HeroCard {
                Text(
                    "Customize your Monitor",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Long-press and drag to reorder cards",
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

                CardConfigItem(
                    cardId = cardId,
                    isVisible = config.isVisible(cardId),
                    isDragging = isDragging,
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
private fun CardConfigItem(
    cardId: String,
    isVisible: Boolean,
    isDragging: Boolean,
    elevation: androidx.compose.ui.unit.Dp,
    onToggleVisibility: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon = getCardIcon(cardId)
    val iconTint = getCardIconTint(cardId)
    val label = MonitorCardConfig.CARD_LABELS[cardId] ?: cardId

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
                contentDescription = "Drag to reorder",
                tint = AppTheme.SubtleTextColor,
                modifier = Modifier.size(24.dp)
            )

            Spacer(Modifier.width(12.dp))

            // Card icon
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isVisible) iconTint else iconTint.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp)
            )

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
                    checkedThumbColor = AppTheme.AccentPurple,
                    checkedTrackColor = AppTheme.AccentPurple.copy(alpha = 0.5f),
                    uncheckedThumbColor = AppTheme.SubtleTextColor,
                    uncheckedTrackColor = AppTheme.TrackColor
                )
            )
        }
    }
}

private fun getCardIcon(cardId: String): ImageVector {
    return when (cardId) {
        MonitorCardConfig.CARD_NUTRITION -> Icons.Outlined.Restaurant
        MonitorCardConfig.CARD_PHYSICAL -> Icons.Outlined.FitnessCenter
        MonitorCardConfig.CARD_SLEEP -> Icons.Outlined.Bedtime
        MonitorCardConfig.CARD_MENTAL -> Icons.Outlined.BubbleChart
        MonitorCardConfig.CARD_ENVIRONMENT -> Icons.Outlined.Cloud
        MonitorCardConfig.CARD_MENSTRUATION -> Icons.Outlined.FavoriteBorder
        else -> Icons.Outlined.Cloud
    }
}

private fun getCardIconTint(cardId: String): Color {
    return when (cardId) {
        MonitorCardConfig.CARD_NUTRITION -> Color(0xFFFFB74D)
        MonitorCardConfig.CARD_PHYSICAL -> Color(0xFF81C784)
        MonitorCardConfig.CARD_SLEEP -> Color(0xFF7986CB)
        MonitorCardConfig.CARD_MENTAL -> Color(0xFFBA68C8)
        MonitorCardConfig.CARD_ENVIRONMENT -> Color(0xFF4FC3F7)
        MonitorCardConfig.CARD_MENSTRUATION -> Color(0xFFE57373)
        else -> Color(0xFF4FC3F7)
    }
}


