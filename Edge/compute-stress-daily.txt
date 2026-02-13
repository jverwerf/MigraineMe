// FILE: supabase/functions/compute-stress-index/index.ts
//
// Cron-triggered edge function that runs every 10 minutes.
// For users where it's 9:00–9:10 AM local time and stress_index_daily is enabled,
// computes a stress index (0–100) from HRV + resting HR data.
//
// Formula:
//   - Uses personal 14-day baseline for both HRV and resting HR
//   - Lower HRV than baseline = more stress
//   - Higher resting HR than baseline = more stress
//   - Combined into a 0–100 score where 0 = very relaxed, 100 = very stressed
//
// Runs AFTER wearable sync so hrv_daily and resting_hr_daily are populated.

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function requireEnv(name: string): string {
  const v = Deno.env.get(name);
  if (!v) throw new Error(`Missing env var: ${name}`);
  return v;
}

function addDaysIsoDate(isoDate: string, deltaDays: number): string {
  const [y, m, d] = isoDate.split("-").map((x) => Number(x));
  const dt = new Date(Date.UTC(y, m - 1, d, 12, 0, 0));
  dt.setUTCDate(dt.getUTCDate() + deltaDays);
  return dt.toISOString().slice(0, 10);
}

function getLocalTimeParts(timeZone: string, now = new Date()) {
  const fmt = new Intl.DateTimeFormat("en-GB", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });

  const parts = fmt.formatToParts(now);
  const get = (type: string) => parts.find((p) => p.type === type)?.value;

  const year = get("year");
  const month = get("month");
  const day = get("day");
  const hour = get("hour");
  const minute = get("minute");

  if (!year || !month || !day || !hour || !minute) {
    throw new Error(`Failed to compute local time parts for timezone=${timeZone}`);
  }

  const localDate = `${year}-${month}-${day}`;
  const hh = Number(hour);
  const mm = Number(minute);

  return { localDate, hh, mm };
}

async function resolveTimezone(
  supabase: ReturnType<typeof createClient>,
  userId: string,
  utcIsoDate: string
): Promise<string | null> {
  const candidates = [addDaysIsoDate(utcIsoDate, -1), utcIsoDate, addDaysIsoDate(utcIsoDate, +1)];

  for (const d of candidates) {
    const { data, error } = await supabase
      .from("user_location_daily")
      .select("timezone")
      .eq("user_id", userId)
      .eq("date", d)
      .not("timezone", "is", null)
      .order("updated_at", { ascending: false })
      .limit(1)
      .maybeSingle();

    if (error) continue;
    if (data?.timezone) return data.timezone;
  }

  return null;
}

async function mapLimit<T, R>(
  items: T[],
  limit: number,
  fn: (item: T) => Promise<R>
): Promise<R[]> {
  const results: R[] = new Array(items.length);
  let nextIndex = 0;

  async function worker() {
    while (true) {
      const i = nextIndex++;
      if (i >= items.length) return;
      results[i] = await fn(items[i]);
    }
  }

  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, () => worker()));
  return results;
}

/**
 * Compute stress index (0–100) from HRV and resting HR relative to personal baseline.
 *
 * HRV component (0–50):
 *   - HRV at or above baseline → 0 (no stress signal)
 *   - HRV 50% below baseline → 50 (max stress signal from HRV)
 *
 * Resting HR component (0–50):
 *   - RHR at or below baseline → 0 (no stress signal)
 *   - RHR 20 bpm above baseline → 50 (max stress signal from RHR)
 *
 * If no baseline (< 3 days of data), uses population defaults:
 *   - HRV baseline: 50 ms (moderate)
 *   - RHR baseline: 65 bpm (moderate)
 */
function computeStressScore(
  todayHrv: number | null,
  todayRhr: number | null,
  baselineHrv: number | null,
  baselineRhr: number | null,
  baselineDays: number
): number | null {
  // Need at least one input
  if (todayHrv == null && todayRhr == null) return null;

  const DEFAULT_HRV = 50;
  const DEFAULT_RHR = 65;
  const MIN_BASELINE_DAYS = 3;

  const refHrv = (baselineDays >= MIN_BASELINE_DAYS && baselineHrv != null) ? baselineHrv : DEFAULT_HRV;
  const refRhr = (baselineDays >= MIN_BASELINE_DAYS && baselineRhr != null) ? baselineRhr : DEFAULT_RHR;

  let hrvScore = 25; // neutral if missing
  let rhrScore = 25;

  if (todayHrv != null && refHrv > 0) {
    // How much lower is today's HRV vs baseline? Lower = more stress.
    const drop = (refHrv - todayHrv) / refHrv; // fraction, e.g. 0.3 = 30% drop
    // Clamp: -0.5 (way above baseline) to 0.5 (way below)
    const clamped = Math.max(-0.5, Math.min(0.5, drop));
    // Map: -0.5 → 0, 0 → 25, 0.5 → 50
    hrvScore = Math.round((clamped + 0.5) * 50);
  }

  if (todayRhr != null) {
    // How much higher is today's RHR vs baseline? Higher = more stress.
    const rise = todayRhr - refRhr; // bpm above baseline
    // Clamp: -10 (well below baseline) to 20 (well above)
    const clamped = Math.max(-10, Math.min(20, rise));
    // Map: -10 → 0, 0 → 16.7, 20 → 50
    rhrScore = Math.round(((clamped + 10) / 30) * 50);
  }

  return Math.max(0, Math.min(100, hrvScore + rhrScore));
}

serve(async (req) => {
  console.log("[compute-stress-index] start", new Date().toISOString());

  try {
    if (req.method !== "POST" && req.method !== "GET") {
      return jsonResponse({ ok: false, error: "Method not allowed" }, 405);
    }

    const supabaseUrl = requireEnv("SUPABASE_URL");
    const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");

    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false },
    });

    const nowUtc = new Date();
    const utcDate = nowUtc.toISOString().slice(0, 10);
    const BASELINE_DAYS = 14;

    // Get users with stress_index_daily enabled
    const { data: metricRows, error: metricErr } = await supabase
      .from("metric_settings")
      .select("user_id")
      .eq("metric", "stress_index_daily")
      .eq("enabled", true);

    if (metricErr) throw new Error(`metric_settings query failed: ${metricErr.message}`);

    const userIds = [...new Set((metricRows ?? []).map((r: any) => String(r.user_id)))];
    console.log(`[compute-stress-index] ${userIds.length} users with stress enabled`);

    if (userIds.length === 0) {
      return jsonResponse({ ok: true, processed: 0 });
    }

    const results = await mapLimit(userIds, 10, async (userId) => {
      try {
        // Resolve timezone
        const timezone = await resolveTimezone(supabase, userId, utcDate);
        if (!timezone) {
          return { userId, status: "no_timezone" };
        }

        // Check if 9:00–9:10 AM local time
        const { localDate, hh, mm } = getLocalTimeParts(timezone, nowUtc);
        if (hh !== 9 || mm > 10) {
          return { userId, status: "not_9am", localHour: hh };
        }

        const yesterday = addDaysIsoDate(localDate, -1);

        // Fetch yesterday's HRV
        const { data: hrvRow } = await supabase
          .from("hrv_daily")
          .select("value_rmssd_ms")
          .eq("user_id", userId)
          .eq("date", yesterday)
          .maybeSingle();

        // Fetch yesterday's resting HR
        const { data: rhrRow } = await supabase
          .from("resting_hr_daily")
          .select("value_bpm")
          .eq("user_id", userId)
          .eq("date", yesterday)
          .maybeSingle();

        const todayHrv = hrvRow?.value_rmssd_ms ?? null;
        const todayRhr = rhrRow?.value_bpm ?? null;

        if (todayHrv == null && todayRhr == null) {
          return { userId, status: "no_input_data", date: yesterday };
        }

        // Fetch baseline (past 14 days, excluding yesterday)
        const baselineStart = addDaysIsoDate(yesterday, -BASELINE_DAYS);
        const baselineEnd = addDaysIsoDate(yesterday, -1);

        const { data: hrvBaseline } = await supabase
          .from("hrv_daily")
          .select("value_rmssd_ms")
          .eq("user_id", userId)
          .gte("date", baselineStart)
          .lte("date", baselineEnd)
          .not("value_rmssd_ms", "is", null);

        const { data: rhrBaseline } = await supabase
          .from("resting_hr_daily")
          .select("value_bpm")
          .eq("user_id", userId)
          .gte("date", baselineStart)
          .lte("date", baselineEnd)
          .not("value_bpm", "is", null);

        const hrvValues = (hrvBaseline ?? []).map((r: any) => Number(r.value_rmssd_ms)).filter((v) => !isNaN(v));
        const rhrValues = (rhrBaseline ?? []).map((r: any) => Number(r.value_bpm)).filter((v) => !isNaN(v));

        const baselineDays = Math.max(hrvValues.length, rhrValues.length);
        const baselineHrv = hrvValues.length > 0
          ? hrvValues.reduce((a, b) => a + b, 0) / hrvValues.length
          : null;
        const baselineRhr = rhrValues.length > 0
          ? rhrValues.reduce((a, b) => a + b, 0) / rhrValues.length
          : null;

        const stressValue = computeStressScore(todayHrv, todayRhr, baselineHrv, baselineRhr, baselineDays);

        if (stressValue == null) {
          return { userId, status: "no_score", date: yesterday };
        }

        // Compute z-scores for storage
        const MIN_BASELINE_DAYS = 3;
        const refHrv = (baselineDays >= MIN_BASELINE_DAYS && baselineHrv != null) ? baselineHrv : 50;
        const refRhr = (baselineDays >= MIN_BASELINE_DAYS && baselineRhr != null) ? baselineRhr : 65;
        const hrvZ = (todayHrv != null && refHrv > 0) ? (refHrv - todayHrv) / refHrv : null;
        const rhrZ = todayRhr != null ? todayRhr - refRhr : null;

        // Upsert
        const { error: upsertErr } = await supabase
          .from("stress_index_daily")
          .upsert(
            {
              user_id: userId,
              date: yesterday,
              value: stressValue,
              hrv_z: hrvZ,
              rhr_z: rhrZ,
              baseline_window_days: baselineDays,
              computed_at: new Date().toISOString(),
            },
            { onConflict: "user_id,date" }
          );

        if (upsertErr) throw upsertErr;

        return {
          userId,
          status: "ok",
          date: yesterday,
          value: stressValue,
          inputs: { hrv: todayHrv, rhr: todayRhr },
          baseline: { hrv: baselineHrv, rhr: baselineRhr, days: baselineDays },
        };
      } catch (e: any) {
        return { userId, status: "error", error: String(e?.message ?? e) };
      }
    });

    const summary = {
      total: results.length,
      ok: results.filter((r: any) => r.status === "ok").length,
      not9am: results.filter((r: any) => r.status === "not_9am").length,
      noTimezone: results.filter((r: any) => r.status === "no_timezone").length,
      noInput: results.filter((r: any) => r.status === "no_input_data").length,
      errors: results.filter((r: any) => r.status === "error").length,
    };

    console.log("[compute-stress-index] done", summary);

    return jsonResponse({ ok: true, summary, results });

  } catch (e: any) {
    console.error("[compute-stress-index] error", e);
    return jsonResponse({ ok: false, error: String(e?.message ?? e) }, 500);
  }
});