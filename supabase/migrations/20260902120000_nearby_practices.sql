-- Guidance "near you": practices we find around a user, shown under our own
-- practitioner cards in the same list.
--
-- Deliberately NOT rows in `practitioners`. Everything there is someone with an
-- account, RLS policies and a consent relationship; a scraped listing is none
-- of those, and mixing them would put unvetted rows behind the policies that
-- gate a patient's diary.
--
-- No client ever reads these tables. The app calls the guidance-nearby edge
-- function, which runs as service role, so RLS is on with no policies at all.

-- One row per grid cell we have ever searched. Scheduling lives here; the
-- places themselves are found by bounding box, not by cell, so a practice on a
-- cell boundary is still found by the person standing next to it.
create table if not exists public.nearby_areas (
  cell_key          text primary key,
  lat               double precision not null,
  lng               double precision not null,
  last_searched_at  timestamptz,
  last_requested_at timestamptz not null default now(),
  request_count     integer not null default 0,
  created_at        timestamptz not null default now()
);

-- The daily refresh reads this: cells someone actually opened recently, oldest
-- search first.
create index if not exists nearby_areas_refresh_idx
  on public.nearby_areas (last_searched_at nulls first, last_requested_at desc);

create table if not exists public.nearby_places (
  id                     uuid primary key default gen_random_uuid(),
  -- Google's permanent identifier. The one field their terms let us keep
  -- indefinitely, and what every refresh reconciles against.
  place_id               text not null unique,
  discovered_in_cell     text references public.nearby_areas(cell_key) on delete set null,
  disciplines            text[] not null default '{}',
  name                   text not null,
  address                text,
  city                   text,
  lat                    double precision not null,
  lng                    double precision not null,
  types                  text[] not null default '{}',
  business_status        text,

  -- Per-field provenance. Only google-sourced contact details carry the 30 day
  -- licence clock, so expiry is decided per field and not per row.
  phone                  text,
  phone_source           text,
  phone_fetched_at       timestamptz,
  website                text,
  website_source         text,
  website_fetched_at     timestamptz,
  description            text,
  description_source     text,
  description_fetched_at timestamptz,

  -- A search that flaps should not delist anything. Two consecutive misses do.
  miss_count             integer not null default 0,
  delisted_at            timestamptz,
  first_seen_at          timestamptz not null default now(),
  last_seen_at           timestamptz not null default now()
);

-- Reads are always "live listings inside this bounding box".
create index if not exists nearby_places_bbox_idx
  on public.nearby_places (lat, lng) where delisted_at is null;

-- The refresh pass looks for google-sourced contact details about to expire.
create index if not exists nearby_places_website_expiry_idx
  on public.nearby_places (website_fetched_at) where website_source = 'google';
create index if not exists nearby_places_phone_expiry_idx
  on public.nearby_places (phone_fetched_at) where phone_source = 'google';

-- What we search for, per discipline. A table so adding one is an insert and
-- never a release, the same rule the practitioner cards follow.
create table if not exists public.nearby_disciplines (
  key      text primary key,
  query    text not null,
  label    text not null,
  sort     integer not null default 0,
  enabled  boolean not null default true
);

-- Migraine-led on purpose: Google matches the query against listing names,
-- categories and, loosely, reviews, so the direct migraine queries come first
-- and every discipline carries the word. Google narrows; the practice's own
-- website, read by the model, is what decides whether a listing is shown.
insert into public.nearby_disciplines (key, query, label, sort) values
  ('migraine',      'migraine clinic',                       'Migraine clinic',       10),
  ('specialist',    'migraine specialist',                   'Migraine specialist',   20),
  ('headache',      'headache clinic',                       'Headache clinic',       30),
  ('neurologist',   'neurologist migraine headache',         'Neurologist',           40),
  ('physio',        'physiotherapist migraine headache',     'Physiotherapist',       50),
  ('osteopath',     'osteopath migraine headache',           'Osteopath',             60),
  ('nutrition',     'nutritional therapist migraine',        'Nutritional therapist', 70),
  ('psychologist',  'psychologist migraine',                 'Psychologist',          80),
  ('acupuncture',   'acupuncturist migraine',                'Acupuncturist',         90)
on conflict (key) do nothing;

-- The free-tier counter. Google bills a whole request at the highest tier any
-- requested field belongs to. Name and address put us on Pro (5000 free calls a
-- month); asking for phone and website puts the same call on Enterprise (1000
-- free). Past the Enterprise allowance the function keeps searching on Pro and
-- fills contact details from free sources instead of billing.
create table if not exists public.nearby_api_usage (
  month             date primary key,
  enterprise_calls  integer not null default 0,
  pro_calls         integer not null default 0,
  updated_at        timestamptz not null default now()
);

alter table public.nearby_areas       enable row level security;
alter table public.nearby_places      enable row level security;
alter table public.nearby_disciplines enable row level security;
alter table public.nearby_api_usage   enable row level security;
