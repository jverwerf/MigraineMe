-- The cached treatment narrative is model prose written in the user's display
-- language at generation time. generate-treatment-narrative already keys its
-- cache-hit check on a lang-aware fingerprint, but build-report-html cannot
-- recompute that fingerprint (it is a hash over stats it does not fetch), so
-- the doctor report had no way to tell a French paragraph from an English one
-- and could print a stale-language narrative after a language switch.
--
-- This column records which language the row's prose is IN. It is NOT a
-- translated value stored in the DB (the i18n contract forbids those): it is
-- metadata about model output, the same way daily_insights carry their
-- generation language implicitly. The report filters on it and omits the
-- paragraph when it does not match; the app regenerates the narrative in the
-- new language on next view via the fingerprint.
--
-- default 'en': every row written before the fingerprint fix (2026-08-13) was
-- English. Rows written between that fix and this migration in another
-- language are mislabelled 'en' at worst, and self-correct on their next
-- regeneration (7-day cap, or immediately after a stats/language change).

alter table public.treatment_narrative_cache
  add column if not exists lang text not null default 'en';
