package com.migraineme

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What Worked — the single source of truth for how a treatment is described,
 * shared by the Insights hub preview, the What Worked page and the Full Report.
 *
 * A treatment does not have "an effectiveness". It has separately measured
 * outcomes — how far pain fell in POINTS, how much shorter attacks ran in
 * HOURS — plus what the user said about it. None of those is ever combined,
 * averaged or turned into a multiplier. The old "×1.7 shorter" headline was
 * ambiguous English (the engine meant 41% shorter) and, on 75% of live rows,
 * was the user's own relief rating rescaled and sold back to them as a
 * measurement.
 *
 * Every row carries one of exactly four verdicts, so rows are comparable at a
 * glance, and every row carries confidence dots — one lit dot for a weak
 * result is the honest answer, no dots at all is not.
 *
 * Field names come from scratchpad/effectiveness-contract.md,
 * lag_details.schema == "treatment_effectiveness/v2".
 */

// ── Model ───────────────────────────────────────────────────────────────────

enum class TreatmentVerdict { WORKS_WELL, SOME_HELP, NO_CLEAR_EFFECT, NOT_ENOUGH_YET }

/**
 * Where the verdict came from, in the unit it was measured in. One of these
 * becomes the row's single plain-English line.
 */
sealed interface TreatmentEvidence {
    data class PainDrop(val points: Float, val uses: Int) : TreatmentEvidence
    data class PainRise(val points: Float, val uses: Int) : TreatmentEvidence
    data class PainFlat(val uses: Int) : TreatmentEvidence
    data class SeverityMilder(val points: Float, val uses: Int) : TreatmentEvidence
    data object SeverityWorse : TreatmentEvidence
    data class SeverityFlat(val uses: Int) : TreatmentEvidence
    data class Shorter(val hours: Float, val uses: Int) : TreatmentEvidence
    data class Longer(val hours: Float, val uses: Int) : TreatmentEvidence
    data class DurationFlat(val uses: Int) : TreatmentEvidence
    /** [word] is the user's own scale: none / low / mild / high. */
    data class Rated(val word: String, val uses: Int) : TreatmentEvidence
    data class NotRated(val uses: Int) : TreatmentEvidence
}

/**
 * Early-vs-late split for one treatment. [cutoffMinutes] is the USER'S OWN
 * median delay before taking it, never a clinical threshold — the copy says so.
 */
data class TreatmentTiming(val cutoffMinutes: Int, val earlyPeak: Float, val latePeak: Float)

data class WhatWorkedRow(
    val name: String,
    val category: String,          // "medicine" | "relief"
    val verdict: TreatmentVerdict,
    val evidence: TreatmentEvidence,
    val dots: Int,                 // always 1..3
    val timing: TreatmentTiming?,
    val uses: Int,
    val measured: Boolean,         // a real test ran on measured data
    val pValue: Float?,
    val updatedAt: String,
)

// ── JSON helpers ────────────────────────────────────────────────────────────

private fun JsonObject.num(key: String): Float? =
    (this[key] as? JsonPrimitive)?.content?.toFloatOrNull()

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toFloatOrNull()?.toInt()

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

// ── Formatting ──────────────────────────────────────────────────────────────

/** "2" not "2.0", "1.4" stays "1.4". Points and hours both read better this way. */
internal fun trimNum(v: Float): String {
    val s = String.format("%.1f", kotlin.math.abs(v))
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

/** "2.9" / "3". A lift printed whole, never truncated to an int. */
internal fun trimLift(lift: Float): String = trimNum(lift)

/** "4h" / "1.5h". Hours only — duration is never a ratio. */
internal fun hoursText(hours: Float): String = "${trimNum(hours)}h"

/** "45 min" under an hour and a half, hours above it. */
internal fun cutoffText(minutes: Int): String =
    if (minutes < 90) tSync("%s min", minutes) else "${trimNum(minutes / 60f)}h"

/** "2 hours" / "1 hour" / "30 min". Plain English, for the pain-response line. */
internal fun horizonPhrase(minutes: Int): String = when {
    minutes % 60 != 0 -> tSync("%s min", minutes)
    minutes == 60 -> tSync("1 hour")
    else -> tSync("%s hours", minutes / 60)
}

/**
 * "−2.0 pts" / "+0.6 pts". Pain points with their sign kept: a rise is a real
 * finding and is never flipped or hidden. One decimal, because this is the
 * measured within-attack change and rounding it to a whole point would overstate
 * the precision the other way.
 */
internal fun painPointsText(points: Float): String {
    val sign = if (points > 0f) "+" else "−"
    return tSync("%s pts", sign + String.format("%.1f", kotlin.math.abs(points)))
}

/** The user's own relief scale. Index i is the label for score i. */
private val RELIEF_SCALE = listOf("none", "low", "mild", "high")

private fun reliefWord(avg0to3: Float, scale: List<String>): String =
    scale.getOrElse(Math.round(avg0to3).coerceIn(0, scale.size - 1)) { "none" }

// ── Building the rows ───────────────────────────────────────────────────────

/**
 * One row per medicine and relief the user logged. The user's own log is the
 * spine and the correlation row is a left join onto it, never a gate: a
 * treatment with no measurement still appears, with its rating or with
 * "Not enough yet". Nothing the user logged ever disappears — that was the
 * original bug and it does not come back.
 *
 * Rows written before the engine change carry no schema tag. They render in
 * rating form only, from the local log, because their stored severity figure is
 * a rescaled relief rating rather than a measurement.
 */
fun buildWhatWorkedRows(
    pool: List<Pair<InsightsViewModel.TreatmentItem, String>>,
    stats: List<EdgeFunctionsService.CorrelationStat>,
    timing: List<EdgeFunctionsService.TreatmentTimingStat>,
): List<WhatWorkedRow> {
    val statByName = stats
        .filter { it.factorType == "treatment" && it.symptomSegment == null }
        .associateBy { it.factorName.trim().lowercase() }
    val timingByName = timing.associateBy { it.treatmentName.trim().lowercase() }

    return pool.map { (item, category) ->
        val key = item.name.trim().lowercase()
        val stat = statByName[key]
        val t = timingByName[key]?.let { TreatmentTiming(it.cutoffMinutes, it.earlyAvgPeak, it.lateAvgPeak) }

        val v2 = stat?.takeIf { it.isTreatmentV2 }
        val tier = v2?.headlineTier
        val dots = stat?.confidenceDots ?: 1

        // Rating fallback, used by rating-tier rows, v1 rows and treatments with
        // no correlation row at all. The local log is authoritative for these.
        val ratingBlock = v2?.ratingBlock
        val avgRelief = ratingBlock?.num("avg_relief_0_3") ?: item.avgRelief
        val nRated = ratingBlock?.int("n_rated") ?: item.ratedCount
        val uses = ratingBlock?.int("n_uses") ?: stat?.sampleSize ?: item.count
        val scale = RELIEF_SCALE

        fun ratedRow(): Pair<TreatmentVerdict, TreatmentEvidence> =
            if (nRated <= 0) {
                TreatmentVerdict.NOT_ENOUGH_YET to TreatmentEvidence.NotRated(uses)
            } else {
                val verdict = when {
                    avgRelief >= 2.5f -> TreatmentVerdict.WORKS_WELL
                    avgRelief >= 1.5f -> TreatmentVerdict.SOME_HELP
                    else -> TreatmentVerdict.NO_CLEAR_EFFECT
                }
                verdict to TreatmentEvidence.Rated(reliefWord(avgRelief, scale), uses)
            }

        var measured = false
        val (verdict, evidence) = when {
            tier == "pain_response" && v2?.painResponse != null -> {
                measured = true
                val b = v2.painResponse!!
                val pts = b.num("median_change_points") ?: 0f
                val n = b.int("n_occurrences") ?: uses
                when (b.str("direction")) {
                    "down" -> (if (dots >= 2) TreatmentVerdict.WORKS_WELL else TreatmentVerdict.SOME_HELP) to
                        TreatmentEvidence.PainDrop(pts, n)
                    "up" -> TreatmentVerdict.NO_CLEAR_EFFECT to TreatmentEvidence.PainRise(pts, n)
                    else -> TreatmentVerdict.NO_CLEAR_EFFECT to TreatmentEvidence.PainFlat(n)
                }
            }
            tier == "severity_between" && v2?.severityBetween != null -> {
                measured = true
                val b = v2.severityBetween!!
                val pts = b.num("mean_change_points") ?: 0f
                val n = b.int("n_treated") ?: uses
                when (b.str("direction")) {
                    "milder" -> (if (dots >= 2) TreatmentVerdict.WORKS_WELL else TreatmentVerdict.SOME_HELP) to
                        TreatmentEvidence.SeverityMilder(pts, n)
                    // A "worse" result is the indication bias showing through —
                    // you reach for it on bad days. Never presented as harm.
                    "worse" -> TreatmentVerdict.NO_CLEAR_EFFECT to TreatmentEvidence.SeverityWorse
                    else -> TreatmentVerdict.NO_CLEAR_EFFECT to TreatmentEvidence.SeverityFlat(n)
                }
            }
            tier == "duration" && v2?.durationBlock != null -> {
                measured = true
                val b = v2.durationBlock!!
                val hrs = b.num("median_delta_hours") ?: 0f
                val n = b.int("n_treated") ?: uses
                when {
                    hrs < 0f -> (if (dots >= 2) TreatmentVerdict.WORKS_WELL else TreatmentVerdict.SOME_HELP) to
                        TreatmentEvidence.Shorter(hrs, n)
                    hrs > 0f -> TreatmentVerdict.NO_CLEAR_EFFECT to TreatmentEvidence.Longer(hrs, n)
                    else -> TreatmentVerdict.NO_CLEAR_EFFECT to TreatmentEvidence.DurationFlat(n)
                }
            }
            else -> ratedRow()
        }

        WhatWorkedRow(
            name = item.name,
            category = category,
            verdict = verdict,
            evidence = evidence,
            dots = dots,
            timing = t,
            uses = uses,
            measured = measured,
            pValue = stat?.pValue,
            updatedAt = stat?.updatedAt ?: "",
        )
    }.sortedWith(whatWorkedComparator)
}

/**
 * Best evidence first: measured rows before rated ones, then by p, then by how
 * much the treatment was actually used. lift_ratio is pinned to 1 on every
 * treatment row, so the old sort was sorting on a constant.
 */
internal val whatWorkedComparator: Comparator<WhatWorkedRow> =
    compareByDescending<WhatWorkedRow> { it.measured }
        .thenBy { it.pValue ?: 1f }
        .thenByDescending { it.uses }

// ── Copy ────────────────────────────────────────────────────────────────────

/** The four words. Same on every row, so rows are comparable. Kept short: this column is narrow. */
fun verdictText(v: TreatmentVerdict): String = when (v) {
    TreatmentVerdict.WORKS_WELL -> tSync("Works well")
    TreatmentVerdict.SOME_HELP -> tSync("Some help")
    TreatmentVerdict.NO_CLEAR_EFFECT -> tSync("No clear effect")
    TreatmentVerdict.NOT_ENOUGH_YET -> tSync("Not enough yet")
}

fun verdictColor(v: TreatmentVerdict): Color = when (v) {
    TreatmentVerdict.WORKS_WELL -> Color(0xFF81C784)
    TreatmentVerdict.SOME_HELP -> Color(0xFFFFB74D)
    else -> AppTheme.SubtleTextColor
}

/** One plain-English line saying where the verdict came from. Hours and pain points only. */
fun evidenceText(e: TreatmentEvidence): String = when (e) {
    is TreatmentEvidence.PainDrop ->
        tSync("Pain dropped about %1\$s points within 2 hours, over %2\$s uses", trimNum(e.points), e.uses)
    is TreatmentEvidence.PainRise ->
        tSync("Pain rose about %1\$s points within 2 hours, over %2\$s uses", trimNum(e.points), e.uses)
    is TreatmentEvidence.PainFlat ->
        tSync("Pain barely moved within 2 hours, over %s uses", e.uses)
    // "At least" is mandatory. People treat their worse attacks, which biases
    // this comparison against the treatment, so a measured benefit is a floor.
    is TreatmentEvidence.SeverityMilder ->
        tSync("At least about %1\$s points less pain than attacks you treated with nothing, over %2\$s uses",
            trimNum(e.points), e.uses)
    is TreatmentEvidence.SeverityWorse ->
        tSync("No measurable difference from attacks you treated with nothing — you tend to reach for it on worse attacks")
    is TreatmentEvidence.SeverityFlat ->
        tSync("About the same pain as attacks you treated with nothing, over %s uses", e.uses)
    is TreatmentEvidence.Shorter ->
        tSync("Attacks ended about %1\$s sooner, over %2\$s uses", hoursText(e.hours), e.uses)
    is TreatmentEvidence.Longer ->
        tSync("Attacks ran about %1\$s longer, over %2\$s uses", hoursText(e.hours), e.uses)
    is TreatmentEvidence.DurationFlat ->
        tSync("Attacks ran about as long as usual, over %s uses", e.uses)
    is TreatmentEvidence.Rated ->
        tSync("You rated it %1\$s relief, over %2\$s uses", reliefWordText(e.word), e.uses)
    is TreatmentEvidence.NotRated ->
        tSync("Used %s times, no relief rated", e.uses)
}

/** Same four words the user picked when logging, translated. */
internal fun reliefWordText(word: String): String = when (word) {
    "high" -> tSync("high")
    "mild" -> tSync("mild")
    "low" -> tSync("low")
    else -> tSync("no")
}

/**
 * Whether taking it sooner changed how bad the attack got. The cutoff is the
 * user's own median delay for this treatment, so the copy says "your median
 * delay" rather than implying a clinical window.
 */
fun timingText(t: TreatmentTiming): String =
    tSync("Taken within %1\$s (your median delay): peak %2\$s · later: peak %3\$s",
        cutoffText(t.cutoffMinutes), trimNum(t.earlyPeak), trimNum(t.latePeak))

// ── Card 1: across your attacks ─────────────────────────────────────────────

/**
 * One row per medicine and relief: verdict, the line it came from, the timing
 * line where timing exists, and dots. No multipliers, no raw 0-3 numbers.
 *
 * [canHide] turns on the per-row hide control and the hidden filter. The page
 * passes true; the Full Report passes false, because an export a clinician
 * reads should carry everything that was logged.
 */
@Composable
fun WhatWorkedCard(
    rows: List<WhatWorkedRow>,
    medicineCategories: Map<String, String> = emptyMap(),
    reliefIconKeys: Map<String, String> = emptyMap(),
    watermark: Boolean = true,
    showBlob: Boolean = true,
    canHide: Boolean = true,
) {
    // tSync() reads the language once, so hold a live subscription here to keep
    // the card honest across a language change without threading t() through
    // every formatter.
    val lang by LangPrefs.lang.collectAsState()
    var sortMode by remember { mutableStateOf("Best evidence") }

    // Same store the pre-verdict Treatments card wrote: file "insights_treatments",
    // key "hidden". Per-device by design, never synced. Rows used to be keyed
    // "$factorName|$factorB", so a single treatment was saved as "Aspirin|" —
    // both spellings are honoured on read so anything hidden before the redesign
    // is still hidden.
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("insights_treatments", Context.MODE_PRIVATE) }
    var hiddenKeys by remember { mutableStateOf(prefs.getStringSet("hidden", emptySet())!!.toSet()) }
    var showHidden by remember { mutableStateOf(false) }

    fun isHidden(row: WhatWorkedRow) = row.name in hiddenKeys || "${row.name}|" in hiddenKeys
    fun toggleHidden(row: WhatWorkedRow) {
        hiddenKeys = if (isHidden(row)) hiddenKeys - row.name - "${row.name}|"
                     else hiddenKeys + row.name
        prefs.edit().putStringSet("hidden", hiddenKeys).apply()
    }

    val sorted = remember(rows, sortMode, lang) {
        when (sortMode) {
            "Most used" -> rows.sortedByDescending { it.uses }
            "A to Z" -> rows.sortedBy { it.name.lowercase() }
            "Newest" -> rows.sortedByDescending { it.updatedAt }
            "Oldest" -> rows.sortedBy { it.updatedAt }
            else -> rows.sortedWith(whatWorkedComparator)
        }
    }
    val visible = if (canHide) sorted.filter { showHidden || !isHidden(it) } else sorted
    val hiddenCount = if (canHide) rows.count { isHidden(it) } else 0

    MaybeWatermarkCard(watermark = watermark, resId = R.drawable.brainy_shield, flipWatermark = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showBlob) {
                BrainyBlobIcon(R.drawable.brainy_shield_small)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(t("What Worked"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(t("How your attacks went when you used each one. From your own log, not a measure of the medicine."),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
            SortChipMenu(sortMode,
                listOf("Best evidence", "Most used", "A to Z", "Newest", "Oldest")) { sortMode = it }
        }
        Spacer(Modifier.height(8.dp))
        visible.forEach { row ->
            WhatWorkedTile(
                row = row,
                iconKey = reliefIconKeys[row.name.lowercase()],
                iconCategory = medicineCategories[row.name.lowercase()],
                dimmed = canHide && isHidden(row),
                onToggleHide = if (canHide) ({ toggleHidden(row) }) else null,
            )
            Spacer(Modifier.height(6.dp))
        }
        // The only route back to a hidden treatment, same wording and place as
        // the Activities & Locations card.
        if (hiddenCount > 0) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text(
                    if (showHidden) t("hide %s again", hiddenCount) else t("%s hidden · show", hiddenCount),
                    color = AppTheme.AccentPurple,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.clickable { showHidden = !showHidden }.padding(4.dp)
                )
            }
        }
    }
}

@Composable
private fun WhatWorkedTile(
    row: WhatWorkedRow,
    iconKey: String?,
    iconCategory: String?,
    dimmed: Boolean = false,
    onToggleHide: (() -> Unit)? = null,
) {
    val metaColor = Color(0xFF9C8BB0)
    val tileShape = RoundedCornerShape(18.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.55f else 1f)
            .clip(tileShape)
            .background(Color.White.copy(alpha = 0.035f))
            .border(1.dp, Color.White.copy(alpha = 0.05f), tileShape)
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrainyRowIcon(row.name, iconKey = iconKey, category = iconCategory, size = 20.dp)
            Text(
                prettyLabel(row.name),
                color = Color(0xFFF3EAFB),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            // The verdict leads this column on every row. The hide control sits
            // after it, small and quiet, exactly as on Activities & Locations.
            Text(
                verdictText(row.verdict),
                color = verdictColor(row.verdict),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.End,
            )
            onToggleHide?.let { toggle ->
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (dimmed) Icons.Outlined.Check else Icons.Outlined.Close,
                    contentDescription = if (dimmed) t("Unhide") else t("Hide"),
                    tint = AppTheme.SubtleTextColor.copy(alpha = if (dimmed) 0.9f else 0.45f),
                    modifier = Modifier.size(15.dp).clickable { toggle() }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(evidenceText(row.evidence), color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.labelSmall)
        // Timing is a sub-finding of the row above it, not a second finding, so
        // it is indented behind a rule and keyed TIMING rather than sitting flush
        // with the evidence line and reading as an equal claim.
        row.timing?.let {
            Spacer(Modifier.height(5.dp))
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight()
                    .background(AppTheme.AccentPurple.copy(alpha = 0.35f)))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(t("Timing").uppercase(), color = Color(0xFFC9BCDA),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                    Text(timingText(it), color = metaColor,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(t("confidence"), color = metaColor, style = MaterialTheme.typography.labelSmall)
            // Always drawn. One lit dot for a weak result is correct.
            ConfidenceDotsCount(row.dots, Color(0xFF81C784))
        }
    }
}

// ── Shared row furniture for cards 2, 3 and 4 ───────────────────────────────

internal val TreatmentTileShape = RoundedCornerShape(18.dp)
internal val TreatmentMetaColor = Color(0xFF9C8BB0)
internal val TreatmentGood = Color(0xFF81C784)
internal val TreatmentWarn = Color(0xFFFFB74D)

/** Same tile the What Worked rows sit in, so the four cards read as one page. */
@Composable
internal fun TreatmentTile(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(TreatmentTileShape)
            .background(Color.White.copy(alpha = 0.035f))
            .border(1.dp, Color.White.copy(alpha = 0.05f), TreatmentTileShape)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        content = content,
    )
}

/** "confidence ●●○" — the same footer on every row that carries a finding. */
@Composable
internal fun TreatmentConfidenceRow(dots: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(t("confidence"), color = TreatmentMetaColor, style = MaterialTheme.typography.labelSmall)
        ConfidenceDotsCount(dots, TreatmentGood)
    }
}

/** The trailing column: the verdict, or on card 2 the measured change. */
@Composable
internal fun TreatmentTrailingText(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        textAlign = TextAlign.End,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

// ── Card 2: within the attack ───────────────────────────────────────────────

/**
 * Pain response. What the pain did in the hours after a dose, measured inside
 * the same attack — the app's native equivalent of the clinical 2-hour endpoint.
 *
 * The change is the headline, in pain points with its sign kept. A treatment
 * after which pain consistently ROSE is shown as such, in the warning colour,
 * never hidden and never sign-flipped. The bar underneath is the proportion of
 * doses that moved the way the median did, so a big change measured on a split
 * decision does not read as a settled one.
 */
@Composable
fun PainResponseCard(
    rows: List<EdgeFunctionsService.IntradayResponseStat>,
    watermark: Boolean = false,
    showBlob: Boolean = false,
) {
    if (rows.isEmpty()) return
    val sorted = remember(rows) { rows.sortedByDescending { kotlin.math.abs(it.medianEffect) } }
    MaybeWatermarkCard(watermark = watermark, resId = R.drawable.brainy_shield, flipWatermark = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showBlob) {
                BrainyBlobIcon(R.drawable.brainy_shield_small)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(t("Pain response"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(t("What your pain did in the hours after a dose, inside the same attack. Needs pain logged over time."),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        sorted.forEach { row ->
            val rising = row.medianEffect > 0f
            val color = if (rising) TreatmentWarn else TreatmentGood
            // consistency is the fraction of doses that moved the median's way,
            // so it converts back to the count the user can check against.
            val hits = Math.round(row.consistency * row.n).coerceIn(0, row.n)
            TreatmentTile {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrainyRowIcon(row.label, size = 20.dp)
                    Text(prettyLabel(row.label), color = Color(0xFFF3EAFB),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    TreatmentTrailingText(painPointsText(row.medianEffect), color)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (rising) t("pain rose after these doses, in %1\$s of %2\$s", hits, row.n)
                    else t("by %1\$s, in %2\$s of %3\$s doses", horizonPhrase(row.horizonMinutes), hits, row.n),
                    color = AppTheme.BodyTextColor, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(5.dp))
                TreatmentConfidenceRow(dotsForP(row.pValue))
                Spacer(Modifier.height(7.dp))
                ProportionBar(row.consistency, color)
            }
            Spacer(Modifier.height(6.dp))
        }
        Text(t("Compared with your pain entries in the 3 hours before each log."),
            color = AppTheme.SubtleTextColor.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelSmall)
    }
}

/** How much of the evidence pulled the same way. Static — no animation, ever. */
@Composable
private fun ProportionBar(fraction: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White.copy(alpha = 0.07f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
    }
}

/** The dot thresholds the whole app uses. Floors at one, never zero. */
internal fun dotsForP(p: Float?): Int {
    val v = p ?: return 1
    return if (v < 0.01f) 3 else if (v < 0.05f) 2 else 1
}

// ── Card 3: pairs ───────────────────────────────────────────────────────────

/**
 * Pairs the user reaches for in the same attack.
 *
 * These rows are still the pre-v2 combination analysis (contract §7): their
 * lift_ratio and lag_details.duration_lift / severity_lift give every treatment
 * in an attack full credit for that attack's outcome, so none of them is
 * rendered — a verdict off that number would be the borrowed-credit multiplier
 * with a word in front of it. What is true here is how often the pair was
 * actually used together, and the dots carry the rest.
 *
 * `sample_size` on an interaction row is the user's TOTAL attack count, not the
 * pair's; the pair count is pct_migraine_windows of it. The old copy printed
 * sample_size as "used together in N of your attacks", which was the whole
 * history every time.
 */
@Composable
fun UsedTogetherCard(
    rows: List<EdgeFunctionsService.CorrelationStat>,
    watermark: Boolean = false,
    showBlob: Boolean = false,
) {
    if (rows.isEmpty()) return
    MaybeWatermarkCard(watermark = watermark, resId = R.drawable.brainy_shield, flipWatermark = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showBlob) {
                BrainyBlobIcon(R.drawable.brainy_shield_small)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(t("Used Together"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(t("Pairs you reach for in the same attack, and how those attacks went."),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        rows.take(6).forEach { stat ->
            val together = Math.round(stat.pctMigraineWindows / 100f * stat.sampleSize)
                .coerceIn(0, stat.sampleSize)
            TreatmentTile {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrainyRowIcon(stat.factorName, size = 20.dp)
                    Text("${prettyLabel(stat.factorName)}  +  ${prettyLabel(stat.factorB)}",
                        color = Color(0xFFF3EAFB),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (stat.isChronic) t("Used together in %1\$s of your %2\$s flare days", together, stat.sampleSize)
                    else t("Used together in %1\$s of your %2\$s attacks", together, stat.sampleSize),
                    color = AppTheme.BodyTextColor, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(5.dp))
                TreatmentConfidenceRow(stat.confidenceDots)
            }
            Spacer(Modifier.height(6.dp))
        }
        Text(t("How the pair itself changed an attack is not measured yet — this is how often you reached for both."),
            color = AppTheme.SubtleTextColor.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelSmall)
    }
}

// ── Card 4: symptom segments ────────────────────────────────────────────────

/** avg_relief_with / avg_relief_without, with the legacy column as the fallback. */
internal fun EdgeFunctionsService.CorrelationStat.segmentReliefWith(): Float =
    (lagDetails?.get("avg_relief_with") as? JsonPrimitive)?.content?.toFloatOrNull() ?: pctMigraineWindows
internal fun EdgeFunctionsService.CorrelationStat.segmentReliefWithout(): Float =
    (lagDetails?.get("avg_relief_without") as? JsonPrimitive)?.content?.toFloatOrNull() ?: pctControlWindows
internal fun EdgeFunctionsService.CorrelationStat.segmentNWith(): Int =
    (lagDetails?.get("n_with") as? JsonPrimitive)?.content?.toFloatOrNull()?.toInt() ?: 0
internal fun EdgeFunctionsService.CorrelationStat.segmentNWithout(): Int =
    (lagDetails?.get("n_without") as? JsonPrimitive)?.content?.toFloatOrNull()?.toInt() ?: 0

/** The user's own relief scale, as a word. Never the raw 0-3 number. */
private fun segmentReliefWord(v: Float): String =
    reliefWordText(RELIEF_SCALE.getOrElse(Math.round(v).coerceIn(0, 3)) { "none" })

/**
 * Whether a treatment lands differently depending on the symptoms present.
 *
 * One row per treatment — its strongest segment — rather than a treatment
 * heading with every segment stacked under it, so the card scans as a list of
 * findings like the other three. These rows compare two RATING averages and no
 * statistical test runs on them (contract §6), so lift_ratio is a constant here
 * and direction has to come from the two averages themselves.
 */
@Composable
fun WorksBestWhenCard(
    rows: List<EdgeFunctionsService.CorrelationStat>,
    watermark: Boolean = true,
    showBlob: Boolean = false,
) {
    if (rows.isEmpty()) return
    // Half a point apart on the 0-3 relief scale is the smallest gap worth
    // calling a difference.
    fun gap(s: EdgeFunctionsService.CorrelationStat) = s.segmentReliefWith() - s.segmentReliefWithout()
    val best = remember(rows) {
        rows.groupBy { it.factorName }
            .mapNotNull { (_, list) -> list.maxByOrNull { kotlin.math.abs(gap(it)) } }
            .sortedByDescending { kotlin.math.abs(gap(it)) }
    }
    var sortMode by remember { mutableStateOf("Strongest effect") }
    val sorted = remember(best, sortMode) {
        when (sortMode) {
            "A to Z" -> best.sortedBy { it.factorName.lowercase() }
            "Newest" -> best.sortedByDescending { it.updatedAt }
            "Oldest" -> best.sortedBy { it.updatedAt }
            else -> best
        }
    }
    MaybeWatermarkCard(watermark = watermark, resId = R.drawable.brainy_shield, flipWatermark = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showBlob) {
                BrainyBlobIcon(R.drawable.brainy_shield_small)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(t("Works Best When…"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(t("Whether a treatment lands differently depending on the symptoms you had."),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
            SortChipMenu(sortMode, listOf("Strongest effect", "A to Z", "Newest", "Oldest")) { sortMode = it }
        }
        Spacer(Modifier.height(8.dp))
        sorted.take(6).forEach { stat ->
            val symptom = prettyLabel(stat.symptomSegment)
            val d = gap(stat)
            val verdict = when {
                d > 0.5f -> t("Better with %s", symptom)
                d < -0.5f -> t("Better without %s", symptom)
                else -> t("Much the same")
            }
            val color = if (kotlin.math.abs(d) > 0.5f) TreatmentGood else AppTheme.SubtleTextColor
            TreatmentTile {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrainyRowIcon(stat.factorName, size = 20.dp)
                    Text(prettyLabel(stat.factorName), color = Color(0xFFF3EAFB),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    TreatmentTrailingText(verdict, color)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (kotlin.math.abs(d) > 0.5f)
                        t("%1\$s relief on %2\$s attacks with %3\$s, against %4\$s on %5\$s without",
                            segmentReliefWord(stat.segmentReliefWith()), stat.segmentNWith(), symptom,
                            segmentReliefWord(stat.segmentReliefWithout()), stat.segmentNWithout())
                    else t("no meaningful difference across your symptoms"),
                    color = AppTheme.BodyTextColor, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(5.dp))
                TreatmentConfidenceRow(stat.confidenceDots)
            }
            Spacer(Modifier.height(6.dp))
        }
        Text(t("Relief levels come from what you logged after using the treatment."),
            color = AppTheme.SubtleTextColor.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelSmall)
    }
}
