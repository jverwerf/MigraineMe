-- Payout tracking. Commission is accrued in USD (RevenueCat's only
-- normalised currency) but Andlane's Stripe settles GBP, so record the
-- converted amount AND the rate used — otherwise a partner statement
-- can never be reconciled six months later.

alter table public.partner_commission_ledger
  add column paid_at timestamptz,
  add column stripe_transfer_id text,
  add column payout_amount_gbp numeric,
  add column fx_rate_usd_gbp numeric;

-- One Stripe transfer covers many ledger rows; this makes "what did we
-- actually send, and for which rows" answerable.
create index partner_commission_ledger_transfer_idx
  on public.partner_commission_ledger(stripe_transfer_id)
  where stripe_transfer_id is not null;
