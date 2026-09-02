-- Every nearby table gains app_id so the same edge function serves every app
-- on the platform from one code path: a vertigo listing and a migraine listing
-- are different verdicts about different practices, and a shared MeSeries
-- database holds several apps at once. MigraineMe's own database only ever
-- holds 'migraine', so the default keeps every existing row and query valid.

-- The cell foreign key goes first: it depends on the old primary key, and a
-- cell is scoped by app now anyway, so the function reconciles by
-- (app_id, discovered_in_cell) rather than the database enforcing it.
alter table public.nearby_places drop constraint if exists nearby_places_discovered_in_cell_fkey;

alter table public.nearby_areas
  add column if not exists app_id text not null default 'migraine';
alter table public.nearby_areas drop constraint if exists nearby_areas_pkey;
alter table public.nearby_areas add primary key (app_id, cell_key);

alter table public.nearby_places
  add column if not exists app_id text not null default 'migraine';
alter table public.nearby_places drop constraint if exists nearby_places_place_id_key;
alter table public.nearby_places add constraint nearby_places_app_place_key unique (app_id, place_id);
drop index if exists public.nearby_places_bbox_idx;
create index if not exists nearby_places_bbox_idx
  on public.nearby_places (app_id, lat, lng) where delisted_at is null;

alter table public.nearby_disciplines
  add column if not exists app_id text not null default 'migraine';
alter table public.nearby_disciplines drop constraint if exists nearby_disciplines_pkey;
alter table public.nearby_disciplines add primary key (app_id, key);

-- The Google key and the model key are shared across apps, so the monthly
-- counter stays global on purpose.
