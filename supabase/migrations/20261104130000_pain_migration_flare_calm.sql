-- Chronic support for "How your pain moves": where the pain sits on FLARE
-- days vs the calmer logged days of the same long attack. One reading a day is
-- enough for this half, which is why it exists: the movement half (onset,
-- spread, minutes to peak) needs several entries within hours and a chronic
-- user logs about one.
--
-- Written by compute-correlation-stats. Rules baked into the computation:
--   * flare day = day max >= attack median + 2 (plus onset), the same split
--     the trigger table uses; calm = the attack's other LOGGED days
--   * >= 3 flare days and >= 3 calm days with a location, or both arrays
--     stay empty and flare_days / calm_days stay null
--   * a location is listed when it hurt on at least half of those days
--   * attacks_analysed may now be 0: only the flare-vs-calm half cleared, so
--     clients drop the "across N attacks" line
alter table public.pain_migration_stats
  add column if not exists flare_locations text[] not null default '{}',
  add column if not exists calm_locations text[] not null default '{}',
  add column if not exists flare_days integer,
  add column if not exists calm_days integer;
