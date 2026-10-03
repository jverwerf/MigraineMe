-- Metric goals (Jordy 2026-10-03): any metric the user tracks and can influence can be a
-- goal, "at least" or "at most" a value, and each day is marked met or not from the
-- user's own data. Weather, pollen, air quality, altitude and noise are left out on
-- purpose: nothing the user does changes them.
--
-- goal_metric_catalog is the ONE list every app and the dashboard read, and the only
-- source of table/column names the progress function will touch (so nothing typed
-- by a client ever reaches dynamic SQL). Labels are English keys, translated in the
-- apps; they match the metric names the apps already show.

create table if not exists public.goal_metric_catalog (
  key               text primary key,
  label             text not null,
  grp               text not null,           -- Monitor group: Sleep, Physical Health, Cognitive, Diet
  metric_table      text not null,
  metric_column     text not null,
  unit              text not null default '',
  agg               text not null default 'max' check (agg in ('max','sum')),
  default_direction text not null check (default_direction in ('gte','lte')),
  default_target    numeric,
  step              numeric not null default 1,
  sort              int not null default 0,
  has_source        boolean not null default true
);
alter table public.goal_metric_catalog enable row level security;
drop policy if exists "catalog is readable" on public.goal_metric_catalog;
create policy "catalog is readable" on public.goal_metric_catalog for select using (true);

insert into public.goal_metric_catalog
  (key, label, grp, metric_table, metric_column, unit, agg, default_direction, default_target, step, sort)
select v.* from (values
  ('sleep_duration', 'Sleep duration', 'Sleep', 'sleep_duration_daily', 'value_hours', 'hours', 'max', 'gte', 7.5, 0.5, 10),
  ('sleep_score', 'Sleep score', 'Sleep', 'sleep_score_daily', 'value_pct', '%', 'max', 'gte', 80, 5, 20),
  ('sleep_efficiency', 'Sleep efficiency', 'Sleep', 'sleep_efficiency_daily', 'value_pct', '%', 'max', 'gte', 85, 5, 30),
  ('sleep_disturbances', 'Sleep disturbances', 'Sleep', 'sleep_disturbances_daily', 'value_count', 'count', 'max', 'lte', 3, 1, 40),
  ('deep_sleep', 'Deep sleep', 'Sleep', 'sleep_stages_daily', 'value_sws_hm', 'hours', 'max', 'gte', 1.5, 0.25, 50),
  ('rem_sleep', 'REM sleep', 'Sleep', 'sleep_stages_daily', 'value_rem_hm', 'hours', 'max', 'gte', 1.5, 0.25, 60),
  ('steps', 'Steps', 'Physical Health', 'steps_daily', 'value_count', 'count', 'max', 'gte', 8000, 500, 70),
  ('high_hr_zones', 'High HR zones', 'Physical Health', 'time_in_high_hr_zones_daily', 'value_minutes', 'min', 'sum', 'gte', 20, 5, 80),
  ('recovery', 'Recovery', 'Physical Health', 'recovery_score_daily', 'value_pct', '%', 'max', 'gte', 60, 5, 90),
  ('resting_hr', 'Resting heart rate', 'Physical Health', 'resting_hr_daily', 'value_bpm', 'bpm', 'max', 'lte', 60, 1, 100),
  ('hrv', 'HRV', 'Physical Health', 'hrv_daily', 'value_rmssd_ms', 'ms', 'max', 'gte', 50, 5, 110),
  ('weight', 'Weight', 'Physical Health', 'weight_daily', 'value_kg', 'kg', 'max', 'lte', 75, 0.5, 120),
  ('body_fat', 'Body fat', 'Physical Health', 'body_fat_daily', 'value_pct', '%', 'max', 'lte', 25, 1, 130),
  ('blood_pressure', 'Blood pressure', 'Physical Health', 'blood_pressure_daily', 'systolic_mmhg', 'mmHg', 'max', 'lte', 130, 5, 140),
  ('blood_glucose', 'Blood glucose', 'Physical Health', 'blood_glucose_daily', 'value_mmol_l', 'mmol/L', 'max', 'lte', 7, 0.5, 150),
  ('water', 'Water', 'Diet', 'hydration_daily', 'value_ml', 'ml', 'sum', 'gte', 2000, 250, 160),
  ('stress', 'Stress', 'Cognitive', 'stress_index_daily', 'value', '', 'max', 'lte', 50, 5, 170),
  ('screen_time', 'Screen time', 'Cognitive', 'screen_time_daily', 'total_hours', 'hours', 'max', 'lte', 3, 0.5, 180),
  ('late_screen_time', 'Late screen time', 'Cognitive', 'screen_time_late_night', 'value_hours', 'hours', 'max', 'lte', 0.5, 0.25, 190),
  ('caffeine_mg', 'Caffeine', 'Diet', 'nutrition_daily', 'total_caffeine_mg', 'mg', 'max', 'lte', 200, 25, 200),
  ('calories', 'Calories', 'Diet', 'nutrition_daily', 'total_calories', 'kcal', 'max', 'lte', 2200, 50, 210),
  ('sugar_g', 'Sugar', 'Diet', 'nutrition_daily', 'total_sugar_g', 'g', 'max', 'lte', 50, 5, 220),
  ('sodium_mg', 'Sodium', 'Diet', 'nutrition_daily', 'total_sodium_mg', 'mg', 'max', 'lte', 2300, 100, 230),
  ('protein_g', 'Protein', 'Diet', 'nutrition_daily', 'total_protein_g', 'g', 'max', 'gte', 60, 5, 240),
  ('fiber_g', 'Fibre', 'Diet', 'nutrition_daily', 'total_fiber_g', 'g', 'max', 'gte', 25, 5, 250),
  ('carbs_g', 'Carbs', 'Diet', 'nutrition_daily', 'total_carbs_g', 'g', 'max', 'lte', 250, 10, 260),
  ('fat_g', 'Fat', 'Diet', 'nutrition_daily', 'total_fat_g', 'g', 'max', 'lte', 70, 5, 270),
  ('saturated_fat_g', 'Saturated fat', 'Diet', 'nutrition_daily', 'total_saturated_fat_g', 'g', 'max', 'lte', 20, 1, 280),
  ('biotin_mcg', 'Biotin', 'Diet', 'nutrition_daily', 'total_biotin_mcg', 'mcg', 'max', 'gte', null, 1, 290),
  ('calcium_mg', 'Calcium', 'Diet', 'nutrition_daily', 'total_calcium_mg', 'mg', 'max', 'gte', null, 1, 300),
  ('cholesterol_mg', 'Cholesterol', 'Diet', 'nutrition_daily', 'total_cholesterol_mg', 'mg', 'max', 'lte', 300, 25, 310),
  ('copper_mg', 'Copper', 'Diet', 'nutrition_daily', 'total_copper_mg', 'mg', 'max', 'gte', null, 1, 320),
  ('folate_mcg', 'Folate', 'Diet', 'nutrition_daily', 'total_folate_mcg', 'mcg', 'max', 'gte', null, 1, 330),
  ('iron_mg', 'Iron', 'Diet', 'nutrition_daily', 'total_iron_mg', 'mg', 'max', 'gte', null, 1, 340),
  ('magnesium_mg', 'Magnesium', 'Diet', 'nutrition_daily', 'total_magnesium_mg', 'mg', 'max', 'gte', null, 1, 350),
  ('manganese_mg', 'Manganese', 'Diet', 'nutrition_daily', 'total_manganese_mg', 'mg', 'max', 'gte', null, 1, 360),
  ('niacin_mg', 'Niacin', 'Diet', 'nutrition_daily', 'total_niacin_mg', 'mg', 'max', 'gte', null, 1, 370),
  ('pantothenic_acid_mg', 'Pantothenic acid', 'Diet', 'nutrition_daily', 'total_pantothenic_acid_mg', 'mg', 'max', 'gte', null, 1, 380),
  ('phosphorus_mg', 'Phosphorus', 'Diet', 'nutrition_daily', 'total_phosphorus_mg', 'mg', 'max', 'gte', null, 1, 390),
  ('potassium_mg', 'Potassium', 'Diet', 'nutrition_daily', 'total_potassium_mg', 'mg', 'max', 'gte', null, 1, 400),
  ('riboflavin_mg', 'Riboflavin', 'Diet', 'nutrition_daily', 'total_riboflavin_mg', 'mg', 'max', 'gte', null, 1, 410),
  ('selenium_mcg', 'Selenium', 'Diet', 'nutrition_daily', 'total_selenium_mcg', 'mcg', 'max', 'gte', null, 1, 420),
  ('thiamin_mg', 'Thiamin', 'Diet', 'nutrition_daily', 'total_thiamin_mg', 'mg', 'max', 'gte', null, 1, 430),
  ('trans_fat_g', 'Trans fat', 'Diet', 'nutrition_daily', 'total_trans_fat_g', 'g', 'max', 'lte', 2, 0.5, 440),
  ('unsaturated_fat_g', 'Unsaturated fat', 'Diet', 'nutrition_daily', 'total_unsaturated_fat_g', 'g', 'max', 'gte', null, 1, 450),
  ('vitamin_a_mcg', 'Vitamin A', 'Diet', 'nutrition_daily', 'total_vitamin_a_mcg', 'mcg', 'max', 'gte', null, 1, 460),
  ('vitamin_b12_mcg', 'Vitamin B12', 'Diet', 'nutrition_daily', 'total_vitamin_b12_mcg', 'mcg', 'max', 'gte', null, 1, 470),
  ('vitamin_b6_mg', 'Vitamin B6', 'Diet', 'nutrition_daily', 'total_vitamin_b6_mg', 'mg', 'max', 'gte', null, 1, 480),
  ('vitamin_c_mg', 'Vitamin C', 'Diet', 'nutrition_daily', 'total_vitamin_c_mg', 'mg', 'max', 'gte', null, 1, 490),
  ('vitamin_d_mcg', 'Vitamin D', 'Diet', 'nutrition_daily', 'total_vitamin_d_mcg', 'mcg', 'max', 'gte', null, 1, 500),
  ('vitamin_e_mg', 'Vitamin E', 'Diet', 'nutrition_daily', 'total_vitamin_e_mg', 'mg', 'max', 'gte', null, 1, 510),
  ('vitamin_k_mcg', 'Vitamin K', 'Diet', 'nutrition_daily', 'total_vitamin_k_mcg', 'mcg', 'max', 'gte', null, 1, 520),
  ('zinc_mg', 'Zinc', 'Diet', 'nutrition_daily', 'total_zinc_mg', 'mg', 'max', 'gte', null, 1, 530)
) as v(key, label, grp, metric_table, metric_column, unit, agg, default_direction, default_target, step, sort)
where exists (
  select 1 from information_schema.columns c
  where c.table_schema = 'public' and c.table_name = v.metric_table and c.column_name = v.metric_column
)
on conflict (key) do update set
  label = excluded.label, grp = excluded.grp, metric_table = excluded.metric_table,
  metric_column = excluded.metric_column, unit = excluded.unit, agg = excluded.agg,
  default_direction = excluded.default_direction, default_target = excluded.default_target,
  step = excluded.step, sort = excluded.sort;

update public.goal_metric_catalog m set has_source = exists (
  select 1 from information_schema.columns c
  where c.table_schema = 'public' and c.table_name = m.metric_table and c.column_name = 'source');

-- The goal row: which metric, which way, what value.
alter table public.practitioner_goals add column if not exists metric_key text references public.goal_metric_catalog(key);
alter table public.practitioner_goals add column if not exists direction text check (direction in ('gte','lte'));
alter table public.practitioner_goals add column if not exists target_value numeric;

alter table public.practitioner_goals drop constraint if exists practitioner_goals_kind_check;
alter table public.practitioner_goals add constraint practitioner_goals_kind_check
  check (kind in ('hr_threshold','exercise_count','mindfulness_minutes','daily_count','metric'));

alter table public.practitioner_goals drop constraint if exists practitioner_goals_shape;
alter table public.practitioner_goals add constraint practitioner_goals_shape check (
  (kind = 'hr_threshold'        and threshold_bpm is not null and target_minutes is not null and times_per_week is not null) or
  (kind in ('exercise_count','daily_count') and target_count is not null) or
  (kind = 'mindfulness_minutes' and target_minutes is not null) or
  (kind = 'metric'              and metric_key is not null and direction is not null and target_value is not null)
);

-- Progress. For a metric goal the day's value comes straight from the metric's own
-- daily table: the user's preferred source when they chose one (metric_settings),
-- else the highest reading; summed for metrics logged in pieces (water, HR-zone
-- sessions). A day with no data has value NULL and is not met. Runs as the caller,
-- so a practitioner only sees what the client shares.
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
    return query execute format(
      'select x.day, x.v, '
      || '(x.v is not null and case when $4 = ''gte'' then x.v >= $5 else x.v <= $5 end), false '
      || 'from (select d::date as day, (select %s from public.%I t where t.user_id = $1 and t.date = d::date and t.%I is not null) as v '
      || 'from generate_series($2, $3, interval ''1 day'') d) x order by x.day',
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
