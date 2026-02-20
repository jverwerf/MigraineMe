// supabase/functions/backfill-metrics/index.ts
//
// One-shot metrics backfill for new users (fast).
// Called from enqueue-login-backfill (fire-and-forget).
// Does everything sequentially in ~10-25s depending on WHOOP API:
//   1. Fetch 14 days WHOOP data (sleep, recovery, workouts, cycles) → write to metric tables
//   2. Fetch weather from Open-Meteo (14 past + 7 forecast) → write to user_weather_daily
//
// No triggers, no prodromes, no risk calculation.
// WHOOP + timezone helpers copied EXACTLY from sync-worker (via backfill-all).

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

// EXACT same function as sync-worker
function getWhoopNoonWindowUtcIso(timeZone: string, localDate: string) {
  const prev = addDaysIsoDate(localDate, -1).split("-").map((x) => Number(x));
  const next = addDaysIsoDate(localDate, +1).split("-").map((x) => Number(x));
  const startUtcIso = localDateTimeToUtcIso(timeZone, prev[0], prev[1], prev[2], 12, 0, 0);
  const endUtcIso = localDateTimeToUtcIso(timeZone, next[0], next[1], next[2], 12, 0, 0);
  return { startUtcIso, endUtcIso };
}

// EXACT same function as sync-worker
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

// EXACT same function as sync-worker
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

// EXACT same function as sync-worker
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

// EXACT same function as sync-worker
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

  console.log(
    `[backfill-metrics] refresh body: grant_type=${body.get("grant_type")}, client_id=${body.get("client_id")?.slice(0, 4)}..., refresh_token=${body.get("refresh_token")?.slice(0, 8)}...`,
  );

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

// EXACT same functions as sync-worker for record selection
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
  console.log("[backfill-metrics] === START ===", new Date().toISOString());

  try {
    const supabaseUrl = requireEnv("SUPABASE_URL");
    const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");
    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false },
      global: { headers: { "X-Client-Info": "backfill-metrics" } },
    });

    // ── Extract user_id from body or JWT ──
    let userId: string | null = null;
    try {
      const body = await req.clone().json();
      userId = typeof body?.user_id === "string" ? body.user_id : null;
    } catch {
      /* no body */
    }

    if (!userId) {
      const authHeader = req.headers.get("Authorization") ?? "";
      const jwt = authHeader.replace("Bearer ", "").trim();
      if (jwt) {
        try {
          const payload = JSON.parse(atob(jwt.split(".")[1]));
          userId = payload.sub ?? null;
        } catch {
          /* bad jwt */
        }
      }
    }

    if (!userId) return jsonResponse({ ok: false, error: "no user_id" }, 400);

    console.log(`[backfill-metrics] user=${userId}`);

    const nowUtc = new Date();
    const nowIso = nowUtc.toISOString();
    const utcDate = nowIso.slice(0, 10);

    // ── Resolve timezone (same approach as backfill-all) ──
    let timezone: string | null = null;
    for (const d of [addDaysIsoDate(utcDate, -1), utcDate, addDaysIsoDate(utcDate, 1)]) {
      const { data } = await supabase
        .from("user_location_daily")
        .select("timezone")
        .eq("user_id", userId)
        .eq("date", d)
        .not("timezone", "is", null)
        .order("updated_at", { ascending: false })
        .limit(1)
        .maybeSingle();
      if (data?.timezone) {
        timezone = data.timezone;
        break;
      }
    }
    if (!timezone) timezone = "UTC";

    const localToday = getLocalDate(timezone, nowUtc);
    const dates: string[] = [];
    for (let i = BACKFILL_DAYS - 1; i >= 0; i--) dates.push(addDaysIsoDate(localToday, -i));

    console.log(
      `[backfill-metrics] timezone=${timezone}, localToday=${localToday}, dates=${dates[0]}..${dates[dates.length - 1]}`,
    );

    // ── Load metric settings ──
    const { data: metrics } = await supabase
      .from("metric_settings")
      .select("metric,enabled,preferred_source,allowed_sources")
      .eq("user_id", userId)
      .eq("enabled", true);

    const enabledMetrics = metrics ?? [];

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
      return (
        name === "user_location_daily" ||
        name === "location" ||
        name.includes("weather") ||
        name.includes("temperature") ||
        name.includes("pressure") ||
        name.includes("humidity") ||
        name.includes("uv") ||
        name.includes("wind")
      );
    });

    console.log(`[backfill-metrics] whoopEnabledForAny=${whoopEnabledForAny}, locationEnabled=${locationEnabled}`);

    const summary: Record<string, unknown> = { userId, timezone, localToday, dates: dates.length };

    // ══════════════════════════════════════════════════════════════════════
    // STEP 1: WHOOP DATA — per-day, EXACT same pattern as sync-worker
    // ══════════════════════════════════════════════════════════════════════

    let whoopResult: Record<string, unknown> = { status: "skipped" };

    if (whoopEnabledForAny) {
      const { data: tok, error: tokErr } = await supabase
        .from("whoop_tokens")
        .select("user_id,access_token,refresh_token,token_type,expires_at")
        .eq("user_id", userId)
        .maybeSingle();

      console.log(
        `[backfill-metrics] whoop token found=${!!tok}, tokErr=${tokErr?.message ?? "none"}, expires_at=${tok?.expires_at ?? "null"}`,
      );

      if (!tok) {
        whoopResult = { status: "no_token" };
      } else {
        let accessToken = tok.access_token;

        // Refresh if needed — EXACT same logic as sync-worker
        try {
          if (isExpiredSoon(tok.expires_at, nowUtc)) {
            console.log(`[backfill-metrics] refreshing token...`);
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
            console.log(`[backfill-metrics] token refreshed OK, new expires_at=${expiresAt}`);
          }
        } catch (e) {
          console.error(`[backfill-metrics] token refresh FAILED: ${(e as Error).message}`);
          console.log(`[backfill-metrics] falling back to existing access_token`);
        }

        if (accessToken) {
          let totalSleep = 0,
            totalRecovery = 0,
            totalCycles = 0,
            totalWorkouts = 0;

          for (const jobLocalDate of dates) {
            const { startUtcIso, endUtcIso } = getWhoopNoonWindowUtcIso(timezone, jobLocalDate);

            if (jobLocalDate === dates[0]) {
              console.log(
                `[backfill-metrics] first day WHOOP window: date=${jobLocalDate}, start=${startUtcIso}, end=${endUtcIso}`,
              );
            }

            // ── SLEEP ──
            try {
              const recs = await whoopFetchPagedRecords("/activity/sleep", startUtcIso, endUtcIso, accessToken);
              const rec = recs.length ? selectWhoopSleepRecordByWakeup(recs as any[], jobLocalDate, timezone) : null;

              if (jobLocalDate === dates[0]) {
                console.log(`[backfill-metrics] sleep day=${jobLocalDate}: recs=${recs.length}, selected=${!!rec}`);
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
                const durationHours = (durationMs > 0 ? durationMs : lightMs + swsMs + remMs) / 3_600_000.0;
                const disturbances = intOrNull(stage?.disturbance_count) ?? 0;
                const perfPct = numOrNull(score?.sleep_performance_percentage);
                const effPct = numOrNull(score?.sleep_efficiency_percentage);
                const fellAsleepAt = whoopShiftedIso(rec, "start");
                const wokeUpAt = whoopShiftedIso(rec, "end");

                if (whoopEnabled("sleep_duration_daily") && durationHours > 0) {
                  await upsertDailyRow(supabase, "sleep_duration_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_hours: durationHours,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: nowIso,
                    updated_at: nowIso,
                  });
                }
                if (whoopEnabled("sleep_score_daily") && perfPct != null) {
                  await upsertDailyRow(supabase, "sleep_score_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_pct: perfPct,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: nowIso,
                    updated_at: nowIso,
                  });
                }
                if (whoopEnabled("sleep_efficiency_daily") && effPct != null) {
                  await upsertDailyRow(supabase, "sleep_efficiency_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_pct: effPct,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: nowIso,
                    updated_at: nowIso,
                  });
                }
                if (whoopEnabled("sleep_disturbances_daily")) {
                  await upsertDailyRow(supabase, "sleep_disturbances_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_count: disturbances,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: nowIso,
                    updated_at: nowIso,
                  });
                }
                if (whoopEnabled("sleep_stages_daily") && (lightMs > 0 || swsMs > 0 || remMs > 0)) {
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
                      created_at: nowIso,
                    },
                    "user_id,date,source",
                  );
                }
                if (whoopEnabled("fell_asleep_time_daily") && fellAsleepAt) {
                  await upsertDailyRow(supabase, "fell_asleep_time_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_at: fellAsleepAt,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: nowIso,
                    updated_at: nowIso,
                  });
                }
                if (whoopEnabled("woke_up_time_daily") && wokeUpAt) {
                  await upsertDailyRow(supabase, "woke_up_time_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_at: wokeUpAt,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: nowIso,
                    updated_at: nowIso,
                  });
                }
              }
            } catch (e) {
              console.error(`[backfill-metrics] SLEEP ERROR day=${jobLocalDate}: ${(e as Error).message}`);
            }

            // ── RECOVERY ──
            try {
              const recs = await whoopFetchPagedRecords("/recovery", startUtcIso, endUtcIso, accessToken);
              const rec = recs.length ? (recs[0] as any) : null;

              if (jobLocalDate === dates[0]) {
                console.log(`[backfill-metrics] recovery day=${jobLocalDate}: recs=${recs.length}, selected=${!!rec}`);
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
                  await upsertDailyRow(supabase, "recovery_score_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_pct: recoveryPct,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: nowIso,
                  });
                }
                if (whoopEnabled("resting_hr_daily") && restingHr != null) {
                  await upsertDailyRow(supabase, "resting_hr_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_bpm: restingHr,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: nowIso,
                  });
                }
                if (whoopEnabled("hrv_daily") && hrvMs != null) {
                  await upsertDailyRow(supabase, "hrv_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_rmssd_ms: hrvMs,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: nowIso,
                  });
                }
                if (whoopEnabled("skin_temp_daily") && skinTemp != null) {
                  await upsertDailyRow(supabase, "skin_temp_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_celsius: skinTemp,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: nowIso,
                  });
                }
                if (whoopEnabled("spo2_daily") && spo2 != null) {
                  await upsertDailyRow(supabase, "spo2_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_pct: spo2,
                    source: "whoop",
                    source_measure_id: sourceMeasureId,
                    created_at: nowIso,
                  });
                }
              }
            } catch (e) {
              console.error(`[backfill-metrics] RECOVERY ERROR day=${jobLocalDate}: ${(e as Error).message}`);
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
                    await upsertDailyRow(supabase, "strain_daily", {
                      user_id: userId,
                      date: jobLocalDate,
                      value_strain: strain,
                      value_kilojoule: numOrNull(scoreObj?.kilojoule),
                      avg_heart_rate: intOrNull(scoreObj?.average_heart_rate),
                      max_heart_rate: intOrNull(scoreObj?.max_heart_rate),
                      source: "whoop",
                      source_measure_id: sourceMeasureId,
                      created_at: nowIso,
                    });
                  }
                }
              }
            } catch (e) {
              console.error(`[backfill-metrics] CYCLE ERROR day=${jobLocalDate}: ${(e as Error).message}`);
            }

            // ── WORKOUTS / HR ZONES ──
            try {
              const wantDailyHigh = whoopEnabled("time_in_high_hr_zones_daily");
              const wantSessions = whoopEnabled("activity_hr_zones_sessions");

              if (wantDailyHigh || wantSessions) {
                const workouts = await whoopFetchPagedRecords("/activity/workout", startUtcIso, endUtcIso, accessToken);
                const ws = (workouts ?? []) as any[];

                // Filter to workouts ending on this local date
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

                  let sumZ3 = 0,
                    sumZ4 = 0,
                    sumZ5 = 0,
                    sumZ6 = 0;
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

                      const { error } = await supabase.from("time_in_high_hr_zones_daily").upsert(
                        {
                          user_id: userId,
                          date: jobLocalDate,
                          value_minutes: highMinutes,
                          source: "whoop",
                          source_measure_id: dailySid,
                          activity_type: "daily_total",
                          created_at: nowIso,
                        },
                        { onConflict: "user_id,source,source_measure_id" },
                      );

                      if (error) console.warn(`[backfill-metrics] hr_zones upsert error: ${error.message}`);
                    }
                  }

                  // (Session writes omitted here; backfill-all didn’t write them either)
                  void wantSessions;
                }
              }
            } catch (e) {
              console.error(`[backfill-metrics] WORKOUT ERROR day=${jobLocalDate}: ${(e as Error).message}`);
            }
          } // end for-each date

          whoopResult = {
            status: "done",
            sleep: totalSleep,
            recovery: totalRecovery,
            cycles: totalCycles,
            workouts: totalWorkouts,
          };
          console.log(
            `[backfill-metrics] WHOOP done: sleep=${totalSleep}, recovery=${totalRecovery}, cycles=${totalCycles}, workouts=${totalWorkouts}`,
          );
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
        let userLat: number | null = null,
          userLon: number | null = null;

        for (const d of [localToday, addDaysIsoDate(localToday, -1), addDaysIsoDate(localToday, -2)]) {
          const { data } = await supabase
            .from("user_location_daily")
            .select("latitude,longitude")
            .eq("user_id", userId)
            .eq("date", d)
            .maybeSingle();
          if (data?.latitude && data?.longitude) {
            userLat = data.latitude;
            userLon = data.longitude;
            break;
          }
        }

        console.log(`[backfill-metrics] weather: lat=${userLat}, lon=${userLon}`);

        if (!userLat || !userLon) {
          weatherResult = { status: "no_location" };
        } else {
          const { data: cities } = await supabase
            .from("city")
            .select("id,name,lat,lon,timezone")
            .gte("lat", userLat - 2)
            .lte("lat", userLat + 2)
            .gte("lon", userLon - 2)
            .lte("lon", userLon + 2)
            .limit(100);

          let nearestCity: any = null;
          let minDist = Infinity;
          for (const c of cities ?? []) {
            const dlat = ((c.lat - userLat) * Math.PI) / 180;
            const dlon = ((c.lon - userLon) * Math.PI) / 180;
            const a =
              Math.sin(dlat / 2) ** 2 +
              Math.cos((userLat * Math.PI) / 180) * Math.cos((c.lat * Math.PI) / 180) * Math.sin(dlon / 2) ** 2;
            const dist = 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
            if (dist < minDist) {
              minDist = dist;
              nearestCity = c;
            }
          }

          console.log(`[backfill-metrics] weather: nearestCity=${nearestCity?.name ?? "none"}, dist=${minDist.toFixed(1)}km`);

          if (!nearestCity) {
            weatherResult = { status: "no_city" };
          } else {
            const tz = nearestCity.timezone ?? "auto";
            const u = new URL("https://api.open-meteo.com/v1/forecast");
            u.searchParams.set("latitude", String(nearestCity.lat));
            u.searchParams.set("longitude", String(nearestCity.lon));
            u.searchParams.set(
              "daily",
              "temperature_2m_min,temperature_2m_max,temperature_2m_mean,surface_pressure_mean,surface_pressure_min,surface_pressure_max,relative_humidity_2m_mean,relative_humidity_2m_min,relative_humidity_2m_max,uv_index_max,wind_speed_10m_mean,wind_speed_10m_max,weathercode",
            );
            u.searchParams.set("past_days", "14");
            u.searchParams.set("forecast_days", "7");
            u.searchParams.set("timezone", tz);

            const resp = await fetch(u.toString(), { headers: { accept: "application/json" } });
            if (!resp.ok) throw new Error(`Open-Meteo ${resp.status}`);
            const d = (await resp.json())?.daily;
            if (!d?.time) throw new Error("missing daily block");

            console.log(`[backfill-metrics] weather: Open-Meteo returned ${d.time.length} days`);

            const { error: rpcErr } = await supabase.rpc("upsert_city_weather_batch", {
              p_city_id: nearestCity.id,
              p_days: d.time,
              p_tmin: d.temperature_2m_min,
              p_tmax: d.temperature_2m_max,
              p_tmean: d.temperature_2m_mean,
              p_pmean: d.surface_pressure_mean,
              p_pmin: d.surface_pressure_min,
              p_pmax: d.surface_pressure_max,
              p_hmean: d.relative_humidity_2m_mean,
              p_hmin: d.relative_humidity_2m_min,
              p_hmax: d.relative_humidity_2m_max,
              p_uv_max: d.uv_index_max,
              p_wind_mean: d.wind_speed_10m_mean,
              p_wind_max: d.wind_speed_10m_max,
              p_weather_code: d.weathercode,
            });

            if (rpcErr) console.error(`[backfill-metrics] weather RPC error: ${rpcErr.message}`);

            let weatherDays = 0;
            const { data: cityRows } = await supabase
              .from("city_weather_daily")
              .select("*")
              .eq("city_id", nearestCity.id)
              .gte("day", dates[0])
              .order("day", { ascending: true });

            for (const row of cityRows ?? []) {
              const { error: uwErr } = await supabase.from("user_weather_daily").upsert(
                {
                  user_id: userId,
                  date: row.day,
                  temp_c_min: row.temp_c_min,
                  temp_c_max: row.temp_c_max,
                  temp_c_mean: row.temp_c_mean,
                  pressure_hpa_min: row.pressure_hpa_min,
                  pressure_hpa_max: row.pressure_hpa_max,
                  pressure_hpa_mean: row.pressure_hpa_mean,
                  humidity_pct_min: row.humidity_pct_min,
                  humidity_pct_max: row.humidity_pct_max,
                  humidity_pct_mean: row.humidity_pct_mean,
                  wind_speed_mps_mean: row.wind_speed_mps_mean,
                  wind_speed_mps_max: row.wind_speed_mps_max,
                  uv_index_max: row.uv_index_max,
                  weather_code: row.weather_code,
                  is_thunderstorm_day: row.is_thunderstorm_day,
                  city_id: nearestCity.id,
                  timezone: timezone,
                  updated_at: nowIso,
                },
                { onConflict: "user_id,date" },
              );
              if (uwErr) console.warn(`[backfill-metrics] user_weather upsert error: ${uwErr.message}`);
              else weatherDays++;
            }

            weatherResult = { status: "done", city: nearestCity.name, days: weatherDays };
            console.log(`[backfill-metrics] weather done: city=${nearestCity.name}, days=${weatherDays}`);
          }
        }
      } catch (e) {
        console.error(`[backfill-metrics] WEATHER ERROR: ${(e as Error).message}`);
        weatherResult = { status: "error", error: (e as Error).message };
      }
    } else {
      console.log(`[backfill-metrics] weather skipped: locationEnabled=${locationEnabled}`);
    }

    summary.weather = weatherResult;

    // ── Audit ──
    try {
      await supabase.from("edge_audit").insert({
        fn: "backfill-metrics",
        user_id: userId,
        ok: true,
        stage: "complete",
        message: JSON.stringify(summary).slice(0, 500),
      });
    } catch (_) {
      /* best-effort */
    }

    const elapsed = Date.now() - t0;
    summary.elapsedMs = elapsed;
    console.log(`[backfill-metrics] === DONE in ${elapsed}ms ===`, JSON.stringify(summary));

    return jsonResponse({ ok: true, summary });
  } catch (e) {
    console.error("[backfill-metrics] FATAL ERROR:", e);
    return jsonResponse({ ok: false, error: (e as Error).message }, 500);
  }
});
