-- Phone-behaviour metrics: percent brightness, screen-on-only samples,
-- time-weighted dark-mode hours.
--
-- Three bugs made these metrics fire prodromes that had nothing to do with the
-- user's behaviour:
--   1. phone_brightness_samples.value held Android's raw 0-255 setting while the
--      thresholds (low 20 / high 80) and every label were on a 0-100 scale.
--   2. Samples were taken hourly around the clock. The 8-10 readings taken while
--      the phone slept sat at the adaptive-brightness floor, so the daily mean
--      fell under "Brightness low" before the user had touched the phone, and
--      every dark-mode sample was really the scheduled night theme.
--   3. aggregate_phone_dark_mode extrapolated (dark samples / samples) * 24, so a
--      single night sample became "24 hours of dark mode".
--
-- The apps now sample only while the screen is on and send brightness as a
-- percentage; those rows are marked screen_on = true. Rows without the flag are
-- legacy: sampled around the clock, value in raw 0-255. The aggregates below
-- normalise legacy values and prefer screen-on rows whenever a day has any.

-- ── 1. Sample provenance ────────────────────────────────────────────────────
alter table public.phone_brightness_samples add column if not exists screen_on boolean;
alter table public.phone_dark_mode_samples  add column if not exists screen_on boolean;
alter table public.phone_volume_samples     add column if not exists screen_on boolean;

comment on column public.phone_brightness_samples.screen_on is
  'true = taken while the screen was on by an app build that stores brightness as a 0-100 percentage. null = legacy row: sampled 24/7, value is the raw 0-255 Android setting.';
comment on column public.phone_dark_mode_samples.screen_on is
  'true = taken while the screen was on. null = legacy row, sampled 24/7 including sleep.';
comment on column public.phone_volume_samples.screen_on is
  'true = taken while the screen was on. null = legacy row, sampled 24/7 including sleep.';

-- ── 2. Daily rows record how much of the day was real usage ─────────────────
alter table public.phone_brightness_daily add column if not exists screen_on_samples integer not null default 0;
alter table public.phone_dark_mode_daily  add column if not exists screen_on_samples integer not null default 0;

comment on column public.phone_brightness_daily.screen_on_samples is
  'How many of sample_count were taken with the screen on. Prodrome detection ignores days below 6: a day built from one or two samples is not evidence.';
comment on column public.phone_dark_mode_daily.screen_on_samples is
  'How many of sample_count were taken with the screen on. Prodrome detection ignores days below 6.';

-- ── 3. Brightness: percent, screen-on preferred ─────────────────────────────
drop function if exists public.aggregate_phone_brightness(uuid, text, text);
create function public.aggregate_phone_brightness(p_user_id uuid, p_date text, p_timezone text)
returns table(value_mean double precision, value_max smallint, sample_count integer, screen_on_samples integer)
language sql
stable
as $function$
    with s as (
        select
            coalesce(screen_on, false) as on_screen,
            case when screen_on is true
                 then value::double precision
                 else value::double precision * 100.0 / 255.0
            end as pct
        from phone_brightness_samples
        where user_id = p_user_id
          and (sampled_at at time zone p_timezone)::date = p_date::date
    ),
    picked as (
        select pct from s where on_screen
        union all
        select pct from s where not exists (select 1 from s where on_screen)
    )
    select
        (select avg(pct) from picked)::double precision,
        (select round(max(pct)) from picked)::smallint,
        (select count(*) from s)::int,
        (select count(*) from s where on_screen)::int;
$function$;

-- ── 4. Dark mode: hours of screen-on time, time-weighted ────────────────────
-- Each sample carries the time until the next one, capped at an hour, so a gap
-- left by a killed worker can never be inflated into a full day.
drop function if exists public.aggregate_phone_dark_mode(uuid, text, text);
create function public.aggregate_phone_dark_mode(p_user_id uuid, p_date text, p_timezone text)
returns table(value_hours double precision, dark_samples integer, sample_count integer, screen_on_samples integer)
language sql
stable
as $function$
    with s as (
        select sampled_at, is_dark, coalesce(screen_on, false) as on_screen
        from phone_dark_mode_samples
        where user_id = p_user_id
          and (sampled_at at time zone p_timezone)::date = p_date::date
    ),
    picked as (
        select sampled_at, is_dark from s where on_screen
        union all
        select sampled_at, is_dark from s where not exists (select 1 from s where on_screen)
    ),
    weighted as (
        select
            is_dark,
            least(
                coalesce(
                    extract(epoch from (lead(sampled_at) over (order by sampled_at) - sampled_at)),
                    extract(epoch from (((p_date::date + 1)::timestamp at time zone p_timezone) - sampled_at))
                ),
                3600.0
            ) as secs
        from picked
    )
    select
        coalesce((select sum(secs) filter (where is_dark) from weighted), 0.0) / 3600.0,
        (select count(*) from picked where is_dark)::int,
        (select count(*) from s)::int,
        (select count(*) from s where on_screen)::int;
$function$;

grant execute on function public.aggregate_phone_brightness(uuid, text, text) to authenticated, service_role;
grant execute on function public.aggregate_phone_dark_mode(uuid, text, text)  to authenticated, service_role;

-- ── 5. Existing brightness history onto the percent scale ───────────────────
-- Every stored daily row predates the fix, so all of them are raw 0-255.
update public.phone_brightness_daily
   set value_mean = value_mean * 100.0 / 255.0,
       value_max  = round(value_max * 100.0 / 255.0),
       updated_at = now()
 where screen_on_samples = 0
   and value_mean is not null;

-- ── 6. Thresholds and units that the unit change invalidates ────────────────
-- Dark mode used to be a share of the 24-hour day; it is now hours of screen-on
-- use, which no phone reaches 14 of. Only untouched defaults are moved.
update public.user_prodromes set default_threshold = 4   where label = 'Dark mode high' and default_threshold = 14;
update public.user_prodromes set default_threshold = 0.5 where label = 'Dark mode low'  and default_threshold = 2;
update public.user_prodromes set unit = '%' where label in ('Brightness high', 'Brightness low') and coalesce(unit, '') = '';
update public.prodrome_templates set default_threshold = 4   where label = 'Dark mode high' and default_threshold = 14;
update public.prodrome_templates set default_threshold = 0.5 where label = 'Dark mode low'  and default_threshold = 2;
update public.prodrome_templates set unit = '%' where label in ('Brightness high', 'Brightness low') and coalesce(unit, '') = '';
