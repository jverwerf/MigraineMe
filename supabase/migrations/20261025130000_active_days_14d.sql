-- Active day = a launch row in user_activity_daily OR a manual log that day.
-- Returns the caller's number of active days in the last 14 (today included).
-- Used by the Android app to fire the Google Ads "third_open" conversion on the
-- third ACTIVE DAY instead of the third cold launch (Jordy, 25 Oct 2026). The
-- dashboard's trial pivot uses the same definition.
create or replace function public.active_days_14d()
returns integer
language sql
security definer
set search_path to 'public'
stable
as $$
  with w as (
    select (start_at at time zone 'utc')::date d from migraines where user_id = auth.uid() and source in ('app','manual') and start_at >= now() - interval '15 days'
    union select (start_at at time zone 'utc')::date from medicines where user_id = auth.uid() and source = 'manual' and start_at >= now() - interval '15 days'
    union select (start_at at time zone 'utc')::date from triggers where user_id = auth.uid() and source = 'manual' and start_at >= now() - interval '15 days'
    union select (start_at at time zone 'utc')::date from reliefs where user_id = auth.uid() and source = 'manual' and start_at >= now() - interval '15 days'
    union select (created_at at time zone 'utc')::date from prodromes where user_id = auth.uid() and source = 'manual' and created_at >= now() - interval '15 days'
    union select (start_at at time zone 'utc')::date from activities where user_id = auth.uid() and source = 'manual' and start_at >= now() - interval '15 days'
    union select (start_at at time zone 'utc')::date from locations where user_id = auth.uid() and source = 'manual' and start_at >= now() - interval '15 days'
    union select (start_at at time zone 'utc')::date from missed_activities where user_id = auth.uid() and source = 'manual' and start_at >= now() - interval '15 days'
    union select (created_at at time zone 'utc')::date from treatment_side_effect_logs where user_id = auth.uid() and created_at >= now() - interval '15 days'
    union select date from chat_usage where user_id = auth.uid() and date >= current_date - 14
    union select date from user_activity_daily where user_id = auth.uid() and date >= current_date - 14
  )
  select coalesce(count(distinct d), 0)::integer from w where d between current_date - 13 and current_date and auth.uid() is not null;
$$;

grant execute on function public.active_days_14d() to authenticated;
