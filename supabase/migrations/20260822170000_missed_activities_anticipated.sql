-- Anticipation: missed activities logged without an attack.
--
-- `anticipated` marks a row logged because an attack was EXPECTED, not because one
-- happened. It is an explicit flag and must never be inferred from a missing attack
-- link: a quick-logged miss on an attack day can be left unlinked, and inferring
-- would silently mislabel it as avoidance.
--
-- `reason_labels` holds the labels the user picked from their trigger and prodrome
-- pools as the reason. Free text keeps using the existing `notes` column.
--
-- `source` is deliberately left alone: the demo-account purge keys on source='demo'.
alter table public.missed_activities
  add column if not exists anticipated boolean not null default false,
  add column if not exists reason_labels text[] null;

comment on column public.missed_activities.anticipated is
  'True when the activity was given up because an attack was expected, not because one happened.';
comment on column public.missed_activities.reason_labels is
  'Trigger/prodrome pool labels chosen as the reason. Free text lives in notes.';
