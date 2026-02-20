// supabase/functions/recalc-risk-scores/index.ts
//
// Lightweight risk score recalculation.
// Called when the user changes gauge thresholds or decay weights in Risk Model settings.
// Does NOT re-evaluate triggers/prodromes — just re-scores existing events
// against the (now updated) thresholds and weights.

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

function zoneForScore(score: number, high: number, mild: number, low: number): string {
  if (score >= high) return "HIGH";
  if (score >= mild) return "MILD";
  if (score >= low) return "LOW";
  return "NONE";
}

// ═════════════════════════════════════════════════════════════════════════════
// MAIN
// ═════════════════════════════════════════════════════════════════════════════

serve(async (req) => {
  const t0 = Date.now();
  console.log("[recalc-risk-scores] === START ===");

  try {
    const supabaseUrl = requireEnv("SUPABASE_URL");
    const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");
    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false },
    });

    // ── Extract user_id ──
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

    console.log(`[recalc-risk-scores] user=${userId}`);
    const nowUtc = new Date();
    const nowIso = nowUtc.toISOString();

    // ── Resolve timezone ──
    let timezone = "UTC";
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

    const localToday = getLocalDate(timezone, nowUtc);

    // Build date range: 7 days back + today
    const dates: string[] = [];
    for (let i = 7; i >= 0; i--) dates.push(addDaysIsoDate(localToday, -i));

    // ── Load decay weights (freshly saved) ──
    const { data: decayRows } = await supabase
      .from("risk_decay_weights")
      .select("severity,day_0,day_1,day_2,day_3,day_4,day_5,day_6")
      .eq("user_id", userId);

    if (!decayRows || decayRows.length === 0) {
      return jsonResponse({ ok: true, status: "no_decay_config" });
    }

    const decayMap: Record<string, number[]> = {};
    for (const row of decayRows) {
      decayMap[row.severity.toUpperCase()] = [
        row.day_0, row.day_1, row.day_2, row.day_3, row.day_4, row.day_5, row.day_6,
      ];
    }

    // ── Load gauge thresholds (freshly saved) ──
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

    // ── Load severity maps ──
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

    // ── Recalculate scores for each day ──
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

      // Collect all events with their dates for days_active counting
      const allEvents: { name: string; eventDate: string; severity: string }[] = [];

      for (const t of triggerEvents ?? []) {
        if (!t.type || !t.start_at) continue;
        const severity = triggerSevMap[t.type.toLowerCase()] || "NONE";
        if (severity === "NONE") continue;
        const eventDate = t.start_at.substring(0, 10);
        const daysAgo = daysBetween(eventDate, localDate);
        if (daysAgo < 0 || daysAgo > 6) continue;
        allEvents.push({ name: t.type, eventDate, severity });
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
        allEvents.push({ name: p.type, eventDate, severity });
        const weights = decayMap[severity];
        if (!weights) continue;
        const weight = weights[daysAgo] ?? 0;
        if (weight > 0) {
          dayScore += weight;
          contributions.push({ name: p.type, contribution: weight, severity });
        }
      }

      // Aggregate
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

      if (riskErr) console.warn(`[recalc-risk-scores] upsert day=${localDate}: ${riskErr.message}`);
      else riskDaysWritten++;
    }

    // ── Write risk_score_live ──
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

      if (liveErr) console.warn(`[recalc-risk-scores] live error: ${liveErr.message}`);
      else console.log(`[recalc-risk-scores] live updated: score=${todayDaily.score}, zone=${todayDaily.zone}`);
    }

    const elapsed = Date.now() - t0;
    console.log(`[recalc-risk-scores] === DONE in ${elapsed}ms, days=${riskDaysWritten} ===`);

    return jsonResponse({ ok: true, daysWritten: riskDaysWritten, elapsedMs: elapsed });

  } catch (e) {
    console.error("[recalc-risk-scores] FATAL:", e);
    return jsonResponse({ ok: false, error: (e as Error).message }, 500);
  }
});