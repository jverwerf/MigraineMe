-- Practitioner programme, stage 3b: the rest of the diary.
--
-- The first pass covered attacks, triggers, food, sleep, steps, reliefs and
-- cycle, which is a fraction of what the app actually knows. Everything the
-- Insights engine computes and everything Monitor charts lives in these
-- tables, and a practitioner who cannot see them is looking at a worse
-- picture than her client is.
--
-- Scope mapping, deliberately conservative: anything derived from attacks is
-- gated on 'attacks', anything a wearable measured is 'sleep_activity',
-- medication and its side effects are 'medication'.

do $$
declare
  t text;
  spec text[][] := array[
    -- what happened inside an attack
    ['symptoms','attacks'], ['prodromes','attacks'],
    ['migraine_pain_points','attacks'], ['migraine_aura_zones','attacks'],
    ['activities','attacks'], ['locations','attacks'], ['missed_activities','attacks'],
    -- what the engine worked out
    ['correlation_stats','attacks'], ['symptom_stats','attacks'],
    ['severity_predictor_stats','attacks'], ['pain_migration_stats','attacks'],
    ['treatment_timing_stats','medication'], ['intraday_response_stats','medication'],
    ['daily_insights','attacks'], ['risk_score_daily','attacks'],
    ['user_trigger_predictions','attacks'], ['user_prodrome_predictions','attacks'],
    -- medication
    ['medicines','medication'], ['treatment_regimens','medication'],
    ['treatment_side_effect_logs','medication'],
    -- what a wearable or the phone measured
    ['hrv_daily','sleep_activity'], ['resting_hr_daily','sleep_activity'],
    ['stress_index_daily','sleep_activity'], ['spo2_daily','sleep_activity'],
    ['skin_temp_daily','sleep_activity'], ['respiratory_rate_daily','sleep_activity'],
    ['sleep_score_daily','sleep_activity'], ['sleep_efficiency_daily','sleep_activity'],
    ['sleep_stages_daily','sleep_activity'], ['sleep_disturbances_daily','sleep_activity'],
    ['recovery_score_daily','sleep_activity'], ['strain_daily','sleep_activity'],
    ['time_in_high_hr_zones_daily','sleep_activity'],
    ['screen_time_daily','sleep_activity'], ['ambient_noise_daily','sleep_activity'],
    ['hydration_daily','sleep_activity'], ['mindfulness_daily','sleep_activity'],
    ['weight_daily','sleep_activity'], ['body_fat_daily','sleep_activity'],
    ['blood_pressure_daily','sleep_activity'], ['blood_glucose_daily','sleep_activity'],
    ['woke_up_time_daily','sleep_activity'], ['fell_asleep_time_daily','sleep_activity'],
    ['nutrition_daily','triggers_food'],
    -- environment around them
    ['user_weather_daily','attacks'],
    -- the client's own labels, or the charts have ids instead of words
    ['user_symptoms','attacks'], ['user_triggers','triggers_food'],
    ['user_migraines_pool','attacks'], ['user_prodromes','attacks'],
    ['user_reliefs','medication'], ['user_medicines','medication'],
    ['user_activities','attacks'], ['user_locations','attacks'],
    ['user_missed_activities','attacks'], ['user_tags','attacks']
  ];
  i int;
begin
  for i in 1 .. array_length(spec, 1) loop
    t := spec[i][1];
    -- Skip anything this project does not have: MigraineMe and MeSeries do
    -- not carry an identical set of tables.
    if not exists (
      select 1 from information_schema.columns
      where table_schema = 'public' and table_name = t and column_name = 'user_id'
    ) then continue; end if;
    if exists (
      select 1 from pg_policies
      where schemaname = 'public' and tablename = t
        and policyname = 'consented practitioner reads'
    ) then continue; end if;
    -- %I for the policy name too: it is an identifier, not a string literal.
    execute format(
      'create policy %I on public.%I for select using (public.practitioner_can_read(user_id, %L))',
      'consented practitioner reads', t, spec[i][2]
    );
  end loop;
end $$;
