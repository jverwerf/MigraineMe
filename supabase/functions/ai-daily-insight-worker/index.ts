// supabase/functions/ai-daily-insight-worker/index.ts
//
// Picks queued jobs from daily_insight_jobs, gathers comprehensive user context,
// pre-summarizes it into plain English, and sends to GPT-4o-mini for actionable advice.
// Stores result in daily_insights.

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

function addDays(base: string, days: number): string {
  const d = new Date(base + "T00:00:00Z");
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().substring(0, 10);
}

function fmtTime(isoStr: string | null): string | null {
  if (!isoStr) return null;
  try {
    const d = new Date(isoStr);
    return `${String(d.getUTCHours()).padStart(2, "0")}:${String(d.getUTCMinutes()).padStart(2, "0")}`;
  } catch { return null; }
}

// ─── Data gathering + summarization ──────────────────────────────────────────

async function buildContextSummary(
  supabase: ReturnType<typeof createClient>,
  userId: string,
  localDate: string,
): Promise<{ summary: string; zone: string } | null> {
  const lines: string[] = [];

  // ── 1. Risk score + active triggers ──
  const { data: riskLive } = await supabase
    .from("risk_score_live")
    .select("zone,top_triggers")
    .eq("user_id", userId)
    .maybeSingle();

  if (!riskLive) return null;

  const zone = (riskLive.zone ?? "NONE").toUpperCase();
  const triggers = (riskLive.top_triggers ?? []) as any[];

  if (triggers.length > 0) {
    const trigDesc = triggers.slice(0, 6).map((t: any) =>
      `${t.name} (score ${t.score ?? 0}, ${t.severity ?? "LOW"}, active ${t.days_active ?? 1}/7 days)`
    ).join(", ");
    lines.push(`ACTIVE TRIGGERS (higher score = more impactful): ${trigDesc}`);
  } else {
    lines.push("ACTIVE TRIGGERS: none");
  }

  // ── 2. All migraines — summarized ──
  const { data: allMigraines } = await supabase
    .from("migraines")
    .select("start_at,severity,type,pain_locations")
    .eq("user_id", userId)
    .order("start_at", { ascending: false })
    .limit(200);

  if (allMigraines && allMigraines.length > 0) {
    const total = allMigraines.length;
    const last7 = allMigraines.filter((m: any) => m.start_at?.substring(0, 10) >= addDays(localDate, -7)).length;
    const last30 = allMigraines.filter((m: any) => m.start_at?.substring(0, 10) >= addDays(localDate, -30)).length;
    const avgSev = (allMigraines.reduce((s: number, m: any) => s + (m.severity ?? 0), 0) / total).toFixed(1);

    const typeCounts: Record<string, number> = {};
    for (const m of allMigraines) { if (m.type) typeCounts[m.type] = (typeCounts[m.type] ?? 0) + 1; }
    const topType = Object.entries(typeCounts).sort((a, b) => b[1] - a[1])[0];

    const lastDate = allMigraines[0]?.start_at?.substring(0, 10) ?? "unknown";

    let migLine = `MIGRAINES: ${last7} in last 7 days, ${last30} in last 30 days, ${total} total. Avg severity ${avgSev}/10.`;
    if (topType) migLine += ` Most common type: ${topType[0]}.`;
    migLine += ` Last migraine: ${lastDate}.`;

    const prev30 = allMigraines.filter((m: any) => {
      const d = m.start_at?.substring(0, 10);
      return d >= addDays(localDate, -60) && d < addDays(localDate, -30);
    }).length;
    if (last30 > prev30 + 2) migLine += " Trend: INCREASING.";
    else if (last30 < prev30 - 2) migLine += " Trend: DECREASING.";
    else migLine += " Trend: stable.";

    lines.push(migLine);
  } else {
    lines.push("MIGRAINES: none recorded.");
  }

  // ── 3. All reliefs — summarized ──
  const { data: allReliefs } = await supabase
    .from("reliefs")
    .select("type,relief_scale,duration_minutes")
    .eq("user_id", userId)
    .limit(500);

  if (allReliefs && allReliefs.length > 0) {
    const reliefMap: Record<string, Record<string, number>> = {};
    for (const r of allReliefs) {
      if (!r.type) continue;
      if (!reliefMap[r.type]) reliefMap[r.type] = {};
      const scale = r.relief_scale ?? "unknown";
      reliefMap[r.type][scale] = (reliefMap[r.type][scale] ?? 0) + 1;
    }
    const reliefDesc = Object.entries(reliefMap).map(([name, scales]) => {
      const total = Object.values(scales).reduce((a, b) => a + b, 0);
      const breakdown = Object.entries(scales).map(([s, c]) => `${s}:${c}`).join(", ");
      return `${name} (${total}x — ${breakdown})`;
    }).join("; ");
    lines.push(`RELIEFS HISTORY: ${reliefDesc}`);
  }

  // ── 4. All medicines — summarized ──
  const { data: allMeds } = await supabase
    .from("medicines")
    .select("name,amount,relief_scale,category")
    .eq("user_id", userId)
    .limit(500);

  if (allMeds && allMeds.length > 0) {
    const medMap: Record<string, Record<string, number>> = {};
    for (const m of allMeds) {
      if (!m.name) continue;
      if (!medMap[m.name]) medMap[m.name] = {};
      const scale = m.relief_scale ?? "unknown";
      medMap[m.name][scale] = (medMap[m.name][scale] ?? 0) + 1;
    }
    const medDesc = Object.entries(medMap).map(([name, scales]) => {
      const total = Object.values(scales).reduce((a, b) => a + b, 0);
      const breakdown = Object.entries(scales).map(([s, c]) => `${s}:${c}`).join(", ");
      return `${name} (${total}x — ${breakdown})`;
    }).join("; ");
    lines.push(`MEDICINES HISTORY: ${medDesc}`);
  }

  // ── 5. Sleep — last 3 nights ──
  const sleepStart = addDays(localDate, -3);
  const { data: sleepDur } = await supabase
    .from("sleep_duration_daily")
    .select("date,value_hours")
    .eq("user_id", userId)
    .gte("date", sleepStart).lte("date", localDate)
    .order("date", { ascending: false });

  const { data: sleepScore } = await supabase
    .from("sleep_score_daily")
    .select("date,value_pct")
    .eq("user_id", userId)
    .gte("date", sleepStart).lte("date", localDate)
    .order("date", { ascending: false });

  const { data: sleepEff } = await supabase
    .from("sleep_efficiency_daily")
    .select("date,value_pct")
    .eq("user_id", userId)
    .gte("date", sleepStart).lte("date", localDate)
    .order("date", { ascending: false });

  if (sleepDur && sleepDur.length > 0) {
    const sleepLines = sleepDur.map((s: any) => {
      const score = sleepScore?.find((sc: any) => sc.date === s.date);
      const eff = sleepEff?.find((e: any) => e.date === s.date);
      let line = `${s.date}: ${s.value_hours?.toFixed(1)}h`;
      if (score?.value_pct) line += `, score ${Math.round(score.value_pct)}`;
      if (eff?.value_pct) line += `, efficiency ${Math.round(eff.value_pct)}%`;
      return line;
    });
    lines.push(`SLEEP (last 3 nights): ${sleepLines.join(" | ")}`);
  }

  // ── 6. Bedtime + wake time — last night ──
  const { data: bedtime } = await supabase
    .from("fell_asleep_time_daily")
    .select("value_at")
    .eq("user_id", userId).eq("date", localDate)
    .maybeSingle();

  const { data: waketime } = await supabase
    .from("woke_up_time_daily")
    .select("value_at")
    .eq("user_id", userId).eq("date", localDate)
    .maybeSingle();

  if (bedtime?.value_at || waketime?.value_at) {
    const bed = fmtTime(bedtime?.value_at);
    const wake = fmtTime(waketime?.value_at);
    lines.push(`LAST NIGHT: fell asleep ${bed ?? "?"}, woke up ${wake ?? "?"}`);
  }

  // ── 7. Weather today ──
  const { data: weather } = await supabase
    .from("user_weather_daily")
    .select("temp_c_mean,temp_c_max,temp_c_min,pressure_hpa_mean,humidity_pct_mean,is_thunderstorm_day,weather_code")
    .eq("user_id", userId).eq("date", localDate)
    .maybeSingle();

  if (weather) {
    const tMin = weather.temp_c_min != null ? Number(weather.temp_c_min).toFixed(1) : "?";
    const tMax = weather.temp_c_max != null ? Number(weather.temp_c_max).toFixed(1) : "?";
    const tAvg = weather.temp_c_mean != null ? Number(weather.temp_c_mean).toFixed(1) : "?";
    const pres = weather.pressure_hpa_mean != null ? Math.round(Number(weather.pressure_hpa_mean)) : "?";
    const hum = weather.humidity_pct_mean != null ? Math.round(Number(weather.humidity_pct_mean)) : "?";
    let wLine = `WEATHER TODAY: temp ${tMin}–${tMax}°C (avg ${tAvg}°C)`;
    wLine += `, pressure ${pres}hPa`;
    wLine += `, humidity ${hum}%`;
    if (weather.is_thunderstorm_day) wLine += `, THUNDERSTORM`;
    lines.push(wLine);

    const { data: yWeather } = await supabase
      .from("user_weather_daily")
      .select("pressure_hpa_mean")
      .eq("user_id", userId).eq("date", addDays(localDate, -1))
      .maybeSingle();

    if (yWeather?.pressure_hpa_mean && weather.pressure_hpa_mean) {
      const diff = Number(weather.pressure_hpa_mean) - Number(yWeather.pressure_hpa_mean);
      if (Math.abs(diff) >= 5) {
        lines.push(`PRESSURE CHANGE: ${diff > 0 ? "rising" : "dropping"} ${Math.abs(diff).toFixed(0)}hPa since yesterday`);
      }
    }
  }

  // ── 8. Screen time ──
  const { data: screenYesterday } = await supabase
    .from("screen_time_daily")
    .select("total_hours")
    .eq("user_id", userId).eq("date", addDays(localDate, -1))
    .maybeSingle();

  const { data: screenLateNight } = await supabase
    .from("screen_time_late_night")
    .select("value_hours")
    .eq("user_id", userId).eq("date", localDate)
    .maybeSingle();

  if (screenYesterday?.total_hours || screenLateNight?.value_hours) {
    let scLine = "SCREEN TIME:";
    if (screenYesterday?.total_hours) scLine += ` yesterday ${Number(screenYesterday.total_hours).toFixed(1)}h total`;
    if (screenLateNight?.value_hours) scLine += `, late night ${Number(screenLateNight.value_hours).toFixed(1)}h`;
    lines.push(scLine);
  }

  // ── 9. Nutrition — last 3 days ──
  const { data: nutRows } = await supabase
    .from("nutrition_daily")
    .select("date,total_caffeine_mg,max_tyramine_exposure,max_alcohol_exposure,max_gluten_exposure,meal_count")
    .eq("user_id", userId)
    .gte("date", addDays(localDate, -3)).lte("date", localDate)
    .order("date", { ascending: false });

  if (nutRows && nutRows.length > 0) {
    const nutFlags: string[] = [];
    for (const n of nutRows) {
      const flags: string[] = [];
      if (n.max_alcohol_exposure && n.max_alcohol_exposure !== "none") flags.push(`alcohol(${n.max_alcohol_exposure})`);
      if (n.max_tyramine_exposure && n.max_tyramine_exposure !== "none") flags.push(`tyramine(${n.max_tyramine_exposure})`);
      if (n.max_gluten_exposure && n.max_gluten_exposure !== "none") flags.push(`gluten(${n.max_gluten_exposure})`);
      if (n.total_caffeine_mg && n.total_caffeine_mg > 200) flags.push(`caffeine(${Math.round(n.total_caffeine_mg)}mg)`);
      if (n.meal_count != null && n.meal_count <= 1) flags.push("skipped meals");
      if (flags.length > 0) nutFlags.push(`${n.date}: ${flags.join(", ")}`);
    }
    if (nutFlags.length > 0) lines.push(`NUTRITION (last 3 days): ${nutFlags.join(" | ")}`);
  }

  // ── 10. Stress + Recovery ──
  const { data: stress } = await supabase
    .from("stress_index_daily")
    .select("date,value")
    .eq("user_id", userId)
    .gte("date", addDays(localDate, -1)).lte("date", localDate)
    .order("date", { ascending: false });

  if (stress && stress.length > 0) {
    const stressDesc = stress.map((s: any) => `${s.date}: ${Number(s.value).toFixed(1)}`).join(", ");
    lines.push(`STRESS INDEX (0=calm, 100=high): ${stressDesc}`);
  }

  const { data: recovery } = await supabase
    .from("recovery_score_daily")
    .select("value_pct")
    .eq("user_id", userId).eq("date", localDate)
    .maybeSingle();

  if (recovery?.value_pct) {
    lines.push(`RECOVERY SCORE TODAY: ${Math.round(recovery.value_pct)}%`);
  }

  // ── 11. Menstruation cycle ──
  const { data: menses } = await supabase
    .from("menstruation_settings")
    .select("last_menstruation_date,avg_cycle_length")
    .eq("user_id", userId)
    .maybeSingle();

  if (menses?.last_menstruation_date && menses?.avg_cycle_length) {
    const lastDate = new Date(menses.last_menstruation_date + "T00:00:00Z");
    const today = new Date(localDate + "T00:00:00Z");
    const daysSinceLast = Math.round((today.getTime() - lastDate.getTime()) / 86400000);
    const cycleDay = daysSinceLast % menses.avg_cycle_length;
    const daysUntilNext = menses.avg_cycle_length - cycleDay;

    let phase = "follicular";
    if (cycleDay <= 5) phase = "menstruation";
    else if (cycleDay >= menses.avg_cycle_length - 14 && cycleDay < menses.avg_cycle_length - 10) phase = "ovulation";
    else if (cycleDay >= menses.avg_cycle_length - 10) phase = "luteal (pre-menstrual)";

    lines.push(`MENSTRUAL CYCLE: day ${cycleDay} of ${menses.avg_cycle_length}-day cycle, phase: ${phase}, ${daysUntilNext} days until next period`);
  }

  // ── 12. Missed activities — last 7 days ──
  const { data: missedAct } = await supabase
    .from("missed_activities")
    .select("type")
    .eq("user_id", userId)
    .gte("start_at", addDays(localDate, -7) + "T00:00:00Z");

  if (missedAct && missedAct.length > 0) {
    const missedCounts: Record<string, number> = {};
    for (const m of missedAct) { if (m.type) missedCounts[m.type] = (missedCounts[m.type] ?? 0) + 1; }
    const missedDesc = Object.entries(missedCounts).map(([t, c]) => `${t} x${c}`).join(", ");
    lines.push(`MISSED ACTIVITIES (last 7 days): ${missedDesc}`);
  }

  // ── 13. Ambient noise ──
  const { data: noise } = await supabase
    .from("ambient_noise_index_daily")
    .select("value_index_pct")
    .eq("user_id", userId).eq("date", localDate)
    .maybeSingle();

  if (noise?.value_index_pct) {
    lines.push(`AMBIENT NOISE INDEX TODAY: ${Math.round(Number(noise.value_index_pct))}% (higher = noisier than usual)`);
  }

  return { summary: lines.join("\n"), zone };
}

// ─── GPT call ────────────────────────────────────────────────────────────────

async function generateInsight(zone: string, contextSummary: string, openaiKey: string): Promise<string> {
  const systemPrompt = `You are a migraine-specialist neurologist embedded in a personal tracking app. You have access to the patient's full history. The patient has a dashboard showing their risk gauge and active triggers already. Your job is to give clinical, personalised advice they can act on RIGHT NOW.

Based on the patient's current risk level, give specific advice:

WHEN RISK IS HIGH OR A MIGRAINE IS ACTIVE:
- Focus on RELIEF: reference specific medicines and relief methods from their history that have worked well
- Tell them to have their most effective medicine ready by name
- Suggest their most effective relief methods by name
- If a medicine has poor effectiveness in their history, note it may not be the best option for them

WHEN RISK IS MILD:
- Focus on PREVENTION: name the specific triggers that are active and what they can do about each
- Cross-reference today's conditions (weather, sleep, nutrition) with their active triggers
- Suggest concrete preventive steps: hydration targets, break intervals, environmental changes

WHEN RISK IS LOW:
- Light preventive advice based on what's slightly off (sleep, nutrition, weather)
- Note any patterns worth watching

WHEN RISK IS NONE:
- Brief note on what's working and one thing to maintain

RULES:
- 2-3 sentences MAX. Every word must earn its place.
- Focus advice on the HIGHEST-SCORING triggers. Ignore triggers with score 1-2 unless they're the only ones active.
- Be specific: use actual names of medicines, reliefs, triggers from their data.
- NEVER mention scores, percentages, zones, or risk levels.
- NEVER summarise their data back to them. Only give ADVICE.
- Cross-reference data sources: weather + triggers, sleep + stress, nutrition + triggers, cycle phase + history.
- If a medicine has mostly NONE or POOR relief in their history, suggest they discuss alternatives with their doctor.
- Reference their relief history personally: "Dark room has worked well for you before" not generic advice.
- Tone: direct, warm, clinical but not cold. Like a specialist who genuinely cares texting a patient they know well.
- NEVER use em dashes. Use commas, full stops, or rewrite the sentence.
- NEVER use these phrases: "keep up the good work", "you're doing great", "stay on track", "looking good", "keep it up", "well done". Be substantive, not cheerful.
- Plain text only. No markdown, no bullets, no headers, no greetings, no sign-offs.`;

  const userMessage = `RISK LEVEL: ${zone}

${contextSummary}

Give actionable advice based on the above.`;

  const response = await fetch("https://api.openai.com/v1/chat/completions", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${openaiKey}`,
    },
    body: JSON.stringify({
      model: "gpt-4o-mini",
      messages: [
        { role: "system", content: systemPrompt },
        { role: "user", content: userMessage },
      ],
      max_tokens: 250,
      temperature: 0.7,
    }),
  });

  if (!response.ok) {
    const err = await response.text();
    throw new Error(`OpenAI error ${response.status}: ${err}`);
  }

  const data = await response.json();
  return (data.choices?.[0]?.message?.content ?? "").trim();
}

// ═════════════════════════════════════════════════════════════════════════════
// MAIN
// ═════════════════════════════════════════════════════════════════════════════

const BATCH_SIZE = 3;

serve(async (_req) => {
  const t0 = Date.now();
  console.log("[ai-insight-worker] === START ===");

  try {
    const supabaseUrl = requireEnv("SUPABASE_URL");
    const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");
    const openaiKey = requireEnv("OPENAI_API_KEY");

    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false },
    });

    const nowIso = new Date().toISOString();

    // ── Unstick stale processing jobs (>5 min old) ──
    await supabase
      .from("daily_insight_jobs")
      .update({ status: "queued", updated_at: nowIso })
      .eq("status", "processing")
      .lt("updated_at", new Date(Date.now() - 5 * 60_000).toISOString());

    // ── Pick queued jobs ──
    const { data: jobs } = await supabase
      .from("daily_insight_jobs")
      .select("id,user_id,local_date")
      .eq("status", "queued")
      .order("created_at", { ascending: true })
      .limit(BATCH_SIZE);

    if (!jobs || jobs.length === 0) {
      console.log("[ai-insight-worker] no queued jobs");
      return jsonResponse({ ok: true, processed: 0 });
    }

    console.log(`[ai-insight-worker] picked ${jobs.length} job(s)`);

    const jobIds = jobs.map((j) => j.id);
    await supabase
      .from("daily_insight_jobs")
      .update({ status: "processing", updated_at: nowIso })
      .in("id", jobIds);

    let processed = 0;
    let errors = 0;

    for (const job of jobs) {
      const { id: jobId, user_id: userId, local_date: localDate } = job;

      try {
        const { data: existing } = await supabase
          .from("daily_insights")
          .select("id")
          .eq("user_id", userId)
          .eq("date", localDate)
          .maybeSingle();

        if (existing) {
          await supabase.from("daily_insight_jobs")
            .update({ status: "done", updated_at: nowIso }).eq("id", jobId);
          continue;
        }

        const ctx = await buildContextSummary(supabase, userId, localDate);
        if (!ctx) {
          await supabase.from("daily_insight_jobs")
            .update({ status: "error", error: "no risk data", updated_at: nowIso }).eq("id", jobId);
          errors++;
          continue;
        }

        console.log(`[ai-insight-worker] ${userId}: context ${ctx.summary.length} chars, zone=${ctx.zone}`);

        const insight = await generateInsight(ctx.zone, ctx.summary, openaiKey);

        if (!insight) {
          await supabase.from("daily_insight_jobs")
            .update({ status: "error", error: "empty GPT response", updated_at: nowIso }).eq("id", jobId);
          errors++;
          continue;
        }

        const { error: insertErr } = await supabase.from("daily_insights").upsert(
          {
            user_id: userId,
            date: localDate,
            insight,
            risk_zone: ctx.zone,
            context_snapshot: ctx.summary,
            created_at: nowIso,
          },
          { onConflict: "user_id,date" },
        );

        if (insertErr) {
          await supabase.from("daily_insight_jobs")
            .update({ status: "error", error: insertErr.message, updated_at: nowIso }).eq("id", jobId);
          errors++;
        } else {
          console.log(`[ai-insight-worker] ${userId}: done "${insight.substring(0, 80)}..."`);
          await supabase.from("daily_insight_jobs")
            .update({ status: "done", updated_at: nowIso }).eq("id", jobId);
          processed++;
        }
      } catch (e) {
        console.error(`[ai-insight-worker] ${userId}: ${(e as Error).message}`);
        await supabase.from("daily_insight_jobs")
          .update({ status: "error", error: (e as Error).message, updated_at: nowIso }).eq("id", jobId);
        errors++;
      }
    }

    const elapsed = Date.now() - t0;
    console.log(`[ai-insight-worker] === DONE ${elapsed}ms, processed=${processed}, errors=${errors} ===`);
    return jsonResponse({ ok: true, processed, errors, elapsedMs: elapsed });

  } catch (e) {
    console.error("[ai-insight-worker] FATAL:", e);
    return jsonResponse({ ok: false, error: (e as Error).message }, 500);
  }
});