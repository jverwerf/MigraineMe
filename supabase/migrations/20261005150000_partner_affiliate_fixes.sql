-- Affiliate fixes found while setting up the first non-UK partner.

-- 1. Apple reports the offer's REFERENCE NAME on a redemption, not the custom
--    code the customer typed, so partner-offer-attribution could never match
--    partners.apple_offer_code and no iPhone customer was ever attributed.
alter table public.partners
  add column if not exists apple_offer_ref_name text unique;

comment on column public.partners.apple_offer_ref_name is
  'Name of this partner''s offer in App Store Connect. This, not the custom code, is what RevenueCat sends as offer_code on an App Store redemption.';

-- 2. A Stripe Connect account is opened in the country the partner banks in
--    and can never be moved. partner-onboarding-link hardcoded GB.
alter table public.partners
  add column if not exists stripe_country text not null default 'GB'
    check (stripe_country ~ '^[A-Z]{2}$');

-- 3. A partner earns on what a user pays AFTER being attributed. Without a
--    baseline, an existing subscriber who redeemed a partner code handed that
--    partner commission on their whole payment history.
alter table public.partner_attributions
  add column if not exists baseline_proceeds_usd numeric not null default 0;

comment on column public.partner_attributions.baseline_proceeds_usd is
  'RevenueCat lifetime proceeds (USD) this user had already generated when attributed. Subtracted at accrual.';

update public.partners
   set apple_offer_ref_name = 'Affiliate Claire Jarvis Monthly'
 where apple_offer_code = 'CLAIRE30' and apple_offer_ref_name is null;
