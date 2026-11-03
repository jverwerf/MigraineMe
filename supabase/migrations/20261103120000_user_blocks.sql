-- User blocks for the community (App Store guideline 1.2), MigraineMe project.
-- Same shape as the MeSeries one (20261103100000) minus app_id: this project
-- serves MigraineMe only.
create table if not exists public.user_blocks (
  id          uuid primary key default gen_random_uuid(),
  blocker_id  uuid not null default auth.uid() references auth.users(id) on delete cascade,
  blocked_id  uuid not null references auth.users(id) on delete cascade,
  created_at  timestamptz not null default now(),
  constraint user_blocks_unique unique (blocker_id, blocked_id),
  constraint user_blocks_not_self check (blocker_id <> blocked_id)
);
alter table public.user_blocks
  add constraint user_blocks_blocked_profile_fkey
  foreign key (blocked_id) references public.profiles(user_id) on delete cascade;
create index if not exists user_blocks_blocker_idx on public.user_blocks (blocker_id);
alter table public.user_blocks enable row level security;
drop policy if exists "user_blocks select own" on public.user_blocks;
create policy "user_blocks select own" on public.user_blocks for select to authenticated using (blocker_id = (select auth.uid()));
drop policy if exists "user_blocks insert own" on public.user_blocks;
create policy "user_blocks insert own" on public.user_blocks for insert to authenticated with check (blocker_id = (select auth.uid()));
drop policy if exists "user_blocks delete own" on public.user_blocks;
create policy "user_blocks delete own" on public.user_blocks for delete to authenticated using (blocker_id = (select auth.uid()));
grant select, insert, delete on public.user_blocks to authenticated;
insert into supabase_migrations.schema_migrations(version, name) values ('20261103120000', 'user_blocks') on conflict do nothing;
