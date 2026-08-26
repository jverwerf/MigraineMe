-- A share offered by a client is now an offer, not a fait accompli.
--
-- Until today, a patient tapping "Share your data" inserted the link straight
-- to 'active': the practitioner acquired a client, and that client's diary,
-- without ever being asked. Consent has to run both ways. The row now lands
-- 'pending', and practitioner_can_read already requires 'active', so nothing
-- is readable until she says yes.
--
-- Two things enforce it, because the app is not the only thing that can write:
--   1. practitioner_answer_share, the only path from pending to active.
--   2. a trigger, so a client cannot update their own row to 'active' and put
--      themselves on her list. The existing "client updates own link" policy
--      has to keep allowing scope edits on rows that are already active, so
--      this rule is about the transition and belongs where OLD is visible.

create or replace function public.practitioner_answer_share(link_id uuid, accept boolean)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  touched int;
begin
  update public.practitioner_clients pc
     set status       = case when accept then 'active' else 'declined' end,
         connected_at = case when accept then now() else null end,
         updated_at   = now()
    from public.practitioners p
   where pc.id = link_id
     and p.id = pc.practitioner_id
     and p.user_id = auth.uid()
     and p.status = 'active'
     and pc.status = 'pending'
     and pc.initiated_by = 'client';
  get diagnostics touched = row_count;
  if touched = 0 then
    raise exception 'no share is waiting on you for this client';
  end if;
end;
$$;

comment on function public.practitioner_answer_share is
  'The practitioner accepting or declining a share a client offered her. The only route from pending to active on a client-initiated link.';

revoke all on function public.practitioner_answer_share(uuid, boolean) from public;
grant execute on function public.practitioner_answer_share(uuid, boolean) to authenticated;

create or replace function public.practitioner_clients_guard()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  -- Only a real end-user request is policed. A backend call (service role,
  -- or a migration running as postgres) has no JWT and no auth.uid(), and
  -- must stay able to repair rows.
  if auth.uid() is not null
     and old.status = 'pending'
     and new.status = 'active'
     and old.initiated_by = 'client'
     and not exists (
       select 1 from public.practitioners p
        where p.id = old.practitioner_id and p.user_id = auth.uid()
     )
  then
    raise exception 'only the practitioner can accept a share offered to her';
  end if;
  return new;
end;
$$;

drop trigger if exists practitioner_clients_guard on public.practitioner_clients;
create trigger practitioner_clients_guard
  before update on public.practitioner_clients
  for each row execute function public.practitioner_clients_guard();
