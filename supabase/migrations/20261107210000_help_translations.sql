-- Help Centre in every app language.
--
-- help_articles and help_categories hold the English. A translation lives beside
-- them, the same shape as shop_item_translations and article_translations: one
-- row per (parent, lang), the prose only, and source_hash so an edited English
-- article can be found. English never gets a row: the base tables are English.
--
-- source_hash is md5 of the English as it was when translated:
--   articles    md5(title || e'\x1f' || body_markdown)
--   categories  md5(name)
-- Nothing refreshes these on its own. After an English edit, run
--   python3 tools/i18n/translate_db_content.py help
-- which redoes exactly the rows whose hash no longer matches. Until then the old
-- translation keeps showing, which beats dropping the reader back into English.
--
-- No search_text here: the apps build it from the translated title and body, so
-- it cannot drift and the download stays half the size.
--
-- Read access copies the base tables (signed in only). Writes are service role
-- only, which needs no policy.
--
-- MigraineMe project only (qykflarpibofvffmzghi). Same file in both MM repos.

create table if not exists public.help_category_translations (
  slug        text not null references public.help_categories(slug) on delete cascade on update cascade,
  lang        text not null check (lang in ('de','es','nl','fr','it','pt','ja','ko','tr','pl','zh')),
  name        text not null check (length(name) >= 1),
  source_hash text not null,
  updated_at  timestamptz not null default now(),
  primary key (slug, lang)
);

create table if not exists public.help_article_translations (
  slug          text not null references public.help_articles(slug) on delete cascade on update cascade,
  lang          text not null check (lang in ('de','es','nl','fr','it','pt','ja','ko','tr','pl','zh')),
  title         text not null check (length(title) >= 1),
  body_markdown text not null check (length(body_markdown) >= 1),
  source_hash   text not null,
  updated_at    timestamptz not null default now(),
  primary key (slug, lang)
);

comment on table public.help_category_translations is
  'Per-language Help Centre category names. Written by tools/i18n/translate_db_content.py.';
comment on table public.help_article_translations is
  'Per-language Help Centre articles. Written by tools/i18n/translate_db_content.py; source_hash finds the stale ones.';

create index if not exists help_category_translations_lang_idx on public.help_category_translations (lang);
create index if not exists help_article_translations_lang_idx  on public.help_article_translations (lang);

alter table public.help_category_translations enable row level security;
alter table public.help_article_translations  enable row level security;

drop policy if exists help_category_translations_read on public.help_category_translations;
create policy help_category_translations_read on public.help_category_translations
  for select using (auth.role() = 'authenticated');

drop policy if exists help_article_translations_read on public.help_article_translations;
create policy help_article_translations_read on public.help_article_translations
  for select using (auth.role() = 'authenticated');

revoke all on public.help_category_translations, public.help_article_translations from anon, authenticated;
grant select on public.help_category_translations, public.help_article_translations to authenticated;
grant all on public.help_category_translations, public.help_article_translations to service_role;
