-- Background pain: a prodrome for days that hurt without becoming an attack.
-- Spec: ~/dev/migraineme-ios/docs/day-classification-spec.md
-- Locked from deletion, auto-favourited, seeded to new and existing users.

-- 1. Flags ------------------------------------------------------------------
alter table public.prodrome_templates
  add column if not exists locked boolean not null default false,
  add column if not exists auto_favourite boolean not null default false;

alter table public.user_prodromes
  add column if not exists locked boolean not null default false;

comment on column public.user_prodromes.locked is
  'Pool item the user cannot delete because a chart depends on it existing. Still renameable and re-categorisable. Enforced by the delete policy, not only by the UI.';

-- 2. The template -----------------------------------------------------------
insert into public.prodrome_templates
  (label, category, icon_key, prediction_value, baseline_days, enabled_by_default, locked, auto_favourite)
values
  ('Background pain', 'Physical', 'background_pain', 'NONE', 14, false, true, true)
on conflict (label) do update
  set category         = excluded.category,
      icon_key         = excluded.icon_key,
      prediction_value = excluded.prediction_value,
      locked           = excluded.locked,
      auto_favourite   = excluded.auto_favourite;

-- 3. Backfill every existing pool -------------------------------------------
insert into public.user_prodromes
  (user_id, label, category, icon_key, prediction_value, baseline_days, enabled_by_default, locked)
select distinct p.user_id, 'Background pain', 'Physical', 'background_pain', 'NONE', 14, false, true
from public.user_prodromes p
on conflict (user_id, label) do nothing;

update public.user_prodromes
   set locked = true
 where label = 'Background pain'
   and locked = false;

-- 4. Favourite it, at the end of the existing favourites --------------------
insert into public.prodrome_user_preferences (user_id, prodrome_id, status, position)
select p.user_id,
       p.id,
       'frequent',
       coalesce((select max(f.position) + 1
                   from public.prodrome_user_preferences f
                  where f.user_id = p.user_id), 0)
  from public.user_prodromes p
 where p.label = 'Background pain'
   and not exists (select 1
                     from public.prodrome_user_preferences f
                    where f.user_id = p.user_id
                      and f.prodrome_id = p.id);

-- 5. Deletion is refused server-side, not just hidden -----------------------
drop policy if exists "del" on public.user_prodromes;
create policy "del" on public.user_prodromes
  for delete
  using (user_id = auth.uid() and locked = false);
CREATE OR REPLACE FUNCTION public.seed_pools_for_new_user()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'auth'
AS $function$
begin
    INSERT INTO public.user_symptoms (user_id, label, category, icon_key)
    SELECT NEW.id, t.label, t.category, t.icon_key
    FROM public.symptom_templates t
    ON CONFLICT (user_id, label) DO NOTHING;

    INSERT INTO public.user_triggers (user_id, label, category, icon_key, prediction_value, direction, default_threshold, unit, enabled_by_default, metric_table, metric_column, display_group, baseline_days, metric_type)
    SELECT NEW.id, t.label, t.category, t.icon_key, t.prediction_value, t.direction, t.default_threshold, t.unit, t.enabled_by_default, t.metric_table, t.metric_column, t.display_group, t.baseline_days, t.metric_type
    FROM public.trigger_templates t
    ON CONFLICT (user_id, label) DO NOTHING;

    INSERT INTO public.user_medicines (user_id, label, category, dose_unit)
    SELECT NEW.id, t.label, t.category, t.dose_unit
    FROM public.medicine_templates t
    ON CONFLICT (user_id, label) DO NOTHING;

    INSERT INTO public.user_reliefs (user_id, label, category, icon_key, is_automatable, is_automated)
    SELECT NEW.id, t.label, t.category, t.icon_key, t.is_automatable, t.is_automated
    FROM public.relief_templates t
    ON CONFLICT (user_id, label) DO NOTHING;

    -- locked carries through so an item the charts depend on cannot be deleted.
    INSERT INTO public.user_prodromes (user_id, label, category, icon_key, prediction_value, direction, default_threshold, unit, enabled_by_default, metric_table, metric_column, display_group, baseline_days, metric_type, locked)
    SELECT NEW.id, t.label, t.category, t.icon_key, t.prediction_value, t.direction, t.default_threshold, t.unit, t.enabled_by_default, t.metric_table, t.metric_column, t.display_group, t.baseline_days, t.metric_type, t.locked
    FROM public.prodrome_templates t
    ON CONFLICT (user_id, label) DO NOTHING;

    -- auto_favourite items start in the favourites, otherwise they sit below
    -- the fold in the check-in list and nobody finds them.
    INSERT INTO public.prodrome_user_preferences (user_id, prodrome_id, status, position)
    SELECT NEW.id, p.id, 'frequent', 0
    FROM public.user_prodromes p
    JOIN public.prodrome_templates t ON t.label = p.label AND t.auto_favourite
    WHERE p.user_id = NEW.id
    ON CONFLICT (user_id, prodrome_id) DO NOTHING;

    INSERT INTO public.user_locations (user_id, label, category, is_automatable, is_automated)
    SELECT NEW.id, t.label, t.category, t.is_automatable, t.is_automated
    FROM public.location_templates t
    ON CONFLICT (user_id, label) DO NOTHING;

    INSERT INTO public.user_activities (user_id, label, category, is_automatable, is_automated)
    SELECT NEW.id, t.label, t.category, t.is_automatable, t.is_automated
    FROM public.activity_templates t
    ON CONFLICT (user_id, label) DO NOTHING;

    INSERT INTO public.user_missed_activities (user_id, label, category, is_automatable, is_automated)
    SELECT NEW.id, t.label, t.category, t.is_automatable, t.is_automated
    FROM public.missed_activity_templates t
    ON CONFLICT (user_id, label) DO NOTHING;

    INSERT INTO public.user_migraines_pool (user_id, label)
    SELECT NEW.id, t.label
    FROM public.migraine_templates t
    ON CONFLICT (user_id, label) DO NOTHING;

    INSERT INTO public.user_treatment_side_effects (user_id, label, category, icon_key)
    SELECT NEW.id, t.label, t.category, t.icon_key
    FROM public.treatment_side_effect_templates t
    ON CONFLICT (user_id, label) DO NOTHING;

    RETURN NEW;
end;
$function$
