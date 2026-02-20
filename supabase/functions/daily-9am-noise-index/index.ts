// FILE: supabase/functions/noise-index-worker/index.ts
//
// Worker that processes jobs from sync_jobs_noise.
// Aggregates samples from ambient_noise_samples into ambient_noise_index_daily.
// Computes baseline from past 14 days and calculates value_index_pct.

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

function parseGmtOffsetToMinutes(gmt: string): number | null {
  const raw = (gmt ?? "").trim().toUpperCase();
  if (raw === "GMT" || raw === "UTC" || raw === "UT") return 0;

  const m = raw.match(/(?:GMT|UTC)?\s*([+-])\s*(\d{1,2})(?::?(\d{2}))?$/);
  if (!m) return null;

  const sign = m[1] === "-" ? -1 : 1;
  const hh = Number(m[2]);
  const mm = m[3] ? Number(m[3]) : 0;
  return sign * (hh * 60 + mm);
}

function getOffsetMinutesForInstant(timeZone: string, instant: Date): number {
  const fmt = new Intl.DateTimeFormat("en-US", {
    timeZone,
    timeZoneName: "shortOffset",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
  const parts = fmt.formatToParts(instant);
  const tzPart = parts.find((p) => p.type === "timeZoneName")?.value ?? "";
  const mins = parseGmtOffsetToMinutes(tzPart);
  if (mins == null) throw new Error(`Could not parse timezone offset "${tzPart}" for tz=${timeZone}`);
  return mins;
}

function addDaysIsoDate(isoDate: string, deltaDays: number): string {
  const [y, m, d] = isoDate.split("-").map((x) => Number(x));
  const dt = new Date(Date.UTC(y, m - 1, d, 12, 0, 0));
  dt.setUTCDate(dt.getUTCDate() + deltaDays);
  return dt.toISOString().slice(0, 10);
}

/**
 * Convert local date to UTC timestamp range for that entire day
 */
function getUtcRangeForLocalDate(
  timeZone: string,
  localDate: string
): { startUtc: string; endUtc: string } {
  const [y, m, d] = localDate.split("-").map(Number);

  // Start of day (00:00:00) in local time
  const startLocal = new Date(Date.UTC(y, m - 1, d, 0, 0, 0));
  const startOffset = getOffsetMinutesForInstant(timeZone, startLocal);
  const startUtc = new Date(startLocal.getTime() - startOffset * 60_000);

  // End of day (23:59:59.999) in local time
  const endLocal = new Date(Date.UTC(y, m - 1, d, 23, 59, 59, 999));
  const endOffset = getOffsetMinutesForInstant(timeZone, endLocal);
  const endUtc = new Date(endLocal.getTime() - endOffset * 60_000);

  return {
    startUtc: startUtc.toISOString(),
    endUtc: endUtc.toISOString(),
  };
}

/**
 * Calculate value_index_pct: how today compares to baseline
 * 0 = quietest (at or below baseline), 100 = loudest (significantly above baseline)
 */
function calculateIndexPct(
  dayMeanLmean: number,
  baselineMeanLmean: number | null,
  baselineDays: number
): number {
  // If no baseline or insufficient data, return 100 (assume it's notable)
  if (baselineMeanLmean == null || baselineDays < 3) {
    return 100;
  }

  // Calculate how much louder today is vs baseline
  // Using a simple ratio approach
  const diff = dayMeanLmean - baselineMeanLmean;
  
  if (diff <= 0) {
    // At or below baseline = quiet day
    return 0;
  }

  // Scale: if diff > 2.0 (log scale), consider it 100%
  // This is roughly 7x louder in linear scale
  const maxDiff = 2.0;
  const pct = Math.min(100, Math.round((diff / maxDiff) * 100));
  
  return pct;
}

serve(async (req) => {
  console.log("[noise-index-worker] start", new Date().toISOString());

  try {
    if (req.method !== "POST" && req.method !== "GET") {
      return jsonResponse({ ok: false, error: "Method not allowed" }, 405);
    }

    const supabaseUrl = requireEnv("SUPABASE_URL");
    const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");

    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false },
    });

    const nowIso = new Date().toISOString();
    const MAX_ATTEMPTS = 3;
    const BATCH_SIZE = 20;
    const BASELINE_DAYS = 14;

    // Fetch queued jobs
    const { data: jobs, error: jobsErr } = await supabase
      .from("sync_jobs_noise")
      .select("*")
      .eq("status", "queued")
      .lt("attempts", MAX_ATTEMPTS)
      .order("created_at", { ascending: true })
      .limit(BATCH_SIZE);

    if (jobsErr) throw new Error(`Failed to fetch jobs: ${jobsErr.message}`);

    if (!jobs || jobs.length === 0) {
      console.log("[noise-index-worker] no queued jobs");
      return jsonResponse({ ok: true, processed: 0 });
    }

    console.log(`[noise-index-worker] processing ${jobs.length} jobs`);

    const results: { jobId: string; status: string; error?: string; sampleCount?: number }[] = [];

    for (const job of jobs) {
      const jobId = job.id;
      const userId = job.user_id;
      const localDate = job.local_date;
      const timezone = job.timezone;

      try {
        // Mark as processing
        await supabase
          .from("sync_jobs_noise")
          .update({ status: "processing", attempts: job.attempts + 1, updated_at: nowIso })
          .eq("id", jobId);

        // Get UTC range for the local date
        const { startUtc, endUtc } = getUtcRangeForLocalDate(timezone, localDate);

        console.log(`[noise-index-worker] job=${jobId} user=${userId} date=${localDate} range=${startUtc} to ${endUtc}`);

        // Fetch noise samples for this user and date range
        const { data: samples, error: samplesErr } = await supabase
          .from("ambient_noise_samples")
          .select("l_mean, l_p90, l_max, duration_s")
          .eq("user_id", userId)
          .gte("start_ts", startUtc)
          .lte("start_ts", endUtc);

        if (samplesErr) {
          throw new Error(`Failed to fetch samples: ${samplesErr.message}`);
        }

        // Calculate day metrics (even if no samples, we record 0)
        let dayMeanLmean: number | null = null;
        let dayMinLmean: number | null = null;
        let dayMeanLp90: number | null = null;
        let dayMaxLmax: number | null = null;
        const samplesCount = samples?.length ?? 0;

        if (samples && samples.length > 0) {
          const lMeanValues = samples.map((s: any) => s.l_mean as number);
          const lP90Values = samples.filter((s: any) => s.l_p90 != null).map((s: any) => s.l_p90 as number);
          const lMaxValues = samples.filter((s: any) => s.l_max != null).map((s: any) => s.l_max as number);

          dayMeanLmean = lMeanValues.reduce((a, b) => a + b, 0) / lMeanValues.length;
          dayMinLmean = Math.min(...lMeanValues);
          dayMeanLp90 = lP90Values.length > 0
            ? lP90Values.reduce((a, b) => a + b, 0) / lP90Values.length
            : null;
          dayMaxLmax = lMaxValues.length > 0 ? Math.max(...lMaxValues) : null;
        }

        // Fetch baseline from past 14 days (excluding today)
        const baselineStartDate = addDaysIsoDate(localDate, -BASELINE_DAYS);
        const baselineEndDate = addDaysIsoDate(localDate, -1);

        const { data: baselineRows, error: baselineErr } = await supabase
          .from("ambient_noise_index_daily")
          .select("day_mean_lmean, day_mean_lp90, day_max_lmax")
          .eq("user_id", userId)
          .gte("date", baselineStartDate)
          .lte("date", baselineEndDate)
          .not("day_mean_lmean", "is", null);

        if (baselineErr) {
          console.warn(`[noise-index-worker] baseline query failed: ${baselineErr.message}`);
        }

        // Calculate baseline metrics
        let baselineDays = 0;
        let baselineMeanLmean: number | null = null;
        let baselineMeanLp90: number | null = null;
        let baselineMaxLmax: number | null = null;

        if (baselineRows && baselineRows.length > 0) {
          baselineDays = baselineRows.length;
          
          const blLmean = baselineRows.map((r: any) => Number(r.day_mean_lmean)).filter((v) => !isNaN(v));
          const blLp90 = baselineRows.filter((r: any) => r.day_mean_lp90 != null).map((r: any) => Number(r.day_mean_lp90));
          const blLmax = baselineRows.filter((r: any) => r.day_max_lmax != null).map((r: any) => Number(r.day_max_lmax));

          if (blLmean.length > 0) {
            baselineMeanLmean = blLmean.reduce((a, b) => a + b, 0) / blLmean.length;
          }
          if (blLp90.length > 0) {
            baselineMeanLp90 = blLp90.reduce((a, b) => a + b, 0) / blLp90.length;
          }
          if (blLmax.length > 0) {
            baselineMaxLmax = Math.max(...blLmax);
          }
        }

        // Calculate index percentage
        const valueIndexPct = dayMeanLmean != null
          ? calculateIndexPct(dayMeanLmean, baselineMeanLmean, baselineDays)
          : 0;

        // Upsert to ambient_noise_index_daily
        const { error: upsertErr } = await supabase
          .from("ambient_noise_index_daily")
          .upsert(
            {
              user_id: userId,
              date: localDate,
              source: "computed",
              value_index_pct: valueIndexPct,
              samples_count: samplesCount,
              day_mean_lmean: dayMeanLmean,
              day_min_lmean: dayMinLmean,
              day_mean_lp90: dayMeanLp90,
              day_max_lmax: dayMaxLmax,
              baseline_days: baselineDays,
              baseline_mean_lmean: baselineMeanLmean,
              baseline_mean_lp90: baselineMeanLp90,
              baseline_max_lmax: baselineMaxLmax,
              computed_at: nowIso,
              updated_at: nowIso,
            },
            { onConflict: "user_id,date" }
          );

        if (upsertErr) {
          throw new Error(`Failed to upsert daily index: ${upsertErr.message}`);
        }

        // Mark job as done
        await supabase
          .from("sync_jobs_noise")
          .update({ status: "done", updated_at: nowIso })
          .eq("id", jobId);

        results.push({ jobId, status: "done", sampleCount: samplesCount });
        console.log(`[noise-index-worker] job=${jobId} done, ${samplesCount} samples, index=${valueIndexPct}%`);

      } catch (e: any) {
        console.error(`[noise-index-worker] job=${jobId} error:`, e);

        // Mark as failed or back to queued for retry
        const newStatus = job.attempts + 1 >= MAX_ATTEMPTS ? "failed" : "queued";
        await supabase
          .from("sync_jobs_noise")
          .update({
            status: newStatus,
            last_error: String(e?.message ?? e),
            updated_at: nowIso,
          })
          .eq("id", jobId);

        results.push({ jobId, status: "error", error: String(e?.message ?? e) });
      }
    }

    const summary = {
      total: results.length,
      done: results.filter((r) => r.status === "done").length,
      errors: results.filter((r) => r.status === "error").length,
    };

    console.log("[noise-index-worker] done", summary);

    return jsonResponse({ ok: true, summary, results });

  } catch (e: any) {
    console.error("[noise-index-worker] error", e);
    return jsonResponse({ ok: false, error: String(e?.message ?? e) }, 500);
  }
});