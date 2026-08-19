// supabase/functions/_shared/exposureScale.ts
//
// ONE definition of "this metric column holds a WORD, not a number" and ONE
// word -> 0..3 conversion. Every function that reads a food-risk exposure
// column imports from here:
//
//   trigger-worker              nutrition-daily-worker
//   prodrome-worker             backfill-all
//   hourly-trigger-dispatcher   backfilltrigger
//   hourly-prodrome-dispatcher  recalc-user-triggers
//
// Before this module existed each of those carried its own hand-written copy
// of the column list, and the copies had drifted. Every one of them named
// tyramine, alcohol and gluten and FORGOT histamine — so histamine skipped the
// word-to-number step, hit the numeric branch, produced Number("high") = NaN,
// and was discarded as "invalid_value". The Histamine trigger had never fired
// for anybody: 14 user-days of medium/high histamine across 9 users between
// 2026-05-23 and 2026-08-01 were silently dropped.
//
// So the membership test is DERIVED, not listed. Every food-risk column in
// both projects is named `<nutrient>_exposure` (nutrition_records) or
// `max_<nutrient>_exposure` (nutrition_daily), and no numeric column anywhere
// in either schema ends in `_exposure` — verified against information_schema
// on 2026-08-19. A fifth exposure added later is therefore handled the moment
// its trigger template lands, with no code change and no list to remember.

/**
 * True when `column` holds a text severity word rather than a number.
 *
 * Derived from the naming convention rather than an enumerated list, so a new
 * exposure column needs no edit here. Both shapes are covered:
 *   nutrition_records.histamine_exposure
 *   nutrition_daily.max_histamine_exposure
 */
export function isExposureColumn(column: string): boolean {
  return /_exposure$/.test(column);
}

/**
 * The word -> rank scale. Matching is case-insensitive and `moderate` is a
 * synonym for `medium`, because live data holds all of these spellings:
 *
 *   nutrition_records: none, NONE, low, LOW, medium, MODERATE, high, HIGH
 *   nutrition_daily:   none, low, medium, high   (normalised on write)
 *
 * The uppercase and MODERATE spellings come from the USDA food mapper, which
 * writes them straight through. Folding here means the whole read side copes
 * with either spelling regardless of what any writer does.
 */
const EXPOSURE_RANK: Record<string, number> = {
  none: 0,
  low: 1,
  medium: 2,
  moderate: 2,
  high: 3,
};

/**
 * Convert a raw exposure cell to its 0..3 rank.
 *
 * Returns 0 for null/absent — no record for the day means no exposure.
 * Returns null for a non-empty word that is NOT on the scale, so the caller
 * reports it as an invalid value instead of silently scoring it as "none";
 * a word we do not recognise is a data problem worth seeing, not a zero.
 */
export function exposureRank(raw: unknown): number | null {
  if (raw == null) return 0;
  if (typeof raw === "number") return raw;
  const word = String(raw).trim().toLowerCase();
  if (word === "") return 0;
  return EXPOSURE_RANK[word] ?? null;
}

/**
 * Same scale, for callers that are ranking words against each other rather
 * than producing a value to compare with a threshold (e.g. the daily
 * aggregator picking the day's worst exposure). Unknown words sort lowest.
 */
export function exposureRankOrZero(raw: unknown): number {
  return exposureRank(raw) ?? 0;
}

/** Canonical lowercase spelling of an exposure word, for values written back. */
export function canonicalExposureWord(raw: unknown): string {
  const word = String(raw ?? "").trim().toLowerCase();
  if (word === "moderate") return "medium";
  return word in EXPOSURE_RANK ? word : "none";
}
