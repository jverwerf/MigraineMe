package com.migraineme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * The exercise routines, in two groups: the ones that help prevent attacks, and
 * the ones for during an attack. Tap a routine to open its player.
 * All copy is an ExerciseCatalogue English literal, translated here at render.
 */
@Composable
fun ExercisesScreen(
    onOpenRoutine: (String) -> Unit,
    /** null = this IS the library (every routine, Add on each). */
    onOpenLibrary: (() -> Unit)? = null
) {
    val library = onOpenLibrary == null
    val scrollState = rememberScrollState()

    // The attack routines are the ones needed when things are worst, possibly
    // with no signal: fetch them into the film cache in the background the first
    // time this list appears. Returns at once, one attempt per app session,
    // skips what is already cached, silent on failure. No UI for it.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ExerciseVideoCache.prefetchAttackRoutines(context, LangPrefs.get().code)
    }
    // Which routines are already yours (an active exercise goal, set by a
    // practitioner or added here). Add moves one up into "Your exercises".
    val goals by PractitionerGoalsStore.goals.collectAsState()
    LaunchedEffect(Unit) { PractitionerGoalsStore.refresh(context) }
    val onHome = goals.filter { it.isActive && it.kind == SupabasePractitionerService.KIND_EXERCISE }
        .mapNotNull { it.exercise_id }.toSet()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf<String?>(null) }
    val add: (ExerciseRoutine) -> Unit = { routine ->
        if (adding == null) {
            adding = routine.id
            scope.launch {
                PractitionerGoalsStore.save(
                    context, null,
                    SupabasePractitionerService.GoalDraft(
                        kind = SupabasePractitionerService.KIND_EXERCISE,
                        title = routine.title,
                        exerciseId = routine.id,
                        targetCount = 1
                    )
                )
                adding = null
            }
        }
    }

    // Two modes (Jordy 10-02): the Exercises page shows only YOUR exercises
    // (practitioner-set first, then the ones you added) with a + in the heading;
    // the + opens the library, which shows EVERY routine with Add / Added.
    // No counts on either; those are on Monitor.
    val mine = orderedGoals(goals.filter { it.isActive && it.kind == SupabasePractitionerService.KIND_EXERCISE })
        .mapNotNull { g -> ExerciseCatalogue.byId(g.exercise_id)?.let { r -> r to g } }
        .distinctBy { it.first.id }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp, spacing = 12.dp) {
            if (!library) {
                ExerciseGroupHeading(t("Your exercises"), onPlus = onOpenLibrary)
                if (mine.isEmpty()) {
                    Text(
                        t("No exercises yet. Tap + to add one."),
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                for ((routine, goal) in mine) {
                    ExerciseRoutineRow(
                        routine = routine,
                        caption = goalSetByText(goal),
                        trailing = null,
                        onTap = { onOpenRoutine(routine.id) }
                    )
                }
            } else {
                // Safety note before the whole library (Jordy 10-02).
                BaseCard(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Column {
                        Text(
                            t("Check with your physio first"),
                            color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            t("Only start these exercises once a physiotherapist has looked at your neck. Done the wrong way, or for the wrong problem, such as a locked or strained muscle, they can make your pain worse."),
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                for ((heading, whenToUse) in listOf(t("Prevent") to ExerciseWhen.PREVENT, t("During an attack") to ExerciseWhen.ATTACK)) {
                    ExerciseGroupHeading(heading)
                    for (routine in ExerciseCatalogue.ROUTINES.filter { it.whenToUse == whenToUse }) {
                        ExerciseRoutineRow(routine = routine, caption = null,
                            trailing = {
                                if (routine.id in onHome) {
                                    Text(t("Added"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
                                } else {
                                    GoalPillButton(label = t("Add"), enabled = adding == null, onClick = { add(routine) })
                                }
                            },
                            onTap = { onOpenRoutine(routine.id) })
                    }
                }
            }
        }
    }
}

/** Shared heading card with an optional round + on the right (also used by MonitorGoalsScreen). */
@Composable
internal fun ExerciseGroupHeading(text: String, onPlus: (() -> Unit)? = null) {
    // Same build as the "Quick Log" header on the Log tab: a full-width card with the title in it
    BaseCard(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f)
            )
            if (onPlus != null) {
                androidx.compose.material3.Surface(
                    onClick = onPlus,
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = AppTheme.AccentPurple.copy(alpha = 0.18f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.6f)),
                    modifier = Modifier.size(36.dp)
                ) {
                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                        Text("+", color = AppTheme.AccentPurple,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseRoutineRow(
    routine: ExerciseRoutine,
    caption: String?,
    trailing: (@Composable () -> Unit)?,
    onTap: () -> Unit
) {
    BaseCard(modifier = Modifier.clickable(onClick = onTap)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Poster — static, no crossfade
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(ExerciseCatalogue.posterUrl(routine))
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(112.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    t(routine.title),
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    t(routine.level) + " · " + t("%s min", routine.minutes),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
                if (caption != null) {
                    Text(caption, color = AppTheme.AccentPurple, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.width(8.dp))
            if (trailing != null) trailing()
            else Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
        }
    }
}
