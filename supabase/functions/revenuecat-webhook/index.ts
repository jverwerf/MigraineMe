// supabase/functions/revenuecat-webhook/index.ts
//
// RevenueCat Webhook Handler
//
// Receives webhook events from RevenueCat and updates the
// premium_status table in Supabase.
//
// Setup in RevenueCat Dashboard:
//   → Project Settings → Integrations → Webhooks
//   → URL: https://<your-project>.supabase.co/functions/v1/revenuecat-webhook
//   → Authorization Header: Bearer <your-webhook-secret>
//
// Deploy: supabase functions deploy revenuecat-webhook --no-verify-jwt
// Secret: supabase secrets set REVENUECAT_WEBHOOK_SECRET=your_secret_here

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const WEBHOOK_SECRET = Deno.env.get("REVENUECAT_WEBHOOK_SECRET") ?? "";

serve(async (req) => {
  // ── Auth ──
  if (WEBHOOK_SECRET) {
    const authHeader = req.headers.get("Authorization") ?? "";
    if (authHeader !== `Bearer ${WEBHOOK_SECRET}`) {
      console.warn("Unauthorized webhook attempt");
      return new Response("Unauthorized", { status: 401 });
    }
  }

  if (req.method !== "POST") {
    return new Response("Method not allowed", { status: 405 });
  }

  try {
    const body = await req.json();
    const event = body.event;

    if (!event) {
      return new Response(JSON.stringify({ error: "No event in body" }), {
        status: 400,
        headers: { "Content-Type": "application/json" },
      });
    }

    // RevenueCat sends app_user_id = your Supabase user_id
    // (set during PremiumManager.initialize → Purchases.logIn(userId))
    const appUserId = event.app_user_id;
    const eventType = body.type;

    if (!appUserId) {
      console.log("No app_user_id in event, skipping");
      return new Response(JSON.stringify({ ok: true, skipped: true }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }

    // Skip RevenueCat anonymous IDs (start with $RCAnonymousID:)
    if (appUserId.startsWith("$RCAnonymousID:")) {
      console.log(`Skipping anonymous user: ${appUserId}`);
      return new Response(JSON.stringify({ ok: true, skipped: true }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }

    console.log(`RevenueCat webhook: type=${eventType}, user=${appUserId}`);

    // ── Determine subscription status ──
    let rcStatus: string;
    const productId = event.product_id ?? null;
    const expirationDate = event.expiration_at_ms
      ? new Date(event.expiration_at_ms).toISOString()
      : null;

    switch (eventType) {
      // User purchased or renewed
      case "INITIAL_PURCHASE":
      case "RENEWAL":
      case "UNCANCELLATION":
      case "PRODUCT_CHANGE":
      case "SUBSCRIBER_ALIAS":
        rcStatus = "active";
        break;

      // User cancelled — still active until expiry date
      case "CANCELLATION":
        rcStatus =
          expirationDate && new Date(expirationDate) > new Date()
            ? "active"
            : "cancelled";
        break;

      // Subscription expired
      case "EXPIRATION":
        rcStatus = "expired";
        break;

      // Payment failed, in grace period
      case "BILLING_ISSUE":
        rcStatus = "grace_period";
        break;

      // Non-subscription events — acknowledge but don't update
      case "NON_RENEWING_PURCHASE":
      case "TRANSFER":
      case "TEST":
        console.log(`Acknowledged event type: ${eventType}`);
        return new Response(JSON.stringify({ ok: true, acknowledged: true }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });

      default:
        console.log(`Unhandled event type: ${eventType}`);
        return new Response(JSON.stringify({ ok: true, unhandled: true }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
    }

    // ── Update Supabase ──
    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);

    // Try update first (row should exist from signup trigger)
    const { data: updated, error: updateErr } = await supabase
      .from("premium_status")
      .update({
        rc_subscription_status: rcStatus,
        rc_product_id: productId,
        rc_expiry_date: expirationDate,
      })
      .eq("user_id", appUserId)
      .select("user_id")
      .maybeSingle();

    if (updateErr) {
      console.error("Supabase update error:", updateErr);
      return new Response(
        JSON.stringify({ error: "DB update failed", detail: updateErr.message }),
        { status: 500, headers: { "Content-Type": "application/json" } },
      );
    }

    // If no row was updated (edge case: row doesn't exist yet), upsert
    if (!updated) {
      const { error: upsertErr } = await supabase
        .from("premium_status")
        .upsert({
          user_id: appUserId,
          rc_subscription_status: rcStatus,
          rc_product_id: productId,
          rc_expiry_date: expirationDate,
        }, { onConflict: "user_id" });

      if (upsertErr) {
        console.error("Upsert fallback error:", upsertErr);
        return new Response(
          JSON.stringify({ error: "DB upsert failed", detail: upsertErr.message }),
          { status: 500, headers: { "Content-Type": "application/json" } },
        );
      }
    }

    console.log(
      `Updated premium_status: user=${appUserId}, status=${rcStatus}, product=${productId}, expires=${expirationDate}`
    );

    return new Response(
      JSON.stringify({ ok: true, status: rcStatus }),
      { status: 200, headers: { "Content-Type": "application/json" } },
    );
  } catch (err) {
    console.error("Webhook error:", err);
    return new Response(
      JSON.stringify({ error: "Internal error", detail: String(err) }),
      { status: 500, headers: { "Content-Type": "application/json" } },
    );
  }
});