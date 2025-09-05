package com.migraineme

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant

@Composable
fun HomeScreenRoot(
    onLogout: () -> Unit,
    onNavigateToMigraine: () -> Unit = {},
    authVm: AuthViewModel,            // <-- use shared instances from MainActivity
    logVm: LogViewModel,              // <--
    vm: HomeViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val auth by authVm.state.collectAsState()
    val ctx = LocalContext.current

    // Quick actions: create minimal entries with "now" timestamps (details editable later)
    val onQuickMigraine = {
        val token = auth.accessToken
        if (token != null) {
            logVm.addMigraine(
                accessToken = token,
                type = "Migraine",
                severity = 5,
                notes = "Quick action",
                endedAtIso = null
            )
            Toast.makeText(ctx, "Quick migraine started (edit later in Log → History).", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, "Please sign in first.", Toast.LENGTH_SHORT).show()
        }
    }
    val onQuickMedicine = {
        val token = auth.accessToken
        if (token != null) {
            logVm.addMedicine(
                accessToken = token,
                name = "Medicine",
                amount = null,
                migraineId = null,
                notes = "Quick action"
            )
            Toast.makeText(ctx, "Quick medicine saved (edit later in Log → History).", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, "Please sign in first.", Toast.LENGTH_SHORT).show()
        }
    }
    val onQuickRelief = {
        val token = auth.accessToken
        if (token != null) {
            logVm.addRelief(
                accessToken = token,
                type = "Relief",
                durationMin = null,
                migraineId = null,
                notes = "Quick action"
            )
            Toast.makeText(ctx, "Quick relief saved (edit later in Log → History).", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(ctx, "Please sign in first.", Toast.LENGTH_SHORT).show()
        }
    }

    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        HomeScreen(
            state = state,
            onQuickMigraine = onQuickMigraine,
            onQuickMedicine = onQuickMedicine,
            onQuickRelief = onQuickRelief,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        )
    }
}

@Composable
private fun HomeScreen(
    state: HomeUiState,
    onQuickMigraine: () -> Unit,
    onQuickMedicine: () -> Unit,
    onQuickRelief: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ---- Quick Actions (first) ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Quick Actions", style = MaterialTheme.typography.titleMedium)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
        }

        // ---- Risk Gauge ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Risk today", style = MaterialTheme.typography.titleMedium)
                RiskGauge(
                    percent = state.riskPercent,
                    diameter = 180.dp,
                    stroke = 14.dp,
                    trackColor = Color(0xFFE8E8E8),
                    progressColor = Color(0xFF6750A4)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${state.riskPercent}%",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }

        // ---- Top Triggers ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Top Triggers", style = MaterialTheme.typography.titleMedium)
                state.triggersAtRisk.forEach { t ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(t.name, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.width(120.dp)) {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = t.score / 100f,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("${t.score}%")
                    }
                }
            }
        }

        // ---- AI Recommendation ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Recommendation", style = MaterialTheme.typography.titleMedium)
                Text(state.aiRecommendation.ifBlank { "—" })
            }
        }
    }
}

@Composable
private fun QuickActionButton(
    text: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedButton(onClick = onClick, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(8.dp))
            Text(text)
        }
    }
}

/** Simple circular gauge that avoids name collisions with Canvas' `size`. */
@Composable
private fun RiskGauge(
    percent: Int,
    diameter: Dp,
    stroke: Dp,
    trackColor: Color,
    progressColor: Color
) {
    val p = percent.coerceIn(0, 100) / 100f
    Canvas(modifier = Modifier.size(diameter)) {
        val strokePx = stroke.toPx()
        val arcSize = Size(width = size.minDimension - strokePx, height = size.minDimension - strokePx)
        val topLeft = Offset((size.width - arcSize.width) / 2f, (size.height - arcSize.height) / 2f)
        // Track
        drawArc(
            color = trackColor,
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
            size = arcSize,
            topLeft = topLeft
        )
        // Progress
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
