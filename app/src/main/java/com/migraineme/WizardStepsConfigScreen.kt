package com.migraineme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorderAfterLongPress
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable

/**
 * Full-log wizard page customisation: show/hide and reorder pages.
 * Paint the Picture cannot be dragged (it prefills later pages); only Timing may
 * sit above it. Every drop is normalised and saved, so a page dropped above
 * Paint snaps back below it.
 */
@Composable
fun WizardStepsConfigScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(WizardStepConfig.load(context)) }

    fun updateConfig(newConfig: WizardSteps) {
        val normalized = newConfig.normalized()
        config = normalized
        WizardStepConfig.save(context, normalized)
    }

    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
            val toKey = to.key as? String ?: return@rememberReorderableLazyListState
            val order = config.order.toMutableList()
            val fromIndex = order.indexOf(fromKey)
            val toIndex = order.indexOf(toKey)
            if (fromIndex >= 0 && toIndex >= 0) {
                order.add(toIndex, order.removeAt(fromIndex))
                // Not normalised mid-drag, so Timing can pass Paint; the drop normalises.
                config = config.copy(order = order)
            }
        },
        canDragOver = { draggedOver, _ -> draggedOver.key in WizardStepConfig.DEFAULT_ORDER },
        onDragEnd = { _, _ -> updateConfig(config) }
    )

    LazyColumn(
        state = reorderState.listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .reorderable(reorderState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top bar: ← Back
        item(key = "wizard_steps_header") {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Back"), tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(t("Back"), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.weight(1f))
                // Same height as the wizard headers' 48dp icon buttons.
                Spacer(Modifier.height(48.dp))
            }
        }

        item(key = "wizard_steps_hero") {
            HeroCard {
                Text(
                    t("Customize log pages"),
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    t("Hide pages you don't need and choose their order. Review always comes last."),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }

        items(items = config.order, key = { it }) { key ->
            ReorderableItem(reorderState, key = key) { isDragging ->
                val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "elevation")
                val isPaint = key == WizardStepConfig.PAINT
                val alwaysShown = key in WizardStepConfig.ALWAYS_SHOWN
                WizardStepConfigItem(
                    title = WizardStepConfig.KEY_TO_TITLE[key] ?: key,
                    isVisible = config.isVisible(key),
                    note = when {
                        isPaint -> t("Stays in place")
                        alwaysShown -> t("Always shown")
                        else -> null
                    },
                    showHandle = !isPaint,
                    showSwitch = !alwaysShown,
                    elevation = elevation,
                    onToggleVisibility = { updateConfig(config.toggleVisibility(key)) },
                    modifier = if (isPaint) Modifier else Modifier.detectReorderAfterLongPress(reorderState)
                )
            }
        }

        item(key = "wizard_steps_reset") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { updateConfig(WizardSteps()) }) {
                    Text(t("Reset to default"), color = AppTheme.AccentPurple)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun WizardStepConfigItem(
    title: String,
    isVisible: Boolean,
    note: String?,
    showHandle: Boolean,
    showSwitch: Boolean,
    elevation: androidx.compose.ui.unit.Dp,
    onToggleVisibility: () -> Unit,
    modifier: Modifier = Modifier
) {
    BaseCard(
        modifier = modifier
            .shadow(elevation, shape = AppTheme.BaseCardShape)
            .alpha(if (isVisible) 1f else 0.6f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!showHandle) {
                Spacer(Modifier.size(24.dp))
            } else {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = t("Drag to reorder"),
                    tint = AppTheme.SubtleTextColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = t(title),
                    color = if (isVisible) AppTheme.TitleColor else AppTheme.TitleColor.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                )
                if (note != null) {
                    Text(
                        note,
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (showSwitch) Switch(
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
