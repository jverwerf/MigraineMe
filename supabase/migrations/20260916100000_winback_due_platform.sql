-- winback_due() now also reports which platform the recipient actually used,
-- so winback-daily can stop claiming a one-time Play promo code for people who
-- only ever opened the app on an iPhone. Those codes are a finite purchased
-- pool and an iOS user can never redeem one: on 2026-09-16, 216 of the 480
-- codes handed out had gone to iOS-only users, against 3 redemptions all time.
--
-- Platform comes from user_activity_daily.platforms, which the app itself
-- writes on open, so it is unaffected by how the person signed up (a Google
-- login on an iPhone still records 'ios'). Verified against RevenueCat for
-- nine subscribers: it agreed every time.
--
-- 'unknown' (no activity rows at all) deliberately still gets a code. A wasted
-- code is cheaper than an Android user receiving an email with no way to claim.
--
-- Return type changes, so drop-and-recreate: CREATE OR REPLACE cannot alter an
-- OUT column list. The only caller is the winback-daily edge function, which
-- reads columns by name and is deployed in the same change.

drop function if exists public.winback_due();

create function public.winback_due()
 returns table(email text, first_name text, lang text, platform text)
 language sql
 security definer
 set search_path to 'public'
as $function$
  select u.email::text,
         nullif(split_part(coalesce(p.display_name, u.raw_user_meta_data->>'full_name', u.raw_user_meta_data->>'name', ''), ' ', 1), '')::text as first_name,
         coalesce(p.lang, 'en')::text as lang,
         (case
            when exists (
              select 1 from user_activity_daily a
               where a.user_id = ps.user_id and 'android' = any(a.platforms)
            ) then 'android'
            when exists (
              select 1 from user_activity_daily a where a.user_id = ps.user_id
            ) then 'ios'
            else 'unknown'
          end)::text as platform
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
