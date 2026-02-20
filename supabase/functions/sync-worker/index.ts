// supabase/functions/sync-worker/index.ts
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

type ResolveRow = {
  city_id: number;
  timezone: string | null;
  resolved_date?: string | null;
};

type MetricSettingRow = {
  user_id: string;
  metric: string;
  enabled: boolean;
  preferred_source: string | null;
  allowed_sources: string[] | null;
};

type WhoopTokenRow = {
  user_id: string;
  access_token: string;
  refresh_token: string;
  token_type: string | null;
  expires_at: string | null; // timestamptz
};

type WhoopRecord = Record<string, unknown>;

type SyncJobRow = {
  id: string | number;
  job_type: string;
  user_id: string;
  local_date: string;
  status: string;
  attempts: number;
  locked_at: string | null;
};

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

function optionalEnv(name: string): string | null {
  return Deno.env.get(name) ?? null;
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
    second: "2-digit",
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

async function mapLimit<T, R>(
  items: T[],
  limit: number,
  fn: (item: T, idx: number) => Promise<R>,
): Promise<R[]> {
  const results: R[] = new Array(items.length);
  let nextIndex = 0;

  async function worker() {
    while (true) {
      const i = nextIndex++;
      if (i >= items.length) return;
      results[i] = await fn(items[i], i);
    }
  }

  const workers = Array.from({ length: Math.min(limit, items.length) }, () => worker());
  await Promise.all(workers);
  return results;
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
  if (mins == null) {
    throw new Error(`Could not parse timezone offset "${tzPart}" for tz=${timeZone}`);
  }
  return mins;
}

function localDateTimeToUtcIso(
  timeZone: string,
  y: number,
  m: number,
  d: number,
  hh: number,
  mm: number,
  ss: number,
): string {
  const baseUtc = Date.UTC(y, m - 1, d, hh, mm, ss);
  let guess = baseUtc;

  for (let i = 0; i < 3; i++) {
    const offsetMin = getOffsetMinutesForInstant(timeZone, new Date(guess));
    guess = baseUtc - offsetMin * 60_000;
  }

  return new Date(guess).toISOString();
}

function getWhoopNoonWindowUtcIso(timeZone: string, localDate: string) {
  const prev = addDaysIsoDate(localDate, -1).split("-").map((x) => Number(x));
  const next = addDaysIsoDate(localDate, +1).split("-").map((x) => Number(x));

  const startUtcIso = localDateTimeToUtcIso(timeZone, prev[0], prev[1], prev[2], 12, 0, 0);
  const endUtcIso = localDateTimeToUtcIso(timeZone, next[0], next[1], next[2], 12, 0, 0);

  return { startUtcIso, endUtcIso };
}

async function whoopFetchJson(url: string, accessToken: string) {
  const res = await fetch(url, {
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
      Accept: "application/json",
    },
  });

  const text = await res.text();
  if (!res.ok) {
    throw new Error(`WHOOP request failed (${res.status}): ${text}`);
  }
  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`WHOOP response not JSON: ${text}`);
  }
}

async function whoopFetchPagedRecords(
  path: string,
  startUtcIso: string,
  endUtcIso: string,
  accessToken: string,
): Promise<WhoopRecord[]> {
  const base = "https://api.prod.whoop.com/developer/v2";
  const out: WhoopRecord[] = [];
  let nextToken: string | null = null;

  for (let page = 0; page < 20; page++) {
    const url = new URL(base + path);
    url.searchParams.set("start", startUtcIso);
    url.searchParams.set("end", endUtcIso);
    url.searchParams.set("limit", "25");
    if (nextToken) url.searchParams.set("nextToken", nextToken);

    const json = await whoopFetchJson(url.toString(), accessToken);

    const records = (json?.records ?? json?.data ?? json?.items) as unknown;
    if (Array.isArray(records)) {
      for (const r of records) out.push((r ?? {}) as WhoopRecord);
    }

    const ntRaw = (json?.next_token ?? json?.nextToken) as unknown;
    const nt = typeof ntRaw === "string" ? ntRaw : null;
    nextToken = nt && nt.toLowerCase() !== "null" && nt.trim() ? nt : null;

    if (!nextToken) break;
  }

  return out;
}

function isExpiredSoon(expiresAtIso: string | null, nowUtc: Date): boolean {
  // If expires_at is NULL or invalid, assume expired and refresh to be safe
  if (!expiresAtIso) return true;
  const exp = new Date(expiresAtIso).getTime();
  if (Number.isNaN(exp)) return true;
  return exp <= nowUtc.getTime() + 5 * 60_000;
}

// PATCHED: refresh wrapper (newline-safe)
// - strips \n and \r and trims refresh_token before sending to WHOOP
// - fails clearly if refresh_token is empty after sanitation
// - validates access_token exists
// - strips \n/\r from returned refresh_token as well
async function refreshWhoopToken(
  refreshTokenRaw: string,
): Promise<{ access_token: string; refresh_token: string; expires_in: number; token_type?: string }> {
  const clientId = requireEnv("WHOOP_CLIENT_ID");
  const clientSecret = requireEnv("WHOOP_CLIENT_SECRET");

  const refreshToken = String(refreshTokenRaw ?? "").replace(/\r?\n/g, "").replace(/\r/g, "").trim();
  if (!refreshToken) {
    throw new Error("WHOOP token refresh failed (400): refresh_token_empty");
  }

  const body = new URLSearchParams();
  body.set("grant_type", "refresh_token");
  body.set("refresh_token", refreshToken);
  body.set("client_id", clientId);
  body.set("client_secret", clientSecret);

  const res = await fetch("https://api.prod.whoop.com/oauth/oauth2/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
  });

  const text = await res.text();
  if (!res.ok) {
    throw new Error(`WHOOP token refresh failed (${res.status}): ${text}`);
  }

  const json = JSON.parse(text);

  const access = String(json.access_token ?? "").trim();
  if (!access) {
    throw new Error(`WHOOP token refresh failed (200): missing_access_token`);
  }

  const newRefresh = String(json.refresh_token ?? refreshToken).replace(/\r?\n/g, "").replace(/\r/g, "").trim();

  return {
    access_token: access,
    refresh_token: newRefresh,
    expires_in: Number(json.expires_in ?? 0),
    token_type: json.token_type ? String(json.token_type) : undefined,
  };
}

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

function numOrNull(v: unknown): number | null {
  const n = typeof v === "number" ? v : typeof v === "string" ? Number(v) : NaN;
  return Number.isFinite(n) ? n : null;
}

function intOrNull(v: unknown): number | null {
  const n = numOrNull(v);
  if (n == null) return null;
  return Math.trunc(n);
}

function whoopTzMinutes(rec: any): number {
  const a = numOrNull(rec?.timezone_offset_minutes);
  if (a != null) return a;
  const b = numOrNull(rec?.timezone_offset);
  if (b != null) return b / 60.0;
  return 0;
}

function whoopShiftedIso(rec: any, key: "start" | "end"): string | null {
  const utc = typeof rec?.[key] === "string" ? String(rec[key]) : "";
  if (!utc) return null;
  const tzMin = whoopTzMinutes(rec);
  const ms = new Date(utc).getTime();
  if (Number.isNaN(ms)) return null;
  return new Date(ms + tzMin * 60_000).toISOString();
}

function selectWhoopSleepRecordByWakeup(records: any[], targetLocalDate: string, timeZone: string): any | null {
  let bestExact: any | null = null;
  let latest: any | null = null;
  let latestEndLocalMs: number | null = null;

  for (const rec of records) {
    const endUtc = typeof rec?.end === "string" ? String(rec.end) : "";
    if (!endUtc) continue;

    const tzMin = whoopTzMinutes(rec);
    const endUtcMs = new Date(endUtc).getTime();
    if (Number.isNaN(endUtcMs)) continue;

    const endLocalMs = endUtcMs + tzMin * 60_000;
    const endLocalDate = getLocalTimeParts(timeZone, new Date(endLocalMs)).localDate;

    if (endLocalDate === targetLocalDate) bestExact = rec;

    if (latestEndLocalMs == null || endLocalMs > latestEndLocalMs) {
      latestEndLocalMs = endLocalMs;
      latest = rec;
    }
  }

  return bestExact ?? latest;
}

function whoopMeasureId(rec: any): string | null {
  const raw =
    rec?.id ??
    rec?.sleep_id ??
    rec?.recovery_id ??
    rec?.record_id ??
    rec?.activity_id ??
    rec?.cycle_id ??
    rec?.score_id ??
    rec?.score?.id ??
    rec?.summary?.id ??
    null;

  if (raw == null) return null;

  const s = String(raw).trim();
  return s ? s : null;
}

async function resolveWithDateFallback(
  supabase: ReturnType<typeof createClient>,
  userId: string,
  isoDate: string,
): Promise<{ row: ResolveRow | null; usedDate: string | null }> {
  const candidates = [addDaysIsoDate(isoDate, -1), isoDate, addDaysIsoDate(isoDate, +1)];

  for (const d of candidates) {
    const { data, error } = await supabase.rpc("resolve_user_city_for_date", {
      p_user_id: userId,
      p_date: d,
    });

    if (error) throw new Error(`resolve_user_city_for_date failed for date=${d}: ${error.message}`);

    const row: ResolveRow | undefined = Array.isArray(data) ? data[0] : data;
    if (row?.timezone) return { row, usedDate: row.resolved_date ?? d };
  }

  return { row: null, usedDate: null };
}

function getForceFlag(req: Request): boolean {
  const url = new URL(req.url);
  const raw = url.searchParams.get("force");
  if (!raw) return false;
  return raw === "1" || raw.toLowerCase() === "true" || raw.toLowerCase() === "yes";
}

// Retry window: 9:00 - 9:45 local time
const RETRY_START_HOUR = 9;
const RETRY_END_HOUR = 9;
const RETRY_END_MINUTE = 45;

function isWithinRetryWindow(hh: number, mm: number): boolean {
  if (hh < RETRY_START_HOUR) return false;
  if (hh > RETRY_END_HOUR) return false;
  if (hh === RETRY_END_HOUR && mm > RETRY_END_MINUTE) return false;
  return true;
}

const MAX_PICK_ATTEMPTS = 10;

serve(async (req) => {
  console.log("[sync-worker] start", { nowUtc: new Date().toISOString(), method: req.method, url: req.url });

  try {
    if (req.method !== "POST" && req.method !== "GET") {
      return jsonResponse({ ok: false, error: "Method not allowed" }, 405);
    }

    const supabaseUrl = requireEnv("SUPABASE_URL");
    const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");

    const whoopConfigured = Boolean(optionalEnv("WHOOP_CLIENT_ID") && optionalEnv("WHOOP_CLIENT_SECRET"));
    const force = getForceFlag(req);

    // Also check for force in request body (for webhook invocation)
    let bodyForce = false;
    let bodyUserId: string | null = null;
    try {
      const body = await req.clone().json();
      bodyForce = body?.force === true;
      bodyUserId = typeof body?.userId === "string" ? body.userId : null;
    } catch {
      // No body or invalid JSON
    }

    const isForced = force || bodyForce;

    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false },
      global: { headers: { "X-Client-Info": "sync-worker" } },
    });

    const nowUtc = new Date();
    const utcDate = nowUtc.toISOString().slice(0, 10);
    const staleCutoffIso = new Date(nowUtc.getTime() - 30 * 60_000).toISOString();

    // NOTE: attempts gating prevents hot-looping and runaway attempts.
    // Force mode bypasses this gate.
    let jobsQuery = supabase
      .from("sync_jobs")
      .select("id,job_type,user_id,local_date,status,attempts,locked_at")
      .eq("job_type", "whoop_daily")
      .or(`status.eq.queued,status.eq.running.and(locked_at.lt.${staleCutoffIso})`)
      .order("local_date", { ascending: true })
      .order("created_at", { ascending: true })
      .limit(50);

    // If specific userId provided (from webhook), filter to just that user
    if (bodyUserId) {
      jobsQuery = jobsQuery.eq("user_id", bodyUserId);
    }

    const { data: jobs, error: jobsErr } = await jobsQuery;

    if (jobsErr) throw new Error(`sync_jobs select failed: ${jobsErr.message}`);

    const all = (jobs ?? []) as SyncJobRow[];

    // Filter attempts only here so we preserve the original select shape
    const picked = all.filter((j) => isForced || (j.attempts ?? 0) < MAX_PICK_ATTEMPTS);

    if (!picked.length) {
      return jsonResponse({
        ok: true,
        summary: {
          picked: 0,
          whoopClientConfigured: whoopConfigured,
          forced: isForced,
          maxPickAttempts: MAX_PICK_ATTEMPTS,
          filteredOut: all.length,
        },
      });
    }

    async function lockJob(job: SyncJobRow): Promise<boolean> {
      const nowIso = new Date().toISOString();

      // IMPORTANT:
      // Do NOT increment attempts here. We only increment when we actually attempt WHOOP fetch work.
      if (job.status === "queued") {
        const { data, error } = await supabase
          .from("sync_jobs")
          .update({
            status: "running",
            locked_at: nowIso,
            updated_at: nowIso,
            last_error: null,
          })
          .eq("id", job.id)
          .eq("status", "queued")
          .select("id")
          .maybeSingle();

        if (error) return false;
        return Boolean(data);
      }

      const { data, error } = await supabase
        .from("sync_jobs")
        .update({
          status: "running",
          locked_at: nowIso,
          updated_at: nowIso,
        })
        .eq("id", job.id)
        .eq("status", "running")
        .lt("locked_at", staleCutoffIso)
        .select("id")
        .maybeSingle();

      if (error) return false;
      return Boolean(data);
    }

    async function bumpAttempts(jobId: string | number): Promise<number> {
      // In supabase-js v2, rpc/from/select return builders; they do NOT support `.catch`.
      // We do explicit { data, error } checks.

      // Optional RPC path if you add it later:
      // create hookup:
      //   create or replace function public.increment_sync_job_attempts(p_job_id uuid) returns int ...
      const { data: rpcData, error: rpcErr } = await supabase.rpc("increment_sync_job_attempts", { p_job_id: jobId });

      if (!rpcErr) {
        // If RPC returns a number, prefer it. Otherwise fall through to re-select.
        const n = typeof rpcData === "number" ? rpcData : null;
        if (n != null) return n;
      }

      // If RPC does not exist (or fails), do a safe read+write.
      // This is not perfectly atomic, but it matches your previous fallback intent and avoids `.catch` crashes.
      const { data: row, error: selErr } = await supabase
        .from("sync_jobs")
        .select("attempts")
        .eq("id", jobId)
        .maybeSingle();

      if (selErr) throw new Error(`bump_attempts_select_failed: ${selErr.message}`);

      const current = typeof (row as any)?.attempts === "number" ? (row as any).attempts : 0;
      const next = current + 1;

      const { error: updErr } = await supabase
        .from("sync_jobs")
        .update({ attempts: next, updated_at: new Date().toISOString() })
        .eq("id", jobId);

      if (updErr) throw new Error(`bump_attempts_update_failed: ${updErr.message}`);

      return next;
    }

    async function markJobDone(jobId: string | number, lastError: string | null = null) {
      const nowIso = new Date().toISOString();
      await supabase
        .from("sync_jobs")
        .update({
          status: "done",
          finished_at: nowIso,
          updated_at: nowIso,
          last_error: lastError,
          locked_at: null,
        })
        .eq("id", jobId);
    }

    async function markJobQueued(jobId: string | number, reason: string) {
      const nowIso = new Date().toISOString();
      await supabase
        .from("sync_jobs")
        .update({
          status: "queued",
          last_error: reason,
          updated_at: nowIso,
          locked_at: null,
        })
        .eq("id", jobId);
    }

    async function markJobError(jobId: string | number, errMsg: string) {
      const nowIso = new Date().toISOString();
      await supabase
        .from("sync_jobs")
        .update({
          status: "error",
          last_error: errMsg,
          finished_at: nowIso,
          updated_at: nowIso,
          locked_at: null,
        })
        .eq("id", jobId);
    }

    async function processJob(job: SyncJobRow) {
      const locked = await lockJob(job);
      if (!locked) return { jobId: job.id, status: "skipped_lock_race" };

      const userId = String(job.user_id);
      const jobLocalDate = String(job.local_date);

      try {
        const out = await resolveWithDateFallback(supabase, userId, jobLocalDate);
        const resolved = out.row;

        if (!resolved?.timezone) {
          await markJobError(job.id, "no_timezone");
          return { jobId: job.id, userId, localDate: jobLocalDate, status: "no_timezone" };
        }

        const tz = resolved.timezone;
        const { localDate: currentLocalDate, hh, mm } = getLocalTimeParts(tz, nowUtc);
        const localTime = `${hh}:${String(mm).padStart(2, "0")}`;
        const isTodayJob = jobLocalDate === currentLocalDate;

        // If already finalized for that local_date, mark job done
        const { data: already, error: ranErr } = await supabase
          .from("backend_daily_runs")
          .select("user_id")
          .eq("user_id", userId)
          .eq("local_date", jobLocalDate)
          .maybeSingle();

        if (ranErr) throw new Error(`run_check_error: ${ranErr.message}`);
        if (already) {
          await markJobDone(job.id, null);
          return { jobId: job.id, userId, localDate: jobLocalDate, status: "already_ran", timezone: tz, localTime };
        }

        // Fetch enabled metrics (needed before any stop decision)
        const { data: metrics, error: metErr } = await supabase
          .from("metric_settings")
          .select("user_id,metric,enabled,preferred_source,allowed_sources")
          .eq("user_id", userId)
          .eq("enabled", true);

        if (metErr) throw new Error(`metrics_error: ${metErr.message}`);

        const enabledMetrics = (metrics ?? []) as MetricSettingRow[];

        const whoopEnabledForAny = enabledMetrics.some((m) => {
          const pref = (m.preferred_source ?? "").toLowerCase();
          const allowed = (m.allowed_sources ?? []).map((s) => String(s).toLowerCase());
          return pref === "whoop" || allowed.includes("whoop");
        });

        // If WHOOP isn't needed, finalize immediately
        if (!whoopEnabledForAny) {
          const { error: insErr } = await supabase.from("backend_daily_runs").insert({
            user_id: userId,
            utc_date: utcDate,
            local_date: jobLocalDate,
            timezone: tz,
            city_id: resolved.city_id,
            ran_at: nowUtc.toISOString(),
          });

          if (insErr) {
            const msg = (insErr.message ?? "").toLowerCase();
            if (!msg.includes("duplicate") && !msg.includes("unique")) {
              throw new Error(`run_insert_error: ${insErr.message}`);
            }
          }

          await markJobDone(job.id, null);
          return {
            jobId: job.id,
            userId,
            localDate: jobLocalDate,
            status: "done",
            timezone: tz,
            localTime,
            finalizedBecause: "no_whoop_needed",
          };
        }

        // Gate by retry window ONLY for today jobs (unless forced by webhook)
        // IMPORTANT: do not increment attempts for gating-only requeues.
        if (isTodayJob && !isForced) {
          if (!isWithinRetryWindow(hh, mm)) {
            const reason = (hh > RETRY_END_HOUR || (hh === RETRY_END_HOUR && mm > RETRY_END_MINUTE))
              ? "after_retry_window_waiting_for_whoop_data"
              : "outside_retry_window";
            await markJobQueued(job.id, reason);
            return { jobId: job.id, userId, localDate: jobLocalDate, status: reason, timezone: tz, localTime };
          }
        }

        // From here on, we will actually attempt WHOOP work. Increment attempts once.
        // This prevents "attempts explosion" from gating-only requeues.
        const currentAttempt = await bumpAttempts(job.id);

        const metricResults: any[] = [];
        let whoopSleepFound = false;
        let whoopRecoveryFound = false;
        let whoopWorkoutFound = false;

        // WHOOP token
        const { data: tok, error: tokErr } = await supabase
          .from("whoop_tokens")
          .select("user_id,access_token,refresh_token,token_type,expires_at")
          .eq("user_id", userId)
          .maybeSingle();

        if (tokErr) throw new Error(`whoop_token_error: ${tokErr.message}`);

        if (!tok) {
          if (isTodayJob) {
            await markJobQueued(job.id, "whoop_not_connected");
            return { jobId: job.id, userId, localDate: jobLocalDate, status: "whoop_not_connected", timezone: tz, localTime };
          } else {
            await markJobError(job.id, "whoop_not_connected");
            return { jobId: job.id, userId, localDate: jobLocalDate, status: "whoop_not_connected_backfill_error", timezone: tz, localTime };
          }
        }

        let whoopToken = tok as WhoopTokenRow;

        // refresh if expiring
        try {
          if (isExpiredSoon(whoopToken.expires_at, nowUtc)) {
            const refreshed = await refreshWhoopToken(whoopToken.refresh_token);
            const expiresInSec = refreshed.expires_in || 3600; // Default 1 hour if missing
            const expiresAt = new Date(nowUtc.getTime() + expiresInSec * 1000).toISOString();

            const { error: updErr } = await supabase
              .from("whoop_tokens")
              .update({
                access_token: refreshed.access_token,
                refresh_token: refreshed.refresh_token,
                token_type: refreshed.token_type ?? whoopToken.token_type ?? "Bearer",
                expires_at: expiresAt,
                updated_at: new Date().toISOString(),
              })
              .eq("user_id", userId);

            if (updErr) throw new Error(updErr.message);

            whoopToken = {
              ...whoopToken,
              access_token: refreshed.access_token,
              refresh_token: refreshed.refresh_token,
              token_type: refreshed.token_type ?? whoopToken.token_type,
              expires_at: expiresAt,
            };
            
            // Log successful refresh to edge_audit
            await supabase.from("edge_audit").insert({
              fn: "sync-worker",
              user_id: userId,
              ok: true,
              stage: "whoop_refresh_ok",
              message: `expires_in=${expiresInSec}s`,
            }).catch(() => {});
          }
        } catch (e) {
          const errMsg = (e as Error).message;
          metricResults.push({ source: "whoop", status: "refresh_failed", error: errMsg });
          
          // Log refresh failure to edge_audit - THIS IS CRITICAL TO MONITOR
          await supabase.from("edge_audit").insert({
            fn: "sync-worker",
            user_id: userId,
            ok: false,
            stage: "whoop_refresh_failed",
            message: errMsg.slice(0, 500),
          }).catch(() => {});
        }

        const whoopEnabled = (metric: string) => {
          const m = enabledMetrics.find((x) => x.metric === metric);
          if (!m) return false;
          const pref = (m.preferred_source ?? "").toLowerCase();
          const allowed = (m.allowed_sources ?? []).map((s) => String(s).toLowerCase());
          return pref === "whoop" || allowed.includes("whoop");
        };

        if (whoopToken.access_token) {
          const { startUtcIso, endUtcIso } = getWhoopNoonWindowUtcIso(tz, jobLocalDate);

          // RECOVERY
          try {
            const recs = await whoopFetchPagedRecords("/recovery", startUtcIso, endUtcIso, whoopToken.access_token);
            const rec = recs.length ? (recs[0] as any) : null;

            if (!rec) {
              metricResults.push({ source: "whoop", status: "no_recovery_record" });
            } else {
              whoopRecoveryFound = true;

              const scoreObj = rec?.score ?? null;
              const sourceMeasureId = whoopMeasureId(rec);

              const recoveryPct = numOrNull(scoreObj?.recovery_score);
              const restingHr = numOrNull(scoreObj?.resting_heart_rate);
              const hrvMs = numOrNull(scoreObj?.hrv_rmssd_milli);
              const skinTemp = numOrNull(scoreObj?.skin_temp_celsius);
              const spo2 = numOrNull(scoreObj?.spo2_percentage);

              if (whoopEnabled("recovery_score_daily") && recoveryPct != null) {
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "recovery_score_daily", "whoop");
                if (proceed) {
                  await upsertDailyRow(supabase, "recovery_score_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_pct: recoveryPct,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: new Date().toISOString(),
                  });
                }
                metricResults.push({ metric: "recovery_score_daily", status: proceed ? "written" : "already_done" });
              }

              if (whoopEnabled("resting_hr_daily") && restingHr != null) {
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "resting_hr_daily", "whoop");
                if (proceed) {
                  await upsertDailyRow(supabase, "resting_hr_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_bpm: restingHr,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: new Date().toISOString(),
                  });
                }
                metricResults.push({ metric: "resting_hr_daily", status: proceed ? "written" : "already_done" });
              }

              if (whoopEnabled("hrv_daily") && hrvMs != null) {
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "hrv_daily", "whoop");
                if (proceed) {
                  await upsertDailyRow(supabase, "hrv_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_rmssd_ms: hrvMs,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: new Date().toISOString(),
                  });
                }
                metricResults.push({ metric: "hrv_daily", status: proceed ? "written" : "already_done" });
              }

              if (whoopEnabled("skin_temp_daily") && skinTemp != null) {
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "skin_temp_daily", "whoop");
                if (proceed) {
                  await upsertDailyRow(supabase, "skin_temp_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_celsius: skinTemp,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: new Date().toISOString(),
                  });
                }
                metricResults.push({ metric: "skin_temp_daily", status: proceed ? "written" : "already_done" });
              }

              if (whoopEnabled("spo2_daily") && spo2 != null) {
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "spo2_daily", "whoop");
                if (proceed) {
                  await upsertDailyRow(supabase, "spo2_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_pct: spo2,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: new Date().toISOString(),
                  });
                }
                metricResults.push({ metric: "spo2_daily", status: proceed ? "written" : "already_done" });
              }

              metricResults.push({ source: "whoop", status: "recovery_processed" });
            }
          } catch (e) {
            metricResults.push({ source: "whoop", status: "recovery_fetch_failed", error: (e as Error).message });
          }

          // SLEEP
          try {
            const recs = await whoopFetchPagedRecords("/activity/sleep", startUtcIso, endUtcIso, whoopToken.access_token);
            const rec = recs.length ? selectWhoopSleepRecordByWakeup(recs as any[], jobLocalDate, tz) : null;

            if (!rec) {
              metricResults.push({ source: "whoop", status: "no_sleep_record_for_day" });
            } else {
              whoopSleepFound = true;

              const sourceMeasureId = whoopMeasureId(rec);
              const score = (rec as any)?.score ?? null;
              const stage = score?.stage_summary ?? null;

              const lightMs = numOrNull(stage?.total_light_sleep_time_milli) ?? 0;
              const swsMs = numOrNull(stage?.total_slow_wave_sleep_time_milli) ?? 0;
              const remMs = numOrNull(stage?.total_rem_sleep_time_milli) ?? 0;

              const durationMs = numOrNull(score?.sleep_duration_milli) ?? 0;
              const durationHours = (durationMs > 0 ? durationMs : (lightMs + swsMs + remMs)) / 3_600_000.0;

              const disturbances = intOrNull(stage?.disturbance_count) ?? 0;

              const perfPct = numOrNull(score?.sleep_performance_percentage);
              const effPct = numOrNull(score?.sleep_efficiency_percentage);

              const fellAsleepAt = whoopShiftedIso(rec, "start");
              const wokeUpAt = whoopShiftedIso(rec, "end");

              if (whoopEnabled("sleep_duration_daily") && durationHours > 0) {
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "sleep_duration_daily", "whoop");
                if (proceed) {
                  await upsertDailyRow(supabase, "sleep_duration_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_hours: durationHours,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: new Date().toISOString(),
                    updated_at: new Date().toISOString(),
                  });
                }
                metricResults.push({ metric: "sleep_duration_daily", status: proceed ? "written" : "already_done" });
              }

              if (whoopEnabled("sleep_score_daily") && perfPct != null) {
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "sleep_score_daily", "whoop");
                if (proceed) {
                  await upsertDailyRow(supabase, "sleep_score_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_pct: perfPct,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: new Date().toISOString(),
                    updated_at: new Date().toISOString(),
                  });
                }
                metricResults.push({ metric: "sleep_score_daily", status: proceed ? "written" : "already_done" });
              }

              if (whoopEnabled("sleep_efficiency_daily") && effPct != null) {
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "sleep_efficiency_daily", "whoop");
                if (proceed) {
                  await upsertDailyRow(supabase, "sleep_efficiency_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_pct: effPct,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: new Date().toISOString(),
                    updated_at: new Date().toISOString(),
                  });
                }
                metricResults.push({ metric: "sleep_efficiency_daily", status: proceed ? "written" : "already_done" });
              }

              if (whoopEnabled("sleep_disturbances_daily")) {
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "sleep_disturbances_daily", "whoop");
                if (proceed) {
                  await upsertDailyRow(supabase, "sleep_disturbances_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_count: disturbances,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: new Date().toISOString(),
                    updated_at: new Date().toISOString(),
                  });
                }
                metricResults.push({ metric: "sleep_disturbances_daily", status: proceed ? "written" : "already_done" });
              }

              if (whoopEnabled("sleep_stages_daily") && (lightMs > 0 || swsMs > 0 || remMs > 0)) {
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "sleep_stages_daily", "whoop");
                if (proceed) {
                  await upsertDailyRow(
                    supabase,
                    "sleep_stages_daily",
                    {
                      user_id: userId,
                      date: jobLocalDate,
                      source: "whoop",
                      source_measure_id: sourceMeasureId,
                      value_sws_hm: swsMs / 3_600_000.0,
                      value_rem_hm: remMs / 3_600_000.0,
                      value_light_hm: lightMs / 3_600_000.0,
                      created_at: new Date().toISOString(),
                    },
                    "user_id,date,source",
                  );
                }
                metricResults.push({ metric: "sleep_stages_daily", status: proceed ? "written" : "already_done" });
              }

              if (whoopEnabled("fell_asleep_time_daily") && fellAsleepAt) {
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "fell_asleep_time_daily", "whoop");
                if (proceed) {
                  await upsertDailyRow(supabase, "fell_asleep_time_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_at: fellAsleepAt,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: new Date().toISOString(),
                    updated_at: new Date().toISOString(),
                  });
                }
                metricResults.push({ metric: "fell_asleep_time_daily", status: proceed ? "written" : "already_done" });
              }

              if (whoopEnabled("woke_up_time_daily") && wokeUpAt) {
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "woke_up_time_daily", "whoop");
                if (proceed) {
                  await upsertDailyRow(supabase, "woke_up_time_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_at: wokeUpAt,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: new Date().toISOString(),
                    updated_at: new Date().toISOString(),
                  });
                }
                metricResults.push({ metric: "woke_up_time_daily", status: proceed ? "written" : "already_done" });
              }

              metricResults.push({ source: "whoop", status: "sleep_processed" });
            }
          } catch (e) {
            metricResults.push({ source: "whoop", status: "sleep_fetch_failed", error: (e as Error).message });
          }

          // WORKOUTS / HR ZONES (WHOOP v2)
          // - Per-session rows (activity_hr_zones_sessions) and/or daily aggregate (time_in_high_hr_zones_daily)
          // - Uses existing table: time_in_high_hr_zones_daily
          //   Unique constraint: (user_id, source, source_measure_id) -> upsert with that onConflict
          try {
            const wantSessions = whoopEnabled("activity_hr_zones_sessions");
            const wantDailyHigh = whoopEnabled("time_in_high_hr_zones_daily");

            if (!wantSessions && !wantDailyHigh) {
              metricResults.push({ source: "whoop", status: "workouts_skipped_not_enabled" });
            } else {
              const workouts = await whoopFetchPagedRecords("/activity/workout", startUtcIso, endUtcIso, whoopToken.access_token);
              const ws = (workouts ?? []) as any[];

              if (!ws.length) {
                metricResults.push({ source: "whoop", status: "no_workouts_in_window" });
              } else {
                // Helpers (kept inline to avoid changing global structure)
                const msToMinutes = (ms: unknown): number => {
                  const n = numOrNull(ms);
                  if (n == null || n <= 0) return 0;
                  return n / 60_000.0;
                };

                const workoutLocalDateByEnd = (rec: any): string | null => {
                  const endUtc = typeof rec?.end === "string" ? String(rec.end) : "";
                  if (!endUtc) return null;
                  const tzMin = whoopTzMinutes(rec);
                  const endUtcMs = new Date(endUtc).getTime();
                  if (Number.isNaN(endUtcMs)) return null;
                  const endLocalMs = endUtcMs + tzMin * 60_000;
                  return getLocalTimeParts(tz, new Date(endLocalMs)).localDate;
                };

                const zoneMs = (rec: any, key: string): number => {
                  const zd = rec?.score?.zone_durations ?? null;
                  const v = zd && typeof zd === "object" ? (zd as any)[key] : null;
                  const n = numOrNull(v);
                  return n != null && n > 0 ? n : 0;
                };

                // Only count workouts that "belong" to this job day by end-local-date
                const dayWorkouts = ws.filter((w) => workoutLocalDateByEnd(w) === jobLocalDate);

                if (!dayWorkouts.length) {
                  metricResults.push({ source: "whoop", status: "no_workouts_for_local_day" });
                } else {
                  whoopWorkoutFound = true;

                  // Aggregate sums for optional daily write
                  let sumZ0 = 0;
                  let sumZ1 = 0;
                  let sumZ2 = 0;
                  let sumZ3 = 0;
                  let sumZ4 = 0;
                  let sumZ5 = 0;
                  let sumZ6 = 0;

                  // Per-session inserts (no backend_metric_runs gating; uniqueness is per workout id)
                  if (wantSessions) {
                    let inserted = 0;
                    let deduped = 0;

                    for (const w of dayWorkouts) {
                      const sourceMeasureId = whoopMeasureId(w);
                      if (!sourceMeasureId) continue;

                      const z0m = zoneMs(w, "zone_zero_milli");
                      const z1m = zoneMs(w, "zone_one_milli");
                      const z2m = zoneMs(w, "zone_two_milli");
                      const z3m = zoneMs(w, "zone_three_milli");
                      const z4m = zoneMs(w, "zone_four_milli");
                      const z5m = zoneMs(w, "zone_five_milli");
                      const z6m = zoneMs(w, "zone_six_milli");

                      sumZ0 += z0m; sumZ1 += z1m; sumZ2 += z2m; sumZ3 += z3m; sumZ4 += z4m; sumZ5 += z5m; sumZ6 += z6m;

                      const highTotalMinutes = (z3m + z4m + z5m + z6m) / 60_000.0;

                      const startAt = whoopShiftedIso(w, "start");
                      const endAt = whoopShiftedIso(w, "end");

                      const activityType =
                        (typeof w?.sport_name === "string" && w.sport_name) ? String(w.sport_name) :
                        (typeof w?.sport === "string" && w.sport) ? String(w.sport) :
                        (typeof w?.activity_type === "string" && w.activity_type) ? String(w.activity_type) :
                        "workout";

                      const { error } = await supabase.from("time_in_high_hr_zones_daily").upsert(
                        {
                          user_id: userId,
                          date: jobLocalDate,
                          value_minutes: highTotalMinutes,
                          zone_zero_minutes: z0m / 60_000.0,
                          zone_one_minutes: z1m / 60_000.0,
                          zone_two_minutes: z2m / 60_000.0,
                          zone_three_minutes: z3m / 60_000.0,
                          zone_four_minutes: z4m / 60_000.0,
                          zone_five_minutes: z5m / 60_000.0,
                          zone_six_minutes: z6m / 60_000.0,
                          source: "whoop",
                          source_measure_id: sourceMeasureId,
                          created_at: new Date().toISOString(),
                          activity_type: activityType,
                          start_at: startAt,
                          end_at: endAt,
                        },
                        { onConflict: "user_id,source,source_measure_id" },
                      );

                      if (!error) {
                        inserted += 1;
                      } else {
                        const msg = (error.message ?? "").toLowerCase();
                        if (msg.includes("duplicate") || msg.includes("unique")) {
                          deduped += 1;
                        } else {
                          throw new Error(`time_in_high_hr_zones_daily session upsert failed: ${error.message}`);
                        }
                      }
                    }

                    metricResults.push({
                      metric: "activity_hr_zones_sessions",
                      status: "processed",
                      workouts: dayWorkouts.length,
                      inserted,
                      deduped,
                    });
                  } else {
                    for (const w of dayWorkouts) {
                      sumZ0 += zoneMs(w, "zone_zero_milli");
                      sumZ1 += zoneMs(w, "zone_one_milli");
                      sumZ2 += zoneMs(w, "zone_two_milli");
                      sumZ3 += zoneMs(w, "zone_three_milli");
                      sumZ4 += zoneMs(w, "zone_four_milli");
                      sumZ5 += zoneMs(w, "zone_five_milli");
                      sumZ6 += zoneMs(w, "zone_six_milli");
                    }
                  }

                  if (wantDailyHigh) {
                    const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "time_in_high_hr_zones_daily", "whoop");
                    if (proceed) {
                      const dailySourceMeasureId = `whoop_workout_daily_${jobLocalDate}`;
                      const highTotalMinutes = (sumZ3 + sumZ4 + sumZ5 + sumZ6) / 60_000.0;

                      await supabase.from("time_in_high_hr_zones_daily").upsert(
                        {
                          user_id: userId,
                          date: jobLocalDate,
                          value_minutes: highTotalMinutes,
                          zone_zero_minutes: sumZ0 / 60_000.0,
                          zone_one_minutes: sumZ1 / 60_000.0,
                          zone_two_minutes: sumZ2 / 60_000.0,
                          zone_three_minutes: sumZ3 / 60_000.0,
                          zone_four_minutes: sumZ4 / 60_000.0,
                          zone_five_minutes: sumZ5 / 60_000.0,
                          zone_six_minutes: sumZ6 / 60_000.0,
                          source: "whoop",
                          source_measure_id: dailySourceMeasureId,
                          created_at: new Date().toISOString(),
                          activity_type: "daily_total",
                          start_at: null,
                          end_at: null,
                        },
                        { onConflict: "user_id,source,source_measure_id" },
                      );
                    }

                    metricResults.push({ metric: "time_in_high_hr_zones_daily", status: proceed ? "written" : "already_done" });
                  }
                }
              }
            }
          } catch (e) {
            metricResults.push({ source: "whoop", status: "workouts_fetch_failed", error: (e as Error).message });
          }
        }

        const hasWhoopData = whoopSleepFound || whoopRecoveryFound || whoopWorkoutFound;

        if (!hasWhoopData) {
          if (isTodayJob) {
            const pastWindow = (hh > RETRY_END_HOUR || (hh === RETRY_END_HOUR && mm > RETRY_END_MINUTE));
            const reason = (!isForced && pastWindow)
              ? "after_retry_window_waiting_for_whoop_data"
              : "retry_waiting_for_whoop_data";
            await markJobQueued(job.id, reason);
            return {
              jobId: job.id,
              userId,
              localDate: jobLocalDate,
              status: reason,
              timezone: tz,
              localTime,
              metricResults,
              whoopSleepFound,
              whoopRecoveryFound,
              attempt: currentAttempt,
            };
          }

          if (currentAttempt < 3) {
            await markJobQueued(job.id, "retry_waiting_for_whoop_data");
            return {
              jobId: job.id,
              userId,
              localDate: jobLocalDate,
              status: "retry_waiting_for_whoop_data_backfill",
              timezone: tz,
              localTime,
              metricResults,
              whoopSleepFound,
              whoopRecoveryFound,
              attempt: currentAttempt,
            };
          }

          await markJobError(job.id, "no_whoop_data");
          return {
            jobId: job.id,
            userId,
            localDate: jobLocalDate,
            status: "no_whoop_data_error",
            timezone: tz,
            localTime,
            metricResults,
            whoopSleepFound,
            whoopRecoveryFound,
            attempt: currentAttempt,
          };
        }

        const { error: insErr } = await supabase.from("backend_daily_runs").insert({
          user_id: userId,
          utc_date: utcDate,
          local_date: jobLocalDate,
          timezone: tz,
          city_id: resolved.city_id,
          ran_at: nowUtc.toISOString(),
        });

        if (insErr) {
          const msg = (insErr.message ?? "").toLowerCase();
          if (!msg.includes("duplicate") && !msg.includes("unique")) {
            throw new Error(`run_insert_error: ${insErr.message}`);
          }
        }

        await markJobDone(job.id, null);

        return {
          jobId: job.id,
          userId,
          localDate: jobLocalDate,
          status: "done",
          timezone: tz,
          localTime,
          metricResults,
          whoopSleepFound,
          whoopRecoveryFound,
          finalizedBecause: isTodayJob ? "whoop_data_found" : "whoop_data_found_backfill",
        };
      } catch (e) {
        const msg = (e as Error).message;
        await markJobError(job.id, msg);
        return { jobId: job.id, status: "error", error: msg };
      }
    }

    const results = await mapLimit(picked, 6, async (j) => processJob(j));

    const summary = {
      picked: picked.length,
      done: results.filter((r: any) => r.status === "done" || r.status === "already_ran").length,
      requeued: results.filter(
        (r: any) =>
          r.status === "outside_retry_window" ||
          String(r.status).includes("retry_waiting_for_whoop_data") ||
          String(r.status).includes("after_retry_window_waiting_for_whoop_data") ||
          r.status === "whoop_not_connected",
      ).length,
      errors: results.filter((r: any) => r.status === "error" || String(r.status).includes("error")).length,
      nowUtc: nowUtc.toISOString(),
      whoopClientConfigured: whoopConfigured,
      forced: isForced,
      retryWindowLocal: `${String(RETRY_START_HOUR).padStart(2, "0")}:00-${String(RETRY_END_HOUR).padStart(2, "0")}:${String(RETRY_END_MINUTE).padStart(2, "0")}`,
      staleReclaimMinutes: 30,
      maxPickAttempts: MAX_PICK_ATTEMPTS,
    };

    return jsonResponse({ ok: true, summary, results });
  } catch (e) {
    return jsonResponse({ ok: false, error: (e as Error).message }, 500);
  }
});