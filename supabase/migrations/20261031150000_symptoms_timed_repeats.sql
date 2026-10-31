-- Symptoms: allow the same symptom more than once per migraine when it carries
-- a time (a chronic user logging nausea on day 1 and again on day 5). Untimed
-- rows (= at attack start) stay one per migraine, so the wizard mirror and
-- every existing app write behave as before.
alter table public.symptoms drop constraint if exists symptoms_migraine_id_type_key;
create unique index if not exists symptoms_migraine_type_untimed_key
  on public.symptoms (migraine_id, type) where start_at is null;
create unique index if not exists symptoms_migraine_type_timed_key
  on public.symptoms (migraine_id, type, start_at) where start_at is not null;

create or replace function public.sync_migraine_symptoms()
returns trigger
language plpgsql
security definer
as $function$
declare
  labels text[];
begin
  if tg_op = 'UPDATE' and old.type is not distinct from new.type then
    return new;
  end if;

  labels := case
    when new.type is null or new.type = 'Migraine' then array[]::text[]
    else array(
      select trim(l)
      from unnest(string_to_array(new.type, ', ')) as l
      where length(trim(l)) > 0
    )
  end;

  -- Rows the user removed from the attack. Only the mirror-managed class is
  -- dropped: postdrome rows are the check-in's own record and are never
  -- deleted by a type change.
  delete from public.symptoms
  where migraine_id = new.id
    and phase = 'active'
    and type <> all (labels);

  if array_length(labels, 1) is null then
    return new;
  end if;

  -- A label that already has a row (timed or not) is left alone, so a timed
  -- repeat never gains an untimed twin.
  insert into public.symptoms (user_id, migraine_id, type)
  select new.user_id, new.id, l
  from unnest(labels) as l
  where not exists (
    select 1 from public.symptoms s where s.migraine_id = new.id and s.type = l
  );

  return new;
end;
$function$;
