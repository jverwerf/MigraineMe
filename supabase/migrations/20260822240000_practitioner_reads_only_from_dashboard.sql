-- Practitioner programme: client data is for the dashboard, not the app.
--
-- The "consented practitioner reads" policies were added to the same tables
-- the apps read for the patient's own journal. RLS policies are OR'd, so a
-- practitioner who is also an app user (Jordy today, Stephanie/Philippa/Yva
-- the day a client consents to them) opened Journal and found every consented
-- client's attacks and medicines merged into their own diary. Monitor,
-- Insights, the Full Report and the PDF do the same.
--
-- The apps trust RLS to mean "my rows". That stays true. Practitioner reads
-- now also require the request to say it is a practitioner view, via the
-- header `x-practitioner-view: 1`. Only the dashboard (and build-report-html
-- when given a `subject`) send it; no app does, so the apps can only ever see
-- auth.uid()'s own rows, however many clients the signed-in user holds.
--
-- This is not a privilege: the header lets a consented practitioner opt in to
-- seeing what consent already allows. Sending it without consent still yields
-- nothing. Done inside practitioner_can_read so all ~70 policies pick it up
-- without being recreated.

create or replace function public.practitioner_can_read(target_user uuid, required_scope text)
returns boolean
language sql
stable
security definer
set search_path to 'public'
as $$
  select coalesce(
    nullif(current_setting('request.headers', true), '')::jsonb ->> 'x-practitioner-view' = '1',
    false
  )
  and exists (
    select 1
    from public.practitioner_clients pc
    join public.practitioners p on p.id = pc.practitioner_id
    where pc.user_id = target_user
      and pc.status = 'active'
      and required_scope = any(pc.scopes)
      and p.user_id = auth.uid()
      and p.status = 'active'
  );
$$;

comment on function public.practitioner_can_read is
  'True when the caller is an active practitioner holding an active consent for target_user that carries required_scope, AND the request carries x-practitioner-view: 1. The header keeps client data out of the practitioner''s own app (Journal/Monitor/Insights/Report), which read the same tables and rely on RLS meaning "my rows". The dashboard and build-report-html (subject mode) send it.';
