-- Practice software beyond Cliniko: Semble, Nookal, Halaxy.
--
-- Same three tables, same rules (RLS on, zero policies, only the edge
-- functions read them). The provider list grows, and `config` holds what a
-- vendor needs besides one key: Halaxy's client id and the practitioner role
-- that authors its notes. It stays server-side like api_key.
--
-- Additive only. Cliniko rows and the `cliniko` function are untouched.

alter table public.practitioner_integrations
  drop constraint if exists practitioner_integrations_provider_check;
alter table public.practitioner_integrations
  add constraint practitioner_integrations_provider_check
  check (provider in ('cliniko', 'semble', 'nookal', 'halaxy'));
alter table public.practitioner_integrations
  add column if not exists config jsonb not null default '{}'::jsonb;
