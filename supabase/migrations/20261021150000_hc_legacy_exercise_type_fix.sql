-- Health Connect exercise names written by MigraineMe Android before the
-- EXERCISE_TYPE_NAMES fix used a shifted number table (walk = WHEELCHAIR,
-- run = SAILING). Old builds wrote UPPERCASE, fixed builds write lowercase,
-- so an UPPERCASE health_connect name is always a legacy one. This table
-- maps each legacy name to what Health Connect actually meant.
create table if not exists public.hc_legacy_exercise_type_map (
  legacy_name text primary key,
  fixed_name text not null
);
alter table public.hc_legacy_exercise_type_map enable row level security;
insert into public.hc_legacy_exercise_type_map (legacy_name, fixed_name) values
    ('BACK_EXTENSION','other'),
    ('BADMINTON','badminton'),
    ('BARBELL_SHOULDER_PRESS','other'),
    ('BASEBALL','baseball'),
    ('BASKETBALL','basketball'),
    ('BIKING','cycling'),
    ('BIKING_STATIONARY','cycling'),
    ('BOOT_CAMP','boot_camp'),
    ('BOXING','other'),
    ('BURPEE','cricket'),
    ('CALISTHENICS','dance'),
    ('CRICKET','other'),
    ('CRUNCH','other'),
    ('DANCING','other'),
    ('DEADLIFT','other'),
    ('ELLIPTICAL','other'),
    ('FENCING','other'),
    ('FOOTBALL_AMERICAN','elliptical'),
    ('FOOTBALL_AUSTRALIAN','exercise_class'),
    ('GOLF','football_american'),
    ('GUIDED_BREATHING','football_australian'),
    ('GYMNASTICS','other'),
    ('HANDBALL','frisbee_disc'),
    ('HIGH_INTENSITY_INTERVAL_TRAINING','golf'),
    ('HIKING','guided_breathing'),
    ('ICE_HOCKEY','gymnastics'),
    ('ICE_SKATING','handball'),
    ('JUMPING_JACK','hiking'),
    ('LAT_PULL_DOWN','skating'),
    ('LUNGE','other'),
    ('MARTIAL_ARTS','other'),
    ('PADDLING','martial_arts'),
    ('PARAGLIDING','other'),
    ('PILATES','watersports'),
    ('PLANK','paragliding'),
    ('RACQUETBALL','pilates'),
    ('ROCK_CLIMBING','other'),
    ('ROLLER_HOCKEY','racquetball'),
    ('ROWING','climbing'),
    ('ROWING_MACHINE','roller_hockey'),
    ('RUGBY','rowing'),
    ('RUNNING','rowing'),
    ('RUNNING_TREADMILL','rugby'),
    ('SAILING','running'),
    ('SCUBA_DIVING','running'),
    ('SKATING','sailing'),
    ('SKIING','scuba_diving'),
    ('SNOWBOARDING','skating'),
    ('SNOWSHOEING','snow_sports'),
    ('SOCCER','snow_sports'),
    ('SOFTBALL','snow_sports'),
    ('SQUASH','soccer'),
    ('SQUAT','softball'),
    ('STAIR_CLIMBING','squash'),
    ('STAIR_CLIMBING_MACHINE','other'),
    ('STRENGTH_TRAINING','stairs'),
    ('STRETCHING','stairs'),
    ('SURFING','strength'),
    ('SWIMMING_OPEN_WATER','stretching'),
    ('SWIMMING_POOL','watersports'),
    ('TABLE_TENNIS','swimming'),
    ('TENNIS','swimming'),
    ('VOLLEYBALL','table_tennis'),
    ('WALKING','tennis'),
    ('WATER_POLO','other'),
    ('WEIGHTLIFTING','volleyball'),
    ('WHEELCHAIR','walking'),
    ('YOGA','water_polo'),
    ('OTHER','other')
on conflict (legacy_name) do update set fixed_name = excluded.fixed_name;

-- activities is the source of truth for a Health Connect session's name.
-- BEFORE: repair an UPPERCASE legacy name sent by an old build.
create or replace function public.hc_fix_legacy_exercise_type()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  fixed text;
begin
  if new.source is distinct from 'health_connect' or new.type is null or new.type !~ '^[A-Z_]+$' then
    return new;
  end if;
  select fixed_name into fixed from public.hc_legacy_exercise_type_map where legacy_name = new.type;
  new.type := coalesce(fixed, lower(new.type));
  return new;
end $$;

drop trigger if exists hc_fix_legacy_exercise_type on public.activities;
create trigger hc_fix_legacy_exercise_type before insert or update of type on public.activities
  for each row execute function public.hc_fix_legacy_exercise_type();

-- AFTER: copy the name onto the same session's HR-zone row. That table
-- lowercases names (fn_auto_category_activity), so a legacy name there
-- can't be told apart from a fixed one; it just follows activities.
-- Old and new builds both write the HR-zone row first, then activities.
create or replace function public.hc_sync_hr_zone_activity_type()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if new.source = 'health_connect' and new.source_measure_id is not null then
    update public.time_in_high_hr_zones_daily
       set activity_type = new.type
     where user_id = new.user_id
       and source = 'health_connect'
       and source_measure_id = new.source_measure_id
       and activity_type is distinct from new.type;
  end if;
  return null;
end $$;

drop trigger if exists hc_sync_hr_zone_activity_type on public.activities;
create trigger hc_sync_hr_zone_activity_type after insert or update of type on public.activities
  for each row execute function public.hc_sync_hr_zone_activity_type();

-- New names count as Exercise in the HR-zone table's category fill.
CREATE OR REPLACE FUNCTION public.fn_auto_category_activity()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    -- Normalize activity_type to lowercase on insert/update
    IF NEW.activity_type IS NOT NULL THEN
        NEW.activity_type := LOWER(NEW.activity_type);
    END IF;

    -- Only auto-fill category if not already set
    IF NEW.category IS NULL AND NEW.activity_type IS NOT NULL THEN
        NEW.category := CASE
            -- Exercise
            WHEN NEW.activity_type IN ('cycling', 'biking', 'biking_stationary', 'running', 'running_treadmill', 
                'walking', 'hiking', 'swimming', 'swimming_open_water', 'swimming_pool', 'yoga', 'exercising',
                'strength_training', 'weightlifting', 'calisthenics', 'boot_camp', 'high_intensity_interval_training',
                'pilates', 'stretching', 'gymnastics', 'elliptical', 'rowing', 'rowing_machine',
                'stair_climbing', 'stair_climbing_machine', 'rock_climbing',
                'back_extension', 'barbell_shoulder_press', 'burpee', 'crunch', 'deadlift', 'jumping_jack',
                'lat_pull_down', 'lunge', 'plank', 'squat',
                'boxing', 'martial_arts', 'fencing',
                'baseball', 'basketball', 'cricket', 'football_american', 'football_australian',
                'golf', 'handball', 'ice_hockey', 'racquetball', 'roller_hockey', 'rugby',
                'soccer', 'softball', 'squash', 'table_tennis', 'tennis', 'volleyball', 'water_polo',
                'badminton', 'dancing', 'ice_skating', 'skating',
                'skiing', 'snowboarding', 'snowshoeing', 'surfing',
                'sailing', 'scuba_diving', 'paddling', 'paragliding',
                'guided_breathing', 'wheelchair', 'activity', 'other',
                -- Health Connect names from EXERCISE_TYPE_NAMES (Android + RN apps)
                'strength', 'hiit', 'stairs', 'dance', 'climbing', 'snow_sports', 'watersports',
                'exercise_class', 'frisbee_disc')
                OR NEW.activity_type LIKE 'other\_%' THEN 'Exercise'
            -- Leisure
            WHEN NEW.activity_type IN ('gaming', 'hobbies', 'reading') THEN 'Leisure'
            -- Screen
            WHEN NEW.activity_type IN ('phone', 'screen time', 'tv / film') THEN 'Screen'
            -- Sleep
            WHEN NEW.activity_type IN ('napping', 'sleeping') THEN 'Sleep'
            -- Social
            WHEN NEW.activity_type IN ('eating out', 'partying', 'socialising') THEN 'Social'
            -- Travel
            WHEN NEW.activity_type IN ('commuting', 'driving') THEN 'Travel'
            -- Work
            WHEN NEW.activity_type IN ('meeting', 'presenting', 'studying', 'working') THEN 'Work'
            ELSE 'Other'
        END;
    END IF;

    RETURN NEW;
END;
$function$;

-- Backfill: rewriting type fires both triggers, fixing activities and then
-- the matching HR-zone rows.
update public.activities set type = type
  where source = 'health_connect' and type ~ '^[A-Z_]+$';

-- One-off, run 2026-10-21 before any fixed build shipped: HR-zone sessions
-- with no activities row still carry a lowercased legacy name. Every
-- health_connect name at that point was legacy, so map them directly.
update public.time_in_high_hr_zones_daily h
   set activity_type = m.fixed_name
  from public.hc_legacy_exercise_type_map m
 where h.source = 'health_connect'
   and h.activity_type = lower(m.legacy_name)
   and not exists (
     select 1 from public.activities a
      where a.user_id = h.user_id and a.source = 'health_connect'
        and a.source_measure_id = h.source_measure_id);
