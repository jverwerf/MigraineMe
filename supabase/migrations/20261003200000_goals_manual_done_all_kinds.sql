-- Every goal can be ticked off by hand (Jordy 2026-10-03: "always make it possible on each
-- goal to press done"). Exercise and meditation goals already had a manual log. For
-- heart-rate and metric goals a manual log for the day (practitioner_goal_logs.count > 0)
-- now marks that day met, whatever the data says: achieved = data says so OR ticked by
-- hand. The day's VALUE stays the measured one, so a hand tick never invents a reading.

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
  expr text;   -- the day's reading (aggregate expression)
  daysub text; -- the day's reading (whole subquery)
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
    if m.agg = 'sum' and m.has_source then
      -- Logged in pieces (water, HR-zone sessions): add up WITHIN one source, then
      -- take the preferred source, else the largest. Never add two sources together:
      -- a watch's daily total and the phone's sessions are the same minutes.
      daysub := format(
        '(select (array_agg(q.sv order by (q.src = (select s.preferred_source from public.metric_settings s '
        || 'where s.user_id = $1 and s.metric = %3$L limit 1)) desc nulls last, q.sv desc))[1] '
        || 'from (select t.source as src, sum(%1$s) as sv from public.%3$I t '
        || 'where t.user_id = $1 and t.date = d::date and t.%2$I is not null group by t.source) q)',
        val, m.metric_column, m.metric_table);
    elsif m.agg = 'sum' then
      expr := format('sum(%s)', val);
    elsif m.has_source and m.scale = 'number' then
      expr := format(
        '(array_agg(%1$s order by (t.source = (select s.preferred_source from public.metric_settings s '
        || 'where s.user_id = $1 and s.metric = %2$L limit 1)) desc nulls last, %1$s desc))[1]',
        val, m.metric_table);
    else
      expr := format('max(%s)', val);
    end if;
    if daysub is null then
      daysub := format('(select %s from public.%I t where t.user_id = $1 and t.date = d::date and t.%I is not null)',
                       expr, m.metric_table, m.metric_column);
    end if;
    -- 14 extra days in front so the first requested day already has its baseline.
    return query execute format(
      'with vals as ('
      || ' select d::date as day, %s as v'
      || ' from generate_series($2 - 14, $3, interval ''1 day'') d),'
      || ' base as ('
      || ' select day, v,'
      || '  avg(v)         over w as mu,'
      || '  stddev_samp(v) over w as sd,'
      || '  count(v)       over w as n'
      || ' from vals window w as (order by day rows between 14 preceding and 1 preceding))'
      || ' select day, v::numeric,'
      || '  (case when v is null then false'
      || '       when $4 = ''gte'' then v >= $5'
      || '       when $4 = ''lte'' then v <= $5'
      || '       else n >= 7 and abs(v - mu) <= 2 * coalesce(sd, 0) end)'
      || '  or exists (select 1 from public.practitioner_goal_logs pl'
      || '             where pl.goal_id = $6 and pl.date = base.day and pl.count > 0),'
      || '  ($4 = ''consistent'' and n < 7)'
      || ' from base where day >= $2 order by day',
      daysub)
    using g.user_id, p_from, p_to, g.direction, g.target_value, g.id;
    return;
  end if;

  return query
  select
    days.day,
    case
      when g.kind in ('exercise_count','daily_count') then coalesce(l.count, 0)::numeric
      when g.kind = 'mindfulness_minutes' then
        case when coalesce(r.open_sessions, 0) > 0
             then greatest(coalesce(mf.duration_minutes, 0), coalesce(l.minutes, 0), coalesce(r.minutes, 0), g.target_minutes)
             else greatest(coalesce(mf.duration_minutes, 0), coalesce(l.minutes, 0), coalesce(r.minutes, 0)) end
      when g.kind = 'hr_threshold' then coalesce(h.longest_run_minutes, 0)
    end,
    case
      when g.kind in ('exercise_count','daily_count') then coalesce(l.count, 0) >= g.target_count
      when g.kind = 'mindfulness_minutes' then
        coalesce(r.open_sessions, 0) > 0
        or greatest(coalesce(mf.duration_minutes, 0), coalesce(l.minutes, 0), coalesce(r.minutes, 0)) >= g.target_minutes
      when g.kind = 'hr_threshold' then coalesce(h.longest_run_minutes, 0) >= g.target_minutes
                                         or coalesce(l.count, 0) > 0
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
    -- one source per day (the largest), not the sum: two apps recording the same
    -- session must not double it
    select max(md.duration_minutes) as duration_minutes
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
