// FILE: C:\Users\verwe\Projects\MigraineMe\app\src\main\java\com\migraineme\InsightsDetailScreen.kt
package com.migraineme

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

private val HeroCardShape = RoundedCornerShape(24.dp)
private val HeroCardContainer = Color(0xFF2A0C3C).copy(alpha = 0.78f)

private val TitleColor = Color(0xFFDCCEFF)
private val BodyTextColor = Color.White.copy(alpha = 0.82f)
private val SubtleTextColor = Color.White.copy(alpha = 0.62f)

private val AccentPurple = Color(0xFFB97BFF)
private val AccentPink = Color(0xFFFF7BB0)

// Keep the same background fade behavior as Home/Insights preview.
private val LogoRevealHeight = 220.dp
private val FadeColor = Color(0xFF2A003D)
private val FadeDistance = 220.dp

@Composable
fun InsightsDetailScreen(
    vm: InsightsViewModel = viewModel()
) {
    val owner = LocalContext.current as ViewModelStoreOwner
    val ctx: Context = LocalContext.current.applicationContext
    val authVm: AuthViewModel = viewModel(owner)
    val auth by authVm.state.collectAsState()

    // Load only (no workers here)
    LaunchedEffect(auth.accessToken) {
        val token = auth.accessToken
        if (!token.isNullOrBlank()) vm.load(ctx, token)
    }

    val migraines by vm.migraines.collectAsState()
    val reliefs by vm.reliefs.collectAsState()
    val triggers by vm.triggers.collectAsState()
    val meds by vm.medicines.collectAsState()

    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val fadeAlpha by remember(scrollState, density) {
        derivedStateOf {
            val fadePx = with(density) { FadeDistance.toPx() }
            if (fadePx <= 0f) 1f else (scrollState.value / fadePx).coerceIn(0f, 1f)
        }
    }

    val hOffsetPx = remember { mutableFloatStateOf(0f) }
    var timeSpan by remember { mutableStateOf(TimeSpan.WEEK) }

    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FadeColor.copy(alpha = fadeAlpha))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(LogoRevealHeight))

            InsightsTimelineDetailCard(
                migraines = migraines,
                reliefs = reliefs,
                triggers = triggers,
                meds = meds,
                hOffsetPx = hOffsetPx,
                timeSpan = timeSpan,
                onTimeSpanSelected = { timeSpan = it }
            )
        }
    }
}

@Composable
private fun InsightsTimelineDetailCard(
    migraines: List<MigraineSpan>,
    reliefs: List<ReliefSpan>,
    triggers: List<TriggerPoint>,
    meds: List<MedicinePoint>,
    hOffsetPx: androidx.compose.runtime.MutableState<Float>,
    timeSpan: TimeSpan,
    onTimeSpanSelected: (TimeSpan) -> Unit
) {
    val borderBrush = remember {
        Brush.linearGradient(
            colors = listOf(
                AccentPurple.copy(alpha = 0.60f),
                AccentPink.copy(alpha = 0.55f)
            )
        )
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val strokePx = 2.dp.toPx()
            val cr = CornerRadius(24.dp.toPx(), 24.dp.toPx())
            drawRoundRect(
                brush = borderBrush,
                topLeft = Offset(strokePx / 2f, strokePx / 2f),
                size = Size(size.width - strokePx, size.height - strokePx),
                cornerRadius = cr,
                style = Stroke(width = strokePx)
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = HeroCardShape,
            colors = CardDefaults.cardColors(containerColor = HeroCardContainer),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(AccentPurple, AccentPink)
                            )
                        )
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        AccentPurple.copy(alpha = 0.18f),
                                        Color.Transparent
                                    ),
                                    center = Offset(0f, 0f),
                                    radius = 800f
                                )
                            )
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Timeline",
                            color = TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            TimeSpan.values().forEachIndexed { idx, span ->
                                val selected = span == timeSpan
                                if (selected) {
                                    Button(
                                        onClick = { onTimeSpanSelected(span) },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentPurple)
                                    ) { Text(span.label, color = Color.White) }
                                } else {
                                    OutlinedButton(
                                        onClick = { onTimeSpanSelected(span) }
                                    ) { Text(span.label, color = BodyTextColor) }
                                }

                                if (idx != TimeSpan.values().lastIndex) Spacer(Modifier.width(10.dp))
                            }
                        }

                        Text(
                            "Tap on an icon/bar for details",
                            color = SubtleTextColor,
                            style = MaterialTheme.typography.bodySmall
                        )

                        InsightsTimelineGraphInteractive(
                            migraines = migraines,
                            reliefs = reliefs,
                            triggers = triggers,
                            meds = meds,
                            hOffsetPx = hOffsetPx,
                            timeSpan = timeSpan,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(360.dp)
                        )
                    }
                }
            }
        }
    }
}
