-- A chart needs the source a person chose in Data settings when a day has rows
-- from two. Their practitioner may read just that, for an active link, through
-- this function; metric_settings itself stays readable by its owner only.
create or replace function public.metric_preferred_sources(p_user uuid)
returns table(metric text, preferred_source text)
language sql stable security definer set search_path = public as $$
  select m.metric, m.preferred_source
  from public.metric_settings m
  where m.user_id = p_user and m.preferred_source is not null
    and (p_user = auth.uid() or auth.role() = 'service_role' or exists (
      select 1 from public.practitioner_clients c
      join public.practitioners p on p.id = c.practitioner_id
      where c.user_id = p_user and c.status = 'active' and p.user_id = auth.uid()));
$$;
revoke all on function public.metric_preferred_sources(uuid) from public, anon;
grant execute on function public.metric_preferred_sources(uuid) to authenticated, service_role;
