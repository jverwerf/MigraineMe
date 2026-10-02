-- Self-set goals (Jordy 2026-10-01): the Monitor "Goals" card has a + so the
-- client can add their own goals next to the ones a practitioner set.
-- Same table, practitioner_id null = set by the client. Only touches the goal
-- objects created earlier today; nothing older is altered.
--
-- New kind daily_count: any free counter ("Drink water, 8 times a day").
-- It is not an exercise, so it stays off Home (Home = exercise goals only).

alter table public.practitioner_goals alter column practitioner_id drop not null;

alter table public.practitioner_goals drop constraint if exists practitioner_goals_kind_check;
alter table public.practitioner_goals add constraint practitioner_goals_kind_check
  check (kind in ('hr_threshold','exercise_count','mindfulness_minutes','daily_count'));

alter table public.practitioner_goals drop constraint if exists practitioner_goals_shape;
alter table public.practitioner_goals add constraint practitioner_goals_shape check (
  (kind = 'hr_threshold'        and threshold_bpm is not null and target_minutes is not null and times_per_week is not null) or
  (kind in ('exercise_count','daily_count') and target_count is not null) or
  (kind = 'mindfulness_minutes' and target_minutes is not null)
);

create index if not exists practitioner_goals_self_idx
  on public.practitioner_goals(user_id) where practitioner_id is null;

-- The client fully manages the goals they set themselves, and only those.
drop policy if exists "client manages own self-set goals" on public.practitioner_goals;
create policy "client manages own self-set goals" on public.practitioner_goals
  for all
  using (user_id = auth.uid() and practitioner_id is null)
  with check (user_id = auth.uid() and practitioner_id is null);

-- Progress: daily_count reads the manual logs like exercise_count.
create or replace function public.goal_daily_progress(p_goal_id uuid, p_from date, p_to date)
returns table (day date, value numeric, achieved boolean, is_estimate boolean)
language sql
stable
security invoker
set search_path = public
as $$
  with g as (
    select * from public.practitioner_goals where id = p_goal_id
  ),
  days as (
    select d::date as day from generate_series(p_from, p_to, interval '1 day') d
  )
  select
    days.day,
    case
      when g.kind in ('exercise_count','daily_count') then coalesce(l.count, 0)::numeric
      when g.kind = 'mindfulness_minutes' then coalesce(m.duration_minutes, 0) + coalesce(l.minutes, 0)
      when g.kind = 'hr_threshold' then coalesce(h.longest_run_minutes, 0)
    end as value,
    case
      when g.kind in ('exercise_count','daily_count') then coalesce(l.count, 0) >= g.target_count
      when g.kind = 'mindfulness_minutes' then coalesce(m.duration_minutes, 0) + coalesce(l.minutes, 0) >= g.target_minutes
      when g.kind = 'hr_threshold' then coalesce(h.longest_run_minutes, 0) >= g.target_minutes
    end as achieved,
    coalesce(h.is_estimate, false) as is_estimate
  from g
  cross join days
  left join lateral (
    select sum(count) as count, sum(minutes) as minutes
    from public.practitioner_goal_logs
    where goal_id = g.id and date = days.day
  ) l on true
  left join lateral (
    select sum(duration_minutes) as duration_minutes
    from public.mindfulness_daily
    where user_id = g.user_id and date = days.day
  ) m on g.kind = 'mindfulness_minutes'
  left join lateral (
    select longest_run_minutes, is_estimate
    from public.hr_threshold_daily
    where user_id = g.user_id and date = days.day and threshold_bpm = g.threshold_bpm
    order by is_estimate asc, longest_run_minutes desc
    limit 1
  ) h on g.kind = 'hr_threshold'
  order by days.day;
$$;

-- Reminder targets now say whether a practitioner set the goal, so the push
-- title can read "A reminder from your practitioner" or "Your goal reminder".
drop function if exists public.goal_reminder_targets();
create function public.goal_reminder_targets()
returns table (
  goal_id        uuid,
  user_id        uuid,
  token          text,
  lang           text,
  kind           text,
  title          text,
  target_count   int,
  target_minutes numeric,
  local_date     date,
  reminder_time  time,
  from_practitioner boolean
)
language sql
security definer
set search_path = public
as $$
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
    );
$$;
revoke all on function public.goal_reminder_targets() from public, anon, authenticated;
grant execute on function public.goal_reminder_targets() to service_role;
