// supabase/functions/partner-offer-attribution/index.ts
//
// Closes the iOS attribution gap.
//
// THE PROBLEM: iOS's "Have a promo code?" calls Apple's own redemption
// sheet (PaywallScreen.swift -> presentCodeRedemptionSheet), because App
// Store guideline 3.1.1 requires it. That never reaches our redeem-promo
// function, so no partner_attributions row is written and iPhone
// customers — the majority of paying users — could never be credited to
// a partner. Android was fine; iOS was invisible.
//
// THE FIX: each partner gets their own APPLE offer code, stored in
// partners.apple_offer_code. RevenueCat fires a webhook on redemption
// carrying that code; this function maps it to the partner and writes
// the same first-touch attribution row redeem-promo would have.
// No app change, so no App Store release needed.
//
// SAFETY: this is a SECOND, separate RevenueCat webhook. The existing
// premium-sync webhook is untouched — a bug here can never break premium
// entitlements for paying customers. It only ever INSERTs attribution.
//
// Auth: RevenueCat Authorization header must equal PARTNER_ACCRUAL_SECRET.
//
// NOTE ON FIELD NAMES: RevenueCat's exact offer-code field varies by
// event/store, so several known spellings are checked and the full event
// is logged when none match — the first real redemption will show the
// true shape in the function logs rather than silently dropping money.

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const SECRET = Deno.env.get("PARTNER_ACCRUAL_SECRET")!;

const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json" } });

// Events that represent someone actually starting a subscription.
const ATTRIBUTABLE = new Set([
  "INITIAL_PURCHASE",
  "NON_RENEWING_PURCHASE",
  "RENEWAL",
  "PRODUCT_CHANGE",
  "SUBSCRIPTION_EXTENDED",
]);

function extractOfferCode(ev: Record<string, any>): string | null {
  const candidates = [
    ev.offer_code,
    ev.presented_offering_id_offer_code,
    ev.promotional_offer_id,
    ev.subscriber_attributes?.offer_code?.value,
    ev.metadata?.offer_code,
  ];
  for (const c of candidates) {
    if (typeof c === "string" && c.trim()) return c.trim();
  }
  return null;
}

serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  const auth = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (auth !== SECRET) return json({ error: "Unauthorized" }, 401);

  const payload = await req.json().catch(() => ({}));
  const ev = payload?.event ?? payload ?? {};
  const type = ev.type ?? "UNKNOWN";

  if (!ATTRIBUTABLE.has(type)) {
    return json({ ok: true, skipped: "event_type_not_attributable", type });
  }

  const offerCode = extractOfferCode(ev);
  if (!offerCode) {
    // Log the whole event so the real field name is discoverable from the
    // first live redemption instead of guessing.
    console.log(`[partner-offer-attribution] no offer code on ${type}; event=${JSON.stringify(ev)}`);
    return json({ ok: true, skipped: "no_offer_code", type });
  }

  // RC app_user_id casing: iOS registers UPPERCASE UUIDs, Android
  // lowercase. Our tables key on the lowercase Supabase user id.
  const rawUserId: string = ev.app_user_id ?? ev.original_app_user_id ?? "";
  const userId = rawUserId.toLowerCase();
  if (!/^[0-9a-f-]{36}$/.test(userId)) {
    console.log(`[partner-offer-attribution] unusable app_user_id "${rawUserId}" for offer ${offerCode}`);
    return json({ ok: true, skipped: "unusable_app_user_id" });
  }

  const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_KEY);

  const { data: partner } = await supabase
    .from("partners")
    .select("id, status")
    .ilike("apple_offer_code", offerCode)
    .maybeSingle();

  if (!partner) {
    console.log(`[partner-offer-attribution] offer code "${offerCode}" matches no partner`);
    return json({ ok: true, skipped: "unknown_offer_code", offerCode });
  }
  if (partner.status !== "active") {
    return json({ ok: true, skipped: "partner_not_active", offerCode });
  }

  // First-touch and permanent: user_id is unique, so an existing
  // attribution (from a code redemption or another partner) always wins.
  const { error } = await supabase.from("partner_attributions").upsert(
    { user_id: userId, partner_id: partner.id, source: 'apple_offer_code' },
    { onConflict: "user_id", ignoreDuplicates: true },
  );
  if (error) {
    console.error(`[partner-offer-attribution] insert failed: ${error.message}`);
    return json({ error: "insert_failed", message: error.message }, 500);
  }

  console.log(`[partner-offer-attribution] attributed ${userId} to partner ${partner.id} via ${offerCode}`);
  return json({ ok: true, attributed: true, partner_id: partner.id, offerCode });
});
