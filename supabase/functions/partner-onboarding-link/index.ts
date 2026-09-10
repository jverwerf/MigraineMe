// supabase/functions/partner-onboarding-link/index.ts
//
// Gives an affiliate partner a stable URL for setting up how they get paid.
//
// WHY THIS EXISTS: a Stripe onboarding link expires five minutes after it is
// minted, so one cannot be pasted into an email — by the time the partner
// reads it, it is dead. Instead each partner has one permanent secret URL
// (migraineme.app/payouts/<token>); every visit mints a fresh link and
// redirects to it. The same URL keeps working if they abandon halfway, come
// back a week later, or need to change their bank details.
//
// The token in the URL is the only credential, so it is compared against the
// partners table server-side and nothing about the partner is echoed back.
//
// Auth: deliberately public (no JWT) — the token IS the auth. Deployed with
// --no-verify-jwt.

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const STRIPE_KEY = Deno.env.get("STRIPE_SECRET_KEY")!;

const SITE = "https://migraineme.app";

// Same guard as partner-payout: a test key would make Stripe return success
// while nothing real is created, and the partner would onboard into a
// sandbox account that can never be paid.
const liveKey = () => STRIPE_KEY && !STRIPE_KEY.includes("_test_");

const page = (title: string, body: string, status = 200) =>
  new Response(
    `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">` +
      `<title>${title}</title>` +
      `<style>body{font:16px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;` +
      `margin:0;padding:48px 20px;background:#faf8fc;color:#1b1023}` +
      `.w{max-width:560px;margin:0 auto}h1{font-size:22px;margin:0 0 12px}` +
      `p{margin:0 0 12px;color:#4a3a56}</style>` +
      `<div class="w"><h1>${title}</h1>${body}</div>`,
    { status, headers: { "content-type": "text/html; charset=utf-8" } },
  );

const stripe = async (path: string, form: Record<string, string>) => {
  const body = new URLSearchParams(form);
  const r = await fetch("https://api.stripe.com/v1/" + path, {
    method: "POST",
    headers: {
      Authorization: "Bearer " + STRIPE_KEY,
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body,
  });
  return { ok: r.ok, data: await r.json() };
};

serve(async (req) => {
  const url = new URL(req.url);
  const token = (url.searchParams.get("t") || "").trim();

  if (!liveKey()) {
    return page("Not available", "<p>Payout setup is not configured. Please contact us.</p>", 503);
  }
  if (!/^[a-f0-9]{32}$/.test(token)) {
    return page("Link not recognised", "<p>This payout link is not valid. Please use the link we sent you.</p>", 404);
  }

  const db = createClient(SUPABASE_URL, SUPABASE_SERVICE_KEY);
  const { data: partner, error } = await db
    .from("partners")
    .select("id, name, contact_email, stripe_connect_account_id")
    .eq("onboarding_token", token)
    .maybeSingle();

  if (error) return page("Something went wrong", "<p>Please try the link again in a moment.</p>", 500);
  if (!partner) {
    return page("Link not recognised", "<p>This payout link is not valid. Please use the link we sent you.</p>", 404);
  }

  // The partner has come back from Stripe. Stripe does not tell us here
  // whether they finished, so say what is true and let the accrual/payout
  // job be the judge.
  if (url.searchParams.get("done")) {
    return page(
      "Thank you",
      "<p>Your details are with Stripe. If anything else is needed they will ask you for it, " +
        "and you can come back to this same link at any time to check or change your details.</p>" +
        "<p>Commission is worked out monthly and paid to the account you just set up.</p>",
    );
  }

  let account = partner.stripe_connect_account_id;
  if (!account) {
    const created = await stripe("accounts", {
      type: "express",
      country: "GB",
      email: partner.contact_email || "",
      business_type: "individual",
      "capabilities[transfers][requested]": "true",
      "metadata[partner_id]": partner.id,
    });
    if (!created.ok || !created.data?.id) {
      return page("Something went wrong", "<p>We could not start the payout setup. Please let us know.</p>", 502);
    }
    account = created.data.id as string;
    // Store it before redirecting: if the partner abandons onboarding, the
    // next visit must reuse this account rather than orphan it and make a
    // second one.
    await db.from("partners").update({ stripe_connect_account_id: account }).eq("id", partner.id);
  }

  const back = `${SITE}/payouts/${token}`;
  const link = await stripe("account_links", {
    account,
    refresh_url: back,
    return_url: back + "?done=1",
    type: "account_onboarding",
  });
  if (!link.ok || !link.data?.url) {
    return page("Something went wrong", "<p>We could not open the payout setup. Please try again shortly.</p>", 502);
  }

  return Response.redirect(link.data.url as string, 302);
});
