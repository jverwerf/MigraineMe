-- CVRD link taps (migraineme.app/cvrd redirect in the website worker). Anon insert only, no anon read.
create table if not exists public.link_clicks (id bigint generated always as identity primary key, slug text not null, platform text not null, country text, ua text, created_at timestamptz not null default now());
create index if not exists link_clicks_slug_created_idx on public.link_clicks (slug, created_at);
alter table public.link_clicks enable row level security;
create policy "anon insert" on public.link_clicks for insert to anon with check (true);
