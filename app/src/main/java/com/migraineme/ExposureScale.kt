package com.migraineme

/**
 * Kotlin counterpart of `supabase/functions/_shared/exposureScale.ts`.
 *
 * ONE word -> 0..3 conversion for the food-risk exposure columns. Keep the
 * rules here identical to the edge module; the whole class of bugs this fixes
 * came from every read site carrying its own hand-written copy of the scale.
 *
 * Live `nutrition_records` holds ALL of these spellings, because the USDA food
 * mapper writes the classifier's output straight through without normalising:
 *
 *   none, NONE, low, LOW, medium, MODERATE, high, HIGH
 *
 * `nutrition_daily.max_*_exposure` is normalised on write by
 * nutrition-daily-worker and only ever holds the lowercase words. The app
 * reads BOTH tables, so matching has to be case-insensitive and has to treat
 * `moderate` as a synonym for `medium` regardless of which table it came from.
 */
object ExposureScale {

    private val RANK: Map<String, Double> = mapOf(
        "none" to 0.0,
        "low" to 1.0,
        "medium" to 2.0,
        "moderate" to 2.0,
        "high" to 3.0,
    )

    /**
     * True when this metric key names a text severity column rather than a
     * number. Derived from the `_exposure` naming convention rather than an
     * enumerated list, exactly as the edge module does it, so a fifth exposure
     * needs no edit here. Both column shapes are covered:
     * `tyramine_exposure` and `max_tyramine_exposure`.
     */
    fun isExposureMetric(metric: String): Boolean = metric.endsWith("_exposure")

    /**
     * Convert a raw exposure cell to its 0..3 rank.
     *
     * null/blank scores 0 — no value logged means no exposure. An unrecognised
     * non-blank word also scores 0 here (the UI has no way to show "invalid"),
     * but callers that can report a data problem should use [rankOrNull].
     */
    fun rank(raw: String?): Double = rankOrNull(raw) ?: 0.0

    /**
     * Same scale, but returns null for a non-blank word that is NOT on the
     * scale, so a caller can tell "unrecognised value" apart from "none"
     * instead of silently scoring a data problem as zero.
     */
    fun rankOrNull(raw: String?): Double? {
        val word = raw?.trim()?.lowercase() ?: return 0.0
        if (word.isEmpty()) return 0.0
        return RANK[word]
    }

    /** Canonical lowercase spelling, for values written back or compared as text. */
    fun canonicalWord(raw: String?): String {
        val word = raw?.trim()?.lowercase() ?: return "none"
        if (word == "moderate") return "medium"
        return if (RANK.containsKey(word)) word else "none"
    }
}
