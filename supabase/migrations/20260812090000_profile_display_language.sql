-- Display language, so anything the SERVER renders can speak the user's language.
--
-- The picker added in the apps stores the choice on the device only (LangPrefs →
-- SharedPreferences / UserDefaults). That is right for the app itself, which
-- translates at the render boundary and needs no round trip, but it leaves every
-- string rendered off-device stuck in English: push notification titles and
-- bodies, and the messages edge functions hand back for display.
--
-- So the choice is mirrored here. It is a DISPLAY preference and nothing else:
-- exactly as on the client, English stays the canonical value and the
-- translation key everywhere. Trigger and symptom labels, insight groupings and
-- proposal labels are written in English regardless of this column, so a user
-- who switches language keeps their whole history intact.
--
-- 'en' is the default rather than NULL because every consumer wants a language,
-- and English is the source text a missing translation falls back to anyway.
-- That makes a row that predates this migration correct, not merely tolerated.

alter table public.profiles
  add column if not exists lang text not null default 'en';

-- Constrained to the seven languages the apps actually ship tables for. A code
-- outside this set would silently render English forever, which is a bug worth
-- failing the write over rather than discovering in a screenshot.
alter table public.profiles
  drop constraint if exists profiles_lang_check;

alter table public.profiles
  add constraint profiles_lang_check
  check (lang in ('en', 'de', 'es', 'nl', 'fr', 'it', 'pt'));

comment on column public.profiles.lang is
  'Display language for SERVER-rendered text (push notifications, edge function messages). '
  'Mirrors the on-device LangPrefs choice. Display only — canonical data stays English.';

-- No new policies. profiles already carries own-row RLS, and lang is an ordinary
-- column on it: the user updates their own row, the service role reads it when
-- rendering a push. Nothing here widens who can see what.
