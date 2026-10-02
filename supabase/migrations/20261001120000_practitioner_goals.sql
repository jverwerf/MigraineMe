-- Practitioner goals. 2026-10-01.
--
-- A linked practitioner (a physio, to start) sets goals for a client. Each goal
-- becomes a Monitor card in the app, an exercise block on Home, optional push
-- reminders, and a progress card on the practitioner dashboard.
--
-- Everything here is NEW. No existing table is altered; the goals read the diary
-- tables that already exist (mindfulness_daily, and the new hr_threshold_daily).
--
-- Three goal kinds, all with practitioner-typed numbers:
--   hr_threshold        heart rate at or above threshold_bpm for target_minutes
--                       in one stretch, times_per_week days a week
--   exercise_count      an exercise (catalogue id) target_count times a day
--   mindfulness_minutes target_minutes of meditation every day
--
-- Consent: setting a goal rides on the active link (Jordy 2026-10-01, no extra
-- tap). Reading progress still obeys the client's consent scopes, because the
-- progress RPC runs as the caller and the metric tables keep their policies.

-- ------------------------------------------------------------------ goals

create table public.practitioner_goals (
  id              uuid primary key default gen_random_uuid(),
  practitioner_id uuid not null references public.practitioners(id) on delete cascade,
  user_id         uuid not null references auth.users(id) on delete cascade,
  kind            text not null check (kind in ('hr_threshold','exercise_count','mindfulness_minutes')),
  -- English, the i18n key convention: known titles are translated in the apps,
  -- a free-typed title is shown as typed.
  title           text not null,
  -- ExerciseCatalogue id for exercise_count goals (e.g. 'chin_tuck'); null otherwise.
  exercise_id     text,
  threshold_bpm   int     check (threshold_bpm between 60 and 220),
  target_minutes  numeric check (target_minutes > 0),
  target_count    int     check (target_count > 0),
  -- null = every day
  times_per_week  int     check (times_per_week between 1 and 7),
  -- Local wall-clock times in the client's timezone, hour precision (HH:00).
  reminder_times  time[] not null default '{}',
  -- The client may mute reminders without touching the goal (RPC below).
  reminders_enabled boolean not null default true,
  status          text not null default 'active' check (status in ('active','paused','ended')),
  note            text,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  ended_at        timestamptz,
  constraint practitioner_goals_shape check (
    (kind = 'hr_threshold'        and threshold_bpm is not null and target_minutes is not null and times_per_week is not null) or
    (kind = 'exercise_count'      and target_count is not null) or
    (kind = 'mindfulness_minutes' and target_minutes is not null)
  )
);

create index practitioner_goals_user_idx
  on public.practitioner_goals(user_id, status);
create index practitioner_goals_practitioner_idx
  on public.practitioner_goals(practitioner_id, user_id, status);

comment on table public.practitioner_goals is
  'Goals a linked practitioner set for a client. One row per goal; the app shows one Monitor card per active row.';

-- ------------------------------------------------------------------- logs

-- What the client did, per goal per day. Manual taps (chin tucks, a meditation
-- the phone did not record) land here. Computed kinds (heart rate) read their
-- metric table directly and never write here.
create table public.practitioner_goal_logs (
  id         uuid primary key default gen_random_uuid(),
  goal_id    uuid not null references public.practitioner_goals(id) on delete cascade,
  user_id    uuid not null references auth.users(id) on delete cascade,
  date       date not null,
  count      int not null default 0 check (count >= 0),
  minutes    numeric not null default 0 check (minutes >= 0),
  source     text not null default 'manual',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (goal_id, date, source)
);

create index practitioner_goal_logs_user_date_idx
  on public.practitioner_goal_logs(user_id, date desc);

-- ---------------------------------------------------- reminders sent log

-- One row per (goal, local day, reminder time) that was pushed, so an hourly
-- cron that runs twice in an hour, or is replayed, cannot double-send.
create table public.practitioner_goal_reminders_sent (
  goal_id       uuid not null references public.practitioner_goals(id) on delete cascade,
  local_date    date not null,
  reminder_time time not null,
  sent_at       timestamptz not null default now(),
  primary key (goal_id, local_date, reminder_time)
);

-- ------------------------------------------------- heart rate threshold

-- Daily summary of "how long was the heart rate at or above N bpm", computed
-- from raw samples the phone (Apple Health / Health Connect) or the Garmin
-- worker already has. One row per user, day, threshold and source; the raw
-- curve never leaves the device. Zone-only wearables (WHOOP, Oura) write an
-- estimate with is_estimate = true.
create table public.hr_threshold_daily (
  id                  uuid primary key default gen_random_uuid(),
  user_id             uuid not null default auth.uid() references auth.users(id) on delete cascade,
  date                date not null,
  threshold_bpm       int  not null check (threshold_bpm between 60 and 220),
  minutes_above       numeric not null default 0 check (minutes_above >= 0),
  longest_run_minutes numeric not null default 0 check (longest_run_minutes >= 0),
  is_estimate         boolean not null default false,
  source              text not null,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  unique (user_id, date, threshold_bpm, source)
);

create index hr_threshold_daily_user_date_idx
  on public.hr_threshold_daily(user_id, date desc);

comment on table public.hr_threshold_daily is
  'Per day: minutes at or above threshold_bpm and the longest continuous stretch. Computed on-device from raw heart rate (never uploaded) or server-side from Garmin samples. Consent scope: heart.';

-- -------------------------------------------------------------- RLS

alter table public.practitioner_goals                enable row level security;
alter table public.practitioner_goal_logs            enable row level security;
alter table public.practitioner_goal_reminders_sent  enable row level security;
alter table public.hr_threshold_daily                enable row level security;

create policy "service role full access" on public.practitioner_goals
  for all using (auth.role() = 'service_role');
create policy "service role full access" on public.practitioner_goal_logs
  for all using (auth.role() = 'service_role');
create policy "service role full access" on public.practitioner_goal_reminders_sent
  for all using (auth.role() = 'service_role');
create policy "service role full access" on public.hr_threshold_daily
  for all using (auth.role() = 'service_role');

-- The practitioner manages goals only for clients with an ACTIVE link to her.
-- Both using and with check, so a revoked link also blocks edits of old goals
-- (they stay readable to the client as history).
create policy "practitioner manages goals for active clients" on public.practitioner_goals
  for all
  using (
    exists (
      select 1
      from public.practitioners p
      join public.practitioner_clients pc on pc.practitioner_id = p.id
      where p.id = practitioner_id
        and p.user_id = auth.uid()
        and p.status = 'active'
        and pc.user_id = practitioner_goals.user_id
        and pc.status = 'active'
    )
  )
  with check (
    exists (
      select 1
      from public.practitioners p
      join public.practitioner_clients pc on pc.practitioner_id = p.id
      where p.id = practitioner_id
        and p.user_id = auth.uid()
        and p.status = 'active'
        and pc.user_id = practitioner_goals.user_id
        and pc.status = 'active'
    )
  );

create policy "client reads own goals" on public.practitioner_goals
  for select using (user_id = auth.uid());

-- Logs: the client owns them; the practitioner who set the goal reads them.
create policy "client manages own goal logs" on public.practitioner_goal_logs
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());

create policy "practitioner reads logs of own goals" on public.practitioner_goal_logs
  for select using (
    exists (
      select 1
      from public.practitioner_goals g
      join public.practitioners p on p.id = g.practitioner_id
      where g.id = goal_id
        and p.user_id = auth.uid()
    )
  );

-- Heart rate threshold rows: like every metric table.
create policy "own rows" on public.hr_threshold_daily
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "consented practitioner reads" on public.hr_threshold_daily
  for select using (public.practitioner_can_read(user_id, 'heart'));

-- ------------------------------------------------------------- RPCs

-- The client mutes or unmutes reminders on one of their goals. The only client
-- write to practitioner_goals, kept to this one column.
create or replace function public.set_goal_reminders(goal_id uuid, enabled boolean)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare touched int;
begin
  update public.practitioner_goals g
     set reminders_enabled = enabled, updated_at = now()
   where g.id = goal_id and g.user_id = auth.uid();
  get diagnostics touched = row_count;
  if touched = 0 then
    raise exception 'no such goal for this user';
  end if;
end;
$$;
revoke all on function public.set_goal_reminders(uuid, boolean) from public;
grant execute on function public.set_goal_reminders(uuid, boolean) to authenticated;

-- Hourly reminder targets: every active goal with a reminder at this local
-- hour for the client, not yet sent today, with a push token. Timezone rule
-- copied from evening_checkin_targets (latest user_location_daily.timezone).
create or replace function public.goal_reminder_targets()
returns table (
  goal_id       uuid,
  user_id       uuid,
  token         text,
  lang          text,
  kind          text,
  title         text,
  target_count  int,
  target_minutes numeric,
  local_date    date,
  reminder_time time
)
language sql
security definer
set search_path = public
as $$
  select
    g.id, g.user_id, p.fcm_token, p.lang, g.kind, g.title, g.target_count, g.target_minutes,
    (now() at time zone tz.timezone)::date,
    rt
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

-- Progress per day for one goal, as the caller (RLS applies: a practitioner
-- without the heart scope gets no rows for a heart goal, which the dashboard
-- renders as "not shared"). value = count for exercise goals, minutes for the
-- others; achieved = that day met the goal's own target.
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
    case g.kind
      when 'exercise_count' then coalesce(l.count, 0)::numeric
      when 'mindfulness_minutes' then coalesce(m.duration_minutes, 0) + coalesce(l.minutes, 0)
      when 'hr_threshold' then coalesce(h.longest_run_minutes, 0)
    end as value,
    case g.kind
      when 'exercise_count' then coalesce(l.count, 0) >= g.target_count
      when 'mindfulness_minutes' then coalesce(m.duration_minutes, 0) + coalesce(l.minutes, 0) >= g.target_minutes
      when 'hr_threshold' then coalesce(h.longest_run_minutes, 0) >= g.target_minutes
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
    -- best source for the day; a measured row beats an estimate
    select longest_run_minutes, is_estimate
    from public.hr_threshold_daily
    where user_id = g.user_id and date = days.day and threshold_bpm = g.threshold_bpm
    order by is_estimate asc, longest_run_minutes desc
    limit 1
  ) h on g.kind = 'hr_threshold'
  order by days.day;
$$;
revoke all on function public.goal_daily_progress(uuid, date, date) from public, anon;
grant execute on function public.goal_daily_progress(uuid, date, date) to authenticated, service_role;

comment on function public.goal_daily_progress is
  'Day-by-day progress of one practitioner goal, computed as the caller so consent scopes apply. Used by the app Monitor card and the practitioner dashboard so both show the same numbers.';
