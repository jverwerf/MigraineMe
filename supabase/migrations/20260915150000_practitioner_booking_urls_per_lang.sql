-- A practitioner whose booking page exists in more than one language should
-- land each reader on the page in the language their app is set to.
-- booking_urls maps a language code to a URL. The app picks the reader's
-- language, then English, then falls back to the single booking_url.
--
-- Martina Dubach 2026-09-15: her Wix booking page has a German and an
-- English version.
alter table public.practitioners
  add column if not exists booking_urls jsonb;

comment on column public.practitioners.booking_urls is
  'Booking page per language, e.g. {"de": "...", "en": "..."}. The app opens the reader''s language, then en, then booking_url.';

update public.practitioners
   set booking_urls = jsonb_build_object(
         'de', 'https://www.naturheilpraxis-md.ch/service-page/migrnetherapie',
         'en', 'https://www.naturheilpraxis-md.ch/en/service-page/migrnetherapie'),
       updated_at = now()
 where slug = 'martina-dubach';
