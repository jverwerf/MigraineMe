-- Her offers as her own site frames them: "Formen der Begleitung", each with
-- its picture and her one-line description, plus the three phases of the
-- mentoring. Wording is hers; only the translations are ours.
alter table public.practitioner_offers add column if not exists image_url text;

create table if not exists public.practitioner_sections (
  id uuid primary key default gen_random_uuid(),
  practitioner_id uuid not null references public.practitioners(id) on delete cascade,
  lang text not null default 'de',
  sort int not null default 0,
  title text not null,
  body text,
  items text[] not null default '{}',
  created_at timestamptz not null default now()
);
create index if not exists practitioner_sections_idx on public.practitioner_sections(practitioner_id, lang, sort);
alter table public.practitioner_sections enable row level security;
drop policy if exists "sections of listed practitioners are public" on public.practitioner_sections;
create policy "sections of listed practitioners are public" on public.practitioner_sections
  for select using (exists (select 1 from public.practitioners p
    where p.id = practitioner_id and p.status='active' and p.listing_mode in ('bookable','listed')));
drop policy if exists "service role full access" on public.practitioner_sections;
create policy "service role full access" on public.practitioner_sections
  for all using (auth.role() = 'service_role');
