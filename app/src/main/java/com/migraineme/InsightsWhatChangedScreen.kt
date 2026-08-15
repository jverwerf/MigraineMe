package com.migraineme

// What changed — full list behind the Insights hub card. Client-side mirror of
// the PDF report's What changed page (build-report-html trendsPage): attack-
// level shifts (count, severity, duration, symptoms per attack), occurrence
// counts of each trigger, prodrome, medicine, symptom and relief on attacks
// whose start falls in the last 30 days vs the 30 days before, and the daily
// habits (Lifestyle context) means over the same windows. Date windows only;
// unwanted items and reliefs grouped like the report, sorted by |delta|.
// Static, no motion.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

// Same chip palette as the Recommendations detail categories.
private val KIND_ORDER = listOf("trigger", "prodrome", "medicine", "symptom", "relief")
private val KIND_CHIP_COLORS = mapOf(
    "trigger" to Color(0xFFFFB74D),
    "prodrome" to Color(0xFFCE93D8),
    "medicine" to Color(0xFF4FC3F7),
    "symptom" to Color(0xFFFF8A65),
    "relief" to Color(0xFF81C784),
)
private val KIND_CHIP_LABELS = mapOf(
    "trigger" to "Triggers",
    "prodrome" to "Prodromes",
    "medicine" to "Medicines",
    "symptom" to "Symptoms",
    "relief" to "Reliefs",
)

@Composable
fun InsightsWhatChangedScreen(vm: InsightsViewModel = viewModel()) {
    val trends by vm.itemTrends.collectAsState()
    val attacks by vm.attackTrends.collectAsState()
    val habits by vm.habitTrends.collectAsState()
    val changed = remember(trends) { trends.filter { it.current != it.prior } }
    // Same sort + filter system as the Recommendations detail.
    var sortMode by remember { mutableStateOf("Biggest change") }
    var activeKinds by remember { mutableStateOf(setOf<String>()) }
    val filtered = remember(changed, activeKinds) {
        changed.filter { activeKinds.isEmpty() || activeKinds.contains(it.kind) }
    }
    val flatSorted = remember(filtered, sortMode) {
        when (sortMode) {
            "Rising" -> filtered.sortedByDescending { it.delta }
            "Falling" -> filtered.sortedBy { it.delta }
            "A to Z" -> filtered.sortedBy { prettyLabel(it.name).lowercase() }
            else -> emptyList()
        }
    }
    val maxDelta = remember(filtered) {
        (filtered.maxOfOrNull { abs(it.delta) } ?: 1).coerceAtLeast(1)
    }
    val medRising = remember(changed) { medicationRising(changed) }

    val scrollState = rememberScrollState()

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            // Your migraines: attack-level shifts over the same windows.
            // Missing sides count as zero; both-zero rows never arrive here.
            if (attacks.isNotEmpty()) {
                BaseCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrainyBlobIcon(R.drawable.brainy_risk_small)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(t("Your migraines"), color = Color.White,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text(t("Last 30 days vs the 30 before"), color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    attacks.forEach { t -> AttackTrendRow(t) }
                }
            }

            if (changed.isNotEmpty()) {
                Text(t("Occurrences on your attacks, last 30 days vs the 30 before."),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)

                BaseCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("Last 30 days vs the 30 before"), color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f))
                        SortChipMenu(sortMode, listOf("Biggest change", "Rising", "Falling", "A to Z")) { sortMode = it }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        changed.map { it.kind }.distinct().forEach { kind ->
                            val active = activeKinds.isEmpty() || activeKinds.contains(kind)
                            val color = KIND_CHIP_COLORS[kind] ?: AppTheme.AccentPurple
                            Text(
                                t(KIND_CHIP_LABELS[kind] ?: kind),
                                color = if (active) color else AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(1.dp, if (active) color else AppTheme.SubtleTextColor.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                                    .clickable {
                                        activeKinds = if (activeKinds.contains(kind)) activeKinds - kind
                                        else activeKinds + kind
                                    }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (sortMode == "Biggest change") {
                        // One section per kind, like the Recommendations detail.
                        var first = true
                        KIND_ORDER.forEach { kind ->
                            val rows = filtered.filter { it.kind == kind }
                                .sortedByDescending { abs(it.delta) }
                            if (rows.isNotEmpty()) {
                                if (!first) Spacer(Modifier.height(6.dp))
                                first = false
                                Text(t(KIND_CHIP_LABELS[kind] ?: kind),
                                    color = KIND_CHIP_COLORS[kind] ?: AppTheme.AccentPurple,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                                rows.forEach { t -> WhatChangedTrendRow(t, maxDelta) }
                                if (kind == "medicine" && medRising) {
                                    Text(t("Acute medication use rising — worth an overuse check."),
                                        color = TrendAmber, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    } else {
                        flatSorted.forEach { t -> WhatChangedTrendRow(t, maxDelta) }
                        if (medRising) {
                            Text(t("Acute medication use rising — worth an overuse check."),
                                color = TrendAmber, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Daily habits: the PDF's Lifestyle context, converted to the same
            // diverging bars as the items above. Renders nothing at all when
            // no metric has data in either window.
            if (habits.isNotEmpty()) {
                BrainyWatermarkCard(resId = R.drawable.brainy_risk, flipWatermark = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrainyBlobIcon(R.drawable.brainy_gardener_small)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(t("Daily habits"), color = Color.White,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text(t("Last 30 days vs the 30 before"), color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    habits.forEach { h -> DailyHabitRow(h) }
                }
            }
        }
    }
}

/** One attack-level row ("Your migraines"), same layout as the other bars. */
@Composable
private fun AttackTrendRow(t: InsightsViewModel.AttackTrend) {
    val spec = attackRowSpec(t)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(t(t.label), color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(122.dp))
        Spacer(Modifier.width(8.dp))
        DivergingTrendBar(fill = spec.fill, positive = spec.positive, color = spec.color,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(t(spec.caption), color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Attack rows read one way: more or worse migraines are bad, so up = red and
 * down = green on every row. Under 3% is "steady" with the empty track; fill
 * is |percent change| capped at 100%, the same mapping as the habit bars.
 */
private fun attackRowSpec(t: InsightsViewModel.AttackTrend): HabitRowSpec {
    fun v(x: Double) = when {
        t.isCount -> x.roundToInt().toString()
        t.isHours -> String.format(appLocale(), "%.1fh", x)
        else -> String.format(appLocale(), "%.1f", x)
    }
    val base = if (abs(t.prior) > 1e-9) abs(t.prior) else null
    val pct = if (base == null) (if (abs(t.current) > 1e-9) 100.0 else 0.0)
        else (t.current - t.prior) / base * 100.0
    if (abs(pct) < 3) return HabitRowSpec(null, false, AppTheme.SubtleTextColor, "steady")
    val up = pct > 0
    val delta = abs(t.current - t.prior)
    val deltaTxt = when {
        t.isCount -> delta.roundToInt().toString()
        t.isHours -> String.format(appLocale(), "%.1fh", delta)
        else -> String.format(appLocale(), "%.1f", delta)
    }
    return HabitRowSpec(
        fill = (abs(pct) / 100.0).toFloat().coerceIn(0.08f, 1f),
        positive = up,
        color = if (up) TrendRed else TrendGreen,
        caption = "${if (up) "+" else "−"}$deltaTxt (${v(t.prior)}→${v(t.current)})",
    )
}

// Clinically unambiguous directions, mirroring the PDF's lifestyleContext.
// Bedtime stays neutral (later is not automatically worse); caffeine guidance
// is "low or stable", so only a large swing in either direction is flagged.
private val HABIT_GOOD_UP =
    setOf("sleep_dur", "sleep_score", "hydration", "steps", "hrv", "mindfulness")
private val HABIT_GOOD_DOWN =
    setOf("stress", "rhr", "late_screen", "alcohol", "tyramine")

// Neutral slate for moves without a clinical direction (bedtime, small
// caffeine swings) — visible on the track without claiming good or bad.
private val HabitNeutralSlate = Color(0xFF90A4AE)

/** One habit row, laid out exactly like the item rows above: fixed-width
 *  label (spanning the items' icon+label width), the shared diverging bar,
 *  and a right caption. Steady and one-sided rows show the empty track. */
@Composable
private fun DailyHabitRow(h: InsightsViewModel.HabitTrend) {
    val spec = habitRowSpec(h)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrainyRowIcon(h.label, size = 18.dp)
        Text(t(h.label), color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(104.dp))
        Spacer(Modifier.width(8.dp))
        DivergingTrendBar(fill = spec.fill, positive = spec.positive, color = spec.color,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(t(spec.caption), color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall)
    }
}

/** Bar fill/direction/colour + caption for one habit row. */
private data class HabitRowSpec(
    val fill: Float?,       // null = no bar (steady / one-sided)
    val positive: Boolean,  // bar right of centre (metric went up)
    val color: Color,
    val caption: String,
)

/** Mean value for one side of the table: clock time for bedtime, 0-3 level
 *  for risk exposures, otherwise number + unit; "—" when that window is empty. */
private fun habitValue(h: InsightsViewModel.HabitTrend, v: Double?): String {
    if (v == null) return "—"
    if (h.isTime) {
        val hod = ((v % 24.0) + 24.0) % 24.0
        var hh = floor(hod).toInt()
        var mm = ((hod - hh) * 60.0).roundToInt()
        if (mm == 60) { hh = (hh + 1) % 24; mm = 0 }
        return String.format(appLocale(), "%02d:%02d", hh, mm)
    }
    if (h.unit == "risk") return String.format(appLocale(), "%.1f", v)
    val n = if (abs(v) >= 100) String.format(appLocale(), "%.0f", v)
        else String.format(appLocale(), "%.1f", v)
    return if (h.unit.isNotEmpty()) "$n ${h.unit}" else n
}

/**
 * The PDF's trend rules mapped onto the bar layout: under 3% (10 min for
 * bedtime) is "steady" with no bar; bedtime moves in ±minutes on a neutral
 * slate bar (an hour's shift fills the half track); caffeine goes red only
 * beyond ±30% either way; otherwise green when the move is clinically
 * favourable, red when it isn't, with the fill proportional to |percent
 * change| capped at 100%. One-sided rows show values only, no bar.
 */
private fun habitRowSpec(h: InsightsViewModel.HabitTrend): HabitRowSpec {
    val prior = h.prior
    val current = h.current
    if (prior == null || current == null) {
        return HabitRowSpec(null, false, AppTheme.SubtleTextColor,
            "${habitValue(h, prior)} → ${habitValue(h, current)}")
    }
    if (h.isTime) {
        val dMin = ((current - prior) * 60.0).roundToInt()
        if (abs(dMin) < 10) return HabitRowSpec(null, false, AppTheme.SubtleTextColor, "steady")
        val fill = (abs(dMin) / 60f).coerceIn(0.08f, 1f)
        return HabitRowSpec(fill, dMin > 0, HabitNeutralSlate,
            "${if (dMin > 0) "+" else "−"}${abs(dMin)}m (${habitValue(h, prior)}→${habitValue(h, current)})")
    }
    val base = if (abs(prior) > 1e-9) abs(prior) else null
    val pct = if (base == null) (if (abs(current) > 1e-9) 100.0 else 0.0)
        else (current - prior) / base * 100.0
    if (abs(pct) < 3) return HabitRowSpec(null, false, AppTheme.SubtleTextColor, "steady")
    val up = pct > 0
    val fill = (abs(pct) / 100.0).toFloat().coerceIn(0.08f, 1f)
    val color = when {
        h.key == "caffeine" -> if (abs(pct) > 30) TrendRed else HabitNeutralSlate
        h.key in HABIT_GOOD_UP -> if (up) TrendGreen else TrendRed
        h.key in HABIT_GOOD_DOWN -> if (up) TrendRed else TrendGreen
        else -> HabitNeutralSlate
    }
    val caption = "${if (up) "+" else "−"}${abs(pct).roundToInt()}% " +
        "(${habitValue(h, prior)}→${habitValue(h, current)})"
    return HabitRowSpec(fill, up, color, caption)
}

/**
 * One item row: icon, name, a diverging bar around a centre line (right = more
 * occurrences, left = fewer, width proportional to |delta|), and "+N (a→b)".
 */
@Composable
private fun WhatChangedTrendRow(t: InsightsViewModel.ItemTrend, maxDelta: Int) {
    val delta = t.delta
    val more = delta > 0
    val color = trendColor(t.kind, delta)
    val frac = (abs(delta).toFloat() / maxDelta).coerceIn(0.08f, 1f)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrainyRowIcon(t.name, size = 18.dp)
        Text(prettyLabel(t.name), color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(104.dp))
        Spacer(Modifier.width(8.dp))
        DivergingTrendBar(fill = frac, positive = more, color = color, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text("${if (more) "+" else "−"}${abs(delta)} (${t.prior}→${t.current})",
            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * The shared diverging bar: a track with a centre hairline, filled right of
 * centre for an increase and left for a decrease, width = `fill` of the half
 * track. `fill == null` draws the empty track (steady / one-sided rows).
 */
@Composable
private fun DivergingTrendBar(fill: Float?, positive: Boolean, color: Color, modifier: Modifier) {
    Box(modifier.height(9.dp)) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                if (fill != null && !positive) Box(
                    Modifier
                        .fillMaxWidth(fill)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(color)
                )
            }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                if (fill != null && positive) Box(
                    Modifier
                        .fillMaxWidth(fill)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(color)
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .width(1.dp)
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.18f))
        )
    }
}
