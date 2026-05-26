// FILE: MigrainesMonitorCard.kt
// Monitor → Migraines preview card. Reuses the Activity-scoped
// InsightsViewModel so data is shared with the detail screen.
package com.migraineme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun MigrainesMonitorCard(onClick: () -> Unit) {
    val ctx = LocalContext.current
    val owner = ctx as? ViewModelStoreOwner
    val vm: InsightsViewModel = if (owner != null) viewModel(owner) else viewModel()
    val ws by vm.weeklySummary.collectAsState()
    var showInfo by remember { mutableStateOf(false) }

    // Trigger load if we have an access token; mirrors InsightsScreen pattern.
    LaunchedEffect(Unit) {
        val token = SessionStore.getValidAccessToken(ctx)
        if (!token.isNullOrBlank()) {
            vm.load(ctx, token)
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        BaseCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(24.dp)) { HubIcons.run { drawMigraineStarburst(AppTheme.AccentPink) } }
                Spacer(Modifier.width(10.dp))
                Text("Migraines", color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f))
                Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
            }

            val wsValue = ws
            if (wsValue != null) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${wsValue.thisWeekCount}", color = Color.White,
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                            if (wsValue.trend != "stable") {
                                val symbol = if (wsValue.trend == "up") "↑" else "↓"
                                val color = if (wsValue.trend == "up") Color(0xFFE57373) else Color(0xFF81C784)
                                Text(symbol, color = color,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            }
                        }
                        Text("this week", color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall)
                        Text("vs ${wsValue.lastWeekCount} last", color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall)
                    }

                    val avg = wsValue.thisWeekAvgSeverity
                    if (avg != null) {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            val sevColor = if (avg >= 7f) Color(0xFFE57373)
                                else if (avg >= 4f) Color(0xFFFFB74D)
                                else Color(0xFF81C784)
                            Text(String.format("%.1f", avg), color = sevColor,
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                            Text("avg sev", color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelSmall)
                            Text("this week", color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${wsValue.totalLogged}", color = Color.White,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                        Text("total", color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall)
                        Text("all time", color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Text("Your weekly overview will appear after your first week of tracking.",
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Start)
            }
        }

        IconButton(
            onClick = { showInfo = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 10.dp, y = (-14).dp)
                .size(34.dp)
        ) {
            Icon(Icons.Outlined.Info, contentDescription = "About Migraines",
                tint = AppTheme.SubtleTextColor, modifier = Modifier.size(20.dp))
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Got it", color = AppTheme.AccentPurple) } },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(20.dp)) { HubIcons.run { drawMigraineStarburst(AppTheme.AccentPink) } }
                    Spacer(Modifier.width(8.dp))
                    Text("About Migraines", color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                }
            },
            text = {
                Text(
                    "Your overview of logged migraine attacks. The card shows three things side-by-side: " +
                    "the number of attacks this week (with an up/down arrow comparing to last week), " +
                    "your average severity this week (green / amber / red), and your all-time total.\n\n" +
                    "Tap into the detail screen and you'll get:\n" +
                    "• The full weekly summary hero (this week vs last week, average per month)\n" +
                    "• Frequency charts — by month, week, day-of-week, and severity\n" +
                    "• The Migraine Timeline: scroll through every attack you've logged, with " +
                    "category-count chips showing how many triggers / prodromes / medicines / reliefs / " +
                    "activities / locations were linked to each one\n" +
                    "• Spider cards breaking down what was happening around each attack\n\n" +
                    "Use the card for a quick weekly trend read; tap in when you want to understand " +
                    "the pattern behind a specific attack.",
                    color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium
                )
            },
            containerColor = Color(0xFF1E0A2E)
        )
    }
}
