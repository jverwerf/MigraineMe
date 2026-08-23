// FILE: MigrainesMonitorCard.kt
// Monitor → Migraines preview card. Reuses the Activity-scoped
// InsightsViewModel so data is shared with the detail screen.
package com.migraineme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.res.painterResource
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
    val streak by vm.streakSummary.collectAsState()
    val thisMonth by vm.freeDaysThisMonth.collectAsState()
    var showInfo by remember { mutableStateOf(false) }

    // Trigger load if we have an access token; mirrors InsightsScreen pattern.
    LaunchedEffect(Unit) {
        val token = SessionStore.getValidAccessToken(ctx)
        if (!token.isNullOrBlank()) {
            vm.load(ctx, token)
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        MonitorBrainyCard(
            modifier = Modifier.fillMaxWidth().clickable { onClick() },
            resId = R.drawable.brainy_migraines,
            flipWatermark = true
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MonitorBlobIcon(resId = R.drawable.brainy_migraines_small)
                Spacer(Modifier.width(10.dp))
                Text(t("Migraines"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f))
                Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodyMedium)
            }

            val wsValue = ws
            if (wsValue != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val trendSuffix = when (wsValue.trend) {
                        "up" -> " ↑"
                        "down" -> " ↓"
                        else -> ""
                    }
                    MetricTile(
                        "${wsValue.thisWeekCount}$trendSuffix",
                        "this week (vs ${wsValue.lastWeekCount})",
                        if (wsValue.trend == "up") Color(0xFFE57373) else Color.White,
                        Modifier.weight(1f)
                    )
                    val avg = wsValue.thisWeekAvgSeverity
                    if (avg != null) {
                        val sevColor = if (avg >= 7f) Color(0xFFE57373)
                            else if (avg >= 4f) Color(0xFFFFB74D)
                            else Color(0xFF81C784)
                        MetricTile(String.format("%.1f", avg), "avg sev this week", sevColor, Modifier.weight(1f))
                    }
                    MetricTile("${wsValue.totalLogged}", "total all time", Color.White, Modifier.weight(1f))
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Text(t("Your weekly overview will appear after your first week of tracking."),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Start)
            }

            // Attack-free streak. Positive only: below 2 days the row simply isn't there,
            // so an attack day never shows a zero.
            // Free days this calendar month, beside the streak. They answer
            // different questions: the streak is "how long since the last one",
            // this is "how was this month". Practitioners work in months.
            // Spec: docs/day-classification-spec.md
            thisMonth?.let { mix ->
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(DayMixColors.Free.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .border(1.dp, DayMixColors.Free.copy(alpha = 0.30f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    Text(
                        "${mix.freeDays}",
                        color = DayMixColors.Free,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Column {
                        Text(
                            if (mix.isPartial) t("free days so far this month") else t("free days this month"),
                            color = AppTheme.BodyTextColor,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            if (mix.painDays > 0)
                                t("%1\$s migraine days, %2\$s pain days", mix.migraineDays, mix.painDays)
                            else t("%s migraine days", mix.migraineDays),
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            streak?.takeIf { it.streakDays >= 2 }?.let { s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(AppTheme.AccentPurple.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .border(1.dp, AppTheme.AccentPurple.copy(alpha = 0.30f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    Text(
                        "${s.streakDays}",
                        color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Column {
                        Text(t("days migraine-free"), color = AppTheme.BodyTextColor,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                        Text(
                            if (s.streakDays >= s.longestRunDaysThisYear) t("your longest run this year")
                            else t("longest run this year: %s", s.longestRunDaysThisYear),
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        IconButton(
            onClick = { showInfo = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 10.dp, y = (-14).dp)
                .size(34.dp)
        ) {
            Icon(Icons.Outlined.Info, contentDescription = t("About Migraines"),
                tint = AppTheme.SubtleTextColor, modifier = Modifier.size(20.dp))
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text(t("Got it"), color = AppTheme.AccentPurple) } },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.brainy_migraines_small), contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(t("About Migraines"), color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                }
            },
            text = {
                Text(
                    t("Your overview of logged migraine attacks. The card shows three things side-by-side: ") +
                    t("the number of attacks this week (with an up/down arrow comparing to last week), ") +
                    t("your average severity this week (green / amber / red), and your all-time total.\n\n") +
                    t("Tap into the detail screen and you'll get:\n") +
                    t("• The full weekly summary hero (this week vs last week, average per month)\n") +
                    t("• Frequency charts — by month, week, day-of-week, and severity\n") +
                    t("• The Migraine Timeline: scroll through every attack you've logged, with ") +
                    t("category-count chips showing how many triggers / prodromes / medicines / reliefs / ") +
                    t("activities / locations were linked to each one\n") +
                    t("• Spider cards breaking down what was happening around each attack\n\n") +
                    t("Use the card for a quick weekly trend read; tap in when you want to understand ") +
                    t("the pattern behind a specific attack."),
                    color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium
                )
            },
            containerColor = Color(0xFF1E0A2E)
        )
    }
}
