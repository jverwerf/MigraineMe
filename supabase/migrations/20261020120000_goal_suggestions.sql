-- Weekly AI goal suggestions + weekly goal review (Jordy 2026-10-20).
--
-- Every Monday, right after the weekly recalibration, the goal-suggestions
-- edge function looks at a premium user's goals, their insights (triggers,
-- protective factors), their attacks and risk, and writes:
--   * ONE goal_reports row for the week: a short AI-written review ("are you
--     doing the goals enough, is it working") plus per-goal adherence, and
--   * up to three goal_suggestions rows the user can Add or Dismiss.
-- A practitioner with an active link reads both for her clients. Exercise
-- films are never suggested (that stays the physio's call).
--
-- Opt-in lives in metric_settings (metric = 'goals'): a missing row counts as
-- ON, like every other data toggle. The push (type goal_suggestions) honours
-- notification_settings the same way the other pushes do.

-- ------------------------------------------------------------- tables
create table if not exists public.goal_suggestions (
  id               uuid primary key default gen_random_uuid(),
  user_id          uuid not null references auth.users(id) on delete cascade,
  week_start       date not null,
  -- Same shape as practitioner_goals so Add is a plain copy. exercise_count
  -- is deliberately not allowed here.
  kind             text not null check (kind in ('hr_threshold', 'mindfulness_minutes', 'daily_count', 'metric')),
  title            text not null,
  metric_key       text references public.goal_metric_catalog(key),
  direction        text check (direction in ('gte', 'lte', 'consistent')),
  target_value     numeric,
  threshold_bpm    integer check (threshold_bpm is null or threshold_bpm between 60 and 220),
  target_minutes   numeric check (target_minutes is null or target_minutes > 0),
  target_count     integer check (target_count is null or target_count > 0),
  times_per_week   integer check (times_per_week is null or times_per_week between 1 and 7),
  -- Why, in the user's language. One or two sentences tied to their own data.
  reason           text not null,
  status           text not null default 'proposed' check (status in ('proposed', 'added', 'dismissed', 'expired')),
  -- The goal that was created from it, once added.
  goal_id          uuid references public.practitioner_goals(id) on delete set null,
  created_at       timestamptz not null default now(),
  reviewed_at      timestamptz,
  constraint goal_suggestions_shape check (
       (kind = 'hr_threshold'        and threshold_bpm is not null and target_minutes is not null and times_per_week is not null)
    or (kind = 'daily_count'         and target_count is not null)
    or (kind = 'mindfulness_minutes' and target_minutes is not null)
    or (kind = 'metric'              and metric_key is not null and direction is not null
                                     and (target_value is not null or direction = 'consistent'))
  )
);

create index if not exists goal_suggestions_user_status_idx
  on public.goal_suggestions (user_id, status, created_at desc);

create table if not exists public.goal_reports (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users(id) on delete cascade,
  week_start  date not null,
  lang        text not null default 'en',
  -- The review itself, a few sentences, already in the user's language.
  report      text not null,
  -- Per goal: {goal_id, title, kind, days_hit, days_total, status: on_track|slipping|not_started, note}
  adherence   jsonb not null default '[]'::jsonb,
  -- What the review was built from, for support and for the dashboard:
  -- {attacks_by_week, risk_by_week, top_triggers, protective}
  evidence    jsonb not null default '{}'::jsonb,
  created_at  timestamptz not null default now(),
  unique (user_id, week_start)
);

create index if not exists goal_reports_user_week_idx
  on public.goal_reports (user_id, week_start desc);

-- ------------------------------------------------------------- RLS
alter table public.goal_suggestions enable row level security;
alter table public.goal_reports      enable row level security;

create policy "service role full access" on public.goal_suggestions
  for all using (auth.role() = 'service_role');
create policy "service role full access" on public.goal_reports
  for all using (auth.role() = 'service_role');

-- The client reads her own; status changes go through the two RPCs below.
create policy "client reads own suggestions" on public.goal_suggestions
  for select using (user_id = auth.uid());
create policy "client reads own reports" on public.goal_reports
  for select using (user_id = auth.uid());

-- A practitioner with an ACTIVE link reads her clients' suggestions and reviews.
create policy "practitioner reads suggestions of active clients" on public.goal_suggestions
  for select using (
    exists (
      select 1
      from public.practitioners p
      join public.practitioner_clients pc on pc.practitioner_id = p.id
      where p.user_id = auth.uid()
        and p.status = 'active'
        and pc.user_id = goal_suggestions.user_id
        and pc.status = 'active'
    )
  );
create policy "practitioner reads reports of active clients" on public.goal_reports
  for select using (
    exists (
      select 1
      from public.practitioners p
      join public.practitioner_clients pc on pc.practitioner_id = p.id
      where p.user_id = auth.uid()
        and p.status = 'active'
        and pc.user_id = goal_reports.user_id
        and pc.status = 'active'
    )
  );

-- ------------------------------------------------------------- RPCs
-- Add: copies the suggestion into a self-set goal (practitioner_id null, so
-- the client owns it like any goal she adds herself), marks the suggestion
-- added and returns the new goal id. Runs as the caller: the self-set goals
-- policy applies to the insert.
create or replace function public.accept_goal_suggestion(p_id uuid)
returns uuid
language plpgsql
security invoker
set search_path = public
as $$
declare
  s public.goal_suggestions%rowtype;
  new_id uuid;
begin
  select * into s from public.goal_suggestions
   where id = p_id and user_id = auth.uid() and status = 'proposed';
  if not found then
    raise exception 'suggestion not open';
  end if;

  insert into public.practitioner_goals
    (practitioner_id, user_id, kind, title, metric_key, direction, target_value,
     threshold_bpm, target_minutes, target_count, times_per_week,
     reminder_times, reminders_enabled, status)
  values
    (null, s.user_id, s.kind, s.title, s.metric_key, s.direction, s.target_value,
     s.threshold_bpm, s.target_minutes, s.target_count, s.times_per_week,
     '{}'::time[], false, 'active')
  returning id into new_id;

  update public.goal_suggestions
     set status = 'added', goal_id = new_id, reviewed_at = now()
   where id = p_id;

  return new_id;
end;
$$;

create or replace function public.dismiss_goal_suggestion(p_id uuid)
returns void
language sql
security definer
set search_path = public
as $$
  update public.goal_suggestions
     set status = 'dismissed', reviewed_at = now()
   where id = p_id and user_id = auth.uid() and status = 'proposed';
$$;

revoke all on function public.accept_goal_suggestion(uuid)  from public, anon;
revoke all on function public.dismiss_goal_suggestion(uuid) from public, anon;
grant execute on function public.accept_goal_suggestion(uuid)  to authenticated;
grant execute on function public.dismiss_goal_suggestion(uuid) to authenticated;

-- Who the Monday cron sends to the function: premium (trial or subscription),
-- Goals not switched off in Data settings, and something to talk about (an
-- active goal, or enough attacks for the insights to mean anything).
create or replace function public.goal_suggestion_targets()
returns table (id uuid)
language sql
stable
security definer
set search_path = public
as $$
  select ps.user_id
  from public.premium_status ps
  where (
          (ps.trial_end is not null and ps.trial_end > now())
       or ps.rc_subscription_status in ('active', 'grace_period')
        )
    and not exists (
          select 1 from public.metric_settings ms
          where ms.user_id = ps.user_id and ms.metric = 'goals' and ms.enabled = false
        )
    and (
          exists (select 1 from public.practitioner_goals g
                  where g.user_id = ps.user_id and g.status = 'active')
       or (select count(*) from public.migraines m
           where m.user_id = ps.user_id and m.start_at is not null) >= 5
        );
$$;

revoke all on function public.goal_suggestion_targets() from public, anon, authenticated;
grant execute on function public.goal_suggestion_targets() to service_role;

-- 28-day snapshot of every catalogue metric for one user, in one call: how
-- many days have a reading and the mean. The model needs the current level
-- before it can propose a target ("you average 5.9 h sleep; aim for 7").
-- Level-scaled columns (tyramine, alcohol…) map words to 0..3 like
-- goal_daily_progress does. Service role only: the function sees every table.
create or replace function public.goal_metric_snapshot(p_user uuid, p_days integer default 28)
returns table (key text, days_with_data integer, mean_value numeric, last_value numeric)
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  m record;
  val text;
  d integer;
  avgv numeric;
  lastv numeric;
begin
  for m in select * from public.goal_metric_catalog order by sort loop
    begin
      if m.scale = 'level' then
        val := format(
          '(case lower(t.%1$I::text) when ''none'' then 0 when ''low'' then 1 when ''medium'' then 2 '
          || 'when ''moderate'' then 2 when ''high'' then 3 '
          || 'else case when t.%1$I::text ~ ''^[0-9]+(\.[0-9]+)?$'' then (t.%1$I::text)::numeric end end)',
          m.metric_column);
      else
        val := format('t.%I::numeric', m.metric_column);
      end if;
      execute format(
        'select count(distinct t.date)::int, avg(%1$s)::numeric, '
        || '(array_agg(%1$s order by t.date desc))[1]::numeric '
        || 'from public.%2$I t where t.user_id = $1 and t.date >= current_date - $2 and t.%3$I is not null',
        val, m.metric_table, m.metric_column)
      into d, avgv, lastv
      using p_user, p_days;
      if coalesce(d, 0) > 0 then
        key := m.key; days_with_data := d; mean_value := round(avgv, 2); last_value := lastv;
        return next;
      end if;
    exception when others then
      -- a catalogue row pointing at a table this project lacks must not sink the run
      raise notice 'goal_metric_snapshot: % skipped: %', m.key, sqlerrm;
    end;
  end loop;
  return;
end;
$$;

revoke all on function public.goal_metric_snapshot(uuid, integer) from public, anon, authenticated;
grant execute on function public.goal_metric_snapshot(uuid, integer) to service_role;

comment on table public.goal_suggestions is
  'Weekly AI goal suggestions (never exercise films). The user Adds or Dismisses; a linked practitioner reads them.';
comment on table public.goal_reports is
  'Weekly AI-written goal review per user: adherence per goal and whether the goals are moving attacks and risk.';

-- ------------------------------------------------------------- cron
-- Monday 08:30 UTC, half an hour after weekly-recalibration. Same request
-- shape and auth header as that job (copied from it, so the service key is
-- not repeated here), one request per user, 55 s timeout.
select cron.unschedule('weekly-goal-suggestions')
 where exists (select 1 from cron.job where jobname = 'weekly-goal-suggestions');

select cron.schedule(
  'weekly-goal-suggestions',
  '30 8 * * 1',
  (select regexp_replace(
            replace(command, '/functions/v1/recalibrate', '/functions/v1/goal-suggestions'),
            'FROM \(.*\) u;', 'FROM public.goal_suggestion_targets() u;')
     from cron.job where jobname = 'weekly-recalibration')
);
