-- Affiliate/referral programme: partners, attribution, commission ledger.
-- Attribution model: a partner promo code redemption permanently tags a
-- user to a partner (first-touch, one row per user). The existing
-- revenuecat-webhook already mirrors that user's ongoing subscription
-- status into premium_status regardless of store, so a monthly job can
-- join partner_attributions x premium_status to accrue commission
-- without needing a separate web checkout per renewal.

create table public.partners (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  contact_email text not null,
  jurisdiction text,
  commission_pct numeric not null default 15.0,
  status text not null default 'pending' check (status in ('pending', 'active', 'paused', 'terminated')),
  stripe_connect_account_id text,
  notes text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.promo_codes
  add column partner_id uuid references public.partners(id);

create table public.partner_attributions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) unique,
  partner_id uuid not null references public.partners(id),
  promo_code_id uuid not null references public.promo_codes(id),
  attributed_at timestamptz not null default now()
);

create index partner_attributions_partner_id_idx on public.partner_attributions(partner_id);

create table public.partner_commission_ledger (
  id uuid primary key default gen_random_uuid(),
  partner_id uuid not null references public.partners(id),
  user_id uuid not null references auth.users(id),
  period_start date not null,
  period_end date not null,
  rc_product_id text,
  net_amount numeric not null,
  commission_amount numeric not null,
  status text not null default 'accrued' check (status in ('accrued', 'paid', 'void')),
  created_at timestamptz not null default now(),
  unique (partner_id, user_id, period_start, period_end)
);

create index partner_commission_ledger_partner_id_idx on public.partner_commission_ledger(partner_id);
create index partner_commission_ledger_status_idx on public.partner_commission_ledger(status) where status = 'accrued';

alter table public.partners enable row level security;
alter table public.partner_attributions enable row level security;
alter table public.partner_commission_ledger enable row level security;

-- Service-role only (edge functions / admin scripts). No end-user or
-- partner-facing client reads these tables directly yet.
create policy "service role full access" on public.partners for all using (auth.role() = 'service_role');
create policy "service role full access" on public.partner_attributions for all using (auth.role() = 'service_role');
create policy "service role full access" on public.partner_commission_ledger for all using (auth.role() = 'service_role');
