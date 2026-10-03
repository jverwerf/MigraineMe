-- Metric goals: the food-risk exposures (Jordy 2026-10-03: "for diet you're missing
-- tyramine, alcohol etc"). nutrition_daily.max_<x>_exposure holds a WORD
-- (none / low / medium / high, plus a few numeric strings), so the catalogue gets a
-- `scale` column: 'level' metrics are ranked 0..3 with the same scale as
-- _shared/exposureScale.ts (none 0, low 1, medium or moderate 2, high 3) and the apps
-- show the words. Also adds the seven numeric nutrients the first seed missed.

alter table public.goal_metric_catalog add column if not exists scale text not null default 'number'
  check (scale in ('number','level'));

insert into public.goal_metric_catalog
  (key, label, grp, metric_table, metric_column, unit, agg, default_direction, default_target, step, sort, scale)
select v.* from (values
  ('tyramine',  'Tyramine',  'Diet', 'nutrition_daily', 'max_tyramine_exposure',  '', 'max', 'lte', 1, 1, 191, 'level'),
  ('alcohol',   'Alcohol',   'Diet', 'nutrition_daily', 'max_alcohol_exposure',   '', 'max', 'lte', 0, 1, 192, 'level'),
  ('gluten',    'Gluten',    'Diet', 'nutrition_daily', 'max_gluten_exposure',    '', 'max', 'lte', 1, 1, 193, 'level'),
  ('histamine', 'Histamine', 'Diet', 'nutrition_daily', 'max_histamine_exposure', '', 'max', 'lte', 1, 1, 194, 'level'),
  ('monounsaturated_fat_g',   'Monounsaturated fat', 'Diet', 'nutrition_daily', 'total_monounsaturated_fat_g',   'g',   'max', 'gte', null::numeric, 1, 600, 'number'),
  ('polyunsaturated_fat_g',   'Polyunsaturated fat', 'Diet', 'nutrition_daily', 'total_polyunsaturated_fat_g',   'g',   'max', 'gte', null, 1, 610, 'number'),
  ('chloride_mg',             'Chloride',            'Diet', 'nutrition_daily', 'total_chloride_mg',             'mg',  'max', 'lte', null, 100, 620, 'number'),
  ('chromium_mcg',            'Chromium',            'Diet', 'nutrition_daily', 'total_chromium_mcg',            'mcg', 'max', 'gte', null, 1, 630, 'number'),
  ('iodine_mcg',              'Iodine',              'Diet', 'nutrition_daily', 'total_iodine_mcg',              'mcg', 'max', 'gte', null, 10, 640, 'number'),
  ('molybdenum_mcg',          'Molybdenum',          'Diet', 'nutrition_daily', 'total_molybdenum_mcg',          'mcg', 'max', 'gte', null, 1, 650, 'number'),
  ('folic_acid_mcg',          'Folic acid',          'Diet', 'nutrition_daily', 'total_folic_acid_mcg',          'mcg', 'max', 'gte', null, 10, 660, 'number')
) as v(key, label, grp, metric_table, metric_column, unit, agg, default_direction, default_target, step, sort, scale)
where exists (
  select 1 from information_schema.columns c
  where c.table_schema = 'public' and c.table_name = v.metric_table and c.column_name = v.metric_column
)
on conflict (key) do update set
  label = excluded.label, grp = excluded.grp, metric_table = excluded.metric_table,
  metric_column = excluded.metric_column, unit = excluded.unit, agg = excluded.agg,
  default_direction = excluded.default_direction, default_target = excluded.default_target,
  step = excluded.step, sort = excluded.sort, scale = excluded.scale;

update public.goal_metric_catalog m set has_source = exists (
  select 1 from information_schema.columns c
  where c.table_schema = 'public' and c.table_name = m.metric_table and c.column_name = 'source');

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
