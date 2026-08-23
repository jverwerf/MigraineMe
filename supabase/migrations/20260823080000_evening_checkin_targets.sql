-- Evening check-in target selection, moved out of send-fcm-push into Postgres.
-- 2026-08-23.
--
-- The 8pm check-in had NEVER been delivered on MigraineMe. getEveningCheckinTargets
-- selected every profile holding an fcm_token (404 of them) and then filtered
-- user_location_daily with .in("user_id", [...404 uuids]). That serialises to a
-- ~15,800 character request URL, past the gateway's request-line limit, so the
-- call failed with "TypeError: fetch failed". The result was never error-checked,
-- so locData came back null, the timezone map stayed empty, and the function
-- returned zero targets on every hourly run. Silent since the token count crossed
-- roughly 330. MeSeries ran identical code and worked purely because it has 16
-- tokened profiles.
--
-- Two further faults died with it: PostgREST caps that unpaginated select at
-- 1000 rows (already truncating at ~250 users, so the oldest users' timezones
-- were being dropped before the URL even broke), and the local hour was derived
-- via new Date(now.toLocaleString("en-US", {timeZone: tz})), which depends on the
-- runtime parsing its own locale string back.
--
-- Doing it in SQL removes all three: no URL, no row cap, and the local hour comes
-- from the database's own tz data. Generic by design, it scales with any user count.
create or replace function public.evening_checkin_targets(target_hour int default 20)
returns table (
  user_id uuid,
  token   text,
  lang    text
)
language sql
security definer
set search_path = public
as $$
  select
    p.user_id,
    p.fcm_token as token,
    p.lang
  from profiles p
  join lateral (
    -- most recent known timezone for this user, same rule the edge function used
    select d.timezone
    from user_location_daily d
    where d.user_id = p.user_id
      and d.timezone is not null
    order by d.date desc
    limit 1
  ) tz on true
  where p.fcm_token is not null
    -- an unrecognised tz string would abort the whole statement; skip it instead,
    -- matching the try/catch the JS had around each user
    and exists (select 1 from pg_timezone_names n where n.name = tz.timezone)
    and extract(hour from (now() at time zone tz.timezone)) = target_hour;
$$;

revoke all on function public.evening_checkin_targets(int) from public, anon, authenticated;
grant execute on function public.evening_checkin_targets(int) to service_role;
