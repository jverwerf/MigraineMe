package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Mid-attack forecast sheet, opened from the red IN AN ATTACK row on the
 * Monitor Migraines card. Everything shown is the similarity-weighted middle
 * of the user's OWN past attacks (similar-attacks edge function); no model,
 * no population data. Two hero states: before the weighted median it names an
 * end window; past it, it says the attack is running longer than its
 * lookalikes and shows the longest of them. Static UI throughout — no
 * animation, ever (motion is itself a symptom trigger for this audience).
 */

private val AttackRed = Color(0xFFE57373)
private val MetaColor = Color(0xFF9C8BB0)
private val Lilac = Color(0xFFC9A9E8)

private fun fmtHours(h: Float): String {
    val total = h.roundToInt()
    return if (total >= 24) {
        val d = total / 24
        val r = total % 24
        if (r == 0) "${d}d" else "${d}d ${r}h"
    } else "${total}h"
}

private fun parseStart(iso: String): ZonedDateTime? = runCatching {
    ZonedDateTime.parse(iso).withZoneSameInstant(ZoneId.systemDefault())
}.getOrNull()

/** "today"/"tomorrow"/weekday for the day the estimate lands on. */
private fun dayWord(target: ZonedDateTime, now: ZonedDateTime): String {
    val days = target.toLocalDate().toEpochDay() - now.toLocalDate().toEpochDay()
    return when (days) {
        0L -> tSync("today")
        1L -> tSync("tomorrow")
        else -> target.format(DateTimeFormatter.ofPattern("EEEE"))
    }
}

private fun clock(zdt: ZonedDateTime): String = zdt.format(DateTimeFormatter.ofPattern("HH:mm"))

private fun factorLabel(f: EdgeFunctionsService.SimilarAttacksFactor): String = when (f.kind) {
    "sleep" -> when (f.band) {
        "under6" -> tSync("Slept under 6h")
        "6to7" -> tSync("Slept 6-7h")
        "7to8" -> tSync("Slept 7-8h")
        else -> tSync("Slept over 8h")
    }
    "humidity" -> if (f.band == "high") tSync("High humidity") else tSync("Low humidity")
    "temp" -> if (f.band == "hot") tSync("Hot day") else tSync("Cold day")
    "aura" -> tSync("Aura")
    "med", "relief" ->
        if (f.band == "early") tSync("%s in the first 2h", prettyLabel(f.name)) else prettyLabel(f.name)
    else -> prettyLabel(f.name)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttackForecastSheet(
    forecast: EdgeFunctionsService.SimilarAttacksResponse,
    nowMs: Long,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1F0A2E),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            // ── header ──
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(AttackRed.copy(alpha = 0.16f))
                    .border(1.dp, AttackRed.copy(alpha = 0.34f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    t("In an attack").uppercase(appLocale()),
                    color = AttackRed,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold, letterSpacing = 0.09.em
                    ),
                )
            }
            val startZdt = forecast.startAt?.let { parseStart(it) }
            val elapsed = forecast.startAt?.let { attackElapsedRunningLabel(it, nowMs) }
            if (elapsed != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(elapsed, color = Color.White,
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(Modifier.width(8.dp))
                    Text(t("and running"), color = MetaColor,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp))
                }
            }
            startZdt?.let {
                Text(
                    t("started %s", it.format(DateTimeFormatter.ofPattern("EEE HH:mm"))),
                    color = MetaColor, style = MaterialTheme.typography.labelSmall,
                )
            }

            val status = forecast.status
            val duration = forecast.duration
            val median = duration?.median

            Spacer(Modifier.height(14.dp))
            SheetCard {
                when {
                    status != "ok" || median == null || startZdt == null -> {
                        Text(
                            t("Not enough logged attacks yet to estimate an end."),
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        )
                    }
                    (forecast.elapsedHours ?: 0f) < median -> {
                        val now = ZonedDateTime.now(ZoneId.systemDefault())
                        val end = startZdt.plusMinutes((median * 60).toLong())
                        Text(t("Usually over by"), color = MetaColor, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            t("%1\$s, around %2\$s", dayWord(end, now), clock(end)),
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        )
                        val p25 = duration.p25; val p75 = duration.p75
                        if (p25 != null && p75 != null) {
                            val a = startZdt.plusMinutes((p25 * 60).toLong())
                            val b = startZdt.plusMinutes((p75 * 60).toLong())
                            // Band ends can land on different days; bare clock
                            // times then read backwards ("22:44 – 13:44").
                            val band = if (a.toLocalDate() == b.toLocalDate()) {
                                "${clock(a)} – ${clock(b)}"
                            } else {
                                "${dayWord(a, now)} ${clock(a)} – ${dayWord(b, now)} ${clock(b)}"
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                band,
                                color = AppTheme.AccentPurple,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                        val remaining = median - (forecast.elapsedHours ?: 0f)
                        if (remaining > 0.5f) {
                            Spacer(Modifier.height(4.dp))
                            Text(t("about %s more", fmtHours(remaining)),
                                color = MetaColor, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    else -> {
                        Text(
                            t("Running longer than your similar attacks usually do"),
                            color = MetaColor, style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            t("They were usually over in %s", fmtHours(median)),
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        )
                        duration.longest?.let {
                            Spacer(Modifier.height(2.dp))
                            Text(t("longest ran %s", fmtHours(it)),
                                color = AppTheme.AccentPurple,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                        }
                    }
                }
                val sev = forecast.severity
                if (status == "ok" && sev?.median != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .border(0.dp, Color.Transparent)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t("Usually peaks at"), color = MetaColor, style = MaterialTheme.typography.labelSmall)
                            Text("${sev.median!!.roundToInt()} / 10", color = Color.White,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        }
                        sev.worst?.let { w ->
                            Column(Modifier.weight(1f)) {
                                Text(t("Worst of them hit"), color = MetaColor, style = MaterialTheme.typography.labelSmall)
                                Text("${w.roundToInt()} / 10", color = Color.White,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            }
                        }
                    }
                }
            }

            if (status == "ok" && duration?.median != null) {
                // ── duration spread ──
                SectionLabel(t("How similar migraines went"))
                SheetCard {
                    val longest = max(duration.longest ?: duration.median!!, forecast.elapsedHours ?: 0f)
                    DurationSpreadBar(
                        p25 = duration.p25, median = duration.median!!, p75 = duration.p75,
                        longest = longest, elapsed = forecast.elapsedHours,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text(t("usually %s", fmtHours(duration.median!!)), color = Color.White,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        Spacer(Modifier.weight(1f))
                        duration.longest?.let {
                            Text(t("longest %s", fmtHours(it)), color = MetaColor,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // ── what helped: attacks like this one, then you in general ──
                val helped = forecast.helped.filter { it.clear || it.reliefRated > 0 }
                if (helped.isNotEmpty()) {
                    SectionLabel(t("What helped attacks like this"))
                    helped.forEach { h -> HelpedRow(h) }
                }

                // ── what helps in general, across every logged attack ──
                val overall = forecast.helpedOverall.filter { it.clear || it.reliefRated > 0 }
                if (overall.isNotEmpty()) {
                    SectionLabel(t("What helps you in general"))
                    overall.forEach { h -> HelpedRow(h) }
                }

                // ── the match ──
                SectionLabel(t("From %s past attacks like this one", forecast.matchedCount))
                SheetCard {
                    forecast.sharedFactors.forEachIndexed { i, f ->
                        if (i > 0) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                factorLabel(f),
                                color = if (f.usable) Color.White else AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (f.usable) t("in %1\$s of %2\$s", f.count, f.of)
                                else t("in %1\$s, too few to use", f.count),
                                color = if (f.usable) AppTheme.AccentPurple else AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                modifier = Modifier.weight(0.8f),
                            )
                        }
                    }
                }
            }

            // ── footer ──
            Spacer(Modifier.height(14.dp))
            SheetCard {
                Text(
                    t("Every attack you have logged is checked against this one on everything you recorded: triggers, sleep, weather, symptoms, what you took and when. The more a past attack shares with this one, the harder it counts, and everything above is the weighted middle of those attacks. With too few similar attacks it shows nothing rather than a guess."),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
                Spacer(Modifier.height(8.dp))
                Text(
                    t("All of this comes from your own logged attacks, nothing else. It is NOT medical advice."),
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

/** Compact pill label for the Home in-progress card. Null without an estimate. */
internal fun forecastPillLabel(f: EdgeFunctionsService.SimilarAttacksResponse): String? {
    if (f.status != "ok") return null
    val median = f.duration?.median ?: return null
    val start = f.startAt?.let { parseStart(it) } ?: return null
    return if ((f.elapsedHours ?: 0f) < median) {
        val end = start.plusMinutes((median * 60).toLong())
        tSync("usually over by %1\$s %2\$s", dayWord(end, ZonedDateTime.now(ZoneId.systemDefault())), clock(end))
    } else {
        tSync("longer than usual")
    }
}

/** One-line read for the Monitor red row. Null while there is no estimate. */
internal fun forecastInlineLabel(f: EdgeFunctionsService.SimilarAttacksResponse): String? {
    if (f.status != "ok") return null
    val median = f.duration?.median ?: return null
    val start = f.startAt?.let { parseStart(it) } ?: return null
    return if ((f.elapsedHours ?: 0f) < median) {
        val end = start.plusMinutes((median * 60).toLong())
        tSync("usually over by %1\$s, around %2\$s",
            dayWord(end, ZonedDateTime.now(ZoneId.systemDefault())), clock(end))
    } else {
        tSync("running longer than your usual")
    }
}

/** "17h 9m" for the sheet header (the card's own label keeps its clock form). */
internal fun attackElapsedRunningLabel(startIso: String, nowMs: Long): String? {
    val startMs = runCatching {
        ZonedDateTime.parse(startIso).toInstant().toEpochMilli()
    }.getOrNull() ?: return null
    val totalMinutes = (nowMs - startMs) / 60_000L
    if (totalMinutes < 0) return null
    val h = totalMinutes / 60L
    val m = totalMinutes % 60L
    return "${h}h ${m}m"
}

@Composable
private fun HelpedRow(h: EdgeFunctionsService.SimilarAttacksHelped) {
    FactorRow(
        title = prettyLabel(h.name),
        sub = if (h.takenThisTime) t("taken this time") else t("not logged this time"),
        right = {
            val dur = h.deltaDurationH
            Column(horizontalAlignment = Alignment.End) {
                if (h.clear && dur != null) {
                    Text(
                        if (dur > 0) t("%s shorter", fmtHours(dur))
                        else t("%s longer", fmtHours(-dur)),
                        color = if (dur > 0) Color(0xFF81C784) else AttackRed,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    )
                }
                // The user's own relief ratings — within-attack, so the
                // mild-attacks-skip-meds skew can't touch it.
                if (h.reliefRated > 0) {
                    val reliefText = when {
                        h.reliefHigh > 0 -> t("high relief %1\$s of %2\$s times", h.reliefHigh, h.reliefRated)
                        h.reliefSome > 0 -> t("some relief %1\$s of %2\$s times", h.reliefSome, h.reliefRated)
                        else -> t("no relief in %s tries", h.reliefRated)
                    }
                    Text(
                        reliefText,
                        color = if (h.reliefHigh > 0) Color(0xFF81C784) else AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.End,
                    )
                }
            }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(
        text.uppercase(appLocale()),
        color = AppTheme.TitleColor,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.06.em),
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SheetCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        content = content,
    )
}

@Composable
private fun FactorRow(title: String, sub: String, right: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color.White.copy(alpha = 0.035f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = MetaColor, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(10.dp))
        right()
    }
}

/** Static spread bar: fill from p25 to p75, white tick at the median, and a
 *  labelled tick where the current attack sits. Scale runs 0..longest. */
@Composable
private fun DurationSpreadBar(p25: Float?, median: Float, p75: Float?, longest: Float, elapsed: Float?) {
    val scale = max(longest, 0.1f)
    Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.08f))
        )
        val lo = (p25 ?: median) / scale
        val hi = (p75 ?: median) / scale
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val w = maxWidth
            Box(
                Modifier
                    .offset(x = w * lo)
                    .width(w * max(hi - lo, 0.02f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Lilac.copy(alpha = 0.35f), AttackRed.copy(alpha = 0.45f))
                        )
                    )
            )
            // median tick
            Box(
                Modifier
                    .offset(x = w * (median / scale) - 1.dp, y = (-4).dp)
                    .width(3.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White)
            )
            // "you" tick
            elapsed?.let { e ->
                val ex = (e / scale).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .offset(x = w * ex - 1.dp, y = (-14).dp)
                        .width(2.dp)
                        .height(26.dp)
                        .background(Color.White.copy(alpha = 0.55f))
                )
            }
        }
    }
}
