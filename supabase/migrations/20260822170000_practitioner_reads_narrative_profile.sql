-- The narrative and the setup profile are part of the report's opening pages:
-- the patient's own words about their migraine, and the plain-language story
-- the recalibration wrote. Both were left out of the first two passes.
do $$
declare t text; spec text[][] := array[
  ['treatment_narrative_cache','medication'],
  ['ai_setup_profiles','attacks'],
  ['recalibration_history','attacks'],
  ['aura_insights','attacks'],
  ['gauge_accuracy','attacks'],
  ['correlation_stats','attacks']
]; i int;
begin
  for i in 1 .. array_length(spec,1) loop
    t := spec[i][1];
    if not exists (select 1 from information_schema.columns
      where table_schema='public' and table_name=t and column_name='user_id') then continue; end if;
    if exists (select 1 from pg_policies where schemaname='public' and tablename=t
      and policyname='consented practitioner reads') then continue; end if;
    execute format('create policy %I on public.%I for select using (public.practitioner_can_read(user_id, %L))',
      'consented practitioner reads', t, spec[i][2]);
  end loop;
end $$;
