// supabase/functions/partner-commission-accrual/index.ts
//
// Monthly job: for every user tagged to an affiliate partner
// (partner_attributions) whose subscription is currently active
// (premium_status.rc_subscription_status = 'active'), accrue that
// partner's commission for the current calendar month into
// partner_commission_ledger. Idempotent — safe to re-run; re-running
// the same month for the same user is a no-op (unique constraint on
// partner_id + user_id + period_start + period_end).
//
// ASSUMPTION FLAGGED FOR JORDY: net-of-store-fee uses a flat 15% cut for
// native (Play/App Store) products (Apple/Google small-business tier) and
// 0% for web/Stripe products (prod_* ids — Stripe's own processing fee is
// separate and already netted out by RC). Confirm the 15% assumption
// matches your actual Apple/Google account tier before relying on this
// for real payouts — if either account is on the standard 30% tier this
// under-charges the commission.
//
// Auth: header x-cron-secret must match PARTNER_ACCRUAL_SECRET.
// Manual run: POST with {"dryRun": true} to preview without writing.

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const CRON_SECRET = Deno.env.get("PARTNER_ACCRUAL_SECRET")!;

// price GBP, gross of any store/payment fee
const PRODUCT_PRICE: Record<string, number> = {
  migraineme_premium_monthly: 4.99,
  migraineme_premium_annual: 39.99,
  prod_V2Dg6y2Go0nxhG: 4.99, // Stripe web monthly
  prod_V2DjlJuy9w6Zmu: 39.99, // Stripe web annual
};

const isWebProduct = (productId: string) => productId.startsWith("prod_");
const NATIVE_STORE_FEE = 0.15; // ASSUMPTION — see header comment

const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json" },
  });

serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  if ((req.headers.get("x-cron-secret") ?? "") !== CRON_SECRET) {
    return json({ error: "Unauthorized" }, 401);
  }

  const body = await req.json().catch(() => ({}));
  const dryRun = body.dryRun === true;

  const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_KEY);

  // Current calendar month, UTC.
  const now = new Date();
  const periodStart = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1));
  const periodEnd = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 0));
  const periodStartStr = periodStart.toISOString().slice(0, 10);
  const periodEndStr = periodEnd.toISOString().slice(0, 10);

  // Every attributed user with an active paid subscription right now.
  const { data: attributions, error: attrErr } = await supabase
    .from("partner_attributions")
    .select("user_id, partner_id, partners(commission_pct, status)");
  if (attrErr) return json({ error: "query_failed", message: attrErr.message }, 500);
  if (!attributions?.length) return json({ ok: true, accrued: 0, message: "No attributions." });

  const userIds = attributions.map((a) => a.user_id);
  const { data: statuses, error: statusErr } = await supabase
    .from("premium_status")
    .select("user_id, rc_subscription_status, rc_product_id")
    .in("user_id", userIds)
    .eq("rc_subscription_status", "active");
  if (statusErr) return json({ error: "query_failed", message: statusErr.message }, 500);

  const statusByUser = new Map(statuses?.map((s) => [s.user_id, s]) ?? []);

  const rows: Array<Record<string, unknown>> = [];
  const skipped: Array<Record<string, unknown>> = [];

  for (const attr of attributions) {
    const partner = (attr as any).partners;
    if (!partner || partner.status !== "active") {
      skipped.push({ user_id: attr.user_id, reason: "partner_not_active" });
      continue;
    }
    const status = statusByUser.get(attr.user_id);
    if (!status?.rc_product_id) {
      skipped.push({ user_id: attr.user_id, reason: "not_active_subscriber" });
      continue;
    }
    const gross = PRODUCT_PRICE[status.rc_product_id];
    if (gross === undefined) {
      skipped.push({ user_id: attr.user_id, reason: "unknown_product", rc_product_id: status.rc_product_id });
      continue;
    }
    const net = isWebProduct(status.rc_product_id) ? gross : gross * (1 - NATIVE_STORE_FEE);
    const commission = Math.round(net * (partner.commission_pct / 100) * 100) / 100;

    rows.push({
      partner_id: attr.partner_id,
      user_id: attr.user_id,
      period_start: periodStartStr,
      period_end: periodEndStr,
      rc_product_id: status.rc_product_id,
      net_amount: Math.round(net * 100) / 100,
      commission_amount: commission,
    });
  }

  if (dryRun) return json({ ok: true, dryRun: true, wouldAccrue: rows.length, rows, skipped });
  if (!rows.length) return json({ ok: true, accrued: 0, skipped });

  const { error: insertErr } = await supabase
    .from("partner_commission_ledger")
    .upsert(rows, { onConflict: "partner_id,user_id,period_start,period_end", ignoreDuplicates: true });
  if (insertErr) return json({ error: "insert_failed", message: insertErr.message }, 500);

  return json({ ok: true, accrued: rows.length, period: `${periodStartStr}..${periodEndStr}`, skipped });
});
