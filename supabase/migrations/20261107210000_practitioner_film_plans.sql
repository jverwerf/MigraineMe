-- Film plans (Jordy 2026-11-07): 2 films free, packages on top. One shared copy of
-- each film on the MigraineMe bucket, so video_path/poster_path may hold a full URL.
alter table public.practitioners add column if not exists extra_films int not null default 0;
create or replace function public.practitioner_film_limit()
returns trigger language plpgsql security definer set search_path = public as $$
declare n int; extra int;
begin
  if new.status = 'active' and (tg_op = 'INSERT' or old.status is distinct from 'active') then
    select count(*) into n from public.practitioner_exercises e
     where e.practitioner_id = new.practitioner_id and e.status = 'active' and e.id <> new.id;
    select extra_films into extra from public.practitioners where id = new.practitioner_id;
    if n >= 2 + coalesce(extra, 0) then raise exception 'film_limit'; end if;
  end if;
  return new;
end $$;
drop trigger if exists practitioner_film_limit on public.practitioner_exercises;
create trigger practitioner_film_limit before insert or update of status on public.practitioner_exercises
  for each row execute function public.practitioner_film_limit();
revoke all on function public.practitioner_film_limit() from public, anon, authenticated;

drop table if exists public.practitioner_film_plans;
create table public.practitioner_film_plans (
  id uuid primary key default gen_random_uuid(),
  owner_email text not null,
  films int not null check (films in (5,10,25,50)),
  stripe_session_id text unique,
  stripe_subscription_id text,
  active boolean not null default false,
  cancel_at_period_end boolean not null default false,
  period_end timestamptz,
  created_at timestamptz not null default now()
);
create index practitioner_film_plans_owner_idx on public.practitioner_film_plans(owner_email);
alter table public.practitioner_film_plans enable row level security;
-- films are compressed before upload now; 150 MB is about 20 minutes at 720p
update storage.buckets set file_size_limit = 157286400 where id = 'practitioner-exercises';
