// supabase/functions/recalibrate/index.ts
//
// Recalibration — re-runs the onboarding AI with REAL data as an overlay
// on the original questionnaire answers.
//
//   Call 1 (The Neurologist): questionnaire baseline + data overlay → clinical assessment,
//                             profile updates, trigger/prodrome/favorite adjustments
//   Call 2 (The Statistician): trigger stats + gauge performance → thresholds + decay curves
//
// Output: proposals written to recalibration_proposals table.
// Everything is a PROPOSAL — nothing changes until the user approves.

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const OPENAI_API_KEY = Deno.env.get("OPENAI_API_KEY")!;

serve(async (req: Request) => {
  try {
    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_KEY);
    const body = await req.json().catch(() => ({}));

    // Auth
    let userId: string;
    if (body.user_id) {
      userId = body.user_id;
    } else {
      const token = (req.headers.get("Authorization") ?? "").replace("Bearer ", "");
      const { data: { user } } = await supabase.auth.getUser(token);
      if (!user) return json({ error: "Unauthorized" }, 401);
      userId = user.id;
    }

    // ── Premium check ──
    try {
      const { data: premiumRow, error: premiumErr } = await supabase
        .from("premium_status")
        .select("trial_end, rc_subscription_status")
        .eq("user_id", userId)
        .maybeSingle();

      if (!premiumErr) {
        const trialActive = premiumRow?.trial_end && new Date(premiumRow.trial_end) > new Date();
        const subActive = premiumRow?.rc_subscription_status === "active" || premiumRow?.rc_subscription_status === "grace_period";
        if (!trialActive && !subActive) {
          return json({ ok: false, error: "premium_required", message: "This feature requires a premium subscription." }, 403);
        }
      }
    } catch {
      // fail open
    }

    const force = body.force === true;
    // "full" = monthly recalibration (both calls), "profile_only" = just Call 1
    const mode: string = body.mode ?? "full";

    // ── Cooldown check ──
    if (!force) {
      if (mode === "full") {
        const { data: lastRecalib } = await supabase
          .from("recalibration_history")
          .select("next_recalibration_at, created_at")
          .eq("user_id", userId)
          .order("created_at", { ascending: false })
          .limit(1)
          .single();

        if (lastRecalib?.next_recalibration_at) {
          const nextAt = new Date(lastRecalib.next_recalibration_at);
          if (nextAt > new Date()) {
            return json({
              status: "cooldown",
              next_recalibration_at: lastRecalib.next_recalibration_at,
              message: "Next recalibration available after " + nextAt.toISOString().substring(0, 10),
            });
          }
        }
      } else if (mode === "profile_only") {
        const { data: lastProfile } = await supabase
          .from("recalibration_history")
          .select("created_at, metadata")
          .eq("user_id", userId)
          .order("created_at", { ascending: false })
          .limit(1)
          .single();

        if (lastProfile?.created_at) {
          const lastRun = new Date(lastProfile.created_at);
          const hoursSince = (Date.now() - lastRun.getTime()) / 3600000;
          if (hoursSince < 24 && lastProfile.metadata?.mode === "profile_only") {
            return json({
              status: "cooldown",
              next_recalibration_at: new Date(lastRun.getTime() + 24 * 3600000).toISOString(),
              message: "Profile re-assessment is available once per day. Try again tomorrow.",
            });
          }
        }
      }
    }

    // ══════════════════════════════════════════════════════════════
    // LOAD ALL DATA
    // ══════════════════════════════════════════════════════════════

    // 1. Onboarding profile (includes questionnaire answers + original assessment)
    const { data: profile } = await supabase
      .from("ai_setup_profiles")
      .select("*")
      .eq("user_id", userId)
      .single();

    if (!profile) return json({ status: "no_profile" });

    // 2. All migraines
    const { data: migraines } = await supabase
      .from("migraines")
      .select("id, start_at, ended_at, severity, type")
      .eq("user_id", userId)
      .not("start_at", "is", null)
      .order("start_at", { ascending: false });

    if (!migraines || migraines.length < 5) {
      return json({ status: "insufficient_data", migraine_count: migraines?.length ?? 0 });
    }

    const migraineIds = new Set(migraines.map((m: any) => m.id));

    // 3. All linked items
    const { data: triggers } = await supabase
      .from("triggers").select("type, migraine_id, source").eq("user_id", userId);
    const { data: prodromes } = await supabase
      .from("prodromes").select("type, migraine_id, source").eq("user_id", userId);
    const { data: medicines } = await supabase
      .from("medicines").select("type, migraine_id").eq("user_id", userId);
    const { data: reliefs } = await supabase
      .from("reliefs").select("type, migraine_id").eq("user_id", userId);
    const { data: symptoms } = await supabase
      .from("symptoms").select("type, migraine_id").eq("user_id", userId);
    const { data: activities } = await supabase
      .from("activities").select("type, migraine_id").eq("user_id", userId);
    const { data: missedActivities } = await supabase
      .from("missed_activities").select("type, migraine_id").eq("user_id", userId);
    const { data: locations } = await supabase
      .from("locations").select("name, migraine_id").eq("user_id", userId);

    // 4. Current config — pools
    const { data: triggerPool } = await supabase
      .from("user_triggers")
      .select("id, label, category, prediction_value, metric_table, display_group")
      .eq("user_id", userId);
    const { data: prodromePool } = await supabase
      .from("user_prodromes")
      .select("id, label, category, prediction_value, metric_table, display_group")
      .eq("user_id", userId);

    // 5. Current favorites
    const { data: trigFavs } = await supabase
      .from("trigger_preferences").select("trigger_id").eq("user_id", userId);
    const trigFavIds = new Set((trigFavs ?? []).map((f: any) => f.trigger_id));

    const { data: prodFavs } = await supabase
      .from("prodrome_user_preferences").select("prodrome_id").eq("user_id", userId);
    const prodFavIds = new Set((prodFavs ?? []).map((f: any) => f.prodrome_id));

    const { data: symptomFavs } = await supabase
      .from("symptom_preferences").select("symptom_id").eq("user_id", userId);
    const symptomFavIds = new Set((symptomFavs ?? []).map((f: any) => f.symptom_id));

    const { data: medFavs } = await supabase
      .from("medicine_preferences").select("medicine_id").eq("user_id", userId);
    const medFavIds = new Set((medFavs ?? []).map((f: any) => f.medicine_id));

    const { data: reliefFavs } = await supabase
      .from("relief_preferences").select("relief_id").eq("user_id", userId);
    const reliefFavIds = new Set((reliefFavs ?? []).map((f: any) => f.relief_id));

    const { data: actFavs } = await supabase
      .from("activity_preferences").select("activity_id").eq("user_id", userId);
    const actFavIds = new Set((actFavs ?? []).map((f: any) => f.activity_id));

    const { data: missedFavs } = await supabase
      .from("missed_activity_preferences").select("missed_activity_id").eq("user_id", userId);
    const missedFavIds = new Set((missedFavs ?? []).map((f: any) => f.missed_activity_id));

    // 6. Gauge data
    const { data: dailyScores } = await supabase
      .from("risk_score_daily")
      .select("date, score, zone")
      .eq("user_id", userId)
      .order("date", { ascending: false });

    const { data: thresholdRows } = await supabase
      .from("risk_gauge_thresholds")
      .select("zone, min_value")
      .eq("user_id", userId);

    const { data: decayRows } = await supabase
      .from("risk_decay_weights")
      .select("severity, day_0, day_1, day_2, day_3, day_4, day_5, day_6")
      .eq("user_id", userId);

    // 7. Connected data sources
    const { data: metricSettings } = await supabase
      .from("metric_settings")
      .select("metric, enabled, preferred_source")
      .eq("user_id", userId);

    // ══════════════════════════════════════════════════════════════
    // DETERMINISTIC COMPARISONS (cheap math on already-loaded data)
    // ══════════════════════════════════════════════════════════════

    const mc = migraines.length;

    function countLinked(logs: any[], field = "type"): Record<string, { linked: number; total: number }> {
      const counts: Record<string, { linked: number; total: number }> = {};
      for (const log of logs ?? []) {
        const key = (log[field] ?? log.name ?? "").toLowerCase();
        if (!key) continue;
        if (!counts[key]) counts[key] = { linked: 0, total: 0 };
        counts[key].total++;
        if (log.migraine_id && migraineIds.has(log.migraine_id)) counts[key].linked++;
      }
      return counts;
    }

    const triggerCounts = countLinked(triggers ?? []);
    const prodromeCounts = countLinked(prodromes ?? []);
    const medicineCounts = countLinked(medicines ?? []);
    const reliefCounts = countLinked(reliefs ?? []);
    const symptomCounts = countLinked(symptoms ?? []);
    const activityCounts = countLinked(activities ?? []);
    const missedCounts = countLinked(missedActivities ?? []);
    const locationCounts = countLinked(locations ?? [], "name");

    // ── Profile field overlay: stored vs actual ──
    const profileOverlay: { field: string; stored: string; actual: string }[] = [];

    // Frequency
    const oldest = migraines[migraines.length - 1]?.start_at;
    const newest = migraines[0]?.start_at;
    const totalSpanDays = oldest && newest
      ? Math.max(1, (new Date(newest).getTime() - new Date(oldest).getTime()) / 86400000)
      : 0;
    const actualFreqPerMonth = totalSpanDays > 0 ? (mc / (totalSpanDays / 30)) : 0;
    profileOverlay.push({
      field: "frequency",
      stored: profile.frequency ?? "unknown",
      actual: `${actualFreqPerMonth.toFixed(1)} migraines/month over ${Math.round(totalSpanDays)} days (${mc} total)`,
    });

    // Duration
    const durationsHrs = migraines
      .filter((m: any) => m.start_at && m.ended_at)
      .map((m: any) => (new Date(m.ended_at).getTime() - new Date(m.start_at).getTime()) / 3600000)
      .filter((h: number) => h > 0 && h < 168);
    if (durationsHrs.length > 0) {
      const avgDuration = durationsHrs.reduce((a: number, b: number) => a + b, 0) / durationsHrs.length;
      profileOverlay.push({
        field: "duration",
        stored: profile.duration ?? "unknown",
        actual: `Average ${avgDuration.toFixed(1)} hours (from ${durationsHrs.length} migraines with end time)`,
      });
    }

    // Trajectory (first half vs second half)
    if (mc >= 6 && totalSpanDays > 30) {
      const midDate = new Date(new Date(oldest).getTime() + (totalSpanDays / 2) * 86400000);
      const firstHalf = migraines.filter((m: any) => new Date(m.start_at) <= midDate).length;
      const secondHalf = mc - firstHalf;
      const halfSpan = totalSpanDays / 2 / 30;
      const firstRate = firstHalf / halfSpan;
      const secondRate = secondHalf / halfSpan;
      let actualTrajectory = "stable";
      if (secondRate > firstRate * 1.3) actualTrajectory = "worsening";
      else if (secondRate < firstRate * 0.7) actualTrajectory = "improving";
      profileOverlay.push({
        field: "trajectory",
        stored: profile.trajectory ?? "unknown",
        actual: `${actualTrajectory} (first half: ${firstRate.toFixed(1)}/month, second half: ${secondRate.toFixed(1)}/month)`,
      });
    }

    // Seasonal pattern
    const monthBuckets: Record<string, number> = {};
    for (const m of migraines) {
      if (m.start_at) {
        const month = new Date(m.start_at).getMonth();
        const season =
          month <= 1 || month === 11 ? "winter" :
          month <= 4 ? "spring" :
          month <= 7 ? "summer" : "fall";
        monthBuckets[season] = (monthBuckets[season] ?? 0) + 1;
      }
    }
    const topSeason = Object.entries(monthBuckets).sort((a, b) => b[1] - a[1])?.[0];
    if (topSeason && topSeason[1] > mc * 0.4) {
      profileOverlay.push({
        field: "seasonal_pattern",
        stored: profile.seasonal_pattern ?? "none",
        actual: `Highest in ${topSeason[0]} (${topSeason[1]} of ${mc}). Distribution: ${Object.entries(monthBuckets).map(([s, n]) => `${s}: ${n}`).join(", ")}`,
      });
    } else {
      profileOverlay.push({
        field: "seasonal_pattern",
        stored: profile.seasonal_pattern ?? "none",
        actual: `No clear pattern. Distribution: ${Object.entries(monthBuckets).map(([s, n]) => `${s}: ${n}`).join(", ")}`,
      });
    }

    // Trigger areas — top categories by linked count
    const trigCategoryCounts: Record<string, number> = {};
    for (const pool of triggerPool ?? []) {
      const key = pool.label.toLowerCase();
      const counts = triggerCounts[key];
      if (counts && counts.linked > 0) {
        const cat = (pool.category ?? "Other").toLowerCase();
        trigCategoryCounts[cat] = (trigCategoryCounts[cat] ?? 0) + counts.linked;
      }
    }
    const topCategories = Object.entries(trigCategoryCounts)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 5)
      .map(([cat, n]) => `${cat} (${n} links)`);
    profileOverlay.push({
      field: "trigger_areas",
      stored: (profile.trigger_areas ?? []).join(", ") || "none",
      actual: topCategories.length > 0 ? topCategories.join(", ") : "not enough linked data yet",
    });

    // Severity average
    const severities = migraines.filter((m: any) => m.severity != null).map((m: any) => m.severity);
    const avgSeverity = severities.length > 0
      ? (severities.reduce((a: number, b: number) => a + b, 0) / severities.length).toFixed(1)
      : "unknown";

    // Gauge performance
    const migDates = new Set(migraines.map((m: any) => m.start_at?.substring(0, 10)));
    let gaugeTP = 0, gaugeFP = 0, gaugeFN = 0, gaugeTN = 0;
    let greenDays = 0, amberDays = 0, yellowDays = 0, redDays = 0;

    for (const day of dailyScores ?? []) {
      const d = day.date?.substring(0, 10);
      const zone = (day.zone ?? "NONE").toUpperCase();
      const warned = zone === "HIGH" || zone === "MILD";
      const migraineNear = migDates.has(d);

      if (zone === "HIGH") redDays++;
      else if (zone === "MILD") yellowDays++;
      else if (zone === "LOW") amberDays++;
      else greenDays++;

      if (warned && migraineNear) gaugeTP++;
      else if (warned && !migraineNear) gaugeFP++;
      else if (!warned && migraineNear) gaugeFN++;
      else gaugeTN++;
    }

    const totalDays = (dailyScores ?? []).length;

    // ══════════════════════════════════════════════════════════════
    // CALL 1 — The Neurologist
    // ══════════════════════════════════════════════════════════════

    const call1UserMessage = buildCall1Message(
      profile, migraines, mc, avgSeverity, profileOverlay,
      triggerPool ?? [], trigFavIds, triggerCounts,
      prodromePool ?? [], prodFavIds, prodromeCounts,
      medicineCounts, medFavIds,
      reliefCounts, reliefFavIds,
      symptomCounts, symptomFavIds,
      activityCounts, actFavIds,
      missedCounts, missedFavIds,
      locationCounts,
      metricSettings ?? [],
    );

    const call1Result = await callOpenAI(CALL1_SYSTEM_PROMPT, call1UserMessage);

    // ══════════════════════════════════════════════════════════════
    // CALL 2 — The Statistician (skip for profile_only mode)
    // ══════════════════════════════════════════════════════════════

    let call2Result: any = {};

    if (mode === "full") {
      const call2UserMessage = buildCall2Message(
        profile, call1Result.clinical_assessment ?? "",
        triggerPool ?? [], triggerCounts, mc,
        gaugeTP, gaugeFP, gaugeFN, gaugeTN,
        totalDays, greenDays, amberDays, yellowDays, redDays,
        thresholdRows ?? [], decayRows ?? [],
      );

      call2Result = await callOpenAI(CALL2_SYSTEM_PROMPT, call2UserMessage);
    }

    // ══════════════════════════════════════════════════════════════
    // WRITE PROPOSALS
    // ══════════════════════════════════════════════════════════════

    // Clear old pending
    await supabase
      .from("recalibration_proposals")
      .delete()
      .eq("user_id", userId)
      .eq("status", "pending");

    const proposals: any[] = [];

    // Trigger adjustments
    for (const adj of call1Result.trigger_adjustments ?? []) {
      proposals.push({
        user_id: userId, type: "trigger", label: adj.label,
        from_value: adj.from, to_value: adj.to,
        should_favorite: adj.should_favorite ?? false,
        reasoning: adj.reasoning, status: "pending",
      });
    }

    // Prodrome adjustments
    for (const adj of call1Result.prodrome_adjustments ?? []) {
      proposals.push({
        user_id: userId, type: "prodrome", label: adj.label,
        from_value: adj.from, to_value: adj.to,
        should_favorite: adj.should_favorite ?? false,
        reasoning: adj.reasoning, status: "pending",
      });
    }

    // Favorite adjustments
    for (const fav of call1Result.favorite_adjustments ?? []) {
      proposals.push({
        user_id: userId, type: fav.item_type, label: fav.label,
        from_value: fav.currently_favorite ? "favorite" : "not_favorite",
        to_value: fav.should_favorite ? "favorite" : "not_favorite",
        should_favorite: fav.should_favorite,
        reasoning: fav.reasoning, status: "pending",
      });
    }

    // Profile updates
    for (const upd of call1Result.profile_updates ?? []) {
      proposals.push({
        user_id: userId, type: "profile", label: upd.field,
        from_value: upd.current_value, to_value: upd.suggested_value,
        reasoning: upd.reasoning, status: "pending",
      });
    }

    // Data warnings
    for (const warn of call1Result.data_warnings ?? []) {
      proposals.push({
        user_id: userId, type: "data_warning",
        label: warn.metric ?? warn.type,
        reasoning: warn.message, status: "pending",
        metadata: { warning_type: warn.type, severity: warn.severity },
      });
    }

    // Gauge thresholds (full mode only)
    if (mode === "full") {
      const gt = call2Result.gauge_thresholds;
      if (gt) {
        for (const zone of ["low", "mild", "high"]) {
          const current = (thresholdRows ?? []).find((r: any) => r.zone?.toUpperCase() === zone.toUpperCase());
          if (gt[zone] != null) {
            proposals.push({
              user_id: userId, type: "gauge_threshold", label: zone.toUpperCase(),
              from_value: String(current?.min_value ?? "?"),
              to_value: String(gt[zone]),
              reasoning: gt.reasoning ?? "", status: "pending",
            });
          }
        }
      }

      // Decay weights (full mode only)
      for (const dw of call2Result.decay_weights ?? []) {
        const currentDecay = (decayRows ?? []).find((r: any) => r.severity?.toUpperCase() === dw.severity?.toUpperCase());
        proposals.push({
          user_id: userId, type: "gauge_decay", label: dw.severity,
          from_value: currentDecay ? JSON.stringify({
            day0: currentDecay.day_0, day1: currentDecay.day_1, day2: currentDecay.day_2,
            day3: currentDecay.day_3, day4: currentDecay.day_4, day5: currentDecay.day_5, day6: currentDecay.day_6,
          }) : "{}",
          to_value: JSON.stringify({
            day0: dw.day0, day1: dw.day1, day2: dw.day2,
            day3: dw.day3, day4: dw.day4, day5: dw.day5, day6: dw.day6,
          }),
          reasoning: dw.reasoning ?? "", status: "pending",
        });
      }
    }

    // Clinical assessment proposal — user must accept the updated narrative
    if (call1Result.clinical_assessment) {
      proposals.push({
        user_id: userId, type: "clinical_assessment", label: "Clinical assessment",
        from_value: (profile.clinical_assessment ?? "").substring(0, 200) + "…",
        to_value: call1Result.clinical_assessment,
        reasoning: call1Result.summary ?? "Updated clinical profile based on your real data.",
        status: "pending",
      });
    }

    // Summary row
    proposals.push({
      user_id: userId, type: "summary", label: "_meta",
      reasoning: call1Result.clinical_assessment ?? "",
      status: "pending",
      metadata: {
        mode,
        call1_summary: call1Result.summary,
        call2_summary: call2Result.summary ?? null,
        calibration_notes: call2Result.calibration_notes ?? null,
        migraine_count: mc,
      },
    });

    // Insert all
    if (proposals.length > 0) {
      await supabase.from("recalibration_proposals").insert(proposals);
    }

    // ── Send FCM ──
    try {
      const { data: prof } = await supabase
        .from("profiles").select("fcm_token").eq("user_id", userId).single();
      if (prof?.fcm_token) {
        await supabase.functions.invoke("send-fcm-push", {
          body: { type: "recalibration_ready", tokens: [prof.fcm_token] },
        });
      }
    } catch (_) { /* non-fatal */ }

    return json({
      status: "ok",
      proposals: proposals.length - 1, // exclude summary row
    });

  } catch (err) {
    console.error("recalibrate error:", err);
    return json({ error: (err as Error).message }, 500);
  }
});


// ══════════════════════════════════════════════════════════════════════
// CALL 1 — The Neurologist (recalibration version)
// ══════════════════════════════════════════════════════════════════════

const CALL1_SYSTEM_PROMPT = `You are a neurologist who previously assessed this migraine patient during onboarding based on their questionnaire answers. Now you're reviewing how their REAL DATA compares to what they told you.

You will receive:
1. The patient's ORIGINAL QUESTIONNAIRE ANSWERS — what they told you about their migraines, sleep, stress, triggers, medicines, lifestyle. This is the FOUNDATION. The patient knows things the data can't capture.
2. Their ORIGINAL CLINICAL ASSESSMENT — what you wrote based on those answers.
3. A DATA OVERLAY — deterministic comparisons showing where reality differs from what they reported (e.g. "stored frequency: 1-3/month, actual: 4.2/month").
4. Their REAL LOGGED DATA — every trigger, prodrome, medicine, relief, symptom, activity linked to their migraines, with concrete counts.
5. Their CURRENT configuration (prediction_values, favorites).
6. Their connected data sources.
7. AVAILABLE LABELS for triggers and prodromes.

YOUR TASK — reconcile the questionnaire baseline with real data:

STEP 1 — UPDATED CLINICAL ASSESSMENT:
Write 3-4 paragraphs as a new clinical profile. Address them as "you/your". Warm, clear, no jargon.

This is a RECONCILIATION — not a replacement. The questionnaire answers are the foundation. The data overlay confirms, contradicts, or adds to what the patient said. Reference both:
- "You mentioned sleep was a key trigger area, and your data confirms this — poor sleep was linked to 8 of your 12 migraines."
- "You originally reported 1-3 migraines per month, but your logged data shows an average of 4.2 per month over the last 3 months."
- "Interestingly, you didn't mention weather as a concern, but pressure changes coincide with 5 of your migraines."

Cover:
- Their migraine pattern (what they said vs what the data shows)
- Which of their self-reported triggers are confirmed by data, and which aren't
- New patterns the data reveals that they may not have been aware of
- Their treatment patterns (which medicines/reliefs they actually reach for)
- Overall clinical picture

STEP 2 — PROFILE FIELD UPDATES:
The DATA OVERLAY section shows stored vs actual values for each profile field. Review each comparison. If the real data meaningfully differs from the stored value, propose an update. Reference the actual numbers in your reasoning.

Profile fields: frequency, duration, trajectory, trigger_areas, seasonal_pattern, experience.

STEP 3 — TRIGGER & PRODROME ADJUSTMENTS:
Based on linked counts vs current prediction_value.

CRITICAL RULES:
- Only adjust ONE NOTCH at a time: NONE→LOW, LOW→MILD, MILD→HIGH (and reverse). NEVER skip levels.
- Use ONLY exact label strings from the AVAILABLE LABELS lists.
- Limit to 10-15 most impactful changes.
- Frame gently: "We've noticed X was linked to Y of your migraines — we suggest adjusting it up a notch."
- Lowering IS allowed — the user approves every change.

STEP 4 — FAVORITE ADJUSTMENTS:
Medicines, reliefs, symptoms, activities, missed activities. If logged frequently but not favorited → suggest favorite. If favorited but rarely used → suggest removing.

STEP 5 — DATA WARNINGS:
If a trigger is rated HIGH or MILD but the relevant data source isn't connected, flag it.

Respond with ONLY valid JSON (no markdown fences):
{
  "clinical_assessment": "Your 3-4 paragraph clinical reassessment reconciling questionnaire with real data...",
  "summary": "2-3 sentence summary of key findings",
  "trigger_adjustments": [
    {"label": "exact label", "from": "CURRENT", "to": "NEW (one notch)", "should_favorite": true/false, "reasoning": "gentle explanation referencing data"}
  ],
  "prodrome_adjustments": [
    {"label": "exact label", "from": "CURRENT", "to": "NEW (one notch)", "should_favorite": true/false, "reasoning": "gentle explanation"}
  ],
  "favorite_adjustments": [
    {"item_type": "medicine|relief|symptom|activity|missed_activity", "label": "exact label", "currently_favorite": true/false, "should_favorite": true/false, "reasoning": "..."}
  ],
  "profile_updates": [
    {"field": "frequency|duration|trajectory|trigger_areas|seasonal_pattern|experience", "current_value": "...", "suggested_value": "...", "reasoning": "cite actual numbers"}
  ],
  "data_warnings": [
    {"type": "missing_data|missing_connection|suggestion", "message": "user-facing message", "metric": "metric_name|null", "severity": "high|medium|low"}
  ]
}`;

function buildCall1Message(
  profile: any, migraines: any[], mc: number, avgSeverity: string,
  profileOverlay: { field: string; stored: string; actual: string }[],
  triggerPool: any[], trigFavIds: Set<string>, triggerCounts: Record<string, any>,
  prodromePool: any[], prodFavIds: Set<string>, prodromeCounts: Record<string, any>,
  medicineCounts: Record<string, any>, medFavIds: Set<string>,
  reliefCounts: Record<string, any>, reliefFavIds: Set<string>,
  symptomCounts: Record<string, any>, symptomFavIds: Set<string>,
  activityCounts: Record<string, any>, actFavIds: Set<string>,
  missedCounts: Record<string, any>, missedFavIds: Set<string>,
  locationCounts: Record<string, any>,
  metricSettings: any[],
): string {
  const L: string[] = [];

  // ── Original questionnaire answers (THE FOUNDATION) ──
  L.push("=== ORIGINAL QUESTIONNAIRE ANSWERS (the patient's own words) ===");
  const answers = profile.answers ?? {};
  L.push("MIGRAINE BASICS:");
  if (answers.frequency) L.push(`- Frequency: ${answers.frequency}`);
  if (answers.avg_duration ?? answers.avgDuration) L.push(`- Average duration: ${answers.avg_duration ?? answers.avgDuration}`);
  if (answers.avg_severity ?? answers.avgSeverity) L.push(`- Average severity: ${answers.avg_severity ?? answers.avgSeverity}`);
  if (answers.usual_timing ?? answers.usualTiming) L.push(`- Usual timing: ${answers.usual_timing ?? answers.usualTiming}`);
  if (answers.warning_before ?? answers.warningBefore) L.push(`- Gets warning signs: ${answers.warning_before ?? answers.warningBefore}`);
  if (answers.family_history ?? answers.familyHistory) L.push(`- Family history: ${answers.family_history ?? answers.familyHistory}`);
  L.push("");
  L.push("TRIGGERS & LIFESTYLE:");
  if (answers.known_trigger_areas ?? answers.knownTriggerAreas) L.push(`- Known trigger areas: ${answers.known_trigger_areas ?? answers.knownTriggerAreas}`);
  if (answers.known_specific_triggers ?? answers.knownSpecificTriggers) L.push(`- Specific triggers: ${answers.known_specific_triggers ?? answers.knownSpecificTriggers}`);
  if (answers.warning_signs_experienced ?? answers.warningSignsExperienced) L.push(`- Warning signs: ${answers.warning_signs_experienced ?? answers.warningSignsExperienced}`);
  if (answers.typical_sleep_hours ?? answers.typicalSleepHours) L.push(`- Typical sleep: ${answers.typical_sleep_hours ?? answers.typicalSleepHours} hours`);
  if (answers.sleep_quality ?? answers.sleepQuality) L.push(`- Sleep quality: ${answers.sleep_quality ?? answers.sleepQuality}`);
  if (answers.screen_time_daily ?? answers.screenTimeDaily) L.push(`- Screen time: ${answers.screen_time_daily ?? answers.screenTimeDaily}`);
  if (answers.caffeine_intake ?? answers.caffeineIntake) L.push(`- Caffeine: ${answers.caffeine_intake ?? answers.caffeineIntake}`);
  if (answers.alcohol_intake ?? answers.alcoholIntake) L.push(`- Alcohol: ${answers.alcohol_intake ?? answers.alcoholIntake}`);
  if (answers.exercise_frequency ?? answers.exerciseFrequency) L.push(`- Exercise: ${answers.exercise_frequency ?? answers.exerciseFrequency}`);
  if (answers.stress_level ?? answers.stressLevel) L.push(`- Stress level: ${answers.stress_level ?? answers.stressLevel}`);
  if (answers.water_intake ?? answers.waterIntake) L.push(`- Water intake: ${answers.water_intake ?? answers.waterIntake}`);
  if (answers.track_cycle ?? answers.trackCycle) L.push(`- Tracks cycle: ${answers.track_cycle ?? answers.trackCycle}`);
  if (answers.cycle_related ?? answers.cycleRelated) L.push(`- Migraines cycle-related: ${answers.cycle_related ?? answers.cycleRelated}`);
  L.push("");
  L.push("MEDICINES & MANAGEMENT:");
  if (answers.selected_symptoms ?? answers.selectedSymptoms) L.push(`- Symptoms: ${answers.selected_symptoms ?? answers.selectedSymptoms}`);
  if (answers.current_medicines ?? answers.currentMedicines) L.push(`- Current medicines: ${answers.current_medicines ?? answers.currentMedicines}`);
  if (answers.preventive_medicines ?? answers.preventiveMedicines) L.push(`- Preventive medicines: ${answers.preventive_medicines ?? answers.preventiveMedicines}`);
  if (answers.helpful_reliefs ?? answers.helpfulReliefs) L.push(`- What helps: ${answers.helpful_reliefs ?? answers.helpfulReliefs}`);
  if (answers.activities_during_migraine ?? answers.activitiesDuringMigraine) L.push(`- Activities during migraine: ${answers.activities_during_migraine ?? answers.activitiesDuringMigraine}`);
  if (answers.missed_activities ?? answers.missedActivities) L.push(`- Missed activities: ${answers.missed_activities ?? answers.missedActivities}`);
  const freeText = answers.free_text ?? answers.additional_notes ?? answers.additionalNotes;
  if (freeText) {
    L.push("");
    L.push(`Patient notes: ${freeText}`);
  }
  L.push("");

  // ── Demographics ──
  L.push("=== PATIENT DEMOGRAPHICS ===");
  L.push(`Gender: ${profile.gender ?? "unknown"}, Age: ${profile.age_range ?? "unknown"}`);
  L.push(`Tracks cycle: ${profile.tracks_cycle ?? "unknown"}`);
  L.push("");

  // ── Original clinical assessment ──
  L.push("=== YOUR ORIGINAL CLINICAL ASSESSMENT ===");
  L.push(profile.clinical_assessment ?? "Not available");
  L.push("");

  // ── Data overlay — stored vs actual ──
  L.push("=== DATA OVERLAY — reality vs what was reported ===");
  L.push(`Total migraines logged: ${mc}, Average severity: ${avgSeverity}/10`);
  L.push("");
  for (const o of profileOverlay) {
    L.push(`${o.field.toUpperCase()}:`);
    L.push(`  From questionnaire: ${o.stored}`);
    L.push(`  From data: ${o.actual}`);
    L.push("");
  }

  // ── Connected data sources ──
  L.push("=== CONNECTED DATA SOURCES ===");
  const enabled = (metricSettings ?? []).filter((m: any) => m.enabled);
  const disabled = (metricSettings ?? []).filter((m: any) => !m.enabled);
  if (enabled.length > 0) L.push(`Enabled: ${enabled.map((m: any) => m.metric).join(", ")}`);
  if (disabled.length > 0) L.push(`Disabled: ${disabled.map((m: any) => m.metric).join(", ")}`);
  L.push("");

  // ── Triggers with linked counts ──
  L.push("=== TRIGGERS — linked to migraines ===");
  for (const pool of triggerPool) {
    const key = pool.label.toLowerCase();
    const counts = triggerCounts[key];
    const sev = (pool.prediction_value ?? "NONE").toUpperCase();
    const fav = trigFavIds.has(pool.id);
    const auto = pool.metric_table != null;

    if (counts) {
      L.push(`- "${pool.label}": linked to ${counts.linked} of ${mc} migraines (logged ${counts.total}x). Currently: ${sev}. Favorite: ${fav}. Auto: ${auto}.`);
    } else if (sev !== "NONE") {
      L.push(`- "${pool.label}": NEVER LOGGED but rated ${sev}. Favorite: ${fav}. Auto: ${auto}.`);
    }
  }
  for (const [key, counts] of Object.entries(triggerCounts)) {
    const inPool = triggerPool.find((p: any) => p.label.toLowerCase() === key);
    if (!inPool || (inPool.prediction_value ?? "NONE").toUpperCase() === "NONE") {
      L.push(`- "${key}": linked to ${(counts as any).linked} of ${mc} migraines but currently NONE — worth reviewing!`);
    }
  }
  L.push("");

  // ── Prodromes ──
  L.push("=== PRODROMES — linked to migraines ===");
  for (const pool of prodromePool) {
    const key = pool.label.toLowerCase();
    const counts = prodromeCounts[key];
    const sev = (pool.prediction_value ?? "NONE").toUpperCase();
    const fav = prodFavIds.has(pool.id);
    if (counts) {
      L.push(`- "${pool.label}": linked to ${counts.linked} of ${mc} migraines (logged ${counts.total}x). Currently: ${sev}. Favorite: ${fav}.`);
    } else if (sev !== "NONE") {
      L.push(`- "${pool.label}": NEVER LOGGED but rated ${sev}. Favorite: ${fav}.`);
    }
  }
  L.push("");

  // ── Medicines ──
  L.push("=== MEDICINES ===");
  for (const [label, counts] of Object.entries(medicineCounts)) {
    L.push(`- "${label}": used ${(counts as any).total}x, linked to ${(counts as any).linked} migraines. Favorited: ${medFavIds.has(label)}.`);
  }
  L.push("");

  // ── Reliefs ──
  L.push("=== RELIEFS ===");
  for (const [label, counts] of Object.entries(reliefCounts)) {
    L.push(`- "${label}": used ${(counts as any).total}x, linked to ${(counts as any).linked} migraines. Favorited: ${reliefFavIds.has(label)}.`);
  }
  L.push("");

  // ── Symptoms ──
  L.push("=== SYMPTOMS ===");
  for (const [label, counts] of Object.entries(symptomCounts)) {
    L.push(`- "${label}": reported ${(counts as any).total}x across ${(counts as any).linked} migraines. Favorited: ${symptomFavIds.has(label)}.`);
  }
  L.push("");

  // ── Activities ──
  L.push("=== ACTIVITIES ===");
  for (const [label, counts] of Object.entries(activityCounts)) {
    L.push(`- "${label}": logged ${(counts as any).total}x, linked to ${(counts as any).linked} migraines. Favorited: ${actFavIds.has(label)}.`);
  }
  L.push("");

  // ── Missed activities ──
  L.push("=== MISSED ACTIVITIES ===");
  for (const [label, counts] of Object.entries(missedCounts)) {
    L.push(`- "${label}": logged ${(counts as any).total}x, linked to ${(counts as any).linked} migraines. Favorited: ${missedFavIds.has(label)}.`);
  }
  L.push("");

  // ── Locations ──
  if (Object.keys(locationCounts).length > 0) {
    L.push("=== LOCATIONS ===");
    for (const [label, counts] of Object.entries(locationCounts)) {
      L.push(`- "${label}": linked to ${(counts as any).linked} migraines.`);
    }
    L.push("");
  }

  // ── Available labels ──
  L.push("=== AVAILABLE TRIGGER LABELS (use ONLY these exact strings) ===");
  const trigByCategory: Record<string, string[]> = {};
  for (const p of triggerPool) {
    const cat = p.category ?? "Other";
    if (!trigByCategory[cat]) trigByCategory[cat] = [];
    trigByCategory[cat].push(p.label);
  }
  for (const [cat, labels] of Object.entries(trigByCategory)) {
    L.push(`  [${cat}] ${labels.join(", ")}`);
  }
  L.push("");

  L.push("=== AVAILABLE PRODROME LABELS ===");
  const prodByCategory: Record<string, string[]> = {};
  for (const p of prodromePool) {
    const cat = p.category ?? "Other";
    if (!prodByCategory[cat]) prodByCategory[cat] = [];
    prodByCategory[cat].push(p.label);
  }
  for (const [cat, labels] of Object.entries(prodByCategory)) {
    L.push(`  [${cat}] ${labels.join(", ")}`);
  }

  return L.join("\n");
}


// ══════════════════════════════════════════════════════════════════════
// CALL 2 — The Statistician (recalibration version)
// ══════════════════════════════════════════════════════════════════════

const CALL2_SYSTEM_PROMPT = `You are a biostatistician recalibrating a migraine risk gauge based on its ACTUAL PERFORMANCE.

=== HOW THE SCORING ENGINE WORKS ===

Every day, the app calculates a RISK SCORE by looking at all trigger events from the last 7 days.

For each event, the score contribution = decay_weight[severity][days_ago].
The daily score = SUM of all event contributions.

The gauge zones are:
  score >= HIGH threshold → RED (genuine warning)
  score >= MILD threshold → YELLOW (building risk)
  score >= LOW threshold  → AMBER (worth noticing)
  score < LOW threshold   → GREEN (normal)

=== TWO TYPES OF TRIGGERS ===

AUTO-DETECTED triggers fire automatically when connected data (wearables, weather, nutrition) crosses a threshold. These can fire EVERY DAY. Examples: sleep duration, weather pressure, HRV, screen time.

MANUAL triggers are logged by the user when they occur. These fire occasionally. Examples: stress, alcohol, skipped meals.

This distinction is CRITICAL:
- A user with many auto-detected triggers has a higher BASELINE score every day
- Thresholds must sit ABOVE that baseline
- Manual triggers stacking ON TOP of the auto baseline is what should push into warning zones

You will receive:
1. The patient profile + updated clinical assessment from Call 1
2. Current trigger breakdown (auto vs manual, severity counts)
3. ACTUAL GAUGE PERFORMANCE: true positives, false positives, false negatives, true negatives
4. Current thresholds and decay curves

YOUR TASK:

1. DIAGNOSE gauge problems:
   - Too many false alarms (warned + no migraine) → thresholds too low
   - Missed migraines (no warning + migraine happened) → thresholds too high
   - Stuck in one zone → thresholds don't match baseline

2. ADJUST THRESHOLDS incrementally:
   - Nudge by ~20-30% per recalibration, don't make dramatic jumps
   - Someone who gets migraines "1-3 per month" should NOT be in red every day
   - Most days should be GREEN. Red should be rare and meaningful.

3. ADJUST DECAY CURVES if needed:
   - Steeper curves if triggers hit fast then fade
   - Flatter curves if cumulative buildup matters more
   - Incremental changes only

4. WRITE calibration_notes (2-3 paragraphs, warm, patient-facing, "you/your"):
   - How the gauge performed
   - What's changing and why
   - What to expect going forward

5. WRITE summary (2-3 sentences)

Respond with ONLY valid JSON (no markdown fences):
{
  "gauge_thresholds": {"low": N, "mild": N, "high": N, "reasoning": "brief technical reasoning"},
  "decay_weights": [
    {"severity": "HIGH", "day0": N, "day1": N, "day2": N, "day3": N, "day4": N, "day5": N, "day6": N, "reasoning": "..."},
    {"severity": "MILD", "day0": N, "day1": N, "day2": N, "day3": N, "day4": N, "day5": N, "day6": N, "reasoning": "..."},
    {"severity": "LOW", "day0": N, "day1": N, "day2": N, "day3": N, "day4": N, "day5": N, "day6": N, "reasoning": "..."}
  ],
  "calibration_notes": "Patient-facing 2-3 paragraph explanation...",
  "summary": "2-3 sentence summary"
}`;

function buildCall2Message(
  profile: any, clinicalAssessment: string,
  triggerPool: any[], triggerCounts: Record<string, any>, mc: number,
  tp: number, fp: number, fn: number, tn: number,
  totalDays: number, greenDays: number, amberDays: number, yellowDays: number, redDays: number,
  thresholdRows: any[], decayRows: any[],
): string {
  const L: string[] = [];

  L.push("=== PATIENT SUMMARY ===");
  L.push(`${profile.gender ?? "Unknown"}, ${profile.age_range ?? "unknown age"}`);
  L.push(`Migraines: ${profile.frequency ?? "unknown"} (actual: ${mc} logged)`);
  L.push("");

  L.push("=== UPDATED CLINICAL ASSESSMENT (from Call 1) ===");
  L.push(clinicalAssessment || "Not available");
  L.push("");

  // Auto vs manual breakdown
  let autoH = 0, autoM = 0, autoL = 0, manH = 0, manM = 0, manL = 0;
  for (const pool of triggerPool) {
    const sev = (pool.prediction_value ?? "NONE").toUpperCase();
    if (sev === "NONE") continue;
    const isAuto = pool.metric_table != null;
    if (isAuto) { if (sev === "HIGH") autoH++; else if (sev === "MILD") autoM++; else autoL++; }
    else { if (sev === "HIGH") manH++; else if (sev === "MILD") manM++; else manL++; }
  }

  L.push("=== TRIGGER BREAKDOWN ===");
  L.push(`Auto-detected: ${autoH} HIGH, ${autoM} MILD, ${autoL} LOW`);
  L.push(`Manual: ${manH} HIGH, ${manM} MILD, ${manL} LOW`);
  L.push(`Total active: ${autoH + autoM + autoL + manH + manM + manL}`);
  L.push("");

  L.push("=== ACTUAL GAUGE PERFORMANCE ===");
  L.push(`Analysis period: ${totalDays} days, ${mc} migraines`);
  L.push(`Correctly warned before migraine (true positive): ${tp}`);
  L.push(`False alarms — warned but no migraine (false positive): ${fp}`);
  L.push(`Missed — no warning but migraine happened (false negative): ${fn}`);
  L.push(`Correctly calm (true negative): ${tn}`);
  L.push("");
  L.push(`Zone distribution: GREEN ${greenDays} days, AMBER ${amberDays}, YELLOW ${yellowDays}, RED ${redDays}`);
  if (totalDays > 0) {
    const sens = tp + fn > 0 ? ((tp / (tp + fn)) * 100).toFixed(0) : "N/A";
    const spec = tn + fp > 0 ? ((tn / (tn + fp)) * 100).toFixed(0) : "N/A";
    L.push(`Sensitivity (% migraines caught): ${sens}%`);
    L.push(`Specificity (% false alarms avoided): ${spec}%`);
  }
  L.push("");

  L.push("=== CURRENT THRESHOLDS ===");
  for (const r of thresholdRows) {
    L.push(`${(r.zone ?? "").toUpperCase()}: ${r.min_value}`);
  }
  if (thresholdRows.length === 0) L.push("(no thresholds set)");
  L.push("");

  L.push("=== CURRENT DECAY CURVES ===");
  for (const d of decayRows) {
    L.push(`${d.severity}: day0=${d.day_0}, day1=${d.day_1}, day2=${d.day_2}, day3=${d.day_3}, day4=${d.day_4}, day5=${d.day_5}, day6=${d.day_6}`);
  }
  if (decayRows.length === 0) L.push("(using defaults)");

  return L.join("\n");
}


// ══════════════════════════════════════════════════════════════════════
// Helpers
// ══════════════════════════════════════════════════════════════════════

async function callOpenAI(systemPrompt: string, userMessage: string): Promise<any> {
  const res = await fetch("https://api.openai.com/v1/chat/completions", {
    method: "POST",
    headers: { "Content-Type": "application/json", "Authorization": `Bearer ${OPENAI_API_KEY}` },
    body: JSON.stringify({
      model: "gpt-4o", temperature: 0.3, max_tokens: 4000,
      messages: [{ role: "system", content: systemPrompt }, { role: "user", content: userMessage }],
    }),
  });
  const data = await res.json();
  const text = data.choices?.[0]?.message?.content ?? "{}";
  return JSON.parse(text.replace(/```json/g, "").replace(/```/g, "").trim());
}

function json(body: any, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}