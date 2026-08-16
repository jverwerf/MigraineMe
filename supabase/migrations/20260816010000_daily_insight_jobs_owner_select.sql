-- Let a patient read the status of their OWN daily-insight job.
--
-- daily_insight_jobs had row-level security enabled and not one policy, which
-- denies every authenticated read outright. That was harmless while only the
-- worker (service role, which bypasses RLS) touched the table.
--
-- The clinical report now needs it. Changing display language deletes the
-- newest daily_insights row and re-queues its job
-- (trg_requeue_daily_insight_on_lang_change), because the model prose in it
-- cannot be re-translated at the render boundary the way t() strings are. For
-- the minutes that regeneration takes, a report built in that window has no
-- recommendations to show — and daily_insights is not language-stamped, so its
-- absence alone cannot distinguish "regenerating right now" from "this patient
-- has never had insights generated". The job queue is the only place that can.
--
-- Without a read policy build-report-html always sees an empty result, always
-- concludes "nothing pending", and silently omits the Recommendations section
-- exactly as it did before — the fix would be dead code.
--
-- SELECT only, and only the caller's own rows. Writes stay with the worker:
-- a client that could queue or re-queue its own jobs could spend the project's
-- model budget at will. This mirrors the policy the MeSeries/VertigoMe project
-- already carries (daily_insight_jobs_owner_write), narrowed to reads.
--
-- auth.uid() is wrapped in a SELECT so it is evaluated once per query rather
-- than once per row. The existing unique index on (user_id, local_date) already
-- makes user_id the leading column, so no new index is needed.

drop policy if exists daily_insight_jobs_owner_select on public.daily_insight_jobs;

create policy daily_insight_jobs_owner_select
  on public.daily_insight_jobs
  for select
  to authenticated
  using ((select auth.uid()) = user_id);

comment on table public.daily_insight_jobs is
  'Queue for the daily AI insight worker. Written by the worker (service role) '
  'and by trg_requeue_daily_insight_on_lang_change; readable by the owning user '
  'so build-report-html can tell "regenerating" from "never generated".';
