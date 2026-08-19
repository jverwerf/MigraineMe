// supabase/functions/_shared/riskAggregation.ts
//
// ONE implementation of how trigger/prodrome contributions become the risk
// gauge's score and its top_triggers list. Every function that writes
// risk_score_daily or risk_score_live imports from here:
//
//   risk-score-worker      — the live gauge, every 5 minutes
//   backfill-all           — login backfill, rewrites the last 14 days
//   recalc-risk-scores     — after the user edits gauge thresholds / decay
//   recalc-user-triggers   — after the user edits trigger predictions
//
// Before this module existed each of those four carried its own copy of the
// arithmetic, and only the worker's copy was correct. The other three summed
// raw event names, which double-counted the SAME underlying signal twice over:
//
//   1. Across group members. The seven sleep metrics all carry display_group
//      "Poor sleep" and describe ONE night, so they must contribute once, not
//      seven times. (Live example, user 45d651de on 2026-08-17: one night of
//      sleep scored 8 separate times for 51 points / 100% HIGH, where the
//      worker's rule gives 14 points / 78% MILD.)
//   2. Across days. The same trigger firing on day 0, day 1 and day 2 must
//      contribute its strongest single decayed weight, not the sum of all
//      three — the decay curve already expresses "how much is left of it".
//
// Grouping happens HERE, at scoring time, never when the trigger is logged:
// the individual trigger stays in the user's history because "REM sleep low"
// is more use to them than "Poor sleep".
//
// Keys are case-folded because the same signal can reach the triggers table
// under two spellings (live data holds both "menstruation" and "Menstruation"),
// and two spellings of one signal must not become two gauge contributions.

export const SEVERITY_ORDER = ["HIGH", "MILD", "LOW", "NONE"];

/** Rank of a severity word; lower is stronger. Unknown words rank last. */
export function severityRank(severity: string | null | undefined): number {
  const i = SEVERITY_ORDER.indexOf(String(severity ?? "NONE").toUpperCase());
  return i === -1 ? SEVERITY_ORDER.length : i;
}

/** Strongest of two severities (HIGH > MILD > LOW > NONE). */
export function strongerSeverity(a: string, b: string): string {
  return severityRank(b) < severityRank(a) ? b : a;
}

export interface RiskPoolRow {
  label: string;
  prediction_value?: string | null;
  category?: string | null;
  display_group?: string | null;
}

/**
 * Lookup tables derived from a user's trigger pool. All keys are lower-cased.
 *  - severity: label OR display_group → prediction value (group takes the
 *    strongest of its members, so one bad metric can still raise the group)
 *  - category: label OR display_group → pool category
 *  - group:    label → its display_group, raw casing (absent when ungrouped)
 *  - display:  canonical key → the label to show the user, raw casing
 */
export interface RiskMaps {
  severity: Record<string, string>;
  category: Record<string, string | null>;
  group: Record<string, string>;
  display: Record<string, string>;
}

export function buildRiskMaps(pool: RiskPoolRow[] | null | undefined): RiskMaps {
  const severity: Record<string, string> = {};
  const category: Record<string, string | null> = {};
  const group: Record<string, string> = {};
  const display: Record<string, string> = {};

  for (const t of pool ?? []) {
    if (!t || typeof t.label !== "string") continue;
    const key = t.label.toLowerCase();
    const sev = String(t.prediction_value || "NONE").toUpperCase();

    severity[key] = sev;
    category[key] = t.category ?? null;
    display[key] = t.label;

    if (t.display_group) {
      group[key] = t.display_group;
      const gk = t.display_group.toLowerCase();
      severity[gk] = severity[gk] ? strongerSeverity(severity[gk], sev) : sev;
      category[gk] = t.category ?? null;
      display[gk] = t.display_group;
    }
  }

  return { severity, category, group, display };
}

/** Severity for an event type, resolved case-insensitively. "NONE" if unknown. */
export function severityFor(name: string, maps: RiskMaps): string {
  return maps.severity[name.toLowerCase()] ?? "NONE";
}

/** Category for an event type, resolved case-insensitively. */
export function categoryFor(name: string, maps: RiskMaps): string | null {
  return maps.category[name.toLowerCase()] ?? null;
}

/**
 * The canonical key an event scores under: its display_group when it has one,
 * otherwise itself — always case-folded, so "Menstruation" and "menstruation"
 * are one contribution and not two.
 */
export function riskGroupKey(name: string, maps: RiskMaps): string {
  const lower = name.toLowerCase();
  const grouped = maps.group[lower];
  return (grouped ?? name).toLowerCase();
}

/** The label shown to the user for a canonical key. */
export function riskDisplayName(key: string, maps: RiskMaps, fallback: string): string {
  return maps.display[key] ?? fallback;
}

export interface RiskContribution {
  name: string;
  contribution: number;
  severity: string;
}

export interface RiskTriggerScore {
  name: string;
  score: number;
  severity: string;
  days_active?: number;
}

export interface RiskEventDate {
  name: string;
  eventDate: string;
}

/**
 * Distinct dates each canonical group appeared on, keyed by canonical key.
 * Members of one group share a count — three sleep metrics breaching on the
 * same night is one active day, not three.
 */
export function countDaysActive(
  events: RiskEventDate[] | null | undefined,
  maps: RiskMaps,
): Record<string, Set<string>> {
  const byKey: Record<string, Set<string>> = {};
  for (const e of events ?? []) {
    if (!e?.name || !e?.eventDate) continue;
    const key = riskGroupKey(e.name, maps);
    (byKey[key] ??= new Set()).add(e.eventDate);
  }
  return byKey;
}

/**
 * Collapse one day's raw contributions into the gauge's top_triggers list.
 *
 * Within a canonical group we keep the STRONGEST single contribution rather
 * than adding them up: several breached sleep metrics are one bad night, and
 * the same trigger seen on three days is one trigger still decaying, not three.
 * Severity is the strongest of the collapsed members.
 *
 * Returns the list sorted by score descending. The gauge score is the sum of
 * these rounded values, so that the number on the dial always equals the
 * numbers the user can read underneath it — use `sumRiskScores` for that.
 */
export function collapseRiskContributions(
  contributions: RiskContribution[] | null | undefined,
  maps: RiskMaps,
  daysActive?: Record<string, Set<string>>,
): RiskTriggerScore[] {
  const grouped: Record<string, { label: string; total: number; severity: string }> = {};

  for (const c of contributions ?? []) {
    if (!c?.name) continue;
    const key = riskGroupKey(c.name, maps);
    const label = riskDisplayName(key, maps, maps.group[c.name.toLowerCase()] ?? c.name);
    const entry = grouped[key];
    if (!entry) {
      grouped[key] = { label, total: c.contribution, severity: c.severity };
      continue;
    }
    entry.total = Math.max(entry.total, c.contribution);
    entry.severity = strongerSeverity(entry.severity, c.severity);
  }

  return Object.entries(grouped)
    .map(([key, { label, total, severity }]) => {
      const row: RiskTriggerScore = { name: label, score: Math.round(total), severity };
      if (daysActive) row.days_active = daysActive[key]?.size ?? 1;
      return row;
    })
    .sort((a, b) => b.score - a.score);
}

/** The gauge score: the sum of the rounded numbers the user can see. */
export function sumRiskScores(topTriggers: RiskTriggerScore[]): number {
  return topTriggers.reduce((sum, t) => sum + t.score, 0);
}
