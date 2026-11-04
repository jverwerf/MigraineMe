-- A goal may name a practitioner's own film ('pr:<practitioner_exercises.id>')
-- only when that film belongs to the practitioner who sets the goal, and a
-- client may copy one into a self-set goal only while linked to that
-- practitioner. Enforced in the database, so no client or API path gets round
-- what the dashboard picker already limits. 2026-11-04.
create or replace function public.check_goal_exercise_owner()
returns trigger language plpgsql security definer set search_path = public as $$
declare e record;
begin
  if new.exercise_id is null or left(new.exercise_id, 3) <> 'pr:' then return new; end if;
  select id, practitioner_id, status into e from public.practitioner_exercises where id::text = substr(new.exercise_id, 4);
  if e.id is null then raise exception 'unknown practitioner film %', new.exercise_id using errcode = '23503'; end if;
  if new.practitioner_id is not null then
    if e.practitioner_id <> new.practitioner_id then
      raise exception 'this film belongs to another practitioner' using errcode = '42501';
    end if;
  else
    if not exists (select 1 from public.practitioner_clients c where c.practitioner_id = e.practitioner_id and c.user_id = new.user_id and c.status = 'active') then
      raise exception 'this film is only available to that practitioner''s clients' using errcode = '42501';
    end if;
  end if;
  return new;
end $$;
drop trigger if exists goal_exercise_owner_check on public.practitioner_goals;
create trigger goal_exercise_owner_check before insert or update of exercise_id, practitioner_id on public.practitioner_goals
  for each row execute function public.check_goal_exercise_owner();
