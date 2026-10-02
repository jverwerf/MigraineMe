-- When a client stops sharing with a practitioner (or a link is declined), the
-- goals that practitioner set for them end, so the Monitor cards, Home block and
-- reminders go away with the link (Jordy 2026-10-01). Additive: a new trigger on
-- practitioner_clients, no existing column or policy changed. Sharing again does
-- not revive old goals; the practitioner sets new ones.
create or replace function public.end_goals_when_link_closes()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  if old.status = 'active' and new.status <> 'active' then
    update public.practitioner_goals
       set status = 'ended', ended_at = now(), updated_at = now()
     where practitioner_id = new.practitioner_id
       and user_id = new.user_id
       and status <> 'ended';
  end if;
  return new;
end;
$$;

drop trigger if exists end_goals_when_link_closes on public.practitioner_clients;
create trigger end_goals_when_link_closes
  after update of status on public.practitioner_clients
  for each row execute function public.end_goals_when_link_closes();
