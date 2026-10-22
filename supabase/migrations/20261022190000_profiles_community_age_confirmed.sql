-- Community age gate (Online Safety Act children's access assessment, 2026-10-22).
-- The user confirms "I am 18 or over" once before first opening Community;
-- the timestamp is the record of that self-declaration. Null = not yet asked.
alter table public.profiles add column if not exists community_age_confirmed_at timestamptz;
