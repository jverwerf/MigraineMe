-- Community content in the user's own language.
--
-- Everything on the Community tab except the UI chrome is stored English-only:
-- ingested news articles (title + ai_summary), the AI companions' comments on
-- them, forum posts and replies, blog comments, and the AI thread summaries. A
-- German user therefore reads a fully German app with an entirely English feed
-- inside it. Blog POSTS were the one exception, translated into
-- blog_translations for the marketing site, which the apps never read.
--
-- This adds a per-language sibling table for each of those, keyed on the source
-- row and the language, holding only the fields that are prose. The pattern is
-- deliberately the same as the existing blog_translations so a client can embed
-- it in the parent query and filter to one language:
--
--   articles?select=...,article_translations(lang,title,ai_summary)
--           &article_translations.lang=eq.de
--
-- WHY A TABLE AND NOT THE Strings TABLES. The app's t() system keys on the
-- English string, which works for a fixed vocabulary written by us. Article
-- prose is unbounded, arrives daily, and is far too long for a lookup table, so
-- it has to be translated once server-side and stored. Written by the
-- translate-community edge function; nothing else may write these rows.
--
-- source_hash is md5 of the English fields as they were when translated. The
-- translator re-does any row whose hash no longer matches, so editing an
-- English article or a bot comment silently refreshes all six languages rather
-- than leaving a stale translation behind for ever. That is also why lang is
-- CHECK-constrained to the six app languages: an unknown code here would never
-- be read by any client and would just burn tokens.
--
-- RLS mirrors the parent row exactly: a translation is visible if and only if
-- the row it translates is visible. Repeating the parent's predicate instead of
-- a blanket `using (true)` keeps moderated ('status' <> 'active') comments and
-- unpublished articles from leaking through their translation.
--
-- Both Supabase projects get this file byte-identical (MigraineMe
-- qykflarpibofvffmzghi, MeSeries/VertigoMe vpwnhpwiwxwoyjfytiye).

-- Shared shape for every table below. Kept as a comment rather than a macro
-- because each one differs in its key type and its prose columns.
--   (<parent>_id, lang) primary key, cascade delete, source_hash, updated_at.

create table if not exists public.article_translations (
  article_id uuid not null references public.articles(id) on delete cascade,
  lang text not null check (lang in ('de','es','nl','fr','it','pt')),
  title text not null check (length(title) between 1 and 1000),
  ai_summary text,
  source_hash text not null,
  updated_at timestamptz not null default now(),
  primary key (article_id, lang)
);

comment on table public.article_translations is
  'Per-language title/ai_summary for public.articles. Written only by the translate-community edge function.';

create index if not exists article_translations_lang_idx
  on public.article_translations (lang);

create table if not exists public.article_comment_translations (
  comment_id uuid not null references public.article_comments(id) on delete cascade,
  lang text not null check (lang in ('de','es','nl','fr','it','pt')),
  body text not null,
  source_hash text not null,
  updated_at timestamptz not null default now(),
  primary key (comment_id, lang)
);

comment on table public.article_comment_translations is
  'Per-language body for public.article_comments (AI companion comments and user replies).';

create index if not exists article_comment_translations_lang_idx
  on public.article_comment_translations (lang);

create table if not exists public.blog_comment_translations (
  comment_id uuid not null references public.blog_comments(id) on delete cascade,
  lang text not null check (lang in ('de','es','nl','fr','it','pt')),
  body text not null,
  source_hash text not null,
  updated_at timestamptz not null default now(),
  primary key (comment_id, lang)
);

comment on table public.blog_comment_translations is
  'Per-language body for public.blog_comments.';

create index if not exists blog_comment_translations_lang_idx
  on public.blog_comment_translations (lang);

create table if not exists public.forum_post_translations (
  post_id uuid not null references public.forum_posts(id) on delete cascade,
  lang text not null check (lang in ('de','es','nl','fr','it','pt')),
  title text not null,
  body text not null,
  source_hash text not null,
  updated_at timestamptz not null default now(),
  primary key (post_id, lang)
);

comment on table public.forum_post_translations is
  'Per-language title/body for public.forum_posts.';

create index if not exists forum_post_translations_lang_idx
  on public.forum_post_translations (lang);

create table if not exists public.forum_comment_translations (
  comment_id uuid not null references public.forum_comments(id) on delete cascade,
  lang text not null check (lang in ('de','es','nl','fr','it','pt')),
  body text not null,
  source_hash text not null,
  updated_at timestamptz not null default now(),
  primary key (comment_id, lang)
);

comment on table public.forum_comment_translations is
  'Per-language body for public.forum_comments.';

create index if not exists forum_comment_translations_lang_idx
  on public.forum_comment_translations (lang);

create table if not exists public.forum_thread_summary_translations (
  post_id text not null references public.forum_thread_summaries(post_id) on delete cascade,
  lang text not null check (lang in ('de','es','nl','fr','it','pt')),
  summary text not null,
  source_hash text not null,
  updated_at timestamptz not null default now(),
  primary key (post_id, lang)
);

comment on table public.forum_thread_summary_translations is
  'Per-language summary for public.forum_thread_summaries.';

create index if not exists forum_thread_summary_translations_lang_idx
  on public.forum_thread_summary_translations (lang);

-- ── RLS: each translation inherits its parent row's visibility ──

alter table public.article_translations enable row level security;
alter table public.article_comment_translations enable row level security;
alter table public.blog_comment_translations enable row level security;
alter table public.forum_post_translations enable row level security;
alter table public.forum_comment_translations enable row level security;
alter table public.forum_thread_summary_translations enable row level security;

drop policy if exists "article_translations_read" on public.article_translations;
create policy "article_translations_read"
  on public.article_translations for select
  to anon, authenticated
  using (exists (
    select 1 from public.articles a
    where a.id = article_translations.article_id
      and a.status = 'published'
  ));

drop policy if exists "article_comment_translations_read" on public.article_comment_translations;
create policy "article_comment_translations_read"
  on public.article_comment_translations for select
  to anon, authenticated
  using (exists (
    select 1 from public.article_comments c
    where c.id = article_comment_translations.comment_id
      and c.status = 'active'
  ));

drop policy if exists "blog_comment_translations_read" on public.blog_comment_translations;
create policy "blog_comment_translations_read"
  on public.blog_comment_translations for select
  to anon, authenticated
  using (exists (
    select 1 from public.blog_comments c
    where c.id = blog_comment_translations.comment_id
      and c.status = 'active'
  ));

drop policy if exists "forum_post_translations_read" on public.forum_post_translations;
create policy "forum_post_translations_read"
  on public.forum_post_translations for select
  to anon, authenticated
  using (exists (
    select 1 from public.forum_posts p
    where p.id = forum_post_translations.post_id
      and p.status = 'active'
  ));

drop policy if exists "forum_comment_translations_read" on public.forum_comment_translations;
create policy "forum_comment_translations_read"
  on public.forum_comment_translations for select
  to anon, authenticated
  using (exists (
    select 1 from public.forum_comments c
    where c.id = forum_comment_translations.comment_id
      and c.status = 'active'
  ));

drop policy if exists "forum_thread_summary_translations_read" on public.forum_thread_summary_translations;
create policy "forum_thread_summary_translations_read"
  on public.forum_thread_summary_translations for select
  to anon, authenticated
  using (true);

grant select on public.article_translations,
                public.article_comment_translations,
                public.blog_comment_translations,
                public.forum_post_translations,
                public.forum_comment_translations,
                public.forum_thread_summary_translations
  to anon, authenticated;

grant all on public.article_translations,
             public.article_comment_translations,
             public.blog_comment_translations,
             public.forum_post_translations,
             public.forum_comment_translations,
             public.forum_thread_summary_translations
  to service_role;

-- The apps read blog_translations too, which until now only the websites did.
-- Its existing policy is anon+authenticated read gated on the blog being
-- published, which is already what the apps need, so nothing changes there.

notify pgrst, 'reload schema';

-- ── The work queue ──
--
-- One place that knows, per kind, which parent table it reads, which columns
-- are prose, which rows are publicly visible, and how the source hash is
-- built. The translator asks this for a batch and hands the hash straight back
-- on upsert, so the "is this stale" rule can never drift between the two.
--
-- security definer because the caller is the service role anyway and this must
-- see rows regardless of RLS; search_path is pinned per Supabase guidance.

create or replace function public.community_translation_queue(
  p_kind text,
  p_lang text,
  p_limit integer default 12
)
returns table(row_id text, source_hash text, fields jsonb)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  lim integer := least(greatest(coalesce(p_limit, 12), 1), 50);
begin
  if p_lang not in ('de','es','nl','fr','it','pt') then
    return;
  end if;

  if p_kind = 'articles' then
    return query
    select a.id::text,
           md5(coalesce(a.title,'') || e'\x1f' || coalesce(a.ai_summary,'')),
           jsonb_build_object('title', a.title, 'ai_summary', a.ai_summary)
    from public.articles a
    left join public.article_translations t
      on t.article_id = a.id and t.lang = p_lang
    where a.status = 'published'
      and coalesce(a.title,'') <> ''
      and (t.article_id is null
           or t.source_hash is distinct from
              md5(coalesce(a.title,'') || e'\x1f' || coalesce(a.ai_summary,'')))
    order by a.published_at desc nulls last
    limit lim;

  elsif p_kind = 'article_comments' then
    return query
    select c.id::text,
           md5(coalesce(c.body,'')),
           jsonb_build_object('body', c.body)
    from public.article_comments c
    left join public.article_comment_translations t
      on t.comment_id = c.id and t.lang = p_lang
    where c.status = 'active'
      and coalesce(c.body,'') <> ''
      and (t.comment_id is null
           or t.source_hash is distinct from md5(coalesce(c.body,'')))
    order by c.created_at desc
    limit lim;

  elsif p_kind = 'blog_comments' then
    return query
    select c.id::text,
           md5(coalesce(c.body,'')),
           jsonb_build_object('body', c.body)
    from public.blog_comments c
    left join public.blog_comment_translations t
      on t.comment_id = c.id and t.lang = p_lang
    where c.status = 'active'
      and coalesce(c.body,'') <> ''
      and (t.comment_id is null
           or t.source_hash is distinct from md5(coalesce(c.body,'')))
    order by c.created_at desc
    limit lim;

  elsif p_kind = 'forum_posts' then
    return query
    select p.id::text,
           md5(coalesce(p.title,'') || e'\x1f' || coalesce(p.body,'')),
           jsonb_build_object('title', p.title, 'body', p.body)
    from public.forum_posts p
    left join public.forum_post_translations t
      on t.post_id = p.id and t.lang = p_lang
    where p.status = 'active'
      and coalesce(p.title,'') <> ''
      and (t.post_id is null
           or t.source_hash is distinct from
              md5(coalesce(p.title,'') || e'\x1f' || coalesce(p.body,'')))
    order by p.created_at desc
    limit lim;

  elsif p_kind = 'forum_comments' then
    return query
    select c.id::text,
           md5(coalesce(c.body,'')),
           jsonb_build_object('body', c.body)
    from public.forum_comments c
    left join public.forum_comment_translations t
      on t.comment_id = c.id and t.lang = p_lang
    where c.status = 'active'
      and coalesce(c.body,'') <> ''
      and (t.comment_id is null
           or t.source_hash is distinct from md5(coalesce(c.body,'')))
    order by c.created_at desc
    limit lim;

  elsif p_kind = 'forum_thread_summaries' then
    return query
    select s.post_id,
           md5(coalesce(s.summary,'')),
           jsonb_build_object('summary', s.summary)
    from public.forum_thread_summaries s
    left join public.forum_thread_summary_translations t
      on t.post_id = s.post_id and t.lang = p_lang
    where coalesce(s.summary,'') <> ''
      and (t.post_id is null
           or t.source_hash is distinct from md5(coalesce(s.summary,'')))
    order by s.generated_at desc nulls last
    limit lim;
  end if;
end;
$$;

revoke all on function public.community_translation_queue(text, text, integer) from public, anon, authenticated;
grant execute on function public.community_translation_queue(text, text, integer) to service_role;

notify pgrst, 'reload schema';
