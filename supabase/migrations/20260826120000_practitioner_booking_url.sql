-- A practitioner who already runs her own booking tool should not have to
-- watch a second inbox. When booking_url is set, the app's "Book an intro
-- call" button opens it instead of filing an in-app request.
--
-- Stephanie Konkol asked for this on 2026-08-26: she uses Calendly, and the
-- in-app request would have sat unread in her dashboard while she was away.
alter table public.practitioners
  add column if not exists booking_url text;

comment on column public.practitioners.booking_url is
  'The practitioner''s own booking tool. When set, the app''s "Book an intro call" button opens this URL instead of filing an in-app request.';

update public.practitioners
   set booking_url = 'https://calendly.com/stephaniekonkol', updated_at = now()
 where slug = 'stephanie-konkol';
