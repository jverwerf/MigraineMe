-- Lamotrigine as a default preventive medicine (Connect IQ review 22 Sep 2026:
-- "I cannot add my medication, i take lamotrigine"). Seeds new users via
-- seed_pools_for_new_user and back-fills every existing pool additively.
insert into public.medicine_templates (label, category, dose_unit)
select 'Lamotrigine', 'Preventive', 'mg'
where not exists (select 1 from public.medicine_templates where lower(label) = 'lamotrigine');

insert into public.user_medicines (user_id, label, category, dose_unit)
select u.user_id, 'Lamotrigine', 'Preventive', 'mg'
  from (select distinct user_id from public.user_medicines) u
 where not exists (
   select 1 from public.user_medicines m
    where m.user_id = u.user_id and lower(m.label) = 'lamotrigine')
on conflict (user_id, label) do nothing;
