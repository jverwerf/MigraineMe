// app/src/main/java/com/migraineme/HomeScreen.kt
package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.max
import kotlin.math.min

@Composable
fun HomeScreenRoot(
    onLogout: () -> Unit,
    onNavigateToMigraine: () -> Unit = {},
    authVm: AuthViewModel,
    logVm: LogViewModel,
    vm: HomeViewModel? = null // safe default
) {
    val actualVm: HomeViewModel = vm ?: viewModel()
    val state by actualVm.state.collectAsState()

    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        HomeScreen(
            state = state,
            onQuickMigraine = {
                authVm.state.value.accessToken?.let {
                    logVm.addMigraine(it, "Migraine", 5, "Quick action", null)
                }
            },
            onQuickMedicine = {
                authVm.state.value.accessToken?.let {
                    logVm.addMedicine(it, "Medicine", null, null, "Quick action")
                }
            },
            onQuickRelief = {
                authVm.state.value.accessToken?.let {
                    logVm.addRelief(it, "Relief", null, null, "Quick action")
                }
            }
        )
    }
}

@Composable
private fun HomeScreen(
    state: HomeUiState,
    onQuickMigraine: () -> Unit,
    onQuickMedicine: () -> Unit,
    onQuickRelief: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Quick actions
        SectionCard(title = "Quick actions") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickActionButton(
                    text = "Migraine",
                    icon = { Icon(Icons.Outlined.Psychology, contentDescription = null) },
                    onClick = onQuickMigraine,
                    modifier = Modifier.weight(1f)
                )
                QuickActionButton(
                    text = "Medicine",
                    icon = { Icon(Icons.Outlined.Medication, contentDescription = null) },
                    onClick = onQuickMedicine,
                    modifier = Modifier.weight(1f)
                )
                QuickActionButton(
                    text = "Relief",
                    icon = { Icon(Icons.Outlined.Spa, contentDescription = null) },
                    onClick = onQuickRelief,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Risk gauge
        SectionCard(title = "Risk today") {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RiskGauge(
                    percent = state.riskPercent,
                    diameter = 160.dp,
                    stroke = 12.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    progressColor = MaterialTheme.colorScheme.primary
                )
                Text(
                    "${state.riskPercent}%",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }

        // Triggers: Now vs Average
        SectionCard(title = "Top triggers (now vs avg)") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.triggersAtRisk.forEach { trig ->
                    val now = trig.score.toFloat().coerceIn(0f, 100f)
                    val avg = 50f // TODO: replace with real average when available
                    TriggerRowLineMeter(
                        name = trig.name,
                        now = now,
                        avg = avg
                    )
                }
            }
        }

        // Recommendation (brought back)
        SectionCard(title = "Recommendation") {
            Text(
                text = state.aiRecommendation.ifBlank { "No recommendation right now." },
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

/* --- UI helpers --- */

@Composable
private fun QuickActionButton(
    text: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedButton(
        onClick = onClick,
        modifier = modifier.height(72.dp) // a bit taller so both fit comfortably
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            icon()
            Spacer(Modifier.height(4.dp))
            Text(
                text,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}


@Composable
private fun RiskGauge(
    percent: Int,
    diameter: Dp,
    stroke: Dp,
    trackColor: Color,
    progressColor: Color
) {
    val p = percent.coerceIn(0, 100) / 100f
    androidx.compose.foundation.Canvas(modifier = Modifier.height(diameter)) {
        val strokePx = stroke.toPx()
        val arcSize = Size(size.minDimension - strokePx, size.minDimension - strokePx)
        val topLeft = Offset((size.width - arcSize.width) / 2f, (size.height - arcSize.height) / 2f)
        drawArc(
            color = trackColor,
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
            size = arcSize,
            topLeft = topLeft
        )
        drawArc(
            color = progressColor,
            startAngle = 135f,
            sweepAngle = 270f * p,
            useCenter = false,
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
            size = arcSize,
            topLeft = topLeft
        )
    }
}

@Composable
private fun TriggerRowLineMeter(
    name: String,
    now: Float,
    avg: Float
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, color = MaterialTheme.colorScheme.onSurface)
            Text("${now.toInt()} / avg ${avg.toInt()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LineMeter(now = now, avg = avg)
    }
}

@Composable
private fun LineMeter(
    now: Float,
    avg: Float,
    height: Dp = 12.dp
) {
    val clampedNow = min(100f, max(0f, now))
    val clampedAvg = min(100f, max(0f, avg))

    // Hoist colors here (composable context) and capture inside Canvas
    val bg = MaterialTheme.colorScheme.surface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val trackShape = RoundedCornerShape(999.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(bg, trackShape)
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val nowX = w * (clampedNow / 100f)
            val avgX = w * (clampedAvg / 100f)

            // avg marker
            drawLine(
                color = onSurfaceVariant.copy(alpha = 0.7f),
                start = Offset(avgX, 0f),
                end = Offset(avgX, h),
                strokeWidth = 2f
            )
            // now fill
            drawRect(
                color = primary.copy(alpha = 0.22f),
                topLeft = Offset(0f, 0f),
                size = Size(nowX, h)
            )
            // now edge
            drawLine(
                color = primary,
                start = Offset(nowX, 0f),
                end = Offset(nowX, h),
                strokeWidth = 4f
            )
        }
    }
}
