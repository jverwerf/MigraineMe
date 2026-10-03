package com.migraineme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

/**
 * One goal (practitioner-set or the client's own): the ask and her note, this week, the last four
 * weeks day by day, the log buttons, the exercise film where there is one,
 * the reminder switch, and Edit / Delete for own goals. Reads the shared PractitionerGoalsStore.
 */
@Composable
fun MonitorPractitionerGoalScreen(navController: NavController, goalId: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val goals by PractitionerGoalsStore.goals.collectAsState()
    val progress by PractitionerGoalsStore.progress.collectAsState()
    val goal = goals.firstOrNull { it.id == goalId }
    val rows = progress[goalId]

    LaunchedEffect(Unit) { PractitionerGoalsStore.refresh(ctx) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteFailed by remember { mutableStateOf(false) }

    // Heart rate goals are measured, not logged: say where the number comes
    // from, or what to connect. Health Connect counts once the app may read
    // heart rate; the wearable token stores are what the server reads.
    var hrSourceReady by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(goal?.isHr) {
        if (goal?.isHr != true) return@LaunchedEffect
        hrSourceReady = withContext(Dispatchers.IO) {
            val hc = runCatching {
                HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE &&
                    HealthPermission.getReadPermission(HeartRateRecord::class) in
                    HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions()
            }.getOrDefault(false)
            hc || runCatching { GarminTokenStore(ctx).load() != null }.getOrDefault(false) ||
                runCatching { OuraTokenStore(ctx).load() != null }.getOrDefault(false) ||
                runCatching { PolarTokenStore(ctx).load() != null }.getOrDefault(false)
        }
    }

    val scrollState = rememberScrollState()
    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp, spacing = 12.dp) {
            if (goal == null) {
                BaseCard {
                    Text(t("This goal is no longer on your plan."),
                        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
                }
                return@ScrollableScreenContent
            }

            HeroCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t(goal.title), color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f))
                    if (goal.isPaused) GoalPausedChip()
                }
                goalSetByText(goal)?.let {
                    Text(it, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                }
                Text(goalAskText(goal), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
                val note = if (goal.isOwn) "" else goal.note?.trim().orEmpty()
                if (note.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(t("Note from your practitioner"), color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall)
                    Text(note, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Metric goals: today's reading, and where it comes from
            if (goal.isMetric) {
                BaseCard {
                    Text(t("Today"), color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text(goalTodayReadingText(goal, rows).orEmpty(), color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                    Text(
                        if (goalHasNoMetricData(goal, rows)) t("No data for this metric yet. Connect a source in Data settings.")
                        else t("Filled in from your own data."),
                        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // This week
            BaseCard {
                Text(t("This week"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(goalProgressText(goal, rows), color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f))
                    GoalLogButton(goal)
                }
                GoalWeekStrip(rows)
                if (goal.isHr) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when (hrSourceReady) {
                            true -> t("Measured from your heart rate in Health Connect")
                            false -> t("Connect a watch or Health Connect to measure this.")
                            null -> ""
                        },
                        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Last four weeks, one Mon–Sun row per week, oldest at the top
            BaseCard {
                Text(t("Last 4 weeks"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                val fmt = remember { DateTimeFormatter.ofPattern("d MMM", appLocale()) }
                val thisWeek = PractitionerGoalsStore.weekDays()
                for (back in 3 downTo 0) {
                    val week = thisWeek.map { it.minusWeeks(back.toLong()) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(week.first().format(fmt), color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(52.dp))
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                            for (day in week) {
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    GoalDayDot(day, PractitionerGoalsStore.rowFor(rows, day), size = 14)
                                }
                            }
                        }
                    }
                }
            }

            // The exercise film, when the goal names one in the catalogue
            val routine = ExerciseCatalogue.byId(goal.exercise_id)
            if (goal.isExercise && routine != null) {
                BaseCard(modifier = Modifier.clickable { navController.navigate(Routes.exercisePlayer(routine.id)) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t("Watch the exercise"), color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                            Text(t(routine.title) + " · " + t("%s min", routine.minutes),
                                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            // Reminders: the server sends them; this only mutes or unmutes
            if (goal.reminder_times.isNotEmpty()) {
                var saving by remember { mutableStateOf(false) }
                BaseCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t("Reminders"), color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                            Text(goal.reminderLabels.joinToString(", "), color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = goal.reminders_enabled,
                            enabled = !saving,
                            onCheckedChange = { on ->
                                saving = true
                                scope.launch { PractitionerGoalsStore.setReminders(ctx, goal.id, on); saving = false }
                            },
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

            // Own goals only: change it, or delete it. Practitioner goals are hers to change.
            if (goal.isOwn) {
                BaseCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        GoalPillButton(label = t("Edit"), enabled = !deleting) {
                            navController.navigate(Routes.goalEditor(goal.id))
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { confirmDelete = true }, enabled = !deleting) {
                            Text(t("Delete goal"), color = AppTheme.AccentPink)
                        }
                    }
                    if (deleteFailed) {
                        Text(t("Could not delete this goal. Try again."),
                            color = AppTheme.AccentPink, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleting = true
                    deleteFailed = false
                    scope.launch {
                        val ok = PractitionerGoalsStore.delete(ctx, goalId)
                        deleting = false
                        if (ok) navController.popBackStack() else deleteFailed = true
                    }
                }) { Text(t("Delete"), color = AppTheme.AccentPink) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(t("Cancel"), color = AppTheme.AccentPurple)
                }
            },
            title = { Text(t("Delete this goal?"), color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text(t("Its progress goes with it."), color = Color.White.copy(alpha = 0.7f)) },
            containerColor = Color(0xFF1A0029),
            shape = RoundedCornerShape(16.dp)
        )
    }
}
