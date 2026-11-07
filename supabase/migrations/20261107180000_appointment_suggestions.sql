-- Two-way appointment suggestions: either side suggests a time, the other accepts
-- or declines, and an accepted time is booked into the practitioner's Cliniko when
-- they use it. Writes go through the appointments edge function; reads keep the
-- existing policies. Rows from before this are the client's own requests.
alter table public.practitioner_appointment_requests
  add column if not exists proposed_by text not null default 'client',
  add column if not exists proposed_start timestamptz,
  add column if not exists proposed_end timestamptz,
  add column if not exists external_provider text,
  add column if not exists external_id text,
  add column if not exists external_type_id text;

do $$ begin
  alter table public.practitioner_appointment_requests
    add constraint practitioner_appointment_requests_proposed_by_check
    check (proposed_by in ('client','practitioner'));
exception when duplicate_object then null; end $$;

create index if not exists practitioner_appointment_requests_prac_status
  on public.practitioner_appointment_requests (practitioner_id, status);
create index if not exists practitioner_appointment_requests_user
  on public.practitioner_appointment_requests (user_id, created_at desc);
