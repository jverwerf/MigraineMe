-- Cliniko connection belongs to the PERSON, not to one app row.
--
-- Jordy, 2026-11-04: me-practitioner.com is one sign-in, one place, all
-- clients whichever app they use, and the Cliniko key is entered once. A
-- practitioner can have one row per app (MeSeries) and rows on both
-- backends, so the connection hangs off the signed-in auth user; the board
-- copies the same key to the other backend when it holds a session there.
-- practitioner_id stays as a pointer to the row that connected.

alter table public.practitioner_integrations
  add column user_id uuid references auth.users(id) on delete cascade,
  add column login_email text,
  alter column practitioner_id drop not null;
alter table public.practitioner_integrations
  drop constraint practitioner_integrations_practitioner_id_provider_key;
create unique index practitioner_integrations_user_provider_key
  on public.practitioner_integrations(user_id, provider);
create index practitioner_integrations_login_email_idx
  on public.practitioner_integrations(lower(login_email));
