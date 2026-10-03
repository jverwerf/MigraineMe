package com.migraineme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle

/**
 * The goal cards shared by Monitor and Home, and the small parts
 * the detail screen reuses. All static: no motion, no progress animation.
 * Copy is English literal through t(); the goal title is itself a t() key.
 */

typealias GoalRow = SupabasePractitionerService.GoalRow
typealias GoalProgressRow = SupabasePractitionerService.GoalProgressRow

private fun fmtMinutes(v: Double): String =
    if (v == Math.rint(v)) v.toInt().toString() else String.format("%.1f", v)

/** Metric values: no decimals when whole, else one; thousands grouped. */
fun fmtGoalNumber(v: Double): String {
    val r = Math.round(v * 10.0) / 10.0
    return if (r == Math.rint(r)) String.format(appLocale(), "%,d", r.toLong())
    else String.format(appLocale(), "%,.1f", r)
}

/** A level metric's rank as a word: 0 None, 1 Low, 2 Medium, 3 High. */
@Composable
fun goalLevelWord(rank: Double): String = when (Math.rint(rank).toInt().coerceIn(0, 3)) {
    0 -> t("None")
    1 -> t("Low")
    2 -> t("Medium")
    else -> t("High")
}

/** "Steps: at least 8,000 steps", "Screen time: at most 3 h, 5 days a week",
 *  "Sleep duration: stay consistent". Unit comes from the catalogue. */
@Composable
private fun metricAskText(goal: GoalRow): String {
    val metrics by PractitionerGoalsStore.metrics.collectAsState()
    val label = t(goal.title)
    val metric = PractitionerGoalsStore.metricFor(metrics, goal.metric_key)
    val isLevel = metric?.isLevel == true
    // Level metrics read as a word, never with a unit: "Alcohol: at most None"
    val unit = if (isLevel) "" else metric?.unit?.trim().orEmpty()
        .let { if (it.isEmpty()) "" else t(it) }
    val days = goal.daysPerWeek
    val v = if (isLevel) goalLevelWord(goal.target_value ?: 0.0) else fmtGoalNumber(goal.target_value ?: 0.0)
    return when {
        goal.isConsistent ->
            if (goal.everyDay) t("%1\$s: stay consistent", label)
            else t("%1\$s: stay consistent, %2\$s days a week", label, days)
        goal.direction == SupabasePractitionerService.DIR_LTE -> when {
            unit.isEmpty() && goal.everyDay -> t("%1\$s: at most %2\$s", label, v)
            unit.isEmpty() -> t("%1\$s: at most %2\$s, %3\$s days a week", label, v, days)
            goal.everyDay -> t("%1\$s: at most %2\$s %3\$s", label, v, unit)
            else -> t("%1\$s: at most %2\$s %3\$s, %4\$s days a week", label, v, unit, days)
        }
        else -> when {
            unit.isEmpty() && goal.everyDay -> t("%1\$s: at least %2\$s", label, v)
            unit.isEmpty() -> t("%1\$s: at least %2\$s, %3\$s days a week", label, v, days)
            goal.everyDay -> t("%1\$s: at least %2\$s %3\$s", label, v, unit)
            else -> t("%1\$s: at least %2\$s %3\$s, %4\$s days a week", label, v, unit, days)
        }
    }
}

/** Metric goals only, else null: "Today: 7,412 steps", "No data yet today",
 *  or "Building your baseline" while a consistent goal has under 7 days of data. */
@Composable
fun goalTodayReadingText(goal: GoalRow, rows: List<GoalProgressRow>?): String? {
    if (!goal.isMetric) return null
    val metrics by PractitionerGoalsStore.metrics.collectAsState()
    val today = PractitionerGoalsStore.rowFor(rows, LocalDate.now())
    val value = today?.value
    val metric = PractitionerGoalsStore.metricFor(metrics, goal.metric_key)
    val unit = metric?.unit?.trim().orEmpty()
    return when {
        goal.isConsistent && today?.is_estimate == true -> t("Building your baseline")
        value == null -> t("No data yet today")
        metric?.isLevel == true -> t("Today: %s", goalLevelWord(value))
        unit.isEmpty() -> t("Today: %s", fmtGoalNumber(value))
        else -> t("Today: %1\$s %2\$s", fmtGoalNumber(value), t(unit))
    }
}

/** A metric goal with no reading on any of the 28 days the store holds. */
fun goalHasNoMetricData(goal: GoalRow, rows: List<GoalProgressRow>?): Boolean =
    goal.isMetric && rows != null && rows.none { it.value != null }

/** The ask, in one line: "3 times a day", "5 minutes every day",
 *  "At or above 130 bpm for 20 min, 4 days a week". */
@Composable
fun goalAskText(goal: GoalRow): String {
    val days = goal.daysPerWeek
    if (goal.isMetric) return metricAskText(goal)
    return when {
        goal.isCounter -> {
            val n = goal.target_count ?: 1
            when {
                goal.everyDay && n == 1 -> t("Once a day")
                goal.everyDay -> t("%s times a day", n)
                n == 1 -> t("Once a day, %s days a week", days)
                else -> t("%1\$s times a day, %2\$s days a week", n, days)
            }
        }
        goal.isMindfulness -> {
            val m = fmtMinutes(goal.target_minutes ?: 0.0)
            if (goal.everyDay) t("%s minutes every day", m)
            else t("%1\$s minutes a day, %2\$s days a week", m, days)
        }
        else -> {
            val bpm = goal.threshold_bpm ?: 0
            val m = fmtMinutes(goal.target_minutes ?: 0.0)
            if (goal.everyDay) t("At or above %1\$s bpm for %2\$s min, every day", bpm, m)
            else t("At or above %1\$s bpm for %2\$s min, %3\$s days a week", bpm, m, days)
        }
    }
}

/** "2 of 3 today", "0 of 5 min today", "1 of 4 days this week" (heart rate and metric goals). */
@Composable
fun goalProgressText(goal: GoalRow, rows: List<GoalProgressRow>?): String = when {
    goal.isCounter ->
        t("%1\$s of %2\$s today", PractitionerGoalsStore.todayValue(rows).toInt(), goal.target_count ?: 1)
    goal.isMindfulness ->
        t("%1\$s of %2\$s min today", fmtMinutes(PractitionerGoalsStore.todayValue(rows)), fmtMinutes(goal.target_minutes ?: 0.0))
    else ->
        t("%1\$s of %2\$s days this week", PractitionerGoalsStore.achievedThisWeek(rows), goal.daysPerWeek)
}

/** One quiet pill button; the log actions are the only buttons on these cards. */
@Composable
fun GoalPillButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .background(AppTheme.AccentPurple.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
            .border(1.dp, AppTheme.AccentPurple.copy(alpha = 0.40f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, color = AppTheme.AccentPurple,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** The manual button every active goal has (Jordy 2026-10-03: "always make it possible on
 *  each goal to press done"): "Done one" for counters, "Add 5 min" for meditation, and
 *  "Done today" for heart-rate and metric goals while today is not met yet (the server
 *  counts a hand tick as that day done; the measured value is left alone).
 *  Nothing for paused goals. Writes through the store, so every surface updates together. */
@Composable
fun GoalLogButton(goal: GoalRow) {
    if (!goal.isActive) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    if (goal.isHr || goal.isMetric) {
        val progress by PractitionerGoalsStore.progress.collectAsState()
        val rows = progress[goal.id] ?: return          // not loaded yet: no button
        val today = LocalDate.now().toString()
        if (rows.firstOrNull { it.day == today }?.achieved == true) return
        GoalPillButton(label = t("Done today"), enabled = !busy) {
            busy = true
            scope.launch {
                PractitionerGoalsStore.log(ctx, goal, countDelta = 1)
                busy = false
            }
        }
        return
    }
    val label = if (goal.isCounter) t("Done one") else t("Add 5 min")
    GoalPillButton(label = label, enabled = !busy) {
        busy = true
        scope.launch {
            if (goal.isCounter) PractitionerGoalsStore.log(ctx, goal, countDelta = 1)
            else PractitionerGoalsStore.log(ctx, goal, minutesDelta = 5.0)
            busy = false
        }
    }
}

@Composable
fun GoalPausedChip() {
    Box(
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(t("Paused"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
    }
}

/** One dot per day: filled when the day was achieved, hollow otherwise
 *  (a "building baseline" day is never achieved, so it is hollow too),
 *  dimmed for days still to come. */
@Composable
fun GoalDayDot(day: LocalDate, row: GoalProgressRow?, size: Int = 12) {
    val future = day.isAfter(LocalDate.now())
    val achieved = row?.achieved == true
    Box(
        modifier = Modifier
            .size(size.dp)
            .alpha(if (future) 0.35f else 1f)
            .background(if (achieved) AppTheme.AccentPurple else Color.Transparent, CircleShape)
            .border(1.5.dp, if (achieved) AppTheme.AccentPurple else Color.White.copy(alpha = 0.35f), CircleShape)
    )
}

/** Mon–Sun of this week as seven dots with a day letter under each. */
@Composable
fun GoalWeekStrip(rows: List<GoalProgressRow>?, withLabels: Boolean = true) {
    val locale = rememberAppLocale()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (day in PractitionerGoalsStore.weekDays()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                GoalDayDot(day, PractitionerGoalsStore.rowFor(rows, day))
                if (withLabels) {
                    Text(
                        day.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

/**
 * The "Set by <name>" caption. Not shown anywhere (Jordy 2026-10-03: "takes space
 * for no good reason"), so this always returns null and every call site draws
 * nothing. Who set a goal still decides what the client may edit (GoalRow.isOwn).
 */
@Suppress("UNUSED_PARAMETER")
fun goalSetByText(goal: GoalRow): String? = null

/** Practitioner goals first, then the client's own, each oldest first
 *  (the store already holds them by created_at). */
fun orderedGoals(goals: List<GoalRow>): List<GoalRow> = goals.sortedBy { it.isOwn }

/** Short progress for a Monitor tile: "2/3" today for counters, "0/5 min" today
 *  for meditation, "2/4" days this week for heart rate and metric goals. */
fun goalProgressShort(goal: GoalRow, rows: List<GoalProgressRow>?): String = when {
    goal.isCounter -> "${PractitionerGoalsStore.todayValue(rows).toInt()}/${goal.target_count ?: 1}"
    goal.isMindfulness ->
        "${fmtMinutes(PractitionerGoalsStore.todayValue(rows))}/${fmtMinutes(goal.target_minutes ?: 0.0)} min"
    else -> "${PractitionerGoalsStore.achievedThisWeek(rows)}/${goal.daysPerWeek}"
}

/** Monitor: the "Goals" card. Same build as the other Monitor category cards:
 *  header, then up to three tiles for the goals picked in GoalsConfigScreen.
 *  No buttons here; the whole card opens the Goals detail screen. */
@Composable
fun GoalsMonitorCard(
    goals: List<GoalRow>,
    progress: Map<String, List<GoalProgressRow>>,
    onClick: () -> Unit
) {
    val ctx = LocalContext.current
    MonitorBrainyCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        resId = R.drawable.brainy_goals,
        flipWatermark = true
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MonitorBlobIcon(resId = R.drawable.brainy_goals_small)
            Spacer(Modifier.width(10.dp))
            Text(t("Goals"), color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.weight(1f))
            Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodyMedium)
        }
        if (goals.isEmpty()) {
            Text(t("No goals yet"), color = AppTheme.SubtleTextColor)
            return@MonitorBrainyCard
        }
        val shown = remember(goals) { GoalsCardConfigStore.displayGoals(ctx, goals) }
        val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))
        // Three tiles per row; a short last row is padded with empty slots so
        // its tiles keep the same width as a full row.
        val perRow = 3
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.chunked(perRow).forEachIndexed { rowIndex, rowGoals ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowGoals.forEachIndexed { col, goal ->
                        val index = rowIndex * perRow + col
                        MetricTile(
                            value = goalProgressShort(goal, progress[goal.id]),
                            label = goal.title,
                            valueColor = slotColors[index % slotColors.size],
                            modifier = Modifier.weight(1f),
                            labelMaxLines = 1
                        )
                    }
                    repeat(perRow - rowGoals.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
