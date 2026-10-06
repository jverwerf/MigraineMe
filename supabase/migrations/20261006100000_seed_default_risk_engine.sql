-- Risk engine defaults per user. Mirrors Android EdgeFunctionsService
-- seedDefaultRiskDecayWeights + seedDefaultRiskGaugeThresholds (ignore-duplicates)
-- and the MeSeries seed_default_risk_engine RPC (no app_id on this project).
-- iOS seeded nothing at login, so users who skipped the AI setup never got
-- decay weights and risk-score-dispatcher skipped them: empty gauge forever
-- (55 active iOS users on 2026-10-06).
create or replace function public.seed_default_risk_engine(p_user_id uuid)
returns void
language plpgsql
security definer
set search_path to 'public'
as $$
begin
  if p_user_id is null then return; end if;
  -- A signed-in caller may only seed itself; service role may seed anyone.
  if auth.uid() is not null and auth.uid() <> p_user_id then
    raise exception 'seed_default_risk_engine: cannot seed another user';
  end if;

  insert into public.risk_decay_weights (user_id, severity, day_0, day_1, day_2, day_3, day_4, day_5, day_6)
  select p_user_id, v.severity, v.d0, v.d1, v.d2, 0, 0, 0, 0
  from (values ('HIGH', 10.0, 5.0, 2.5), ('MILD', 6.0, 3.0, 1.5), ('LOW', 3.0, 1.5, 0.0)) v(severity, d0, d1, d2)
  where not exists (select 1 from public.risk_decay_weights w where w.user_id = p_user_id and w.severity = v.severity);

  insert into public.risk_gauge_thresholds (user_id, zone, min_value)
  select p_user_id, v.zone, v.min_value
  from (values ('NONE', 0.0), ('LOW', 3.0), ('MILD', 5.0), ('HIGH', 10.0)) v(zone, min_value)
  where not exists (select 1 from public.risk_gauge_thresholds t where t.user_id = p_user_id and t.zone = v.zone);
end $$;

revoke all on function public.seed_default_risk_engine(uuid) from public;
grant execute on function public.seed_default_risk_engine(uuid) to authenticated, service_role;

-- One-off backfill: every existing user without decay weights (397 on
-- 2026-10-06, 55 of them active iOS users with an empty gauge). Idempotent.
select count(*) as seeded from (
  select public.seed_default_risk_engine(u.id)
  from auth.users u
  where not exists (select 1 from public.risk_decay_weights w where w.user_id = u.id)
) s;
