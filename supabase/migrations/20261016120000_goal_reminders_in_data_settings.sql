-- Goal reminders live in Data settings: the client sets the times (also on
-- practitioner-set goals), and a goal whose day is already achieved gets no
-- further reminders that day.
create or replace function public.set_goal_reminder_times(goal_id uuid, times time[])
returns void
language plpgsql
security definer
set search_path to 'public'
as $$
declare touched int; clean time[];
begin
  select coalesce(array_agg(x order by x), '{}') into clean
    from (select distinct date_trunc('minute', t)::time x
            from unnest(coalesce(times, '{}'::time[])) t where t is not null) d;
  if coalesce(array_length(clean, 1), 0) > 6 then
    raise exception 'at most 6 reminder times';
  end if;
  update public.practitioner_goals g
     set reminder_times = clean, updated_at = now()
   where g.id = goal_id and g.user_id = auth.uid();
  get diagnostics touched = row_count;
  if touched = 0 then
    raise exception 'no such goal for this user';
  end if;
end;
$$;
revoke all on function public.set_goal_reminder_times(uuid, time[]) from public, anon;
grant execute on function public.set_goal_reminder_times(uuid, time[]) to authenticated;

CREATE OR REPLACE FUNCTION public.goal_reminder_targets()
 RETURNS TABLE(goal_id uuid, user_id uuid, token text, lang text, kind text, title text, target_count integer, target_minutes numeric, local_date date, reminder_time time without time zone, from_practitioner boolean)
 LANGUAGE sql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
  select
    g.id, g.user_id, p.fcm_token, p.lang, g.kind, g.title, g.target_count, g.target_minutes,
    (now() at time zone tz.timezone)::date,
    rt,
    g.practitioner_id is not null
  from public.practitioner_goals g
  join public.profiles p on p.user_id = g.user_id
  join lateral (
    select d.timezone
    from public.user_location_daily d
    where d.user_id = g.user_id and d.timezone is not null
    order by d.date desc
    limit 1
  ) tz on true
  cross join lateral unnest(g.reminder_times) rt
  where g.status = 'active'
    and g.reminders_enabled
    and p.fcm_token is not null
    and exists (select 1 from pg_timezone_names n where n.name = tz.timezone)
    and extract(hour from (now() at time zone tz.timezone)) = extract(hour from rt)
    and not exists (
      select 1 from public.practitioner_goal_reminders_sent s
      where s.goal_id = g.id
        and s.local_date = (now() at time zone tz.timezone)::date
        and s.reminder_time = rt
    )
    and not coalesce((
      select bool_or(gp.achieved) from public.goal_daily_progress(g.id, (now() at time zone tz.timezone)::date, (now() at time zone tz.timezone)::date) gp
    ), false);
$function$
