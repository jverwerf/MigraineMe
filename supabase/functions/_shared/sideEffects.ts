// Per-item side effects on medicine and relief logs (spec 2026-09-19).
//
// This file is IDENTICAL in the MigraineMe (Android) repo, the migraineme-ios
// repo and MeSeries: it is pure and knows nothing about app scoping.
//
// A log row carries BOTH fields:
//   side_effect_scale  NONE | SOFT | MODERATE | SEVERE, the overall level
//   side_effects       [{ label, severity }], severity SOFT | MODERATE | SEVERE
// Clients write the scale as the max of the items. Legacy rows have a scale and
// an empty array, so both are read: a use counts as "with side effects" when
// the scale is not NONE OR the array is non-empty.

export type SideEffectScale = "NONE" | "SOFT" | "MODERATE" | "SEVERE";

export type SideEffectLog = {
  side_effect_scale?: string | null;
  side_effects?: unknown;
};

export type SideEffectSummary = {
  n_uses: number;
  n_uses_with: number;
  max_scale: SideEffectScale;
  typical_scale: SideEffectScale;
  items: { label: string; n: number; typical_severity: SideEffectScale }[];
};

const SCALES: SideEffectScale[] = ["NONE", "SOFT", "MODERATE", "SEVERE"];

const rankOf = (raw: unknown): number => {
  const i = SCALES.indexOf(String(raw ?? "").trim().toUpperCase() as SideEffectScale);
  return i < 0 ? 0 : i;
};

/** The mode of a rank histogram (index 1..3). Ties go to the MORE severe. */
const modeRank = (counts: number[]): number => {
  let best = 0;
  for (let r = 1; r <= 3; r++) if (counts[r] > 0 && counts[r] >= (counts[best] ?? 0)) best = r;
  return best;
};

/** One row's ticked items: trimmed label, rank 1..3, one entry per label (the
 *  more severe wins when a label is somehow stored twice on the same log). */
export function sideEffectItems(raw: unknown): { label: string; rank: number }[] {
  if (!Array.isArray(raw)) return [];
  const byKey = new Map<string, { label: string; rank: number }>();
  for (const it of raw) {
    const label = String((it as { label?: unknown } | null)?.label ?? "").trim();
    if (!label) continue;
    // A ticked item always has a level; an unreadable one makes the lowest claim.
    const rank = rankOf((it as { severity?: unknown }).severity) || 1;
    const key = label.toLowerCase();
    const cur = byKey.get(key);
    if (!cur) byKey.set(key, { label, rank });
    else if (rank > cur.rank) cur.rank = rank;
  }
  return [...byKey.values()];
}

/** Summarise every logged use of ONE treatment. Always returns the full shape,
 *  zeros and an empty list included, so clients can rely on it. */
export function summariseSideEffects(logs: SideEffectLog[]): SideEffectSummary {
  const scaleCounts = [0, 0, 0, 0];
  const perItem = new Map<string, { label: string; n: number; counts: number[] }>();
  let nWith = 0, maxRank = 0;
  for (const log of logs) {
    const items = sideEffectItems(log.side_effects);
    // The items win over the stored scale; a legacy row only has the scale.
    const rank = items.length ? Math.max(...items.map((i) => i.rank)) : rankOf(log.side_effect_scale);
    if (rank === 0) continue;
    nWith++;
    scaleCounts[rank]++;
    if (rank > maxRank) maxRank = rank;
    for (const it of items) {
      const key = it.label.toLowerCase();
      const e = perItem.get(key) ?? { label: it.label, n: 0, counts: [0, 0, 0, 0] };
      e.n++;
      e.counts[it.rank]++;
      perItem.set(key, e);
    }
  }
  const items = [...perItem.values()]
    .sort((a, b) => b.n - a.n || (a.label < b.label ? -1 : a.label > b.label ? 1 : 0))
    .slice(0, 5)
    .map((e) => ({ label: e.label, n: e.n, typical_severity: SCALES[modeRank(e.counts)] }));
  return {
    n_uses: logs.length,
    n_uses_with: nWith,
    max_scale: SCALES[maxRank],
    typical_scale: SCALES[modeRank(scaleCounts)],
    items,
  };
}
