// supabase/functions/hourly-trigger-dispatcher/index.ts
//
// Runs every hour via cron. For each active user, evaluates all enabled
// trigger definitions against today's metric data and creates system
// triggers when thresholds are breached.
//
// Architecture: dispatcher does ALL the work (like daily-9am-sync).
// No separate worker needed.

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
    second: "2-digit",
    hour12: false,
  });
  const parts = fmt.formatToParts(now);
  const get = (type: string) => parts.find((p) => p.type === type)?.value;
  const year = get("year"), month = get("month"), day = get("day"), hour = get("hour"), minute = get("minute");
  if (!year || !month || !day || !hour || !minute) throw new Error(`Failed to compute local time for tz=${timeZone}`);
  return { localDate: `${year}-${month}-${day}`, hh: Number(hour), mm: Number(minute) };
}

function mean(arr: number[]): number | null {
  if (arr.length === 0) return null;
  return arr.reduce((a, b) => a + b, 0) / arr.length;
}

function stdDev(arr: number[], avg: number): number | null {
  if (arr.length < 2) return null;
  return Math.sqrt(arr.map((x) => (x - avg) ** 2).reduce((a, b) => a + b, 0) / arr.length);
}

function isoToMinutesSinceMidnight(isoString: string, isBedtime: boolean): number | null {
  const d = new Date(isoString);
  if (Number.isNaN(d.getTime())) return null;
  const hh = d.getUTCHours(), mm = d.getUTCMinutes();
  let mins = hh * 60 + mm;
  if (isBedtime && hh < 12) mins += 24 * 60;
  return mins;
}

function hoursToMinutes(hours: number, isBedtime: boolean): number {
  let mins = hours * 60;
  if (isBedtime && hours < 12) mins += 24 * 60;
  return mins;
}

function formatValue(value: number, unit: string | null, isTimeFromMinutes: boolean): string {
  if (isTimeFromMinutes) {
    let mins = Math.round(value);
    if (mins >= 24 * 60) mins -= 24 * 60;
    const hh = Math.floor(mins / 60), mm = mins % 60;
    return `${String(hh).padStart(2, "0")}:${String(mm).padStart(2, "0")}`;
  }
  switch (unit) {
    case "hours": return `${value.toFixed(1)}h`;
    case "%": return `${value.toFixed(0)}%`;
    case "count": return `${Math.round(value)}`;
    default: return `${value.toFixed(1)}`;
  }
}

async function mapLimit<T, R>(items: T[], limit: number, fn: (item: T, idx: number) => Promise<R>): Promise<R[]> {
  const results: R[] = new Array(items.length);
  let nextIndex = 0;
  async function worker() {
    while (true) {
      const i = nextIndex++;
      if (i >= items.length) return;
      results[i] = await fn(items[i], i);
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, () => worker()));
  return results;
}

const MIN_BASELINE_DAYS = 7;
const CUMULATIVE_LOW_CHECK_HOUR = 21;
const RISK_EXPOSURE_COLUMNS = new Set(["max_tyramine_exposure", "max_alcohol_exposure", "max_gluten_exposure"]);
const RISK_RANK: Record<string, number> = { none: 0, low: 1, medium: 2, high: 3 };

type TriggerDefinition = {
  id: string; label: string; category: string; direction: string;
  default_threshold: number | null; unit: string | null;
  metric_table: string; metric_column: string; baseline_days: number;
  enabled_by_default: boolean; metric_type: string | null;
};
type TriggerSetting = { trigger_type: string; enabled: boolean; threshold: number | null };

async function resolveUserTimezone(
  supabase: ReturnType<typeof createClient>, userId: string, utcIsoDate: string
): Promise<string | null> {
  for (const d of [addDaysIsoDate(utcIsoDate, -1), utcIsoDate, addDaysIsoDate(utcIsoDate, +1)]) {
    const { data, error } = await supabase
      .from("user_location_daily").select("timezone")
      .eq("user_id", userId).eq("date", d).not("timezone", "is", null)
      .order("updated_at", { ascending: false }).limit(1).maybeSingle();
    if (!error && data?.timezone) return data.timezone;
  }
  return null;
}

async function createTriggerIfNotExists(
  supabase: ReturnType<typeof createClient>, userId: string, triggerType: string, date: string, notes: string
): Promise<boolean> {
  const { data: existing, error: checkErr } = await supabase
    .from("triggers").select("id")
    .eq("user_id", userId).eq("type", triggerType).eq("source", "system")
    .gte("start_at", `${date}T00:00:00Z`).lte("start_at", `${date}T23:59:59Z`).limit(1);
  if (checkErr) { console.error(`[trigger] check failed: ${checkErr.message}`); return false; }
  if (existing && existing.length > 0) return false;

  const { error: insertErr } = await supabase.from("triggers").insert({
    user_id: userId, type: triggerType, source: "system",
    start_at: `${date}T09:00:00Z`, notes, active: true,
  });
  if (insertErr) { console.error(`[trigger] insert failed: ${insertErr.message}`); return false; }
  return true;
}

// ─── Evaluate all triggers for one user on one date ──────────────────────────
async function evaluateUserTriggers(
  supabase: ReturnType<typeof createClient>,
  userId: string,
  localDate: string,
  userLocalHour: number
): Promise<Record<string, unknown>> {
  const result: Record<string, unknown> = { userId, localDate, triggerResults: [] };

  // Load definitions
  const { data: defsRaw, error: defsErr } = await supabase
    .from("user_triggers")
    .select("id,label,category,direction,default_threshold,unit,metric_table,metric_column,baseline_days,enabled_by_default,metric_type")
    .eq("user_id", userId).not("direction", "is", null);
  if (defsErr) { result.status = "error"; result.error = defsErr.message; return result; }

  const definitions = (defsRaw ?? []) as TriggerDefinition[];
  if (definitions.length === 0) { result.status = "no_definitions"; return result; }

  // Load settings
  const { data: userSettings } = await supabase
    .from("trigger_settings").select("trigger_type,enabled,threshold").eq("user_id", userId);
  const settingsMap = new Map<string, TriggerSetting>();
  for (const s of (userSettings ?? []) as TriggerSetting[]) settingsMap.set(s.trigger_type, s);

  const activeDefs = definitions.filter((def) => {
    const setting = settingsMap.get(def.label);
    return setting ? setting.enabled : def.enabled_by_default;
  });

  if (activeDefs.length === 0) { result.status = "no_active_triggers"; return result; }

  // Group by metric
  const metricGroups = new Map<string, TriggerDefinition[]>();
  for (const def of activeDefs) {
    const key = `${def.metric_table}::${def.metric_column}`;
    const arr = metricGroups.get(key) ?? [];
    arr.push(def);
    metricGroups.set(key, arr);
  }

  // Process each metric group
  for (const [key, defs] of metricGroups) {
    const table = defs[0].metric_table, column = defs[0].metric_column;
    const baselineDays = defs[0].baseline_days || 14;
    const isTimeMetric = defs[0].unit === "time";
    const isBedtime = table === "fell_asleep_time_daily";

    // Fetch today's value
    const { data: todayRows, error: todayErr } = await supabase
      .from(table).select(column).eq("user_id", userId).eq("date", localDate).limit(1);
    if (todayErr) { (result.triggerResults as unknown[]).push({ metric: key, status: "fetch_error" }); continue; }

    const rawValue = (todayRows ?? [])[0] ? ((todayRows ?? [])[0] as Record<string, unknown>)[column] : null;
    if (rawValue == null) { (result.triggerResults as unknown[]).push({ metric: key, status: "no_data" }); continue; }

    // Convert to numeric
    let numericValue: number | null = null;
    const isRiskColumn = RISK_EXPOSURE_COLUMNS.has(column);
    if (isRiskColumn) {
      numericValue = RISK_RANK[typeof rawValue === "string" ? rawValue.toLowerCase() : "none"] ?? 0;
    } else if (isTimeMetric) {
      numericValue = isoToMinutesSinceMidnight(String(rawValue), isBedtime);
    } else {
      numericValue = typeof rawValue === "number" ? rawValue : Number(rawValue);
      if (Number.isNaN(numericValue)) numericValue = null;
    }
    if (numericValue == null) { (result.triggerResults as unknown[]).push({ metric: key, status: "invalid_value" }); continue; }

    // Fetch baseline
    let avg: number | null = null, sd: number | null = null;
    const startDate = addDaysIsoDate(localDate, -baselineDays), endDate = addDaysIsoDate(localDate, -1);
    const { data: historyRows, error: histErr } = await supabase
      .from(table).select(column).eq("user_id", userId).gte("date", startDate).lte("date", endDate);

    if (!histErr) {
      const baselineValues = (historyRows ?? []).map((r: Record<string, unknown>) => {
        const v = r[column];
        if (isRiskColumn) return RISK_RANK[typeof v === "string" ? v.toLowerCase() : "none"] ?? 0;
        if (isTimeMetric) return isoToMinutesSinceMidnight(String(v), isBedtime);
        const n = typeof v === "number" ? v : Number(v);
        return Number.isFinite(n) ? n : null;
      }).filter((v): v is number => v != null);

      if (baselineValues.length >= MIN_BASELINE_DAYS) {
        avg = mean(baselineValues);
        sd = avg != null ? stdDev(baselineValues, avg) : null;
      }
    }

    // Evaluate each definition
    for (const def of defs) {
      const setting = settingsMap.get(def.label);
      const thresh = setting?.threshold ?? def.default_threshold;

      if (def.direction === "low" && def.metric_type === "cumulative" && userLocalHour < CUMULATIVE_LOW_CHECK_HOUR) {
        (result.triggerResults as unknown[]).push({ triggerType: def.label, status: "skipped_cumulative" });
        continue;
      }

      let fired = false;
      const reasons: string[] = [];

      // Check 1: Absolute threshold
      if (thresh != null) {
        const ct = isTimeMetric ? hoursToMinutes(thresh, isBedtime) : thresh;
        if (def.direction === "low" && numericValue < ct) {
          fired = true;
          reasons.push(isTimeMetric ? `before ${thresh}:00 threshold` : `below ${formatValue(thresh, def.unit, false)} threshold`);
        } else if (def.direction === "high" && numericValue > ct) {
          fired = true;
          reasons.push(isTimeMetric ? `after ${thresh}:00 threshold` : `above ${formatValue(thresh, def.unit, false)} threshold`);
        }
      }

      // Check 2: 2SD from baseline
      if (avg != null && sd != null && sd > 0) {
        if (def.direction === "low" && numericValue < avg - 2 * sd) {
          if (!fired) fired = true;
          reasons.push(`2SD below avg ${formatValue(avg, def.unit, isTimeMetric)}`);
        } else if (def.direction === "high" && numericValue > avg + 2 * sd) {
          if (!fired) fired = true;
          reasons.push(`2SD above avg ${formatValue(avg, def.unit, isTimeMetric)}`);
        }
      }

      if (fired) {
        const notes = `${def.label}: ${formatValue(numericValue, def.unit, isTimeMetric)} — ${reasons.join("; ")}`;
        const created = await createTriggerIfNotExists(supabase, userId, def.label, localDate, notes);
        (result.triggerResults as unknown[]).push({ triggerType: def.label, status: created ? "created" : "exists", notes });
      } else {
        (result.triggerResults as unknown[]).push({ triggerType: def.label, status: "not_fired" });
      }
    }
  }

  result.status = "done";
  return result;
}

// ─── Main ────────────────────────────────────────────────────────────────────
serve(async (req) => {
  console.log("[hourly-trigger-dispatcher] start", { nowUtc: new Date().toISOString() });

  try {
    if (req.method !== "POST" && req.method !== "GET") {
      return jsonResponse({ ok: false, error: "Method not allowed" }, 405);
    }

    const supabaseUrl = requireEnv("SUPABASE_URL");
    const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");
    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false },
      global: { headers: { "X-Client-Info": "hourly-trigger-dispatcher" } },
    });

    const nowUtc = new Date();
    const utcDate = nowUtc.toISOString().slice(0, 10);

    // ─── Find users ──────────────────────────────────────────────────
    const { data: defaultDefs } = await supabase
      .from("user_triggers").select("user_id").eq("enabled_by_default", true).not("direction", "is", null).limit(1);
    const hasDefaultEnabled = (defaultDefs ?? []).length > 0;

    const { data: settingsUsers, error: tsErr } = await supabase
      .from("trigger_settings").select("user_id").eq("enabled", true);
    if (tsErr) throw new Error(`trigger_settings query failed: ${tsErr.message}`);

    const userIdSet = new Set<string>((settingsUsers ?? []).map((r) => r.user_id));

    if (hasDefaultEnabled) {
      const recentDate = addDaysIsoDate(utcDate, -2);
      const { data: activeUsers, error: auErr } = await supabase
        .from("user_location_daily").select("user_id").gte("date", recentDate);
      if (!auErr && activeUsers) for (const row of activeUsers) userIdSet.add(row.user_id);
    }

    const userIds = [...userIdSet];
    if (userIds.length === 0) {
      return jsonResponse({ ok: true, message: "No users to process", processed: 0 });
    }

    // ─── Process all users in parallel ───────────────────────────────
    const results = await mapLimit(userIds, 12, async (userId) => {
      try {
        const timezone = await resolveUserTimezone(supabase, userId, utcDate);
        if (!timezone) return { userId, status: "no_timezone" };

        const { localDate, hh } = getLocalTimeParts(timezone, nowUtc);

        // Evaluate today's triggers
        const todayResult = await evaluateUserTriggers(supabase, userId, localDate, hh);

        // Also evaluate forecast dates that have weather data
        const forecastResults: unknown[] = [];
        const forecastDates: string[] = [];
        for (let i = 1; i <= 6; i++) forecastDates.push(addDaysIsoDate(localDate, i));

        const { data: forecastWeather } = await supabase
          .from("user_weather_daily").select("date").eq("user_id", userId).in("date", forecastDates);

        if (forecastWeather && forecastWeather.length > 0) {
          for (const fw of forecastWeather) {
            const fResult = await evaluateUserTriggers(supabase, userId, fw.date, 23);
            forecastResults.push(fResult);
          }
        }

        return { userId, timezone, localDate, today: todayResult, forecast: forecastResults };
      } catch (e) {
        return { userId, status: "error", error: (e as Error).message };
      }
    });

    const summary = {
      totalUsers: userIds.length,
      done: results.filter((r: any) => r.today?.status === "done").length,
      noTimezone: results.filter((r: any) => r.status === "no_timezone").length,
      errors: results.filter((r: any) => r.status === "error").length,
    };

    console.log("[hourly-trigger-dispatcher] done", { summary });
    return jsonResponse({ ok: true, nowUtc: nowUtc.toISOString(), summary, results });
  } catch (e) {
    console.error("[hourly-trigger-dispatcher] error", e);
    return jsonResponse({ ok: false, error: (e as Error).message }, 500);
  }
});