-- Weekly recalibration: stagger the Monday run and skip non-premium users.
--
-- The job posted one request per eligible user in the same second. On
-- 2026-11-02 08:00 that was ~110 boots at once: 25 got 403 (not premium,
-- which the function rejects anyway), 74 got 503/429 from the platform,
-- and only 11 actually ran, so most premium users got no weekly check-in.
--
-- Now: only premium/trial users (same rule as the function's own check),
-- spread over 24 five-minute slots 08:02-09:57 UTC by a hash of the user id.
-- Each user lands in exactly one slot; 36 premium users = max 4 per slot.
select cron.schedule(
  'weekly-recalibration',
  '2-57/5 8,9 * * 1',
  (select replace(command,
      'WHERE (SELECT COUNT(*) FROM migraines m',
      'WHERE mod(abs(hashtext(p.user_id::text)), 24) = ((extract(hour from now() at time zone ''UTC'')::int - 8) * 12 + extract(minute from now() at time zone ''UTC'')::int / 5)
          AND EXISTS (SELECT 1 FROM premium_status ps WHERE ps.user_id = p.user_id
                      AND (ps.trial_end > NOW() OR ps.rc_subscription_status IN (''active'', ''grace_period'')))
          AND (SELECT COUNT(*) FROM migraines m')
     from cron.job where jobname = 'weekly-recalibration')
);
