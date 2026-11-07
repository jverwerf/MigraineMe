-- A client can read the practitioner they are connected to, listed or not. Before this a
-- client of an unlisted (code_only) practitioner saw "Practitioner" instead of the name.
-- The check runs as definer because practitioner_clients' own policies read practitioners,
-- so a plain subquery here would recurse.
create or replace function public.my_linked_practitioner_ids()
returns setof uuid language sql stable security definer set search_path = public as $$
  select practitioner_id from public.practitioner_clients
  where user_id = auth.uid() and status in ('active', 'pending');
$$;
revoke all on function public.my_linked_practitioner_ids() from public, anon;
grant execute on function public.my_linked_practitioner_ids() to authenticated;

drop policy if exists "linked client reads their practitioner" on public.practitioners;
create policy "linked client reads their practitioner" on public.practitioners
  for select to authenticated
  using (id in (select public.my_linked_practitioner_ids()));
