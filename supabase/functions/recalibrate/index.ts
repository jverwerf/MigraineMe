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
import { getLang, languageDirective, t, type Lang } from "../_shared/i18n.ts";
import { exposureRank } from "../_shared/exposureScale.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const OPENAI_API_KEY = Deno.env.get("OPENAI_API_KEY")!;

// The closed sets a proposal is allowed to carry out of the model. Both are
// enforced again in apply-recalibration, which is the last gate before the
// database; checking here as well keeps a proposal that could never be applied
// from ever being shown to the user.
const PREDICTION_VALUES = new Set(["NONE", "LOW", "MILD", "HIGH"]);
const FAVORITE_ITEM_TYPES = new Set([
  "medicine", "relief", "symptom", "activity", "missed_activity",
]);

/**
 * Model-written prose has no translation table, so it must be generated in the
 * user's display language in the first place. Structure must NOT move with the
 * language: build-report-html and all clients parse clinical_assessment by its
 * ENGLISH "=== Section ===" headers (parseNarrativeSections), and every
 * label/enum in the JSON is matched against English rows downstream. So the
 * directive pins those explicitly and frees only the prose.
 */
function modelLanguageDirective(lang: Lang): string {
  const base = languageDirective(lang);
  if (!base) return "";
  return base
    + ` The "=== Migraine Profile ===", "=== Trigger Profile ===", "=== Burden Profile ===",`
    + ` "=== Coping Profile ===", "=== Lifestyle ===" and "=== Trend ===" section headers are`
    + ` machine-parsed markers: reproduce them EXACTLY as written, in English, never translated.`
    + ` Every "label", "from", "to", "item_type", "type", "metric" and "severity" value must also`
    + ` stay exactly as provided in English, because it is matched against English records.`
    + ` Only the free prose — section bodies, "summary", "reasoning", "message",`
    + ` "calibration_notes" — is written in the user's language.`;
}

// Onboarding-tour seed rows are marked source='demo'. Written as an OR group so
// rows with a NULL source (real data on the nullable tables) are kept.
const NOT_DEMO_SOURCE = "source.is.null,source.neq.demo";

serve(async (req: Request) => {
  try {
    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_KEY);
    const body = await req.json().catch(() => ({}));

    // Auth
    // A body user_id means a server caller (the weekly cron); otherwise the
    // request carries the user's own JWT and they are sat in front of it.
    // Only the automated run is allowed to come back silent — someone who
    // TAPPED "re-assess" must always get an answer, even if the only thing
    // to show them is a refreshed narrative.
    const automated = !!body.user_id;

    let userId: string;
    if (body.user_id) {
      userId = body.user_id;
    } else {
      const token = (req.headers.get("Authorization") ?? "").replace("Bearer ", "");
      const { data: { user } } = await supabase.auth.getUser(token);
      if (!user) return json({ error: "Unauthorized" }, 401);
      userId = user.id;
    }

    // Display language: for the `message` fields below, and for the model
    // calls' prose (clinical_assessment bodies, reasoning, summary) via
    // modelLanguageDirective. Labels and the "=== Section ===" headers stay
    // English — they are matched/parsed against English data downstream.
    const lang: Lang = await getLang(supabase, userId);

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
          return json({ ok: false, error: "premium_required", message: t(lang, "This feature requires a premium subscription.") }, 403);
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
              message: t(lang, "Next recalibration available after %1$s", nextAt.toISOString().substring(0, 10)),
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
              message: t(lang, "Profile re-assessment is available once per day. Try again tomorrow."),
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

    // Recently rejected proposals — fed to every AI call so a weekly run
    // doesn't re-propose something the user said no to a week ago.
    const { data: rejectedRows } = await supabase
      .from("recalibration_proposals")
      .select("type, label, to_value")
      .eq("user_id", userId)
      .eq("status", "rejected")
      .gte("created_at", new Date(Date.now() - 60 * 86400000).toISOString());

    let rejectionBlock = "";
    if (rejectedRows && rejectedRows.length > 0) {
      const lines = rejectedRows.map((r: any) =>
        `- ${r.type}: "${r.label}"${r.to_value ? ` → ${r.to_value}` : ""}`);
      rejectionBlock =
        "\n\n=== RECENTLY REJECTED (last 60 days) ===\n" +
        "The patient declined these proposals. Do NOT propose them again:\n" +
        lines.join("\n");
    }

    // Onboarding-tour demo rows must never reach the AI: the assessment has to
    // describe the patient, not the tour fixture. Seeded rows carry
    // source='demo' or notes starting '[demo]'; children of a demo migraine are
    // excluded by their parent id even when they carry no marker of their own.
    const isDemoNote = (v: unknown) => String(v ?? "").startsWith("[demo]");

    // 2. All migraines
    const { data: migraineRows } = await supabase
      .from("migraines")
      .select("id, start_at, ended_at, severity, type, aura_locations, aura_duration_minutes, notes")
      .eq("user_id", userId)
      .not("start_at", "is", null)
      .order("start_at", { ascending: false });

    const demoMigraineIds = new Set(
      (migraineRows ?? []).filter((m: any) => isDemoNote(m.notes)).map((m: any) => m.id)
    );
    const migraines = (migraineRows ?? []).filter((m: any) => !demoMigraineIds.has(m.id));

    if (migraines.length < 5) {
      return json({ status: "insufficient_data", migraine_count: migraines.length });
    }

    const migraineIds = new Set(migraines.map((m: any) => m.id));

    const notDemo = <T extends Record<string, any>>(rows: T[] | null | undefined): T[] =>
      (rows ?? []).filter((r) =>
        r.source !== "demo" &&
        !isDemoNote(r.notes) &&
        !(r.migraine_id && demoMigraineIds.has(r.migraine_id))
      );

    // 3. All linked items
    const { data: triggerRows } = await supabase
      .from("triggers").select("type, migraine_id, source, notes").eq("user_id", userId);
    const triggers = notDemo(triggerRows);
    const { data: prodromeRows } = await supabase
      .from("prodromes").select("type, migraine_id, source, notes").eq("user_id", userId);
    const prodromes = notDemo(prodromeRows);
    const { data: medicineRows } = await supabase
      .from("medicines").select("name, migraine_id, source, notes").eq("user_id", userId);
    const medicines = notDemo(medicineRows);
    const { data: reliefRows } = await supabase
      .from("reliefs").select("type, migraine_id, source, notes").eq("user_id", userId);
    const reliefs = notDemo(reliefRows);
    // Symptoms are stored as comma-joined labels in migraines.type. Unpack into
    // per-label rows so countLinked produces the same shape the rest of this
    // function expects. "Migraine" is the sentinel written when no symptoms
    // were picked and is skipped.
    const symptoms: { type: string; migraine_id: string }[] = [];
    for (const m of (migraines ?? [])) {
      if (!m.type || m.type === "Migraine") continue;
      const labels = String(m.type).split(",").map((s: string) => s.trim()).filter(Boolean);
      for (const label of labels) {
        symptoms.push({ type: label, migraine_id: m.id });
      }
    }
    const { data: activityRows } = await supabase
      .from("activities").select("type, migraine_id, source, notes").eq("user_id", userId);
    const activities = notDemo(activityRows);
    const { data: missedActivityRows } = await supabase
      .from("missed_activities").select("type, migraine_id, source, notes").eq("user_id", userId);
    const missedActivities = notDemo(missedActivityRows);
    const { data: locationRows } = await supabase
      .from("locations").select("type, migraine_id, source, notes").eq("user_id", userId);
    const locations = notDemo(locationRows);

    // Aura detail: timestamped eye zones live in their own table; the array
    // columns on migraines cover attacks logged before the feature.
    const { data: auraZoneRowsRaw } = await supabase
      .from("migraine_aura_zones").select("migraine_id, zone").eq("user_id", userId);
    const auraZoneRows = notDemo(auraZoneRowsRaw);

    // Typed symptom rows — the symptoms table carries per-row severity and
    // start_at. Merged below with the legacy comma-joined labels that still
    // live in migraines.type (deduped per migraine+label).
    const { data: typedSymptomRowsRaw } = await supabase
      .from("symptoms").select("type, severity, migraine_id").eq("user_id", userId);
    const typedSymptomRows = notDemo(typedSymptomRowsRaw);

    // The symptoms table is the newer, richer source; add anything the
    // comma-joined labels missed, one row per migraine+label.
    const seenSymKeys = new Set(symptoms.map((s) => `${s.migraine_id}|${s.type.toLowerCase()}`));
    for (const r of (typedSymptomRows ?? [])) {
      if (!r.type || !r.migraine_id) continue;
      const k = `${r.migraine_id}|${String(r.type).toLowerCase()}`;
      if (seenSymKeys.has(k)) continue;
      seenSymKeys.add(k);
      symptoms.push({ type: String(r.type), migraine_id: r.migraine_id });
    }
    // Per-label severity distribution ("throbbing": severe 3x, mild 1x).
    const symSeverityByLabel: Record<string, Record<string, number>> = {};
    for (const r of (typedSymptomRows ?? [])) {
      if (!r.type || !r.severity) continue;
      const l = String(r.type).toLowerCase();
      const s = String(r.severity).toLowerCase();
      const bucket = (symSeverityByLabel[l] ??= {});
      bucket[s] = (bucket[s] ?? 0) + 1;
    }

    // 3b. Symptom category map (pain_character / accompanying / postdrome / etc.)
    const { data: symptomTemplates } = await supabase
      .from("symptom_templates").select("label, category");
    const symCategoryByLabel = new Map<string, string>();
    for (const t of symptomTemplates ?? []) {
      if (t.label) symCategoryByLabel.set(String(t.label).toLowerCase(), String(t.category ?? "other"));
    }

    // 3c. Lifestyle daily metrics — last 60 days (split into last30 vs prior30 for Trend)
    const today = new Date();
    const ymd = (d: Date) => d.toISOString().substring(0, 10);
    const last30Cutoff = new Date(today); last30Cutoff.setDate(today.getDate() - 30);
    const last60Cutoff = new Date(today); last60Cutoff.setDate(today.getDate() - 60);
    const last30CutoffIso = ymd(last30Cutoff);
    const last60CutoffIso = ymd(last60Cutoff);

    const [
      sleepScore, sleepDuration, stressIndex, recoveryScore,
      nutrition, hydration, steps, restingHr,
      screenTime, lateScreen, bedtime, hrvDaily, mindfulness,
    ] = await Promise.all([
      supabase.from("sleep_score_daily").select("date, value_pct").eq("user_id", userId).gte("date", last60CutoffIso).or(NOT_DEMO_SOURCE),
      supabase.from("sleep_duration_daily").select("date, value_hours").eq("user_id", userId).gte("date", last60CutoffIso).or(NOT_DEMO_SOURCE),
      // stress_index_daily has no `source` column, so demo rows cannot be
      // filtered here — the tour cleanup drops the table wholesale instead.
      supabase.from("stress_index_daily").select("date, value").eq("user_id", userId).gte("date", last60CutoffIso),
      supabase.from("recovery_score_daily").select("date, value_pct").eq("user_id", userId).gte("date", last60CutoffIso).or(NOT_DEMO_SOURCE),
      supabase.from("nutrition_daily").select("date, total_calories, total_protein_g, total_carbs_g, total_fat_g, total_fiber_g, total_sugar_g, total_sodium_mg, total_caffeine_mg, max_tyramine_exposure, max_alcohol_exposure, max_gluten_exposure, max_histamine_exposure").eq("user_id", userId).gte("date", last60CutoffIso).or(NOT_DEMO_SOURCE),
      supabase.from("hydration_daily").select("date, value_ml").eq("user_id", userId).gte("date", last60CutoffIso).or(NOT_DEMO_SOURCE),
      supabase.from("steps_daily").select("date, value_count").eq("user_id", userId).gte("date", last60CutoffIso).or(NOT_DEMO_SOURCE),
      supabase.from("resting_hr_daily").select("date, value_bpm").eq("user_id", userId).gte("date", last60CutoffIso).or(NOT_DEMO_SOURCE),
      supabase.from("screen_time_daily").select("date, total_hours").eq("user_id", userId).gte("date", last60CutoffIso).or(NOT_DEMO_SOURCE),
      supabase.from("screen_time_late_night").select("date, value_hours").eq("user_id", userId).gte("date", last60CutoffIso).or(NOT_DEMO_SOURCE),
      supabase.from("fell_asleep_time_daily").select("date, value_at").eq("user_id", userId).gte("date", last60CutoffIso).or(NOT_DEMO_SOURCE),
      supabase.from("hrv_daily").select("date, value_rmssd_ms").eq("user_id", userId).gte("date", last60CutoffIso).or(NOT_DEMO_SOURCE),
      supabase.from("mindfulness_daily").select("date, duration_minutes").eq("user_id", userId).gte("date", last60CutoffIso).or(NOT_DEMO_SOURCE),
    ]);

    // Correlation engine output + treatment history — new since the original
    // narrative prompt was written; both feed Call 1 only.
    const last90Cutoff = new Date(today); last90Cutoff.setDate(today.getDate() - 90);
    const [
      { data: corrStats }, { data: regimenRows }, { data: sideEffectLogRows },
      { data: timingStats }, { data: regimenNarrativeRows },
    ] = await Promise.all([
      supabase.from("correlation_stats")
        .select("factor_name, factor_type, factor_b, best_lag_days, lift_ratio, pct_migraine_windows, pct_control_windows, sample_size, p_value, lag_details, symptom_outcome")
        .eq("user_id", userId),
      supabase.from("treatment_regimens")
        .select("id, kind, name, amount, frequency, start_date, stop_date, drug_class, notes")
        .eq("user_id", userId)
        .order("start_date", { ascending: false })
        .limit(10),
      supabase.from("treatment_side_effect_logs")
        .select("log_date, selected_symptoms, regimen_id, source, notes")
        .eq("user_id", userId)
        .gte("log_date", ymd(last90Cutoff)),
      supabase.from("treatment_timing_stats")
        .select("treatment_name, cutoff_minutes, early_count, early_avg_peak, late_count, late_avg_peak")
        .eq("user_id", userId),
      supabase.from("treatment_narrative_cache")
        .select("regimen_id, narrative")
        .eq("user_id", userId),
    ]);

    // Demo treatments carry notes '[demo]…'; their side-effect diary and cached
    // narrative carry no marker of their own, so drop them by regimen id.
    const regimens = notDemo(regimenRows);
    const demoRegimenIds = new Set(
      (regimenRows ?? []).filter((r: any) => isDemoNote(r.notes)).map((r: any) => r.id)
    );
    const sideEffectLogs = notDemo(sideEffectLogRows)
      .filter((r: any) => !demoRegimenIds.has(r.regimen_id));
    const regimenNarratives = (regimenNarrativeRows ?? [])
      .filter((r: any) => !demoRegimenIds.has(r.regimen_id));

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

    // Favorites are stored by symptom_id (UUID) but the prompt below checks by
    // label, so resolve to labels via user_symptoms.
    const { data: symptomFavs } = await supabase
      .from("symptom_preferences")
      .select("symptom_id, user_symptoms(label)")
      .eq("user_id", userId);
    const symptomFavIds = new Set(
      (symptomFavs ?? [])
        .map((f: any) => f.user_symptoms?.label)
        .filter((l: any): l is string => typeof l === "string" && l.length > 0)
    );

    const { data: medFavs } = await supabase
      .from("medicine_preferences")
      .select("medicine_id, user_medicines(label)")
      .eq("user_id", userId);
    const medFavIds = new Set(
      (medFavs ?? [])
        .map((f: any) => f.user_medicines?.label)
        .filter((l: any): l is string => typeof l === "string" && l.length > 0)
    );

    const { data: reliefFavs } = await supabase
      .from("relief_preferences")
      .select("relief_id, user_reliefs(label)")
      .eq("user_id", userId);
    const reliefFavIds = new Set(
      (reliefFavs ?? [])
        .map((f: any) => f.user_reliefs?.label)
        .filter((l: any): l is string => typeof l === "string" && l.length > 0)
    );

    const { data: actFavs } = await supabase
      .from("activity_preferences")
      .select("activity_id, user_activities(label)")
      .eq("user_id", userId);
    const actFavIds = new Set(
      (actFavs ?? [])
        .map((f: any) => f.user_activities?.label)
        .filter((l: any): l is string => typeof l === "string" && l.length > 0)
    );

    const { data: missedFavs } = await supabase
      .from("missed_activity_preferences")
      .select("missed_activity_id, user_missed_activities(label)")
      .eq("user_id", userId);
    const missedFavIds = new Set(
      (missedFavs ?? [])
        .map((f: any) => f.user_missed_activities?.label)
        .filter((l: any): l is string => typeof l === "string" && l.length > 0)
    );

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

    // 7. Connected data sources. Tour-seeded rows carry preferred_source='demo'
    // and must not describe the patient's real setup to the AI.
    const { data: metricSettingRows } = await supabase
      .from("metric_settings")
      .select("metric, enabled, preferred_source")
      .eq("user_id", userId);
    const metricSettings = (metricSettingRows ?? [])
      .filter((s: any) => String(s.preferred_source ?? "").toLowerCase() !== "demo");

    // 8. Menstruation data (for Call 3 — cycle-tracking users only)
    const { data: menstrualDecayRow } = await supabase
      .from("menstruation_decay_weights")
      .select("day_m7, day_m6, day_m5, day_m4, day_m3, day_m2, day_m1, day_0, day_p1, day_p2, day_p3, day_p4, day_p5, day_p6, day_p7")
      .eq("user_id", userId)
      .maybeSingle();

    const { data: menstruationConfig } = await supabase
      .from("menstruation_settings")
      .select("last_menstruation_date, avg_cycle_length, auto_update_average")
      .eq("user_id", userId)
      .maybeSingle();

    const { data: menstrualEventRows } = await supabase
      .from("triggers")
      .select("start_at, migraine_id, source, notes")
      .eq("user_id", userId)
      // ilike with no wildcard = case-insensitive equality: periods exist as
      // lowercase "menstruation" (tracker/health sync/auto-convert) AND
      // capital-M "Menstruation" (logged from the user's own trigger pool).
      .ilike("type", "menstruation")
      .eq("active", true);
    const menstrualEvents = notDemo(menstrualEventRows);

    // ══════════════════════════════════════════════════════════════
    // DETERMINISTIC COMPARISONS (cheap math on already-loaded data)
    // ══════════════════════════════════════════════════════════════

    const mc = migraines.length;

    // ── Trend windows: last 30d vs all prior history ──
    const last30MigraineIds = new Set<string>();
    const priorMigraineIds = new Set<string>();
    for (const m of migraines) {
      if (!m.start_at) continue;
      if (m.start_at.substring(0, 10) >= last30CutoffIso) last30MigraineIds.add(m.id);
      else priorMigraineIds.add(m.id);
    }
    const last30MigraineCount = last30MigraineIds.size;
    const priorMigraineCount = priorMigraineIds.size;

    function countLinked(logs: any[], field = "type"): Record<string, { linked: number; linked30: number; linkedPrior: number; total: number }> {
      const counts: Record<string, { linked: number; linked30: number; linkedPrior: number; total: number }> = {};
      for (const log of logs ?? []) {
        const key = (log[field] ?? log.name ?? "").toLowerCase();
        if (!key) continue;
        if (!counts[key]) counts[key] = { linked: 0, linked30: 0, linkedPrior: 0, total: 0 };
        counts[key].total++;
        if (log.migraine_id && migraineIds.has(log.migraine_id)) {
          counts[key].linked++;
          if (last30MigraineIds.has(log.migraine_id)) counts[key].linked30++;
          else counts[key].linkedPrior++;
        }
      }
      return counts;
    }

    // ── Lifestyle window averages (last30 vs prior30) ──
    function avgBy(rows: any[] | null | undefined, valueCol: string): { last30: number | null; prior: number | null; last30N: number; priorN: number } {
      let l30 = 0, l30N = 0, p = 0, pN = 0;
      for (const r of rows ?? []) {
        const v = r[valueCol];
        if (v == null) continue;
        if (String(r.date) >= last30CutoffIso) { l30 += Number(v); l30N++; }
        else { p += Number(v); pN++; }
      }
      return {
        last30: l30N > 0 ? +(l30 / l30N).toFixed(1) : null,
        prior: pN > 0 ? +(p / pN).toFixed(1) : null,
        last30N: l30N,
        priorN: pN,
      };
    }
    // Food-risk exposure columns are text ("none"/"low"/"medium"/"high") —
    // mapped onto the 0..3 scale the apps chart so avgBy can window them.
    // exposureRank additionally folds "MODERATE", which the hand-written map
    // this replaced scored as null, dropping the day from the average.
    // An absent cell stays null here rather than 0, so a day with no food
    // logged is "no data" and not a real "none" pulling the mean down.
    const riskNum = (raw: unknown): number | null =>
      raw == null || String(raw).trim() === "" ? null : exposureRank(raw);
    const nutritionRows = (nutrition.data ?? []).map((r: any) => ({
      ...r,
      tyramine_n: riskNum(r.max_tyramine_exposure),
      alcohol_n: riskNum(r.max_alcohol_exposure),
      gluten_n: riskNum(r.max_gluten_exposure),
      histamine_n: riskNum(r.max_histamine_exposure),
    }));

    const lifestyle = {
      sleepScore: avgBy(sleepScore.data, "value_pct"),
      sleepHours: avgBy(sleepDuration.data, "value_hours"),
      stress: avgBy(stressIndex.data, "value"),
      recovery: avgBy(recoveryScore.data, "value_pct"),
      calories: avgBy(nutritionRows, "total_calories"),
      protein: avgBy(nutritionRows, "total_protein_g"),
      carbs: avgBy(nutritionRows, "total_carbs_g"),
      fat: avgBy(nutritionRows, "total_fat_g"),
      fiber: avgBy(nutritionRows, "total_fiber_g"),
      sugar: avgBy(nutritionRows, "total_sugar_g"),
      sodium: avgBy(nutritionRows, "total_sodium_mg"),
      caffeine: avgBy(nutritionRows, "total_caffeine_mg"),
      hydration: avgBy(hydration.data, "value_ml"),
      steps: avgBy(steps.data, "value_count"),
      restingHr: avgBy(restingHr.data, "value_bpm"),
      screenTime: avgBy(screenTime.data, "total_hours"),
      lateScreen: avgBy(lateScreen.data, "value_hours"),
      hrv: avgBy(hrvDaily.data, "value_rmssd_ms"),
      mindfulness: avgBy(mindfulness.data, "duration_minutes"),
      tyramine: avgBy(nutritionRows, "tyramine_n"),
      alcoholExposure: avgBy(nutritionRows, "alcohol_n"),
      glutenExposure: avgBy(nutritionRows, "gluten_n"),
      histamineExposure: avgBy(nutritionRows, "histamine_n"),
    };

    // Bedtime: clock readings, so averages need the shift-past-noon trick and
    // the SD (regularity) matters more than the absolute hour — timestamps are
    // UTC and the user's offset is unknown here.
    const bedtimeStats = (() => {
      const hrs: number[] = [];
      for (const r of (bedtime.data ?? [])) {
        const t = new Date(String((r as any).value_at));
        if (isNaN(t.getTime())) continue;
        let h = t.getUTCHours() + t.getUTCMinutes() / 60;
        if (h < 12) h += 24;
        hrs.push(h);
      }
      if (hrs.length === 0) return { meanHour: null as number | null, sdHours: null as number | null, n: 0 };
      const mean = hrs.reduce((a, b) => a + b, 0) / hrs.length;
      const sd = Math.sqrt(hrs.reduce((a, b) => a + (b - mean) ** 2, 0) / hrs.length);
      return { meanHour: mean % 24, sdHours: sd, n: hrs.length };
    })();

    // Aura summary: how many attacks carry aura, typical duration, and which
    // eye zones recur (zones deduped per attack).
    const auraSummary = (() => {
      const zoneMigraines = new Map<string, Set<string>>();
      const migrainesWithZones = new Set<string>();
      for (const r of (auraZoneRows ?? [])) {
        if (!r.migraine_id || !r.zone) continue;
        migrainesWithZones.add(r.migraine_id);
        const set = zoneMigraines.get(String(r.zone)) ?? new Set<string>();
        set.add(r.migraine_id);
        zoneMigraines.set(String(r.zone), set);
      }
      let withAura = 0;
      const durations: number[] = [];
      for (const m of migraines) {
        const hasArray = Array.isArray(m.aura_locations) && m.aura_locations.length > 0;
        if (hasArray || migrainesWithZones.has(m.id)) withAura++;
        if (m.aura_duration_minutes != null && m.aura_duration_minutes > 0) durations.push(m.aura_duration_minutes);
      }
      const avgDurationMin = durations.length
        ? Math.round(durations.reduce((a, b) => a + b, 0) / durations.length)
        : null;
      const zoneCounts = [...zoneMigraines.entries()]
        .map(([zone, set]) => [zone, set.size] as [string, number])
        .sort((a, b) => b[1] - a[1])
        .slice(0, 6);
      return { withAura, total: mc, avgDurationMin, zoneCounts };
    })();

    // Statistical patterns from the correlation engine, pre-formatted. Rows
    // tagged with a gate mode are already vetted; older untagged rows fall
    // back to the p/lift cut the report uses. Symptom-outcome rows are a
    // different product and stay out of this prompt.
    const corrLines: string[] = (() => {
      const rows = (corrStats ?? []).filter((s: any) =>
        !s.symptom_outcome &&
        ["trigger", "metric", "prodrome", "interaction"].includes(s.factor_type));
      const gated = (s: any) => typeof s.lag_details?.mode === "string";
      const sig = (s: any) => gated(s) || (s.p_value <= 0.1 && s.lift_ratio > 1);
      const picked = rows.filter(sig)
        .sort((a: any, b: any) => b.lift_ratio - a.lift_ratio)
        .slice(0, 12);
      return picked.map((s: any) => {
        const name = s.factor_b ? `${s.factor_name} + ${s.factor_b}` : s.factor_name;
        const lag = s.best_lag_days ? `, strongest ${s.best_lag_days}d before` : "";
        const pm = s.pct_migraine_windows != null ? `${Math.round(s.pct_migraine_windows)}%` : "?";
        const pc = s.pct_control_windows != null ? `${Math.round(s.pct_control_windows)}%` : "?";
        if (s.lag_details?.mode === "prevalence") {
          return `- "${name}": present around ${pm} of migraine windows${lag} (prevalence pattern — too common or rare for a control comparison; n=${s.sample_size ?? "?"})`;
        }
        return `- "${name}": ${pm} of migraine windows vs ${pc} of ordinary days, lift ${Number(s.lift_ratio).toFixed(1)}x${lag} (n=${s.sample_size ?? "?"}, p=${Number(s.p_value).toFixed(3)})`;
      });
    })();

    // Treatment history, pre-formatted: regimens, timing stats, side effects.
    const treatmentLines: string[] = (() => {
      const L: string[] = [];
      const todayIso = ymd(today);
      const narrByRegimen = new Map<string, string>(
        (regimenNarratives ?? []).map((r: any) => [String(r.regimen_id), String(r.narrative ?? "")]));
      let narrsUsed = 0;
      for (const r of (regimens ?? [])) {
        const active = !r.stop_date || String(r.stop_date) >= todayIso;
        const span = `${r.start_date ?? "?"} → ${r.stop_date ?? "ongoing"}`;
        L.push(`- ${active ? "ACTIVE" : "STOPPED"} ${r.kind ?? "treatment"}: "${r.name}"${r.amount ? ` ${r.amount}` : ""}${r.frequency ? `, ${r.frequency}` : ""}${r.drug_class ? ` [${r.drug_class}]` : ""} (${span})`);
        const narr = narrByRegimen.get(String(r.id));
        if (narr && narrsUsed < 4) {
          narrsUsed++;
          L.push(`  App's read so far: ${narr.replace(/\s+/g, " ").slice(0, 220)}${narr.length > 220 ? "…" : ""}`);
        }
      }
      if ((timingStats ?? []).length > 0) {
        L.push("Timing (peak severity when treatment taken early vs late):");
        for (const t of (timingStats ?? []).slice(0, 8)) {
          L.push(`- "${t.treatment_name}": within ${t.cutoff_minutes}m → avg peak ${Number(t.early_avg_peak).toFixed(1)} (${t.early_count} attacks); later → avg peak ${Number(t.late_avg_peak).toFixed(1)} (${t.late_count} attacks)`);
        }
      }
      const seCounts: Record<string, number> = {};
      let seLogs = 0;
      for (const log of (sideEffectLogs ?? [])) {
        seLogs++;
        for (const s of (log.selected_symptoms ?? []) as string[]) {
          const k = String(s).toLowerCase();
          seCounts[k] = (seCounts[k] ?? 0) + 1;
        }
      }
      if (seLogs > 0) {
        const top = Object.entries(seCounts).sort((a, b) => b[1] - a[1]).slice(0, 8)
          .map(([s, n]) => `${s} (${n}x)`).join(", ");
        L.push(`Side effects logged in the last 90 days: ${seLogs} check-ins${top ? `; most frequent: ${top}` : ""}.`);
      }
      return L;
    })();

    const triggerCounts = countLinked(triggers ?? []);
    const prodromeCounts = countLinked(prodromes ?? []);
    const medicineCounts = countLinked(medicines ?? []);
    const reliefCounts = countLinked(reliefs ?? []);
    const symptomCounts = countLinked(symptoms ?? []);
    const activityCounts = countLinked(activities ?? []);
    const missedCounts = countLinked(missedActivities ?? []);
    const locationCounts = countLinked(locations ?? []);

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
      symptomCounts, symptomFavIds, symCategoryByLabel,
      activityCounts, actFavIds,
      missedCounts, missedFavIds,
      locationCounts,
      metricSettings ?? [],
      last30MigraineCount, priorMigraineCount,
      lifestyle,
      symSeverityByLabel, auraSummary, corrLines, treatmentLines, bedtimeStats,
    ) + rejectionBlock;

    const call1Result = await callOpenAI(CALL1_SYSTEM_PROMPT + modelLanguageDirective(lang), call1UserMessage);

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
      ) + rejectionBlock;

      call2Result = await callOpenAI(CALL2_SYSTEM_PROMPT + modelLanguageDirective(lang), call2UserMessage);
    }

    // ══════════════════════════════════════════════════════════════
    // CALL 3 — The Menstrual Decay Specialist
    // Cycle-aware bell curve (m7…0…p7) is separate from severity-tiered
    // post-event decay. Predictive: scoring engine looks forward up to 7
    // days from the next predicted period. Only runs for cycle-tracking
    // users with enough on-cycle migraines to fit a curve.
    // ══════════════════════════════════════════════════════════════

    let call3Result: any = {};
    let runCall3 = false;
    let offsetHistogram: Record<number, number> = {};
    let onCycleCount = 0;
    let offCycleCount = 0;

    const eligibleForCall3 =
      mode === "full" &&
      profile.tracks_cycle === true &&
      !!menstruationConfig &&
      (menstrualEvents?.length ?? 0) >= 2;

    if (eligibleForCall3) {
      const eventDates = (menstrualEvents ?? [])
        .map((e: any) => e.start_at?.substring(0, 10))
        .filter((d: any): d is string => typeof d === "string" && d.length === 10)
        .map((d: string) => new Date(d + "T00:00:00Z").getTime())
        .sort((a: number, b: number) => a - b);

      for (let i = -7; i <= 7; i++) offsetHistogram[i] = 0;

      for (const m of migraines) {
        if (!m.start_at) continue;
        const mTime = new Date(m.start_at.substring(0, 10) + "T00:00:00Z").getTime();
        let nearestOffset = Number.POSITIVE_INFINITY;
        for (const eTime of eventDates) {
          const offset = Math.round((mTime - eTime) / 86400000);
          if (Math.abs(offset) < Math.abs(nearestOffset)) nearestOffset = offset;
        }
        if (Math.abs(nearestOffset) <= 7) {
          offsetHistogram[nearestOffset] = (offsetHistogram[nearestOffset] ?? 0) + 1;
          onCycleCount++;
        } else {
          offCycleCount++;
        }
      }

      // Need a meaningful sample to fit the curve.
      if (onCycleCount >= 3) {
        runCall3 = true;
        const call3UserMessage = buildCall3Message(
          profile, mc, onCycleCount, offCycleCount,
          offsetHistogram, menstrualDecayRow, menstruationConfig,
        );
        call3Result = await callOpenAI(CALL3_SYSTEM_PROMPT + modelLanguageDirective(lang), call3UserMessage);
      }
    }

    // ══════════════════════════════════════════════════════════════
    // WRITE PROPOSALS
    // ══════════════════════════════════════════════════════════════

    const proposals: any[] = [];

    // Trigger adjustments
    //
    // `to` becomes user_triggers.prediction_value verbatim. The ladder is
    // closed — the risk engine ignores anything else — so a proposal off the
    // ladder can never be applied and is dropped here rather than offered to
    // the user and refused later.
    for (const adj of call1Result.trigger_adjustments ?? []) {
      if (!PREDICTION_VALUES.has(String(adj.to ?? "").trim().toUpperCase())) {
        console.warn(`recalibrate: dropping trigger_adjustment with to=${JSON.stringify(adj.to)}`);
        continue;
      }
      proposals.push({
        user_id: userId, type: "trigger", label: adj.label,
        from_value: adj.from, to_value: String(adj.to).trim().toUpperCase(),
        should_favorite: adj.should_favorite ?? false,
        reasoning: adj.reasoning, status: "pending",
      });
    }

    // Prodrome adjustments
    for (const adj of call1Result.prodrome_adjustments ?? []) {
      if (!PREDICTION_VALUES.has(String(adj.to ?? "").trim().toUpperCase())) {
        console.warn(`recalibrate: dropping prodrome_adjustment with to=${JSON.stringify(adj.to)}`);
        continue;
      }
      proposals.push({
        user_id: userId, type: "prodrome", label: adj.label,
        from_value: adj.from, to_value: String(adj.to).trim().toUpperCase(),
        should_favorite: adj.should_favorite ?? false,
        reasoning: adj.reasoning, status: "pending",
      });
    }

    // Favorite adjustments
    //
    // item_type becomes the proposal TYPE, which is what apply-recalibration
    // branches on, so a value outside this list steers the proposal into some
    // other branch entirely. A favorite the model typed as a "prodrome" landed
    // in the prodrome branch and wrote to_value ("favorite") straight into
    // user_prodromes.prediction_value — a value the risk engine ignores. There
    // is one such row in production. Drop anything unrecognised.
    for (const fav of call1Result.favorite_adjustments ?? []) {
      if (!FAVORITE_ITEM_TYPES.has(fav.item_type)) {
        console.warn(`recalibrate: dropping favorite_adjustment with item_type ${JSON.stringify(fav.item_type)}`);
        continue;
      }
      proposals.push({
        user_id: userId, type: fav.item_type, label: fav.label,
        from_value: fav.currently_favorite ? "favorite" : "not_favorite",
        to_value: fav.should_favorite ? "favorite" : "not_favorite",
        should_favorite: fav.should_favorite,
        reasoning: fav.reasoning, status: "pending",
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

    // Menstrual decay weights (Call 3, only if it ran and returned weights)
    if (runCall3 && call3Result.menstrual_weights) {
      const w = call3Result.menstrual_weights;
      proposals.push({
        user_id: userId, type: "menstruation_decay", label: "menstrual_cycle",
        from_value: menstrualDecayRow ? JSON.stringify(menstrualDecayRow) : "{}",
        to_value: JSON.stringify({
          day_m7: w.day_m7, day_m6: w.day_m6, day_m5: w.day_m5, day_m4: w.day_m4,
          day_m3: w.day_m3, day_m2: w.day_m2, day_m1: w.day_m1, day_0: w.day_0,
          day_p1: w.day_p1, day_p2: w.day_p2, day_p3: w.day_p3, day_p4: w.day_p4,
          day_p5: w.day_p5, day_p6: w.day_p6, day_p7: w.day_p7,
        }),
        reasoning: call3Result.reasoning ?? "",
        status: "pending",
        metadata: {
          on_cycle_migraines: onCycleCount,
          off_cycle_migraines: offCycleCount,
          histogram: offsetHistogram,
        },
      });
    }

    // ── No-op filter ──
    // The AI sometimes proposes a "change" to the value already in place.
    // from == to can never be worth a banner, so drop it deterministically.
    const isNoop = (p: any) => {
      if (p.from_value == null || p.to_value == null) return false;
      if (String(p.from_value) === String(p.to_value)) return true;
      if (p.type === "gauge_decay" || p.type === "menstruation_decay") {
        try {
          const a = JSON.parse(p.from_value), b = JSON.parse(p.to_value);
          const keys = Object.keys(b);
          return keys.length > 0 && keys.every((k) => Number(a[k]) === Number(b[k]));
        } catch { return false; }
      }
      return false;
    };
    const actionable = proposals.filter((p) => !isNoop(p));

    // ── Materiality gate (automated runs only) ──
    // A weekly run whose only output is a rewritten narrative (or a repeat
    // data warning) must stay silent: no batch, no banner, no push. Any
    // still-pending batch from a previous week is left in place untouched.
    // User-initiated runs skip the gate: the person is waiting on a reply, and
    // the clients have no branch for an empty one.
    const material = actionable.filter((p) => p.type !== "data_warning");
    if (automated && material.length === 0) {
      return json({ status: "no_material_changes", proposals: 0 });
    }

    // Clinical assessment proposal — user must accept the updated narrative
    if (call1Result.clinical_assessment) {
      actionable.push({
        user_id: userId, type: "clinical_assessment", label: "Clinical assessment",
        from_value: (profile.clinical_assessment ?? "").substring(0, 200) + "…",
        to_value: call1Result.clinical_assessment,
        reasoning: call1Result.summary ?? "Updated clinical profile based on your real data.",
        status: "pending",
      });
    }

    // Summary row
    actionable.push({
      user_id: userId, type: "summary", label: "_meta",
      reasoning: call1Result.clinical_assessment ?? "",
      status: "pending",
      metadata: {
        mode,
        call1_summary: call1Result.summary,
        call2_summary: call2Result.summary ?? null,
        calibration_notes: call2Result.calibration_notes ?? null,
        call3_summary: runCall3 ? (call3Result.summary ?? null) : null,
        menstrual_notes: runCall3 ? (call3Result.menstrual_notes ?? null) : null,
        migraine_count: mc,
      },
    });

    // Clear old pending — only now that a replacement batch exists. A silent
    // (no-material-changes) run returned above and left any prior batch alone.
    await supabase
      .from("recalibration_proposals")
      .delete()
      .eq("user_id", userId)
      .eq("status", "pending");

    await supabase.from("recalibration_proposals").insert(actionable);

    // ── Send FCM ──
    // user_ids + notification_key = a VISIBLE push rendered in the recipient's
    // language. The old direct-token send was data-only: Android just set the
    // banner flag and iOS got a silent background wake, so no user ever saw a
    // notification. The banner reads the DB, so tray-rendering on Android
    // (which suppresses onMessageReceived) loses nothing.
    try {
      await supabase.functions.invoke("send-fcm-push", {
        body: {
          type: "recalibration_ready",
          user_ids: [userId],
          notification_key: {
            title: "Your weekly check-in found something",
            body: "Your data has shifted. Review the suggested adjustments to your profile.",
          },
        },
      });
    } catch (_) { /* non-fatal */ }

    return json({
      status: "ok",
      proposals: actionable.length - 1, // exclude summary row
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
4. Their REAL LOGGED DATA — every trigger, prodrome, medicine, relief, symptom (with per-row severity), activity linked to their migraines, with concrete counts; plus aura detail (how often attacks carry aura, which visual-field zones, typical duration).
5. Their CURRENT configuration (prediction_values, favorites).
6. Their connected data sources.
7. AVAILABLE LABELS for triggers and prodromes.
8. STATISTICAL PATTERNS from the app's correlation engine — vetted links between factors and attacks. Rows marked as prevalence patterns describe how often a factor surrounds attacks without a control-day comparison; treat them as descriptive, not causal.
9. TREATMENT COURSES — preventive/acute regimens with dates and drug class, early-vs-late timing outcomes, and side effects logged during them.
10. Expanded LIFESTYLE METRICS — sleep, stress, nutrition, hydration, steps, HRV, screen time (total and late-night), bedtime regularity, mindfulness, and food-risk exposure (tyramine, alcohol, gluten, histamine).

YOUR TASK — reconcile the questionnaire baseline with real data:

STEP 1 — PATIENT PROFILE (clinical_assessment):
Write ONE narrative reconciling questionnaire with real data, organised into the SIX sections below. Address the patient as "you/your". Warm, clear, no jargon. Output as plain text with the exact section headers shown (in this order, separated by blank lines). 1-3 short paragraphs per section.

This is a RECONCILIATION — not a replacement. The questionnaire is the foundation. The data overlay confirms, contradicts, or adds to it. Always cite concrete numbers from the data.

SECTION FORMAT — use these exact headers:

=== Migraine Profile ===
Pain character, accompanying symptoms (note how severe each tends to be when the data says so), postdromes, pain locations, aura (how often attacks carry it, which visual-field zones, typical duration — skip if none recorded), duration, severity, frequency, experience (years with migraines). Who she is as a migraine sufferer.

=== Trigger Profile ===
Triggers (self-reported vs data-confirmed vs newly discovered), prodromes (warning signs that precede attacks), and activities (what she was usually doing when migraines hit). Where the STATISTICAL PATTERNS section confirms or contradicts a suspected trigger, say so with its numbers; describe prevalence patterns as "often around your attacks" rather than proven causes. What leads up to her migraines.

=== Burden Profile ===
Missed activities — what she's losing because of migraines. Frame this as discussion material for a neurologist conversation.

=== Coping Profile ===
Medicines (what she takes, frequency, what appears to be helping), reliefs (what she reaches for, effectiveness), and treatment courses: what she's on or has tried, for how long, side effects logged during them, and whether taking treatment early vs late changed peak severity (cite the timing numbers when present). How she manages attacks.

=== Lifestyle ===
Sleep (including bedtime regularity), stress, nutrition (calories, macros), food-risk exposure (tyramine, alcohol, gluten, histamine), hydration, exercise, HRV, screen habits (total and late-night), mindfulness. Baseline patterns over the window. Flag extremes in either direction (too much/too little), independent of whether they linked to migraines.

=== Trend ===
Compare the LAST 30 DAYS against ALL PRIOR HISTORY across the 5 profiles above. State direction for each (improving / stable / worsening) with concrete numbers (e.g. "migraines went from 4.2/mo prior to 5.1 last 30d"). End with the single most important shift to watch.

STEP 2 — TRIGGER & PRODROME ADJUSTMENTS:
Based on linked counts vs current prediction_value.

CRITICAL RULES:
- This recalibration runs WEEKLY. Propose an adjustment ONLY where the data clearly contradicts the current setting — for example a trigger's linked share of migraines no longer matches its level, or the correlation engine has confirmed a new pattern. Do not propose changes just because time has passed.
- Empty adjustment lists are a valid, complete answer. Never manufacture a change to have something to show.
- If a RECENTLY REJECTED section is present, do not re-propose anything in it.
- Only adjust ONE NOTCH at a time: NONE→LOW, LOW→MILD, MILD→HIGH (and reverse). NEVER skip levels.
- Use ONLY exact label strings from the AVAILABLE LABELS lists.
- At most 10 changes.
- Frame gently: "We've noticed X was linked to Y of your migraines — we suggest adjusting it up a notch."
- Lowering IS allowed — the user approves every change.

STEP 3 — FAVORITE ADJUSTMENTS:
Medicines, reliefs, symptoms, activities, missed activities. If logged frequently but not favorited → suggest favorite. If favorited but rarely used → suggest removing. Only clear-cut cases backed by the counts; an empty list is fine.

STEP 4 — DATA WARNINGS:
If a trigger is rated HIGH or MILD but the relevant data source isn't connected, flag it.

Respond with ONLY valid JSON (no markdown fences):
{
  "clinical_assessment": "Six-section narrative using the exact === Section === headers from STEP 1, in order, separated by blank lines.",
  "summary": "2-3 sentence summary of key findings",
  "trigger_adjustments": [
    {"label": "exact label", "from": "CURRENT", "to": "NONE|LOW|MILD|HIGH (one notch from CURRENT)", "should_favorite": true/false, "reasoning": "gentle explanation referencing data"}
  ],
  "prodrome_adjustments": [
    {"label": "exact label", "from": "CURRENT", "to": "NONE|LOW|MILD|HIGH (one notch from CURRENT)", "should_favorite": true/false, "reasoning": "gentle explanation"}
  ],
  "favorite_adjustments": [
    {"item_type": "medicine|relief|symptom|activity|missed_activity", "label": "exact label", "currently_favorite": true/false, "should_favorite": true/false, "reasoning": "..."}
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
  symptomCounts: Record<string, any>, symptomFavIds: Set<string>, symCategoryByLabel: Map<string, string>,
  activityCounts: Record<string, any>, actFavIds: Set<string>,
  missedCounts: Record<string, any>, missedFavIds: Set<string>,
  locationCounts: Record<string, any>,
  metricSettings: any[],
  last30MigraineCount: number, priorMigraineCount: number,
  lifestyle: Record<string, { last30: number | null; prior: number | null; last30N: number; priorN: number }>,
  symSeverityByLabel: Record<string, Record<string, number>>,
  auraSummary: { withAura: number; total: number; avgDurationMin: number | null; zoneCounts: [string, number][] },
  corrLines: string[],
  treatmentLines: string[],
  bedtimeStats: { meanHour: number | null; sdHours: number | null; n: number },
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
      L.push(`- "${pool.label}": linked to ${counts.linked} of ${mc} migraines (logged ${counts.total}x, last30: ${counts.linked30}, prior: ${counts.linkedPrior}). Currently: ${sev}. Favorite: ${fav}. Auto: ${auto}.`);
    } else if (sev !== "NONE") {
      L.push(`- "${pool.label}": NEVER LOGGED but rated ${sev}. Favorite: ${fav}. Auto: ${auto}.`);
    }
  }
  for (const [key, counts] of Object.entries(triggerCounts)) {
    const inPool = triggerPool.find((p: any) => p.label.toLowerCase() === key);
    if (!inPool || (inPool.prediction_value ?? "NONE").toUpperCase() === "NONE") {
      L.push(`- "${key}": linked to ${(counts as any).linked} of ${mc} migraines (last30: ${(counts as any).linked30}, prior: ${(counts as any).linkedPrior}) but currently NONE — worth reviewing!`);
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
      L.push(`- "${pool.label}": linked to ${counts.linked} of ${mc} migraines (logged ${counts.total}x, last30: ${counts.linked30}, prior: ${counts.linkedPrior}). Currently: ${sev}. Favorite: ${fav}.`);
    } else if (sev !== "NONE") {
      L.push(`- "${pool.label}": NEVER LOGGED but rated ${sev}. Favorite: ${fav}.`);
    }
  }
  L.push("");

  // ── Medicines ──
  L.push("=== MEDICINES ===");
  for (const [label, counts] of Object.entries(medicineCounts)) {
    L.push(`- "${label}": used ${(counts as any).total}x, linked to ${(counts as any).linked} migraines (last30: ${(counts as any).linked30}, prior: ${(counts as any).linkedPrior}). Favorited: ${medFavIds.has(label)}.`);
  }
  L.push("");

  // ── Reliefs ──
  L.push("=== RELIEFS ===");
  for (const [label, counts] of Object.entries(reliefCounts)) {
    L.push(`- "${label}": used ${(counts as any).total}x, linked to ${(counts as any).linked} migraines (last30: ${(counts as any).linked30}, prior: ${(counts as any).linkedPrior}). Favorited: ${reliefFavIds.has(label)}.`);
  }
  L.push("");

  // ── Symptoms split by category (pain_character / accompanying / postdrome) ──
  const symByCategory: Record<string, [string, any][]> = { pain_character: [], accompanying: [], postdrome: [], other: [] };
  for (const [label, counts] of Object.entries(symptomCounts)) {
    const cat = (symCategoryByLabel.get(label) ?? "other").toLowerCase();
    const bucket =
      cat === "pain_character" ? "pain_character" :
      cat === "postdrome" ? "postdrome" :
      (cat === "accompanying" || cat === "visual" || cat === "cognitive" || cat === "motor") ? "accompanying" :
      "other";
    symByCategory[bucket].push([label, counts]);
  }
  const renderSymBucket = (header: string, rows: [string, any][]) => {
    L.push(`=== ${header} ===`);
    if (rows.length === 0) { L.push("(none logged)"); }
    for (const [label, counts] of rows) {
      const sevDist = symSeverityByLabel[label];
      const sevNote = sevDist
        ? ` Severity: ${Object.entries(sevDist).sort((a, b) => b[1] - a[1]).map(([s, n]) => `${s} ${n}x`).join(", ")}.`
        : "";
      L.push(`- "${label}": reported ${counts.total}x across ${counts.linked} migraines (last30: ${counts.linked30}, prior: ${counts.linkedPrior}). Favorited: ${symptomFavIds.has(label)}.${sevNote}`);
    }
    L.push("");
  };
  renderSymBucket("PAIN CHARACTER SYMPTOMS", symByCategory.pain_character);
  renderSymBucket("ACCOMPANYING SYMPTOMS (includes visual/cognitive/motor)", symByCategory.accompanying);
  renderSymBucket("POSTDROME SYMPTOMS", symByCategory.postdrome);
  if (symByCategory.other.length > 0) renderSymBucket("OTHER SYMPTOMS", symByCategory.other);

  // ── Activities (context when migraine hit) ──
  L.push("=== ACTIVITIES (what she was doing when migraine hit) ===");
  for (const [label, counts] of Object.entries(activityCounts)) {
    L.push(`- "${label}": logged ${(counts as any).total}x, linked to ${(counts as any).linked} migraines (last30: ${(counts as any).linked30}, prior: ${(counts as any).linkedPrior}). Favorited: ${actFavIds.has(label)}.`);
  }
  L.push("");

  // ── Missed activities (what she's losing) ──
  L.push("=== MISSED ACTIVITIES (what she's losing because of migraines) ===");
  for (const [label, counts] of Object.entries(missedCounts)) {
    L.push(`- "${label}": logged ${(counts as any).total}x, linked to ${(counts as any).linked} migraines (last30: ${(counts as any).linked30}, prior: ${(counts as any).linkedPrior}). Favorited: ${missedFavIds.has(label)}.`);
  }
  L.push("");

  // ── Locations ──
  if (Object.keys(locationCounts).length > 0) {
    L.push("=== LOCATIONS (where on head) ===");
    for (const [label, counts] of Object.entries(locationCounts)) {
      L.push(`- "${label}": linked to ${(counts as any).linked} migraines (last30: ${(counts as any).linked30}, prior: ${(counts as any).linkedPrior}).`);
    }
    L.push("");
  }

  // ── Aura detail ──
  L.push("=== AURA (visual disturbances before/during attacks) ===");
  if (auraSummary.withAura === 0) {
    L.push("No aura recorded on any attack.");
  } else {
    L.push(`${auraSummary.withAura} of ${auraSummary.total} migraines carried aura.`);
    if (auraSummary.avgDurationMin != null) L.push(`Typical aura duration: ~${auraSummary.avgDurationMin} minutes.`);
    if (auraSummary.zoneCounts.length > 0) {
      L.push(`Most-hit visual-field zones (attacks): ${auraSummary.zoneCounts.map(([z, n]) => `${z.replace(/_/g, " ")} (${n})`).join(", ")}.`);
    }
  }
  L.push("");

  // ── Statistical patterns (correlation engine) ──
  L.push("=== STATISTICAL PATTERNS (the app's vetted correlation engine) ===");
  if (corrLines.length === 0) {
    L.push("(no statistically established patterns yet)");
  } else {
    L.push("These passed the app's significance gate. 'Prevalence pattern' rows describe how often a factor surrounds attacks, without a control-day comparison.");
    for (const line of corrLines) L.push(line);
  }
  L.push("");

  // ── Treatment courses ──
  L.push("=== TREATMENT COURSES (regimens, timing, side effects) ===");
  if (treatmentLines.length === 0) {
    L.push("(no treatment regimens tracked)");
  } else {
    for (const line of treatmentLines) L.push(line);
  }
  L.push("");

  // ── Lifestyle metrics: last 30d vs prior 30d averages ──
  L.push("=== LIFESTYLE METRICS (avg last 30d vs prior 30d, from connected sources) ===");
  const ls = lifestyle;
  const fmt = (w: { last30: number | null; prior: number | null; last30N: number; priorN: number }, unit: string) => {
    const a = w.last30 != null ? `${w.last30}${unit}` : "no data";
    const b = w.prior != null ? `${w.prior}${unit}` : "no data";
    return `last30=${a} (n=${w.last30N}), prior=${b} (n=${w.priorN})`;
  };
  L.push(`- Sleep duration: ${fmt(ls.sleepHours, "h")}`);
  L.push(`- Sleep score: ${fmt(ls.sleepScore, "%")}`);
  L.push(`- Stress index: ${fmt(ls.stress, "")}`);
  L.push(`- Recovery score: ${fmt(ls.recovery, "%")}`);
  L.push(`- Calories: ${fmt(ls.calories, " kcal")}`);
  L.push(`- Protein: ${fmt(ls.protein, "g")} | Carbs: ${fmt(ls.carbs, "g")} | Fat: ${fmt(ls.fat, "g")}`);
  L.push(`- Fiber: ${fmt(ls.fiber, "g")} | Sugar: ${fmt(ls.sugar, "g")} | Sodium: ${fmt(ls.sodium, "mg")} | Caffeine: ${fmt(ls.caffeine, "mg")}`);
  L.push(`- Hydration: ${fmt(ls.hydration, "ml")}`);
  L.push(`- Steps: ${fmt(ls.steps, "")} | Resting HR: ${fmt(ls.restingHr, " bpm")} | HRV: ${fmt(ls.hrv, " ms")}`);
  L.push(`- Screen time: ${fmt(ls.screenTime, "h")} | Late-night screen (after bedtime hours): ${fmt(ls.lateScreen, "h")}`);
  L.push(`- Mindfulness: ${fmt(ls.mindfulness, " min")}`);
  L.push(`- Food-risk exposure (0 none … 3 high, avg of daily max): tyramine ${fmt(ls.tyramine, "")} | alcohol ${fmt(ls.alcoholExposure, "")} | gluten ${fmt(ls.glutenExposure, "")} | histamine ${fmt(ls.histamineExposure, "")}`);
  if (bedtimeStats.n > 0 && bedtimeStats.meanHour != null && bedtimeStats.sdHours != null) {
    const hh = Math.floor(bedtimeStats.meanHour), mm = Math.round((bedtimeStats.meanHour - hh) * 60);
    L.push(`- Bedtime: around ${String(hh).padStart(2, "0")}:${String(mm).padStart(2, "0")} UTC, varying by ±${bedtimeStats.sdHours.toFixed(1)}h night to night (n=${bedtimeStats.n}). Clock is UTC — use it for regularity, not the exact local hour.`);
  }
  L.push("");

  // ── Trend window summary ──
  L.push("=== TREND WINDOW (last 30 days vs all prior history) ===");
  L.push(`Migraines: last30=${last30MigraineCount}, prior=${priorMigraineCount} (total=${mc}).`);
  L.push("Use the linked30/linkedPrior numbers on each item above to compare direction (rising / falling / stable).");
  L.push("");

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

2. ADJUST THRESHOLDS only if the diagnosis shows a real problem:
   - This recalibration runs WEEKLY. If the gauge is performing acceptably (sensible zone distribution, no systematic false-alarm or missed-migraine pattern), set "gauge_thresholds" to null. No change is the correct answer for a well-calibrated gauge.
   - When adjusting: nudge by ~20-30% per recalibration, don't make dramatic jumps
   - Someone who gets migraines "1-3 per month" should NOT be in red every day
   - Most days should be GREEN. Red should be rare and meaningful.

3. ADJUST DECAY CURVES only if the performance data demands it:
   - If the current curves are doing their job, return an empty "decay_weights" list.
   - Steeper curves if triggers hit fast then fade
   - Flatter curves if cumulative buildup matters more
   - Incremental changes only
   - WHOLE INTEGERS ONLY for every day value (0, 1, 2, 3...). No decimals. No 0.5, no 2.5, no 1.5. Round to the nearest integer.

4. WRITE calibration_notes (2-3 paragraphs, warm, patient-facing, "you/your"):
   - How the gauge performed
   - What's changing and why
   - What to expect going forward

5. WRITE summary (2-3 sentences)

Respond with ONLY valid JSON (no markdown fences). "gauge_thresholds" may be null and "decay_weights" may be [] when no change is warranted:
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
// CALL 3 — The Menstrual Decay Specialist
// ══════════════════════════════════════════════════════════════════════

const CALL3_SYSTEM_PROMPT = `You are a women's health specialist recalibrating a PREDICTIVE menstrual decay curve that feeds the patient's migraine risk gauge.

=== HOW THIS CURVE WORKS (very different from the post-event decay you may know) ===

The patient's app already predicts each upcoming period from their cycle length + last period date. On the predicted period day, the app fires a synthetic event called "menstruation_predicted". The 15-day curve you tune below is then applied CENTERED on that event:

  day_m7 … day_m1 = the 7 days BEFORE the predicted period (gauge pre-rises)
  day_0           = the predicted period day itself
  day_p1 … day_p7 = the 7 days AFTER (gauge tapers off)

The scoring engine looks forward up to 7 days from today. So a weight at day_m5 means: when the predicted period is 5 days away, the gauge already carries this much menstruation contribution TODAY.

This curve is severity-agnostic — only ONE curve per user, not three (unlike risk_decay_weights).

The daily gauge = sum of (menstruation curve contribution at the right offset) + (every active trigger × its decay weight) + (every active prodrome × its decay weight). So this curve STACKS with everything else. If you collapse it from peak=6 to peak=0.8, you've effectively removed menstruation from the patient's gauge entirely.

=== WHAT YOU RECEIVE ===

1. Patient demographics + cycle settings (cycle length, last period date, auto-update flag)
2. Total migraines + on-cycle vs off-cycle counts (on-cycle = within ±7 days of any LOGGED period). The on/off ratio tells you how cycle-linked this patient really is.
3. Histogram: how many of their migraines fall at each offset day -7..+7 from the nearest logged period.
4. The patient's CURRENT 15-day curve.

=== YOUR TASK — 4 STEPS ===

STEP 1 — DIAGNOSE from the histogram:
- Where is the peak? (textbook menstrual migraine peaks day_m2 to day_p2, but THIS patient may peak elsewhere — follow THEIR data)
- Is the pattern tight (1-3 day window) or broad (smeared across the cycle)?
- How cycle-linked are they overall? on_cycle / total ratio. <30% means weakly linked — propose a flatter curve. >60% means strongly linked — sharpen the peak around their cluster.

STEP 2 — PROPOSE new weights, following these CRITICAL RULES:

A) PEAK MATCHES THE HISTOGRAM, NOT A TEXTBOOK.
   If the histogram peaks at day_p4, your new peak goes at day_p4. Do not default to a symmetric bell at day_0 unless that is genuinely where the data peaks. The whole point of recalibration is to match THIS patient.

B) PRESERVE THE SCALE OF THE CURRENT CURVE.
   This is non-negotiable. Compute the current peak value. The new peak MUST be within ±30% of that. So if current peak = 6, new peak is 4.2-7.8. If current peak = 1.0, new peak is 0.7-1.3. Never collapse the magnitude by an order of magnitude. The gauge depends on these weights stacking meaningfully with trigger contributions.

C) NUDGE THE SHAPE, DON'T REBUILD IT.
   This recalibration runs WEEKLY. If the current curve already fits the histogram, set "menstrual_weights" to null — no change is a valid, common outcome. When you do adjust: shift the peak by at most 1 day per recalibration, and adjust individual weights by at most 20%. If the current curve peaks at day_m1 and the data peaks at day_p4, move toward day_p4 over multiple recalibrations — don't jump the whole way in one run.

D) SANITY CHECK.
   - If on_cycle migraines < 3, return a FLATTER curve, not a sharper one. The data isn't strong enough to fit a precise peak.
   - Weights should taper toward (but not necessarily to) 0 at the edges. A weight of 0 at day_m7 and day_p7 is fine; non-zero is also fine if migraines actually appear there.
   - Avoid negative weights. Avoid weights more than 1.5× the current peak.

E) WHOLE INTEGERS ONLY.
   - Every weight must be a whole integer (0, 1, 2, 3, 4, 5, 6, 7...). No decimals. No 0.5, no 1.5, no 2.5. Round to the nearest integer.

STEP 3 — WRITE menstrual_notes (2-3 paragraphs, warm, patient-facing, "you/your"):
- Paragraph 1: what the data revealed about THEIR cycle-migraine pattern (cite the histogram peak offset and on-cycle %)
- Paragraph 2: what's changing in the curve and why (reference the old vs new peak position, mention this is a nudge not a rewrite)
- Paragraph 3: what to expect day-to-day (e.g. "your gauge will start ramping up around X days before your predicted period")
- Tone: matter-of-fact, kind, no jargon. No em dashes.

STEP 4 — WRITE summary (1-2 sentences, what changed in plain English) and reasoning (1-2 sentences, PLAIN ENGLISH, no jargon, no field names like "day_p4" or "histogram" or percentages — talk like a kind clinician explaining the change to the patient. Match the tone of "A flatter curve helps capture cumulative effect of mild triggers over several days").

Respond with ONLY valid JSON (no markdown fences). "menstrual_weights" may be null when the current curve already fits:
{
  "menstrual_weights": {
    "day_m7": N, "day_m6": N, "day_m5": N, "day_m4": N, "day_m3": N, "day_m2": N, "day_m1": N,
    "day_0": N,
    "day_p1": N, "day_p2": N, "day_p3": N, "day_p4": N, "day_p5": N, "day_p6": N, "day_p7": N
  },
  "reasoning": "Plain-English 1-2 sentence explanation, no jargon, no field names, kind clinician tone.",
  "menstrual_notes": "Patient-facing 2-3 paragraph explanation following the structure above",
  "summary": "1-2 sentence summary of what changed in plain English"
}`;

function buildCall3Message(
  profile: any, mc: number, onCycle: number, offCycle: number,
  histogram: Record<number, number>,
  currentWeights: any,
  config: any,
): string {
  const L: string[] = [];

  L.push("=== PATIENT SUMMARY ===");
  L.push(`${profile.gender ?? "Unknown"}, ${profile.age_range ?? "unknown age"}`);
  L.push(`Cycle length: ${config?.avg_cycle_length ?? "unknown"} days`);
  L.push(`Last logged period: ${config?.last_menstruation_date ?? "unknown"}`);
  L.push(`Auto-update average from logs: ${config?.auto_update_average ?? false}`);
  L.push("");

  L.push("=== MIGRAINE DISTRIBUTION RELATIVE TO NEAREST LOGGED PERIOD ===");
  L.push(`Total migraines: ${mc}`);
  L.push(`On-cycle (within ±7 days of a period): ${onCycle}`);
  L.push(`Off-cycle (more than 7 days from any period): ${offCycle}`);
  if (mc > 0) {
    L.push(`Cycle-linkage: ${((onCycle / mc) * 100).toFixed(0)}% of migraines are within the cycle window`);
  }
  L.push("");

  L.push("=== HISTOGRAM (offset days → migraine count) ===");
  for (let i = -7; i <= 7; i++) {
    const key = i === 0 ? "day_0" : i < 0 ? `day_m${Math.abs(i)}` : `day_p${i}`;
    L.push(`${key.padEnd(6, " ")} (offset ${i >= 0 ? "+" : ""}${i}): ${histogram[i] ?? 0}`);
  }
  L.push("");

  L.push("=== CURRENT CURVE ===");
  if (currentWeights) {
    for (let i = -7; i <= 7; i++) {
      const key = i === 0 ? "day_0" : i < 0 ? `day_m${Math.abs(i)}` : `day_p${i}`;
      L.push(`${key}: ${currentWeights[key]}`);
    }
  } else {
    L.push("(no curve set yet — propose a starting curve)");
  }

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
      response_format: { type: "json_object" },
      messages: [{ role: "system", content: systemPrompt }, { role: "user", content: userMessage }],
    }),
  });
  const data = await res.json();
  const text = data.choices?.[0]?.message?.content ?? "{}";
  const cleaned = text.replace(/```json/g, "").replace(/```/g, "").trim();
  try {
    return JSON.parse(cleaned);
  } catch (err) {
    console.error("callOpenAI: JSON parse failed", { err, snippet: cleaned.slice(0, 500) });
    return {};
  }
}

function json(body: any, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}