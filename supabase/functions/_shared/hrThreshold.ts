// Heart-rate threshold summary, shared by every Garmin ingest path
// (garmin-webhook pushes and the sync-worker-garmin pull).
//
// Reduces heart-rate samples [[offsetSeconds, bpm], ...] (sorted by offset) to
// the minutes at or above `thr` and the longest continuous stretch at or above
// it. Same rule as the phones (HealthKit / Health Connect), so every source
// agrees:
//   - a sample covers the time until the next sample, capped at HR_MAX_STEP_S;
//   - a stretch survives dips below the threshold of up to HR_GRACE_S in a row,
//     and ends on a gap in samples longer than HR_MAX_STEP_S (watch off).
export const HR_GRACE_S = 60;
export const HR_MAX_STEP_S = 60;
export const HR_DEFAULT_STEP_S = 15;

export function hrThresholdSummary(samples: number[][], thr: number) {
  let above = 0, longest = 0;
  let runStart = -1, lastAbove = -1, lastAboveEnd = -1;
  for (let i = 0; i < samples.length; i++) {
    const [t, bpm] = samples[i];
    const next = i + 1 < samples.length ? samples[i + 1][0] : t + HR_DEFAULT_STEP_S;
    const step = Math.min(Math.max(next - t, 0), HR_MAX_STEP_S) || HR_DEFAULT_STEP_S;
    const prevT = i > 0 ? samples[i - 1][0] : t;
    if (runStart >= 0 && t - prevT > HR_MAX_STEP_S) {           // watch off: close the stretch
      longest = Math.max(longest, lastAboveEnd - runStart); runStart = -1;
    }
    if (bpm >= thr) {
      above += step;
      if (runStart < 0) runStart = t;
      lastAbove = t; lastAboveEnd = t + step;
    } else if (runStart >= 0 && t - lastAbove > HR_GRACE_S) {   // dipped too long: close the stretch
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
