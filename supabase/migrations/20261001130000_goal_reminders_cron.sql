-- Hourly cron for the goal-reminders edge function (practitioner goals), at :05.
-- Reuses the exact command of the ongoing-migraine-reminder job (same service-role
-- bearer, same project URL) with only the function name swapped, so no secret is
-- written into this file. MigraineMe project only (the dashboard lives there).
select cron.schedule(
  'goal-reminders',
  '5 * * * *',
  (select replace(command, 'ongoing-migraine-reminder', 'goal-reminders')
     from cron.job where jobname = 'ongoing-migraine-reminder')
);
