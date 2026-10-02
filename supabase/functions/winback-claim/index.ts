// supabase/functions/winback-claim/index.ts
//
// The one button in the winback email. GET ?u=<user id>&s=<signature>&lang=xx
// adds the free month straight onto premium_status.trial_end, then redirects to
// migraineme.app/welcomeback, which opens the app. No store, no code to type,
// same on iPhone and Android.
//
// The month is the in-app WELCOMEBACK promo (promo_codes), logged in
// promo_redemptions, so a second tap, or typing WELCOMEBACK in the app
// afterwards, can never stack another month.
//
// Deploy with --no-verify-jwt: it is opened from an email, there is no session.
// Mail scanners that prefetch links can trigger the claim early; harmless, the
// recipient was being offered this month anyway.

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";
import { winbackSig } from "../_shared/winback_sig.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const PROMO = "WELCOMEBACK";
const LANDING = "https://migraineme.app/welcomeback";

const go = (state: "ok" | "already" | "premium" | "error", lang: string) =>
  new Response(null, { status: 302, headers: { Location: `${LANDING}?s=${state}&lang=${lang}`, "Cache-Control": "no-store" } });

serve(async (req: Request) => {
  const url = new URL(req.url);
  const lang = (url.searchParams.get("lang") ?? "en").toLowerCase().replace(/[^a-z]/g, "").slice(0, 2) || "en";
  try {
    const userId = url.searchParams.get("u") ?? "";
    const sig = url.searchParams.get("s") ?? "";
    if (!/^[0-9a-f-]{36}$/i.test(userId) || sig !== (await winbackSig(SERVICE_KEY, userId))) return go("error", lang);

    const db = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });

    const { data: promo } = await db.from("promo_codes").select("id, days_granted").eq("code", PROMO).maybeSingle();
    if (!promo) { console.error("winback-claim: promo row missing"); return go("error", lang); }

    const { data: status } = await db.from("premium_status")
      .select("trial_end, rc_subscription_status").eq("user_id", userId).maybeSingle();
    if (status?.rc_subscription_status === "active") return go("premium", lang);

    const { data: prior } = await db.from("promo_redemptions")
      .select("id").eq("user_id", userId).eq("promo_code_id", promo.id).maybeSingle();
    if (prior) return go("already", lang);

    const now = new Date();
    const current = status?.trial_end ? new Date(status.trial_end) : null;
    const newEnd = new Date(current && current > now ? current : now);
    newEnd.setDate(newEnd.getDate() + promo.days_granted);

    const { error: upErr } = await db.from("premium_status")
      .upsert({ user_id: userId, trial_end: newEnd.toISOString() }, { onConflict: "user_id" });
    if (upErr) { console.error("winback-claim update:", upErr.message); return go("error", lang); }

    await db.from("promo_redemptions").insert({
      user_id: userId,
      promo_code_id: promo.id,
      code: PROMO,
      days_granted: promo.days_granted,
      trial_end_before: status?.trial_end ?? null,
      trial_end_after: newEnd.toISOString(),
    });
    await db.rpc("increment_promo_usage", { p_code_id: promo.id });

    console.log(`winback-claim: user=${userId} new_trial_end=${newEnd.toISOString()}`);
    return go("ok", lang);
  } catch (err) {
    console.error("winback-claim error:", err);
    return go("error", lang);
  }
});
