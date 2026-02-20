// supabase/functions/backfill-all/index.ts
//
// One-shot backfill for new users. Called from enqueue-login-backfill (fire-and-forget).
// Does everything sequentially in ~20-30s:
//   1. Fetch 14 days WHOOP data (sleep, recovery, workouts, cycles) → write to metric tables
//   2. Fetch weather from Open-Meteo (14 past + 7 forecast) → write to user_weather_daily
//   3. Evaluate triggers for each day → write to triggers table
//   4. Calculate risk scores for each day → write to risk_score_daily + risk_score_live
//
// No jobs, no queues, no crons. Just one function, one user, one run.
// WHOOP fetch logic copied EXACTLY from sync-worker.

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

// ─── Helpers ─────────────────────────────────────────────────────────────────

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

function getLocalDate(timeZone: string, now = new Date()): string {
  const fmt = new Intl.DateTimeFormat("en-GB", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour12: false,
  });
  const parts = fmt.formatToParts(now);
  const get = (type: string) => parts.find((p) => p.type === type)?.value;
  return `${get("year")}-${get("month")}-${get("day")}`;
}

function numOrNull(v: unknown): number | null {
  const n = typeof v === "number" ? v : typeof v === "string" ? Number(v) : NaN;
  return Number.isFinite(n) ? n : null;
}

function intOrNull(v: unknown): number | null {
  const n = numOrNull(v);
  return n != null ? Math.trunc(n) : null;
}

function daysBetween(a: string, b: string): number {
  const da = new Date(a + "T00:00:00Z");
  const db = new Date(b + "T00:00:00Z");
  return Math.round((db.getTime() - da.getTime()) / 86400000);
}

// ─── WHOOP helpers — COPIED EXACTLY FROM sync-worker ─────────────────────────

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
): Promise<Record<string, unknown>[]> {
  const base = "https://api.prod.whoop.com/developer/v2";
  const out: Record<string, unknown>[] = [];
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
      for (const r of records) out.push((r ?? {}) as Record<string, unknown>);
    }

    const ntRaw = (json?.next_token ?? json?.nextToken) as unknown;
    const nt = typeof ntRaw === "string" ? ntRaw : null;
    nextToken = nt && nt.toLowerCase() !== "null" && nt.trim() ? nt : null;

    if (!nextToken) break;
  }

  return out;
}

function isExpiredSoon(expiresAtIso: string | null, nowUtc: Date): boolean {
  if (!expiresAtIso) return true;
  const exp = new Date(expiresAtIso).getTime();
  if (Number.isNaN(exp)) return true;
  return exp <= nowUtc.getTime() + 5 * 60_000;
}

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
  console.log(`[backfill-all] refresh body: grant_type=${body.get("grant_type")}, client_id=${body.get("client_id")?.slice(0,4)}..., refresh_token=${body.get("refresh_token")?.slice(0,8)}...`);;
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

function selectWhoopCycleByEndDate(records: any[], targetLocalDate: string, timeZone: string): any | null {
  let bestExact: any | null = null;
  let latest: any | null = null;
  let latestEndMs: number | null = null;

  for (const rec of records) {
    const endUtc = typeof rec?.end === "string" ? String(rec.end) : "";
    if (!endUtc) continue;
    const tzMin = whoopTzMinutes(rec);
    const endUtcMs = new Date(endUtc).getTime();
    if (Number.isNaN(endUtcMs)) continue;
    const endLocalMs = endUtcMs + tzMin * 60_000;
    const endLocalDate = getLocalTimeParts(timeZone, new Date(endLocalMs)).localDate;
    if (endLocalDate === targetLocalDate) bestExact = rec;
    if (latestEndMs == null || endLocalMs > latestEndMs) {
      latestEndMs = endLocalMs;
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

// ─── Trigger evaluation helpers (from trigger-worker) ────────────────────────

const RISK_EXPOSURE_COLUMNS = new Set(["max_tyramine_exposure", "max_alcohol_exposure", "max_gluten_exposure"]);
const RISK_RANK: Record<string, number> = { none: 0, low: 1, medium: 2, high: 3 };
const MIN_BASELINE_DAYS = 7;

function isoToMinutesSinceMidnight(iso: string, isBedtime: boolean): number | null {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  let mins = d.getUTCHours() * 60 + d.getUTCMinutes();
  if (isBedtime && d.getUTCHours() < 12) mins += 24 * 60;
  return mins;
}

function hoursToMinutes(hours: number, isBedtime: boolean): number {
  let mins = hours * 60;
  if (isBedtime && hours < 12) mins += 24 * 60;
  return mins;
}

function mean(arr: number[]): number | null {
  return arr.length ? arr.reduce((a, b) => a + b, 0) / arr.length : null;
}

function stdDev(arr: number[], avg: number): number | null {
  if (arr.length < 2) return null;
  return Math.sqrt(arr.map((x) => (x - avg) ** 2).reduce((a, b) => a + b, 0) / arr.length);
}

function formatValue(value: number, unit: string | null, isTime: boolean): string {
  if (isTime) {
    let mins = Math.round(value);
    if (mins >= 1440) mins -= 1440;
    return `${String(Math.floor(mins / 60)).padStart(2, "0")}:${String(mins % 60).padStart(2, "0")}`;
  }
  switch (unit) {
    case "hours": return `${value.toFixed(1)}h`;
    case "%": return `${value.toFixed(0)}%`;
    case "count": return `${Math.round(value)}`;
    default: return `${value.toFixed(1)}`;
  }
}

// ─── Risk calculation helpers (from risk-score-worker) ───────────────────────

function zoneForScore(score: number, high: number, mild: number, low: number): string {
  if (score >= high) return "HIGH";
  if (score >= mild) return "MILD";
  if (score >= low) return "LOW";
  return "NONE";
}

// ─── Upsert helpers ──────────────────────────────────────────────────────────

async function upsertDailyRow(
  supabase: ReturnType<typeof createClient>,
  table: string,
  row: Record<string, unknown>,
  onConflict = "user_id,date,source",
) {
  const { error } = await supabase.from(table).upsert(row, { onConflict });
  if (error) throw new Error(`Upsert to ${table} failed: ${error.message}`);
}

// ═════════════════════════════════════════════════════════════════════════════
// MAIN
// ═════════════════════════════════════════════════════════════════════════════

const BACKFILL_DAYS = 14;

serve(async (req) => {
  const t0 = Date.now();
  console.log("[backfill-all] === START ===", new Date().toISOString());

  try {
    const supabaseUrl = requireEnv("SUPABASE_URL");
    const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");
    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false },
      global: { headers: { "X-Client-Info": "backfill-all" } },
    });

    // ── Extract user_id from body or JWT ──
    let userId: string | null = null;
    try {
      const body = await req.clone().json();
      userId = typeof body?.user_id === "string" ? body.user_id : null;
    } catch { /* no body */ }

    if (!userId) {
      const authHeader = req.headers.get("Authorization") ?? "";
      const jwt = authHeader.replace("Bearer ", "").trim();
      if (jwt) {
        try {
          const payload = JSON.parse(atob(jwt.split(".")[1]));
          userId = payload.sub ?? null;
        } catch { /* bad jwt */ }
      }
    }

    if (!userId) return jsonResponse({ ok: false, error: "no user_id" }, 400);

    console.log(`[backfill-all] user=${userId}`);
    const nowUtc = new Date();
    const nowIso = nowUtc.toISOString();

    // ── Resolve timezone ──
    let timezone: string | null = null;
    const utcDate = nowIso.slice(0, 10);
    for (const d of [addDaysIsoDate(utcDate, -1), utcDate, addDaysIsoDate(utcDate, 1)]) {
      const { data } = await supabase
        .from("user_location_daily")
        .select("timezone")
        .eq("user_id", userId).eq("date", d)
        .not("timezone", "is", null)
        .order("updated_at", { ascending: false }).limit(1).maybeSingle();
      if (data?.timezone) { timezone = data.timezone; break; }
    }
    if (!timezone) timezone = "UTC";

    const localToday = getLocalDate(timezone, nowUtc);
    const dates: string[] = [];
    for (let i = BACKFILL_DAYS - 1; i >= 0; i--) dates.push(addDaysIsoDate(localToday, -i));

    console.log(`[backfill-all] timezone=${timezone}, localToday=${localToday}, dates=${dates[0]}..${dates[dates.length - 1]}`);

    // ── Load metric settings ──
    const { data: metrics } = await supabase
      .from("metric_settings")
      .select("metric,enabled,preferred_source,allowed_sources")
      .eq("user_id", userId).eq("enabled", true);
    const enabledMetrics = metrics ?? [];

    console.log(`[backfill-all] enabledMetrics=${enabledMetrics.length}`);

    const whoopEnabled = (metric: string) => {
      const m = enabledMetrics.find((x: any) => x.metric === metric);
      if (!m) return false;
      const pref = ((m as any).preferred_source ?? "").toLowerCase();
      const allowed = ((m as any).allowed_sources ?? []).map((s: string) => String(s).toLowerCase());
      return pref === "whoop" || allowed.includes("whoop");
    };

    const whoopEnabledForAny = enabledMetrics.some((m: any) => {
      const pref = ((m as any).preferred_source ?? "").toLowerCase();
      const allowed = ((m as any).allowed_sources ?? []).map((s: string) => String(s).toLowerCase());
      return pref === "whoop" || allowed.includes("whoop");
    });

    const locationEnabled = enabledMetrics.some((m: any) => {
      const name = ((m as any).metric ?? "").toLowerCase();
      return name === "user_location_daily" || name === "location" || name.includes("weather") || name.includes("temperature") || name.includes("pressure") || name.includes("humidity");
    });

    console.log(`[backfill-all] whoopEnabledForAny=${whoopEnabledForAny}, locationEnabled=${locationEnabled}`);

    const summary: Record<string, unknown> = { userId, timezone, localToday, dates: dates.length };

    // ══════════════════════════════════════════════════════════════════════
    // STEP 1: WHOOP DATA — per-day, EXACT same pattern as sync-worker
    // ══════════════════════════════════════════════════════════════════════

    let whoopResult: Record<string, unknown> = { status: "skipped" };

    if (whoopEnabledForAny) {
      const { data: tok, error: tokErr } = await supabase
        .from("whoop_tokens")
        .select("user_id,access_token,refresh_token,token_type,expires_at")
        .eq("user_id", userId).maybeSingle();

      console.log(`[backfill-all] whoop token found=${!!tok}, tokErr=${tokErr?.message ?? "none"}, expires_at=${tok?.expires_at ?? "null"}`);

      if (!tok) {
        whoopResult = { status: "no_token" };
      } else {
        let accessToken = tok.access_token;

        try {
          if (isExpiredSoon(tok.expires_at, nowUtc)) {
            console.log(`[backfill-all] refreshing token...`);
            const refreshed = await refreshWhoopToken(tok.refresh_token);
            const expiresInSec = refreshed.expires_in || 3600;
            const expiresAt = new Date(nowUtc.getTime() + expiresInSec * 1000).toISOString();

            const { error: updErr } = await supabase
              .from("whoop_tokens")
              .update({
                access_token: refreshed.access_token,
                refresh_token: refreshed.refresh_token,
                token_type: refreshed.token_type ?? tok.token_type ?? "Bearer",
                expires_at: expiresAt,
                updated_at: nowIso,
              })
              .eq("user_id", userId);

            if (updErr) throw new Error(updErr.message);

            accessToken = refreshed.access_token;
            console.log(`[backfill-all] token refreshed OK, new expires_at=${expiresAt}`);
          }
        } catch (e) {
          console.error(`[backfill-all] token refresh FAILED: ${(e as Error).message}`);
          console.log(`[backfill-all] falling back to existing access_token`);
        }

        if (accessToken) {
          let totalSleep = 0, totalRecovery = 0, totalCycles = 0, totalWorkouts = 0;

          for (const jobLocalDate of dates) {
            const { startUtcIso, endUtcIso } = getWhoopNoonWindowUtcIso(timezone, jobLocalDate);

            if (jobLocalDate === dates[0]) {
              console.log(`[backfill-all] first day WHOOP window: date=${jobLocalDate}, start=${startUtcIso}, end=${endUtcIso}`);
            }

            // ── SLEEP ──
            try {
              const recs = await whoopFetchPagedRecords("/activity/sleep", startUtcIso, endUtcIso, accessToken);
              const rec = recs.length ? selectWhoopSleepRecordByWakeup(recs as any[], jobLocalDate, timezone) : null;

              if (jobLocalDate === dates[0]) {
                console.log(`[backfill-all] sleep day=${jobLocalDate}: recs=${recs.length}, selected=${!!rec}`);
              }

              if (rec) {
                totalSleep++;
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
                  await upsertDailyRow(supabase, "sleep_duration_daily", { user_id: userId, date: jobLocalDate, value_hours: durationHours, source: "whoop", source_measure_id: sourceMeasureId, created_at: nowIso, updated_at: nowIso });
                }
                if (whoopEnabled("sleep_score_daily") && perfPct != null) {
                  await upsertDailyRow(supabase, "sleep_score_daily", { user_id: userId, date: jobLocalDate, value_pct: perfPct, source: "whoop", source_measure_id: sourceMeasureId, created_at: nowIso, updated_at: nowIso });
                }
                if (whoopEnabled("sleep_efficiency_daily") && effPct != null) {
                  await upsertDailyRow(supabase, "sleep_efficiency_daily", { user_id: userId, date: jobLocalDate, value_pct: effPct, source: "whoop", source_measure_id: sourceMeasureId, created_at: nowIso, updated_at: nowIso });
                }
                if (whoopEnabled("sleep_disturbances_daily")) {
                  await upsertDailyRow(supabase, "sleep_disturbances_daily", { user_id: userId, date: jobLocalDate, value_count: disturbances, source: "whoop", source_measure_id: sourceMeasureId, created_at: nowIso, updated_at: nowIso });
                }
                if (whoopEnabled("sleep_stages_daily") && (lightMs > 0 || swsMs > 0 || remMs > 0)) {
                  await upsertDailyRow(supabase, "sleep_stages_daily", { user_id: userId, date: jobLocalDate, source: "whoop", source_measure_id: sourceMeasureId, value_sws_hm: swsMs / 3_600_000.0, value_rem_hm: remMs / 3_600_000.0, value_light_hm: lightMs / 3_600_000.0, created_at: nowIso }, "user_id,date,source");
                }
                if (whoopEnabled("fell_asleep_time_daily") && fellAsleepAt) {
                  await upsertDailyRow(supabase, "fell_asleep_time_daily", { user_id: userId, date: jobLocalDate, value_at: fellAsleepAt, source: "whoop", source_measure_id: sourceMeasureId, created_at: nowIso, updated_at: nowIso });
                }
                if (whoopEnabled("woke_up_time_daily") && wokeUpAt) {
                  await upsertDailyRow(supabase, "woke_up_time_daily", { user_id: userId, date: jobLocalDate, value_at: wokeUpAt, source: "whoop", source_measure_id: sourceMeasureId, created_at: nowIso, updated_at: nowIso });
                }
              }
            } catch (e) {
              console.error(`[backfill-all] SLEEP ERROR day=${jobLocalDate}: ${(e as Error).message}`);
            }

            // ── RECOVERY ──
            try {
              const recs = await whoopFetchPagedRecords("/recovery", startUtcIso, endUtcIso, accessToken);
              const rec = recs.length ? (recs[0] as any) : null;

              if (jobLocalDate === dates[0]) {
                console.log(`[backfill-all] recovery day=${jobLocalDate}: recs=${recs.length}, selected=${!!rec}`);
              }

              if (rec) {
                totalRecovery++;
                const scoreObj = rec?.score ?? null;
                const sourceMeasureId = whoopMeasureId(rec);
                const recoveryPct = numOrNull(scoreObj?.recovery_score);
                const restingHr = numOrNull(scoreObj?.resting_heart_rate);
                const hrvMs = numOrNull(scoreObj?.hrv_rmssd_milli);
                const skinTemp = numOrNull(scoreObj?.skin_temp_celsius);
                const spo2 = numOrNull(scoreObj?.spo2_percentage);

                if (whoopEnabled("recovery_score_daily") && recoveryPct != null) {
                  await upsertDailyRow(supabase, "recovery_score_daily", { user_id: userId, date: jobLocalDate, value_pct: recoveryPct, source: "whoop", source_measure_id: sourceMeasureId, created_at: nowIso });
                }
                if (whoopEnabled("resting_hr_daily") && restingHr != null) {
                  await upsertDailyRow(supabase, "resting_hr_daily", { user_id: userId, date: jobLocalDate, value_bpm: restingHr, source: "whoop", source_measure_id: sourceMeasureId, created_at: nowIso });
                }
                if (whoopEnabled("hrv_daily") && hrvMs != null) {
                  await upsertDailyRow(supabase, "hrv_daily", { user_id: userId, date: jobLocalDate, value_rmssd_ms: hrvMs, source: "whoop", source_measure_id: sourceMeasureId, created_at: nowIso });
                }
                if (whoopEnabled("skin_temp_daily") && skinTemp != null) {
                  await upsertDailyRow(supabase, "skin_temp_daily", { user_id: userId, date: jobLocalDate, value_celsius: skinTemp, source: "whoop", source_measure_id: sourceMeasureId, created_at: nowIso });
                }
                if (whoopEnabled("spo2_daily") && spo2 != null) {
                  await upsertDailyRow(supabase, "spo2_daily", { user_id: userId, date: jobLocalDate, value_pct: spo2, source: "whoop", source_measure_id: sourceMeasureId, created_at: nowIso });
                }
              }
            } catch (e) {
              console.error(`[backfill-all] RECOVERY ERROR day=${jobLocalDate}: ${(e as Error).message}`);
            }

            // ── CYCLE / STRAIN ──
            try {
              if (whoopEnabled("strain_daily")) {
                const recs = await whoopFetchPagedRecords("/cycle", startUtcIso, endUtcIso, accessToken);
                const rec = recs.length ? selectWhoopCycleByEndDate(recs as any[], jobLocalDate, timezone) : null;

                if (rec) {
                  const scoreState = typeof rec?.score_state === "string" ? String(rec.score_state) : "";
                  const scoreObj = rec?.score ?? null;
                  const strain = numOrNull(scoreObj?.strain);

                  if (scoreState === "SCORED" && strain != null) {
                    totalCycles++;
                    const sourceMeasureId = whoopMeasureId(rec);
                    await upsertDailyRow(supabase, "strain_daily", { user_id: userId, date: jobLocalDate, value_strain: strain, value_kilojoule: numOrNull(scoreObj?.kilojoule), avg_heart_rate: intOrNull(scoreObj?.average_heart_rate), max_heart_rate: intOrNull(scoreObj?.max_heart_rate), source: "whoop", source_measure_id: sourceMeasureId, created_at: nowIso });
                  }
                }
              }
            } catch (e) {
              console.error(`[backfill-all] CYCLE ERROR day=${jobLocalDate}: ${(e as Error).message}`);
            }

            // ── WORKOUTS / HR ZONES ──
            try {
              const wantDailyHigh = whoopEnabled("time_in_high_hr_zones_daily");
              const wantSessions = whoopEnabled("activity_hr_zones_sessions");

              if (wantDailyHigh || wantSessions) {
                const workouts = await whoopFetchPagedRecords("/activity/workout", startUtcIso, endUtcIso, accessToken);
                const ws = (workouts ?? []) as any[];

                const dayWorkouts = ws.filter((w) => {
                  const endUtc = typeof w?.end === "string" ? String(w.end) : "";
                  if (!endUtc) return false;
                  const tzMin = whoopTzMinutes(w);
                  const endUtcMs = new Date(endUtc).getTime();
                  if (Number.isNaN(endUtcMs)) return false;
                  const endLocalMs = endUtcMs + tzMin * 60_000;
                  return getLocalTimeParts(timezone, new Date(endLocalMs)).localDate === jobLocalDate;
                });

                if (dayWorkouts.length > 0) {
                  totalWorkouts++;

                  const zoneMs = (rec: any, key: string): number => {
                    const zd = rec?.score?.zone_durations ?? null;
                    const v = zd && typeof zd === "object" ? (zd as any)[key] : null;
                    const n = numOrNull(v);
                    return n != null && n > 0 ? n : 0;
                  };

                  let sumZ3 = 0, sumZ4 = 0, sumZ5 = 0, sumZ6 = 0;
                  for (const w of dayWorkouts) {
                    sumZ3 += zoneMs(w, "zone_three_milli");
                    sumZ4 += zoneMs(w, "zone_four_milli");
                    sumZ5 += zoneMs(w, "zone_five_milli");
                    sumZ6 += zoneMs(w, "zone_six_milli");
                  }

                  if (wantDailyHigh) {
                    const highMinutes = (sumZ3 + sumZ4 + sumZ5 + sumZ6) / 60_000.0;
                    if (highMinutes > 0) {
                      const dailySid = `whoop_workout_daily_${jobLocalDate}`;
                      const { error } = await supabase.from("time_in_high_hr_zones_daily").upsert({ user_id: userId, date: jobLocalDate, value_minutes: highMinutes, source: "whoop", source_measure_id: dailySid, activity_type: "daily_total", created_at: nowIso }, { onConflict: "user_id,source,source_measure_id" });
                      if (error) console.warn(`[backfill-all] hr_zones upsert error: ${error.message}`);
                    }
                  }
                }
              }
            } catch (e) {
              console.error(`[backfill-all] WORKOUT ERROR day=${jobLocalDate}: ${(e as Error).message}`);
            }
          } // end for-each date

          whoopResult = { status: "done", sleep: totalSleep, recovery: totalRecovery, cycles: totalCycles, workouts: totalWorkouts };
          console.log(`[backfill-all] WHOOP done: sleep=${totalSleep}, recovery=${totalRecovery}, cycles=${totalCycles}, workouts=${totalWorkouts}`);
        }
      }
    }

    summary.whoop = whoopResult;

    // ══════════════════════════════════════════════════════════════════════
    // STEP 2: WEATHER
    // ══════════════════════════════════════════════════════════════════════

    let weatherResult: Record<string, unknown> = { status: "skipped" };

    if (locationEnabled) {
      try {
        let userLat: number | null = null, userLon: number | null = null;
        for (const d of [localToday, addDaysIsoDate(localToday, -1), addDaysIsoDate(localToday, -2)]) {
          const { data } = await supabase
            .from("user_location_daily")
            .select("latitude,longitude")
            .eq("user_id", userId).eq("date", d).maybeSingle();
          if (data?.latitude && data?.longitude) {
            userLat = data.latitude;
            userLon = data.longitude;
            break;
          }
        }

        console.log(`[backfill-all] weather: lat=${userLat}, lon=${userLon}`);

        if (!userLat || !userLon) {
          weatherResult = { status: "no_location" };
        } else {
          const { data: cities } = await supabase
            .from("city")
            .select("id,name,lat,lon,timezone")
            .gte("lat", userLat - 2).lte("lat", userLat + 2)
            .gte("lon", userLon - 2).lte("lon", userLon + 2)
            .limit(100);

          let nearestCity: any = null;
          let minDist = Infinity;
          for (const c of cities ?? []) {
            const dlat = (c.lat - userLat) * Math.PI / 180;
            const dlon = (c.lon - userLon) * Math.PI / 180;
            const a = Math.sin(dlat / 2) ** 2 + Math.cos(userLat * Math.PI / 180) * Math.cos(c.lat * Math.PI / 180) * Math.sin(dlon / 2) ** 2;
            const dist = 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
            if (dist < minDist) { minDist = dist; nearestCity = c; }
          }

          console.log(`[backfill-all] weather: nearestCity=${nearestCity?.name ?? "none"}, dist=${minDist.toFixed(1)}km`);

          if (!nearestCity) {
            weatherResult = { status: "no_city" };
          } else {
            const tz = nearestCity.timezone ?? "auto";
            const u = new URL("https://api.open-meteo.com/v1/forecast");
            u.searchParams.set("latitude", String(nearestCity.lat));
            u.searchParams.set("longitude", String(nearestCity.lon));
            u.searchParams.set("daily", "temperature_2m_min,temperature_2m_max,temperature_2m_mean,surface_pressure_mean,surface_pressure_min,surface_pressure_max,relative_humidity_2m_mean,relative_humidity_2m_min,relative_humidity_2m_max,uv_index_max,wind_speed_10m_mean,wind_speed_10m_max,weathercode");
            u.searchParams.set("past_days", "14");
            u.searchParams.set("forecast_days", "7");
            u.searchParams.set("timezone", tz);

            const resp = await fetch(u.toString(), { headers: { accept: "application/json" } });
            if (!resp.ok) throw new Error(`Open-Meteo ${resp.status}`);
            const d = (await resp.json())?.daily;
            if (!d?.time) throw new Error("missing daily block");

            console.log(`[backfill-all] weather: Open-Meteo returned ${d.time.length} days`);

            const { error: rpcErr } = await supabase.rpc("upsert_city_weather_batch", {
              p_city_id: nearestCity.id,
              p_days: d.time,
              p_tmin: d.temperature_2m_min, p_tmax: d.temperature_2m_max, p_tmean: d.temperature_2m_mean,
              p_pmean: d.surface_pressure_mean, p_pmin: d.surface_pressure_min, p_pmax: d.surface_pressure_max,
              p_hmean: d.relative_humidity_2m_mean, p_hmin: d.relative_humidity_2m_min, p_hmax: d.relative_humidity_2m_max,
              p_uv_max: d.uv_index_max, p_wind_mean: d.wind_speed_10m_mean, p_wind_max: d.wind_speed_10m_max,
              p_weather_code: d.weathercode,
            });

            if (rpcErr) console.error(`[backfill-all] weather RPC error: ${rpcErr.message}`);

            let weatherDays = 0;
            const { data: cityRows } = await supabase
              .from("city_weather_daily")
              .select("*")
              .eq("city_id", nearestCity.id)
              .gte("day", dates[0])
              .order("day", { ascending: true });

            for (const row of cityRows ?? []) {
              const { error: uwErr } = await supabase.from("user_weather_daily").upsert({
                user_id: userId, date: row.day,
                temp_c_min: row.temp_c_min, temp_c_max: row.temp_c_max, temp_c_mean: row.temp_c_mean,
                pressure_hpa_min: row.pressure_hpa_min, pressure_hpa_max: row.pressure_hpa_max, pressure_hpa_mean: row.pressure_hpa_mean,
                humidity_pct_min: row.humidity_pct_min, humidity_pct_max: row.humidity_pct_max, humidity_pct_mean: row.humidity_pct_mean,
                wind_speed_mps_mean: row.wind_speed_mps_mean, wind_speed_mps_max: row.wind_speed_mps_max,
                uv_index_max: row.uv_index_max, weather_code: row.weather_code, is_thunderstorm_day: row.is_thunderstorm_day,
                city_id: nearestCity.id, timezone: timezone, updated_at: nowIso,
              }, { onConflict: "user_id,date" });
              if (uwErr) console.warn(`[backfill-all] user_weather upsert error: ${uwErr.message}`);
              else weatherDays++;
            }

            weatherResult = { status: "done", city: nearestCity.name, days: weatherDays };
            console.log(`[backfill-all] weather done: city=${nearestCity.name}, days=${weatherDays}`);
          }
        }
      } catch (e) {
        console.error(`[backfill-all] WEATHER ERROR: ${(e as Error).message}`);
        weatherResult = { status: "error", error: (e as Error).message };
      }
    } else {
      console.log(`[backfill-all] weather skipped: locationEnabled=${locationEnabled}`);
    }

    summary.weather = weatherResult;

    // ══════════════════════════════════════════════════════════════════════
    // STEP 3: EVALUATE TRIGGERS FOR EACH DAY
    // ══════════════════════════════════════════════════════════════════════

    let triggerResult: Record<string, unknown> = { status: "skipped" };

    try {
      const { data: defsRaw } = await supabase
        .from("user_triggers")
        .select("id,label,category,direction,default_threshold,unit,metric_table,metric_column,baseline_days,enabled_by_default,metric_type,display_group")
        .eq("user_id", userId)
        .not("direction", "is", null);

      const definitions = defsRaw ?? [];
      console.log(`[backfill-all] trigger definitions=${definitions.length}`);

      if (definitions.length === 0) {
        triggerResult = { status: "no_definitions" };
      } else {
        const { data: userSettings } = await supabase
          .from("trigger_settings")
          .select("trigger_type,enabled,threshold")
          .eq("user_id", userId);

        const settingsMap = new Map<string, any>();
        for (const s of userSettings ?? []) settingsMap.set(s.trigger_type, s);

        const activeDefs = definitions.filter((def: any) => {
          const setting = settingsMap.get(def.label);
          return setting ? setting.enabled : def.enabled_by_default;
        });

        console.log(`[backfill-all] active trigger defs=${activeDefs.length}`);

        let triggersCreated = 0;
        let triggersSkipped = 0;

        const metricGroups = new Map<string, any[]>();
        for (const def of activeDefs) {
          const key = `${def.metric_table}::${def.metric_column}`;
          const arr = metricGroups.get(key) ?? [];
          arr.push(def);
          metricGroups.set(key, arr);
        }

        for (const localDate of dates) {
          const firedGroups = new Set<string>();

          for (const [_key, defs] of metricGroups) {
            const table = defs[0].metric_table;
            const column = defs[0].metric_column;
            const baselineDays = defs[0].baseline_days || 14;
            const isTimeMetric = defs[0].unit === "time";
            const isBedtime = table === "fell_asleep_time_daily";
            const isRiskColumn = RISK_EXPOSURE_COLUMNS.has(column);

            const { data: todayRows } = await supabase
              .from(table).select(column).eq("user_id", userId).eq("date", localDate).limit(1);

            const rawValue = todayRows?.[0] ? (todayRows[0] as any)[column] : null;
            if (rawValue == null) continue;

            let numericValue: number | null = null;
            if (isRiskColumn) {
              numericValue = RISK_RANK[typeof rawValue === "string" ? rawValue.toLowerCase() : "none"] ?? 0;
            } else if (isTimeMetric) {
              numericValue = isoToMinutesSinceMidnight(String(rawValue), isBedtime);
            } else {
              numericValue = typeof rawValue === "number" ? rawValue : Number(rawValue);
              if (Number.isNaN(numericValue)) numericValue = null;
            }
            if (numericValue == null) continue;

            let avg: number | null = null, sd: number | null = null;
            const startDate = addDaysIsoDate(localDate, -baselineDays);
            const endDate = addDaysIsoDate(localDate, -1);
            const { data: histRows } = await supabase
              .from(table).select(column).eq("user_id", userId).gte("date", startDate).lte("date", endDate);

            const baselineValues = (histRows ?? []).map((r: any) => {
              const v = r[column];
              if (isRiskColumn) return RISK_RANK[typeof v === "string" ? v.toLowerCase() : "none"] ?? 0;
              if (isTimeMetric) return isoToMinutesSinceMidnight(String(v), isBedtime);
              const n = typeof v === "number" ? v : Number(v);
              return Number.isFinite(n) ? n : null;
            }).filter((v: any): v is number => v != null);

            if (baselineValues.length >= MIN_BASELINE_DAYS) {
              avg = mean(baselineValues);
              sd = avg != null ? stdDev(baselineValues, avg) : null;
            }

            for (const def of defs) {
              const setting = settingsMap.get(def.label);
              const thresh = setting?.threshold ?? def.default_threshold;

              let fired = false;
              const reasons: string[] = [];

              if (thresh != null) {
                const ct = isTimeMetric ? hoursToMinutes(thresh, isBedtime) : thresh;
                if (def.direction === "low" && numericValue < ct) { fired = true; reasons.push(isTimeMetric ? `before ${thresh}:00` : `below ${formatValue(thresh, def.unit, false)}`); }
                else if (def.direction === "high" && numericValue > ct) { fired = true; reasons.push(isTimeMetric ? `after ${thresh}:00` : `above ${formatValue(thresh, def.unit, false)}`); }
              }

              if (avg != null && sd != null && sd > 0) {
                if (def.direction === "low" && numericValue < avg - 2 * sd) { if (!fired) fired = true; reasons.push(`2SD below avg`); }
                else if (def.direction === "high" && numericValue > avg + 2 * sd) { if (!fired) fired = true; reasons.push(`2SD above avg`); }
              }

              if (fired) {
                const triggerName = def.display_group ?? def.label;
                if (firedGroups.has(triggerName)) continue;

                const notes = `${def.label}: ${formatValue(numericValue, def.unit, isTimeMetric)} — ${reasons.join("; ")}`;
                const dayStart = `${localDate}T00:00:00Z`;
                const dayEnd = `${localDate}T23:59:59Z`;

                const { data: existing } = await supabase
                  .from("triggers").select("id")
                  .eq("user_id", userId).eq("type", triggerName).eq("source", "system")
                  .gte("start_at", dayStart).lte("start_at", dayEnd).limit(1);

                if (!existing || existing.length === 0) {
                  const { error: insErr } = await supabase.from("triggers").insert({ user_id: userId, type: triggerName, source: "system", start_at: `${localDate}T09:00:00Z`, notes, active: true });
                  if (!insErr) triggersCreated++;
                  else console.warn(`[backfill-all] trigger insert error: ${insErr.message}`);
                } else {
                  triggersSkipped++;
                }
                firedGroups.add(triggerName);
              }
            }
          }
        }

        triggerResult = { status: "done", created: triggersCreated, skipped: triggersSkipped, definitions: activeDefs.length };
        console.log(`[backfill-all] triggers done: created=${triggersCreated}, skipped=${triggersSkipped}`);
      }
    } catch (e) {
      console.error(`[backfill-all] TRIGGER ERROR: ${(e as Error).message}`);
      triggerResult = { status: "error", error: (e as Error).message };
    }

    summary.triggers = triggerResult;

    // ══════════════════════════════════════════════════════════════════════
    // STEP 3b: EVALUATE PRODROMES FOR EACH DAY (same logic as triggers)
    // ══════════════════════════════════════════════════════════════════════

    let prodromeResult: Record<string, unknown> = { status: "skipped" };

    try {
      const { data: pDefsRaw } = await supabase
        .from("user_prodromes")
        .select("id,label,category,direction,default_threshold,unit,metric_table,metric_column,baseline_days,enabled_by_default,metric_type,display_group")
        .eq("user_id", userId)
        .not("direction", "is", null);

      const pDefinitions = pDefsRaw ?? [];
      console.log(`[backfill-all] prodrome definitions=${pDefinitions.length}`);

      if (pDefinitions.length === 0) {
        prodromeResult = { status: "no_definitions" };
      } else {
        const { data: pUserSettings } = await supabase
          .from("prodrome_settings")
          .select("prodrome_type,enabled,threshold")
          .eq("user_id", userId);

        const pSettingsMap = new Map<string, any>();
        for (const s of pUserSettings ?? []) pSettingsMap.set(s.prodrome_type, s);

        const pActiveDefs = pDefinitions.filter((def: any) => {
          const setting = pSettingsMap.get(def.label);
          return setting ? setting.enabled : def.enabled_by_default;
        });

        console.log(`[backfill-all] active prodrome defs=${pActiveDefs.length}`);

        let prodromesCreated = 0;
        let prodromesSkipped = 0;

        const pMetricGroups = new Map<string, any[]>();
        for (const def of pActiveDefs) {
          const key = `${def.metric_table}::${def.metric_column}`;
          const arr = pMetricGroups.get(key) ?? [];
          arr.push(def);
          pMetricGroups.set(key, arr);
        }

        for (const localDate of dates) {
          const firedGroups = new Set<string>();

          for (const [_key, defs] of pMetricGroups) {
            const table = defs[0].metric_table;
            const column = defs[0].metric_column;
            const baselineDays = defs[0].baseline_days || 14;
            const isTimeMetric = defs[0].unit === "time";
            const isBedtime = table === "fell_asleep_time_daily";
            const isRiskColumn = RISK_EXPOSURE_COLUMNS.has(column);

            const { data: todayRows } = await supabase
              .from(table).select(column).eq("user_id", userId).eq("date", localDate).limit(1);

            const rawValue = todayRows?.[0] ? (todayRows[0] as any)[column] : null;
            if (rawValue == null) continue;

            let numericValue: number | null = null;
            if (isRiskColumn) {
              numericValue = RISK_RANK[typeof rawValue === "string" ? rawValue.toLowerCase() : "none"] ?? 0;
            } else if (isTimeMetric) {
              numericValue = isoToMinutesSinceMidnight(String(rawValue), isBedtime);
            } else {
              numericValue = typeof rawValue === "number" ? rawValue : Number(rawValue);
              if (Number.isNaN(numericValue)) numericValue = null;
            }
            if (numericValue == null) continue;

            let avg: number | null = null, sd: number | null = null;
            const startDate = addDaysIsoDate(localDate, -baselineDays);
            const endDate = addDaysIsoDate(localDate, -1);
            const { data: histRows } = await supabase
              .from(table).select(column).eq("user_id", userId).gte("date", startDate).lte("date", endDate);

            const baselineValues = (histRows ?? []).map((r: any) => {
              const v = r[column];
              if (isRiskColumn) return RISK_RANK[typeof v === "string" ? v.toLowerCase() : "none"] ?? 0;
              if (isTimeMetric) return isoToMinutesSinceMidnight(String(v), isBedtime);
              const n = typeof v === "number" ? v : Number(v);
              return Number.isFinite(n) ? n : null;
            }).filter((v: any): v is number => v != null);

            if (baselineValues.length >= MIN_BASELINE_DAYS) {
              avg = mean(baselineValues);
              sd = avg != null ? stdDev(baselineValues, avg) : null;
            }

            for (const def of defs) {
              const setting = pSettingsMap.get(def.label);
              const thresh = setting?.threshold ?? def.default_threshold;

              let fired = false;
              const reasons: string[] = [];

              if (thresh != null) {
                const ct = isTimeMetric ? hoursToMinutes(thresh, isBedtime) : thresh;
                if (def.direction === "low" && numericValue < ct) { fired = true; reasons.push(isTimeMetric ? `before ${thresh}:00` : `below ${formatValue(thresh, def.unit, false)}`); }
                else if (def.direction === "high" && numericValue > ct) { fired = true; reasons.push(isTimeMetric ? `after ${thresh}:00` : `above ${formatValue(thresh, def.unit, false)}`); }
              }

              if (avg != null && sd != null && sd > 0) {
                if (def.direction === "low" && numericValue < avg - 2 * sd) { if (!fired) fired = true; reasons.push(`2SD below avg`); }
                else if (def.direction === "high" && numericValue > avg + 2 * sd) { if (!fired) fired = true; reasons.push(`2SD above avg`); }
              }

              if (fired) {
                const prodromeName = def.display_group ?? def.label;
                if (firedGroups.has(prodromeName)) continue;

                const notes = `${def.label}: ${formatValue(numericValue, def.unit, isTimeMetric)} — ${reasons.join("; ")}`;
                const dayStart = `${localDate}T00:00:00Z`;
                const dayEnd = `${localDate}T23:59:59Z`;

                const { data: existing } = await supabase
                  .from("prodromes").select("id")
                  .eq("user_id", userId).eq("type", prodromeName).eq("source", "system")
                  .gte("start_at", dayStart).lte("start_at", dayEnd).limit(1);

                if (!existing || existing.length === 0) {
                  const { error: insErr } = await supabase.from("prodromes").insert({ user_id: userId, type: prodromeName, source: "system", start_at: `${localDate}T09:00:00Z`, notes, active: true });
                  if (!insErr) prodromesCreated++;
                  else console.warn(`[backfill-all] prodrome insert error: ${insErr.message}`);
                } else {
                  prodromesSkipped++;
                }
                firedGroups.add(prodromeName);
              }
            }
          }
        }

        prodromeResult = { status: "done", created: prodromesCreated, skipped: prodromesSkipped, definitions: pActiveDefs.length };
        console.log(`[backfill-all] prodromes done: created=${prodromesCreated}, skipped=${prodromesSkipped}`);
      }
    } catch (e) {
      console.error(`[backfill-all] PRODROME ERROR: ${(e as Error).message}`);
      prodromeResult = { status: "error", error: (e as Error).message };
    }

    summary.prodromes = prodromeResult;

    // ══════════════════════════════════════════════════════════════════════
    // STEP 4: CALCULATE RISK SCORES FOR EACH DAY
    // ══════════════════════════════════════════════════════════════════════

    let riskResult: Record<string, unknown> = { status: "skipped" };

    try {
      const { data: decayRows } = await supabase
        .from("risk_decay_weights")
        .select("severity,day_0,day_1,day_2,day_3,day_4,day_5,day_6")
        .eq("user_id", userId);

      if (!decayRows || decayRows.length === 0) {
        riskResult = { status: "no_decay_config" };
        console.log(`[backfill-all] risk skipped: no decay config`);
      } else {
        const decayMap: Record<string, number[]> = {};
        for (const row of decayRows) {
          decayMap[row.severity.toUpperCase()] = [row.day_0, row.day_1, row.day_2, row.day_3, row.day_4, row.day_5, row.day_6];
        }

        const { data: thresholdRows } = await supabase
          .from("risk_gauge_thresholds")
          .select("zone,min_value")
          .eq("user_id", userId);

        const thresholdMap: Record<string, number> = {};
        for (const row of thresholdRows ?? []) thresholdMap[row.zone.toUpperCase()] = row.min_value;
        const thresholdHigh = thresholdMap["HIGH"] ?? 10.0;
        const thresholdMild = thresholdMap["MILD"] ?? 5.0;
        const thresholdLow = thresholdMap["LOW"] ?? 3.0;
        const gaugeMax = thresholdHigh * 1.2;

        const { data: triggerPool } = await supabase
          .from("user_triggers")
          .select("label,prediction_value,category,display_group")
          .eq("user_id", userId);

        const triggerSevMap: Record<string, string> = {};
        for (const t of triggerPool ?? []) {
          const sev = ((t as any).prediction_value || "NONE").toUpperCase();
          triggerSevMap[(t as any).label.toLowerCase()] = sev;
          if ((t as any).display_group) triggerSevMap[(t as any).display_group.toLowerCase()] = sev;
        }

        const { data: prodromePool } = await supabase
          .from("user_prodromes")
          .select("label,prediction_value")
          .eq("user_id", userId);

        const prodromeSevMap: Record<string, string> = {};
        for (const p of prodromePool ?? []) {
          prodromeSevMap[(p as any).label.toLowerCase()] = ((p as any).prediction_value || "NONE").toUpperCase();
        }

        let riskDaysWritten = 0;

        const lastDate = dates[dates.length - 1];
        const riskDates = [...dates];
        for (let i = 1; i <= 6; i++) {
          riskDates.push(addDaysIsoDate(lastDate, i));
        }

        for (const localDate of riskDates) {
          const cutoffStart = addDaysIsoDate(localDate, -7) + "T00:00:00Z";
          const cutoffEnd = addDaysIsoDate(localDate, 1) + "T00:00:00Z";

          const { data: triggerEvents } = await supabase
            .from("triggers")
            .select("type,start_at")
            .eq("user_id", userId)
            .gte("start_at", cutoffStart).lte("start_at", cutoffEnd);

          const { data: prodromeEvents } = await supabase
            .from("prodromes")
            .select("type,start_at")
            .eq("user_id", userId)
            .gte("start_at", cutoffStart).lte("start_at", cutoffEnd);

          let dayScore = 0;
          const contributions: { name: string; contribution: number; severity: string }[] = [];

          for (const t of triggerEvents ?? []) {
            if (!t.type || !t.start_at) continue;
            const severity = triggerSevMap[t.type.toLowerCase()] || "NONE";
            if (severity === "NONE") continue;
            const eventDate = t.start_at.substring(0, 10);
            const daysAgo = daysBetween(eventDate, localDate);
            if (daysAgo < 0 || daysAgo > 6) continue;
            const weights = decayMap[severity];
            if (!weights) continue;
            const weight = weights[daysAgo] ?? 0;
            if (weight > 0) {
              dayScore += weight;
              contributions.push({ name: t.type, contribution: weight, severity });
            }
          }

          for (const p of prodromeEvents ?? []) {
            if (!p.type || !p.start_at) continue;
            const severity = prodromeSevMap[p.type.toLowerCase()] || "NONE";
            if (severity === "NONE") continue;
            const eventDate = p.start_at.substring(0, 10);
            const daysAgo = daysBetween(eventDate, localDate);
            if (daysAgo < 0 || daysAgo > 6) continue;
            const weights = decayMap[severity];
            if (!weights) continue;
            const weight = weights[daysAgo] ?? 0;
            if (weight > 0) {
              dayScore += weight;
              contributions.push({ name: p.type, contribution: weight, severity });
            }
          }

          const grouped: Record<string, { total: number; severity: string }> = {};
          for (const c of contributions) {
            if (!grouped[c.name]) grouped[c.name] = { total: 0, severity: c.severity };
            grouped[c.name].total += c.contribution;
          }
          // FIX: removed .slice(0, 5) — store ALL triggers so the sum matches the score
          const topTriggers = Object.entries(grouped)
            .map(([name, { total, severity }]) => ({ name, score: Math.round(total), severity }))
            .sort((a, b) => b.score - a.score);

          // Use the sum of rounded trigger scores so gauge matches what user sees
          const roundedScore = topTriggers.reduce((sum, t) => sum + t.score, 0);
          const dayPercent = Math.min(100, Math.max(0, Math.round((roundedScore / gaugeMax) * 100)));
          const dayZone = zoneForScore(roundedScore, thresholdHigh, thresholdMild, thresholdLow);

          const { error: riskErr } = await supabase.from("risk_score_daily").upsert({
            user_id: userId, date: localDate,
            score: roundedScore, zone: dayZone, percent: dayPercent,
            top_triggers: topTriggers, created_at: nowIso,
          }, { onConflict: "user_id,date" });

          if (riskErr) console.warn(`[backfill-all] risk upsert error day=${localDate}: ${riskErr.message}`);
          else riskDaysWritten++;
        }

        // Write risk_score_live for today with forecast + dayRisks
        const todayDate = dates[dates.length - 1];
        const { data: todayDaily } = await supabase
          .from("risk_score_daily")
          .select("score,zone,percent,top_triggers")
          .eq("user_id", userId).eq("date", todayDate).maybeSingle();

        if (todayDaily) {
          const forecastDates: string[] = [];
          for (let i = 0; i <= 6; i++) forecastDates.push(addDaysIsoDate(todayDate, i));

          const { data: forecastRows } = await supabase
            .from("risk_score_daily")
            .select("date,score,zone,percent,top_triggers")
            .eq("user_id", userId)
            .in("date", forecastDates)
            .order("date", { ascending: true });

          const forecastMap = new Map<string, any>();
          for (const r of forecastRows ?? []) forecastMap.set(r.date, r);

          const forecast: number[] = [];
          const dayRisks: any[] = [];
          for (const d of forecastDates) {
            const row = forecastMap.get(d);
            forecast.push(row?.percent ?? 0);
            dayRisks.push({
              date: d,
              score: row?.score ?? 0,
              zone: row?.zone ?? "NONE",
              percent: row?.percent ?? 0,
              top_triggers: row?.top_triggers ?? [],
            });
          }

          const { error: liveErr } = await supabase.from("risk_score_live").upsert({
            user_id: userId,
            score: todayDaily.score,
            zone: todayDaily.zone,
            percent: todayDaily.percent,
            top_triggers: todayDaily.top_triggers,
            forecast: forecast,
            day_risks: dayRisks,
            updated_at: nowIso,
          }, { onConflict: "user_id" });
          if (liveErr) console.warn(`[backfill-all] risk_score_live error: ${liveErr.message}`);
        }

        riskResult = { status: "done", daysWritten: riskDaysWritten };
        console.log(`[backfill-all] risk done: daysWritten=${riskDaysWritten}`);
      }
    } catch (e) {
      console.error(`[backfill-all] RISK ERROR: ${(e as Error).message}`);
      riskResult = { status: "error", error: (e as Error).message };
    }

    summary.risk = riskResult;

    // ── Audit ──
    try {
      await supabase.from("edge_audit").insert({
        fn: "backfill-all",
        user_id: userId,
        ok: true,
        stage: "complete",
        message: JSON.stringify(summary).slice(0, 500),
      });
    } catch (_) { /* best-effort */ }

    const elapsed = Date.now() - t0;
    summary.elapsedMs = elapsed;
    console.log(`[backfill-all] === DONE in ${elapsed}ms ===`, JSON.stringify(summary));

    return jsonResponse({ ok: true, summary });

  } catch (e) {
    console.error("[backfill-all] FATAL ERROR:", e);
    return jsonResponse({ ok: false, error: (e as Error).message }, 500);
  }
});