-- me-practitioner.com: one sign-in page for every Me app.
--
-- Before sending a magic link the portal asks each backend whether this
-- e-mail belongs to a listed practitioner, so the link is sent by the project
-- that actually holds her clients and lands on that app's board. Anonymous
-- and security definer: it reads practitioners and auth.users but only ever
-- reveals the app ids a listed, active practitioner belongs to (an empty
-- array otherwise). This project hosts one app, so the answer is 'migraine'.
create or replace function public.practitioner_login_apps(p_email text)
returns text[]
language sql
stable
security definer
set search_path to 'public'
as $$
  select case when exists (
    select 1
      from public.practitioners p
      left join auth.users u on u.id = p.user_id
     where p.status = 'active'
       and (lower(p.login_email) = lower(trim(p_email))
            or lower(u.email) = lower(trim(p_email)))
  ) then array['migraine']::text[] else '{}'::text[] end;
$$;

comment on function public.practitioner_login_apps(text) is
  'me-practitioner.com sign-in: which app ids list this e-mail as an active practitioner. Empty array when none.';

revoke all on function public.practitioner_login_apps(text) from public;
grant execute on function public.practitioner_login_apps(text) to anon, authenticated;
