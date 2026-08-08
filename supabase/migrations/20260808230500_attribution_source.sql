-- Attribution can now arrive two ways: an in-app promo code (Android, via
-- redeem-promo) or an Apple offer code (iOS, via partner-offer-attribution).
-- The Apple path has no promo_codes row, so promo_code_id must be nullable.
alter table public.partner_attributions
  alter column promo_code_id drop not null;

alter table public.partner_attributions
  add column source text not null default 'promo_code'
    check (source in ('promo_code', 'apple_offer_code', 'web_link', 'manual'));

comment on column public.partner_attributions.source is
  'How this user was attributed. apple_offer_code = iOS, where Apple owns redemption and promo_code_id is null.';
