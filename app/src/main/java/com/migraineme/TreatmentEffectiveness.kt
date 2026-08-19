package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

/** "4h" / "1.5h". Hours only — duration is never a ratio. */
internal fun hoursText(hours: Float): String = "${trimNum(hours)}h"

/** "45 min" under an hour and a half, hours above it. */
internal fun cutoffText(minutes: Int): String =
    if (minutes < 90) tSync("%s min", minutes) else "${trimNum(minutes / 60f)}h"

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
        val nRated = ratingBlock?.int("n_rated") ?: item.count
        val uses = ratingBlock?.int("n_uses") ?: stat?.sampleSize ?: item.count
        val scale = RELIEF_SCALE

        fun ratedRow(): Pair<TreatmentVerdict, TreatmentEvidence> =
            if (nRated <= 0 || item.count <= 0) {
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
private fun reliefWordText(word: String): String = when (word) {
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
 */
@Composable
fun WhatWorkedCard(
    rows: List<WhatWorkedRow>,
    medicineCategories: Map<String, String> = emptyMap(),
    reliefIconKeys: Map<String, String> = emptyMap(),
    watermark: Boolean = true,
    showBlob: Boolean = true,
) {
    // tSync() reads the language once, so hold a live subscription here to keep
    // the card honest across a language change without threading t() through
    // every formatter.
    val lang by LangPrefs.lang.collectAsState()
    var sortMode by remember { mutableStateOf("Best evidence") }
    val sorted = remember(rows, sortMode, lang) {
        when (sortMode) {
            "Most used" -> rows.sortedByDescending { it.uses }
            "A to Z" -> rows.sortedBy { it.name.lowercase() }
            "Newest" -> rows.sortedByDescending { it.updatedAt }
            "Oldest" -> rows.sortedBy { it.updatedAt }
            else -> rows.sortedWith(whatWorkedComparator)
        }
    }

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
        sorted.forEach { row ->
            WhatWorkedTile(
                row = row,
                iconKey = reliefIconKeys[row.name.lowercase()],
                iconCategory = medicineCategories[row.name.lowercase()],
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun WhatWorkedTile(
    row: WhatWorkedRow,
    iconKey: String?,
    iconCategory: String?,
) {
    val metaColor = Color(0xFF9C8BB0)
    val tileShape = RoundedCornerShape(18.dp)

    Column(
        Modifier
            .fillMaxWidth()
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
            // The verdict is the only thing in this column, on every row.
            Text(
                verdictText(row.verdict),
                color = verdictColor(row.verdict),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.End,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(evidenceText(row.evidence), color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.labelSmall)
        row.timing?.let {
            Spacer(Modifier.height(3.dp))
            Text(timingText(it), color = metaColor, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(t("confidence"), color = metaColor, style = MaterialTheme.typography.labelSmall)
            // Always drawn. One lit dot for a weak result is correct.
            ConfidenceDotsCount(row.dots, Color(0xFF81C784))
        }
    }
}
