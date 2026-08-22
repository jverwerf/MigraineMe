-- Her actual offers, as they stand on stephanie-konkol.ch and as shown in the
-- card we agreed with her. Prices are hers; nothing here is invented.
create table if not exists public.practitioner_offers (
  id uuid primary key default gen_random_uuid(),
  practitioner_id uuid not null references public.practitioners(id) on delete cascade,
  lang text not null default 'de',
  sort int not null default 0,
  title text not null,
  price text,
  subtitle text,
  bullets text[] not null default '{}',
  kind text not null default 'service' check (kind in ('service','intro','addon')),
  created_at timestamptz not null default now()
);
create index if not exists practitioner_offers_idx on public.practitioner_offers(practitioner_id, lang, sort);
alter table public.practitioner_offers enable row level security;
drop policy if exists "offers of listed practitioners are public" on public.practitioner_offers;
create policy "offers of listed practitioners are public" on public.practitioner_offers
  for select using (exists (select 1 from public.practitioners p
    where p.id = practitioner_id and p.status='active' and p.listing_mode in ('bookable','listed')));
drop policy if exists "service role full access" on public.practitioner_offers;
create policy "service role full access" on public.practitioner_offers
  for all using (auth.role() = 'service_role');

delete from public.practitioner_offers
 where practitioner_id = (select id from public.practitioners where slug='stephanie-konkol');

insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind)
select id, 'de', 10, 'Mentoring, online', 'CHF 1''275', '3 Monate · via Zoom',
  array['5 Sitzungen zu 60 bis 90 Minuten, 1:1',
        'WhatsApp-Begleitung zwischen den Sitzungen',
        'Trance-Audios zum Vertiefen',
        'Wöchentliche Reflexion der guten Strecken'], 'service'
from public.practitioners where slug='stephanie-konkol';

insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind)
select id, 'de', 20, 'Mentoring, vor Ort', 'CHF 1''395', '3 Monate · Praxis Schüpfheim',
  array['Gleicher Aufbau, Sitzungen in der Praxis',
        'Ratenzahlung auf Anfrage möglich'], 'service'
from public.practitioners where slug='stephanie-konkol';

insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind)
select id, 'de', 30, 'Kennenlerngespräch', 'kostenlos', 'unverbindlich · online',
  array['Schauen, ob es passt, bevor irgendetwas beginnt'], 'intro'
from public.practitioners where slug='stephanie-konkol';

insert into public.practitioner_offers (practitioner_id, lang, sort, title, price, subtitle, bullets, kind)
select id, 'de', 40, 'Entspannungsaudios', 'Add-on', 'direkt in MigraineMe',
  array['Deine Audios dort, wo die ruhigen Strecken sichtbar werden'], 'addon'
from public.practitioners where slug='stephanie-konkol';

-- The card carries her quote and her chips, not a paragraph. The bio I had
-- written for her was mine, not hers, so it goes.
update public.practitioner_bios set bio = null
 where practitioner_id = (select id from public.practitioners where slug='stephanie-konkol');
delete from public.practitioner_bios
 where lang='en' and practitioner_id = (select id from public.practitioners where slug='stephanie-konkol');
