-- Regenerate the daily insight when the display language changes.
--
-- daily_insights rows are MODEL PROSE (insight, ai_recommendations, positives)
-- generated once a day in whatever language profiles.lang held at generation
-- time. Unlike every t()-rendered string, they cannot be re-translated at the
-- render boundary — so a user who switches language kept seeing yesterday's
-- language on the insight and recommendation cards until the next nightly run.
--
-- The apps all mirror a language change onto profiles.lang (LangPrefs.set →
-- syncLang). Hooking that write here fixes every client at once — Android, iOS
-- and anything future — with no client release needed:
--
--   1. Delete the newest daily_insights row (the one Home/Insights render),
--      but only if it is recent enough to still be "today" in some timezone.
--      Older rows are history in the language of their day; rewriting the
--      archive would cost an AI call per row and change the record.
--   2. Re-queue its generation job. The ai-daily-insight-worker cron picks
--      queued jobs up within 5 minutes and regenerates in the new language.
--      (The worker skips a job whose daily_insights row still exists, which is
--      why the delete comes first, and the dispatcher only enqueues at 10am
--      local — without this re-queue the row would stay gone until tomorrow.)
--
-- treatment_narrative_cache needs no trigger: its fingerprint now includes the
-- language, so the next open regenerates by itself.

create or replace function public.requeue_daily_insight_on_lang_change()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  v_date date;
begin
  if new.lang is distinct from old.lang then
    -- The newest row is the one on screen. daily_insights.date is the user's
    -- local date, so "could still be today somewhere" = within a day of UTC.
    select max(date) into v_date
      from public.daily_insights
      where user_id = new.user_id;

    if v_date is not null and v_date >= (current_date - 1) then
      delete from public.daily_insights
        where user_id = new.user_id and date = v_date;

      insert into public.daily_insight_jobs (user_id, local_date, status)
      values (new.user_id, v_date::text, 'queued')
      on conflict (user_id, local_date)
        do update set status = 'queued', error = null, updated_at = now();
    end if;
  end if;
  return new;
end;
$$;

drop trigger if exists trg_requeue_daily_insight_on_lang_change on public.profiles;

create trigger trg_requeue_daily_insight_on_lang_change
  after update of lang on public.profiles
  for each row
  execute function public.requeue_daily_insight_on_lang_change();

comment on function public.requeue_daily_insight_on_lang_change is
  'profiles.lang changed: drop the newest (on-screen) daily_insights row and re-queue its '
  'generation job, so the AI insight/recommendation/positives cards regenerate in the new '
  'language within minutes instead of surviving in the old one until the next 10am run.';
