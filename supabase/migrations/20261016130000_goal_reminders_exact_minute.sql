-- Goal reminders go out at the minute the user picked, not just in that hour:
-- the job runs every 5 minutes and sends a time once it is due, up to 14 minutes
-- late (a missed run still catches it; reminders_sent stops repeats).
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
    and ((now() at time zone tz.timezone)::time - rt) between interval '0 minutes' and interval '14 minutes'
    and not exists (
      select 1 from public.practitioner_goal_reminders_sent s
      where s.goal_id = g.id
        and s.local_date = (now() at time zone tz.timezone)::date
        and s.reminder_time = rt
    )
    and not coalesce((
      select bool_or(gp.achieved) from public.goal_daily_progress(g.id, (now() at time zone tz.timezone)::date, (now() at time zone tz.timezone)::date) gp
    ), false);
$function$;

select cron.alter_job(j.jobid, schedule := '*/5 * * * *')
  from cron.job j where j.jobname = 'goal-reminders';
