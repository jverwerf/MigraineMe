-- The Shop (formerly Devices) becomes data instead of code.
--
-- Until now every card on that page lived four times over: DeviceGroup/DeviceInfo
-- arrays in MigraineMe's DevicesScreen.kt, the mirrored arrays in the iOS
-- ConnectionsScreen.swift, and nothing at all in VertigoMe/MeSeries, which have
-- no Shop yet. Fixing a typo, adding a partner or swapping an affiliate link
-- therefore meant four code changes and a store release each. This moves the
-- catalogue into Postgres so the apps render whatever the table says.
--
-- WHAT STAYS IN CODE. The card layout, the commission disclosure, and the Brainy
-- art: icon_key names a key in the shipped brainy-icons set so the icon still
-- draws with no network. Product photography moves to Storage (bucket 'shop'),
-- which is what kills the real chore, four Android densities plus an iOS
-- imageset for every new product.
--
-- REGIONS ARE THE POINT OF shop_item_links. A card is only worth showing where
-- we can actually sell: Alpine runs Awin programmes in the US and NL and
-- rejected us twice in the UK, so a British tap would land on a euro store and
-- earn nothing under a header promising we picked it for you. Rather than
-- teaching four codebases about blocs, a link row carries the countries it
-- serves; no matching row means no link, and the resolver drops the item. Cards
-- that ship anywhere (Breo, Apollo) get one row with countries = null.
--
-- app_ids is the app_id law from the MeSeries platform, in array form because a
-- single catalogue row is deliberately shared: Jordy's call 2026-08-24 is that
-- VertigoMe and MeSeries carry the same items as MigraineMe.
--
-- Translation follows the community-content pattern exactly (see
-- 20260823160000_community_translations.sql): English lives on the parent row,
-- a per-language sibling table holds the prose, source_hash lets the translator
-- notice an edited English string and refresh all six languages. Nothing but
-- the edge function may write those rows.
--
-- Both Supabase projects get this file byte-identical (MigraineMe
-- qykflarpibofvffmzghi, MeSeries/VertigoMe vpwnhpwiwxwoyjfytiye).

-- ---------------------------------------------------------------- groups

create table if not exists public.shop_groups (
  key         text primary key check (key ~ '^[a-z0-9_]+$'),
  title       text not null check (length(title) between 1 and 80),
  blurb       text not null check (length(blurb) between 1 and 300),
  app_ids     text[] not null default array['migraine','vertigo'],
  sort        integer not null default 0,
  active      boolean not null default true,
  updated_at  timestamptz not null default now()
);

comment on table public.shop_groups is
  'Section headers on the Shop page. English source of truth; see shop_group_translations.';

-- ---------------------------------------------------------------- items

create table if not exists public.shop_items (
  key          text primary key check (key ~ '^[a-z0-9_]+$'),
  group_key    text not null references public.shop_groups(key) on update cascade,
  name         text not null check (length(name) between 1 and 80),
  what         text not null check (length(what) between 1 and 400),
  evidence     text check (length(evidence) <= 400),
  pros         text[] not null default '{}',
  cons         text[] not null default '{}',
  -- Where the "what people report" block's score came from, when there is one.
  -- Left null deliberately for products with no per-product customer score:
  -- a brand-wide average printed on two cards reads as invented.
  rating       text check (length(rating) <= 40),
  rating_source text check (length(rating_source) <= 80),
  rating_url   text,
  icon_key     text not null,
  photo_path   text,
  access       text not null default 'otc' check (access in ('otc','prescription')),
  safety_note  text check (length(safety_note) <= 400),
  app_ids      text[] not null default array['migraine','vertigo'],
  sort         integer not null default 0,
  active       boolean not null default true,
  updated_at   timestamptz not null default now()
);

comment on table public.shop_items is
  'One card on the Shop page. Renders only where shop_item_links has a row for the viewer''s country.';
comment on column public.shop_items.icon_key is
  'Key in the shipped brainy-icons set. Art is bundled, so the icon draws offline.';
comment on column public.shop_items.photo_path is
  'Object path in the public "shop" Storage bucket. Null means the card draws without a product shot.';

create index if not exists shop_items_active_sort_idx
  on public.shop_items (active, group_key, sort);

create index if not exists shop_items_app_ids_idx
  on public.shop_items using gin (app_ids);

-- ---------------------------------------------------------------- links

create table if not exists public.shop_item_links (
  item_key    text not null references public.shop_items(key) on delete cascade on update cascade,
  slug        text not null check (slug ~ '^[a-z0-9_]+$'),
  -- ISO-3166 alpha-2 list this link serves. NULL means "anywhere we have no
  -- more specific row for", which is how a worldwide store is expressed.
  countries   text[],
  url         text not null check (url ~ '^https://'),
  -- Shown under the Buy button: the code itself plus the offer wording. Both
  -- optional, because Alpine gives us commission and nothing for the user.
  code        text check (length(code) <= 40),
  note        text check (length(note) <= 120),
  sort        integer not null default 0,
  updated_at  timestamptz not null default now(),
  primary key (item_key, slug)
);

comment on table public.shop_item_links is
  'Per-region destination for a card. No row for the viewer''s country means the card is not shown there.';

create index if not exists shop_item_links_countries_idx
  on public.shop_item_links using gin (countries);

-- ---------------------------------------------------------------- translations

create table if not exists public.shop_group_translations (
  group_key   text not null references public.shop_groups(key) on delete cascade on update cascade,
  lang        text not null check (lang in ('de','es','nl','fr','it','pt')),
  title       text not null,
  blurb       text not null,
  source_hash text not null,
  updated_at  timestamptz not null default now(),
  primary key (group_key, lang)
);

create table if not exists public.shop_item_translations (
  item_key    text not null references public.shop_items(key) on delete cascade on update cascade,
  lang        text not null check (lang in ('de','es','nl','fr','it','pt')),
  what        text not null,
  evidence    text,
  pros        text[] not null default '{}',
  cons        text[] not null default '{}',
  safety_note text,
  note        text,
  source_hash text not null,
  updated_at  timestamptz not null default now(),
  primary key (item_key, lang)
);

comment on table public.shop_item_translations is
  'Per-language prose for a Shop card. Product names stay English. Written only by the edge function.';

create index if not exists shop_group_translations_lang_idx on public.shop_group_translations (lang);
create index if not exists shop_item_translations_lang_idx  on public.shop_item_translations (lang);

-- ---------------------------------------------------------------- RLS

alter table public.shop_groups             enable row level security;
alter table public.shop_items              enable row level security;
alter table public.shop_item_links         enable row level security;
alter table public.shop_group_translations enable row level security;
alter table public.shop_item_translations  enable row level security;

-- The catalogue is public marketing copy: readable by anyone, including a
-- signed-out user on the marketing site. Writes are service-role only, which
-- needs no policy at all.
drop policy if exists shop_groups_read on public.shop_groups;
create policy shop_groups_read on public.shop_groups
  for select to anon, authenticated using (active);

drop policy if exists shop_items_read on public.shop_items;
create policy shop_items_read on public.shop_items
  for select to anon, authenticated using (active);

drop policy if exists shop_item_links_read on public.shop_item_links;
create policy shop_item_links_read on public.shop_item_links
  for select to anon, authenticated using (true);

drop policy if exists shop_group_translations_read on public.shop_group_translations;
create policy shop_group_translations_read on public.shop_group_translations
  for select to anon, authenticated using (true);

drop policy if exists shop_item_translations_read on public.shop_item_translations;
create policy shop_item_translations_read on public.shop_item_translations
  for select to anon, authenticated using (true);

-- ---------------------------------------------------------------- resolver

-- One RPC so no app carries region or language logic. Give it the app, the
-- viewer's country and their language; get back exactly the cards to draw, in
-- order, already translated and already pointed at the right store.
--
-- The link choice is "most specific wins": a row naming the country beats the
-- catch-all row with countries = null. An item with neither is not returned,
-- which is how Alpine disappears in the UK.
create or replace function public.shop_catalogue(
  p_app     text,
  p_country text default null,
  p_lang    text default 'en'
)
returns table (
  group_key   text,
  group_title text,
  group_blurb text,
  group_sort  integer,
  item_key    text,
  name        text,
  what        text,
  evidence    text,
  pros        text[],
  cons        text[],
  rating      text,
  rating_source text,
  rating_url  text,
  icon_key    text,
  photo_path  text,
  access      text,
  safety_note text,
  item_sort   integer,
  url         text,
  code        text,
  note        text
)
language sql
stable
security invoker
set search_path = public
as $$
  select
    g.key,
    coalesce(gt.title, g.title),
    coalesce(gt.blurb, g.blurb),
    g.sort,
    i.key,
    i.name,
    coalesce(it.what, i.what),
    coalesce(it.evidence, i.evidence),
    coalesce(nullif(it.pros, '{}'), i.pros),
    coalesce(nullif(it.cons, '{}'), i.cons),
    i.rating,
    i.rating_source,
    i.rating_url,
    i.icon_key,
    i.photo_path,
    i.access,
    coalesce(it.safety_note, i.safety_note),
    i.sort,
    l.url,
    l.code,
    coalesce(it.note, l.note)
  from public.shop_items i
  join public.shop_groups g
    on g.key = i.group_key
   and g.active
   and g.app_ids @> array[p_app]
  join lateral (
    select li.url, li.code, li.note
    from public.shop_item_links li
    where li.item_key = i.key
      and (li.countries is null or (p_country is not null and li.countries @> array[upper(p_country)]))
    order by (li.countries is not null) desc, li.sort
    limit 1
  ) l on true
  left join public.shop_group_translations gt
    on gt.group_key = g.key and gt.lang = p_lang
  left join public.shop_item_translations it
    on it.item_key = i.key and it.lang = p_lang
  where i.active
    and i.app_ids @> array[p_app]
  order by g.sort, i.sort, i.key;
$$;

comment on function public.shop_catalogue is
  'Cards to draw for one app, country and language. Items with no link for that country are omitted.';

grant execute on function public.shop_catalogue(text, text, text) to anon, authenticated;
