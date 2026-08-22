-- Mock practitioner rig: Jordy as the practitioner, three existing test
-- accounts as clients in deliberately different states.

with p as (
  insert into public.partners (name, contact_email, jurisdiction, commission_pct, status, notes)
  values ('Jordy Verwerft (test practitioner)', 'verwerft.j@gmail.com', 'GB', 0,
          'active', 'Mock row for testing the practitioner side. Not a real partner.')
  returning id
)
insert into public.practitioners (
  partner_id, user_id, slug, display_name, practice_name, discipline,
  website, languages, country, city, consult_mode, listing_mode, founding,
  registration_body, registration_number, qualification,
  insurance_provider, insurance_expiry,
  vetted_at, vetted_by, terms_accepted_at, permitted_declared_at, status, notes
)
select p.id,
       'c1da588d-77ff-49dc-aad7-bfb66ecde798'::uuid,
       'jordy-verwerft-test',
       'Jordy Verwerft',
       'MigraineMe Test Practice',
       'nutritional_therapist',
       'https://migraineme.app',
       array['en','nl'],
       'GB', 'London', 'both', 'bookable', true,
       'Test Register', 'TEST-0001', 'Test qualification',
       'Test Insurance Ltd', date '2027-08-22',
       now(), 'seed', now(), now(), 'active',
       'MOCK ROW - safe to delete. Created for testing the practitioner side.'
from p;

insert into public.practitioner_bios (practitioner_id, lang, headline, bio, treats, is_source)
select id, 'en',
  'Test practice for the practitioner side',
  'This is a mock listing used to test the directory, the dashboard and the consent flow end to end. It is not a real practice and should not be shown to users.',
  array['Migraine','Hormonal migraine','Food triggers','Sleep'],
  true
from public.practitioners where slug = 'jordy-verwerft-test';

-- Client 1: everything shared. The happy path.
insert into public.practitioner_clients
  (practitioner_id, user_id, status, initiated_by, requested_scopes, scopes, connected_at, last_viewed_at)
select pr.id, 'e884b0d6-eec8-498e-8f8d-7923bc83a377'::uuid, 'active', 'practitioner',
  array['attacks','triggers_food','sleep_activity','medication','cycle'],
  array['attacks','triggers_food','sleep_activity','medication','cycle'],
  now() - interval '21 days', now() - interval '2 days'
from public.practitioners pr where pr.slug = 'jordy-verwerft-test';

-- Client 2: partial consent. Asked for everything, said no to food and cycle.
-- This is the case the dashboard has to render as "not shared", not "empty".
insert into public.practitioner_clients
  (practitioner_id, user_id, status, initiated_by, requested_scopes, scopes, connected_at, last_viewed_at)
select pr.id, 'd476b31d-ae0d-4538-9edf-43967b7dac83'::uuid, 'active', 'practitioner',
  array['attacks','triggers_food','sleep_activity','medication','cycle'],
  array['attacks','sleep_activity','medication'],
  now() - interval '9 days', now() - interval '6 days'
from public.practitioners pr where pr.slug = 'jordy-verwerft-test';

-- Client 3: asked, not yet answered. Nothing may be read.
insert into public.practitioner_clients
  (practitioner_id, user_id, status, initiated_by, requested_scopes, scopes)
select pr.id, '401f43ed-b6ca-4b42-829f-14564ac4fc1f'::uuid, 'pending', 'practitioner',
  array['attacks','triggers_food'], array[]::text[]
from public.practitioners pr where pr.slug = 'jordy-verwerft-test';

-- Consent history, so the audit trail has something in it.
insert into public.practitioner_consent_events (link_id, action, scopes_before, scopes_after, actor, surface, created_at)
select pc.id, 'requested', array[]::text[], pc.requested_scopes, 'practitioner', 'web', pc.created_at
from public.practitioner_clients pc
join public.practitioners pr on pr.id = pc.practitioner_id
where pr.slug = 'jordy-verwerft-test';

insert into public.practitioner_consent_events (link_id, action, scopes_before, scopes_after, actor, surface, created_at)
select pc.id, 'granted', array[]::text[], pc.scopes, 'client', 'android', pc.connected_at
from public.practitioner_clients pc
join public.practitioners pr on pr.id = pc.practitioner_id
where pr.slug = 'jordy-verwerft-test' and pc.status = 'active';

-- Reads, so "last looked on" has history behind it.
insert into public.practitioner_access_log (practitioner_id, user_id, scopes, viewed_at)
select pc.practitioner_id, pc.user_id, pc.scopes, pc.last_viewed_at
from public.practitioner_clients pc
join public.practitioners pr on pr.id = pc.practitioner_id
where pr.slug = 'jordy-verwerft-test' and pc.last_viewed_at is not null;

-- One open initial contact request, so stage 4 has something to render.
insert into public.practitioner_appointment_requests
  (practitioner_id, user_id, kind, message, preferred_times, status)
select pr.id, '401f43ed-b6ca-4b42-829f-14564ac4fc1f'::uuid, 'initial',
  'Would like to talk through my food triggers.', 'Weekday mornings', 'requested'
from public.practitioners pr where pr.slug = 'jordy-verwerft-test';

-- ---------------------------------------------------------------------------
-- To remove the whole rig:
--
--   delete from public.partners where id in (
--     select partner_id from public.practitioners where slug = 'jordy-verwerft-test'
--   );
--   -- practitioners and everything hanging off it cascades from there, except
--   -- the partners row, which is why it is deleted last. Run the practitioners
--   -- delete first if the FK complains:
--   -- delete from public.practitioners where slug = 'jordy-verwerft-test';
--
-- MigraineMe project only. The MeSeries project has its own auth.users, so
-- these user ids do not exist there and the insert correctly refuses.
-- ---------------------------------------------------------------------------
