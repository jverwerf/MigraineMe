-- Food flags v2 (2026-10-31): one shared food cache, eleven flags per food.
--
-- Until now classify-food-risks scored four migraine flags (tyramine, alcohol,
-- gluten, histamine). Every 2026 gout app leads with purine and fructose, and
-- the classic migraine additive classes (aspartame, MSG, nitrates, sulfites)
-- were only manual trigger tiles. This adds seven flags end to end:
--
--   aspartame   artificial sweeteners (diet drinks, sugar-free gum)
--   msg         monosodium glutamate (stock cubes, instant noodles, takeaways)
--   nitrates    curing salts in bacon, ham, salami, hot dogs
--   sulfites    preservative in wine, beer, dried fruit, pickles
--   purine      purine load (organ meat, shellfish, oily fish, red meat, beer)
--   fructose    fructose / sugary drinks / HFCS
--   protective  foods with urate-lowering evidence (cherries, coffee,
--               low-fat dairy, vitamin C); the one flag where "high" is good
--
-- Levels are the same none/low/medium/high words as the existing four, so
-- _shared/exposureScale.ts, the daily worker, the trigger engine and the
-- Monitor graphs need no new scale. The MigraineMe project's food_risk_cache
-- is now THE cache: MeSeries' classify-food-risks proxies to it.
--
-- No backfill of nutrition_daily here (see 20260819120000_nutrition_daily_
-- histamine.sql for why): nutrition-daily-worker fills the new max_* columns
-- the next time it rolls a day up. Old food_risk_cache rows have the new
-- columns null and are re-classified on their next lookup.

alter table public.food_risk_cache
  add column if not exists aspartame  text,
  add column if not exists msg        text,
  add column if not exists nitrates   text,
  add column if not exists sulfites   text,
  add column if not exists purine     text,
  add column if not exists fructose   text,
  add column if not exists protective text;

alter table public.nutrition_records
  add column if not exists aspartame_exposure  text,
  add column if not exists msg_exposure        text,
  add column if not exists nitrates_exposure   text,
  add column if not exists sulfites_exposure   text,
  add column if not exists purine_exposure     text,
  add column if not exists fructose_exposure   text,
  add column if not exists protective_exposure text;

alter table public.nutrition_daily
  add column if not exists max_aspartame_exposure  text,
  add column if not exists max_msg_exposure        text,
  add column if not exists max_nitrates_exposure   text,
  add column if not exists max_sulfites_exposure   text,
  add column if not exists max_purine_exposure     text,
  add column if not exists max_fructose_exposure   text,
  add column if not exists max_protective_exposure text;

-- Goal metric catalogue: same shape as the tyramine/histamine rows
-- (20261003170000_metric_goals_exposures.sql). Protective is the one metric a
-- goal wants MORE of, so its default direction is gte.
insert into public.goal_metric_catalog
  (key, label, grp, metric_table, metric_column, unit, agg, default_direction, default_target, step, sort, has_source, scale)
values
  ('aspartame',  'Aspartame',  'Diet', 'nutrition_daily', 'max_aspartame_exposure',  '', 'max', 'lte', 1, 1, 195, true, 'level'),
  ('msg',        'MSG',        'Diet', 'nutrition_daily', 'max_msg_exposure',        '', 'max', 'lte', 1, 1, 196, true, 'level'),
  ('nitrates',   'Nitrates',   'Diet', 'nutrition_daily', 'max_nitrates_exposure',   '', 'max', 'lte', 1, 1, 197, true, 'level'),
  ('sulfites',   'Sulfites',   'Diet', 'nutrition_daily', 'max_sulfites_exposure',   '', 'max', 'lte', 1, 1, 198, true, 'level'),
  ('purine',     'Purine',     'Diet', 'nutrition_daily', 'max_purine_exposure',     '', 'max', 'lte', 1, 1, 199, true, 'level'),
  ('fructose',   'Fructose',   'Diet', 'nutrition_daily', 'max_fructose_exposure',   '', 'max', 'lte', 1, 1, 200, true, 'level'),
  ('protective', 'Protective', 'Diet', 'nutrition_daily', 'max_protective_exposure', '', 'max', 'gte', 1, 1, 201, true, 'level')
on conflict (key) do nothing;

-- Trigger templates for the four migraine additive flags, mirroring
-- "Tyramine exposure high" (Diet, cumulative, 14-day baseline, threshold 1 =
-- any day at "low" or worse counts). Ids are deterministic so a re-run is a
-- no-op and the two projects agree.
insert into public.trigger_templates
  (id, label, category, icon_key, prediction_value, direction, default_threshold, unit,
   enabled_by_default, metric_table, metric_column, baseline_days, metric_type, display_group)
values
  (md5('food-flag:migraine:Aspartame exposure high')::uuid, 'Aspartame exposure high', 'Diet', 'risk_aspartame', 'NONE', 'high', 1, '', true, 'nutrition_daily', 'max_aspartame_exposure', 14, 'cumulative', null),
  (md5('food-flag:migraine:MSG exposure high')::uuid,       'MSG exposure high',       'Diet', 'risk_msg',       'NONE', 'high', 1, '', true, 'nutrition_daily', 'max_msg_exposure',       14, 'cumulative', null),
  (md5('food-flag:migraine:Nitrates exposure high')::uuid,  'Nitrates exposure high',  'Diet', 'risk_nitrates',  'NONE', 'high', 1, '', true, 'nutrition_daily', 'max_nitrates_exposure',  14, 'cumulative', null),
  (md5('food-flag:migraine:Sulfites exposure high')::uuid,  'Sulfites exposure high',  'Diet', 'risk_sulfites',  'NONE', 'high', 1, '', true, 'nutrition_daily', 'max_sulfites_exposure',  14, 'cumulative', null)
on conflict (label) do nothing;

-- Pool back-fill. MetricRegistry builds the user's metric list from the
-- user's own user_triggers pool, not from templates, so a template that is
-- not copied into every existing pool is invisible (the pollen lesson,
-- 2026-09-13). Every user who already holds the tyramine flag gets the four.
insert into public.user_triggers
  (user_id, label, category, icon_key, prediction_value, direction, default_threshold, unit,
   enabled_by_default, metric_table, metric_column, baseline_days, metric_type, display_group)
select u.user_id, t.label, t.category, t.icon_key, t.prediction_value, t.direction, t.default_threshold, t.unit,
       t.enabled_by_default, t.metric_table, t.metric_column, t.baseline_days, t.metric_type, t.display_group
from (select distinct user_id from public.user_triggers where label = 'Tyramine exposure high') u
cross join public.trigger_templates t
where t.label in ('Aspartame exposure high', 'MSG exposure high', 'Nitrates exposure high', 'Sulfites exposure high')
  and not exists (select 1 from public.user_triggers x where x.user_id = u.user_id and x.label = t.label);
