-- Practitioner exercise library: a practitioner uploads their own exercise films
-- (bucket practitioner-exercises, folder = practitioner id) and assigns them as
-- exercise_count goals with exercise_id = 'pr:<uuid>'. 2026-11-04.
create table if not exists public.practitioner_exercises (
  id uuid primary key default gen_random_uuid(),
  practitioner_id uuid not null references public.practitioners(id) on delete cascade,
  title text not null,
  description text,
  minutes numeric,
  video_path text not null,          -- storage object path inside bucket practitioner-exercises
  poster_path text,
  lang text not null default 'en',
  status text not null default 'active' check (status in ('active','archived')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists practitioner_exercises_practitioner_idx on public.practitioner_exercises(practitioner_id);
alter table public.practitioner_exercises enable row level security;
drop policy if exists "practitioner manages own exercises" on public.practitioner_exercises;
create policy "practitioner manages own exercises" on public.practitioner_exercises for all
  using (exists (select 1 from public.practitioners p where p.id = practitioner_id and p.user_id = auth.uid()))
  with check (exists (select 1 from public.practitioners p where p.id = practitioner_id and p.user_id = auth.uid()));
drop policy if exists "client reads exercises of linked practitioners" on public.practitioner_exercises;
create policy "client reads exercises of linked practitioners" on public.practitioner_exercises for select
  using (exists (select 1 from public.practitioner_clients c where c.practitioner_id = practitioner_exercises.practitioner_id and c.user_id = auth.uid() and c.status = 'active')
      or exists (select 1 from public.practitioner_goals g where g.user_id = auth.uid() and g.exercise_id = 'pr:' || practitioner_exercises.id::text));
drop policy if exists "service role full access" on public.practitioner_exercises;
create policy "service role full access" on public.practitioner_exercises for all using (auth.role() = 'service_role');
grant select, insert, update, delete on public.practitioner_exercises to authenticated;
grant all on public.practitioner_exercises to service_role;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('practitioner-exercises', 'practitioner-exercises', true, 314572800, array['video/mp4','video/quicktime','image/jpeg','image/png'])
on conflict (id) do update set public = true, file_size_limit = excluded.file_size_limit, allowed_mime_types = excluded.allowed_mime_types;
drop policy if exists "practitioner_exercises_public_read" on storage.objects;
create policy "practitioner_exercises_public_read" on storage.objects for select using (bucket_id = 'practitioner-exercises');
drop policy if exists "practitioner_exercises_owner_insert" on storage.objects;
create policy "practitioner_exercises_owner_insert" on storage.objects for insert to authenticated
  with check (bucket_id = 'practitioner-exercises' and exists (select 1 from public.practitioners p where p.id::text = (storage.foldername(name))[1] and p.user_id = auth.uid()));
drop policy if exists "practitioner_exercises_owner_update" on storage.objects;
create policy "practitioner_exercises_owner_update" on storage.objects for update to authenticated
  using (bucket_id = 'practitioner-exercises' and exists (select 1 from public.practitioners p where p.id::text = (storage.foldername(name))[1] and p.user_id = auth.uid()));
drop policy if exists "practitioner_exercises_owner_delete" on storage.objects;
create policy "practitioner_exercises_owner_delete" on storage.objects for delete to authenticated
  using (bucket_id = 'practitioner-exercises' and exists (select 1 from public.practitioners p where p.id::text = (storage.foldername(name))[1] and p.user_id = auth.uid()));
