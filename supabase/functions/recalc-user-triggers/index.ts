// supabase/functions/recalc-user-triggers/index.ts
//
// Re-evaluates system triggers + prodromes for the last 7 days and recalculates
// risk scores. Called when prediction_value or default_threshold changes on
// user_triggers / user_prodromes.
//
// Steps:
//   1. Delete all source='system' triggers + prodromes in the 7-day window
//   2. Re-evaluate trigger definitions against existing metric data
//   3. Re-evaluate prodrome definitions against existing metric data
//   4. Recalculate risk_score_daily + risk_score_live (gauge)
//
// No WHOOP fetch, no weather fetch — metric data is already in the DB.

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

function daysBetween(a: string, b: string): number {
  const da = new Date(a + "T00:00:00Z");
  const db = new Date(b + "T00:00:00Z");
  return Math.round((db.getTime() - da.getTime()) / 86400000);
}

// ─── Metric evaluation helpers (from trigger-worker / backfill-all) ──────────

const RISK_EXPOSURE_COLUMNS = new Set([
  "max_tyramine_exposure",
  "max_alcohol_exposure",
  "max_gluten_exposure",
]);
const RISK_RANK: Record<string, number> = { none: 0, low: 1, medium: 2, high: 3 };
const MIN_BASELINE_DAYS = 7;
const EVAL_WINDOW_DAYS = 7;

function mean(arr: number[]): number | null {
  return arr.length ? arr.reduce((a, b) => a + b, 0) / arr.length : null;
}

function stdDev(arr: number[], avg: number): number | null {
  if (arr.length < 2) return null;
  return Math.sqrt(arr.map((x) => (x - avg) ** 2).reduce((a, b) => a + b, 0) / arr.length);
}

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

function formatValue(value: number, unit: string | null, isTime: boolean): string {
  if (isTime) {
    let mins = Math.round(value);
    if (mins >= 1440) mins -= 1440;
    return `${String(Math.floor(mins / 60)).padStart(2, "0")}:${String(mins % 60).padStart(2, "0")}`;
  }
  switch (unit) {
    case "hours":
      return `${value.toFixed(1)}h`;
    case "%":
      return `${value.toFixed(0)}%`;
    case "count":
      return `${Math.round(value)}`;
    default:
      return `${value.toFixed(1)}`;
  }
}

function zoneForScore(score: number, high: number, mild: number, low: number): string {
  if (score >= high) return "HIGH";
  if (score >= mild) return "MILD";
  if (score >= low) return "LOW";
  return "NONE";
}

// ─── Types ───────────────────────────────────────────────────────────────────

type Definition = {
  id: string;
  label: string;
  category: string;
  direction: string;
  default_threshold: number | null;
  unit: string | null;
  metric_table: string;
  metric_column: string;
  baseline_days: number;
  enabled_by_default: boolean;
  metric_type: string | null;
  display_group: string | null;
};

// ─── Shared evaluation logic ─────────────────────────────────────────────────

async function evaluateDefinitions(
  supabase: ReturnType<typeof createClient>,
  userId: string,
  dates: string[],
  definitions: Definition[],
  settingsMap: Map<string, { enabled: boolean; threshold: number | null }>,
  targetTable: "triggers" | "prodromes",
): Promise<{ created: number; definitions: number }> {
  const activeDefs = definitions.filter((def) => {
    const setting = settingsMap.get(def.label);
    return setting ? setting.enabled : def.enabled_by_default;
  });

  if (activeDefs.length === 0) return { created: 0, definitions: 0 };

  // Group by metric_table::metric_column
  const metricGroups = new Map<string, Definition[]>();
  for (const def of activeDefs) {
    const key = `${def.metric_table}::${def.metric_column}`;
    const arr = metricGroups.get(key) ?? [];
    arr.push(def);
    metricGroups.set(key, arr);
  }

  let created = 0;

  for (const localDate of dates) {
    const firedGroups = new Set<string>();

    for (const [_key, defs] of metricGroups) {
      const table = defs[0].metric_table;
      const column = defs[0].metric_column;
      const baselineDays = defs[0].baseline_days || 14;
      const isTimeMetric = defs[0].unit === "time";
      const isBedtime = table === "fell_asleep_time_daily";
      const isRiskColumn = RISK_EXPOSURE_COLUMNS.has(column);

      // Fetch today's value
      const { data: todayRows } = await supabase
        .from(table)
        .select(column)
        .eq("user_id", userId)
        .eq("date", localDate)
        .limit(1);

      const rawValue = todayRows?.[0] ? (todayRows[0] as any)[column] : null;
      if (rawValue == null) continue;

      // Convert to numeric
      let numericValue: number | null = null;
      if (isRiskColumn) {
        numericValue =
          RISK_RANK[typeof rawValue === "string" ? rawValue.toLowerCase() : "none"] ?? 0;
      } else if (isTimeMetric) {
        numericValue = isoToMinutesSinceMidnight(String(rawValue), isBedtime);
      } else {
        numericValue = typeof rawValue === "number" ? rawValue : Number(rawValue);
        if (Number.isNaN(numericValue)) numericValue = null;
      }
      if (numericValue == null) continue;

      // Fetch baseline for 2SD
      let avg: number | null = null;
      let sd: number | null = null;
      const startDate = addDaysIsoDate(localDate, -baselineDays);
      const endDate = addDaysIsoDate(localDate, -1);

      const { data: histRows } = await supabase
        .from(table)
        .select(column)
        .eq("user_id", userId)
        .gte("date", startDate)
        .lte("date", endDate);

      const baselineValues = (histRows ?? [])
        .map((r: any) => {
          const v = r[column];
          if (isRiskColumn)
            return RISK_RANK[typeof v === "string" ? v.toLowerCase() : "none"] ?? 0;
          if (isTimeMetric) return isoToMinutesSinceMidnight(String(v), isBedtime);
          const n = typeof v === "number" ? v : Number(v);
          return Number.isFinite(n) ? n : null;
        })
        .filter((v: any): v is number => v != null);

      if (baselineValues.length >= MIN_BASELINE_DAYS) {
        avg = mean(baselineValues);
        sd = avg != null ? stdDev(baselineValues, avg) : null;
      }

      // Evaluate each definition
      for (const def of defs) {
        const setting = settingsMap.get(def.label);
        const thresh = setting?.threshold ?? def.default_threshold;

        let fired = false;
        const reasons: string[] = [];

        // Check 1: Absolute threshold
        if (thresh != null) {
          const ct = isTimeMetric ? hoursToMinutes(thresh, isBedtime) : thresh;
          if (def.direction === "low" && numericValue < ct) {
            fired = true;
            reasons.push(
              isTimeMetric
                ? `before ${thresh}:00`
                : `below ${formatValue(thresh, def.unit, false)}`,
            );
          } else if (def.direction === "high" && numericValue > ct) {
            fired = true;
            reasons.push(
              isTimeMetric
                ? `after ${thresh}:00`
                : `above ${formatValue(thresh, def.unit, false)}`,
            );
          }
        }

        // Check 2: 2SD from baseline
        if (avg != null && sd != null && sd > 0) {
          if (def.direction === "low" && numericValue < avg - 2 * sd) {
            if (!fired) fired = true;
            reasons.push(`2SD below avg`);
          } else if (def.direction === "high" && numericValue > avg + 2 * sd) {
            if (!fired) fired = true;
            reasons.push(`2SD above avg`);
          }
        }

        if (fired) {
          const itemName = def.display_group ?? def.label;
          if (firedGroups.has(itemName)) continue;

          const notes = `${def.label}: ${formatValue(numericValue, def.unit, isTimeMetric)} — ${reasons.join("; ")}`;
          const dayStart = `${localDate}T00:00:00Z`;
          const dayEnd = `${localDate}T23:59:59Z`;

          // Check for duplicates (manual or from a concurrent worker run)
          const { data: existing } = await supabase
            .from(targetTable)
            .select("id")
            .eq("user_id", userId)
            .eq("type", itemName)
            .eq("source", "system")
            .gte("start_at", dayStart)
            .lte("start_at", dayEnd)
            .limit(1);

          if (!existing || existing.length === 0) {
            const row: Record<string, unknown> = {
              user_id: userId,
              type: itemName,
              source: "system",
              start_at: `${localDate}T09:00:00Z`,
              notes,
            };
            // triggers table has 'active' column, prodromes does not
            if (targetTable === "triggers") row.active = true;

            const { error: insErr } = await supabase.from(targetTable).insert(row);
            if (!insErr) created++;
            else console.warn(`[recalc] ${targetTable} insert error: ${insErr.message}`);
          }
          firedGroups.add(itemName);
        }
      }
    }
  }

  return { created, definitions: activeDefs.length };
}

// ═════════════════════════════════════════════════════════════════════════════
// MAIN
// ═════════════════════════════════════════════════════════════════════════════

serve(async (req) => {
  const t0 = Date.now();
  console.log("[recalc] === START ===", new Date().toISOString());

  try {
    if (req.method !== "POST" && req.method !== "GET") {
      return jsonResponse({ ok: false, error: "Method not allowed" }, 405);
    }

    const supabaseUrl = requireEnv("SUPABASE_URL");
    const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");
    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false },
      global: { headers: { "X-Client-Info": "recalc-user-triggers" } },
    });

    // ── Extract user_id ──
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

    console.log(`[recalc] user=${userId}`);
    const nowUtc = new Date();
    const nowIso = nowUtc.toISOString();

    // ── Resolve timezone ──
    let timezone: string | null = null;
    const utcDate = nowIso.slice(0, 10);
    for (const d of [utcDate, addDaysIsoDate(utcDate, -1), addDaysIsoDate(utcDate, 1)]) {
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

    // Build evaluation window: last 7 days
    const dates: string[] = [];
    for (let i = EVAL_WINDOW_DAYS - 1; i >= 0; i--) {
      dates.push(addDaysIsoDate(localToday, -i));
    }

    const windowStart = `${dates[0]}T00:00:00Z`;
    const windowEnd = `${dates[dates.length - 1]}T23:59:59Z`;

    console.log(`[recalc] timezone=${timezone}, window=${dates[0]}..${dates[dates.length - 1]}`);

    const summary: Record<string, unknown> = { userId, timezone, localToday, windowDays: dates.length };

    // ══════════════════════════════════════════════════════════════════════
    // STEP 1: DELETE system triggers + prodromes in the window
    // ══════════════════════════════════════════════════════════════════════

    const { count: deletedTriggers, error: delTErr } = await supabase
      .from("triggers")
      .delete({ count: "exact" })
      .eq("user_id", userId)
      .eq("source", "system")
      .gte("start_at", windowStart)
      .lte("start_at", windowEnd);

    if (delTErr) console.warn(`[recalc] trigger delete error: ${delTErr.message}`);
    console.log(`[recalc] deleted ${deletedTriggers ?? 0} system triggers`);

    const { count: deletedProdromes, error: delPErr } = await supabase
      .from("prodromes")
      .delete({ count: "exact" })
      .eq("user_id", userId)
      .eq("source", "system")
      .gte("start_at", windowStart)
      .lte("start_at", windowEnd);

    if (delPErr) console.warn(`[recalc] prodrome delete error: ${delPErr.message}`);
    console.log(`[recalc] deleted ${deletedProdromes ?? 0} system prodromes`);

    summary.deleted = {
      triggers: deletedTriggers ?? 0,
      prodromes: deletedProdromes ?? 0,
    };

    // ══════════════════════════════════════════════════════════════════════
    // STEP 2: RE-EVALUATE TRIGGERS
    // ══════════════════════════════════════════════════════════════════════

    const { data: trigDefs } = await supabase
      .from("user_triggers")
      .select(
        "id,label,category,direction,default_threshold,unit,metric_table,metric_column,baseline_days,enabled_by_default,metric_type,display_group",
      )
      .eq("user_id", userId)
      .not("direction", "is", null);

    const { data: trigSettings } = await supabase
      .from("trigger_settings")
      .select("trigger_type,enabled,threshold")
      .eq("user_id", userId);

    const trigSettingsMap = new Map<string, { enabled: boolean; threshold: number | null }>();
    for (const s of trigSettings ?? []) {
      trigSettingsMap.set(s.trigger_type, { enabled: s.enabled, threshold: s.threshold });
    }

    const trigResult = await evaluateDefinitions(
      supabase,
      userId,
      dates,
      (trigDefs ?? []) as Definition[],
      trigSettingsMap,
      "triggers",
    );

    console.log(`[recalc] triggers: created=${trigResult.created}, defs=${trigResult.definitions}`);
    summary.triggers = trigResult;

    // ══════════════════════════════════════════════════════════════════════
    // STEP 3: RE-EVALUATE PRODROMES
    // ══════════════════════════════════════════════════════════════════════

    const { data: prodDefs } = await supabase
      .from("user_prodromes")
      .select(
        "id,label,category,direction,default_threshold,unit,metric_table,metric_column,baseline_days,enabled_by_default,metric_type,display_group",
      )
      .eq("user_id", userId)
      .not("direction", "is", null);

    const { data: prodSettings } = await supabase
      .from("prodrome_settings")
      .select("prodrome_type,enabled,threshold")
      .eq("user_id", userId);

    const prodSettingsMap = new Map<string, { enabled: boolean; threshold: number | null }>();
    for (const s of prodSettings ?? []) {
      prodSettingsMap.set(s.prodrome_type, { enabled: s.enabled, threshold: s.threshold });
    }

    const prodResult = await evaluateDefinitions(
      supabase,
      userId,
      dates,
      (prodDefs ?? []) as Definition[],
      prodSettingsMap,
      "prodromes",
    );

    console.log(`[recalc] prodromes: created=${prodResult.created}, defs=${prodResult.definitions}`);
    summary.prodromes = prodResult;

    // ══════════════════════════════════════════════════════════════════════
    // STEP 4: RECALCULATE RISK SCORES
    // ══════════════════════════════════════════════════════════════════════

    let riskResult: Record<string, unknown> = { status: "skipped" };

    try {
      // Load decay weights
      const { data: decayRows } = await supabase
        .from("risk_decay_weights")
        .select("severity,day_0,day_1,day_2,day_3,day_4,day_5,day_6")
        .eq("user_id", userId);

      if (!decayRows || decayRows.length === 0) {
        riskResult = { status: "no_decay_config" };
        console.log(`[recalc] risk skipped: no decay config`);
      } else {
        const decayMap: Record<string, number[]> = {};
        for (const row of decayRows) {
          decayMap[row.severity.toUpperCase()] = [
            row.day_0,
            row.day_1,
            row.day_2,
            row.day_3,
            row.day_4,
            row.day_5,
            row.day_6,
          ];
        }

        // Load gauge thresholds
        const { data: thresholdRows } = await supabase
          .from("risk_gauge_thresholds")
          .select("zone,min_value")
          .eq("user_id", userId);

        const thresholdMap: Record<string, number> = {};
        for (const row of thresholdRows ?? []) {
          thresholdMap[row.zone.toUpperCase()] = row.min_value;
        }
        const thresholdHigh = thresholdMap["HIGH"] ?? 10.0;
        const thresholdMild = thresholdMap["MILD"] ?? 5.0;
        const thresholdLow = thresholdMap["LOW"] ?? 3.0;
        const gaugeMax = thresholdHigh * 1.2;

        // Load prediction_value maps (fresh — these are the values that just changed)
        const { data: triggerPool } = await supabase
          .from("user_triggers")
          .select("label,prediction_value,display_group")
          .eq("user_id", userId);

        const triggerSevMap: Record<string, string> = {};
        for (const t of triggerPool ?? []) {
          const sev = ((t as any).prediction_value || "NONE").toUpperCase();
          triggerSevMap[(t as any).label.toLowerCase()] = sev;
          if ((t as any).display_group) {
            triggerSevMap[(t as any).display_group.toLowerCase()] = sev;
          }
        }

        const { data: prodromePool } = await supabase
          .from("user_prodromes")
          .select("label,prediction_value")
          .eq("user_id", userId);

        const prodromeSevMap: Record<string, string> = {};
        for (const p of prodromePool ?? []) {
          prodromeSevMap[(p as any).label.toLowerCase()] =
            ((p as any).prediction_value || "NONE").toUpperCase();
        }

        let riskDaysWritten = 0;

        // Risk dates = eval window + 6 future days (for decay forecast)
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
            .gte("start_at", cutoffStart)
            .lte("start_at", cutoffEnd);

          const { data: prodromeEvents } = await supabase
            .from("prodromes")
            .select("type,start_at")
            .eq("user_id", userId)
            .gte("start_at", cutoffStart)
            .lte("start_at", cutoffEnd);

          let dayScore = 0;
          const contributions: { name: string; contribution: number; severity: string }[] = [];
          const allEvents: { name: string; eventDate: string }[] = [];

          for (const t of triggerEvents ?? []) {
            if (!t.type || !t.start_at) continue;
            const severity = triggerSevMap[t.type.toLowerCase()] || "NONE";
            if (severity === "NONE") continue;
            const eventDate = t.start_at.substring(0, 10);
            const daysAgo = daysBetween(eventDate, localDate);
            if (daysAgo < 0 || daysAgo > 6) continue;
            allEvents.push({ name: t.type, eventDate });
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
            allEvents.push({ name: p.type, eventDate });
            const weights = decayMap[severity];
            if (!weights) continue;
            const weight = weights[daysAgo] ?? 0;
            if (weight > 0) {
              dayScore += weight;
              contributions.push({ name: p.type, contribution: weight, severity });
            }
          }

          // Aggregate top triggers
          const grouped: Record<string, { total: number; severity: string }> = {};
          for (const c of contributions) {
            if (!grouped[c.name]) grouped[c.name] = { total: 0, severity: c.severity };
            grouped[c.name].total += c.contribution;
          }

          // Count distinct dates each trigger appeared on
          const triggerDatesMap: Record<string, Set<string>> = {};
          for (const e of allEvents) {
            if (!triggerDatesMap[e.name]) triggerDatesMap[e.name] = new Set();
            triggerDatesMap[e.name].add(e.eventDate);
          }

          const topTriggers = Object.entries(grouped)
            .map(([name, { total, severity }]) => ({
              name,
              score: Math.round(total),
              severity,
              days_active: triggerDatesMap[name]?.size ?? 1,
            }))
            .sort((a, b) => b.score - a.score);

          // Use the sum of rounded trigger scores so gauge matches what user sees
          const roundedScore = topTriggers.reduce((sum, t) => sum + t.score, 0);
          const dayPercent = Math.min(100, Math.max(0, Math.round((roundedScore / gaugeMax) * 100)));
          const dayZone = zoneForScore(roundedScore, thresholdHigh, thresholdMild, thresholdLow);

          const { error: riskErr } = await supabase.from("risk_score_daily").upsert(
            {
              user_id: userId,
              date: localDate,
              score: roundedScore,
              zone: dayZone,
              percent: dayPercent,
              top_triggers: topTriggers,
              created_at: nowIso,
            },
            { onConflict: "user_id,date" },
          );

          if (riskErr) console.warn(`[recalc] risk upsert day=${localDate}: ${riskErr.message}`);
          else riskDaysWritten++;
        }

        // ── Write risk_score_live (gauge) ──
        const todayDate = dates[dates.length - 1];
        const { data: todayDaily } = await supabase
          .from("risk_score_daily")
          .select("score,zone,percent,top_triggers")
          .eq("user_id", userId)
          .eq("date", todayDate)
          .maybeSingle();

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

          const { error: liveErr } = await supabase.from("risk_score_live").upsert(
            {
              user_id: userId,
              score: todayDaily.score,
              zone: todayDaily.zone,
              percent: todayDaily.percent,
              top_triggers: todayDaily.top_triggers,
              forecast,
              day_risks: dayRisks,
              updated_at: nowIso,
            },
            { onConflict: "user_id" },
          );

          if (liveErr) console.warn(`[recalc] risk_score_live error: ${liveErr.message}`);
          else console.log(`[recalc] risk_score_live updated: score=${todayDaily.score}, zone=${todayDaily.zone}`);
        }

        riskResult = { status: "done", daysWritten: riskDaysWritten };
        console.log(`[recalc] risk done: daysWritten=${riskDaysWritten}`);
      }
    } catch (e) {
      console.error(`[recalc] RISK ERROR: ${(e as Error).message}`);
      riskResult = { status: "error", error: (e as Error).message };
    }

    summary.risk = riskResult;

    // ── Audit ──
    try {
      await supabase.from("edge_audit").insert({
        fn: "recalc-user-triggers",
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
    console.log(`[recalc] === DONE in ${elapsed}ms ===`);

    return jsonResponse({ ok: true, summary });
  } catch (e) {
    console.error("[recalc] FATAL ERROR:", e);
    return jsonResponse({ ok: false, error: (e as Error).message }, 500);
  }
});