-- The Goals card says "Set by <name>". Clients cannot read most practitioner rows
-- (code_only practitioners are not public, and the row holds internal notes), so
-- the display name is copied onto the goal when it is written. Only the name
-- travels; nothing else on the practitioner row becomes readable.
alter table public.practitioner_goals add column if not exists practitioner_name text;

create or replace function public.fill_goal_practitioner_name()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  if new.practitioner_id is null then
    new.practitioner_name := null;
  else
    select p.display_name into new.practitioner_name
      from public.practitioners p where p.id = new.practitioner_id;
  end if;
  return new;
end;
$$;

drop trigger if exists fill_goal_practitioner_name on public.practitioner_goals;
create trigger fill_goal_practitioner_name
  before insert or update of practitioner_id on public.practitioner_goals
  for each row execute function public.fill_goal_practitioner_name();

update public.practitioner_goals g
   set practitioner_name = p.display_name
  from public.practitioners p
 where p.id = g.practitioner_id and g.practitioner_name is null;
