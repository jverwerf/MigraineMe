-- Record which language a clinical assessment was generated in.
--
-- ai_setup_profiles.clinical_assessment is MODEL PROSE (the onboarding
-- neurologist assessment, later replaced by the recalibration's six-section
-- narrative). Like daily_insights it cannot be re-translated at render time,
-- but unlike daily_insights it has NO unattended regeneration path to hook a
-- lang-change trigger onto: recalibrate writes PROPOSALS that the user must
-- review and approve in the app (recalibration-ready-notify just notifies on
-- pending proposals; there is no queue/worker that could write an approved
-- assessment by itself). Deleting the assessment on a language switch would
-- therefore delete a user's only assessment with nothing to replace it — so
-- instead we RECORD the generation language and let each consumer act on a
-- mismatch where it has a path to regenerate:
--
--   - the apps (which own the "Re-assess my profile" flow) can see
--     assessment_lang != profiles.lang and prompt a re-assessment, which now
--     generates in the new language (recalibrate appends languageDirective);
--   - build-report-html keeps printing the assessment regardless — a clinical
--     narrative in its generation language beats a missing one, and the column
--     tells any future consumer exactly what it is looking at.
--
-- The stamp is a trigger on ai_setup_profiles itself rather than code in each
-- writer, because the assessment is written from two places we do not fully
-- control in lockstep: the clients' onboarding save and the
-- apply-recalibration edge function. NULL means "written before this
-- migration": those rows are overwhelmingly English, but we do not pretend to
-- know, and consumers treat NULL as unknown-legacy.

alter table public.ai_setup_profiles
  add column if not exists assessment_lang text;

create or replace function public.stamp_assessment_lang()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  -- Only when the assessment text itself is (re)written; edits to other
  -- profile columns must not re-stamp an old assessment with a new language.
  if tg_op = 'INSERT'
     or (new.clinical_assessment is distinct from old.clinical_assessment) then
    select coalesce(p.lang, 'en') into new.assessment_lang
      from public.profiles p
      where p.user_id = new.user_id;
    new.assessment_lang := coalesce(new.assessment_lang, 'en');
  end if;
  return new;
end;
$$;

drop trigger if exists trg_stamp_assessment_lang on public.ai_setup_profiles;

create trigger trg_stamp_assessment_lang
  before insert or update on public.ai_setup_profiles
  for each row
  execute function public.stamp_assessment_lang();

comment on column public.ai_setup_profiles.assessment_lang is
  'Display language (profiles.lang) at the moment clinical_assessment was written. '
  'NULL = written before 2026-08-14. Consumers with a regeneration path (the apps'' '
  'Re-assess flow) prompt on mismatch; nothing deletes an assessment over language.';
