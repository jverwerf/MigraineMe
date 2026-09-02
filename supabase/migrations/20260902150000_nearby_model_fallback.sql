-- The model steps of the waterfall need three things the Google-only version
-- did not: where a listing came from, when we last tried to enrich it (so a
-- practice with no findable website is not searched for again every night),
-- and whether their own site says they treat migraine, which is the only
-- honest source for that and what the list is ranked on.

alter table public.nearby_places
  add column if not exists source text not null default 'google',
  add column if not exists enriched_at timestamptz,
  add column if not exists treats_migraine boolean,
  add column if not exists migraine_note text;

alter table public.nearby_api_usage
  add column if not exists model_calls integer not null default 0;

create index if not exists nearby_places_enrich_idx
  on public.nearby_places (enriched_at nulls first) where delisted_at is null;
