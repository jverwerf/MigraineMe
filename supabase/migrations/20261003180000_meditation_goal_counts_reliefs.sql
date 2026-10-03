-- Meditation goals also count meditation logged as a RELIEF (Jordy 2026-10-03: "is
-- meditation also linked to the meditation relief?"). A relief in the Meditation
-- category, or named meditation / mindfulness, on the user's local day: its minutes are
-- added when it has a duration; logged without one, it counts as that day's goal done.
-- Everything else in the function is unchanged from 20261003170000.

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
  val  text;   -- one row's reading as a number
  expr text;   -- the day's reading
begin
  select * into g from public.practitioner_goals where id = p_goal_id;
  if not found then return; end if;

  if g.kind = 'metric' then
    select * into m from public.goal_metric_catalog where key = g.metric_key;
    if not found then return; end if;
    if m.scale = 'level' then
      -- word -> 0..3, same scale as _shared/exposureScale.ts; numeric strings pass through
      val := format(
        '(case lower(t.%1$I::text) when ''none'' then 0 when ''low'' then 1 when ''medium'' then 2 '
        || 'when ''moderate'' then 2 when ''high'' then 3 '
        || 'else case when t.%1$I::text ~ ''^[0-9]+(\.[0-9]+)?$'' then (t.%1$I::text)::numeric end end)',
        m.metric_column);
    else
      val := format('t.%I::numeric', m.metric_column);
    end if;
    if m.agg = 'sum' then
      expr := format('sum(%s)', val);
    elsif m.has_source and m.scale = 'number' then
      expr := format(
        '(array_agg(%1$s order by (t.source = (select s.preferred_source from public.metric_settings s '
        || 'where s.user_id = $1 and s.metric = %2$L limit 1)) desc nulls last, %1$s desc))[1]',
        val, m.metric_table);
    else
      expr := format('max(%s)', val);
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
      || ' select day, v::numeric,'
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
      when g.kind = 'mindfulness_minutes' then
        case when coalesce(r.open_sessions, 0) > 0
             then greatest(coalesce(mf.duration_minutes, 0) + coalesce(l.minutes, 0) + coalesce(r.minutes, 0), g.target_minutes)
             else coalesce(mf.duration_minutes, 0) + coalesce(l.minutes, 0) + coalesce(r.minutes, 0) end
      when g.kind = 'hr_threshold' then coalesce(h.longest_run_minutes, 0)
    end,
    case
      when g.kind in ('exercise_count','daily_count') then coalesce(l.count, 0) >= g.target_count
      when g.kind = 'mindfulness_minutes' then
        coalesce(r.open_sessions, 0) > 0
        or coalesce(mf.duration_minutes, 0) + coalesce(l.minutes, 0) + coalesce(r.minutes, 0) >= g.target_minutes
      when g.kind = 'hr_threshold' then coalesce(h.longest_run_minutes, 0) >= g.target_minutes
    end,
    coalesce(h.is_estimate, false)
  from (select d::date as day from generate_series(p_from, p_to, interval '1 day') d) days
  cross join lateral (
    -- the user's timezone, so a relief logged late in the evening lands on their day
    select coalesce(
      (select uld.timezone from public.user_location_daily uld
        where uld.user_id = g.user_id and uld.timezone is not null
          and exists (select 1 from pg_timezone_names n where n.name = uld.timezone)
        order by uld.date desc limit 1),
      'UTC') as tz
  ) tzz
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
    -- Meditation logged as a RELIEF counts too. With a duration (or a sane
    -- start/end) its minutes are added; logged without one, the session counts
    -- as that day's goal done (open_sessions).
    select
      sum(x.mins) as minutes,
      count(*) filter (where x.mins is null) as open_sessions
    from (
      select case
               when rl.duration_minutes > 0 then rl.duration_minutes::numeric
               when rl.end_at > rl.start_at and rl.end_at - rl.start_at <= interval '3 hours'
                 then round(extract(epoch from (rl.end_at - rl.start_at)) / 60)::numeric
             end as mins
      from public.reliefs rl
      where rl.user_id = g.user_id
        and (rl.category ilike 'meditation' or rl.type ilike '%meditat%' or rl.type ilike '%mindful%')
        and (rl.start_at at time zone tzz.tz)::date = days.day
    ) x
  ) r on g.kind = 'mindfulness_minutes'
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
