-- Practitioner seats, second version (Jordy 2026-11-07): one pool of seats that works
-- in all five apps, not per app. No seat had been sold, so the first tables are replaced.
drop trigger if exists premium_status_keep_seat on public.premium_status;
drop function if exists public.claim_practitioner_seat(uuid, text);
drop function if exists public.claim_practitioner_seat(uuid, text, text);
drop function if exists public.practitioner_seats_fulfil(uuid);
drop function if exists public.seat_new_code();
drop table if exists public.practitioner_seats;
drop table if exists public.practitioner_seat_orders;

-- The one list of seats, here on the MigraineMe backend: a seat is bought once and
-- works in any of the five apps. Owners are identified by verified e-mail because
-- a practitioner may be listed on either backend. Other backends reach this through
-- the practitioner-seats function with a shared secret.
create table public.practitioner_seat_orders (
  id uuid primary key default gen_random_uuid(),
  owner_email text not null,
  owner_name text,
  owner_lang text,
  quantity int not null check (quantity between 1 and 100),
  currency text not null check (currency in ('gbp','usd','eur')),
  unit_amount int not null check (unit_amount > 0),
  stripe_session_id text unique,
  status text not null default 'pending' check (status in ('pending','paid','expired')),
  created_at timestamptz not null default now(),
  paid_at timestamptz
);
create index practitioner_seat_orders_owner_idx on public.practitioner_seat_orders(owner_email);
create table public.practitioner_seats (
  id uuid primary key default gen_random_uuid(),
  order_id uuid not null references public.practitioner_seat_orders(id),
  owner_email text not null,
  code text not null unique,
  label text,
  status text not null default 'available' check (status in ('available','claimed')),
  claimed_app text,
  client_ref text,
  claimed_at timestamptz,
  ends_at timestamptz,
  reminded_at timestamptz,
  created_at timestamptz not null default now()
);
create index practitioner_seats_owner_idx on public.practitioner_seats(owner_email);
alter table public.practitioner_seat_orders enable row level security;
alter table public.practitioner_seats enable row level security;

-- No look-alike characters (I, L, O, 0, 1): these get read out and retyped.
create or replace function public.seat_new_code()
returns text language plpgsql volatile set search_path = public, extensions as $$
declare
  a text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
  b bytea := gen_random_bytes(10);
  r text := '';
begin
  for i in 0..9 loop
    r := r || substr(a, (get_byte(b, i) % 31) + 1, 1);
    if i = 4 then r := r || '-'; end if;
  end loop;
  return r;
end $$;

-- Paid order to seats. Safe to call twice: only pending -> paid creates anything.
create or replace function public.practitioner_seats_fulfil(p_order uuid)
returns int language plpgsql security definer set search_path = public as $$
declare o public.practitioner_seat_orders;
begin
  update public.practitioner_seat_orders set status = 'paid', paid_at = now()
   where id = p_order and status = 'pending' returning * into o;
  if not found then return 0; end if;
  for i in 1..o.quantity loop
    insert into public.practitioner_seats(order_id, owner_email, code)
    values (o.id, o.owner_email, public.seat_new_code());
  end loop;
  return o.quantity;
end $$;

-- The row lock means two people can never take the same code. Claiming again from
-- the same account just answers with the same seat, so a failed step can be retried.
create or replace function public.practitioner_seat_take(p_code text, p_app text, p_client text, p_ends timestamptz)
returns table(result text, seat_ends_at timestamptz, seat_owner text)
language plpgsql security definer set search_path = public as $$
declare s public.practitioner_seats;
begin
  select * into s from public.practitioner_seats where code = p_code for update;
  if not found then return query select 'invalid'::text, null::timestamptz, null::text; return; end if;
  if s.status <> 'available' then
    if s.client_ref = p_client then return query select 'already_yours'::text, s.ends_at, s.owner_email; return; end if;
    return query select 'used'::text, null::timestamptz, null::text; return;
  end if;
  update public.practitioner_seats set status = 'claimed', claimed_app = p_app, client_ref = p_client, claimed_at = now(), ends_at = p_ends
   where id = s.id;
  return query select 'claimed'::text, p_ends, s.owner_email;
end $$;

revoke all on function public.seat_new_code() from public, anon, authenticated;
revoke all on function public.practitioner_seats_fulfil(uuid) from public, anon, authenticated;
revoke all on function public.practitioner_seat_take(text, text, text, timestamptz) from public, anon, authenticated;
grant execute on function public.practitioner_seats_fulfil(uuid) to service_role;
grant execute on function public.practitioner_seat_take(text, text, text, timestamptz) to service_role;

-- Local record of a seat a client holds in an app on this backend. The apps read
-- premium_status as before; this table only keeps that status correct.
create table public.practitioner_seat_grants (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  
  code text not null unique,
  ends_at timestamptz not null,
  status text not null default 'live' check (status in ('live','ended')),
  created_at timestamptz not null default now()
);
create index practitioner_seat_grants_live_idx on public.practitioner_seat_grants(user_id, ends_at) where status = 'live';
alter table public.practitioner_seat_grants enable row level security;

alter table public.partner_attributions drop constraint if exists partner_attributions_source_check;
alter table public.partner_attributions add constraint partner_attributions_source_check
  check (source = any (array['promo_code','apple_offer_code','web_link','manual','seat']));

-- Premium on. A store subscription still running keeps its row; the trigger takes
-- over when it ends. Trial dates are NOT NULL and checked before ON CONFLICT, so
-- they have to be here; an existing row keeps its own.
create or replace function public.practitioner_seat_grant(p_user uuid, p_code text, p_ends timestamptz)
returns void language plpgsql security definer set search_path = public as $$
begin
  insert into public.practitioner_seat_grants(user_id, code, ends_at)
  values (p_user, p_code, p_ends) on conflict (code) do nothing;
  insert into public.premium_status(user_id, trial_start, trial_end, rc_subscription_status, rc_product_id, rc_expiry_date)
  values (p_user, now(), now(), 'active', 'practitioner_seat', p_ends)
  on conflict (user_id) do update
    set rc_subscription_status = 'active',
        rc_product_id = case when premium_status.rc_subscription_status = 'active'
                              and coalesce(premium_status.rc_product_id, '') <> 'practitioner_seat'
                             then premium_status.rc_product_id else 'practitioner_seat' end,
        rc_expiry_date = case when premium_status.rc_subscription_status = 'active'
                               and coalesce(premium_status.rc_product_id, '') <> 'practitioner_seat'
                              then premium_status.rc_expiry_date else p_ends end;
end $$;

-- A client on a seat whose old store subscription runs out: RevenueCat's webhook
-- writes 'expired', which must not cost them the year someone paid for.
create or replace function public.premium_status_keep_seat()
returns trigger language plpgsql security definer set search_path = public as $$
declare ends timestamptz;
begin
  if new.rc_subscription_status is distinct from 'active' then
    select max(g.ends_at) into ends from public.practitioner_seat_grants g
     where g.user_id = new.user_id  and g.status = 'live' and g.ends_at > now();
    if ends is not null then
      new.rc_subscription_status := 'active';
      new.rc_product_id := 'practitioner_seat';
      new.rc_expiry_date := ends;
    end if;
  end if;
  return new;
end $$;
drop trigger if exists premium_status_keep_seat on public.premium_status;
create trigger premium_status_keep_seat before update on public.premium_status
  for each row execute function public.premium_status_keep_seat();

-- Ends grants whose year is up. A store subscription bought meanwhile is untouched.
create or replace function public.practitioner_seats_expire()
returns int language plpgsql security definer set search_path = public as $$
declare n int;
begin
  update public.practitioner_seat_grants set status = 'ended' where status = 'live' and ends_at <= now();
  get diagnostics n = row_count;
  update public.premium_status ps set rc_subscription_status = 'expired'
   where ps.rc_product_id = 'practitioner_seat' and ps.rc_subscription_status = 'active'
     and not exists (select 1 from public.practitioner_seat_grants g
                      where g.user_id = ps.user_id  and g.status = 'live');
  return n;
end $$;

create or replace function public.seat_user_needs_password(p_user uuid)
returns boolean language sql security definer set search_path = public, auth as $$
  select coalesce(u.encrypted_password, '') = ''
     and not exists (select 1 from auth.identities i where i.user_id = u.id and i.provider <> 'email')
    from auth.users u where u.id = p_user;
$$;

revoke all on function public.practitioner_seat_grant(uuid, text, timestamptz) from public, anon, authenticated;
revoke all on function public.practitioner_seats_expire() from public, anon, authenticated;
revoke all on function public.seat_user_needs_password(uuid) from public, anon, authenticated;
revoke all on function public.premium_status_keep_seat() from public, anon, authenticated;
grant execute on function public.practitioner_seat_grant(uuid, text, timestamptz) to service_role;
grant execute on function public.practitioner_seats_expire() to service_role;
grant execute on function public.seat_user_needs_password(uuid) to service_role;

select cron.unschedule('practitioner-seats-expire');
select cron.schedule('practitioner-seats-expire', '10 3 * * *', 'select public.practitioner_seats_expire()');
