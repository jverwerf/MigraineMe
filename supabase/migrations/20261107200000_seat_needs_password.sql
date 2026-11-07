-- A brand-new client signing up from the seat page gets a random password from
-- Supabase, so "no password yet" cannot be read from encrypted_password. The page
-- marks such an account at signup (metadata only lands on a new account) and clears
-- the mark once they have chosen a password for the app.
create or replace function public.seat_user_needs_password(p_user uuid)
returns boolean language sql security definer set search_path = public, auth as $$
  select coalesce(u.raw_user_meta_data->>'seat_signup', '') = '1'
    from auth.users u where u.id = p_user;
$$;
