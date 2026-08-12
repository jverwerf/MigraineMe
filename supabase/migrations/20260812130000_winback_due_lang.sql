-- winback_due() now carries each recipient's display language, so the winback
-- email can be rendered in it. The cohort query is unchanged; the RPC simply
-- stops forcing the sender to guess. coalesce to 'en' keeps rows that predate
-- profiles.lang (or have no profile row at all) correct rather than tolerated.
--
-- Return type changes, so drop-and-recreate: CREATE OR REPLACE cannot alter an
-- OUT column list. The only caller is the winback-daily edge function, which
-- reads columns by name and is deployed in the same change.

drop function if exists public.winback_due();

create function public.winback_due()
 returns table(email text, first_name text, lang text)
 language sql
 security definer
 set search_path to 'public'
as $function$
  select u.email::text,
         nullif(split_part(coalesce(p.display_name, u.raw_user_meta_data->>'full_name', u.raw_user_meta_data->>'name', ''), ' ', 1), '')::text as first_name,
         coalesce(p.lang, 'en')::text as lang
  from premium_status ps
  join auth.users u on u.id = ps.user_id
  left join profiles p on p.user_id = ps.user_id
  where ps.trial_end::date = (current_date - 3)
    and (ps.rc_subscription_status is null or ps.rc_subscription_status <> 'active')
    and u.email is not null
    and u.email not ilike '%cloudtestlabaccounts.com'
    and lower(u.email) not in (select lower(email) from public.winback_emails_sent);
$function$;

-- SECURITY DEFINER + default PUBLIC execute would let any signed-in user list
-- the winback cohort's email addresses. The only caller runs with the service
-- role, so lock it down to that.
revoke execute on function public.winback_due() from public, anon, authenticated;
grant execute on function public.winback_due() to service_role;
