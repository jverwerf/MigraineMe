-- Per-item side effects on medicine and relief logs.
-- side_effect_scale (NONE/SOFT/MODERATE/SEVERE) stays the OVERALL level and is written by the
-- clients as the max of the items below, so everything that reads it today keeps working.
-- side_effects = [{"label": "Drowsiness", "severity": "SOFT"}, ...], labels from the shared
-- side-effect pool (user_treatment_side_effects).
alter table public.medicines add column if not exists side_effects jsonb not null default '[]'::jsonb;
alter table public.reliefs   add column if not exists side_effects jsonb not null default '[]'::jsonb;
alter table public.medicines drop constraint if exists medicines_side_effects_is_array;
alter table public.medicines add constraint medicines_side_effects_is_array check (jsonb_typeof(side_effects) = 'array');
alter table public.reliefs drop constraint if exists reliefs_side_effects_is_array;
alter table public.reliefs add constraint reliefs_side_effects_is_array check (jsonb_typeof(side_effects) = 'array');
