-- Practitioner programme: granular consent.
--
-- Five buckets was too coarse. "Sleep and movement" bundled a sleep score with
-- a heart rate, a step count, screen time and body weight; a patient who wants
-- their physio to see sleep should not have to hand over their weight to do it.
--
-- Consent is now per data family. The app still shows groups, because ticking
-- twenty boxes is not consent either, but a group is a convenience that ticks
-- its children rather than a thing stored in its own right.
--
-- Old rows are expanded into their children below, so nothing a patient
-- already agreed to silently narrows or widens.

create table if not exists public.consent_scopes (
  key text primary key,
  grp text not null,
  sort int not null default 0
);

insert into public.consent_scopes (key, grp, sort) values
  ('attacks','attacks',10), ('symptoms','attacks',20), ('prodromes','attacks',30),
  ('pain_locations','attacks',40), ('aura','attacks',50), ('attack_notes','attacks',60),
  ('context','attacks',70),
  ('triggers','triggers_food',10), ('food','triggers_food',20),
  ('sleep','body',10), ('heart','body',20), ('activity','body',30),
  ('stress','body',40), ('phone_use','body',50), ('body_measures','body',60),
  ('medication','medication',10), ('side_effects','medication',20), ('regimens','medication',30),
  ('cycle','cycle',10),
  ('weather','environment',10), ('air_quality','environment',20),
  ('insights','insights',10), ('narrative','insights',20), ('setup_profile','insights',30)
on conflict (key) do update set grp = excluded.grp, sort = excluded.sort;

alter table public.consent_scopes enable row level security;
drop policy if exists "consent scopes are public" on public.consent_scopes;
create policy "consent scopes are public" on public.consent_scopes for select using (true);

-- Expand what patients already granted. A row holding the old bucket gains
-- every child of that bucket, so a practitioner sees exactly what she saw
-- yesterday.
update public.practitioner_clients set
  scopes = (select coalesce(array_agg(distinct k), '{}') from (
    select unnest(case s
      when 'attacks' then array['attacks','symptoms','prodromes','pain_locations','aura','attack_notes','context','insights','narrative','setup_profile','weather','air_quality']
      when 'triggers_food' then array['triggers','food']
      when 'sleep_activity' then array['sleep','heart','activity','stress','phone_use','body_measures']
      when 'medication' then array['medication','side_effects','regimens']
      when 'cycle' then array['cycle']
      else array[s] end) k
    from unnest(scopes) s) x),
  requested_scopes = (select coalesce(array_agg(distinct k), '{}') from (
    select unnest(case s
      when 'attacks' then array['attacks','symptoms','prodromes','pain_locations','aura','attack_notes','context','insights','narrative','setup_profile','weather','air_quality']
      when 'triggers_food' then array['triggers','food']
      when 'sleep_activity' then array['sleep','heart','activity','stress','phone_use','body_measures']
      when 'medication' then array['medication','side_effects','regimens']
      when 'cycle' then array['cycle']
      else array[s] end) k
    from unnest(requested_scopes) s) x)
where scopes && array['attacks','triggers_food','sleep_activity','medication','cycle']
   or requested_scopes && array['attacks','triggers_food','sleep_activity','medication','cycle'];

-- Repoint every table policy at its own scope.
do $$
declare t text; spec text[][] := array[
  ['migraines','attacks'], ['episodes','attacks'],
  ['symptoms','symptoms'], ['user_symptoms','symptoms'],
  ['prodromes','prodromes'], ['user_prodromes','prodromes'], ['user_prodrome_predictions','prodromes'],
  ['migraine_pain_points','pain_locations'], ['pain_migration_stats','pain_locations'],
  ['migraine_aura_zones','aura'], ['aura_insights','aura'],
  ['activities','context'], ['user_activities','context'], ['locations','context'],
  ['user_locations','context'], ['missed_activities','context'], ['user_missed_activities','context'],
  ['user_tags','context'],
  ['triggers','triggers'], ['user_triggers','triggers'], ['user_trigger_predictions','triggers'],
  ['nutrition_records','food'], ['nutrition_daily','food'],
  ['sleep_duration_daily','sleep'], ['sleep_score_daily','sleep'], ['sleep_efficiency_daily','sleep'],
  ['sleep_stages_daily','sleep'], ['sleep_disturbances_daily','sleep'],
  ['woke_up_time_daily','sleep'], ['fell_asleep_time_daily','sleep'],
  ['hrv_daily','heart'], ['resting_hr_daily','heart'], ['spo2_daily','heart'],
  ['respiratory_rate_daily','heart'], ['blood_pressure_daily','heart'],
  ['steps_daily','activity'], ['strain_daily','activity'], ['recovery_score_daily','activity'],
  ['time_in_high_hr_zones_daily','activity'], ['mindfulness_daily','activity'],
  ['stress_index_daily','stress'],
  ['screen_time_daily','phone_use'], ['screen_time_late_night','phone_use'],
  ['phone_unlock_daily','phone_use'], ['phone_brightness_daily','phone_use'],
  ['phone_volume_daily','phone_use'], ['phone_dark_mode_daily','phone_use'],
  ['ambient_noise_daily','phone_use'],
  ['weight_daily','body_measures'], ['body_fat_daily','body_measures'],
  ['blood_glucose_daily','body_measures'], ['skin_temp_daily','body_measures'],
  ['hydration_daily','body_measures'],
  ['medicines','medication'], ['user_medicines','medication'],
  ['reliefs','medication'], ['user_reliefs','medication'],
  ['treatment_side_effect_logs','side_effects'],
  ['treatment_regimens','regimens'], ['treatment_narrative_cache','narrative'],
  ['treatment_timing_stats','medication'], ['intraday_response_stats','medication'],
  ['menstruation_periods','cycle'], ['menstruation_settings','cycle'],
  ['user_weather_daily','weather'],
  ['correlation_stats','insights'], ['symptom_stats','insights'],
  ['severity_predictor_stats','insights'], ['daily_insights','insights'],
  ['risk_score_daily','insights'], ['recalibration_history','insights'], ['gauge_accuracy','insights'],
  ['ai_setup_profiles','setup_profile'],
  ['user_migraines_pool','attacks'], ['profiles','attacks']
]; i int;
begin
  for i in 1 .. array_length(spec,1) loop
    t := spec[i][1];
    if not exists (select 1 from information_schema.columns
      where table_schema='public' and table_name=t and column_name='user_id') then continue; end if;
    execute format('drop policy if exists %I on public.%I', 'consented practitioner reads', t);
    execute format('drop policy if exists %I on public.%I', 'consented practitioner reads attacks', t);
    execute format('drop policy if exists %I on public.%I', 'consented practitioner reads triggers', t);
    execute format('drop policy if exists %I on public.%I', 'consented practitioner reads food', t);
    execute format('drop policy if exists %I on public.%I', 'consented practitioner reads sleep', t);
    execute format('drop policy if exists %I on public.%I', 'consented practitioner reads steps', t);
    execute format('drop policy if exists %I on public.%I', 'consented practitioner reads reliefs', t);
    execute format('drop policy if exists %I on public.%I', 'consented practitioner reads cycle', t);
    execute format('drop policy if exists %I on public.%I', 'consented practitioner reads client profile', t);
    execute format('create policy %I on public.%I for select using (public.practitioner_can_read(user_id, %L))',
      'consented practitioner reads', t, spec[i][2]);
  end loop;
end $$;
-- Group consent by the cards the patient already knows from Monitor and
-- Insights. A person who has spent months looking at a "Physical Health" card
-- should not have to work out what "body measures" means to decide whether to
-- share it.
insert into public.consent_scopes (key, grp, sort) values
  ('attacks','Migraines',10), ('symptoms','Migraines',20), ('prodromes','Migraines',30),
  ('pain_locations','Migraines',40), ('aura','Migraines',50), ('attack_notes','Migraines',60),
  ('context','Migraines',70),
  ('triggers','Triggers',10),
  ('food','Diet',10),
  ('medication','Medicines',10), ('side_effects','Medicines',20),
  ('regimens','Treatments',10), ('narrative','Treatments',20),
  ('sleep','Sleep',10),
  ('heart','Physical Health',10), ('activity','Physical Health',20), ('body_measures','Physical Health',30),
  ('stress','Cognitive',10), ('phone_use','Cognitive',20),
  ('weather','Environment',10), ('air_quality','Environment',20),
  ('cycle','Menstruation',10),
  ('insights','Insights',10), ('setup_profile','Insights',20), ('risk','Risk',10)
on conflict (key) do update set grp = excluded.grp, sort = excluded.sort;

-- risk was folded into insights by the first pass; split it out and carry the
-- existing grants across so nobody loses or gains sight of anything.
update public.practitioner_clients
   set scopes = array_append(scopes,'risk')
 where 'insights' = any(scopes) and not ('risk' = any(scopes));
update public.practitioner_clients
   set requested_scopes = array_append(requested_scopes,'risk')
 where 'insights' = any(requested_scopes) and not ('risk' = any(requested_scopes));

do $$
begin
  if exists (select 1 from information_schema.columns
    where table_schema='public' and table_name='risk_score_daily' and column_name='user_id') then
    drop policy if exists "consented practitioner reads" on public.risk_score_daily;
    create policy "consented practitioner reads" on public.risk_score_daily
      for select using (public.practitioner_can_read(user_id, 'risk'));
  end if;
end $$;
-- The consent categories as agreed with Stephanie on the card, not the ones I
-- grouped by myself. Her seven, in her order, with the app's own extras placed
-- where they belong. The individual scopes underneath are unchanged, so no
-- grant widens or narrows.
--
--   Anfälle & Symptome      Daten, Dauer, Schmerzstärke, Aura
--   Schmerzfreie Strecken   Gute Phasen und was sie gemeinsam haben
--   Trigger & Schmerzpunkte Was rund um einen Anfall erfasst wurde
--   Schlaf & Erholung       Von Uhr oder Telefon
--   Stresswerte             Aus der Garmin, im Hintergrund erfasst
--   Medikamente & Linderung Was du nimmst und ob es geholfen hat
--   Wetter & Standort       Luftdruck und Bedingungen in deiner Nähe
insert into public.consent_scopes (key, grp, sort) values
  ('attacks','Attacks and symptoms',10), ('symptoms','Attacks and symptoms',20),
  ('aura','Attacks and symptoms',30), ('attack_notes','Attacks and symptoms',40),
  ('prodromes','Attacks and symptoms',50),

  ('insights','Pain-free stretches',10), ('risk','Pain-free stretches',20),
  ('setup_profile','Pain-free stretches',30),

  ('triggers','Triggers and pain points',10), ('pain_locations','Triggers and pain points',20),
  ('food','Triggers and pain points',30), ('context','Triggers and pain points',40),

  ('sleep','Sleep and recovery',10), ('activity','Sleep and recovery',20),

  ('stress','Stress',10), ('heart','Stress',20), ('phone_use','Stress',30),
  ('body_measures','Stress',40),

  ('medication','Medication and relief',10), ('side_effects','Medication and relief',20),
  ('regimens','Medication and relief',30), ('narrative','Medication and relief',40),

  ('weather','Weather and location',10), ('air_quality','Weather and location',20),

  ('cycle','Cycle',10)
on conflict (key) do update set grp = excluded.grp, sort = excluded.sort;
-- Back to the app's own cards. A person who has spent months reading a
-- "Physical Health" card in Monitor should meet that same name here, not a
-- grouping invented for this screen.
insert into public.consent_scopes (key, grp, sort) values
  ('attacks','Migraines',10), ('symptoms','Migraines',20), ('prodromes','Migraines',30),
  ('pain_locations','Migraines',40), ('aura','Migraines',50), ('attack_notes','Migraines',60),
  ('context','Migraines',70),
  ('triggers','Triggers',10),
  ('food','Diet',10),
  ('medication','Medicines',10), ('side_effects','Medicines',20),
  ('regimens','Treatments',10), ('narrative','Treatments',20),
  ('sleep','Sleep',10),
  ('heart','Physical Health',10), ('activity','Physical Health',20), ('body_measures','Physical Health',30),
  ('stress','Cognitive',10), ('phone_use','Cognitive',20),
  ('weather','Environment',10), ('air_quality','Environment',20),
  ('cycle','Menstruation',10),
  ('risk','Risk',10),
  ('insights','Insights',10), ('setup_profile','Insights',20)
on conflict (key) do update set grp = excluded.grp, sort = excluded.sort;
