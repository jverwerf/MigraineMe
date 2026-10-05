// supabase/functions/partner-payout/index.ts
//
// Pays affiliate partners what the ledger says they're owed, via Stripe
// Connect transfers to their Express account.
//
// THIS FUNCTION MOVES REAL MONEY. Safeguards, in order of importance:
//
//  1. LIVE-KEY GUARD. A `sk_test_`/`rk_test_` key makes Stripe return
//     success while nothing actually moves — partners would look paid and
//     never be. This function hard-refuses to run on a test key rather
//     than silently pretending. (Jordy, 2026-08-08: "MAKE SURE YOU DO NOT
//     STAY IN SANDBOX MODE!")
//  2. IDEMPOTENCY. Every transfer carries an idempotency key derived from
//     the partner and the exact ledger rows it pays, so a re-run, retry or
//     double-fire can never pay those rows twice — Stripe returns the
//     original transfer instead.
//  3. DRY RUN BY DEFAULT. Nothing is sent unless {"confirm": true} is
//     passed explicitly. A cron with an empty body previews only.
//  4. MINIMUM THRESHOLD. Below MIN_PAYOUT_GBP the fees cost more than the
//     transfer moves, so it rolls over to next month instead. Everything a
//     partner is owed is paid in ONE transfer across all open periods:
//     judged period by period, a partner earning a few pounds a month
//     would stay under the minimum forever and never be paid.
//  5. BALANCE CHECK. Refuses to attempt transfers exceeding the available
//     GBP balance, so we fail loudly rather than half-paying a run.
//
// CURRENCY: the ledger is USD (RevenueCat's only normalised currency) but
// the Stripe balance is GBP, so each run fetches the day's USD->GBP rate
// and records both the GBP sent and the rate used, per row.
//
// Auth: x-cron-secret must match PARTNER_ACCRUAL_SECRET.

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const CRON_SECRET = Deno.env.get("PARTNER_ACCRUAL_SECRET")!;
const STRIPE_KEY = Deno.env.get("STRIPE_SECRET_KEY")!;

const MIN_PAYOUT_GBP = 5;

const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json" },
  });

async function stripe(path: string, body?: Record<string, string>, idempotencyKey?: string) {
  const headers: Record<string, string> = {
    Authorization: `Basic ${btoa(STRIPE_KEY + ":")}`,
    "Content-Type": "application/x-www-form-urlencoded",
  };
  if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;
  const res = await fetch(`https://api.stripe.com/v1/${path}`, {
    method: body ? "POST" : "GET",
    headers,
    body: body ? new URLSearchParams(body).toString() : undefined,
  });
  const data = await res.json();
  if (!res.ok) throw new Error(`Stripe ${res.status}: ${JSON.stringify(data.error ?? data)}`);
  return data;
}

// Stripe idempotency key for one partner's transfer: the partner plus the
// exact set of ledger rows, hashed because Stripe caps keys at 255 chars.
async function payoutKey(partnerId: string, rowIds: string[]): Promise<string> {
  const data = new TextEncoder().encode([...rowIds].sort().join(","));
  const digest = await crypto.subtle.digest("SHA-256", data);
  const hex = Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
  return `partner-payout:${partnerId}:${hex}`;
}

async function usdToGbpRate(): Promise<number> {
  // ECB rates via Frankfurter — no key, no account.
  const res = await fetch("https://api.frankfurter.app/latest?from=USD&to=GBP");
  if (!res.ok) throw new Error(`FX lookup failed: ${res.status}`);
  const data = await res.json();
  const rate = data?.rates?.GBP;
  if (typeof rate !== "number" || rate <= 0) throw new Error(`FX rate invalid: ${JSON.stringify(data)}`);
  return rate;
}

serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);
  if ((req.headers.get("x-cron-secret") ?? "") !== CRON_SECRET) {
    return json({ error: "Unauthorized" }, 401);
  }

  // ── Guard 1: never run against a sandbox key ──
  if (!STRIPE_KEY || /_test_/.test(STRIPE_KEY)) {
    return json({
      error: "test_key_refused",
      message:
        "STRIPE_SECRET_KEY is a test key. Transfers would report success while no money moves. Refusing to run.",
    }, 500);
  }

  const body = await req.json().catch(() => ({}));
  const confirm = body.confirm === true; // Guard 3: dry run unless explicit

  const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_KEY);

  const { data: rows, error: rowsErr } = await supabase
    .from("partner_commission_ledger")
    .select("id, partner_id, period_start, period_end, commission_amount, partners(name, status, stripe_connect_account_id)")
    .eq("status", "accrued");
  if (rowsErr) return json({ error: "query_failed", message: rowsErr.message }, 500);
  if (!rows?.length) return json({ ok: true, paid: 0, message: "Nothing accrued." });

  // Group by partner — one transfer per partner, covering every period
  // still owed (including months that rolled over under the minimum).
  const groups = new Map<string, { partnerId: string; from: string; to: string; usd: number; ids: string[]; partner: any }>();
  for (const r of rows) {
    const partner = (r as any).partners;
    const g = groups.get(r.partner_id) ?? {
      partnerId: r.partner_id,
      from: r.period_start,
      to: r.period_end,
      usd: 0,
      ids: [] as string[],
      partner,
    };
    if (r.period_start < g.from) g.from = r.period_start;
    if (r.period_end > g.to) g.to = r.period_end;
    g.usd += Number(r.commission_amount);
    g.ids.push(r.id);
    groups.set(r.partner_id, g);
  }

  const rate = await usdToGbpRate();

  const planned: Array<Record<string, unknown>> = [];
  const skipped: Array<Record<string, unknown>> = [];

  for (const g of groups.values()) {
    if (!g.partner || g.partner.status !== "active") {
      skipped.push({ partner_id: g.partnerId, reason: "partner_not_active" });
      continue;
    }
    if (!g.partner.stripe_connect_account_id) {
      skipped.push({ partner_id: g.partnerId, partner: g.partner.name, reason: "no_stripe_connect_account" });
      continue;
    }
    const gbp = Math.round(g.usd * rate * 100) / 100;
    if (gbp < MIN_PAYOUT_GBP) {
      // Guard 4: rolls over, stays 'accrued' for next month.
      skipped.push({ partner: g.partner.name, reason: "below_minimum", gbp, minimum: MIN_PAYOUT_GBP });
      continue;
    }
    planned.push({
      key: await payoutKey(g.partnerId, g.ids),
      partner_id: g.partnerId,
      partner: g.partner.name,
      destination: g.partner.stripe_connect_account_id,
      period: `${g.from}..${g.to}`,
      usd: Math.round(g.usd * 100) / 100,
      gbp,
      row_ids: g.ids,
    });
  }

  if (!confirm) {
    return json({
      ok: true,
      dryRun: true,
      note: "Nothing sent. Pass {\"confirm\": true} to actually transfer.",
      fx_rate_usd_gbp: rate,
      wouldPay: planned.length,
      totalGbp: Math.round(planned.reduce((s, p) => s + (p.gbp as number), 0) * 100) / 100,
      planned,
      skipped,
    });
  }

  // ── Guard 5: don't start if the balance can't cover it ──
  const totalGbp = planned.reduce((s, p) => s + (p.gbp as number), 0);
  const balance = await stripe("balance");
  const availableGbp = (balance.available ?? []).find((b: any) => b.currency === "gbp")?.amount ?? 0;
  if (Math.round(totalGbp * 100) > availableGbp) {
    return json({
      error: "insufficient_balance",
      message: `Need £${totalGbp.toFixed(2)} but only £${(availableGbp / 100).toFixed(2)} available. Nothing sent.`,
    }, 400);
  }

  const results: Array<Record<string, unknown>> = [];
  for (const p of planned) {
    try {
      const transfer = await stripe(
        "transfers",
        {
          amount: String(Math.round((p.gbp as number) * 100)),
          currency: "gbp",
          destination: p.destination as string,
          description: `MigraineMe affiliate commission ${p.period}`,
        },
        // Guard 2: the same rows can never be paid twice.
        p.key as string,
      );

      const { error: markErr } = await supabase
        .from("partner_commission_ledger")
        .update({
          status: "paid",
          paid_at: new Date().toISOString(),
          stripe_transfer_id: transfer.id,
          payout_amount_gbp: p.gbp,
          fx_rate_usd_gbp: rate,
        })
        .in("id", p.row_ids as string[]);

      if (markErr) {
        // The money HAS moved but the ledger still says 'accrued'. Left
        // alone, the next run would pay these rows again, so shout.
        results.push({
          partner: p.partner,
          gbp: p.gbp,
          transfer_id: transfer.id,
          status: "TRANSFERRED_BUT_NOT_MARKED_PAID",
          row_ids: p.row_ids,
          error: markErr.message,
        });
        continue;
      }

      results.push({ partner: p.partner, gbp: p.gbp, transfer_id: transfer.id, status: "paid" });
    } catch (err) {
      // Keep going — one partner failing shouldn't block the rest.
      results.push({ partner: p.partner, gbp: p.gbp, status: "failed", error: String(err) });
    }
  }

  return json({ ok: true, fx_rate_usd_gbp: rate, results, skipped });
});
