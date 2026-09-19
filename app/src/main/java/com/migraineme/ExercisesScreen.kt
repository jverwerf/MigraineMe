package com.migraineme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * The exercise routines, in two groups: the ones that help prevent attacks, and
 * the ones for during an attack. Tap a routine to open its player.
 * All copy is an ExerciseCatalogue English literal, translated here at render.
 */
@Composable
fun ExercisesScreen(
    onOpenRoutine: (String) -> Unit
) {
    val scrollState = rememberScrollState()

    // The attack routines are the ones needed when things are worst, possibly
    // with no signal: fetch them into the film cache in the background the first
    // time this list appears. Returns at once, one attempt per app session,
    // skips what is already cached, silent on failure. No UI for it.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ExerciseVideoCache.prefetchAttackRoutines(context, LangPrefs.get().code)
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp, spacing = 12.dp) {
            ExerciseGroupHeading(t("Prevent"))
            for (routine in ExerciseCatalogue.forWhen(ExerciseWhen.PREVENT)) {
                ExerciseRoutineRow(routine = routine, onTap = { onOpenRoutine(routine.id) })
            }

            ExerciseGroupHeading(t("During an attack"))
            for (routine in ExerciseCatalogue.forWhen(ExerciseWhen.ATTACK)) {
                ExerciseRoutineRow(routine = routine, onTap = { onOpenRoutine(routine.id) })
            }
        }
    }
}

@Composable
private fun ExerciseGroupHeading(text: String) {
    // Same build as the "Quick Log" header on the Log tab: a full-width card with the title in it
    BaseCard(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            text,
            color = AppTheme.TitleColor,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ExerciseRoutineRow(
    routine: ExerciseRoutine,
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
            }
            Spacer(Modifier.width(8.dp))
            Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
        }
    }
}
