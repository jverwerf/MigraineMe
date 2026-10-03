// supabase/functions/sync-worker-oura/index.ts
//
// Standalone sync worker for Oura Ring data.
// Processes "oura_daily" jobs from sync_jobs table.
// Does NOT touch any WHOOP or weather logic — completely independent.

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";
import { hrDaySamples, hrSpacingOpts, hrThresholdSummary, hrWithTimeout, localDayStartEpochS } from "../_shared/hrThreshold.ts";

// ══════════════════════════════════════════════════════════════════════
// Helpers
// ══════════════════════════════════════════════════════════════════════

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

function numOrNull(v: unknown): number | null {
  const n = typeof v === "number" ? v : typeof v === "string" ? Number(v) : NaN;
  return Number.isFinite(n) ? n : null;
}

function intOrNull(v: unknown): number | null {
  const n = numOrNull(v);
  if (n == null) return null;
  return Math.trunc(n);
}

function isExpiredSoon(expiresAtIso: string | null, nowUtc: Date): boolean {
  if (!expiresAtIso) return true;
  const exp = new Date(expiresAtIso).getTime();
  if (Number.isNaN(exp)) return true;
  return exp <= nowUtc.getTime() + 5 * 60_000;
}

// ══════════════════════════════════════════════════════════════════════
// Oura API helpers
// ══════════════════════════════════════════════════════════════════════

const OURA_BASE = "https://api.ouraring.com/v2/usercollection";

async function ouraFetch(path: string, accessToken: string, params?: Record<string, string>) {
  const url = new URL(OURA_BASE + path);
  if (params) {
    for (const [k, v] of Object.entries(params)) {
      url.searchParams.set(k, v);
    }
  }

  const res = await fetch(url.toString(), {
    headers: {
      Authorization: `Bearer ${accessToken}`,
      Accept: "application/json",
    },
  });

  const text = await res.text();
  if (!res.ok) {
    throw new Error(`Oura ${path} failed (${res.status}): ${text.slice(0, 500)}`);
  }

  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`Oura ${path} response not JSON: ${text.slice(0, 200)}`);
  }
}

// Oura token refresh
async function refreshOuraToken(
  refreshTokenRaw: string,
): Promise<{ access_token: string; refresh_token: string; expires_in: number }> {
  const clientId = requireEnv("OURA_CLIENT_ID");
  const clientSecret = requireEnv("OURA_CLIENT_SECRET");

  const refreshToken = String(refreshTokenRaw ?? "").replace(/\r?\n/g, "").replace(/\r/g, "").trim();
  if (!refreshToken) {
    throw new Error("Oura token refresh failed: refresh_token_empty");
  }

  const body = new URLSearchParams();
  body.set("grant_type", "refresh_token");
  body.set("refresh_token", refreshToken);
  body.set("client_id", clientId);
  body.set("client_secret", clientSecret);

  const res = await fetch("https://api.ouraring.com/oauth/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
  });

  const text = await res.text();
  if (!res.ok) {
    throw new Error(`Oura token refresh failed (${res.status}): ${text}`);
  }

  const json = JSON.parse(text);
  const access = String(json.access_token ?? "").trim();
  if (!access) {
    throw new Error("Oura token refresh: missing access_token in response");
  }

  const newRefresh = String(json.refresh_token ?? refreshToken).replace(/\r?\n/g, "").replace(/\r/g, "").trim();

  return {
    access_token: access,
    refresh_token: newRefresh,
    expires_in: typeof json.expires_in === "number" ? json.expires_in : 86400,
  };
}

// ══════════════════════════════════════════════════════════════════════
// DB helpers (same pattern as sync-worker)
// ══════════════════════════════════════════════════════════════════════

async function upsertDailyRow(
  supabase: ReturnType<typeof createClient>,
  table: string,
  row: Record<string, unknown>,
  onConflict = "user_id,date,source",
) {
  const { error } = await supabase.from(table).upsert(row, { onConflict });
  if (error) throw new Error(`Upsert to ${table} failed: ${error.message}`);
}

async function tryMarkMetricRan(
  supabase: ReturnType<typeof createClient>,
  userId: string,
  localDate: string,
  metric: string,
  source: string,
): Promise<boolean> {
  const { error } = await supabase.from("backend_metric_runs").insert({
    user_id: userId,
    local_date: localDate,
    metric,
    source,
  });

  if (!error) return true;
  const msg = (error.message ?? "").toLowerCase();
  if (msg.includes("duplicate") || msg.includes("unique")) return false;
  throw new Error(`backend_metric_runs insert failed: ${error.message}`);
}

// Concurrency limiter (same as sync-worker)
async function mapLimit<T, R>(items: T[], limit: number, fn: (item: T) => Promise<R>): Promise<R[]> {
  const results: R[] = [];
  let idx = 0;
  async function next(): Promise<void> {
    const i = idx++;
    if (i >= items.length) return;
    results[i] = await fn(items[i]);
    return next();
  }
  const workers = Array.from({ length: Math.min(limit, items.length) }, () => next());
  await Promise.all(workers);
  return results;
}

// ══════════════════════════════════════════════════════════════════════
// Types
// ══════════════════════════════════════════════════════════════════════

type SyncJobRow = {
  id: string;
  job_type: string;
  user_id: string;
  local_date: string;
  status: string;
  attempts: number | null;
  locked_at: string | null;
};

const MAX_PICK_ATTEMPTS = 10;
// Retry window: 9:00 - 9:45 local time (same as WHOOP sync-worker)
const RETRY_START_HOUR = 9;
const RETRY_END_HOUR = 9;
const RETRY_END_MINUTE = 45;

function isWithinRetryWindow(hh: number, mm: number): boolean {
  if (hh < RETRY_START_HOUR) return false;
  if (hh > RETRY_END_HOUR) return false;
  if (hh === RETRY_END_HOUR && mm > RETRY_END_MINUTE) return false;
  return true;
}

// ══════════════════════════════════════════════════════════════════════
// Main handler
// ══════════════════════════════════════════════════════════════════════

// edge_audit is best-effort telemetry: a logging failure must never fail the job.
// supabase-js v2 builders are thenable but have no .catch, so never chain one here.
async function logAudit(
  supabase: ReturnType<typeof createClient>,
  row: Record<string, unknown>,
) {
  try {
    await supabase.from("edge_audit").insert(row);
  } catch (_e) {
    // ignore
  }
}

serve(async (req) => {
  const supabaseUrl = requireEnv("SUPABASE_URL");
  const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");

  try {
    const bodyText = await req.text().catch(() => "{}");
    const bodyJson = JSON.parse(bodyText || "{}");
    const force = bodyJson?.force === true;
    const bodyUserId = typeof bodyJson?.user_id === "string" ? bodyJson.user_id : null;

    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false },
      global: { headers: { "X-Client-Info": "sync-worker-oura" } },
    });

    const nowUtc = new Date();
    const utcDate = nowUtc.toISOString().slice(0, 10);
    const staleCutoffIso = new Date(nowUtc.getTime() - 30 * 60_000).toISOString();

    // Pick oura_daily jobs
    let jobsQuery = supabase
      .from("sync_jobs")
      .select("id,job_type,user_id,local_date,status,attempts,locked_at")
      .eq("job_type", "oura_daily")
      .or(`status.eq.queued,and(status.eq.running,locked_at.lt.${staleCutoffIso})`)
      .order("local_date", { ascending: true })
      .order("created_at", { ascending: true })
      .limit(50);

    if (bodyUserId) {
      jobsQuery = jobsQuery.eq("user_id", bodyUserId);
    }

    const { data: jobs, error: jobsErr } = await jobsQuery;
    if (jobsErr) throw new Error(`sync_jobs select failed: ${jobsErr.message}`);

    const all = (jobs ?? []) as SyncJobRow[];
    const picked = all.filter((j) => force || (j.attempts ?? 0) < MAX_PICK_ATTEMPTS);

    if (!picked.length) {
      return jsonResponse({
        ok: true,
        summary: { picked: 0, forced: force, maxPickAttempts: MAX_PICK_ATTEMPTS, filteredOut: all.length },
      });
    }

    // Job state management helpers
    async function lockJob(job: SyncJobRow): Promise<boolean> {
      const nowIso = new Date().toISOString();
      if (job.status === "queued") {
        const { data, error } = await supabase
          .from("sync_jobs")
          .update({ status: "running", locked_at: nowIso, updated_at: nowIso })
          .eq("id", job.id)
          .eq("status", "queued")
          .select("id")
          .maybeSingle();
        return !!data && !error;
      }
      // Stale running job — reclaim lock
      const { data, error } = await supabase
        .from("sync_jobs")
        .update({ locked_at: nowIso, updated_at: nowIso })
        .eq("id", job.id)
        .lt("locked_at", staleCutoffIso)
        .select("id")
        .maybeSingle();
      return !!data && !error;
    }

    async function markJobDone(jobId: string, note: string | null) {
      const nowIso = new Date().toISOString();
      await supabase
        .from("sync_jobs")
        .update({ status: "done", last_error: note, finished_at: nowIso, locked_at: null, updated_at: nowIso })
        .eq("id", jobId);
    }

    async function markJobError(jobId: string, errMsg: string) {
      const nowIso = new Date().toISOString();
      await supabase
        .from("sync_jobs")
        .update({ status: "error", last_error: errMsg.slice(0, 1000), finished_at: nowIso, locked_at: null, updated_at: nowIso })
        .eq("id", jobId);
    }

    async function markJobQueued(jobId: string, note: string) {
      await supabase
        .from("sync_jobs")
        .update({ status: "queued", locked_at: null, last_error: note, updated_at: new Date().toISOString() })
        .eq("id", jobId);
    }

    async function incrementAttempts(jobId: string) {
      const rpc = await supabase.rpc("increment_sync_job_attempts", { job_id: jobId });
      if (!rpc.error) return;
      const { data } = await supabase.from("sync_jobs").select("attempts").eq("id", jobId).maybeSingle();
      const next = ((data?.attempts as number | null) ?? 0) + 1;
      await supabase
        .from("sync_jobs")
        .update({ attempts: next, updated_at: new Date().toISOString() })
        .eq("id", jobId);
    }

    // ══════════════════════════════════════════════════════════════════
    // Process a single oura_daily job
    // ══════════════════════════════════════════════════════════════════

    async function processJob(job: SyncJobRow) {
      const userId = job.user_id;
      const jobLocalDate = job.local_date;
      const metricResults: Record<string, unknown>[] = [];

      try {
        const locked = await lockJob(job);
        if (!locked) return { jobId: job.id, status: "lock_failed" };

        // Resolve timezone
        let tz = "UTC";
        const tzCandidates = [jobLocalDate];
        for (const d of tzCandidates) {
          const { data } = await supabase
            .from("user_location_daily")
            .select("timezone")
            .eq("user_id", userId)
            .eq("date", d)
            .not("timezone", "is", null)
            .order("updated_at", { ascending: false })
            .limit(1)
            .maybeSingle();
          if (data?.timezone) { tz = data.timezone; break; }
        }
        if (tz === "UTC") {
          // Try most recent location
          const { data } = await supabase
            .from("user_location_daily")
            .select("timezone")
            .eq("user_id", userId)
            .not("timezone", "is", null)
            .order("date", { ascending: false })
            .limit(1)
            .maybeSingle();
          if (data?.timezone) tz = data.timezone;
        }

        // Compute local time for retry window check
        const localParts = (() => {
          const fmt = new Intl.DateTimeFormat("en-GB", {
            timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit",
            hour: "2-digit", minute: "2-digit", hour12: false,
          });
          const parts = fmt.formatToParts(nowUtc);
          const get = (t: string) => parts.find((p) => p.type === t)?.value ?? "00";
          return {
            localDate: `${get("year")}-${get("month")}-${get("day")}`,
            hh: Number(get("hour")),
            mm: Number(get("minute")),
          };
        })();
        const { localDate: currentLocalDate, hh, mm } = localParts;
        const localTime = `${hh}:${String(mm).padStart(2, "0")}`;
        const isTodayJob = jobLocalDate === currentLocalDate;

        // Gate by retry window ONLY for today's jobs (unless forced by webhook)
        // Do NOT increment attempts for gating-only requeues.
        if (isTodayJob && !force) {
          if (!isWithinRetryWindow(hh, mm)) {
            const reason = (hh > RETRY_END_HOUR || (hh === RETRY_END_HOUR && mm > RETRY_END_MINUTE))
              ? "after_retry_window_waiting_for_oura_data"
              : "outside_retry_window";
            await markJobQueued(job.id, reason);
            return { jobId: job.id, userId, localDate: jobLocalDate, status: reason, timezone: tz, localTime };
          }
        }

        // From here on, we actually attempt Oura work. Increment attempts.
        const currentAttempt = (job.attempts ?? 0) + 1;
        await incrementAttempts(job.id);

        // Get Oura token
        const { data: ouraToken, error: tokErr } = await supabase
          .from("oura_tokens")
          .select("user_id,access_token,refresh_token,token_type,expires_at")
          .eq("user_id", userId)
          .maybeSingle();

        if (!ouraToken) {
          await markJobQueued(job.id, "oura_not_connected");
          return { jobId: job.id, userId, localDate: jobLocalDate, status: "oura_not_connected" };
        }

        // Get enabled metrics
        const { data: enabledMetricsRaw } = await supabase
          .from("metric_settings")
          .select("metric,enabled,preferred_source,allowed_sources")
          .eq("user_id", userId);

        const enabledMetrics = (enabledMetricsRaw ?? []) as {
          metric: string;
          enabled: boolean;
          preferred_source: string | null;
          allowed_sources: string[] | null;
        }[];

        const ouraEnabled = (metric: string) => {
          const m = enabledMetrics.find((x) => x.metric === metric);
          if (!m || !m.enabled) return false;
          const pref = (m.preferred_source ?? "").toLowerCase();
          const allowed = (m.allowed_sources ?? []).map((s) => String(s).toLowerCase());
          return pref === "oura" || allowed.includes("oura");
        };

        // Strict check for single-slot tables keyed on (user_id,date) with no
        // source column (stress_index_daily): only the user's PREFERRED source
        // may write there, otherwise sources clobber each other's values.
        const ouraPreferred = (metric: string) => {
          const m = enabledMetrics.find((x) => x.metric === metric);
          if (!m || !m.enabled) return false;
          return (m.preferred_source ?? "").toLowerCase() === "oura";
        };

        // Refresh token if needed
        try {
          if (isExpiredSoon(ouraToken.expires_at, nowUtc)) {
            console.log(`[sync-worker-oura] refreshing token for user=${userId}`);
            const refreshed = await refreshOuraToken(ouraToken.refresh_token);
            const expiresInSec = refreshed.expires_in || 86400;
            const expiresAt = new Date(nowUtc.getTime() + expiresInSec * 1000).toISOString();

            const { error: updErr } = await supabase
              .from("oura_tokens")
              .update({
                access_token: refreshed.access_token,
                refresh_token: refreshed.refresh_token,
                expires_at: expiresAt,
                updated_at: new Date().toISOString(),
              })
              .eq("user_id", userId);

            if (updErr) throw new Error(updErr.message);

            ouraToken.access_token = refreshed.access_token;
            ouraToken.refresh_token = refreshed.refresh_token;
            ouraToken.expires_at = expiresAt;

            await logAudit(supabase, {
              fn: "sync-worker-oura", user_id: userId, ok: true,
              stage: "oura_refresh_ok", message: `expires_in=${expiresInSec}s`,
            });
          }
        } catch (e) {
          const errMsg = (e as Error).message;
          metricResults.push({ source: "oura", status: "refresh_failed", error: errMsg });

          await logAudit(supabase, {
            fn: "sync-worker-oura", user_id: userId, ok: false,
            stage: "oura_refresh_failed", message: errMsg.slice(0, 500),
          });
        }

        if (!ouraToken.access_token) {
          await markJobError(job.id, "no_access_token_after_refresh");
          return { jobId: job.id, userId, status: "no_access_token" };
        }

        const token = ouraToken.access_token;
        // Oura's /sleep and /daily_activity return 0 rows when start_date == end_date,
        // even when records exist with day === that date. Widen the range by ±1 day
        // and filter client-side via records.find(r.day === jobLocalDate).
        const prevDay = new Date(new Date(jobLocalDate + "T00:00:00Z").getTime() - 86_400_000).toISOString().slice(0, 10);
        const nextDay = new Date(new Date(jobLocalDate + "T00:00:00Z").getTime() + 86_400_000).toISOString().slice(0, 10);
        const dateParams = { start_date: prevDay, end_date: nextDay };
        let anyDataFound = false;

        // ────────────────────────────────────────────────────────────
        // 1. DAILY SLEEP (score)
        // ────────────────────────────────────────────────────────────
        try {
          const json = await ouraFetch("/daily_sleep", token, dateParams);
          const records = (json?.data ?? []) as any[];
          const rec = records.find((r: any) => r.day === jobLocalDate) ?? null;

          if (rec) {
            anyDataFound = true;
            const sleepScore = intOrNull(rec.score);

            if (ouraEnabled("sleep_score_daily") && sleepScore != null) {
              const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "sleep_score_daily", "oura");
              if (proceed) {
                await upsertDailyRow(supabase, "sleep_score_daily", {
                  user_id: userId, date: jobLocalDate, value_pct: sleepScore,
                  source: "oura", source_measure_id: rec.id ?? null,
                  created_at: new Date().toISOString(),
                });
              }
              metricResults.push({ metric: "sleep_score_daily", status: proceed ? "written" : "already_done" });
            }
          }
        } catch (e) {
          metricResults.push({ source: "oura", status: "daily_sleep_fetch_failed", error: (e as Error).message });
        }

        // ────────────────────────────────────────────────────────────
        // 2. SLEEP (detailed — duration, stages, bedtime, wake, efficiency, disturbances)
        // ────────────────────────────────────────────────────────────
        try {
          const json = await ouraFetch("/sleep", token, dateParams);
          const records = (json?.data ?? []) as any[];

          // Pick the main sleep for this day:
          //   1) prefer a "long_sleep" record assigned to jobLocalDate
          //   2) else longest record assigned to jobLocalDate with >= 3h total_sleep_duration
          // Never fall back to short naps — that's how 0.1h durations got written.
          const MIN_MAIN_SLEEP_SECONDS = 3 * 3600;
          const dayRecs = records.filter((r: any) => r.day === jobLocalDate);
          const rec = dayRecs.find((r: any) => r.type === "long_sleep")
            ?? dayRecs
              .filter((r: any) => (numOrNull(r.total_sleep_duration) ?? 0) >= MIN_MAIN_SLEEP_SECONDS)
              .sort((a: any, b: any) => (numOrNull(b.total_sleep_duration) ?? 0) - (numOrNull(a.total_sleep_duration) ?? 0))[0]
            ?? null;

          if (rec) {
            anyDataFound = true;
            const sourceMeasureId = rec.id ?? null;

            // Duration (total_sleep_duration is in seconds)
            const durationSec = numOrNull(rec.total_sleep_duration);
            const durationHours = durationSec != null && durationSec > 0 ? durationSec / 3600.0 : null;

            if (ouraEnabled("sleep_duration_daily") && durationHours != null && durationHours > 0) {
              const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "sleep_duration_daily", "oura");
              if (proceed) {
                await upsertDailyRow(supabase, "sleep_duration_daily", {
                  user_id: userId, date: jobLocalDate, value_hours: durationHours,
                  source: "oura", source_measure_id: sourceMeasureId,
                  created_at: new Date().toISOString(),
                });
              }
              metricResults.push({ metric: "sleep_duration_daily", status: proceed ? "written" : "already_done" });
            }

            // Efficiency
            const efficiency = numOrNull(rec.efficiency);
            if (ouraEnabled("sleep_efficiency_daily") && efficiency != null) {
              const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "sleep_efficiency_daily", "oura");
              if (proceed) {
                await upsertDailyRow(supabase, "sleep_efficiency_daily", {
                  user_id: userId, date: jobLocalDate, value_pct: efficiency,
                  source: "oura", source_measure_id: sourceMeasureId,
                  created_at: new Date().toISOString(),
                });
              }
              metricResults.push({ metric: "sleep_efficiency_daily", status: proceed ? "written" : "already_done" });
            }

            // Disturbances (restless_periods)
            const disturbances = intOrNull(rec.restless_periods);
            if (ouraEnabled("sleep_disturbances_daily") && disturbances != null) {
              const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "sleep_disturbances_daily", "oura");
              if (proceed) {
                await upsertDailyRow(supabase, "sleep_disturbances_daily", {
                  user_id: userId, date: jobLocalDate, value_count: disturbances,
                  source: "oura", source_measure_id: sourceMeasureId,
                  created_at: new Date().toISOString(),
                });
              }
              metricResults.push({ metric: "sleep_disturbances_daily", status: proceed ? "written" : "already_done" });
            }

            // Sleep stages (seconds → hours)
            const deepSec = numOrNull(rec.deep_sleep_duration) ?? 0;
            const remSec = numOrNull(rec.rem_sleep_duration) ?? 0;
            const lightSec = numOrNull(rec.light_sleep_duration) ?? 0;

            if (ouraEnabled("sleep_stages_daily") && (deepSec > 0 || remSec > 0 || lightSec > 0)) {
              const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "sleep_stages_daily", "oura");
              if (proceed) {
                await upsertDailyRow(supabase, "sleep_stages_daily", {
                  user_id: userId, date: jobLocalDate, source: "oura",
                  source_measure_id: sourceMeasureId,
                  value_sws_hm: deepSec / 3600.0,
                  value_rem_hm: remSec / 3600.0,
                  value_light_hm: lightSec / 3600.0,
                  created_at: new Date().toISOString(),
                }, "user_id,date,source");
              }
              metricResults.push({ metric: "sleep_stages_daily", status: proceed ? "written" : "already_done" });
            }

            // Fell asleep time (bedtime_start → UTC ISO)
            const fellAsleepRaw = typeof rec.bedtime_start === "string" ? rec.bedtime_start : null;
            const fellAsleep = fellAsleepRaw ? new Date(fellAsleepRaw).toISOString() : null;
            if (ouraEnabled("fell_asleep_time_daily") && fellAsleep) {
              const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "fell_asleep_time_daily", "oura");
              if (proceed) {
                await upsertDailyRow(supabase, "fell_asleep_time_daily", {
                  user_id: userId, date: jobLocalDate, value_at: fellAsleep,
                  source: "oura", source_measure_id: sourceMeasureId,
                  created_at: new Date().toISOString(),
                });
              }
              metricResults.push({ metric: "fell_asleep_time_daily", status: proceed ? "written" : "already_done" });
            }

            // Woke up time (bedtime_end → UTC ISO)
            const wokeUpRaw = typeof rec.bedtime_end === "string" ? rec.bedtime_end : null;
            const wokeUp = wokeUpRaw ? new Date(wokeUpRaw).toISOString() : null;
            if (ouraEnabled("woke_up_time_daily") && wokeUp) {
              const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "woke_up_time_daily", "oura");
              if (proceed) {
                await upsertDailyRow(supabase, "woke_up_time_daily", {
                  user_id: userId, date: jobLocalDate, value_at: wokeUp,
                  source: "oura", source_measure_id: sourceMeasureId,
                  created_at: new Date().toISOString(),
                });
              }
              metricResults.push({ metric: "woke_up_time_daily", status: proceed ? "written" : "already_done" });
            }

            // HRV (average_hrv from sleep record)
            const hrvMs = numOrNull(rec.average_hrv);
            if (ouraEnabled("hrv_daily") && hrvMs != null) {
              const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "hrv_daily", "oura");
              if (proceed) {
                await upsertDailyRow(supabase, "hrv_daily", {
                  user_id: userId, date: jobLocalDate, value_rmssd_ms: hrvMs,
                  source: "oura", source_measure_id: sourceMeasureId,
                  created_at: new Date().toISOString(),
                });
              }
              metricResults.push({ metric: "hrv_daily", status: proceed ? "written" : "already_done" });
            }

            // Resting HR (lowest_heart_rate from sleep record)
            const restingHr = numOrNull(rec.lowest_heart_rate);
            if (ouraEnabled("resting_hr_daily") && restingHr != null) {
              const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "resting_hr_daily", "oura");
              if (proceed) {
                await upsertDailyRow(supabase, "resting_hr_daily", {
                  user_id: userId, date: jobLocalDate, value_bpm: restingHr,
                  source: "oura", source_measure_id: sourceMeasureId,
                  created_at: new Date().toISOString(),
                });
              }
              metricResults.push({ metric: "resting_hr_daily", status: proceed ? "written" : "already_done" });
            }

            metricResults.push({ source: "oura", status: "sleep_processed" });
          } else {
            metricResults.push({ source: "oura", status: "no_sleep_record" });
          }
        } catch (e) {
          metricResults.push({ source: "oura", status: "sleep_fetch_failed", error: (e as Error).message });
        }

        // ────────────────────────────────────────────────────────────
        // 3. DAILY READINESS (recovery score + skin temp)
        // ────────────────────────────────────────────────────────────
        try {
          const json = await ouraFetch("/daily_readiness", token, dateParams);
          const records = (json?.data ?? []) as any[];
          const rec = records.find((r: any) => r.day === jobLocalDate) ?? null;

          if (rec) {
            anyDataFound = true;
            const readinessScore = intOrNull(rec.score);

            if (ouraEnabled("recovery_score_daily") && readinessScore != null) {
              const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "recovery_score_daily", "oura");
              if (proceed) {
                await upsertDailyRow(supabase, "recovery_score_daily", {
                  user_id: userId, date: jobLocalDate, value_pct: readinessScore,
                  source: "oura", source_measure_id: rec.id ?? null,
                  created_at: new Date().toISOString(),
                });
              }
              metricResults.push({ metric: "recovery_score_daily", status: proceed ? "written" : "already_done" });
            }

            // Skin temp deviation
            const tempDev = numOrNull(rec.temperature_deviation);
            if (ouraEnabled("skin_temp_daily") && tempDev != null) {
              const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "skin_temp_daily", "oura");
              if (proceed) {
                await upsertDailyRow(supabase, "skin_temp_daily", {
                  user_id: userId, date: jobLocalDate, value_celsius: tempDev,
                  source: "oura", source_measure_id: rec.id ?? null,
                  created_at: new Date().toISOString(),
                });
              }
              metricResults.push({ metric: "skin_temp_daily", status: proceed ? "written" : "already_done" });
            }
          }
        } catch (e) {
          metricResults.push({ source: "oura", status: "readiness_fetch_failed", error: (e as Error).message });
        }

        // ────────────────────────────────────────────────────────────
        // 4. DAILY SPO2
        // ────────────────────────────────────────────────────────────
        try {
          const json = await ouraFetch("/daily_spo2", token, dateParams);
          const records = (json?.data ?? []) as any[];
          const rec = records.find((r: any) => r.day === jobLocalDate) ?? null;

          if (rec) {
            anyDataFound = true;
            // spo2_percentage is the average, or breathing_disturbance_index
            const spo2Avg = numOrNull(rec.spo2_percentage?.average ?? rec.spo2_percentage);

            // Oura returns 0 when no measurement happened (ring off charger/finger off).
            // Treat 0 as missing data — skip the write so the day stays empty.
            if (ouraEnabled("spo2_daily") && spo2Avg != null && spo2Avg > 0) {
              const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "spo2_daily", "oura");
              if (proceed) {
                await upsertDailyRow(supabase, "spo2_daily", {
                  user_id: userId, date: jobLocalDate, value_pct: spo2Avg,
                  source: "oura", source_measure_id: rec.id ?? null,
                  created_at: new Date().toISOString(),
                });
              }
              metricResults.push({ metric: "spo2_daily", status: proceed ? "written" : "already_done" });
            }
          }
        } catch (e) {
          metricResults.push({ source: "oura", status: "spo2_fetch_failed", error: (e as Error).message });
        }

        // ────────────────────────────────────────────────────────────
        // 5. DAILY ACTIVITY (steps)
        // ────────────────────────────────────────────────────────────
        try {
          const json = await ouraFetch("/daily_activity", token, dateParams);
          const records = (json?.data ?? []) as any[];
          const rec = records.find((r: any) => r.day === jobLocalDate) ?? null;

          if (rec) {
            anyDataFound = true;
            const steps = intOrNull(rec.steps);

            if (ouraEnabled("steps_daily") && steps != null && steps > 0) {
              const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "steps_daily", "oura");
              if (proceed) {
                // steps_daily has no unique constraint on user_id,date,source — delete+insert
                await supabase.from("steps_daily")
                  .delete()
                  .eq("user_id", userId)
                  .eq("date", jobLocalDate)
                  .eq("source", "oura");
                const { error: stepsErr } = await supabase.from("steps_daily").insert({
                  user_id: userId, date: jobLocalDate, value_count: steps,
                  source: "oura", source_measure_id: rec.id ?? null,
                  created_at: new Date().toISOString(),
                });
                if (stepsErr) throw new Error(`Insert to steps_daily failed: ${stepsErr.message}`);
              }
              metricResults.push({ metric: "steps_daily", status: proceed ? "written" : "already_done" });
            }
          }
        } catch (e) {
          metricResults.push({ source: "oura", status: "activity_fetch_failed", error: (e as Error).message });
        }

        // ────────────────────────────────────────────────────────────
        // 6. DAILY STRESS
        // ────────────────────────────────────────────────────────────
        try {
          const json = await ouraFetch("/daily_stress", token, dateParams);
          const records = (json?.data ?? []) as any[];
          const rec = records.find((r: any) => r.day === jobLocalDate) ?? null;

          if (rec) {
            anyDataFound = true;
            // stress_high is seconds of high stress; we store as a simple index
            const stressHigh = numOrNull(rec.stress_high);
            const recoveryHigh = numOrNull(rec.recovery_high);
            // Compute a simple stress index: ratio of stress to recovery (0-100 scale)
            let stressIndex: number | null = null;
            if (stressHigh != null && recoveryHigh != null && (stressHigh + recoveryHigh) > 0) {
              stressIndex = Math.round((stressHigh / (stressHigh + recoveryHigh)) * 100);
            }

            if (ouraPreferred("stress_index_daily") && stressIndex != null) {
              const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "stress_index_daily", "oura");
              if (proceed) {
                const { error: stressErr } = await supabase.from("stress_index_daily").upsert({
                  user_id: userId, date: jobLocalDate, value: stressIndex,
                  hrv_z: 0.0, rhr_z: 0.0, baseline_window_days: 0,
                  computed_at: new Date().toISOString(),
                }, { onConflict: "user_id,date" });
                if (stressErr) throw new Error(`Upsert to stress_index_daily failed: ${stressErr.message}`);
              }
              metricResults.push({ metric: "stress_index_daily", status: proceed ? "written" : "already_done" });
            }
          }
        } catch (e) {
          metricResults.push({ source: "oura", status: "stress_fetch_failed", error: (e as Error).message });
        }

        // ────────────────────────────────────────────────────────────
        // 7. RESPIRATORY RATE (from /sleep detailed — average_breath)
        // ────────────────────────────────────────────────────────────
        try {
          // We already fetched /sleep above for sleep metrics. Fetch again only if we
          // didn't get it (keeping it simple — the data is cached by Oura per request).
          const json = await ouraFetch("/sleep", token, dateParams);
          const records = (json?.data ?? []) as any[];
          // Pick the main sleep using the same rules as section 2 (no nap fallback)
          const MIN_MAIN_SLEEP_SECONDS_RESP = 3 * 3600;
          const dayRecords = records.filter((r: any) => r.day === jobLocalDate);
          const longSleep = dayRecords.find((r: any) => r.type === "long_sleep")
            ?? dayRecords
              .filter((r: any) => (numOrNull(r.total_sleep_duration) ?? 0) >= MIN_MAIN_SLEEP_SECONDS_RESP)
              .sort((a: any, b: any) => (numOrNull(b.total_sleep_duration) ?? 0) - (numOrNull(a.total_sleep_duration) ?? 0))[0]
            ?? null;

          if (longSleep) {
            const breathRate = numOrNull(longSleep.average_breath);

            if (ouraEnabled("respiratory_rate_daily") && breathRate != null && breathRate > 0) {
              const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "respiratory_rate_daily", "oura");
              if (proceed) {
                await upsertDailyRow(supabase, "respiratory_rate_daily", {
                  user_id: userId, date: jobLocalDate, value_bpm: breathRate,
                  source: "oura", source_measure_id: longSleep.id ?? null,
                  created_at: new Date().toISOString(),
                });
              }
              metricResults.push({ metric: "respiratory_rate_daily", status: proceed ? "written" : "already_done" });
            }
          }
        } catch (e) {
          metricResults.push({ source: "oura", status: "respiratory_fetch_failed", error: (e as Error).message });
        }

        // ────────────────────────────────────────────────────────────
        // 8. STRAIN / ACTIVITY SCORE (from /daily_activity)
        //    Oura active_calories (kcal) → kJ → strain_daily.value_kilojoule
        //    Oura active_calories (kcal) → strain_daily.value_kilojoule (convert)
        // ────────────────────────────────────────────────────────────
        try {
          // We already fetched /daily_activity above for steps. Re-fetch for the full record.
          const json = await ouraFetch("/daily_activity", token, dateParams);
          const records = (json?.data ?? []) as any[];
          const rec = records.find((r: any) => r.day === jobLocalDate) ?? null;

          if (rec) {
            const activityScore = numOrNull(rec.score);
            const activeCals = numOrNull(rec.active_calories);
            // Convert kcal to kJ (1 kcal = 4.184 kJ)
            const activeKj = activeCals != null ? Math.round(activeCals * 4.184) : null;

            if (ouraEnabled("strain_daily") && activityScore != null) {
              const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "strain_daily", "oura");
              if (proceed) {
                await upsertDailyRow(supabase, "strain_daily", {
                  user_id: userId, date: jobLocalDate,
                  value_kilojoule: activeKj,
                  avg_heart_rate: null,
                  max_heart_rate: null,
                  source: "oura", source_measure_id: rec.id ?? null,
                  created_at: new Date().toISOString(),
                });
              }
              metricResults.push({ metric: "strain_daily", status: proceed ? "written" : "already_done" });
            }
          }
        } catch (e) {
          metricResults.push({ source: "oura", status: "strain_fetch_failed", error: (e as Error).message });
        }

        // ────────────────────────────────────────────────────────────
        // 9. WORKOUTS → time_in_high_hr_zones_daily
        //    Oura workouts have: activity, calories, intensity, start/end, duration
        //    No HR zone breakdown, so we map:
        //      duration → value_minutes
        //      intensity "hard" → zone_five, "moderate" → zone_three, "easy" → zone_one
        //      activity → activity_type
        // ────────────────────────────────────────────────────────────
        try {
          const json = await ouraFetch("/workout", token, dateParams);
          const records = (json?.data ?? []) as any[];
          const dayWorkouts = records.filter((r: any) => r.day === jobLocalDate);

          for (const workout of dayWorkouts) {
            anyDataFound = true;
            // Same gate as sync-worker (WHOOP) and sync-worker-polar: workouts
            // feed time_in_high_hr_zones_daily + activities only when enabled.
            if (!ouraEnabled("time_in_high_hr_zones_daily")) continue;
            const startDt = workout.start_datetime ?? null;
            const endDt = workout.end_datetime ?? null;
            let durationMin = 0;
            if (startDt && endDt) {
              durationMin = Math.round((new Date(endDt).getTime() - new Date(startDt).getTime()) / 60000);
            }
            if (durationMin <= 0) continue;

            const intensity = (workout.intensity ?? "").toLowerCase(); // "easy", "moderate", "hard"
            const activityType = workout.activity ?? workout.label ?? "workout";

            // Map intensity to zone buckets (approximate)
            let z0 = 0, z1 = 0, z2 = 0, z3 = 0, z4 = 0, z5 = 0, z6 = 0;
            if (intensity === "hard" || intensity === "high") {
              z5 = durationMin; // high intensity → zone 5
            } else if (intensity === "moderate" || intensity === "medium") {
              z3 = durationMin; // moderate → zone 3
            } else {
              z1 = durationMin; // easy/rest → zone 1
            }

            // Use workout ID as source_measure_id to enable per-workout upsert
            const workoutSourceId = `oura_workout_${workout.id ?? startDt}`;

            // Don't use tryMarkMetricRan here — there can be multiple workouts per day.
            // Just upsert each workout as a separate row keyed by source_measure_id.
            const { error: wErr } = await supabase
              .from("time_in_high_hr_zones_daily")
              .upsert({
                user_id: userId, date: jobLocalDate,
                value_minutes: durationMin,
                zone_zero_minutes: z0,
                zone_one_minutes: z1,
                zone_two_minutes: z2,
                zone_three_minutes: z3 ?? 0,
                zone_four_minutes: z4,
                zone_five_minutes: z5,
                zone_six_minutes: z6,
                activity_type: activityType,
                start_at: startDt,
                end_at: endDt,
                source: "oura",
                source_measure_id: workoutSourceId,
                created_at: new Date().toISOString(),
              }, { onConflict: "user_id,source,source_measure_id" });

            if (wErr) {
              // If unique constraint doesn't include source_measure_id, try without onConflict
              metricResults.push({ metric: "time_in_high_hr_zones_daily", workout: activityType, status: "upsert_error", error: wErr.message });
            } else {
              metricResults.push({ metric: "time_in_high_hr_zones_daily", workout: activityType, duration: durationMin, intensity, status: "written" });
            }

            // One row per workout in activities (wearable rows; source set)
            const { error: sErr } = await supabase
              .from("activities")
              .upsert({
                user_id: userId,
                type: activityType,
                source: "oura",
                source_measure_id: workoutSourceId,
                start_at: startDt,
                end_at: endDt,
                duration_minutes: durationMin,
              }, { onConflict: "user_id,source,source_measure_id" });
            if (sErr) {
              metricResults.push({ table: "activities", workout: activityType, status: "upsert_error", error: sErr.message });
            } else {
              metricResults.push({ table: "activities", workout: activityType, status: "written" });
            }
          }
        } catch (e) {
          metricResults.push({ source: "oura", status: "workout_fetch_failed", error: (e as Error).message });
        }

        // ────────────────────────────────────────────────────────────
        // 10. HEART-RATE GOALS → hr_threshold_daily
        //     Only for users with an active heart-rate goal (practitioner_goals,
        //     kind hr_threshold); everyone else costs one cheap query.
        //     GET /heartrate?start_datetime=&end_datetime= (needs the `heartrate`
        //     scope) returns a sample every ~5 minutes, denser during workouts.
        //     We reduce it to "minutes at or above N bpm" and "longest stretch"
        //     for the job's day and the day before (the job runs in the morning,
        //     so the day before is the first complete one). 5-minute samples
        //     cannot prove a continuous stretch, so those rows are estimates.
        //     Strictly optional: any failure is logged and the job carries on.
        // ────────────────────────────────────────────────────────────
        try {
          const { data: hrGoalRows } = await supabase.from("practitioner_goals")
            .select("threshold_bpm").eq("user_id", userId).eq("kind", "hr_threshold").eq("status", "active");
          const hrThresholds = Array.from(new Set(
            ((hrGoalRows ?? []) as any[]).map((r) => Number(r.threshold_bpm)).filter((n) => n > 0),
          ));
          if (hrThresholds.length) {
            const hrDeadline = Date.now() + 20_000;
            const hrFromS = localDayStartEpochS(prevDay, tz);
            const hrMidS = localDayStartEpochS(jobLocalDate, tz);
            const hrToS = localDayStartEpochS(nextDay, tz);
            const hrPoints: { t: number; bpm: number }[] = [];
            let hrNextToken: string | null = null;
            for (let page = 0; page < 8 && Date.now() < hrDeadline; page++) {
              const hrParams: Record<string, string> = {
                start_datetime: new Date(hrFromS * 1000).toISOString(),
                end_datetime: new Date(hrToS * 1000).toISOString(),
              };
              if (hrNextToken) hrParams.next_token = hrNextToken;
              const json = await hrWithTimeout(ouraFetch("/heartrate", token, hrParams), 12_000, "Oura /heartrate");
              for (const it of (json?.data ?? []) as any[]) {
                hrPoints.push({ t: Date.parse(String(it?.timestamp ?? "")) / 1000, bpm: Number(it?.bpm) });
              }
              hrNextToken = typeof json?.next_token === "string" && json.next_token ? json.next_token : null;
              if (!hrNextToken) break;
            }
            const hrDays: [string, number, number][] = [[prevDay, hrFromS, hrMidS], [jobLocalDate, hrMidS, hrToS]];
            for (const [hrDay, dayFromS, dayToS] of hrDays) {
              const samples = hrDaySamples(hrPoints, dayFromS, dayToS);
              if (!samples.length) continue;
              const { opts, isEstimate } = hrSpacingOpts(samples);
              let hrWritten = 0;
              for (const thr of hrThresholds) {
                const { minutesAbove, longestRunMinutes } = hrThresholdSummary(samples, thr, opts);
                const { error: hrErr } = await supabase.from("hr_threshold_daily").upsert({
                  user_id: userId,
                  date: hrDay,
                  threshold_bpm: thr,
                  minutes_above: minutesAbove,
                  longest_run_minutes: longestRunMinutes,
                  is_estimate: isEstimate,
                  source: "oura",
                  updated_at: new Date().toISOString(),
                }, { onConflict: "user_id,date,threshold_bpm,source" });
                if (hrErr) console.error("[sync-worker-oura] upsert hr_threshold_daily:", hrErr.message);
                else hrWritten++;
              }
              metricResults.push({
                metric: "hr_threshold_daily", date: hrDay, samples: samples.length,
                is_estimate: isEstimate, status: hrWritten ? "written" : "upsert_error",
              });
            }
          }
        } catch (e) {
          console.error("[sync-worker-oura] hr_threshold_daily skipped:", (e as Error).message);
          metricResults.push({ source: "oura", status: "hr_threshold_failed", error: (e as Error).message });
        }

        // ────────────────────────────────────────────────────────────
        // Finalize job
        // ────────────────────────────────────────────────────────────

        if (!anyDataFound) {
          if (isTodayJob) {
            const pastWindow = (hh > RETRY_END_HOUR || (hh === RETRY_END_HOUR && mm > RETRY_END_MINUTE));
            const reason = (!force && pastWindow)
              ? "after_retry_window_waiting_for_oura_data"
              : "retry_waiting_for_oura_data";
            await markJobQueued(job.id, reason);
            return {
              jobId: job.id, userId, localDate: jobLocalDate,
              status: reason, timezone: tz, localTime, metricResults,
              attempt: currentAttempt,
            };
          }

          if (currentAttempt < 3) {
            await markJobQueued(job.id, "retry_waiting_for_oura_data_backfill");
            return {
              jobId: job.id, userId, localDate: jobLocalDate,
              status: "retry_waiting_for_oura_data_backfill", timezone: tz,
              metricResults, attempt: currentAttempt,
            };
          }

          await markJobError(job.id, "no_oura_data");
          return {
            jobId: job.id, userId, localDate: jobLocalDate,
            status: "no_oura_data_error", timezone: tz,
            metricResults, attempt: currentAttempt,
          };
        }

        // Mark job done
        await markJobDone(job.id, null);

        return {
          jobId: job.id, userId, localDate: jobLocalDate,
          status: "done", timezone: tz, localTime, metricResults,
          attempt: currentAttempt,
          finalizedBecause: isTodayJob ? "oura_data_found" : "oura_data_found_backfill",
        };

      } catch (e) {
        const msg = (e as Error).message;
        await markJobError(job.id, msg);
        return { jobId: job.id, status: "error", error: msg };
      }
    }

    // Process all picked jobs (up to 6 concurrently)
    const results = await mapLimit(picked, 6, async (j) => processJob(j));

    const summary = {
      picked: picked.length,
      done: results.filter((r: any) => r.status === "done").length,
      requeued: results.filter((r: any) =>
        r.status === "outside_retry_window" ||
        String(r.status).includes("retry_waiting_for_oura_data") ||
        String(r.status).includes("after_retry_window") ||
        r.status === "oura_not_connected"
      ).length,
      errors: results.filter((r: any) => r.status === "error" || String(r.status).includes("error")).length,
      nowUtc: nowUtc.toISOString(),
      forced: force,
      maxPickAttempts: MAX_PICK_ATTEMPTS,
      retryWindowLocal: `${String(RETRY_START_HOUR).padStart(2, "0")}:00-${String(RETRY_END_HOUR).padStart(2, "0")}:${String(RETRY_END_MINUTE).padStart(2, "0")}`,
    };

    return jsonResponse({ ok: true, summary, results });

  } catch (e) {
    return jsonResponse({ ok: false, error: (e as Error).message }, 500);
  }
});