// supabase/functions/partner-commission-accrual/index.ts
//
// Monthly job: accrue affiliate commission for every user attributed to a
// partner, based on RevenueCat's ACTUAL reported proceeds.
//
// Why the API and not a price table: prices differ per country (a US
// $6.99/mo, a UK £4.99/mo and an AU annual are all different money), and
// the store's cut differs per store. RevenueCat reports, per subscription,
// `total_revenue_in_usd.proceeds` — gross less store commission less tax,
// normalised to USD. That is the real money received, so commission taken
// from it is fair to both sides and needs no hardcoded price or fee.
// It also means Apple's 30% -> 15% Small Business Program change
// (approved ~Sept 2026, see the appstore-small-business-program note)
// flows through automatically with no code edit.
//
// DELTA ACCRUAL: `total_revenue_in_usd` is LIFETIME-to-date for a
// subscription, not per-period. So each run accrues
//   (lifetime proceeds now) - (everything already accrued for that pair)
// which bills each renewal exactly once and never double-counts, even if
// the job is re-run, misses a month, or a partner is added mid-life.
// Negative deltas (refunds/chargebacks) are skipped rather than written
// as negative rows — flagged in the response for manual review.
//
// APP_USER_ID CASING: iOS registers the Supabase user id as an UPPERCASE
// UUID (Swift's uuidString), Android lowercase — the same person can be
// two RevenueCat customers. Both spellings are queried and summed; miss
// this and iPhone subscribers look like they never paid.
//
// Auth: header x-cron-secret must match PARTNER_ACCRUAL_SECRET.
// Manual run: POST {"dryRun": true} to preview without writing.

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const CRON_SECRET = Deno.env.get("PARTNER_ACCRUAL_SECRET")!;
const RC_KEY = Deno.env.get("REVENUECAT_SECRET_KEY")!;
const RC_PROJECT = "ec488213"; // MigraineMe

const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json" },
  });

type RcSubscription = {
  country?: string | null;
  store?: string | null;
  total_revenue_in_usd?: { proceeds?: number; currency?: string } | null;
};

// Sum lifetime proceeds across every subscription RevenueCat holds for
// this user, under either id casing. Returns null if the customer is not
// found at all (vs 0, which means "found but no money yet").
async function fetchProceeds(userId: string): Promise<
  { proceeds: number; country: string | null; store: string | null; found: boolean }
> {
  const variants = [userId.toUpperCase(), userId.toLowerCase()];
  let proceeds = 0;
  let country: string | null = null;
  let store: string | null = null;
  let found = false;

  for (const id of variants) {
    const res = await fetch(
      `https://api.revenuecat.com/v2/projects/${RC_PROJECT}/customers/${id}/subscriptions?limit=50`,
      { headers: { Authorization: `Bearer ${RC_KEY}` } },
    );
    if (res.status === 404) continue; // customer doesn't exist under this casing
    if (!res.ok) throw new Error(`RC ${res.status} for ${id}: ${await res.text()}`);
    found = true;
    const body = await res.json();
    for (const sub of (body.items ?? []) as RcSubscription[]) {
      proceeds += sub.total_revenue_in_usd?.proceeds ?? 0;
      country ??= sub.country ?? null;
      store ??= sub.store ?? null;
    }
    // Uppercase and lowercase can BOTH exist (same human, two devices),
    // so keep going rather than breaking on the first hit.
  }

  return { proceeds: Math.round(proceeds * 100) / 100, country, store, found };
}

serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);
  if ((req.headers.get("x-cron-secret") ?? "") !== CRON_SECRET) {
    return json({ error: "Unauthorized" }, 401);
  }

  const body = await req.json().catch(() => ({}));
  const dryRun = body.dryRun === true;

  const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_KEY);

  const now = new Date();
  const periodStart = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1));
  const periodEnd = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 0));
  const periodStartStr = periodStart.toISOString().slice(0, 10);
  const periodEndStr = periodEnd.toISOString().slice(0, 10);

  const { data: attributions, error: attrErr } = await supabase
    .from("partner_attributions")
    .select("user_id, partner_id, partners(commission_pct, status)");
  if (attrErr) return json({ error: "query_failed", message: attrErr.message }, 500);
  if (!attributions?.length) return json({ ok: true, accrued: 0, message: "No attributions." });

  // Everything accrued so far, per partner+user, to compute the delta.
  const { data: priorRows, error: priorErr } = await supabase
    .from("partner_commission_ledger")
    .select("partner_id, user_id, net_amount")
    .neq("status", "void");
  if (priorErr) return json({ error: "query_failed", message: priorErr.message }, 500);

  const priorByPair = new Map<string, number>();
  for (const r of priorRows ?? []) {
    const k = `${r.partner_id}|${r.user_id}`;
    priorByPair.set(k, (priorByPair.get(k) ?? 0) + Number(r.net_amount));
  }

  const rows: Array<Record<string, unknown>> = [];
  const skipped: Array<Record<string, unknown>> = [];

  for (const attr of attributions) {
    const partner = (attr as any).partners;
    if (!partner || partner.status !== "active") {
      skipped.push({ user_id: attr.user_id, reason: "partner_not_active" });
      continue;
    }

    let rc;
    try {
      rc = await fetchProceeds(attr.user_id);
    } catch (err) {
      // Don't let one bad lookup silently drop a partner's money — surface it.
      skipped.push({ user_id: attr.user_id, reason: "revenuecat_error", detail: String(err) });
      continue;
    }

    if (!rc.found) {
      skipped.push({ user_id: attr.user_id, reason: "no_revenuecat_customer" });
      continue;
    }

    const alreadyAccrued = priorByPair.get(`${attr.partner_id}|${attr.user_id}`) ?? 0;
    const delta = Math.round((rc.proceeds - alreadyAccrued) * 100) / 100;

    if (delta <= 0) {
      skipped.push({
        user_id: attr.user_id,
        reason: delta === 0 ? "nothing_new_this_period" : "negative_delta_refund_review",
        lifetime_proceeds: rc.proceeds,
        already_accrued: alreadyAccrued,
      });
      continue;
    }

    rows.push({
      partner_id: attr.partner_id,
      user_id: attr.user_id,
      period_start: periodStartStr,
      period_end: periodEndStr,
      net_amount: delta,
      currency: "USD",
      country: rc.country,
      store: rc.store,
      commission_amount: Math.round(delta * (partner.commission_pct / 100) * 100) / 100,
    });
  }

  if (dryRun) return json({ ok: true, dryRun: true, wouldAccrue: rows.length, rows, skipped });
  if (!rows.length) return json({ ok: true, accrued: 0, skipped });

  const { error: insertErr } = await supabase
    .from("partner_commission_ledger")
    .upsert(rows, { onConflict: "partner_id,user_id,period_start,period_end", ignoreDuplicates: true });
  if (insertErr) return json({ error: "insert_failed", message: insertErr.message }, 500);

  return json({
    ok: true,
    accrued: rows.length,
    period: `${periodStartStr}..${periodEndStr}`,
    total_commission: Math.round(rows.reduce((s, r) => s + (r.commission_amount as number), 0) * 100) / 100,
    skipped,
  });
});
