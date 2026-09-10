-- Stripe onboarding links expire five minutes after they are minted, so a
-- link cannot be emailed to a partner. Instead each partner gets one stable
-- secret URL; hitting it mints a fresh link and redirects. The token is the
-- only credential on that URL, so it is random and unique per partner.
alter table partners add column if not exists onboarding_token text unique;

create index if not exists partners_onboarding_token_idx
  on partners (onboarding_token) where onboarding_token is not null;
