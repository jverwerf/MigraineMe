package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val CATEGORY_COLORS = mapOf(
    "Sleep" to Color(0xFF7E57C2),
    "Weather" to Color(0xFF4FC3F7),
    "Physical" to Color(0xFF81C784),
    "Mental" to Color(0xFFBA68C8),
    "Nutrition" to Color(0xFFFFB74D)
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RiskConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val pool = remember { buildFavoritesPool(context) }
    val initialConfig = remember { RiskCardConfigStore.load(context) }
    // Only keep selections that are still in the pool
    var selected by remember { mutableStateOf(initialConfig.favOfFavs.filter { key -> pool.any { it.key == key } }) }

    fun save() {
        RiskCardConfigStore.save(context, RiskCardConfigData(favOfFavs = selected))
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                IconButton(onClick = { save(); onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
            }

            BaseCard {
                Text("Choose 3 Favorites", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(4.dp))
                Text(
                    "Pick 3 metrics to show beneath the risk score on your Monitor card. These are drawn from the favorites you configured in each category.",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Group by category
            val grouped = pool.groupBy { it.category }
            grouped.forEach { (category, entries) ->
                val color = CATEGORY_COLORS[category] ?: Color(0xFF999999)
                BaseCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).background(color, CircleShape))
                        Text(category, color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    }
                    Spacer(Modifier.height(10.dp))

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        entries.forEach { entry ->
                            val sel = entry.key in selected
                            FilterChip(
                                selected = sel,
                                onClick = {
                                    selected = if (sel) {
                                        selected - entry.key
                                    } else if (selected.size < 3) {
                                        selected + entry.key
                                    } else selected
                                    save()
                                },
                                label = { Text(entry.label) },
                                leadingIcon = if (sel) {{ Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }} else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = color.copy(alpha = 0.2f),
                                    selectedLabelColor = color,
                                    selectedLeadingIconColor = color,
                                    containerColor = Color.Transparent,
                                    labelColor = AppTheme.SubtleTextColor
                                )
                            )
                        }
                    }
                }
            }

            if (pool.isEmpty()) {
                BaseCard {
                    Text("No favorites configured yet. Set up favorites in each category's detail screen first.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
