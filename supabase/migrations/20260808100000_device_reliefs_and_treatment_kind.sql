-- Neuromodulation device tracking: allow kind='device' on treatment regimens
-- and seed the global relief template pool with FDA-cleared migraine devices.

alter table public.treatment_regimens
  drop constraint if exists treatment_regimens_kind_check;
alter table public.treatment_regimens
  add constraint treatment_regimens_kind_check
  check (kind = any (array['drug'::text, 'lifestyle'::text, 'device'::text]));

insert into public.relief_templates (label, category, icon_key, is_automatable, is_automated, suggested)
select v.label, 'Device', v.icon_key, false, false, false
from (values
  ('CEFALY',           'cefaly'),
  ('Nerivio',          'nerivio'),
  ('gammaCore',        'gammacore'),
  ('sTMS (SAVI Dual)', 'stms'),
  ('Relivion',         'relivion'),
  ('Quell',            'quell'),
  ('Reliefband',       'reliefband'),
  ('CalmiGo',          'calmigo'),
  ('HeartMath',        'heartmath')
) as v(label, icon_key)
where not exists (
  select 1 from public.relief_templates t where lower(t.label) = lower(v.label)
);

-- Copy icon_key when seeding user_reliefs from templates (was dropped before)
CREATE OR REPLACE FUNCTION public.seed_pools_for_new_user()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'auth'
AS $function$
begin
    INSERT INTO public.user_symptoms (user_id, label, category, icon_key)
    SELECT NEW.id, t.label, t.category, t.icon_key
    FROM public.symptom_templates t
    ON CONFLICT (user_id, label) DO NOTHING;

    INSERT INTO public.user_triggers (user_id, label, category, icon_key, prediction_value, direction, default_threshold, unit, enabled_by_default, metric_table, metric_column, display_group, baseline_days, metric_type)
    SELECT NEW.id, t.label, t.category, t.icon_key, t.prediction_value, t.direction, t.default_threshold, t.unit, t.enabled_by_default, t.metric_table, t.metric_column, t.display_group, t.baseline_days, t.metric_type
    FROM public.trigger_templates t
    ON CONFLICT (user_id, label) DO NOTHING;

    INSERT INTO public.user_medicines (user_id, label, category)
    SELECT NEW.id, t.label, t.category
    FROM public.medicine_templates t
    ON CONFLICT (user_id, label) DO NOTHING;

    INSERT INTO public.user_reliefs (user_id, label, category, icon_key, is_automatable, is_automated)
    SELECT NEW.id, t.label, t.category, t.icon_key, t.is_automatable, t.is_automated
    FROM public.relief_templates t
    ON CONFLICT (user_id, label) DO NOTHING;

    INSERT INTO public.user_prodromes (user_id, label, category, icon_key, prediction_value, direction, default_threshold, unit, enabled_by_default, metric_table, metric_column, display_group, baseline_days, metric_type)
    SELECT NEW.id, t.label, t.category, t.icon_key, t.prediction_value, t.direction, t.default_threshold, t.unit, t.enabled_by_default, t.metric_table, t.metric_column, t.display_group, t.baseline_days, t.metric_type
    FROM public.prodrome_templates t
    ON CONFLICT (user_id, label) DO NOTHING;

    INSERT INTO public.user_locations (user_id, label, category, is_automatable, is_automated)
    SELECT NEW.id, t.label, t.category, t.is_automatable, t.is_automated
    FROM public.location_templates t
    ON CONFLICT (user_id, label) DO NOTHING;

    INSERT INTO public.user_activities (user_id, label, category, is_automatable, is_automated)
    SELECT NEW.id, t.label, t.category, t.is_automatable, t.is_automated
    FROM public.activity_templates t
    ON CONFLICT (user_id, label) DO NOTHING;

    INSERT INTO public.user_missed_activities (user_id, label, category, is_automatable, is_automated)
    SELECT NEW.id, t.label, t.category, t.is_automatable, t.is_automated
    FROM public.missed_activity_templates t
    ON CONFLICT (user_id, label) DO NOTHING;

    INSERT INTO public.user_migraines_pool (user_id, label)
    SELECT NEW.id, t.label
    FROM public.migraine_templates t
    ON CONFLICT (user_id, label) DO NOTHING;

    INSERT INTO public.user_treatment_side_effects (user_id, label, category, icon_key)
    SELECT NEW.id, t.label, t.category, t.icon_key
    FROM public.treatment_side_effect_templates t
    ON CONFLICT (user_id, label) DO NOTHING;

    RETURN NEW;
end;
$function$
;

-- Wave 2 (2026-08-08, consumer devices with affiliate potential):
insert into public.relief_templates (label, category, icon_key, is_automatable, is_automated, suggested)
select v.label, 'Device', v.icon_key, false, false, false
from (values
  ('Avulux glasses', 'avulux'),
  ('TheraSpecs',     'theraspecs'),
  ('Allay Lamp',     'allay'),
  ('ThermaZone',     'thermazone'),
  ('Apollo Neuro',   'apollo')
) as v(label, icon_key)
where not exists (
  select 1 from public.relief_templates t where lower(t.label) = lower(v.label)
);

-- Device ramp-in: preventive neuromodulation gets a 4-week ramp exclusion
-- (was falling into the 8-week oral-preventive default).
CREATE OR REPLACE FUNCTION public.compute_group_efficacy(p_group uuid)
 RETURNS TABLE(group_id uuid, member_count integer, member_names text[], earliest_start date, latest_stop date, any_active boolean, kind text, baseline_mmd double precision, rolling_mmd double precision, trailing_4w_mmd double precision, pct_change_mmd double precision, weeks_active integer, weeks_post_ramp integer, ramp_weeks integer, n_attacks_rolling integer, band text, ramp_complete boolean)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_user uuid; v_kind text; v_class text;
  v_start date; v_stop date;
  v_any_active boolean;
  v_count integer; v_names text[];
  v_baseline_s date; v_baseline_e date;
  v_post_ramp_start date; v_eval_end date; v_recent_start date;
  v_ramp_weeks int;
  v_base_mmd numeric;
  v_roll_mmd numeric := 0;
  v_trail_mmd numeric;
  v_pct numeric;
  v_weeks integer; v_post_ramp_weeks integer;
  v_n_rolling integer := 0;
  v_band text; v_ramp_done boolean;
  v_n_migraine_days integer; v_n_window_days integer;
begin
  select tr.user_id,
         min(tr.start_date),
         case when bool_or(tr.stop_date is null) then null else max(tr.stop_date) end,
         bool_or(tr.stop_date is null),
         count(*),
         array_agg(tr.name order by tr.start_date),
         case when bool_or(tr.kind = 'drug') then 'drug' else 'lifestyle' end,
         case
           when bool_or(tr.drug_class in ('cgrp_mab','gepant'))
             and not bool_or(tr.drug_class is not null and tr.drug_class not in ('cgrp_mab','gepant'))
           then 'cgrp_mab'
           else 'oral_preventive'
         end
    into v_user, v_start, v_stop, v_any_active, v_count, v_names, v_kind, v_class
    from public.treatment_regimens tr
   where tr.group_id = p_group
   group by tr.user_id;

  if v_user is null then return; end if;

  v_ramp_weeks := case
    when v_kind = 'lifestyle' then 0
    when v_kind = 'device' then 4 -- preventive neuromodulation: judge after ~1 month
    when v_class = 'cgrp_mab' then 4
    else 8
  end;

  v_baseline_e := v_start - 1;
  v_baseline_s := v_start - 28;
  v_post_ramp_start := v_start + (v_ramp_weeks * 7);
  v_eval_end := coalesce(v_stop, current_date);
  v_recent_start := v_eval_end - 27;

  select count(distinct (m.start_at at time zone 'UTC')::date)
    into v_base_mmd
    from public.migraines m
   where m.user_id = v_user
     and (m.start_at at time zone 'UTC')::date between v_baseline_s and v_baseline_e;

  if v_post_ramp_start <= v_eval_end then
    select count(distinct (m.start_at at time zone 'UTC')::date)
      into v_n_migraine_days
      from public.migraines m
     where m.user_id = v_user
       and (m.start_at at time zone 'UTC')::date between v_post_ramp_start and v_eval_end;
    v_n_window_days := greatest(1, v_eval_end - v_post_ramp_start + 1);
    v_roll_mmd := v_n_migraine_days::numeric * 28.0 / v_n_window_days::numeric;

    select count(*) into v_n_rolling
      from public.migraines m
     where m.user_id = v_user
       and (m.start_at at time zone 'UTC')::date between v_post_ramp_start and v_eval_end;
  end if;

  select count(distinct (m.start_at at time zone 'UTC')::date)
    into v_trail_mmd
    from public.migraines m
   where m.user_id = v_user
     and (m.start_at at time zone 'UTC')::date between v_recent_start and v_eval_end;

  v_base_mmd := coalesce(v_base_mmd, 0);
  v_roll_mmd := coalesce(v_roll_mmd, 0);
  v_trail_mmd := coalesce(v_trail_mmd, 0);

  if v_base_mmd > 0 then
    v_pct := round(((v_roll_mmd - v_base_mmd) / v_base_mmd) * 100, 1);
  else v_pct := null; end if;

  v_weeks := greatest(0, (v_eval_end - v_start) / 7);
  v_post_ramp_weeks := greatest(0, (v_eval_end - v_post_ramp_start) / 7);
  v_ramp_done := v_post_ramp_start <= v_eval_end;

  if not v_ramp_done or coalesce(v_n_rolling, 0) < 5 then
    v_band := 'not_enough_data';
  elsif v_pct is null then v_band := 'not_enough_data';
  elsif v_pct <= -50 then v_band := 'working_well';
  elsif v_pct <= -30 then v_band := 'showing_progress';
  elsif v_pct <= -10 then v_band := 'some_effect';
  else v_band := 'not_noticeable';
  end if;

  return query select
    p_group, v_count, v_names, v_start, v_stop, v_any_active, v_kind,
    round(v_base_mmd, 1)::float8, round(v_roll_mmd, 1)::float8,
    round(v_trail_mmd, 1)::float8,
    v_pct::float8, v_weeks, v_post_ramp_weeks, v_ramp_weeks,
    coalesce(v_n_rolling, 0), v_band, v_ramp_done;
end $function$
;
CREATE OR REPLACE FUNCTION public.compute_treatment_efficacy(p_regimen uuid)
 RETURNS TABLE(baseline_mmd double precision, rolling_mmd double precision, trailing_4w_mmd double precision, baseline_severity double precision, rolling_severity double precision, baseline_duration_h double precision, rolling_duration_h double precision, pct_change_mmd double precision, weeks_active integer, weeks_post_ramp integer, ramp_weeks integer, n_attacks_rolling integer, band text, ramp_complete boolean)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_user uuid; v_kind text; v_class text; v_start date; v_stop date;
  v_baseline_s date; v_baseline_e date;
  v_post_ramp_start date;
  v_eval_end date;
  v_recent_start date;
  v_ramp_weeks int;
  v_base_mmd numeric;
  v_roll_mmd numeric := 0;
  v_trail_mmd numeric;
  v_base_sev numeric;
  v_roll_sev numeric;
  v_base_dur numeric;
  v_roll_dur numeric;
  v_pct numeric;
  v_weeks integer;
  v_post_ramp_weeks integer;
  v_n_rolling integer := 0;
  v_band text;
  v_ramp_done boolean;
  v_n_migraine_days integer;
  v_n_window_days integer;
begin
  select user_id, kind, drug_class, start_date, stop_date
    into v_user, v_kind, v_class, v_start, v_stop
    from public.treatment_regimens where id = p_regimen;
  if v_user is null then return; end if;

  -- Ramp weeks per drug class (medical convention)
  v_ramp_weeks := case
    when v_kind = 'lifestyle' then 0
    when v_kind = 'device' then 4 -- preventive neuromodulation: judge after ~1 month
    when v_class in ('cgrp_mab','gepant') then 4
    else 8 -- oral_preventive / other / null
  end;

  v_baseline_e := v_start - 1;
  v_baseline_s := v_start - 28;
  v_post_ramp_start := v_start + (v_ramp_weeks * 7);
  v_eval_end := coalesce(v_stop, current_date);
  v_recent_start := v_eval_end - 27;

  -- Baseline metrics
  select count(distinct (start_at at time zone 'UTC')::date),
         avg(severity),
         avg(extract(epoch from coalesce(ended_at, start_at + interval '4 hours') - start_at) / 3600.0)
    into v_base_mmd, v_base_sev, v_base_dur
    from public.migraines
   where user_id = v_user
     and (start_at at time zone 'UTC')::date between v_baseline_s and v_baseline_e;

  -- Post-ramp: count distinct migraine days, normalize to per-28-day rate
  if v_post_ramp_start <= v_eval_end then
    select count(distinct (start_at at time zone 'UTC')::date)
      into v_n_migraine_days
      from public.migraines
     where user_id = v_user
       and (start_at at time zone 'UTC')::date between v_post_ramp_start and v_eval_end;
    v_n_window_days := greatest(1, v_eval_end - v_post_ramp_start + 1);
    v_roll_mmd := v_n_migraine_days::numeric * 28.0 / v_n_window_days::numeric;

    select avg(severity),
           avg(extract(epoch from coalesce(ended_at, start_at + interval '4 hours') - start_at) / 3600.0),
           count(*)
      into v_roll_sev, v_roll_dur, v_n_rolling
      from public.migraines
     where user_id = v_user
       and (start_at at time zone 'UTC')::date between v_post_ramp_start and v_eval_end;
  end if;

  -- Trailing 28-day MMD (secondary, direction-of-travel)
  select count(distinct (start_at at time zone 'UTC')::date)
    into v_trail_mmd
    from public.migraines
   where user_id = v_user
     and (start_at at time zone 'UTC')::date between v_recent_start and v_eval_end;

  v_base_mmd := coalesce(v_base_mmd, 0);
  v_roll_mmd := coalesce(v_roll_mmd, 0);
  v_trail_mmd := coalesce(v_trail_mmd, 0);

  if v_base_mmd > 0 then
    v_pct := round(((v_roll_mmd - v_base_mmd) / v_base_mmd) * 100, 1);
  else
    v_pct := null;
  end if;

  v_weeks := greatest(0, (v_eval_end - v_start) / 7);
  v_post_ramp_weeks := greatest(0, (v_eval_end - v_post_ramp_start) / 7);
  v_ramp_done := v_post_ramp_start <= v_eval_end;

  if not v_ramp_done or coalesce(v_n_rolling, 0) < 5 then
    v_band := 'not_enough_data';
  elsif v_pct is null then v_band := 'not_enough_data';
  elsif v_pct <= -50 then v_band := 'working_well';
  elsif v_pct <= -30 then v_band := 'showing_progress';
  elsif v_pct <= -10 then v_band := 'some_effect';
  else v_band := 'not_noticeable';
  end if;

  return query select
    round(v_base_mmd, 1)::float8,
    round(v_roll_mmd, 1)::float8,
    round(v_trail_mmd, 1)::float8,
    round(v_base_sev, 1)::float8,
    round(v_roll_sev, 1)::float8,
    round(v_base_dur, 1)::float8,
    round(v_roll_dur, 1)::float8,
    v_pct::float8,
    v_weeks,
    v_post_ramp_weeks,
    v_ramp_weeks,
    coalesce(v_n_rolling, 0),
    v_band,
    v_ramp_done;
end $function$
;
-- Group-kind collapse fix: a linked group of device regimens must read kind='device',
-- not fall through to 'lifestyle' (which would zero its ramp).
CREATE OR REPLACE FUNCTION public.compute_group_efficacy(p_group uuid)
 RETURNS TABLE(group_id uuid, member_count integer, member_names text[], earliest_start date, latest_stop date, any_active boolean, kind text, baseline_mmd double precision, rolling_mmd double precision, trailing_4w_mmd double precision, pct_change_mmd double precision, weeks_active integer, weeks_post_ramp integer, ramp_weeks integer, n_attacks_rolling integer, band text, ramp_complete boolean)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
declare
  v_user uuid; v_kind text; v_class text;
  v_start date; v_stop date;
  v_any_active boolean;
  v_count integer; v_names text[];
  v_baseline_s date; v_baseline_e date;
  v_post_ramp_start date; v_eval_end date; v_recent_start date;
  v_ramp_weeks int;
  v_base_mmd numeric;
  v_roll_mmd numeric := 0;
  v_trail_mmd numeric;
  v_pct numeric;
  v_weeks integer; v_post_ramp_weeks integer;
  v_n_rolling integer := 0;
  v_band text; v_ramp_done boolean;
  v_n_migraine_days integer; v_n_window_days integer;
begin
  select tr.user_id,
         min(tr.start_date),
         case when bool_or(tr.stop_date is null) then null else max(tr.stop_date) end,
         bool_or(tr.stop_date is null),
         count(*),
         array_agg(tr.name order by tr.start_date),
         case when bool_or(tr.kind = 'drug') then 'drug'
              when bool_or(tr.kind = 'device') then 'device'
              else 'lifestyle' end,
         case
           when bool_or(tr.drug_class in ('cgrp_mab','gepant'))
             and not bool_or(tr.drug_class is not null and tr.drug_class not in ('cgrp_mab','gepant'))
           then 'cgrp_mab'
           else 'oral_preventive'
         end
    into v_user, v_start, v_stop, v_any_active, v_count, v_names, v_kind, v_class
    from public.treatment_regimens tr
   where tr.group_id = p_group
   group by tr.user_id;

  if v_user is null then return; end if;

  v_ramp_weeks := case
    when v_kind = 'lifestyle' then 0
    when v_kind = 'device' then 4 -- preventive neuromodulation: judge after ~1 month
    when v_class = 'cgrp_mab' then 4
    else 8
  end;

  v_baseline_e := v_start - 1;
  v_baseline_s := v_start - 28;
  v_post_ramp_start := v_start + (v_ramp_weeks * 7);
  v_eval_end := coalesce(v_stop, current_date);
  v_recent_start := v_eval_end - 27;

  select count(distinct (m.start_at at time zone 'UTC')::date)
    into v_base_mmd
    from public.migraines m
   where m.user_id = v_user
     and (m.start_at at time zone 'UTC')::date between v_baseline_s and v_baseline_e;

  if v_post_ramp_start <= v_eval_end then
    select count(distinct (m.start_at at time zone 'UTC')::date)
      into v_n_migraine_days
      from public.migraines m
     where m.user_id = v_user
       and (m.start_at at time zone 'UTC')::date between v_post_ramp_start and v_eval_end;
    v_n_window_days := greatest(1, v_eval_end - v_post_ramp_start + 1);
    v_roll_mmd := v_n_migraine_days::numeric * 28.0 / v_n_window_days::numeric;

    select count(*) into v_n_rolling
      from public.migraines m
     where m.user_id = v_user
       and (m.start_at at time zone 'UTC')::date between v_post_ramp_start and v_eval_end;
  end if;

  select count(distinct (m.start_at at time zone 'UTC')::date)
    into v_trail_mmd
    from public.migraines m
   where m.user_id = v_user
     and (m.start_at at time zone 'UTC')::date between v_recent_start and v_eval_end;

  v_base_mmd := coalesce(v_base_mmd, 0);
  v_roll_mmd := coalesce(v_roll_mmd, 0);
  v_trail_mmd := coalesce(v_trail_mmd, 0);

  if v_base_mmd > 0 then
    v_pct := round(((v_roll_mmd - v_base_mmd) / v_base_mmd) * 100, 1);
  else v_pct := null; end if;

  v_weeks := greatest(0, (v_eval_end - v_start) / 7);
  v_post_ramp_weeks := greatest(0, (v_eval_end - v_post_ramp_start) / 7);
  v_ramp_done := v_post_ramp_start <= v_eval_end;

  if not v_ramp_done or coalesce(v_n_rolling, 0) < 5 then
    v_band := 'not_enough_data';
  elsif v_pct is null then v_band := 'not_enough_data';
  elsif v_pct <= -50 then v_band := 'working_well';
  elsif v_pct <= -30 then v_band := 'showing_progress';
  elsif v_pct <= -10 then v_band := 'some_effect';
  else v_band := 'not_noticeable';
  end if;

  return query select
    p_group, v_count, v_names, v_start, v_stop, v_any_active, v_kind,
    round(v_base_mmd, 1)::float8, round(v_roll_mmd, 1)::float8,
    round(v_trail_mmd, 1)::float8,
    v_pct::float8, v_weeks, v_post_ramp_weeks, v_ramp_weeks,
    coalesce(v_n_rolling, 0), v_band, v_ramp_done;
end $function$
;