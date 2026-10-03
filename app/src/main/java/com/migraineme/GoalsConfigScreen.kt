package com.migraineme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Pick the goals for the Monitor "Goals" card. Same build as
 * PhysicalConfigScreen; every change is saved to GoalsCardConfigStore at once.
 */
@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GoalsConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val goals by PractitionerGoalsStore.goals.collectAsState()
    LaunchedEffect(Unit) { PractitionerGoalsStore.refresh(context) }

    val ordered = orderedGoals(goals)
    // Starts from what the card shows now (saved picks, or the first three),
    // kept in slot order. Re-read if the list of goals itself changes.
    var selected by remember(ordered.map { it.id }) {
        mutableStateOf(GoalsCardConfigStore.displayGoals(context, goals).map { it.id })
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
            HeroCard {
                Text(t("Customize Goals"), color = AppTheme.TitleColor, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(4.dp))
                Text(t("Choose which goals to display on the Monitor screen."), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }

            if (ordered.isEmpty()) {
                BaseCard {
                    Text(t("No goals yet"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                BaseCard {
                    Text(t("Display goals (%s)", selected.size), color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(4.dp))
                    Text(t("Choose the goals to show on the Monitor card."), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))
                        for (goal in ordered) {
                            val isSelected = goal.id in selected
                            val slotIndex = selected.indexOf(goal.id)
                            val slotColor = if (slotIndex >= 0) slotColors[slotIndex % slotColors.size] else AppTheme.AccentPurple

                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selected = if (isSelected) {
                                        selected - goal.id
                                    } else {
                                        selected + goal.id
                                    }
                                    GoalsCardConfigStore.save(context, selected)
                                },
                                enabled = true,
                                label = { Text(t(goal.title), style = MaterialTheme.typography.labelSmall) },
                                leadingIcon = if (isSelected) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                } else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = slotColor.copy(alpha = 0.3f),
                                    selectedLabelColor = AppTheme.TitleColor,
                                    selectedLeadingIconColor = slotColor,
                                    containerColor = AppTheme.BaseCardContainer,
                                    labelColor = AppTheme.BodyTextColor
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                                    selectedBorderColor = slotColor,
                                    enabled = true,
                                    selected = isSelected
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
