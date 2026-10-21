-- Weekly recalibration: skip only people recalibrated in the last 4 days.
--
-- The Monday 08:00 UTC job excluded anyone still inside the 5-day cooldown
-- that apply-recalibration stamps (next_recalibration_at). Apply after
-- Wednesday 08:00 and the cooldown runs past the next Monday run, so the job
-- skipped you and you waited almost two weeks (4 users on 2026-10-19). The
-- cooldown stays for manual re-assess taps; the weekly job now looks at WHEN
-- the last recalibration was applied instead.
select cron.schedule(
  'weekly-recalibration',
  '0 8 * * 1',
  (select replace(command,
      'WHERE rh.next_recalibration_at > NOW()',
      'WHERE rh.created_at > NOW() - INTERVAL ''4 days''')
     from cron.job where jobname = 'weekly-recalibration')
);
