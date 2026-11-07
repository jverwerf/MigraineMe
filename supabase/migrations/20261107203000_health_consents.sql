-- A clear, separate yes to health data, asked at sign-in and once from existing users.
create table if not exists public.health_consents (
  user_id uuid primary key references auth.users(id) on delete cascade,
  consented_at timestamptz not null default now(),
  policy_version text not null
);
alter table public.health_consents enable row level security;
drop policy if exists "user reads own health consent" on public.health_consents;
create policy "user reads own health consent" on public.health_consents for select using (user_id = auth.uid());
drop policy if exists "user records own health consent" on public.health_consents;
create policy "user records own health consent" on public.health_consents for insert with check (user_id = auth.uid());
