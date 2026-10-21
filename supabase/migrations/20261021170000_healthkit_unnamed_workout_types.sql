-- Apple Health workouts saved before the 2026-10-21 naming fix: .other
-- (3000) and .mixedCardio (73) had no name and were saved as workout_<n>.
update public.activities set type = 'other'
  where source = 'healthkit' and type = 'workout_3000';
update public.activities set type = 'mixed_cardio'
  where source = 'healthkit' and type = 'workout_73';
update public.activities set type = 'other_' || substring(type from 9)
  where source = 'healthkit' and type ~ '^workout_[0-9]+$';

-- Old iOS builds still send workout_<n>; repair those on arrival too.
create or replace function public.hc_fix_legacy_exercise_type()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  fixed text;
begin
  if new.type is null then return new; end if;
  if new.source = 'healthkit' and new.type ~ '^workout_[0-9]+$' then
    new.type := case new.type
      when 'workout_3000' then 'other'
      when 'workout_73' then 'mixed_cardio'
      else 'other_' || substring(new.type from 9) end;
    return new;
  end if;
  if new.source is distinct from 'health_connect' or new.type !~ '^[A-Z_]+$' then
    return new;
  end if;
  select fixed_name into fixed from public.hc_legacy_exercise_type_map where legacy_name = new.type;
  new.type := coalesce(fixed, lower(new.type));
  return new;
end $$;
