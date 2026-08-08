-- Commission is now computed from RevenueCat's ACTUAL reported proceeds
-- (per subscription, USD-normalised, already net of store commission and
-- tax) instead of a hardcoded price table. Record where the money came
-- from so a partner statement can be itemised, and make the amount
-- currency-explicit.

alter table public.partner_commission_ledger
  add column currency text not null default 'USD',
  add column country text,
  add column store text;

comment on column public.partner_commission_ledger.net_amount is
  'Proceeds for this period in `currency` (RevenueCat proceeds: gross less store commission and tax). NOT gross.';
