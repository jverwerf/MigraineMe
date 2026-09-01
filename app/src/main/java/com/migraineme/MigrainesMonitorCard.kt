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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun MigrainesMonitorCard(onClick: () -> Unit) {
    val ctx = LocalContext.current
    val owner = ctx as? ViewModelStoreOwner
    val vm: InsightsViewModel = if (owner != null) viewModel(owner) else viewModel()
    val ws by vm.weeklySummary.collectAsState()
    val streak by vm.streakSummary.collectAsState()
    val thisMonth by vm.freeDaysThisMonth.collectAsState()
    var showInfo by remember { mutableStateOf(false) }

    // Open attacks drive the "in an attack" row. Kept local to the card so the
    // Monitor hub doesn't have to know about migraine rows.
    val db = remember { SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) }
    var openAttacks by remember { mutableStateOf<List<SupabaseDbService.MigraineRow>>(emptyList()) }
    // Mid-attack forecast (similar-attacks edge fn). Loaded once an open
    // attack is known; the red row shows its one-line read and opens the
    // full AttackForecastSheet on tap.
    var forecast by remember { mutableStateOf<EdgeFunctionsService.SimilarAttacksResponse?>(null) }
    var showForecast by remember { mutableStateOf(false) }
    // Ticks the elapsed clock. A minute is the smallest unit shown, so half a
    // minute is fine and costs nothing while no attack is open.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

    // Trigger load if we have an access token; mirrors InsightsScreen pattern.
    LaunchedEffect(Unit) {
        val token = SessionStore.getValidAccessToken(ctx)
        if (!token.isNullOrBlank()) {
            vm.load(ctx, token)
            openAttacks = withContext(Dispatchers.IO) {
                runCatching { db.getOpenMigraines(token) }.getOrDefault(emptyList())
            }
            if (openAttacks.isNotEmpty()) {
                forecast = withContext(Dispatchers.IO) {
                    runCatching { EdgeFunctionsService().getSimilarAttacks(ctx) }.getOrNull()
                }
            }
        }
    }

    LaunchedEffect(openAttacks.isEmpty()) {
        if (openAttacks.isEmpty()) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    val fc = forecast
    if (showForecast && fc != null) {
        AttackForecastSheet(forecast = fc, nowMs = nowMs, onDismiss = { showForecast = false })
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
                // Insights explore is premium: show the padlock next to the arrow for
                // free users, same treatment as the Monitor "History" labels.
                val premiumAccess by PremiumManager.state.collectAsState()
                if (premiumAccess.access == PremiumAccess.NOT_ENTITLED) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = t("Premium"),
                        tint = AppTheme.AccentPurple,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodyMedium)
            }

            // Live "in an attack" row. Present only while a migraine is open;
            // shows how long it has been running so far (mm counted from
            // start_at). Mirrors the Home in-progress card's attack red.
            longestOpenAttack(openAttacks)?.let { open ->
                val elapsed = attackElapsedLabel(open.startAt, nowMs)
                if (elapsed != null) {
                    Spacer(Modifier.height(8.dp))
                    val inline = forecast?.let { forecastInlineLabel(it) }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .background(AttackRed.copy(alpha = 0.14f))
                            .border(1.dp, AttackRed.copy(alpha = 0.35f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .clickable { if (forecast != null) showForecast = true }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                t("In an attack").uppercase(appLocale()),
                                color = AttackRed,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold, letterSpacing = 0.09.em
                                )
                            )
                            Text(
                                elapsed,
                                color = Color.White,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.weight(1f)
                            )
                            if (openAttacks.size > 1) {
                                Text(
                                    t("%s open", openAttacks.size),
                                    color = AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        if (inline != null) {
                            Spacer(Modifier.height(5.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(AttackRed.copy(alpha = 0.22f)))
                            Spacer(Modifier.height(5.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    inline,
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text("\u203a", color = AttackRed, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
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
                        t("this week (vs %1\$s)", wsValue.lastWeekCount),
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
/** Attack red, the same value the Home in-progress card uses. */
private val AttackRed = Color(0xFFE57373)

/** Longest-running open attack — the one the card counts. Several can be open
 *  at once; the oldest is the one the user is most likely asking about. */
private fun longestOpenAttack(
    open: List<SupabaseDbService.MigraineRow>
): SupabaseDbService.MigraineRow? = open.minByOrNull { it.startAt }

/** "04:12" under a day, "1d 04:12" past it. Null when start_at can't be parsed
 *  or lies in the future, so the row simply isn't drawn. */
internal fun attackElapsedLabel(startIso: String, nowMs: Long): String? {
    val startMs = runCatching {
        java.time.ZonedDateTime.parse(startIso).toInstant().toEpochMilli()
    }.getOrNull() ?: return null
    val totalMinutes = (nowMs - startMs) / 60_000L
    if (totalMinutes < 0) return null
    val days = totalMinutes / 1440L
    val hours = (totalMinutes % 1440L) / 60L
    val minutes = totalMinutes % 60L
    val hm = String.format("%02d:%02d", hours, minutes)
    return if (days > 0) "${days}d $hm" else hm
}
