-- Five more display languages: Japanese, Korean, Turkish, Polish and Chinese.
-- "zh" is the app's one Chinese table, written as Traditional for Taiwan.
--
-- Additive only: every language already allowed stays allowed. Without this the
-- apps cannot save the new codes to profiles.lang, which is what pushes and AI
-- text read to pick a language.

do $$
declare
  t record;
  new_list text := $l$'de','es','nl','fr','it','pt','ja','ko','tr','pl','zh'$l$;
  def text;
  fn text;
begin
  for t in
    select * from (values
      ('article_comment_translations'), ('article_translations'),
      ('blog_comment_translations'), ('blog_translations'),
      ('forum_comment_translations'), ('forum_post_translations'),
      ('forum_thread_summary_translations'),
      ('shop_group_translations'), ('shop_item_translations'),
      ('usda_foods_i18n')
    ) as v(tbl)
  loop
    execute format('alter table public.%I drop constraint if exists %I', t.tbl, t.tbl || '_lang_check');
    execute format('alter table public.%I add constraint %I check (lang in (%s))', t.tbl, t.tbl || '_lang_check', new_list);
  end loop;

  alter table public.profiles drop constraint if exists profiles_lang_check;
  execute format('alter table public.profiles add constraint profiles_lang_check check (lang in (''en'',%s))', new_list);

  -- The two functions gate on the same list. Rewrite the list inside the live
  -- definition, so nothing else about them can drift from what is deployed.
  foreach fn in array array['search_usda_foods', 'community_translation_queue']
  loop
    select pg_get_functiondef(p.oid) into def
    from pg_proc p
    where p.pronamespace = 'public'::regnamespace and p.proname = fn;
    if position($l$'de','es','nl','fr','it','pt')$l$ in def) = 0 then
      raise exception 'language list not found in %', fn;
    end if;
    execute replace(def, $l$'de','es','nl','fr','it','pt')$l$, new_list || ')');
  end loop;
end $$;

-- Postgres ships a Turkish stemmer but nothing for the other four, so they use
-- 'simple'; search_usda_foods falls back to a substring match for those.
create or replace function public.usda_ts_config(lang text)
returns regconfig
language sql
immutable
parallel safe
strict
set search_path = ''
as $$
  select case lang
    when 'de' then 'german'::regconfig
    when 'es' then 'spanish'::regconfig
    when 'nl' then 'dutch'::regconfig
    when 'fr' then 'french'::regconfig
    when 'it' then 'italian'::regconfig
    when 'pt' then 'portuguese'::regconfig
    when 'tr' then 'turkish'::regconfig
    when 'ja' then 'simple'::regconfig
    when 'ko' then 'simple'::regconfig
    when 'pl' then 'simple'::regconfig
    when 'zh' then 'simple'::regconfig
    else 'english'::regconfig
  end
$$;
