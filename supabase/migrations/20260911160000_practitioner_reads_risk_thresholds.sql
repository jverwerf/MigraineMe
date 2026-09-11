-- The practitioner board draws the risk history the way the app draws it:
-- zone bands and a line coloured by the zone it sits in. Those zones are the
-- client's own thresholds, recalibrated weekly, so reading the graph against
-- the 3/5/10 defaults would mislabel every client the recalibration has moved.
-- Same 'risk' scope as risk_score_daily itself: no new consent, no wider grant.
do $$
begin
  if exists (select 1 from information_schema.columns
    where table_schema='public' and table_name='risk_gauge_thresholds' and column_name='user_id') then
    drop policy if exists "consented practitioner reads" on public.risk_gauge_thresholds;
    create policy "consented practitioner reads" on public.risk_gauge_thresholds
      for select using (public.practitioner_can_read(user_id, 'risk'));
  end if;
end $$;
