-- Shop impression and click log.
--
-- The partner panels (Impact for CEFALY, Affiliatly for TheraSpecs, Awin for
-- Alpine) count the clicks that reach them, but they cannot say which app the
-- click came from, which country, or how many people saw the card and did not
-- tap. This table is our side of that: one row per card per event, written
-- through record_shop_events so the apps never need a write policy.
--
-- Semantics, the same in all four apps:
--   impression = the card was delivered to the screen on a Shop open. Every
--                card the catalogue returned is logged once per open, in one
--                call, right after shop_catalogue answers.
--   click      = the Buy row was tapped. Logged before the browser opens.
-- Reopening the page logs again. Distinct users per item come from user_id.
--
-- Apply by hand to BOTH projects (MM qykflarpibofvffmzghi, MeSeries
-- vpwnhpwiwxwoyjfytiye); the file is byte-identical in both repos.

create table if not exists public.shop_events (
  id          bigint generated always as identity primary key,
  created_at  timestamptz not null default now(),
  -- Null when the Shop was opened before sign-in; the page works pre-onboarding.
  user_id     uuid references auth.users(id) on delete set null,
  app         text not null check (app ~ '^[a-z_]+$'),
  platform    text not null check (platform in ('android', 'ios')),
  country     text check (country ~ '^[A-Z]{2}$'),
  -- Deliberately not a foreign key: a card that is later deleted or renamed
  -- must not take its history with it.
  item_key    text not null,
  event       text not null check (event in ('impression', 'click'))
);

comment on table public.shop_events is
  'Shop card impressions and Buy taps from the apps. Written only via record_shop_events; read via the biz dashboard.';

create index if not exists shop_events_item_time_idx
  on public.shop_events (item_key, created_at desc);
create index if not exists shop_events_time_idx
  on public.shop_events (created_at desc);

-- RLS on with no policies: PostgREST can neither read nor write the table.
-- The SECURITY DEFINER function below is the only way in; the management API
-- (postgres role) is the only way out.
alter table public.shop_events enable row level security;

create or replace function public.record_shop_events(
  p_app       text,
  p_platform  text,
  p_event     text,
  p_item_keys text[],
  p_country   text default null
)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  if p_event not in ('impression', 'click') then return; end if;
  if p_platform not in ('android', 'ios') then return; end if;
  if p_item_keys is null or cardinality(p_item_keys) = 0 then return; end if;

  -- Only keys that are real cards, and never more than a screenful, so a
  -- misbehaving client cannot fill the table with junk.
  insert into public.shop_events (user_id, app, platform, country, item_key, event)
  select auth.uid(),
         p_app,
         p_platform,
         case when upper(p_country) ~ '^[A-Z]{2}$' then upper(p_country) end,
         k,
         p_event
  from (select distinct unnest(p_item_keys[1:50]) as k) keys
  where exists (select 1 from public.shop_items si where si.key = keys.k);
end;
$$;

comment on function public.record_shop_events is
  'Log Shop impressions (all keys on screen, one call per open) or a click (one key). Fire-and-forget from the apps.';

-- anon as well as authenticated: the Shop is reachable before onboarding
-- finishes, and an impression from a not-yet-signed-in user still counts.
grant execute on function public.record_shop_events(text, text, text, text[], text) to anon, authenticated;

-- Per-day rollup for the dashboard. security_invoker so the view cannot
-- become a side door around the table's RLS; PostgREST roles get no grant.
create or replace view public.shop_stats_daily
with (security_invoker = true) as
select app,
       item_key,
       platform,
       created_at::date                                              as day,
       count(*) filter (where event = 'impression')                  as impressions,
       count(*) filter (where event = 'click')                       as clicks,
       count(distinct user_id) filter (where event = 'click')        as clickers
from public.shop_events
group by 1, 2, 3, 4;

revoke all on public.shop_stats_daily from anon, authenticated;
