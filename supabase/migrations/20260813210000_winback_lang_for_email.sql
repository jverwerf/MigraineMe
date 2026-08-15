-- winback-campaign and winback-backfill address people by EMAIL (an explicit
-- recipient list), not by user_id, so they cannot read profiles.lang the way
-- every other sender does. This RPC is that missing lookup: email -> display
-- language, the same coalesce-to-'en' contract as winback_due().
--
-- The edge functions treat a missing function or an error as English, so this
-- migration can land after the code deploys without breaking a send.

create or replace function public.winback_lang_for_email(p_email text)
 returns text
 language sql
 security definer
 set search_path to 'public'
as $function$
  select coalesce(p.lang, 'en')::text
  from auth.users u
  left join profiles p on p.user_id = u.id
  where lower(u.email) = lower(p_email)
  limit 1;
$function$;

-- SECURITY DEFINER + default PUBLIC execute would let any signed-in user probe
-- which emails have accounts. The only callers run with the service role.
revoke execute on function public.winback_lang_for_email(text) from public, anon, authenticated;
grant execute on function public.winback_lang_for_email(text) to service_role;
