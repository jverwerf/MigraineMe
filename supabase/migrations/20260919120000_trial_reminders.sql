-- Trial reminders: a second push 3 days before the trial ends, plus a last-day
-- email for people a push cannot reach. trial_push_sent used to mean "one push
-- per user ever"; it now holds one row per user per reminder kind.
alter table public.trial_push_sent
  add column if not exists kind text not null default 'last_day',
  add column if not exists channel text not null default 'push';

alter table public.trial_push_sent drop constraint if exists trial_push_sent_pkey;
alter table public.trial_push_sent add constraint trial_push_sent_pkey primary key (user_id, kind);

alter table public.trial_push_sent drop constraint if exists trial_push_sent_kind_check;
alter table public.trial_push_sent add constraint trial_push_sent_kind_check
  check (kind in ('three_day', 'last_day'));
alter table public.trial_push_sent drop constraint if exists trial_push_sent_channel_check;
alter table public.trial_push_sent add constraint trial_push_sent_channel_check
  check (channel in ('push', 'email'));

-- Who is due a reminder right now. The cron runs once a day, so each window is
-- exactly 24h wide and a user falls into each one once:
--   last_day  = trial ends within the next 24h
--   three_day = trial ends in 60-84h (rounds to "3 days")
-- ends_today is judged in the user's own timezone so the last-day copy can say
-- "today" or "tomorrow" truthfully. p_test_user bypasses the window (not the
-- subscribed / already-sent filters) for a single-user test send.
create or replace function public.trial_reminder_targets(
  p_test_user uuid default null,
  p_test_kind text default null
)
returns table(user_id uuid, kind text, email text, first_name text, lang text, fcm_token text, ends_today boolean)
language sql
security definer
set search_path to 'public'
as $function$
  with c as (
    select ps.user_id, ps.trial_end,
           case
             when p_test_user is not null then coalesce(p_test_kind, 'last_day')
             when ps.trial_end <= now() + interval '24 hours' then 'last_day'
             when ps.trial_end > now() + interval '60 hours' then 'three_day'
           end as kind
    from premium_status ps
    where (ps.rc_subscription_status is null or ps.rc_subscription_status <> 'active')
      and (
        (p_test_user is null and ps.trial_end > now() and ps.trial_end <= now() + interval '84 hours')
        or ps.user_id = p_test_user
      )
  )
  select c.user_id, c.kind, u.email::text,
         nullif(split_part(coalesce(p.display_name, u.raw_user_meta_data->>'full_name', u.raw_user_meta_data->>'name', ''), ' ', 1), '')::text,
         coalesce(p.lang, 'en')::text,
         nullif(p.fcm_token, '')::text,
         ((c.trial_end at time zone tz.name)::date = (now() at time zone tz.name)::date)
  from c
  join auth.users u on u.id = c.user_id
  left join profiles p on p.user_id = c.user_id
  cross join lateral (
    select coalesce((select n.name from pg_timezone_names n where n.name = p.timezone), 'UTC') as name
  ) tz
  where c.kind is not null
    and coalesce(u.email, '') not ilike '%cloudtestlabaccounts.com'
    and not exists (
      select 1 from trial_push_sent s where s.user_id = c.user_id and s.kind = c.kind
    );
$function$;

revoke all on function public.trial_reminder_targets(uuid, text) from public, anon, authenticated;
grant execute on function public.trial_reminder_targets(uuid, text) to service_role;
