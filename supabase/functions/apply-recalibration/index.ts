// supabase/functions/apply-recalibration/index.ts
//
// Called by the client after user reviews proposals.
// Receives accepted/rejected proposal IDs, applies accepted changes.

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { ANSWER_COLUMN_FIELDS, answerField, answerKeyFor, normalizeCertainty } from "../_shared/answerFields.ts";
import { getLang, t, type Lang } from "../_shared/i18n.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

serve(async (req: Request) => {
  try {
    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_KEY);

    // Must be the user (not cron)
    const token = (req.headers.get("Authorization") ?? "").replace("Bearer ", "");
    const { data: { user } } = await supabase.auth.getUser(token);
    if (!user) return json({ error: "Unauthorized" }, 401);
    const userId = user.id;

    // Display language for the `message` fields below. The `error` codes and
    // everything written to recalibration_history stay English: history rows
    // are read back by the app, which renders them through its own tables, so
    // translating at write time would freeze them in one language.
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

    const body = await req.json();
    // `accepted` is a list of proposal ids, or of {id, to_value} where the
    // user picked a different value than proposed (answer proposals let them
    // choose any option on the review screen). Both shapes are accepted so
    // older clients keep working.
    const overrides = new Map<string, string>();
    const accepted: string[] = [];
    for (const item of (body.accepted ?? []) as unknown[]) {
      if (typeof item === "string") { accepted.push(item); continue; }
      if (item && typeof item === "object" && typeof (item as any).id === "string") {
        accepted.push((item as any).id);
        if ((item as any).to_value != null) overrides.set((item as any).id, String((item as any).to_value));
      }
    }
    const rejected: string[] = body.rejected ?? [];

    if (accepted.length === 0 && rejected.length === 0) {
      return json({ error: "no_proposals", message: t(lang, "No proposals specified") }, 400);
    }

    // Load all referenced proposals
    const { data: proposals, error: proposalsErr } = await supabase
      .from("recalibration_proposals")
      .select("*")
      .eq("user_id", userId)
      .eq("status", "pending")
      .in("id", [...accepted, ...rejected]);
    check({ error: proposalsErr }, "read recalibration_proposals");

    if (!proposals || proposals.length === 0) {
      return json({ error: "no_pending_proposals", message: t(lang, "No matching pending proposals") }, 404);
    }

    const now = new Date().toISOString();
    let changeCount = 0;

    // ── Apply each accepted proposal ──
    //
    // A proposal is only marked `accepted` once its write has actually landed.
    // Anything that threw is collected in `failures`, stays `pending` (so the
    // review screen offers it again) and turns the whole call into an HTTP
    // error, because a client that got 200 tells the user the change was made.
    const appliedIds: string[] = [];
    const failures: { id: string; type: string; label: string | null; reason: string }[] = [];

    for (const p of proposals) {
      if (!accepted.includes(p.id)) continue;

      try {
        // An override replaces the proposed value; it is written back onto the
        // proposal row too, so history shows what was actually applied.
        const override = overrides.get(p.id);
        const effective = override != null && override !== p.to_value ? { ...p, to_value: override } : p;
        const applied = await applyProposal(supabase, userId, effective);
        if (effective !== p) {
          check(await supabase.from("recalibration_proposals")
            .update({ to_value: override }).eq("id", p.id), "record override");
        }
        appliedIds.push(p.id);
        if (applied) changeCount++;
      } catch (e) {
        const reason = (e as Error).message ?? String(e);
        console.error(`apply-recalibration: proposal ${p.id} (${p.type}/${p.label}) failed: ${reason}`);
        failures.push({ id: p.id, type: p.type, label: p.label ?? null, reason });
      }
    }

    // ── Mark proposals as accepted/rejected ──
    if (appliedIds.length > 0) {
      check(await supabase.from("recalibration_proposals")
        .update({ status: "accepted", reviewed_at: now })
        .in("id", appliedIds), "mark accepted");
    }
    if (rejected.length > 0) {
      check(await supabase.from("recalibration_proposals")
        .update({ status: "rejected", reviewed_at: now })
        .in("id", rejected), "mark rejected");
    }

    // Mark any remaining pending as expired — except the ones that failed to
    // apply. Expiring those would destroy the only record that the user asked
    // for a change that never happened.
    {
      let sweep = supabase.from("recalibration_proposals")
        .update({ status: "rejected", reviewed_at: now })
        .eq("user_id", userId)
        .eq("status", "pending");
      if (failures.length > 0) {
        sweep = sweep.not("id", "in", `(${failures.map((f) => f.id).join(",")})`);
      }
      check(await sweep, "expire pending");
    }

    // ── Get summary for history record ──
    const { data: summaryRow } = await supabase
      .from("recalibration_proposals")
      .select("reasoning, metadata")
      .eq("user_id", userId)
      .eq("type", "summary")
      .order("created_at", { ascending: false })
      .limit(1)
      .maybeSingle();

    // ── Write history ──
    // Skipped when nothing at all applied but something failed: the history row
    // carries the cooldown, and locking the user out on the strength of a run
    // that changed nothing would compound the failure.
    // Full cooldown is 5 days: recalibration runs WEEKLY with a materiality
    // gate, so the cooldown only has to stop back-to-back manual runs, not
    // pace the cadence — 30 days here would silently starve the weekly cron.
    if (appliedIds.length > 0 || failures.length === 0) {
      const historyMode = summaryRow?.metadata?.mode ?? "full";
      const cooldownDays = historyMode === "profile_only" ? 1 : 5;

      check(await supabase.from("recalibration_history").insert({
        user_id: userId,
        summary: summaryRow?.metadata?.call1_summary ?? "Recalibration applied.",
        change_count: changeCount,
        next_recalibration_at: new Date(Date.now() + cooldownDays * 86400000).toISOString(),
        metadata: {
          mode: historyMode,
          accepted_count: appliedIds.length,
          rejected_count: rejected.length,
          failed_count: failures.length,
          calibration_notes: summaryRow?.metadata?.calibration_notes,
        },
      }), "write history");
    }

    // ── Trigger risk score recalculation ──
    if (changeCount > 0) {
      try {
        await fetch(`${SUPABASE_URL}/functions/v1/recalc-risk-scores`, {
          method: "POST",
          headers: {
            "Authorization": `Bearer ${SUPABASE_SERVICE_KEY}`,
            "apikey": SUPABASE_SERVICE_KEY,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({ user_id: userId }),
        });
      } catch (_) { /* non-fatal */ }
    }

    // `error` codes stay English machine codes, same as everywhere else in this
    // function; the clients render their own translated failure state off the
    // non-2xx status, and the failed proposals are still pending to retry.
    if (failures.length > 0) {
      return json({
        status: "failed",
        error: "apply_failed",
        changes: changeCount,
        applied: appliedIds.length,
        failed: failures,
      }, 500);
    }

    return json({ status: "ok", changes: changeCount });

  } catch (err) {
    console.error("apply-recalibration error:", err);
    return json({ error: (err as Error).message }, 500);
  }
});


// ══════════════════════════════════════════════════════════════════
// Write checking
// ══════════════════════════════════════════════════════════════════

// PostgREST does not throw on a rejected write — it hands the rejection back in
// `error` and leaves `data` null. Every write in this function used to discard
// that field, so an upsert naming columns that do not exist (day0 vs day_0)
// counted as a change and the proposal was marked accepted. Nothing may write
// without going through here.
function check(res: { error?: { message?: string } | null }, what: string): void {
  if (res?.error) {
    throw new Error(`${what}: ${res.error.message ?? JSON.stringify(res.error)}`);
  }
}

// ══════════════════════════════════════════════════════════════════
// Value whitelists
//
// Both of these guard the same hole: a proposal's `to_value` is model output
// that was written to the database unexamined. A prediction_value outside the
// ladder is silently ignored by the risk engine — production carries a
// user_prodromes row reading 'favorite', from a favorite_adjustments item the
// model typed as a prodrome, which landed in the prodrome branch and was
// written straight into prediction_value.
// ══════════════════════════════════════════════════════════════════

const PREDICTION_VALUES = new Set(["NONE", "LOW", "MILD", "HIGH"]);

function predictionValue(raw: unknown): string {
  const v = typeof raw === "string" ? raw.trim().toUpperCase() : "";
  if (!PREDICTION_VALUES.has(v)) {
    throw new Error(`prediction_value must be one of NONE|LOW|MILD|HIGH, got ${JSON.stringify(raw)}`);
  }
  return v;
}

// The proposal stores the decay curve as {day0…day6}, because that is the shape
// the review screens parse (RecalibrationReviewScreen reads "day$i"). The
// COLUMNS are day_0…day_6. Spreading the parsed object into the row sent
// PostgREST seven columns that do not exist. Map explicitly, and read either
// spelling so a proposal written by any version of recalibrate still applies.
const DECAY_COLUMNS = ["day_0", "day_1", "day_2", "day_3", "day_4", "day_5", "day_6"];

// day_m7 … day_m1, day_0, day_p1 … day_p7 — the 15-day cycle-centred bell.
const MENSTRUAL_COLUMNS = [
  "day_m7", "day_m6", "day_m5", "day_m4", "day_m3", "day_m2", "day_m1", "day_0",
  "day_p1", "day_p2", "day_p3", "day_p4", "day_p5", "day_p6", "day_p7",
];

/** Parse `to_value` into exactly `columns`, rejecting anything else. */
function weightColumns(toValue: unknown, columns: string[]): Record<string, number> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(typeof toValue === "string" ? toValue : JSON.stringify(toValue));
  } catch {
    throw new Error(`to_value is not valid JSON: ${JSON.stringify(toValue)}`);
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    throw new Error(`to_value is not a weight map: ${JSON.stringify(toValue)}`);
  }
  const src = parsed as Record<string, unknown>;
  const out: Record<string, number> = {};
  for (const col of columns) {
    // "day_0" accepts the stored "day0" spelling too; "day_m7" has no variant.
    const raw = src[col] ?? src[col.replace(/^day_(\d)$/, "day$1")];
    const n = typeof raw === "string" ? Number(raw) : raw;
    if (typeof n !== "number" || !Number.isFinite(n)) {
      throw new Error(`to_value is missing a finite ${col}`);
    }
    out[col] = n;
  }
  return out;
}


// ══════════════════════════════════════════════════════════════════
// Apply one proposal
//
// Throws if the change did not land. Returns true when it wrote something,
// false when the proposal type carries nothing to write (data_warning is
// advice for the user, and `summary` is the run's own bookkeeping row).
// ══════════════════════════════════════════════════════════════════

async function applyProposal(supabase: any, userId: string, p: any): Promise<boolean> {
  switch (p.type) {

    // ── Trigger: update prediction_value + optional favorite ──
    case "trigger": {
      const value = predictionValue(p.to_value);
      const { data: items, error } = await supabase
        .from("user_triggers")
        .select("id, display_group")
        .eq("user_id", userId)
        .ilike("label", p.label);
      check({ error }, "read user_triggers");
      let rows: any[] = items ?? [];
      if (rows.length === 0) {
        // A group label ("Poor sleep" standing for its sleep metrics) has no
        // row of its own; the change applies to every member, which is how
        // the gauge scores it.
        const { data: members, error: gErr } = await supabase
          .from("user_triggers")
          .select("id, display_group")
          .eq("user_id", userId)
          .ilike("display_group", p.label);
        check({ error: gErr }, "read user_triggers group");
        rows = members ?? [];
      }
      if (rows.length === 0) throw new Error(`no user_triggers row or group labelled ${p.label}`);

      // Update all group members (same as AiSetupApplier)
      for (const item of rows) {
        check(await supabase.from("user_triggers")
          .update({ prediction_value: value })
          .eq("id", item.id), "update user_triggers");
      }

      if (p.should_favorite) {
        await upsertFavorite(supabase, "trigger_preferences", "trigger_id", userId, rows[0].id);
      }

      return true;
    }

    // ── Prodrome: update prediction_value + optional favorite ──
    case "prodrome": {
      const value = predictionValue(p.to_value);
      const { data: items, error } = await supabase
        .from("user_prodromes")
        .select("id, display_group")
        .eq("user_id", userId)
        .ilike("label", p.label);
      check({ error }, "read user_prodromes");
      let rows: any[] = items ?? [];
      if (rows.length === 0) {
        // A group label ("Poor sleep" standing for its sleep metrics) has no
        // row of its own; the change applies to every member, which is how
        // the gauge scores it.
        const { data: members, error: gErr } = await supabase
          .from("user_prodromes")
          .select("id, display_group")
          .eq("user_id", userId)
          .ilike("display_group", p.label);
        check({ error: gErr }, "read user_prodromes group");
        rows = members ?? [];
      }
      if (rows.length === 0) throw new Error(`no user_prodromes row or group labelled ${p.label}`);

      for (const item of rows) {
        check(await supabase.from("user_prodromes")
          .update({ prediction_value: value })
          .eq("id", item.id), "update user_prodromes");
      }

      if (p.should_favorite) {
        await upsertFavorite(supabase, "prodrome_user_preferences", "prodrome_id", userId, rows[0].id);
      }

      return true;
    }

    // ── Favorites: medicine, relief, symptom, activity, missed_activity ──
    case "medicine":
    case "relief":
    case "symptom":
    case "activity":
    case "missed_activity": {
      const config = FAVORITE_TABLE_MAP[p.type];
      if (!config) throw new Error(`no favorite table for type ${p.type}`);

      // Find pool item ID by label
      const { data: poolItems, error } = await supabase
        .from(config.poolTable)
        .select("id")
        .eq("user_id", userId)
        .ilike("label", p.label)
        .limit(1);
      check({ error }, `read ${config.poolTable}`);

      const poolId = poolItems?.[0]?.id;
      if (!poolId) throw new Error(`no ${config.poolTable} row labelled ${p.label}`);

      if (p.should_favorite) {
        await upsertFavorite(supabase, config.prefTable, config.fkColumn, userId, poolId);
      } else {
        // Remove favorite
        check(await supabase.from(config.prefTable)
          .delete()
          .eq("user_id", userId)
          .eq(config.fkColumn, poolId), `delete ${config.prefTable}`);
      }

      return true;
    }

    // ── Gauge threshold ──
    case "gauge_threshold": {
      const minValue = parseFloat(p.to_value);
      if (!Number.isFinite(minValue)) throw new Error(`to_value is not a number: ${p.to_value}`);

      check(await supabase
        .from("risk_gauge_thresholds")
        .upsert({
          user_id: userId,
          zone: p.label,
          min_value: minValue,
        }, { onConflict: "user_id,zone" }), "upsert risk_gauge_thresholds");

      return true;
    }

    // ── Gauge decay curve ──
    case "gauge_decay": {
      check(await supabase
        .from("risk_decay_weights")
        .upsert({
          user_id: userId,
          severity: p.label,
          ...weightColumns(p.to_value, DECAY_COLUMNS),
        }, { onConflict: "user_id,severity" }), "upsert risk_decay_weights");

      return true;
    }

    // ── Menstrual decay curve (15-day cycle-centered bell) ──
    case "menstruation_decay": {
      check(await supabase
        .from("menstruation_decay_weights")
        .upsert({
          user_id: userId,
          ...weightColumns(p.to_value, MENSTRUAL_COLUMNS),
        }, { onConflict: "user_id" }), "upsert menstruation_decay_weights");

      return true;
    }

    // ── Clinical assessment update ──
    case "clinical_assessment": {
      // Upsert, not update: a user who skipped onboarding has no row yet, and
      // an update on a missing row reports success while writing nothing.
      check(await supabase
        .from("ai_setup_profiles")
        .upsert({
          user_id: userId,
          clinical_assessment: p.to_value,
          // ai_setup_profiles.summary was written once at setup and never
          // again, so it kept describing thresholds the gauge had long left.
          // The run's own summary rides in `reasoning`; the fallback string
          // there is not a summary, so it must not overwrite one.
          ...(p.reasoning && p.reasoning !== "Updated clinical profile based on your real data."
            ? { summary: p.reasoning } : {}),
        }, { onConflict: "user_id" }), "upsert ai_setup_profiles");

      return true;
    }

    // ── Nothing to write ──
    // A data warning is advice the user reads on the review screen; accepting
    // it acknowledges it. `summary` is the run's own bookkeeping row. Both are
    // listed rather than left to fall through, so a type nobody handled cannot
    // be reported as applied.
    // ── Questionnaire answer correction ──
    // Merged into ai_setup_profiles.answers under the casing the row already
    // uses (Android snake_case / iOS camelCase), and mirrored into the
    // matching column for the few answers that also live as one. Upsert, so a
    // user who skipped setup gets their row created by the first accept.
    case "answer": {
      const a = answerField(String(p.label ?? ""));
      if (!a) throw new Error(`unknown answer field ${JSON.stringify(p.label)}`);
      let value = String(p.to_value ?? "").trim();
      if (a.kind === "certainty") value = normalizeCertainty(value) ?? "";
      if (!a.options.includes(value)) throw new Error(`${a.field}: ${JSON.stringify(p.to_value)} is not an allowed value`);

      const { data: row, error: rowErr } = await supabase
        .from("ai_setup_profiles")
        .select("answers")
        .eq("user_id", userId)
        .maybeSingle();
      check({ error: rowErr }, "read ai_setup_profiles");
      const answers: Record<string, unknown> = { ...((row?.answers as Record<string, unknown> | null) ?? {}) };
      answers[answerKeyFor(answers, a)] = value;
      const patch: Record<string, unknown> = { user_id: userId, answers };
      if (ANSWER_COLUMN_FIELDS.has(a.field)) patch[a.field] = value;
      check(await supabase
        .from("ai_setup_profiles")
        .upsert(patch, { onConflict: "user_id" }), "upsert ai_setup_profiles answers");

      return true;
    }

    case "data_warning":
    case "summary":
      return false;

    default:
      throw new Error(`unknown proposal type ${p.type}`);
  }
}


// ══════════════════════════════════════════════════════════════════
// Favorite helper
// ══════════════════════════════════════════════════════════════════

const FAVORITE_TABLE_MAP: Record<string, { poolTable: string; prefTable: string; fkColumn: string }> = {
  medicine:         { poolTable: "user_medicines",          prefTable: "medicine_preferences",          fkColumn: "medicine_id" },
  relief:           { poolTable: "user_reliefs",            prefTable: "relief_preferences",            fkColumn: "relief_id" },
  symptom:          { poolTable: "user_symptoms",           prefTable: "symptom_preferences",           fkColumn: "symptom_id" },
  activity:         { poolTable: "user_activities",         prefTable: "activity_preferences",          fkColumn: "activity_id" },
  missed_activity:  { poolTable: "user_missed_activities",  prefTable: "missed_activity_preferences",   fkColumn: "missed_activity_id" },
};

async function upsertFavorite(
  supabase: any, prefTable: string, fkColumn: string, userId: string, itemId: string
) {
  // Check if already exists. maybeSingle(), not single(): "no rows" is the
  // normal case here and single() reports it as an error, which would now
  // (correctly) fail the proposal.
  const { data: existing, error: existingErr } = await supabase
    .from(prefTable)
    .select("id")
    .eq("user_id", userId)
    .eq(fkColumn, itemId)
    .limit(1)
    .maybeSingle();
  check({ error: existingErr }, `read ${prefTable}`);

  if (existing) return; // already favorited

  // Get next position
  const { data: maxRow, error: maxErr } = await supabase
    .from(prefTable)
    .select("position")
    .eq("user_id", userId)
    .order("position", { ascending: false })
    .limit(1)
    .maybeSingle();
  check({ error: maxErr }, `read ${prefTable} position`);

  const pos = (maxRow?.position ?? -1) + 1;

  check(await supabase.from(prefTable).insert({
    user_id: userId,
    [fkColumn]: itemId,
    position: pos,
    status: "frequent",
  }), `insert ${prefTable}`);
}

function json(body: any, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}