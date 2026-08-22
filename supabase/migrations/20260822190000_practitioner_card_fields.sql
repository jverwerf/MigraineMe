-- The practitioner card, as designed with Stephanie and approved by her.
--
-- A card is the practitioner in her own words and her own pictures: a
-- landscape from her practice, her portrait, her line about her work, and the
-- things she treats. Without these the directory is a list of names, which is
-- not what anyone chooses a therapist from.
alter table public.practitioners
  add column if not exists banner_url text,
  add column if not exists logo_url text,
  add column if not exists facts jsonb not null default '[]'::jsonb;

comment on column public.practitioners.facts is
  'Up to two short label/value pairs shown as the card''s stat boxes, e.g. [{"k":"Mentoring","v":"3 Monate"},{"k":"Kennenlerngespräch","v":"kostenlos","tone":"good"}]';

alter table public.practitioner_bios
  add column if not exists quote text,
  add column if not exists meta text[] not null default '{}';

comment on column public.practitioner_bios.quote is
  'The practitioner''s own line about her work, in her language. Set in the serif face against the accent rule.';
comment on column public.practitioner_bios.meta is
  'Short standing facts under the chips: where she practises, how long, who she sees.';
alter table public.practitioner_bios
  add column if not exists facts jsonb not null default '[]'::jsonb;
comment on column public.practitioner_bios.facts is
  'The card''s two stat boxes, in this language. Falls back to practitioners.facts when a translation has not set its own.';

update public.practitioner_bios set facts =
  '[{"k":"Mentoring","v":"3 Monate"},{"k":"Kennenlerngespräch","v":"kostenlos","tone":"good"}]'::jsonb
 where lang='de' and practitioner_id=(select id from public.practitioners where slug='stephanie-konkol');
update public.practitioner_bios set facts =
  '[{"k":"Mentoring","v":"3 months"},{"k":"Intro call","v":"free","tone":"good"}]'::jsonb
 where lang='en' and practitioner_id=(select id from public.practitioners where slug='stephanie-konkol');
