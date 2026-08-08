-- "Ongoing migraine" reminder: nudge the user every N days while a logged
-- migraine still has no end time, so they go back and close it.
--
-- The awkward part is the existing data. `ended_at is null` is the only marker
-- of "ongoing", but in practice it mostly means "the user never came back to
-- fill in the end time": 500 of 758 migraines are open, 312 of them more than
-- 30 days old, across 137 users. Switching this on naively would fire hundreds
-- of reminders about migraines from months ago. So every currently-open
-- migraine is seeded as silenced below, and the feature only ever speaks up
-- about migraines logged from now on.

-- 1. Reminder interval on the existing preference table. NULL = use the
--    default (3 days). Bounded so a typo can't schedule a reminder in 2099.
alter table public.notification_settings
  add column if not exists interval_days integer;

alter table public.notification_settings
  drop constraint if exists notification_settings_interval_days_check;

alter table public.notification_settings
  add constraint notification_settings_interval_days_check
  check (interval_days is null or (interval_days between 1 and 30));

comment on column public.notification_settings.interval_days is
  'For type=ongoing_migraine: days between reminders while a migraine is still open. NULL = 3.';

-- 2. Per-migraine reminder state. One row per migraine, created lazily the
--    first time we consider it. `silenced` stops a migraine the user is never
--    going to close from nagging forever.
create table if not exists public.ongoing_migraine_reminders (
  migraine_id  uuid        primary key references public.migraines (id) on delete cascade,
  user_id      uuid        not null references auth.users (id) on delete cascade,
  last_sent_at timestamptz,
  sent_count   integer     not null default 0,
  silenced     boolean     not null default false,
  created_at   timestamptz not null default now()
);

comment on table public.ongoing_migraine_reminders is
  'Reminder state per still-open migraine. Drives the ongoing_migraine push and stops it repeating.';

-- The worker sweeps by user, so index that path.
create index if not exists ongoing_migraine_reminders_user_idx
  on public.ongoing_migraine_reminders (user_id) where not silenced;

alter table public.ongoing_migraine_reminders enable row level security;

-- Written only by the reminder worker (service role, bypasses RLS). Users can
-- read their own rows; nothing client-side needs to write here.
drop policy if exists ongoing_migraine_reminders_own_select on public.ongoing_migraine_reminders;
create policy ongoing_migraine_reminders_own_select on public.ongoing_migraine_reminders
  for select
  to authenticated
  using ((select auth.uid()) = user_id);

-- 3. Silence the backlog. Every migraine that is already open on the day this
--    ships is treated as handled, so the first reminder anyone ever gets is
--    about a migraine they log after this point.
insert into public.ongoing_migraine_reminders (migraine_id, user_id, silenced)
select id, user_id, true
from public.migraines
where ended_at is null
on conflict (migraine_id) do nothing;

-- 4. Grants. This project grants ALL to anon/authenticated by default. RLS
--    already blocks writes (no INSERT/UPDATE/DELETE policy exists), but narrow
--    the privilege to match the intent: read-your-own-rows, nothing for anon.
revoke all on public.ongoing_migraine_reminders from anon, authenticated;
grant select on public.ongoing_migraine_reminders to authenticated;
