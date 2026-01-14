package com.migraineme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Composable
fun HomeScreenRoot(
    onLogout: () -> Unit,
    onNavigateToMigraine: () -> Unit = {},
    authVm: AuthViewModel,
    logVm: LogViewModel,
    vm: HomeViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val auth by authVm.state.collectAsState()

    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        HomeScreen(
            state = state,
            accessToken = auth.accessToken,
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
    accessToken: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        DataStatusCard(accessToken = accessToken)

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
                            LinearProgressIndicator(
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
private fun DataStatusCard(accessToken: String?) {
    val ctx = LocalContext.current.applicationContext
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone).toString()

    val whoopConnected = remember {
        runCatching { WhoopTokenStore(ctx).load() != null }.getOrDefault(false)
    }

    var lastManualRunLabel by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Data status", style = MaterialTheme.typography.titleMedium)

            val canRunNow = !accessToken.isNullOrBlank() && whoopConnected

            Button(
                onClick = {
                    // WHOOP
                    WhoopDailySyncWorkerSleepFields.runOnceNow(ctx)
                    WhoopDailyPhysicalHealthWorker.runOnceNow(ctx)

                    // LOCATION
                    LocationDailySyncWorker.runOnceNow(ctx)

                    lastManualRunLabel = "Manual sync triggered at ${java.time.LocalTime.now(zone).withSecond(0)}"
                },
                enabled = canRunNow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Run WHOOP + Location sync now")
            }

            if (lastManualRunLabel != null) {
                Text(
                    text = lastManualRunLabel!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            if (!canRunNow) {
                val reason = when {
                    accessToken.isNullOrBlank() -> "Sign in to run sync."
                    !whoopConnected -> "Connect WHOOP to run sync."
                    else -> "Sync unavailable."
                }
                Text(reason, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
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
    Canvas(modifier = Modifier.size(diameter)) {
        val strokePx = stroke.toPx()
        val arcSize = Size(size.minDimension - strokePx, size.minDimension - strokePx)
        val topLeft = Offset(
            (size.width - arcSize.width) / 2f,
            (size.height - arcSize.height) / 2f
        )
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
