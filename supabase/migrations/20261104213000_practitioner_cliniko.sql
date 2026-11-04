-- Practitioner integrations, stage 1: Cliniko.
--
-- A practitioner connects their own Cliniko account (practice-management
-- software) to the dashboard once. From then on a client page can find the
-- matching Cliniko patient, show their sessions, and file a PDF of the board
-- plus a draft treatment note into that patient's record.
--
-- The API key is a credential for THEIR Cliniko account, so no client-side
-- policy reads any of these tables: RLS on, zero policies. The `cliniko`
-- edge function (service role) is the only reader and writer, and it checks
-- the caller's JWT against practitioners.user_id itself.
--
-- Additive only.

create table public.practitioner_integrations (
  id uuid primary key default gen_random_uuid(),
  practitioner_id uuid not null references public.practitioners(id) on delete cascade,
  provider text not null check (provider in ('cliniko')),
  api_key text not null,
  shard text not null,                       -- 'uk2': the API host, from the key's suffix
  web_base text,                             -- 'https://hito-physiotherapy.uk2.cliniko.com', learnt from a pasted patient link
  account_name text,
  user_name text,
  user_email text,
  connected_at timestamptz not null default now(),
  last_used_at timestamptz,
  last_error text,
  unique (practitioner_id, provider)
);
alter table public.practitioner_integrations enable row level security;

-- Which Cliniko patient a shared client is. Matched by the client's account
-- e-mail, or by hand from a pasted patient link when the e-mails differ.
create table public.practitioner_integration_patients (
  link_id uuid not null references public.practitioner_clients(id) on delete cascade,
  provider text not null,
  external_id text not null,
  external_name text,
  external_url text,
  matched_by text not null check (matched_by in ('email', 'manual')),
  updated_at timestamptz not null default now(),
  primary key (link_id, provider)
);
alter table public.practitioner_integration_patients enable row level security;

-- What was filed where, so the dialog can say "last sent 3 days ago".
create table public.practitioner_integration_events (
  id uuid primary key default gen_random_uuid(),
  link_id uuid not null references public.practitioner_clients(id) on delete cascade,
  provider text not null,
  kind text not null,                        -- 'attachment' | 'treatment_note'
  external_id text,
  detail jsonb,
  created_at timestamptz not null default now()
);
alter table public.practitioner_integration_events enable row level security;
create index practitioner_integration_events_link_idx
  on public.practitioner_integration_events(link_id, created_at desc);
