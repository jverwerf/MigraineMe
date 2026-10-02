-- 1) Never re-hand someone a code from the dead June batch
CREATE OR REPLACE FUNCTION public.claim_winback_android_code(p_email text)
 RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path TO 'public'
AS $function$
declare
  v_code text;
begin
  select code into v_code
    from public.winback_android_codes c
   where c.assigned_email = lower(p_email)
     and c.status = 'assigned'
     and not exists (select 1 from public.winback_android_codes v where v.batch = c.batch and v.status = 'void')
   order by c.id desc
   limit 1;
  if v_code is not null then
    return v_code;
  end if;
  update public.winback_android_codes
     set status = 'assigned', assigned_email = lower(p_email), assigned_at = now()
   where id = (select id from public.winback_android_codes where status = 'available' order by id for update skip locked limit 1)
  returning code into v_code;
  return v_code;
end;
$function$;
