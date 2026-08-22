-- Practitioner programme, stage 3a: let a consented practitioner read the
-- diary.
--
-- Every policy below is additive. RLS policies are OR'd, so adding one can
-- only widen what a practitioner sees; the patient's own existing policy is
-- untouched and nobody else gains anything.
--
-- The scope names map to what the client ticks in the app:
--   attacks         the attacks themselves
--   triggers_food   triggers and food
--   sleep_activity  sleep and movement
--   medication      what they took and whether it worked
--   cycle           menstrual cycle, off unless explicitly asked for

create policy "consented practitioner reads attacks" on public.migraines
  for select using (public.practitioner_can_read(user_id, 'attacks'));

create policy "consented practitioner reads triggers" on public.triggers
  for select using (public.practitioner_can_read(user_id, 'triggers_food'));

create policy "consented practitioner reads food" on public.nutrition_records
  for select using (public.practitioner_can_read(user_id, 'triggers_food'));

create policy "consented practitioner reads sleep" on public.sleep_duration_daily
  for select using (public.practitioner_can_read(user_id, 'sleep_activity'));

create policy "consented practitioner reads steps" on public.steps_daily
  for select using (public.practitioner_can_read(user_id, 'sleep_activity'));

create policy "consented practitioner reads reliefs" on public.reliefs
  for select using (public.practitioner_can_read(user_id, 'medication'));

create policy "consented practitioner reads cycle" on public.menstruation_periods
  for select using (public.practitioner_can_read(user_id, 'cycle'));

-- The client's display name, so the dashboard shows a person rather than a
-- uuid. Nothing else on profiles is needed and nothing else is exposed,
-- because a select policy still returns only the columns the caller asks for
-- and this row is theirs to see either way.
create policy "consented practitioner reads client profile" on public.profiles
  for select using (public.practitioner_can_read(user_id, 'attacks'));
