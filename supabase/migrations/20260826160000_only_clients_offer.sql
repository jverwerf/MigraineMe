-- A practitioner cannot ask for a diary. Only the patient offers.
--
-- The schema allowed initiated_by='practitioner', and the apps carried a
-- whole "respond to her request" flow for it, but no dashboard ever shipped
-- a way to send one: the only two such rows were seeded by hand. Soliciting
-- a person's health record is not a thing we want to be possible, so the
-- direction goes rather than sitting there waiting for a UI.
--
-- Existing rows are left alone: both are already active and consented.

alter table public.practitioner_clients
  drop constraint if exists practitioner_clients_initiated_by_check;

alter table public.practitioner_clients
  add constraint practitioner_clients_initiated_by_check
  check (initiated_by = 'client') not valid;

comment on column public.practitioner_clients.initiated_by is
  'Always ''client''. Only the patient can offer their diary; a practitioner cannot ask for one. Kept as a column so the two legacy practitioner-initiated rows stay readable and so the constraint states the rule out loud.';
