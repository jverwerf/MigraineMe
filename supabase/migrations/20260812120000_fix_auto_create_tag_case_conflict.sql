-- Fix: new-user signup fails with "duplicate key value violates unique
-- constraint tags_name_lower_key".
--
-- WHAT BROKE
-- public.tags carries TWO unique indexes:
--   tags_name_key        unique (name)
--   tags_name_lower_key  unique (lower(name))
-- but auto_create_tag() declared `ON CONFLICT (name)`, which only ever matches
-- the first. A row whose name differs only in CASE from an existing tag skips
-- the DO NOTHING path entirely and raises, and because the tag insert runs in
-- an AFTER INSERT trigger on the user_* pool tables, that exception aborts the
-- whole seeding transaction — so the user is never created and signup fails
-- with no useful message.
--
-- It went unnoticed because it only fires on a case mismatch. Adding the
-- relief template 'CEFALY' on 2026-08-08 collided with the tag 'Cefaly'
-- auto-created from a user's own relief back on 2026-06-20, and every
-- email signup has failed since. Apple sign-ins were unaffected because
-- they do not seed through the same path.
--
-- THE FIX
-- Point ON CONFLICT at the expression index that actually governs uniqueness.
-- With `(lower(name))` inferred, any case variant is caught and skipped, so a
-- future template added in a different case cannot take signup down again.
--
-- Deliberately NOT touching public.tags rows: article_tags references them by
-- id, and both apps read tags by id/name/category, so renaming a tag would
-- risk the community feed for no benefit here.

create or replace function public.auto_create_tag()
returns trigger
language plpgsql
security definer
as $function$
begin
  insert into tags (name, category, source_table, source_key)
  values (new.label, tg_argv[0], tg_table_name, new.label)
  on conflict ((lower(name))) do nothing;
  return new;
end;
$function$;

-- Normalise the template that triggered it, so newly seeded users carry the
-- same casing as the tag that already exists. Existing user_reliefs rows are
-- left alone: they are user data, and the trigger fix already makes them safe.
update public.relief_templates
   set label = 'Cefaly'
 where label = 'CEFALY';
