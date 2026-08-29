-- Where did this user's install come from? Written once by the app at signup.
-- Kept off `profiles` because profiles is readable by every authenticated user.
create table if not exists public.install_attribution (
  user_id     uuid primary key references auth.users(id) on delete cascade,
  platform    text not null check (platform in ('android','ios')),
  source      text not null check (source in ('google_ads','apple_ads','organic','unknown')),
  campaign_id text,
  ad_group_id text,
  keyword_id  text,
  click_id    text,          -- gclid (Android) / none on iOS
  raw         text,          -- raw referrer string / Apple attribution JSON
  install_at  timestamptz,   -- install time as reported by the store, if any
  recorded_at timestamptz not null default now()
);
alter table public.install_attribution enable row level security;
create policy "insert own install attribution" on public.install_attribution
  for insert to authenticated with check ((select auth.uid()) = user_id);
create policy "update own install attribution" on public.install_attribution
  for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy "read own install attribution" on public.install_attribution
  for select to authenticated using ((select auth.uid()) = user_id);
create index if not exists install_attribution_source_idx on public.install_attribution (source, recorded_at);
