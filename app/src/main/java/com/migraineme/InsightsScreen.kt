package com.migraineme

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import java.time.Instant

data class MigraineSpan(
    val start: Instant,
    val end: Instant?,
    val severity: Int? = null,
    val label: String? = null
)

data class ReliefSpan(
    val start: Instant,
    val end: Instant?,
    val intensity: Int? = null,
    val name: String
)

data class TriggerPoint(
    val at: Instant,
    val name: String
)

data class MedicinePoint(
    val at: Instant,
    val name: String,
    val amount: String?
)

enum class TimeSpan(val days: Long, val label: String) {
    DAY(1, "Day"),
    WEEK(7, "Week"),
    MONTH(30, "Month"),
    YEAR(365, "Year");

    val millis: Long get() = days * 24L * 60L * 60L * 1000L
}

@Composable
fun InsightsScreen(
    navController: NavHostController,
    vm: InsightsViewModel = viewModel()
) {
    val owner = LocalContext.current as ViewModelStoreOwner
    val ctx: Context = LocalContext.current.applicationContext
    val authVm: AuthViewModel = viewModel(owner)
    val auth by authVm.state.collectAsState()

    LaunchedEffect(auth.accessToken) {
        val token = auth.accessToken
        if (!token.isNullOrBlank()) vm.load(ctx, token)
    }

    val migraines by vm.migraines.collectAsState()
    val reliefs by vm.reliefs.collectAsState()
    val triggers by vm.triggers.collectAsState()
    val meds by vm.medicines.collectAsState()

    val scrollState = rememberScrollState()
    val hOffsetPx = remember { mutableFloatStateOf(0f) }
    var timeSpan by remember { mutableStateOf(TimeSpan.WEEK) }
    val previewSpans = remember { listOf(TimeSpan.WEEK, TimeSpan.MONTH) }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll) {
            InsightsTimelinePreviewCard(
                migraines = migraines,
                reliefs = reliefs,
                triggers = triggers,
                meds = meds,
                hOffsetPx = hOffsetPx,
                timeSpan = timeSpan,
                spans = previewSpans,
                onTimeSpanSelected = { timeSpan = it },
                onTapMoreDetails = { navController.navigate(Routes.INSIGHTS_DETAIL) }
            )
        }
    }
}

@Composable
private fun InsightsTimelinePreviewCard(
    migraines: List<MigraineSpan>,
    reliefs: List<ReliefSpan>,
    triggers: List<TriggerPoint>,
    meds: List<MedicinePoint>,
    hOffsetPx: androidx.compose.runtime.MutableState<Float>,
    timeSpan: TimeSpan,
    spans: List<TimeSpan>,
    onTimeSpanSelected: (TimeSpan) -> Unit,
    onTapMoreDetails: () -> Unit
) {
    HeroCard {
        Text(
            "Timeline",
            color = AppTheme.TitleColor,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            spans.forEachIndexed { idx, span ->
                val selected = span == timeSpan
                if (selected) {
                    Button(
                        onClick = { onTimeSpanSelected(span) },
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                    ) { Text(span.label, color = Color.White) }
                } else {
                    OutlinedButton(
                        onClick = { onTimeSpanSelected(span) }
                    ) { Text(span.label, color = AppTheme.BodyTextColor) }
                }

                if (idx != spans.lastIndex) Spacer(Modifier.width(10.dp))
            }
        }

        Text(
            "Tap for more details",
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.bodySmall
        )

        InsightsTimelineGraphPreview(
            migraines = migraines,
            reliefs = reliefs,
            triggers = triggers,
            meds = meds,
            hOffsetPx = hOffsetPx,
            timeSpan = timeSpan,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            onTapMoreDetails = onTapMoreDetails
        )
    }
}
