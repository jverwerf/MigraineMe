// supabase/functions/backfilltrigger/index.ts
//
// ONE-TIME USE: Backfills system triggers for entire metric history.
// Evaluates directly (no job queue). Loads definitions once, then
// loops through every date.
//
// Invoke with empty body {} — user_id hardcoded.
// Optional: {"from_date":"2024-06-01","to_date":"2025-12-31"}
// Will timeout for large ranges — use from_date/to_date to chunk.

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function addDaysIsoDate(isoDate: string, deltaDays: number): string {
  const [y, m, d] = isoDate.split("-").map((x) => Number(x));
  const dt = new Date(Date.UTC(y, m - 1, d, 12, 0, 0));
  dt.setUTCDate(dt.getUTCDate() + deltaDays);
  return dt.toISOString().slice(0, 10);
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

const MIN_BASELINE_DAYS = 7;
const RISK_EXPOSURE_COLUMNS = new Set(["max_tyramine_exposure", "max_alcohol_exposure", "max_gluten_exposure"]);
const RISK_RANK: Record<string, number> = { none: 0, low: 1, medium: 2, high: 3 };

type TriggerDefinition = {
  id: string; label: string; category: string; direction: string;
  default_threshold: number | null; unit: string | null;
  metric_table: string; metric_column: string; baseline_days: number;
  enabled_by_default: boolean; metric_type: string | null;
};
type TriggerSetting = { trigger_type: string; enabled: boolean; threshold: number | null };

async function createTriggerIfNotExists(
  supabase: ReturnType<typeof createClient>, userId: string, triggerType: string, date: string, notes: string
): Promise<boolean> {
  const { data: existing } = await supabase
    .from("triggers").select("id")
    .eq("user_id", userId).eq("type", triggerType).eq("source", "system")
    .gte("start_at", `${date}T00:00:00Z`).lte("start_at", `${date}T23:59:59Z`).limit(1);
  if (existing && existing.length > 0) return false;

  const { error: insertErr } = await supabase.from("triggers").insert({
    user_id: userId, type: triggerType, source: "system",
    start_at: `${date}T09:00:00Z`, notes, active: true,
  });
  if (insertErr) { console.error(`[backfill] insert failed: ${insertErr.message}`); return false; }
  return true;
}

async function evaluateDate(
  supabase: ReturnType<typeof createClient>,
  userId: string,
  localDate: string,
  activeDefs: TriggerDefinition[],
  settingsMap: Map<string, TriggerSetting>
): Promise<{ date: string; created: number; checked: number }> {
  let created = 0, checked = 0;

  const metricGroups = new Map<string, TriggerDefinition[]>();
  for (const def of activeDefs) {
    const key = `${def.metric_table}::${def.metric_column}`;
    const arr = metricGroups.get(key) ?? [];
    arr.push(def);
    metricGroups.set(key, arr);
  }

  for (const [_key, defs] of metricGroups) {
    const table = defs[0].metric_table, column = defs[0].metric_column;
    const baselineDays = defs[0].baseline_days || 14;
    const isTimeMetric = defs[0].unit === "time";
    const isBedtime = table === "fell_asleep_time_daily";

    const { data: todayRows, error: todayErr } = await supabase
      .from(table).select(column).eq("user_id", userId).eq("date", localDate).limit(1);
    if (todayErr) continue;

    const rawValue = (todayRows ?? [])[0] ? ((todayRows ?? [])[0] as Record<string, unknown>)[column] : null;
    if (rawValue == null) continue;

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
    if (numericValue == null) continue;

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

    for (const def of defs) {
      checked++;
      const setting = settingsMap.get(def.label);
      const thresh = setting?.threshold ?? def.default_threshold;

      let fired = false;
      const reasons: string[] = [];

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
        const wasCreated = await createTriggerIfNotExists(supabase, userId, def.label, localDate, notes);
        if (wasCreated) created++;
      }
    }
  }

  return { date: localDate, created, checked };
}

serve(async (req: Request) => {
  console.log("[backfill-triggers] start");

  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL") || "";
    const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";

    let body: Record<string, unknown> = {};
    try { body = await req.json(); } catch (_e) { body = {}; }

    const userId = (body.user_id as string) || "b8d101ad-e46e-4fc0-8ec8-fa15f0ff5fb8";
    const fromDate = (body.from_date as string) || "2024-06-01";
    const toDate = (body.to_date as string) || new Date().toISOString().slice(0, 10);

    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false },
    });

    // Load definitions ONCE
    const { data: defsRaw, error: defsErr } = await supabase
      .from("user_triggers")
      .select("id,label,category,direction,default_threshold,unit,metric_table,metric_column,baseline_days,enabled_by_default,metric_type")
      .eq("user_id", userId).not("direction", "is", null);

    if (defsErr) return jsonResponse({ error: defsErr.message }, 500);
    const definitions = (defsRaw ?? []) as TriggerDefinition[];

    // Load settings ONCE
    const { data: userSettings } = await supabase
      .from("trigger_settings").select("trigger_type,enabled,threshold").eq("user_id", userId);
    const settingsMap = new Map<string, TriggerSetting>();
    for (const s of (userSettings ?? []) as TriggerSetting[]) settingsMap.set(s.trigger_type, s);

    const activeDefs = definitions.filter((def) => {
      const setting = settingsMap.get(def.label);
      return setting ? setting.enabled : def.enabled_by_default;
    });

    if (activeDefs.length === 0) return jsonResponse({ error: "No active trigger definitions" }, 400);

    // Generate date range
    const dates: string[] = [];
    const start = new Date(fromDate + "T12:00:00Z");
    const end = new Date(toDate + "T12:00:00Z");
    const dd = new Date(start);
    while (dd <= end) {
      dates.push(dd.toISOString().slice(0, 10));
      dd.setUTCDate(dd.getUTCDate() + 1);
    }

    // Process sequentially
    let totalCreated = 0;
    let totalChecked = 0;
    let datesProcessed = 0;
    const sample: unknown[] = [];

    for (const date of dates) {
      const r = await evaluateDate(supabase, userId, date, activeDefs, settingsMap);
      totalCreated += r.created;
      totalChecked += r.checked;
      datesProcessed++;

      if (r.created > 0 || datesProcessed <= 3) {
        sample.push(r);
      }
    }

    return jsonResponse({
      ok: true,
      userId,
      fromDate,
      toDate,
      activeDefinitions: activeDefs.length,
      datesProcessed,
      totalChecked,
      totalCreated,
      sample,
    });

  } catch (e) {
    return jsonResponse({
      error: (e as Error).message,
      stack: (e as Error).stack,
    }, 500);
  }
});