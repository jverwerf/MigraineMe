-- Split the single "gauge & recommendations" push into two independent alerts.
--
--   daily_gauge   → fires when today's risk gauge first meets the user's chosen
--                   threshold, and again on each escalation to a higher zone.
--   new_insight   → unchanged type, now only about the AI recommendations.
--
-- The gauge alert needs two things the schema didn't have: a per-user threshold
-- on the existing preference row, and a record of what has already been sent
-- today so the 5-minute risk worker doesn't re-fire as the score wobbles.

-- 1. Threshold on the existing preference table. NULL means "use the default"
--    (MILD), which keeps rows that predate this migration valid.
alter table public.notification_settings
  add column if not exists threshold text;

alter table public.notification_settings
  drop constraint if exists notification_settings_threshold_check;

alter table public.notification_settings
  add constraint notification_settings_threshold_check
  check (threshold is null or threshold in ('LOW', 'MILD', 'HIGH'));

comment on column public.notification_settings.threshold is
  'For type=daily_gauge: lowest risk zone that triggers a push (LOW/MILD/HIGH). NULL = MILD.';

-- 2. What we already told this user today. One row per user per local day,
--    holding the highest zone alerted so far. The risk worker compares against
--    this before pushing, so a day can escalate NONE → MILD → HIGH and produce
--    at most one push per zone.
create table if not exists public.gauge_alerts (
  user_id    uuid        not null references auth.users (id) on delete cascade,
  local_date date        not null,
  zone       text        not null check (zone in ('LOW', 'MILD', 'HIGH')),
  sent_at    timestamptz not null default now(),
  primary key (user_id, local_date)
);

comment on table public.gauge_alerts is
  'Highest risk zone already pushed to a user on a given local date. Dedupes the gauge alert.';

alter table public.gauge_alerts enable row level security;

-- Written only by the risk worker (service role, bypasses RLS). Users get read
-- access to their own rows so the app can explain why an alert did or did not
-- arrive; nothing client-side needs to write here.
drop policy if exists gauge_alerts_own_select on public.gauge_alerts;
create policy gauge_alerts_own_select on public.gauge_alerts
  for select
  to authenticated
  using ((select auth.uid()) = user_id);

-- Grants. This project grants ALL to anon/authenticated by default. RLS already
-- blocks writes (no INSERT/UPDATE/DELETE policy exists), but narrow the
-- privilege to match the intent: read-your-own-rows, nothing for anon.
revoke all on public.gauge_alerts from anon, authenticated;
grant select on public.gauge_alerts to authenticated;
