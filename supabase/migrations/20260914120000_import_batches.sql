-- Import Anything (docs/import-anything-spec.md): one row per import run.
-- Holds the canonical model, the linking decisions, the preview shown to the
-- person, their answers, and the manifest of every inserted row id so an
-- import can be undone without a source column on every table.
create table if not exists public.import_batches (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null references auth.users(id) on delete cascade,
  status        text not null default 'preview' check (status in ('preview','writing','committed','failed','undone')),
  source_app    text,
  file_name     text,
  file_sha256   text,
  content_type  text,
  timezone      text,
  model         jsonb,
  linking       jsonb,
  regimens      jsonb,
  preview       jsonb,
  answers       jsonb,
  edits         jsonb,
  manifest      jsonb,
  verify        jsonb,
  cost_usd      numeric,
  created_at    timestamptz not null default now(),
  committed_at  timestamptz,
  undone_at     timestamptz
);
create index if not exists import_batches_user_idx on public.import_batches (user_id, created_at desc);
alter table public.import_batches enable row level security;
drop policy if exists import_batches_owner_read on public.import_batches;
create policy import_batches_owner_read on public.import_batches
  for select using (auth.uid() = user_id);
-- Writes happen only through the import-history edge function (service role).
