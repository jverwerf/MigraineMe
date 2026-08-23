-- Practitioner programme: let a listed practitioner sign in before we know
-- her auth user.
--
-- practitioners.user_id is null until the person has an auth account, and we
-- list practitioners (Stephanie Konkol today) before they ever log in. The
-- dashboard signs in with a magic link, which creates the auth user at that
-- moment, so there was no way to link the row except by hand after the fact.
--
-- login_email is the address the practitioner will sign in with. On every
-- dashboard boot the page calls claim_practitioner_login(); when the caller's
-- JWT email matches an unclaimed row, that row takes auth.uid(). Claim is
-- one-way and only ever fills a null user_id, so an already-linked row can
-- never be stolen, and a caller who already owns a practitioner row cannot
-- claim a second one.

alter table public.practitioners add column if not exists login_email text;

create unique index if not exists practitioners_login_email_idx
  on public.practitioners (lower(login_email)) where login_email is not null;

comment on column public.practitioners.login_email is
  'The e-mail the practitioner signs in to the dashboard with. Matched (case-insensitively) against the JWT email by claim_practitioner_login() to fill user_id on first sign-in.';

create or replace function public.claim_practitioner_login()
returns uuid
language plpgsql
security definer
set search_path to 'public'
as $$
declare
  em  text;
  pid uuid;
begin
  em := lower(coalesce(auth.jwt() ->> 'email', ''));
  if em = '' or auth.uid() is null then
    return null;
  end if;
  -- already linked: nothing to do
  select id into pid from public.practitioners where user_id = auth.uid() limit 1;
  if pid is not null then
    return pid;
  end if;
  update public.practitioners p
     set user_id = auth.uid(), updated_at = now()
   where p.user_id is null
     and p.status = 'active'
     and lower(p.login_email) = em
  returning p.id into pid;
  return pid;
end;
$$;

comment on function public.claim_practitioner_login is
  'Called by the practitioner dashboard on boot. Links the signed-in auth user to the unclaimed practitioners row whose login_email matches the JWT email. Returns the practitioner id, or null when nothing matched.';

revoke all on function public.claim_practitioner_login() from public;
grant execute on function public.claim_practitioner_login() to authenticated;
