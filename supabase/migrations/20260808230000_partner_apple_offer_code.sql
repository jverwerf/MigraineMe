-- iOS attribution. iOS sends "Have a promo code?" to Apple's own
-- redemption sheet (App Store guideline 3.1.1), so our redeem-promo fn
-- never sees it and no partner_attributions row is written — meaning
-- only Android users could be attributed. Fix: give each partner their
-- own APPLE offer code; RevenueCat reports which code was redeemed, and
-- a dedicated webhook maps it back to the partner. No app release needed.
alter table public.partners
  add column apple_offer_code text unique;

comment on column public.partners.apple_offer_code is
  'App Store Connect offer code issued to this partner. Redemptions of it are attributed to them via the partner-offer-attribution webhook.';
