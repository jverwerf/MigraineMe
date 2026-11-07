-- Practitioner plugins: AI summary, calendar. Per practitioner row; premium later.
alter table public.practitioners add column if not exists plugins jsonb not null default '{}'::jsonb;
-- A client's own yes or no to AI summaries by this practitioner. Off until they switch it on.
alter table public.practitioner_clients add column if not exists ai_summary_allowed boolean not null default false;

create or replace function public.practitioner_clients_guard()
returns trigger language plpgsql security definer set search_path = public as $$
begin
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
  -- AI summaries are the client's own yes or no.
  if auth.uid() is not null
     and new.ai_summary_allowed is distinct from old.ai_summary_allowed
     and auth.uid() <> old.user_id
  then
    raise exception 'only the client can allow AI summaries';
  end if;
  return new;
end;
$$;
