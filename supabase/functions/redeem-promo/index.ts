// supabase/functions/redeem-promo/index.ts
//
// Redeems a promo code for the authenticated user.
// Extends trial_end in premium_status by the code's days_granted.
// If the code belongs to an affiliate partner, permanently tags the user
// to that partner (first-touch) for commission attribution.
//
// POST body: { "code": "EARLYADOPTER" }
// Returns: { ok: true, days_granted: 90, new_trial_end: "..." }

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { getLang, t, type Lang } from "../_shared/i18n.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*",
    },
  });

serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response(null, {
      headers: {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Headers": "Authorization, Content-Type, apikey",
        "Access-Control-Allow-Methods": "POST, OPTIONS",
      },
    });
  }

  if (req.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }

  try {
    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_KEY);

    // ── Auth ──
    const token = (req.headers.get("Authorization") ?? "").replace("Bearer ", "");
    const { data: { user }, error: authErr } = await supabase.auth.getUser(token);
    if (authErr || !user) return json({ error: "Unauthorized" }, 401);
    const userId = user.id;

    // Display language for every `message` below. The app shows these verbatim
    // in the paywall, so this response IS the render boundary — there is no
    // second chance for the client to translate. `error` stays English: it is a
    // machine code, not copy.
    const lang: Lang = await getLang(supabase, userId);

    // ── Parse input ──
    const body = await req.json().catch(() => ({}));
    const rawCode = (body.code ?? "").toString().trim().toUpperCase();
    if (!rawCode) return json({ error: "missing_code", message: t(lang, "Please enter a promo code.") }, 400);

    // ── Look up code ──
    const { data: promo, error: lookupErr } = await supabase
      .from("promo_codes").select("*").eq("code", rawCode).maybeSingle();
    if (lookupErr || !promo) return json({ error: "invalid_code", message: t(lang, "This promo code doesn't exist.") }, 404);

    // ── Validate code ──
    if (!promo.is_active) return json({ error: "inactive_code", message: t(lang, "This promo code is no longer active.") }, 410);
    if (promo.expires_at && new Date(promo.expires_at) < new Date()) return json({ error: "expired_code", message: t(lang, "This promo code has expired.") }, 410);
    if (promo.max_uses !== null && promo.times_used >= promo.max_uses) return json({ error: "exhausted_code", message: t(lang, "This promo code has reached its usage limit.") }, 410);

    // ── Check if user already redeemed this code ──
    const { data: existing } = await supabase
      .from("promo_redemptions").select("id").eq("user_id", userId).eq("promo_code_id", promo.id).maybeSingle();
    if (existing) return json({ error: "already_redeemed", message: t(lang, "You've already used this promo code.") }, 409);

    // ── Get current premium_status ──
    const { data: currentStatus } = await supabase
      .from("premium_status").select("trial_end, rc_subscription_status").eq("user_id", userId).maybeSingle();

    const now = new Date();
    const currentTrialEnd = currentStatus?.trial_end ? new Date(currentStatus.trial_end) : null;
    const baseDate = currentTrialEnd && currentTrialEnd > now ? currentTrialEnd : now;
    const newTrialEnd = new Date(baseDate);
    newTrialEnd.setDate(newTrialEnd.getDate() + promo.days_granted);

    // ── Update premium_status ──
    const { error: updateErr } = await supabase
      .from("premium_status").upsert({ user_id: userId, trial_end: newTrialEnd.toISOString() }, { onConflict: "user_id" });
    if (updateErr) return json({ error: "update_failed", message: t(lang, "Something went wrong. Please try again.") }, 500);

    // ── Log the redemption ──
    await supabase.from("promo_redemptions").insert({
      user_id: userId,
      promo_code_id: promo.id,
      code: rawCode,
      days_granted: promo.days_granted,
      trial_end_before: currentStatus?.trial_end ?? null,
      trial_end_after: newTrialEnd.toISOString(),
    });

    // ── Increment usage counter atomically ──
    // Uses a DB function to check + increment in one operation, preventing
    // race conditions where two simultaneous redemptions both pass the max_uses check.
    const { data: incremented } = await supabase.rpc("increment_promo_usage", { p_code_id: promo.id });
    if (!incremented) {
      // Another request beat us to the last use — roll back
      await supabase.from("premium_status").upsert({ user_id: userId, trial_end: currentStatus?.trial_end ?? null }, { onConflict: "user_id" });
      await supabase.from("promo_redemptions").delete().eq("user_id", userId).eq("promo_code_id", promo.id);
      return json({ error: "exhausted_code", message: t(lang, "This promo code has reached its usage limit.") }, 410);
    }

    // ── Affiliate attribution: first-touch, permanent ──
    // If this code belongs to a partner, tag the user to that partner once.
    // user_id is unique on partner_attributions, so a later non-partner or
    // different-partner code redemption never overwrites the first tag.
    if (promo.partner_id) {
      await supabase.from("partner_attributions").upsert({
        user_id: userId,
        partner_id: promo.partner_id,
        promo_code_id: promo.id,
        source: "promo_code",
      }, { onConflict: "user_id", ignoreDuplicates: true });
    }

    console.log(`Promo redeemed: user=${userId}, code=${rawCode}, days=${promo.days_granted}, new_trial_end=${newTrialEnd.toISOString()}`);

    return json({ ok: true, days_granted: promo.days_granted, new_trial_end: newTrialEnd.toISOString() });

  } catch (err) {
    console.error("redeem-promo error:", err);
    // English on purpose: this catch also covers a failure to resolve the user,
    // so there is no language to render in. An untranslated last-resort message
    // beats swallowing the error.
    return json({ error: "internal_error", message: "Something went wrong." }, 500);
  }
});
