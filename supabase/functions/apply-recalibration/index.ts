// supabase/functions/apply-recalibration/index.ts
//
// Called by the client after user reviews proposals.
// Receives accepted/rejected proposal IDs, applies accepted changes.

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

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

    const body = await req.json();
    const accepted: string[] = body.accepted ?? [];
    const rejected: string[] = body.rejected ?? [];

    if (accepted.length === 0 && rejected.length === 0) {
      return json({ error: "No proposals specified" }, 400);
    }

    // Load all referenced proposals
    const { data: proposals } = await supabase
      .from("recalibration_proposals")
      .select("*")
      .eq("user_id", userId)
      .eq("status", "pending")
      .in("id", [...accepted, ...rejected]);

    if (!proposals || proposals.length === 0) {
      return json({ error: "No matching pending proposals" }, 404);
    }

    const now = new Date().toISOString();
    let changeCount = 0;

    // ── Apply each accepted proposal ──
    for (const p of proposals) {
      if (!accepted.includes(p.id)) continue;

      switch (p.type) {

        // ── Trigger: update prediction_value + optional favorite ──
        case "trigger": {
          const { data: items } = await supabase
            .from("user_triggers")
            .select("id, display_group")
            .eq("user_id", userId)
            .ilike("label", p.label);

          // Update all group members (same as AiSetupApplier)
          for (const item of items ?? []) {
            await supabase.from("user_triggers")
              .update({ prediction_value: p.to_value })
              .eq("id", item.id);
          }

          if (p.should_favorite && items?.[0]) {
            await upsertFavorite(supabase, "trigger_preferences", "trigger_id", userId, items[0].id);
          }

          changeCount++;
          break;
        }

        // ── Prodrome: update prediction_value + optional favorite ──
        case "prodrome": {
          const { data: items } = await supabase
            .from("user_prodromes")
            .select("id, display_group")
            .eq("user_id", userId)
            .ilike("label", p.label);

          for (const item of items ?? []) {
            await supabase.from("user_prodromes")
              .update({ prediction_value: p.to_value })
              .eq("id", item.id);
          }

          if (p.should_favorite && items?.[0]) {
            await upsertFavorite(supabase, "prodrome_user_preferences", "prodrome_id", userId, items[0].id);
          }

          changeCount++;
          break;
        }

        // ── Favorites: medicine, relief, symptom, activity, missed_activity ──
        case "medicine":
        case "relief":
        case "symptom":
        case "activity":
        case "missed_activity": {
          const config = FAVORITE_TABLE_MAP[p.type];
          if (!config) break;

          // Find pool item ID by label
          const { data: poolItems } = await supabase
            .from(config.poolTable)
            .select("id")
            .eq("user_id", userId)
            .ilike("label", p.label)
            .limit(1);

          const poolId = poolItems?.[0]?.id;
          if (!poolId) break;

          if (p.should_favorite) {
            await upsertFavorite(supabase, config.prefTable, config.fkColumn, userId, poolId);
          } else {
            // Remove favorite
            await supabase.from(config.prefTable)
              .delete()
              .eq("user_id", userId)
              .eq(config.fkColumn, poolId);
          }

          changeCount++;
          break;
        }

        // ── Gauge threshold ──
        case "gauge_threshold": {
          await supabase
            .from("risk_gauge_thresholds")
            .upsert({
              user_id: userId,
              zone: p.label,
              min_value: parseFloat(p.to_value),
            }, { onConflict: "user_id,zone" });

          changeCount++;
          break;
        }

        // ── Gauge decay curve ──
        case "gauge_decay": {
          const decay = JSON.parse(p.to_value);
          await supabase
            .from("risk_decay_weights")
            .upsert({
              user_id: userId,
              severity: p.label,
              ...decay,
            }, { onConflict: "user_id,severity" });

          changeCount++;
          break;
        }

        // ── Profile update ──
        case "profile": {
          // p.label is the field name (e.g. "frequency", "trajectory")
          await supabase
            .from("ai_setup_profiles")
            .update({ [p.label]: p.to_value })
            .eq("user_id", userId);

          changeCount++;
          break;
        }

        // ── Clinical assessment update ──
        case "clinical_assessment": {
          await supabase
            .from("ai_setup_profiles")
            .update({ clinical_assessment: p.to_value })
            .eq("user_id", userId);

          changeCount++;
          break;
        }
      }
    }

    // ── Mark proposals as accepted/rejected ──
    if (accepted.length > 0) {
      await supabase.from("recalibration_proposals")
        .update({ status: "accepted", reviewed_at: now })
        .in("id", accepted);
    }
    if (rejected.length > 0) {
      await supabase.from("recalibration_proposals")
        .update({ status: "rejected", reviewed_at: now })
        .in("id", rejected);
    }

    // Mark any remaining pending as expired
    await supabase.from("recalibration_proposals")
      .update({ status: "rejected", reviewed_at: now })
      .eq("user_id", userId)
      .eq("status", "pending");

    // ── Get summary for history record ──
    const { data: summaryRow } = await supabase
      .from("recalibration_proposals")
      .select("reasoning, metadata")
      .eq("user_id", userId)
      .eq("type", "summary")
      .order("created_at", { ascending: false })
      .limit(1)
      .single();

    // ── Write history ──
    const historyMode = summaryRow?.metadata?.mode ?? "full";
    const cooldownDays = historyMode === "profile_only" ? 1 : 30;

    await supabase.from("recalibration_history").insert({
      user_id: userId,
      summary: summaryRow?.metadata?.call1_summary ?? "Recalibration applied.",
      change_count: changeCount,
      next_recalibration_at: new Date(Date.now() + cooldownDays * 86400000).toISOString(),
      metadata: {
        mode: historyMode,
        accepted_count: accepted.length,
        rejected_count: rejected.length,
        calibration_notes: summaryRow?.metadata?.calibration_notes,
      },
    });

    // ── Trigger risk score recalculation ──
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

    return json({ status: "ok", changes: changeCount });

  } catch (err) {
    console.error("apply-recalibration error:", err);
    return json({ error: (err as Error).message }, 500);
  }
});


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
  // Check if already exists
  const { data: existing } = await supabase
    .from(prefTable)
    .select("id")
    .eq("user_id", userId)
    .eq(fkColumn, itemId)
    .limit(1)
    .single();

  if (existing) return; // already favorited

  // Get next position
  const { data: maxRow } = await supabase
    .from(prefTable)
    .select("position")
    .eq("user_id", userId)
    .order("position", { ascending: false })
    .limit(1)
    .single();

  const pos = (maxRow?.position ?? -1) + 1;

  await supabase.from(prefTable).insert({
    user_id: userId,
    [fkColumn]: itemId,
    position: pos,
    status: "frequent",
  });
}

function json(body: any, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}