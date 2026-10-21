package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Monitor > Goals: this week's review and its push switch, the card picker,
 * the + to add a goal, and every goal (active and paused) as a row that opens
 * its own detail screen. Goal ideas live in the recalibration review.
 *
 * Premium (Goals v2): paying users see everything. Free users with
 * practitioner-set goals see and log those goals; the review is
 * blurred. Free users with none get the whole page blurred.
 * Reads the shared PractitionerGoalsStore. Static, no motion.
 */
@Composable
fun MonitorGoalsScreen(navController: NavController) {
    val ctx = LocalContext.current
    val goals by PractitionerGoalsStore.goals.collectAsState()
    val goalsOn by PractitionerGoalsStore.goalsOn.collectAsState()
    val premium by PremiumManager.state.collectAsState()
    LaunchedEffect(Unit) {
        PractitionerGoalsStore.refresh(ctx)
        PractitionerGoalsStore.refreshReview(ctx)
    }
    val free = premium.access == PremiumAccess.NOT_ENTITLED
    val practitionerSet = goals.filter { !it.isOwn }
    val onUpgrade: () -> Unit = { navController.navigate(Routes.PAYWALL) }

    val scrollState = rememberScrollState()
    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
            when {
                !goalsOn -> BaseCard {
                    Text(t("Goals are switched off in Data settings."),
                        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
                free && practitionerSet.isEmpty() -> PremiumGate(
                    message = t("Unlock Goals"),
                    subtitle = t("Weekly AI goal suggestions and reviews from your own data"),
                    onUpgrade = onUpgrade
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        GoalsReviewRegion(teaser = true)
                        GoalsListRegion(navController, goals)
                    }
                }
                free -> {
                    PremiumGate(
                        message = t("Unlock Goals"),
                        subtitle = t("Weekly AI goal suggestions and reviews from your own data"),
                        onUpgrade = onUpgrade
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            GoalsReviewRegion(teaser = true)
                        }
                    }
                    // Practitioner-set goals stay visible and loggable on the free plan.
                    GoalsListRegion(navController, practitionerSet, onAdd = onUpgrade)
                }
                else -> {
                    GoalsReviewRegion(teaser = false)
                    GoalsListRegion(navController, goals)
                }
            }
        }
    }
}

/** The card picker, "Your goals" with its +, every goal, and the practitioner's notes. */
@Composable
private fun ColumnScope.GoalsListRegion(
    navController: NavController,
    goals: List<GoalRow>,
    onAdd: () -> Unit = { navController.navigate(Routes.goalEditor(null)) }
) {
    val progress by PractitionerGoalsStore.progress.collectAsState()
    val hrNotFed = hrGoalNotFed()

    HeroCard(modifier = Modifier.clickable { navController.navigate(Routes.GOALS_CONFIG) }) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Tune, contentDescription = t("Configure"), tint = AppTheme.AccentPurple, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(t("Customize Monitor Card"), color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                Text(t("Choose the goals for the Goals card on Monitor"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
            Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
        }
    }

    ExerciseGroupHeading(t("Your goals"), onPlus = onAdd)

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
                        if (goal.isHr && !goal.isPaused && hrNotFed) {
                            Text(t("Not filling in automatically"), color = HrWarningOrange,
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

    // Notes a practitioner left on active goals. Hidden when there are none.
    val noted = orderedGoals(goals).filter { it.isActive && !it.isOwn && !it.note.isNullOrBlank() }
    if (noted.isNotEmpty()) {
        BaseCard {
            Text(t("Notes"), color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            noted.forEachIndexed { i, goal ->
                if (i > 0) HorizontalDivider(color = AppTheme.TrackColor, modifier = Modifier.padding(vertical = 4.dp))
                Text(t(goal.title), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(goal.note!!.trim(), color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * "This week's review" and the weekly push switch.
 * [teaser] = drawn under the premium blur: with nothing real to show, one
 * placeholder review card keeps the blur from covering an empty space.
 */
@Composable
private fun GoalsReviewRegion(teaser: Boolean) {
    val report by PractitionerGoalsStore.report.collectAsState()

    val r = report
    if (r != null) GoalReviewCard(r)
    else if (teaser) {
        BaseCard {
            Text(t("This week's review"), color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            Text(t("Weekly AI goal suggestions and reviews from your own data"),
                color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
        }
    }

}

private fun weekOfLabel(iso: String): String = runCatching {
    LocalDate.parse(iso.take(10)).format(DateTimeFormatter.ofPattern("d MMM yyyy", appLocale()))
}.getOrDefault(iso.take(10))

@Composable
private fun GoalReviewCard(report: SupabasePractitionerService.GoalReportRow) {
    // Can be clicked away; the next week's review (a new week_start) shows again.
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("goals_review", android.content.Context.MODE_PRIVATE) }
    var dismissedWeek by remember { mutableStateOf(prefs.getString("dismissed_week", null)) }
    if (dismissedWeek == report.week_start) return
    BaseCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(t("This week's review"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                Text(t("Week of %s", weekOfLabel(report.week_start)),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(
                onClick = {
                    prefs.edit().putString("dismissed_week", report.week_start).apply()
                    dismissedWeek = report.week_start
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(Icons.Outlined.Close, contentDescription = t("Dismiss"), tint = AppTheme.SubtleTextColor, modifier = Modifier.size(18.dp))
            }
        }
        if (report.report.isNotBlank()) {
            Text(report.report.trim(), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
        }
        for (a in report.adherence) {
            HorizontalDivider(color = AppTheme.TrackColor, modifier = Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(t(a.title), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                GoalStatusChip(a.status)
            }
            a.note?.takeIf { it.isNotBlank() }?.let {
                Text(it.trim(), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GoalStatusChip(status: String) {
    val (label, color) = when (status) {
        "on_track" -> t("On track") to Color(0xFF81C784)
        "slipping" -> t("Slipping") to Color(0xFFFFB74D)
        "not_started" -> t("Not started") to AppTheme.SubtleTextColor
        else -> return
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(label, color = color, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
    }
}

