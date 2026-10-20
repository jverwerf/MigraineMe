// supabase/functions/goal-suggestions/index.ts
//
// Weekly goal review + goal suggestions (Jordy 2026-10-20).
//
// Runs every Monday right after the weekly recalibration (cron
// 'weekly-goal-suggestions', one request per user from
// goal_suggestion_targets(): premium, Goals not switched off, something to
// review). One AI call per user, acting as the whole care team at once
// (neurologist, physiotherapist, dietitian, sleep specialist, sports
// physiologist), fed with everything the app already knows:
//
//   * the goals and 28 days of progress per goal (days hit, by week)
//   * the insights engine: triggers, interactions, metric thresholds, and the
//     protective factors ("what makes you stronger")
//   * attacks per week and risk per week over 8 weeks
//   * the recalibration profile summary and the last week of daily insights
//   * the goal catalogue plus a 28-day snapshot of every metric the user has
//
// It writes ONE goal_reports row for the week (the review + adherence per
// goal) and up to three goal_suggestions rows the user can Add or Dismiss.
// Exercise films are NEVER suggested: a routine proposed without seeing the
// person (sore muscles, a bad neck) is the physio's call, not ours.
//
// POST {user_id} with the service key (cron), or no body on the user's own
// JWT (a "review now" tap). {force: true} re-runs a week that was already
// written; otherwise a second call in the same week is a no-op.

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { getLang, languageDirective, type Lang } from "../_shared/i18n.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const OPENAI_API_KEY = Deno.env.get("OPENAI_API_KEY")!;

const ALLOWED_KINDS = new Set(["hr_threshold", "mindfulness_minutes", "daily_count", "metric"]);
const MAX_SUGGESTIONS = 3;
const DISMISS_COOLDOWN_DAYS = 60;

type Goal = {
  id: string; practitioner_id: string | null; kind: string; title: string;
  metric_key: string | null; direction: string | null; target_value: number | null;
  threshold_bpm: number | null; target_minutes: number | null; target_count: number | null;
  times_per_week: number | null; status: string; created_at: string; practitioner_name: string | null;
};
type Progress = { day: string; value: number | null; achieved: boolean };
type CatalogueRow = {
  key: string; label: string; grp: string; unit: string; default_direction: string;
  default_target: number | null; scale: string;
};

serve(async (req: Request) => {
  try {
    const body = await req.json().catch(() => ({}));
    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_KEY, { auth: { persistSession: false } });

    // Who: cron passes user_id with the service key; a person arrives on her JWT.
    let userId: string | null = typeof body.user_id === "string" ? body.user_id : null;
    const automated = !!userId;
    if (!userId) {
      const token = req.headers.get("Authorization")?.replace("Bearer ", "") ?? "";
      const { data: { user } } = await supabase.auth.getUser(token);
      userId = user?.id ?? null;
    }
    if (!userId) return json({ ok: false, error: "unauthorized" }, 401);

    const lang: Lang = await getLang(supabase, userId);

    // ── Premium + Goals toggle (the cron pre-filters; a manual call must not bypass) ──
    const { data: premiumRow } = await supabase
      .from("premium_status").select("trial_end, rc_subscription_status").eq("user_id", userId).maybeSingle();
    const trialActive = premiumRow?.trial_end && new Date(premiumRow.trial_end) > new Date();
    const subActive = premiumRow?.rc_subscription_status === "active" || premiumRow?.rc_subscription_status === "grace_period";
    if (!trialActive && !subActive) return json({ ok: false, error: "premium_required" }, 403);

    const { data: goalsToggle } = await supabase
      .from("metric_settings").select("enabled").eq("user_id", userId).eq("metric", "goals").maybeSingle();
    if (goalsToggle && goalsToggle.enabled === false) return json({ ok: true, status: "goals_off" });

    // ── Week stamp: Monday of today (UTC). One report per week unless forced. ──
    const today = new Date();
    const weekStart = mondayOf(today);
    if (!body.force) {
      const { data: existing } = await supabase
        .from("goal_reports").select("id").eq("user_id", userId).eq("week_start", weekStart).maybeSingle();
      if (existing) return json({ ok: true, status: "already_done", week_start: weekStart });
    }

    // ── Goals + 28 days of progress each ──
    const { data: goalRows } = await supabase
      .from("practitioner_goals")
      .select("id, practitioner_id, kind, title, metric_key, direction, target_value, threshold_bpm, target_minutes, target_count, times_per_week, status, created_at, practitioner_name")
      .eq("user_id", userId)
      .in("status", ["active", "paused"])
      .order("created_at");
    const goals = (goalRows ?? []) as Goal[];
    const activeGoals = goals.filter((g) => g.status === "active");

    const from28 = isoDate(addDays(today, -27));
    const todayIso = isoDate(today);
    const progressByGoal: Record<string, Progress[]> = {};
    await Promise.all(activeGoals.map(async (g) => {
      const { data } = await supabase.rpc("goal_daily_progress", { p_goal_id: g.id, p_from: from28, p_to: todayIso });
      progressByGoal[g.id] = (data ?? []) as Progress[];
    }));

    const goalSummaries = activeGoals.map((g) => {
      const rows = progressByGoal[g.id] ?? [];
      const started = new Date(g.created_at) > new Date(from28) ? isoDate(new Date(g.created_at)) : from28;
      const inScope = rows.filter((r) => r.day >= started);
      const weeks = [0, 1, 2, 3].map((w) => {
        const slice = inScope.filter((r) => weekIndex(r.day, today) === w);
        return { days: slice.length, hit: slice.filter((r) => r.achieved).length };
      }).reverse(); // oldest first
      return {
        goal_id: g.id,
        title: g.title,
        kind: g.kind,
        set_by: g.practitioner_id ? (g.practitioner_name || "practitioner") : "self",
        target: describeTarget(g),
        days_total: inScope.length,
        days_hit: inScope.filter((r) => r.achieved).length,
        hit_this_week: weeks[3]?.hit ?? 0,
        hit_last_week: weeks[2]?.hit ?? 0,
        by_week_oldest_first: weeks,
        goal_age_days: Math.max(0, Math.round((today.getTime() - new Date(g.created_at).getTime()) / 86400000)),
      };
    });

    // ── Attacks and risk, 8 weeks by week ──
    const from56 = isoDate(addDays(today, -55));
    const { data: migraineRows } = await supabase
      .from("migraines").select("start_at, severity").eq("user_id", userId).gte("start_at", from56).not("start_at", "is", null);
    const attacksByWeek = bucketWeeks(8, today);
    for (const m of migraineRows ?? []) {
      const w = weekIndex(String(m.start_at).slice(0, 10), today);
      if (w >= 0 && w < 8) { attacksByWeek[w].count += 1; attacksByWeek[w].severity_sum += Number(m.severity ?? 0); }
    }
    const attacks = attacksByWeek.map((b) => ({
      week: b.label, attacks: b.count,
      mean_severity: b.count ? round1(b.severity_sum / b.count) : null,
    })).reverse();

    const { data: riskRows } = await supabase
      .from("risk_score_daily").select("date, percent").eq("user_id", userId).gte("date", from56);
    const riskByWeek = bucketWeeks(8, today);
    for (const r of riskRows ?? []) {
      const w = weekIndex(String(r.date), today);
      if (w >= 0 && w < 8 && r.percent != null) { riskByWeek[w].count += 1; riskByWeek[w].severity_sum += Number(r.percent); }
    }
    const risk = riskByWeek.map((b) => ({
      week: b.label, mean_risk_pct: b.count ? Math.round(b.severity_sum / b.count) : null,
    })).reverse();

    // ── Insights engine: triggers, metric thresholds, protective factors ──
    const { data: statRows } = await supabase
      .from("correlation_stats")
      .select("factor_type, factor_name, factor_b, lift_ratio, p_value, sample_size, pct_migraine_windows, pct_control_windows, suggested_threshold, current_threshold, threshold_direction, metric_table, best_lag_days")
      .eq("user_id", userId);
    const stats = statRows ?? [];
    const sig = (s: any) => s.p_value != null && s.p_value < 0.1 && s.sample_size >= 3;
    const triggers = stats
      .filter((s) => ["trigger", "interaction", "location_trigger"].includes(s.factor_type) && sig(s) && s.lift_ratio > 1.3)
      .sort((a, b) => b.lift_ratio - a.lift_ratio).slice(0, 8)
      .map((s) => ({
        factor: s.factor_b ? `${s.factor_name} + ${s.factor_b}` : s.factor_name,
        lift: round1(s.lift_ratio), lag_days: s.best_lag_days, n: s.sample_size,
      }));
    const metricFindings = stats
      .filter((s) => s.factor_type === "metric" && sig(s))
      .sort((a, b) => a.p_value - b.p_value).slice(0, 8)
      .map((s) => ({
        metric: s.factor_name, lift: round1(s.lift_ratio),
        risky_when: s.threshold_direction, threshold: s.suggested_threshold ?? s.current_threshold,
        lag_days: s.best_lag_days, n: s.sample_size,
      }));
    const protective = stats
      .filter((s) => (s.factor_type === "well_done" || s.factor_type === "well_done_chain") && s.p_value != null && s.p_value < 0.15)
      .sort((a, b) => b.lift_ratio - a.lift_ratio).slice(0, 8)
      .map((s) => ({
        factor: s.factor_b ? `${s.factor_name} -> ${s.factor_b}` : s.factor_name,
        pct_on_attack_days: s.pct_migraine_windows, pct_on_good_days: s.pct_control_windows, n: s.sample_size,
      }));

    // ── Profile + last week of daily insights ──
    const { data: profile } = await supabase
      .from("ai_setup_profiles").select("summary, clinical_assessment, frequency, trajectory").eq("user_id", userId).maybeSingle();
    const { data: insightRows } = await supabase
      .from("daily_insights").select("date, insight, positives").eq("user_id", userId)
      .order("date", { ascending: false }).limit(7);
    const recentInsights = (insightRows ?? []).map((r) => ({
      date: r.date, insight: clip(r.insight, 300),
      positives: Array.isArray(r.positives) ? r.positives.map((p: any) => clip(p?.text ?? String(p), 200)).slice(0, 2) : [],
    }));

    // ── Catalogue + what the user actually has data for ──
    const { data: catRows } = await supabase
      .from("goal_metric_catalog").select("key, label, grp, unit, default_direction, default_target, scale").order("sort");
    const catalogue = (catRows ?? []) as CatalogueRow[];
    const { data: snapRows } = await supabase.rpc("goal_metric_snapshot", { p_user: userId, p_days: 28 });
    const snapshot: Record<string, { days: number; mean: number; last: number }> = {};
    for (const r of snapRows ?? []) snapshot[r.key] = { days: r.days_with_data, mean: Number(r.mean_value), last: Number(r.last_value) };
    const catalogueForModel = catalogue
      .filter((c) => snapshot[c.key]) // only metrics with data: a goal on a metric nobody logs cannot be scored
      .map((c) => ({
        key: c.key, label: c.label, group: c.grp, unit: c.unit, scale: c.scale,
        usual_direction: c.default_direction, usual_target: c.default_target,
        last_28_days: snapshot[c.key],
      }));

    // ── What not to propose again ──
    const sinceDismiss = isoDate(addDays(today, -DISMISS_COOLDOWN_DAYS));
    const { data: pastRows } = await supabase
      .from("goal_suggestions").select("id, kind, metric_key, title, status, created_at")
      .eq("user_id", userId).gte("created_at", sinceDismiss);
    const past = pastRows ?? [];
    const blockedMetricKeys = new Set<string>();
    const blockedKinds = new Set<string>();
    for (const g of goals) { if (g.metric_key) blockedMetricKeys.add(g.metric_key); if (g.kind !== "metric" && g.kind !== "daily_count") blockedKinds.add(g.kind); }
    for (const p of past) if (p.status === "dismissed") { if (p.metric_key) blockedMetricKeys.add(p.metric_key); else if (p.kind !== "daily_count") blockedKinds.add(p.kind); }
    const dismissedTitles = past.filter((p) => p.status === "dismissed").map((p) => p.title);

    const hasHrSource = !!snapshot["high_hr_zones"] || !!snapshot["resting_hr"];

    // ── The one AI call ──
    const systemPrompt = buildSystemPrompt(lang);
    const userMessage = JSON.stringify({
      today: todayIso,
      week_start: weekStart,
      profile: {
        summary: clip(profile?.summary, 1200),
        clinical_assessment: clip(profile?.clinical_assessment, 800),
        frequency: profile?.frequency ?? null, trajectory: profile?.trajectory ?? null,
      },
      goals: goalSummaries,
      paused_goals: goals.filter((g) => g.status === "paused").map((g) => ({ title: g.title, kind: g.kind })),
      attacks_by_week_oldest_first: attacks,
      risk_by_week_oldest_first: risk,
      insights: { triggers, metric_thresholds: metricFindings, protective_factors: protective },
      recent_daily_insights: recentInsights,
      catalogue_with_data: catalogueForModel,
      heart_rate_data_available: hasHrSource,
      do_not_propose: {
        metric_keys: [...blockedMetricKeys],
        kinds: [...blockedKinds],
        dismissed_titles: dismissedTitles,
      },
    }, null, 0);

    const out = await callOpenAI(systemPrompt, userMessage);

    // ── Validate what came back ──
    const report = typeof out.report === "string" ? out.report.trim() : "";
    if (!report) return json({ ok: false, error: "no_report" }, 502);

    const notesById: Record<string, { status: string; note: string }> = {};
    for (const n of Array.isArray(out.goal_notes) ? out.goal_notes : []) {
      if (typeof n?.goal_id === "string" && ["on_track", "slipping", "not_started"].includes(n.status)) {
        notesById[n.goal_id] = { status: n.status, note: clip(n.note, 240) ?? "" };
      }
    }
    const adherence = goalSummaries.map((g) => ({
      goal_id: g.goal_id, title: g.title, kind: g.kind, set_by: g.set_by,
      days_hit: g.days_hit, days_total: g.days_total,
      hit_this_week: g.hit_this_week, hit_last_week: g.hit_last_week,
      status: notesById[g.goal_id]?.status ?? fallbackStatus(g),
      note: notesById[g.goal_id]?.note ?? "",
    }));

    const catByKey = new Map(catalogue.map((c) => [c.key, c]));
    // New goal ideas are reviewed inside the weekly recalibration (Jordy
    // 10-20), so they are only kept when recalibrate called us (quiet). The
    // standalone run (people with goals but no recalibration) writes the
    // review only.
    const suggestions: any[] = [];
    for (const s of body.quiet && Array.isArray(out.suggestions) ? out.suggestions : []) {
      if (suggestions.length >= MAX_SUGGESTIONS) break;
      const kind = String(s?.kind ?? "");
      if (!ALLOWED_KINDS.has(kind)) continue;             // exercise_count and anything odd dies here
      const reason = clip(s?.reason, 280);
      if (!reason) continue;
      const row: any = { user_id: userId, week_start: weekStart, kind, reason, status: "proposed" };
      if (kind === "metric") {
        const key = String(s.metric_key ?? "");
        const cat = catByKey.get(key);
        if (!cat || blockedMetricKeys.has(key) || !snapshot[key]) continue;
        const direction = ["gte", "lte", "consistent"].includes(s.direction) ? s.direction : cat.default_direction;
        const target = direction === "consistent" ? null : num(s.target_value) ?? cat.default_target;
        if (direction !== "consistent" && target == null) continue;
        row.title = cat.label; row.metric_key = key; row.direction = direction; row.target_value = target;
        blockedMetricKeys.add(key);
      } else if (kind === "hr_threshold") {
        if (blockedKinds.has(kind) || !hasHrSource) continue;
        row.title = "Heart rate training";
        row.threshold_bpm = clampInt(num(s.threshold_bpm) ?? 130, 100, 190);
        row.target_minutes = clampInt(num(s.target_minutes) ?? 20, 5, 120);
        row.times_per_week = clampInt(num(s.times_per_week) ?? 3, 1, 7);
        blockedKinds.add(kind);
      } else if (kind === "mindfulness_minutes") {
        if (blockedKinds.has(kind)) continue;
        row.title = "Meditation";
        row.target_minutes = clampInt(num(s.target_minutes) ?? 5, 1, 60);
        blockedKinds.add(kind);
      } else if (kind === "daily_count") {
        const title = clip(s.title, 60);
        if (!title || dismissedTitles.some((t) => t.toLowerCase() === title.toLowerCase())) continue;
        if (goals.some((g) => g.title.toLowerCase() === title.toLowerCase())) continue;
        row.title = title;
        row.target_count = clampInt(num(s.target_count) ?? 1, 1, 20);
      }
      suggestions.push(row);
    }

    // ── Write: the report, retire last week's open suggestions, add this week's ──
    const evidence = {
      attacks_by_week: attacks, risk_by_week: risk,
      top_triggers: triggers.slice(0, 5), protective: protective.slice(0, 5), metric_thresholds: metricFindings.slice(0, 5),
    };
    const { error: repErr } = await supabase.from("goal_reports").upsert({
      user_id: userId, week_start: weekStart, lang, report, adherence, evidence,
    }, { onConflict: "user_id,week_start" });
    if (repErr) throw new Error(`goal_reports upsert: ${repErr.message}`);

    const { error: expErr } = await supabase.from("goal_suggestions")
      .update({ status: "expired", reviewed_at: new Date().toISOString() })
      .eq("user_id", userId).eq("status", "proposed").lt("week_start", weekStart);
    if (expErr) console.warn("[goal-suggestions] expire failed", expErr.message);
    if (body.force) {
      // A forced re-run replaces this week's open suggestions rather than stacking them.
      await supabase.from("goal_suggestions").delete().eq("user_id", userId).eq("status", "proposed").eq("week_start", weekStart);
    }
    if (suggestions.length) {
      const { error: insErr } = await supabase.from("goal_suggestions").insert(suggestions);
      if (insErr) throw new Error(`goal_suggestions insert: ${insErr.message}`);
    }

    // ── Push (cron runs only; a person tapping "review now" is already looking) ──
    let pushed = false;
    // quiet: called from recalibrate, whose push covers both.
    if (automated && !body.quiet) {
      const { data: optOut } = await supabase
        .from("notification_settings").select("enabled").eq("user_id", userId).eq("type", "goal_suggestions").maybeSingle();
      if (!optOut || optOut.enabled !== false) {
        try {
          await supabase.functions.invoke("send-fcm-push", {
            body: {
              type: "goal_suggestions",
              user_ids: [userId],
              notification_key: suggestions.length
                ? { title: "Your weekly goal review is ready", body: "See how your goals went, plus {0} new goal ideas from your data.", body_args: [String(suggestions.length)] }
                : { title: "Your weekly goal review is ready", body: "See how your goals went this week." },
            },
          });
          pushed = true;
        } catch (_) { /* non-fatal */ }
      }
    }

    return json({ ok: true, status: "ok", week_start: weekStart, suggestions: suggestions.length, goals_reviewed: adherence.length, pushed });
  } catch (err) {
    console.error("[goal-suggestions] failed", err);
    return json({ ok: false, error: String((err as Error)?.message ?? err) }, 500);
  }
});

// ── Prompt ──────────────────────────────────────────────────────────────────
function buildSystemPrompt(lang: Lang): string {
  return `You are one person's migraine care team in a single voice: a neurologist, a physiotherapist, a dietitian, a sleep specialist and a sports physiologist, reviewing four weeks of this person's own tracking data every Monday.

You receive, as JSON: their profile; every active goal with how many days it was met (by week, oldest first); attacks and average risk per week over eight weeks (oldest first); what their own data has shown to be triggers, risky metric levels and protective factors; the last week of daily insights; the goal catalogue limited to metrics they actually have data for, with their 28-day levels; and a list of things not to propose.

Write three things.

1. "report": a short weekly review, 3 to 5 sentences, speaking to the person as "you". Cover, in this order: (a) whether they did their goals enough this week, naming the goals; (b) whether it is working, by putting the weeks they kept a goal next to attacks and risk in those weeks, in plain numbers ("the two weeks you hit the sleep goal five days or more had one attack each, the other weeks had three"); say honestly when four weeks is too little to tell; (c) one concrete thing for next week. No greetings, no "keep it up" filler, no diagnosis, no medication advice.

2. "goal_notes": one entry per active goal: {"goal_id", "status": "on_track" | "slipping" | "not_started", "note"}. on_track = met on most days of the last two weeks; slipping = met before but fewer days this week than last; not_started = never or almost never met. The note is one sentence, specific to the numbers, and may suggest a smaller target when a goal is not started.

3. "suggestions": zero to three NEW goals, pulled from this person's own evidence, as a JSON array. Each must be one of:
   - {"kind":"metric","metric_key":<catalogue key>,"direction":"gte"|"lte"|"consistent","target_value":<number>,"reason":...}
   - {"kind":"hr_threshold","threshold_bpm":<number>,"target_minutes":<number>,"times_per_week":<number>,"reason":...} only when heart_rate_data_available is true
   - {"kind":"mindfulness_minutes","target_minutes":<number>,"reason":...}
   - {"kind":"daily_count","title":<short title in the person's language>,"target_count":<number>,"reason":...} for a habit that is not a catalogue metric (a regular lunch, a caffeine cut-off, a screen-free hour)
   Where a suggestion comes from: a protective factor becomes a goal to keep it up; a trigger or a risky metric level becomes a limit goal; a metric that is below its usual target and matters to their attacks becomes a target goal. The reason is one or two sentences in the person's language, naming the number in their data that motivates it. Targets must be realistic for THIS person: start from their 28-day level and move it, not from a textbook. Never propose anything in do_not_propose. Never propose exercise routines, stretches, neck or posture exercises or any physical exercise programme: that needs a physiotherapist who has seen the person. Prefer an empty array over a weak suggestion.

Return ONLY a JSON object: {"report": string, "goal_notes": [...], "suggestions": [...]}.
${languageDirective(lang)}${INFORMAL[lang] ? ` Address the person as "${INFORMAL[lang]}", never formally.` : ""}`;
}

// The apps speak informally in every language; the model drifted to "votre".
const INFORMAL: Partial<Record<Lang, string>> = { de: "du", es: "tú", nl: "je/jij", fr: "tu", it: "tu", pt: "tu" };

// ── Helpers ─────────────────────────────────────────────────────────────────
function describeTarget(g: Goal): string {
  switch (g.kind) {
    case "hr_threshold": return `${g.target_minutes} min above ${g.threshold_bpm} bpm, ${g.times_per_week}x per week`;
    case "mindfulness_minutes": return `${g.target_minutes} min per day`;
    case "exercise_count":
    case "daily_count": return `${g.target_count}x per day`;
    case "metric": return g.direction === "consistent" ? `${g.metric_key}: within usual range` : `${g.metric_key} ${g.direction === "gte" ? ">=" : "<="} ${g.target_value}`;
    default: return "";
  }
}

function fallbackStatus(g: { days_total: number; days_hit: number; hit_this_week: number; hit_last_week: number }): string {
  if (g.days_total === 0 || g.days_hit === 0) return "not_started";
  if (g.hit_this_week < g.hit_last_week && g.hit_this_week < 4) return "slipping";
  return "on_track";
}

function mondayOf(d: Date): string {
  const u = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()));
  const dow = (u.getUTCDay() + 6) % 7; // Monday = 0
  u.setUTCDate(u.getUTCDate() - dow);
  return isoDate(u);
}
function isoDate(d: Date): string { return d.toISOString().slice(0, 10); }
function addDays(d: Date, n: number): Date { const x = new Date(d); x.setUTCDate(x.getUTCDate() + n); return x; }
/** 0 = the 7 days ending today, 1 = the 7 before that, … */
function weekIndex(day: string, today: Date): number {
  const diff = Math.floor((Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate()) - Date.parse(day)) / 86400000);
  return Math.floor(diff / 7);
}
function bucketWeeks(n: number, today: Date) {
  return Array.from({ length: n }, (_, i) => ({
    label: `${isoDate(addDays(today, -(i * 7 + 6)))}..${isoDate(addDays(today, -(i * 7)))}`,
    count: 0, severity_sum: 0,
  }));
}
function round1(x: number): number { return Math.round(x * 10) / 10; }
function clip(s: unknown, n: number): string | null {
  if (typeof s !== "string") return null;
  const t = s.trim();
  return t.length > n ? t.slice(0, n - 1) + "…" : t;
}
function num(x: unknown): number | null { const n = Number(x); return Number.isFinite(n) ? n : null; }
function clampInt(x: number, lo: number, hi: number): number { return Math.min(hi, Math.max(lo, Math.round(x))); }

async function callOpenAI(systemPrompt: string, userMessage: string): Promise<any> {
  const res = await fetch("https://api.openai.com/v1/chat/completions", {
    method: "POST",
    headers: { "Content-Type": "application/json", "Authorization": `Bearer ${OPENAI_API_KEY}` },
    body: JSON.stringify({
      model: "gpt-4o", temperature: 0.3, max_tokens: 1800,
      response_format: { type: "json_object" },
      messages: [{ role: "system", content: systemPrompt }, { role: "user", content: userMessage }],
    }),
  });
  const data = await res.json();
  const text = data.choices?.[0]?.message?.content ?? "{}";
  try {
    return JSON.parse(text.replace(/```json/g, "").replace(/```/g, "").trim());
  } catch (err) {
    console.error("callOpenAI: JSON parse failed", { err, snippet: String(text).slice(0, 500) });
    return {};
  }
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
