-- Metric goals, third direction (Jordy 2026-10-03): 'consistent'. The day is met when
-- the value is within 2 standard deviations of the user's own average over the
-- previous 14 days. No target value. While fewer than 7 of those 14 days have data
-- the baseline is still building: the day is not met and is_estimate is true, so the
-- apps can say "building your baseline" instead of showing a miss.

alter table public.practitioner_goals drop constraint if exists practitioner_goals_direction_check;
alter table public.practitioner_goals add constraint practitioner_goals_direction_check
  check (direction in ('gte','lte','consistent'));

alter table public.practitioner_goals drop constraint if exists practitioner_goals_shape;
alter table public.practitioner_goals add constraint practitioner_goals_shape check (
  (kind = 'hr_threshold'        and threshold_bpm is not null and target_minutes is not null and times_per_week is not null) or
  (kind in ('exercise_count','daily_count') and target_count is not null) or
  (kind = 'mindfulness_minutes' and target_minutes is not null) or
  (kind = 'metric'              and metric_key is not null and direction is not null
                                and (target_value is not null or direction = 'consistent'))
);

create or replace function public.goal_daily_progress(p_goal_id uuid, p_from date, p_to date)
returns table (day date, value numeric, achieved boolean, is_estimate boolean)
language plpgsql
stable
security invoker
set search_path = public
as $fn$
declare
  g public.practitioner_goals%rowtype;
  m public.goal_metric_catalog%rowtype;
  expr text;
begin
  select * into g from public.practitioner_goals where id = p_goal_id;
  if not found then return; end if;

  if g.kind = 'metric' then
    select * into m from public.goal_metric_catalog where key = g.metric_key;
    if not found then return; end if;
    if m.agg = 'sum' then
      expr := format('sum(t.%I)::numeric', m.metric_column);
    elsif m.has_source then
      expr := format(
        '(array_agg(t.%1$I::numeric order by (t.source = (select s.preferred_source from public.metric_settings s '
        || 'where s.user_id = $1 and s.metric = %2$L limit 1)) desc nulls last, t.%1$I desc))[1]',
        m.metric_column, m.metric_table);
    else
      expr := format('max(t.%I)::numeric', m.metric_column);
    end if;
    -- 14 extra days in front so the first requested day already has its baseline.
    return query execute format(
      'with vals as ('
      || ' select d::date as day, (select %s from public.%I t where t.user_id = $1 and t.date = d::date and t.%I is not null) as v'
      || ' from generate_series($2 - 14, $3, interval ''1 day'') d),'
      || ' base as ('
      || ' select day, v,'
      || '  avg(v)         over w as mu,'
      || '  stddev_samp(v) over w as sd,'
      || '  count(v)       over w as n'
      || ' from vals window w as (order by day rows between 14 preceding and 1 preceding))'
      || ' select day, v,'
      || '  case when v is null then false'
      || '       when $4 = ''gte'' then v >= $5'
      || '       when $4 = ''lte'' then v <= $5'
      || '       else n >= 7 and abs(v - mu) <= 2 * coalesce(sd, 0) end,'
      || '  ($4 = ''consistent'' and n < 7)'
      || ' from base where day >= $2 order by day',
      expr, m.metric_table, m.metric_column)
    using g.user_id, p_from, p_to, g.direction, g.target_value;
    return;
  end if;

  return query
  select
    days.day,
    case
      when g.kind in ('exercise_count','daily_count') then coalesce(l.count, 0)::numeric
      when g.kind = 'mindfulness_minutes' then coalesce(mf.duration_minutes, 0) + coalesce(l.minutes, 0)
      when g.kind = 'hr_threshold' then coalesce(h.longest_run_minutes, 0)
    end,
    case
      when g.kind in ('exercise_count','daily_count') then coalesce(l.count, 0) >= g.target_count
      when g.kind = 'mindfulness_minutes' then coalesce(mf.duration_minutes, 0) + coalesce(l.minutes, 0) >= g.target_minutes
      when g.kind = 'hr_threshold' then coalesce(h.longest_run_minutes, 0) >= g.target_minutes
    end,
    coalesce(h.is_estimate, false)
  from (select d::date as day from generate_series(p_from, p_to, interval '1 day') d) days
  left join lateral (
    select sum(pl.count) as count, sum(pl.minutes) as minutes
    from public.practitioner_goal_logs pl
    where pl.goal_id = g.id and pl.date = days.day
  ) l on true
  left join lateral (
    select sum(md.duration_minutes) as duration_minutes
    from public.mindfulness_daily md
    where md.user_id = g.user_id and md.date = days.day
  ) mf on g.kind = 'mindfulness_minutes'
  left join lateral (
    select hd.longest_run_minutes, hd.is_estimate
    from public.hr_threshold_daily hd
    where hd.user_id = g.user_id and hd.date = days.day and hd.threshold_bpm = g.threshold_bpm
    order by hd.is_estimate asc, hd.longest_run_minutes desc
    limit 1
  ) h on g.kind = 'hr_threshold'
  order by days.day;
end;
$fn$;
revoke all on function public.goal_daily_progress(uuid, date, date) from public, anon;
grant execute on function public.goal_daily_progress(uuid, date, date) to authenticated, service_role;
