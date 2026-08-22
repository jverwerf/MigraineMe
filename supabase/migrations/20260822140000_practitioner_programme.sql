-- Practitioner programme, stage 1: the schema.
--
-- A practitioner is a partner (public.partners already carries the promo
-- code, the attribution and the commission ledger) with three things bolted
-- on: a public profile for the directory, a login for the dashboard, and a
-- consented, revocable link to individual clients.
--
-- Nothing here charges anybody. The commercial fields on partners are left
-- exactly as they are; practitioners are onboarded with commission_pct 0
-- until there is a decision to make.
--
-- Additive only. No existing table is altered and no existing policy changes.

-- ---------------------------------------------------------------- profile

create table public.practitioners (
  id uuid primary key default gen_random_uuid(),

  -- The commercial identity. One partner row per practitioner, so the
  -- existing code / attribution / ledger machinery applies unchanged.
  partner_id uuid not null unique references public.partners(id),

  -- The dashboard login. Null until they have actually signed up: we list
  -- practitioners before they ever log in.
  user_id uuid unique references auth.users(id),

  -- Directory identity.
  slug text not null unique,
  display_name text not null,
  practice_name text,
  discipline text not null,          -- 'nutritional_therapist', 'psychologist', 'physiotherapist', ...
  photo_url text,
  website text,
  languages text[] not null default '{}',
  country text,                      -- ISO-3166-1 alpha-2
  city text,
  consult_mode text not null default 'both'
    check (consult_mode in ('in_person', 'online', 'both')),

  -- What they chose to be. Drives the directory card and whether the app
  -- offers a Book button at all.
  listing_mode text not null default 'listed'
    check (listing_mode in ('bookable', 'listed', 'code_only')),

  -- Founding practitioners are permanently exempt from whatever gets
  -- charged later. Claire and Stephanie.
  founding boolean not null default false,

  -- Vetting. Collected at signup, checked by hand against a public register
  -- where one exists, and the registration is shown on their card.
  registration_body text,
  registration_number text,
  qualification text,
  insurance_provider text,
  insurance_expiry date,
  vetted_at timestamptz,
  vetted_by text,

  -- Their own declaration that they are allowed to do what they picked.
  terms_accepted_at timestamptz,
  permitted_declared_at timestamptz,

  status text not null default 'pending'
    check (status in ('pending', 'active', 'paused', 'retired')),

  notes text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index practitioners_status_idx on public.practitioners(status) where status = 'active';
create index practitioners_country_idx on public.practitioners(country);
create index practitioners_insurance_expiry_idx on public.practitioners(insurance_expiry)
  where insurance_expiry is not null;

-- Card copy, one row per language. The practitioner writes their own in
-- their own language; the rest are translations. Kept out of the parent so
-- adding a language is data, not a schema change.
create table public.practitioner_bios (
  practitioner_id uuid not null references public.practitioners(id) on delete cascade,
  lang text not null,
  headline text,
  bio text,
  treats text[] not null default '{}',   -- what they help with, shown as the card's list
  is_source boolean not null default false,
  updated_at timestamptz not null default now(),
  primary key (practitioner_id, lang)
);

-- ------------------------------------------------------- the relationship

-- One row per practitioner-client pair. `scopes` is the live consent: what
-- this practitioner may read about this client, right now. It lives here
-- rather than in a child table because every report query tests it, and an
-- array membership check keeps that policy cheap. Changes are audited
-- separately below.
create table public.practitioner_clients (
  id uuid primary key default gen_random_uuid(),
  practitioner_id uuid not null references public.practitioners(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,

  status text not null default 'pending'
    check (status in ('pending', 'active', 'declined', 'revoked')),

  initiated_by text not null default 'client'
    check (initiated_by in ('client', 'practitioner')),

  -- What she asked for, and what the client actually granted. Kept apart so
  -- the dashboard can tell "she never asked" from "the client said no".
  requested_scopes text[] not null default '{}',
  scopes text[] not null default '{}',

  connected_at timestamptz,
  revoked_at timestamptz,
  last_viewed_at timestamptz,          -- drives "Claire last looked on Tuesday"
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),

  unique (practitioner_id, user_id)
);

create index practitioner_clients_user_idx on public.practitioner_clients(user_id);
create index practitioner_clients_active_idx
  on public.practitioner_clients(practitioner_id) where status = 'active';

-- Every consent change, kept forever. This is the record that says the
-- client granted it, when, and from where.
create table public.practitioner_consent_events (
  id uuid primary key default gen_random_uuid(),
  link_id uuid not null references public.practitioner_clients(id) on delete cascade,
  action text not null
    check (action in ('requested', 'granted', 'narrowed', 'widened', 'revoked', 'declined')),
  scopes_before text[] not null default '{}',
  scopes_after text[] not null default '{}',
  actor text not null check (actor in ('client', 'practitioner', 'system')),
  surface text,                        -- 'android', 'ios', 'web'
  created_at timestamptz not null default now()
);

create index practitioner_consent_events_link_idx
  on public.practitioner_consent_events(link_id, created_at desc);

-- Every time a practitioner opens a client's data. Needed twice over: the
-- client is told when they were last looked at, and we can answer who saw
-- what if we are ever asked.
create table public.practitioner_access_log (
  id bigserial primary key,
  practitioner_id uuid not null references public.practitioners(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  scopes text[] not null default '{}',
  viewed_at timestamptz not null default now()
);

create index practitioner_access_log_user_idx
  on public.practitioner_access_log(user_id, viewed_at desc);

-- ---------------------------------------------------------- appointments

-- Stage 4's initial contact. A request, never a self-booking: she accepts
-- or declines. No money passes through this table and none is planned to.
create table public.practitioner_appointment_requests (
  id uuid primary key default gen_random_uuid(),
  practitioner_id uuid not null references public.practitioners(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,

  kind text not null default 'initial'
    check (kind in ('initial', 'followup')),
  message text,
  preferred_times text,                -- free text until released slots exist

  status text not null default 'requested'
    check (status in ('requested', 'accepted', 'declined', 'cancelled', 'expired')),
  response_note text,
  scheduled_for timestamptz,

  created_at timestamptz not null default now(),
  responded_at timestamptz
);

create index practitioner_appointment_requests_practitioner_idx
  on public.practitioner_appointment_requests(practitioner_id, status);
create index practitioner_appointment_requests_user_idx
  on public.practitioner_appointment_requests(user_id, created_at desc);

-- ------------------------------------------------------- dashboard layout

-- Her default card layout, and a per-client override. user_id null is the
-- default; a row with a user_id overrides it for that client only.
create table public.practitioner_view_prefs (
  id uuid primary key default gen_random_uuid(),
  practitioner_id uuid not null references public.practitioners(id) on delete cascade,
  user_id uuid references auth.users(id) on delete cascade,
  layout jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

create unique index practitioner_view_prefs_default_idx
  on public.practitioner_view_prefs(practitioner_id) where user_id is null;
create unique index practitioner_view_prefs_client_idx
  on public.practitioner_view_prefs(practitioner_id, user_id) where user_id is not null;

-- ------------------------------------------------------------------ gate

-- The single place that decides whether the caller may read one scope of
-- one patient's data. Stage 3 adds "or practitioner_can_read(user_id, ...)"
-- to the diary tables' select policies, so the rule lives here once instead
-- of being copied into every policy.
--
-- security definer because it reads practitioner tables the caller has no
-- direct rights to; it returns a boolean and nothing else.
create or replace function public.practitioner_can_read(
  target_user uuid,
  required_scope text
)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1
    from public.practitioner_clients pc
    join public.practitioners p on p.id = pc.practitioner_id
    where pc.user_id = target_user
      and pc.status = 'active'
      and required_scope = any(pc.scopes)
      and p.user_id = auth.uid()
      and p.status = 'active'
  );
$$;

comment on function public.practitioner_can_read is
  'True when the calling practitioner has an active, unrevoked link to target_user carrying required_scope. Used by the diary tables select policies; never grants anything on its own.';

revoke all on function public.practitioner_can_read(uuid, text) from public;
grant execute on function public.practitioner_can_read(uuid, text) to authenticated;

-- ------------------------------------------------------------------- RLS

alter table public.practitioners                     enable row level security;
alter table public.practitioner_bios                 enable row level security;
alter table public.practitioner_clients              enable row level security;
alter table public.practitioner_consent_events       enable row level security;
alter table public.practitioner_access_log           enable row level security;
alter table public.practitioner_appointment_requests enable row level security;
alter table public.practitioner_view_prefs           enable row level security;

-- Service role, for the edge functions and the admin scripts.
create policy "service role full access" on public.practitioners
  for all using (auth.role() = 'service_role');
create policy "service role full access" on public.practitioner_bios
  for all using (auth.role() = 'service_role');
create policy "service role full access" on public.practitioner_clients
  for all using (auth.role() = 'service_role');
create policy "service role full access" on public.practitioner_consent_events
  for all using (auth.role() = 'service_role');
create policy "service role full access" on public.practitioner_access_log
  for all using (auth.role() = 'service_role');
create policy "service role full access" on public.practitioner_appointment_requests
  for all using (auth.role() = 'service_role');
create policy "service role full access" on public.practitioner_view_prefs
  for all using (auth.role() = 'service_role');

-- The directory is public. Only active practitioners, and only the profile:
-- nothing here touches a client.
create policy "active practitioners are public" on public.practitioners
  for select using (status = 'active');
create policy "bios of active practitioners are public" on public.practitioner_bios
  for select using (
    exists (select 1 from public.practitioners p
            where p.id = practitioner_id and p.status = 'active')
  );

-- A practitioner reads and edits her own profile.
create policy "practitioner reads own row" on public.practitioners
  for select using (user_id = auth.uid());
create policy "practitioner updates own row" on public.practitioners
  for update using (user_id = auth.uid()) with check (user_id = auth.uid());

-- A client sees their own links; a practitioner sees the links to her.
create policy "client reads own links" on public.practitioner_clients
  for select using (user_id = auth.uid());
create policy "practitioner reads own links" on public.practitioner_clients
  for select using (
    exists (select 1 from public.practitioners p
            where p.id = practitioner_id and p.user_id = auth.uid())
  );

-- Only the client changes consent. She can ask; she cannot grant.
create policy "client updates own link" on public.practitioner_clients
  for update using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "client creates own link" on public.practitioner_clients
  for insert with check (user_id = auth.uid());

create policy "client reads own consent history" on public.practitioner_consent_events
  for select using (
    exists (select 1 from public.practitioner_clients pc
            where pc.id = link_id and pc.user_id = auth.uid())
  );

-- The client can always see who looked at their data and when.
create policy "client reads own access log" on public.practitioner_access_log
  for select using (user_id = auth.uid());

create policy "client reads own appointment requests" on public.practitioner_appointment_requests
  for select using (user_id = auth.uid());
create policy "client creates own appointment request" on public.practitioner_appointment_requests
  for insert with check (user_id = auth.uid());
create policy "practitioner reads requests to her" on public.practitioner_appointment_requests
  for select using (
    exists (select 1 from public.practitioners p
            where p.id = practitioner_id and p.user_id = auth.uid())
  );
create policy "practitioner responds to requests to her" on public.practitioner_appointment_requests
  for update using (
    exists (select 1 from public.practitioners p
            where p.id = practitioner_id and p.user_id = auth.uid())
  );

create policy "practitioner manages own view prefs" on public.practitioner_view_prefs
  for all using (
    exists (select 1 from public.practitioners p
            where p.id = practitioner_id and p.user_id = auth.uid())
  );
-- Belt as well as braces: a code_only practitioner told us they do not want
-- clients through the app, so the public policy itself must not expose them.
-- The worker also filters, but a future caller that forgets should get
-- nothing rather than an unwanted listing.
drop policy if exists "active practitioners are public" on public.practitioners;
create policy "listed practitioners are public" on public.practitioners
  for select using (status = 'active' and listing_mode in ('bookable','listed'));

drop policy if exists "bios of active practitioners are public" on public.practitioner_bios;
create policy "bios of listed practitioners are public" on public.practitioner_bios
  for select using (
    exists (select 1 from public.practitioners p
            where p.id = practitioner_id
              and p.status = 'active'
              and p.listing_mode in ('bookable','listed'))
  );
