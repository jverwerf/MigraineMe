package com.migraineme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import androidx.navigation.NavHostController
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorderAfterLongPress
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable

/**
 * Loads the section config for [page] and reloads it every time the page comes
 * back on screen (after the config screen pops), same as the Insights tab does.
 */
@Composable
fun rememberInsightsSectionConfig(page: String): MutableState<InsightsSectionConfig> {
    val context = LocalContext.current
    val state = remember(page) { mutableStateOf(InsightsSectionConfigStore.load(context, page)) }
    LaunchedEffect(page) {
        state.value = InsightsSectionConfigStore.load(context, page)
    }
    return state
}

/**
 * "Customize" entry row for the top of an Insights detail page — the same
 * HeroCard as the Insights tab's "Customize Insights" entry card.
 */
@Composable
fun InsightsCustomizeRow(navController: NavHostController, page: String) {
    HeroCard(
        modifier = Modifier.clickable { navController.navigate("${Routes.INSIGHTS_SECTION_CONFIG}/$page") }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = t("Configure"),
                tint = AppTheme.AccentPurple,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    t("Customize"),
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    t("Show, hide, and reorder sections"),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "→",
                color = AppTheme.AccentPurple,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

/**
 * Generic "Customize <page>" screen — show/hide and reorder the sections of one
 * Insights detail page. Clone of InsightsConfigScreen, parametrised by page.
 */
@Composable
fun InsightsSectionConfigScreen(
    page: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var config by remember(page) { mutableStateOf(InsightsSectionConfigStore.load(context, page)) }
    val labels = InsightsSections.SECTION_LABELS[page] ?: emptyMap()
    val pageTitle = InsightsSections.PAGE_TITLES[page] ?: page
    val rowIcon = InsightsSections.smallIcon(page)

    fun updateConfig(newConfig: InsightsSectionConfig) {
        config = newConfig
        InsightsSectionConfigStore.save(context, page, newConfig)
    }

    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            // Adjust for header items (spacer + header = 2 items)
            val fromIndex = from.index - 2
            val toIndex = to.index - 2
            if (fromIndex >= 0 && toIndex >= 0) {
                updateConfig(config.moveSection(fromIndex, toIndex))
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

        // Header in HeroCard — the page's own title
        item {
            HeroCard {
                Text(
                    t(pageTitle),
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

        // Section items
        itemsIndexed(
            items = config.order,
            key = { _, id -> id }
        ) { _, id ->
            ReorderableItem(reorderState, key = id) { isDragging ->
                val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "elevation")

                InsightsSectionConfigItem(
                    label = t(labels[id] ?: id),
                    iconRes = rowIcon,
                    isVisible = config.isVisible(id),
                    elevation = elevation,
                    onToggleVisibility = {
                        updateConfig(config.toggleVisibility(id))
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
private fun InsightsSectionConfigItem(
    label: String,
    iconRes: Int,
    isVisible: Boolean,
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
            // Drag handle
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = t("Drag to reorder"),
                tint = AppTheme.SubtleTextColor,
                modifier = Modifier.size(24.dp)
            )

            Spacer(Modifier.width(12.dp))

            // The page's Brainy blob
            Box(Modifier.size(28.dp).alpha(if (isVisible) 1f else 0.5f)) {
                InlineBlobIcon(iconRes)
            }

            Spacer(Modifier.width(12.dp))

            // Section name
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
