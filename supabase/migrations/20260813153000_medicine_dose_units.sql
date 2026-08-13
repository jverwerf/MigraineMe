-- Medicine one-unit system + relief end_at cleanup (2026-08-13)
-- Every medicine gets exactly ONE dose unit, stored on the pool item and stamped
-- per log. Free-text amounts are parsed into dose_value/dose_unit where honest,
-- left null where prose. reliefs.end_at becomes the single duration source.

-- 1) Unit on templates
alter table public.medicine_templates add column if not exists dose_unit text not null default 'mg';
alter table public.medicine_templates drop constraint if exists medicine_templates_dose_unit_check;
alter table public.medicine_templates add constraint medicine_templates_dose_unit_check
  check (dose_unit in ('mg','amount','units','minutes','mcg'));
update public.medicine_templates set dose_unit = 'amount'  where label in ('Excedrin','Migraleve','Symbravo','Treximet');
update public.medicine_templates set dose_unit = 'units'   where label = 'Botox';
update public.medicine_templates set dose_unit = 'minutes' where label = 'Oxygen therapy';
update public.medicine_templates set dose_unit = 'mcg'     where label = 'Vitamin D';

-- 2) Unit on the per-user pool
alter table public.user_medicines add column if not exists dose_unit text not null default 'mg';
alter table public.user_medicines drop constraint if exists user_medicines_dose_unit_check;
alter table public.user_medicines add constraint user_medicines_dose_unit_check
  check (dose_unit in ('mg','amount','units','minutes','mcg'));
update public.user_medicines um set dose_unit = t.dose_unit
  from public.medicine_templates t
  where um.label = t.label and um.dose_unit <> t.dose_unit;

-- Custom medicines: if the user's own logs for it are count-style and never
-- carried a mass unit, its unit is 'amount'; otherwise the mg default stands.
update public.user_medicines um set dose_unit = 'amount'
where not exists (select 1 from public.medicine_templates t where t.label = um.label)
  and exists (
    select 1 from public.medicines m
    where m.user_id = um.user_id and m.name = um.label
      and replace(lower(trim(m.amount)), ',', '.')
          ~ '^[0-9]+(\.[0-9]+)?\s*(tab|tabs|tablet|tablets|cap|caps|capsule|capsules|pill|pills)?s?\.?\s*$'
  )
  and not exists (
    select 1 from public.medicines m
    where m.user_id = um.user_id and m.name = um.label
      and lower(m.amount) ~ '[0-9]\s*(mg|mcg|g|gr)'
  );

-- 3) Structured dose on logs (legacy `amount` text stays for old rows/clients)
alter table public.medicines add column if not exists dose_value numeric;
alter table public.medicines add column if not exists dose_unit text;
alter table public.medicines drop constraint if exists medicines_dose_unit_check;
alter table public.medicines add constraint medicines_dose_unit_check
  check (dose_unit is null or dose_unit in ('mg','amount','units','minutes','mcg'));
alter table public.treatment_regimens add column if not exists dose_value numeric;
alter table public.treatment_regimens add column if not exists dose_unit text;
alter table public.treatment_regimens drop constraint if exists treatment_regimens_dose_unit_check;
alter table public.treatment_regimens add constraint treatment_regimens_dose_unit_check
  check (dose_unit is null or dose_unit in ('mg','amount','units','minutes','mcg'));

-- 4) Backfill: parse historic free text. Bare numbers and tablet-words are a
-- COUNT ('amount'), never assumed mg. Prose stays unparsed.
with parsed as (
  select id, (m[1])::numeric val, m[2] u from (
    select id, regexp_match(replace(lower(trim(amount)), ',', '.'),
                            '^([0-9]+(?:\.[0-9]+)?)\s*([a-zµ×]+)?') m
    from public.medicines
    where amount is not null and trim(amount) <> '' and dose_value is null
  ) s where m is not null
), mapped as (
  select id,
    case
      when u is null or u in ('x','tab','tabs','tablet','tablets','cap','caps','capsule','capsules','pill','pills','sachet','sachets','injection','injections','puff','puffs','spray','sprays') or u = chr(215) then 'amount'
      when u = 'mg' then 'mg'
      when u in ('g','gr','gram','grams') then 'mg'
      when u in ('mcg','ug') or u = chr(181)||'g' then 'mcg'
      when u = 'iu' then 'mcg'
      when u in ('unit','units','u') then 'units'
      when u in ('min','mins','minute','minutes') then 'minutes'
      else null
    end unit,
    case
      when u in ('g','gr','gram','grams') then val * 1000
      when u = 'iu' then round(val / 40, 3)
      else val
    end val
  from parsed
)
update public.medicines x set dose_value = mp.val, dose_unit = mp.unit
from mapped mp where x.id = mp.id and mp.unit is not null;

with parsed as (
  select id, (m[1])::numeric val, m[2] u from (
    select id, regexp_match(replace(lower(trim(amount)), ',', '.'),
                            '^([0-9]+(?:\.[0-9]+)?)\s*([a-zµ×]+)?') m
    from public.treatment_regimens
    where amount is not null and trim(amount) <> '' and dose_value is null
  ) s where m is not null
), mapped as (
  select id,
    case
      when u is null or u in ('x','tab','tabs','tablet','tablets','cap','caps','capsule','capsules','pill','pills') or u = chr(215) then 'amount'
      when u = 'mg' then 'mg'
      when u in ('g','gr','gram','grams') then 'mg'
      when u in ('mcg','ug') or u = chr(181)||'g' then 'mcg'
      when u = 'iu' then 'mcg'
      when u in ('unit','units','u') then 'units'
      when u in ('min','mins','minute','minutes') then 'minutes'
      else null
    end unit,
    case
      when u in ('g','gr','gram','grams') then val * 1000
      when u = 'iu' then round(val / 40, 3)
      else val
    end val
  from parsed
)
update public.treatment_regimens x set dose_value = mp.val, dose_unit = mp.unit
from mapped mp where x.id = mp.id and mp.unit is not null;

-- 5) Reliefs: end_at is the one duration source. Convert stray duration_minutes,
-- then null the zero-span junk (end_at == start_at was a client default, and it
-- poisons insights as 0-minute reliefs).
update public.reliefs set end_at = start_at + make_interval(mins => duration_minutes)
  where duration_minutes is not null and duration_minutes > 0
    and (end_at is null or end_at = start_at);
update public.reliefs set end_at = null where end_at = start_at;
