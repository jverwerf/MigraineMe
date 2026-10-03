package com.migraineme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

/**
 * Monitor > Goals: the card picker, the + to add a goal, and every goal
 * (active and paused) as a row that opens its own detail screen.
 * Reads the shared PractitionerGoalsStore. Static, no motion.
 */
@Composable
fun MonitorGoalsScreen(navController: NavController) {
    val ctx = LocalContext.current
    val goals by PractitionerGoalsStore.goals.collectAsState()
    val progress by PractitionerGoalsStore.progress.collectAsState()
    LaunchedEffect(Unit) { PractitionerGoalsStore.refresh(ctx) }

    val scrollState = rememberScrollState()
    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
            HeroCard(modifier = Modifier.clickable { navController.navigate(Routes.GOALS_CONFIG) }) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, contentDescription = t("Configure"), tint = AppTheme.AccentPurple, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(t("Customize Monitor Card"), color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Text(t("Choose 3 goals for the Goals card on Monitor"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
                }
            }

            ExerciseGroupHeading(t("Your goals"), onPlus = { navController.navigate(Routes.goalEditor(null)) })

            if (goals.isEmpty()) {
                BaseCard {
                    Text(t("No goals yet. Tap + to set one, or ask your practitioner."),
                        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                for (goal in orderedGoals(goals)) {
                    val rows = progress[goal.id]
                    BaseCard(modifier = Modifier.clickable { navController.navigate(Routes.practitionerGoal(goal.id)) }) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t(goal.title), color = AppTheme.TitleColor,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(goalProgressText(goal, rows), color = AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.bodySmall)
                                goalTodayReadingText(goal, rows)?.let {
                                    Text(it, color = AppTheme.SubtleTextColor,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            if (goal.isPaused) GoalPausedChip() else GoalLogButton(goal)
                            Spacer(Modifier.width(8.dp))
                            Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        GoalWeekStrip(rows)
                    }
                }
            }
        }
    }
}
