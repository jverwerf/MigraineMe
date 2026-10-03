// Heart-rate threshold summary, shared by every server-side ingest path
// (garmin-webhook pushes, the sync-worker-garmin pull, sync-worker-oura and
// sync-worker-polar).
//
// Reduces heart-rate samples [[offsetSeconds, bpm], ...] (sorted by offset) to
// the minutes at or above `thr` and the longest continuous stretch at or above
// it. Same rule as the phones (HealthKit / Health Connect), so every source
// agrees:
//   - a sample covers the time until the next sample, capped at maxStepS;
//   - a stretch survives dips below the threshold of up to graceS in a row,
//     and ends on a gap in samples longer than maxStepS (watch off).
// The defaults (60 / 60 / 15) are the Garmin + phone rule. Sources that only
// sample every few minutes (Oura, Polar continuous heart rate) pass wider
// options from hrSpacingOpts() and store the result as an estimate.
export const HR_GRACE_S = 60;
export const HR_MAX_STEP_S = 60;
export const HR_DEFAULT_STEP_S = 15;

export type HrThresholdOpts = { maxStepS?: number; graceS?: number; defaultStepS?: number };

export function hrThresholdSummary(samples: number[][], thr: number, opts: HrThresholdOpts = {}) {
  const maxStepS = opts.maxStepS ?? HR_MAX_STEP_S;
  const graceS = opts.graceS ?? HR_GRACE_S;
  const defaultStepS = opts.defaultStepS ?? HR_DEFAULT_STEP_S;
  let above = 0, longest = 0;
  let runStart = -1, lastAbove = -1, lastAboveEnd = -1;
  for (let i = 0; i < samples.length; i++) {
    const [t, bpm] = samples[i];
    const next = i + 1 < samples.length ? samples[i + 1][0] : t + defaultStepS;
    const step = Math.min(Math.max(next - t, 0), maxStepS) || defaultStepS;
    const prevT = i > 0 ? samples[i - 1][0] : t;
    if (runStart >= 0 && t - prevT > maxStepS) {                // watch off: close the stretch
      longest = Math.max(longest, lastAboveEnd - runStart); runStart = -1;
    }
    if (bpm >= thr) {
      above += step;
      if (runStart < 0) runStart = t;
      lastAbove = t; lastAboveEnd = t + step;
    } else if (runStart >= 0 && t - lastAbove > graceS) {       // dipped too long: close the stretch
      longest = Math.max(longest, lastAboveEnd - runStart); runStart = -1;
    }
  }
  if (runStart >= 0) longest = Math.max(longest, lastAboveEnd - runStart);
  const r1 = (x: number) => Math.round(x / 60 * 10) / 10;
  return { minutesAbove: r1(above), longestRunMinutes: r1(longest) };
}

/** Garmin's `timeOffsetHeartRateSamples` map ({"15": 62, "30": 63, ...}) as sorted pairs. */
export function garminHrSamples(map: unknown): number[][] {
  if (!map || typeof map !== "object") return [];
  return Object.entries(map as Record<string, unknown>)
    .map(([off, bpm]) => [Number(off), Number(bpm)])
    .filter(([off, bpm]) => Number.isFinite(off) && Number.isFinite(bpm) && bpm > 0)
    .sort((x, y) => x[0] - y[0]);
}

/**
 * Timestamped samples [{ t: epochSeconds, bpm }] for one local day as sorted
 * [[offsetSeconds, bpm], ...] pairs. `dayStartS` / `dayEndS` are the epoch
 * seconds of local midnight and the next local midnight (see localDayStartEpochS).
 */
export function hrDaySamples(
  points: { t: number; bpm: number }[],
  dayStartS: number,
  dayEndS: number = dayStartS + 86400,
): number[][] {
  const byOffset = new Map<number, number>();
  for (const p of points) {
    const t = Number(p?.t), bpm = Number(p?.bpm);
    if (!Number.isFinite(t) || !Number.isFinite(bpm) || bpm <= 0) continue;
    if (t < dayStartS || t >= dayEndS) continue;
    byOffset.set(Math.round(t - dayStartS), bpm);
  }
  return Array.from(byOffset.entries()).sort((x, y) => x[0] - y[0]);
}

/** Epoch seconds of 00:00 local time on `date` (YYYY-MM-DD) in IANA zone `tz`. */
export function localDayStartEpochS(date: string, tz: string): number {
  const asUtcMs = Date.parse(date + "T00:00:00Z");
  const wallAsUtcMs = (ms: number) => {
    const parts = new Intl.DateTimeFormat("en-GB", {
      timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit",
      hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23",
    }).formatToParts(new Date(ms));
    const get = (type: string) => Number(parts.find((p) => p.type === type)?.value ?? 0);
    return Date.UTC(get("year"), get("month") - 1, get("day"), get("hour"), get("minute"), get("second"));
  };
  let ms = asUtcMs;
  try {
    // Two passes settle the offset on days the clocks change.
    for (let i = 0; i < 2; i++) ms = asUtcMs - (wallAsUtcMs(ms) - ms);
  } catch (_e) {
    ms = asUtcMs;                                               // unknown zone: treat as UTC
  }
  return Math.round(ms / 1000);
}

/**
 * Options for hrThresholdSummary sized to how densely the samples really are
 * spaced. Dense series (a sample at least every 60 s) keep the default rule and
 * are exact. When more than a tenth of the covered time comes from coarser
 * samples (Oura and Polar all-day heart rate arrive every ~5 minutes) the step
 * and grace widen to that spacing plus 10% (300 s -> 330 s) and the result is
 * an estimate: a 5-minute sample cannot prove a continuous stretch.
 */
export function hrSpacingOpts(samples: number[][]): { opts: HrThresholdOpts; isEstimate: boolean } {
  const HR_SPARSE_GAP_S = 900;                                  // longer than this is "not worn", not spacing
  let covered = 0, coarseCovered = 0;
  const coarse: number[] = [];
  for (let i = 1; i < samples.length; i++) {
    const gap = samples[i][0] - samples[i - 1][0];
    if (gap <= 0 || gap > HR_SPARSE_GAP_S) continue;
    covered += gap;
    if (gap > HR_MAX_STEP_S) { coarse.push(gap); coarseCovered += gap; }
  }
  if (samples.length === 1) {
    // A lone sample says nothing about spacing: never call it exact.
    return { opts: {}, isEstimate: true };
  }
  if (!coarse.length || coarseCovered <= covered * 0.1) return { opts: {}, isEstimate: false };
  coarse.sort((x, y) => x - y);
  const median = coarse[Math.floor(coarse.length / 2)];
  const step = Math.round(median * 1.1);
  return { opts: { maxStepS: step, graceS: step, defaultStepS: median }, isEstimate: true };
}

/** Rejects when `p` takes longer than `ms`, so an optional fetch can never stall a sync job. */
export function hrWithTimeout<T>(p: Promise<T>, ms: number, label = "request"): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error(`${label} timed out after ${ms} ms`)), ms);
  });
  return Promise.race([p, timeout]).finally(() => clearTimeout(timer));
}
