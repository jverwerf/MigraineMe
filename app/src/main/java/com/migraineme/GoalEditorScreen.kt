package com.migraineme

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.navigation.NavController
import kotlinx.coroutines.launch

/**
 * Add (goalId null) or edit one of the client's own goals. Five kinds:
 * an exercise film so many times a day, meditation minutes a day, a heart
 * rate stretch so many days a week, a free counter the client names, or a
 * catalogue metric (at least / at most a value, or staying consistent) that
 * the server marks from the client's own data. The kind, and a metric goal's
 * metric, are fixed once the goal exists.
 * Static: steppers and chips, no motion. Practitioner-set goals are not
 * editable here (the server refuses it too).
 */
@Composable
fun GoalEditorScreen(navController: NavController, goalId: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val goals by PractitionerGoalsStore.goals.collectAsState()
    val existing = goalId?.let { id -> goals.firstOrNull { it.id == id } }

    LaunchedEffect(Unit) { if (goalId != null && existing == null) PractitionerGoalsStore.refresh(ctx) }
    val metrics by PractitionerGoalsStore.metrics.collectAsState()
    LaunchedEffect(Unit) { PractitionerGoalsStore.ensureMetrics(ctx) }

    val routines = ExerciseCatalogue.ROUTINES
    var kind by remember { mutableStateOf(SupabasePractitionerService.KIND_EXERCISE) }
    var exerciseId by remember { mutableStateOf(routines.firstOrNull()?.id ?: "") }
    var exerciseCount by remember { mutableIntStateOf(1) }
    var meditationMinutes by remember { mutableIntStateOf(5) }
    var bpm by remember { mutableIntStateOf(130) }
    var hrMinutes by remember { mutableIntStateOf(20) }
    var hrDays by remember { mutableIntStateOf(4) }
    var dailyName by remember { mutableStateOf("") }
    var dailyCount by remember { mutableIntStateOf(1) }
    var metricKey by remember { mutableStateOf<String?>(null) }
    var metricDirection by remember { mutableStateOf(SupabasePractitionerService.DIR_GTE) }
    var metricTarget by remember { mutableStateOf(0.0) }
    var metricDays by remember { mutableIntStateOf(7) }
    var openGroup by remember { mutableStateOf<String?>(null) }
    var showValueDialog by remember { mutableStateOf(false) }
    var reminders by remember { mutableStateOf(listOf<String>()) }
    var initialized by remember { mutableStateOf(goalId == null) }
    var showHourPicker by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Fill the form from the goal being edited, once.
    LaunchedEffect(existing) {
        val g = existing ?: return@LaunchedEffect
        if (initialized) return@LaunchedEffect
        kind = g.kind
        when (g.kind) {
            SupabasePractitionerService.KIND_EXERCISE -> {
                g.exercise_id?.takeIf { ExerciseCatalogue.byId(it) != null }?.let { exerciseId = it }
                exerciseCount = (g.target_count ?: 1).coerceIn(1, 20)
            }
            SupabasePractitionerService.KIND_MINDFULNESS ->
                meditationMinutes = (g.target_minutes ?: 5.0).toInt().coerceIn(1, 120)
            SupabasePractitionerService.KIND_HR -> {
                bpm = (g.threshold_bpm ?: 130).coerceIn(60, 220)
                hrMinutes = (g.target_minutes ?: 20.0).toInt().coerceIn(1, 240)
                hrDays = (g.times_per_week ?: 4).coerceIn(1, 7)
            }
            SupabasePractitionerService.KIND_DAILY -> {
                dailyName = g.title
                dailyCount = (g.target_count ?: 1).coerceIn(1, 50)
            }
            SupabasePractitionerService.KIND_METRIC -> {
                metricKey = g.metric_key
                metricDirection = g.direction ?: SupabasePractitionerService.DIR_GTE
                metricTarget = g.target_value ?: 0.0
                metricDays = (g.times_per_week ?: 7).coerceIn(1, 7)
            }
        }
        reminders = g.reminder_times.distinct().sorted()
        initialized = true
    }

    val scrollState = rememberScrollState()
    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp, spacing = 12.dp) {
            if (goalId != null && existing == null) {
                BaseCard {
                    Text(t("This goal is no longer on your plan."),
                        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
                }
                return@ScrollableScreenContent
            }
            if (existing != null && !existing.isOwn) {
                BaseCard {
                    Text(t("Only your practitioner can change this goal."),
                        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
                }
                return@ScrollableScreenContent
            }

            val selectedMetric = PractitionerGoalsStore.metricFor(metrics, metricKey)

            // Kind: chosen when the goal is made, fixed after that
            if (existing == null) BaseCard {
                EditorHeading(t("What kind of goal?"))
                val kinds = listOf(
                    SupabasePractitionerService.KIND_EXERCISE to t("Exercise"),
                    SupabasePractitionerService.KIND_MINDFULNESS to t("Meditation"),
                    SupabasePractitionerService.KIND_HR to t("Heart rate"),
                    SupabasePractitionerService.KIND_DAILY to t("Something else"),
                    SupabasePractitionerService.KIND_METRIC to t("A metric"),
                )
                for (pair in kinds.chunked(2)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((k, label) in pair) {
                            EditorChip(label = label, selected = kind == k, modifier = Modifier.weight(1f)) {
                                kind = k; error = null
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            // The settings for that kind
            BaseCard {
                when (kind) {
                    SupabasePractitionerService.KIND_EXERCISE -> {
                        EditorHeading(t("Pick an exercise"))
                        for (r in routines) {
                            EditorChip(
                                label = t(r.title) + " · " + t("%s min", r.minutes),
                                selected = exerciseId == r.id,
                                modifier = Modifier.fillMaxWidth()
                            ) { exerciseId = r.id }
                        }
                        Spacer(Modifier.height(4.dp))
                        EditorStepper(t("Times a day"), exerciseCount, 1, 20) { exerciseCount = it }
                    }
                    SupabasePractitionerService.KIND_MINDFULNESS -> {
                        EditorHeading(t("Meditation"))
                        EditorStepper(t("Minutes a day"), meditationMinutes, 1, 120) { meditationMinutes = it }
                    }
                    SupabasePractitionerService.KIND_HR -> {
                        EditorHeading(t("Heart rate training"))
                        EditorStepper(t("At or above (bpm)"), bpm, 60, 220, step = 5) { bpm = it }
                        EditorStepper(t("Minutes in one go"), hrMinutes, 1, 240) { hrMinutes = it }
                        EditorStepper(t("Days a week"), hrDays, 1, 7) { hrDays = it }
                    }
                    SupabasePractitionerService.KIND_METRIC -> {
                        if (existing != null) {
                            // Editing: the metric is fixed
                            EditorHeading(t(selectedMetric?.label ?: existing.title))
                        } else {
                            EditorHeading(t("Pick a metric"))
                            if (metrics.isEmpty()) {
                                Text(t("Could not load the metrics. Check your connection and open this page again."),
                                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                            }
                            // One group open at a time: Diet alone is long
                            val groups = (listOf("Sleep", "Physical Health", "Cognitive", "Diet") +
                                metrics.map { it.grp }).distinct().filter { g -> metrics.any { it.grp == g } }
                            val open = openGroup ?: selectedMetric?.grp ?: groups.firstOrNull()
                            for (g in groups) {
                                Row(
                                    Modifier.fillMaxWidth().clickable { openGroup = if (open == g) "" else g }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(t(g), color = AppTheme.TitleColor,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        modifier = Modifier.weight(1f))
                                    Text(if (open == g) "−" else "+", color = AppTheme.AccentPurple,
                                        style = MaterialTheme.typography.titleMedium)
                                }
                                if (open == g) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        for (m in metrics.filter { it.grp == g }) {
                                            val unit = m.unit.trim()
                                            EditorChip(
                                                label = if (unit.isEmpty() || m.isLevel) t(m.label) else t(m.label) + " · " + t(unit),
                                                selected = metricKey == m.key,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                metricKey = m.key
                                                metricDirection = m.default_direction
                                                metricTarget = m.default_target ?: 0.0
                                                if (m.isLevel) {
                                                    if (metricDirection == SupabasePractitionerService.DIR_CONSISTENT)
                                                        metricDirection = SupabasePractitionerService.DIR_LTE
                                                    metricTarget = Math.rint(metricTarget).coerceIn(0.0, 3.0)
                                                }
                                                error = null
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (selectedMetric != null || existing != null) {
                            Spacer(Modifier.height(4.dp))
                            EditorHeading(t("Goal"))
                            val isLevel = selectedMetric?.isLevel == true
                            // Level metrics (None / Low / Medium / High) have no "stay consistent"
                            val dirs = listOfNotNull(
                                SupabasePractitionerService.DIR_GTE to t("At least"),
                                SupabasePractitionerService.DIR_LTE to t("At most"),
                                (SupabasePractitionerService.DIR_CONSISTENT to t("Stay consistent")).takeIf { !isLevel },
                            )
                            // Stacked: three side by side wrap unevenly in the longer languages
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for ((d, label) in dirs) {
                                    EditorChip(label = label, selected = metricDirection == d, modifier = Modifier.fillMaxWidth()) {
                                        metricDirection = d
                                    }
                                }
                            }
                            if (isLevel) {
                                val rank = Math.rint(metricTarget).coerceIn(0.0, 3.0)
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(t("Value"), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f))
                                    StepperButton("−", enabled = rank > 0.0) { metricTarget = rank - 1.0 }
                                    Box(
                                        Modifier.padding(horizontal = 6.dp)
                                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(goalLevelWord(rank), color = Color.White,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                                    }
                                    StepperButton("+", enabled = rank < 3.0) { metricTarget = rank + 1.0 }
                                }
                            } else if (metricDirection == SupabasePractitionerService.DIR_CONSISTENT) {
                                Text(t("Within your usual range: no more than 2 standard deviations from your 14-day average."),
                                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                            } else {
                                val step = (selectedMetric?.step ?: 1.0).takeIf { it > 0.0 } ?: 1.0
                                val unit = selectedMetric?.unit?.trim().orEmpty()
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (unit.isEmpty()) t("Value") else t("Value (%s)", t(unit)),
                                        color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f))
                                    StepperButton("−", enabled = metricTarget > 0.0) {
                                        metricTarget = roundTarget((metricTarget - step).coerceAtLeast(0.0))
                                    }
                                    // Tap the number to type it
                                    Box(
                                        Modifier.padding(horizontal = 6.dp)
                                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                                            .clickable { showValueDialog = true }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(fmtTarget(metricTarget), color = Color.White,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                                    }
                                    StepperButton("+", enabled = true) { metricTarget = roundTarget(metricTarget + step) }
                                }
                            }
                            EditorStepper(t("Days a week"), metricDays, 1, 7) { metricDays = it }
                        }
                    }
                    else -> {
                        EditorHeading(t("Something else"))
                        OutlinedTextField(
                            value = dailyName,
                            onValueChange = { dailyName = it.take(60); error = null },
                            label = { Text(t("Name")) },
                            placeholder = { Text(t("For example: Drink water")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                cursorColor = AppTheme.AccentPurple, focusedBorderColor = AppTheme.AccentPurple,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                                focusedLabelColor = AppTheme.AccentPurple, unfocusedLabelColor = AppTheme.SubtleTextColor,
                                focusedPlaceholderColor = AppTheme.SubtleTextColor, unfocusedPlaceholderColor = AppTheme.SubtleTextColor
                            )
                        )
                        EditorStepper(t("Times a day"), dailyCount, 1, 50) { dailyCount = it }
                    }
                }
            }

            // Reminders: whole hours, sent by the server
            BaseCard {
                EditorHeading(t("Reminders"))
                if (reminders.isEmpty()) {
                    Text(t("No reminders"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
                for (time in reminders) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(time.take(5), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { reminders = reminders - time }) {
                            Text("×", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                GoalPillButton(label = t("Add reminder"), enabled = true) { showHourPicker = true }
            }

            error?.let {
                Text(it, color = AppTheme.AccentPink, style = MaterialTheme.typography.bodySmall)
            }

            val nameMissing = t("Give your goal a name.")
            val metricMissing = t("Pick a metric first.")
            val saveFailed = t("Could not save. Try again.")
            Button(
                onClick = {
                    val draft = when (kind) {
                        SupabasePractitionerService.KIND_EXERCISE -> {
                            val r = ExerciseCatalogue.byId(exerciseId) ?: routines.first()
                            SupabasePractitionerService.GoalDraft(
                                kind = kind, title = r.title, exerciseId = r.id, targetCount = exerciseCount,
                                reminderTimes = reminders
                            )
                        }
                        SupabasePractitionerService.KIND_MINDFULNESS -> SupabasePractitionerService.GoalDraft(
                            kind = kind, title = "Meditation", targetMinutes = meditationMinutes.toDouble(),
                            reminderTimes = reminders
                        )
                        SupabasePractitionerService.KIND_HR -> SupabasePractitionerService.GoalDraft(
                            kind = kind, title = "Heart rate training", thresholdBpm = bpm,
                            targetMinutes = hrMinutes.toDouble(), timesPerWeek = hrDays, reminderTimes = reminders
                        )
                        SupabasePractitionerService.KIND_METRIC -> {
                            val key = metricKey
                            // Title = the catalogue's English label; an edit keeps the saved one if the catalogue did not load
                            val title = selectedMetric?.label ?: existing?.title
                            if (key == null || title == null) { error = metricMissing; return@Button }
                            val level = selectedMetric?.isLevel == true
                            val consistent = !level && metricDirection == SupabasePractitionerService.DIR_CONSISTENT
                            SupabasePractitionerService.GoalDraft(
                                kind = kind, title = title, metricKey = key, direction = metricDirection,
                                targetValue = if (consistent) null else if (level) Math.rint(metricTarget).coerceIn(0.0, 3.0) else metricTarget,
                                timesPerWeek = metricDays.takeIf { it < 7 }, reminderTimes = reminders
                            )
                        }
                        else -> {
                            val name = dailyName.trim()
                            if (name.isEmpty()) { error = nameMissing; return@Button }
                            SupabasePractitionerService.GoalDraft(
                                kind = SupabasePractitionerService.KIND_DAILY, title = name,
                                targetCount = dailyCount, reminderTimes = reminders
                            )
                        }
                    }
                    saving = true
                    error = null
                    scope.launch {
                        val ok = PractitionerGoalsStore.save(ctx, goalId, draft)
                        saving = false
                        if (ok) navController.popBackStack() else error = saveFailed
                    }
                },
                enabled = !saving && initialized,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple, contentColor = Color.White)
            ) {
                Text(t("Save"), fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (showValueDialog) {
        var typed by remember { mutableStateOf(if (metricTarget == 0.0) "" else trimTarget(metricTarget)) }
        val unit = PractitionerGoalsStore.metricFor(metrics, metricKey)?.unit?.trim().orEmpty()
        val parsed = typed.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0.0 && it.isFinite() }
        AlertDialog(
            onDismissRequest = { showValueDialog = false },
            confirmButton = {
                TextButton(enabled = parsed != null, onClick = {
                    parsed?.let { metricTarget = roundTarget(it) }
                    showValueDialog = false
                }) { Text(t("OK"), color = if (parsed != null) AppTheme.AccentPurple else AppTheme.SubtleTextColor) }
            },
            dismissButton = {
                TextButton(onClick = { showValueDialog = false }) {
                    Text(t("Cancel"), color = AppTheme.AccentPurple)
                }
            },
            title = {
                Text(if (unit.isEmpty()) t("Value") else t("Value (%s)", t(unit)),
                    color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { v -> typed = v.filter { it.isDigit() || it == '.' || it == ',' }.take(9) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        cursorColor = AppTheme.AccentPurple, focusedBorderColor = AppTheme.AccentPurple,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.12f)
                    )
                )
            },
            containerColor = Color(0xFF1A0029),
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showHourPicker) {
        AlertDialog(
            onDismissRequest = { showHourPicker = false },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showHourPicker = false }) {
                    Text(t("Cancel"), color = AppTheme.AccentPurple)
                }
            },
            title = { Text(t("Pick an hour"), color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (row in (0..23).chunked(4)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (h in row) {
                                val value = "%02d:00:00".format(h)
                                EditorChip(
                                    label = "%02d:00".format(h),
                                    selected = value in reminders,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    reminders = (reminders + value).distinct().sorted()
                                    showHourPicker = false
                                }
                            }
                        }
                    }
                }
            },
            containerColor = Color(0xFF1A0029),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

/** Steps of 0.1 or 0.25 must not drift into 7.300000001. */
private fun roundTarget(v: Double): Double = Math.round(v * 100.0) / 100.0

/** As fmtGoalNumber, but a quarter step (0.25) keeps its second decimal. */
private fun fmtTarget(v: Double): String =
    if (Math.round(v * 10.0) / 10.0 == v) fmtGoalNumber(v) else String.format(appLocale(), "%,.2f", v)

/** Plain digits for the type-it field: "8000", "7.5". */
private fun trimTarget(v: Double): String =
    if (v == Math.rint(v)) v.toLong().toString() else v.toString()

@Composable
private fun EditorHeading(text: String) {
    Text(text, color = AppTheme.TitleColor,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
}

/** A selectable rounded box: filled purple tint when selected. */
@Composable
private fun EditorChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .background(if (selected) AppTheme.AccentPurple.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f), shape)
            .border(1.dp, if (selected) AppTheme.AccentPurple else Color.White.copy(alpha = 0.10f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(label, color = if (selected) Color.White else AppTheme.BodyTextColor,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal))
    }
}

/** Label, then − value +, moving by [step]. Clamped to [min]..[max]. */
@Composable
private fun EditorStepper(label: String, value: Int, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f))
        StepperButton("−", enabled = value > min) { onChange((value - step).coerceAtLeast(min)) }
        Text(value.toString(), color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.width(52.dp).padding(horizontal = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        StepperButton("+", enabled = value < max) { onChange((value + step).coerceAtMost(max)) }
    }
}

@Composable
private fun StepperButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .background(AppTheme.AccentPurple.copy(alpha = 0.16f), CircleShape)
            .border(1.dp, AppTheme.AccentPurple.copy(alpha = 0.40f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = AppTheme.AccentPurple,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}
