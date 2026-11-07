-- Practitioner seats: a practitioner pays for a client's year by card on
-- me-practitioner.com, the client claims it with a code on migraineme.app/seat.
-- Optional on top of the store subscriptions, which stay exactly as they are.
--
-- A live seat is stored in premium_status as rc_subscription_status='active'
-- with rc_product_id='practitioner_seat'. Both apps already read that as premium,
-- and trial reminders + winback already skip 'active', so nothing else changes.
-- trial_end is left alone on purpose: when the seat ends it is long past, so no
-- reminder or winback fires off the seat.

create table if not exists public.practitioner_seat_orders (
  id uuid primary key default gen_random_uuid(),
  practitioner_id uuid not null references public.practitioners(id),
  quantity int not null check (quantity between 1 and 100),
  currency text not null check (currency in ('gbp','usd','eur')),
  unit_amount int not null check (unit_amount > 0),
  stripe_session_id text unique,
  status text not null default 'pending' check (status in ('pending','paid','expired')),
  created_at timestamptz not null default now(),
  paid_at timestamptz
);

create table if not exists public.practitioner_seats (
  id uuid primary key default gen_random_uuid(),
  order_id uuid not null references public.practitioner_seat_orders(id),
  practitioner_id uuid not null references public.practitioners(id),
  code text not null unique,
  label text,
  status text not null default 'available' check (status in ('available','claimed','ended')),
  claimed_by uuid references auth.users(id) on delete set null,
  claimed_at timestamptz,
  ends_at timestamptz,
  reminded_at timestamptz,
  created_at timestamptz not null default now()
);
create index if not exists practitioner_seats_practitioner_idx on public.practitioner_seats(practitioner_id);
create index if not exists practitioner_seats_live_idx on public.practitioner_seats(claimed_by, ends_at) where status = 'claimed';

-- Everything goes through the practitioner-seats edge function (service role).
alter table public.practitioner_seat_orders enable row level security;
alter table public.practitioner_seats enable row level security;

-- Commission link: a client who claims a seat is tagged to the practitioner.
alter table public.partner_attributions drop constraint if exists partner_attributions_source_check;
alter table public.partner_attributions add constraint partner_attributions_source_check
  check (source = any (array['promo_code','apple_offer_code','web_link','manual','seat']));

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

-- Turns a paid order into seats. Safe to call twice: only the pending -> paid
-- step creates anything.
create or replace function public.practitioner_seats_fulfil(p_order uuid)
returns int language plpgsql security definer set search_path = public as $$
declare o public.practitioner_seat_orders; n int := 0;
begin
  update public.practitioner_seat_orders set status = 'paid', paid_at = now()
   where id = p_order and status = 'pending' returning * into o;
  if not found then return 0; end if;
  for n in 1..o.quantity loop
    insert into public.practitioner_seats(order_id, practitioner_id, code)
    values (o.id, o.practitioner_id, public.seat_new_code());
  end loop;
  return o.quantity;
end $$;

-- Claim one seat. The row lock means two people can never take the same code.
-- A second seat for someone already on one starts when the first ends.
create or replace function public.claim_practitioner_seat(p_user uuid, p_code text)
returns table(result text, seat_ends_at timestamptz, seat_practitioner uuid)
language plpgsql security definer set search_path = public as $$
declare s public.practitioner_seats; base timestamptz; ends timestamptz;
begin
  select * into s from public.practitioner_seats where code = p_code for update;
  if not found then return query select 'invalid'::text, null::timestamptz, null::uuid; return; end if;
  if s.status <> 'available' then
    if s.claimed_by = p_user then return query select 'already_yours'::text, s.ends_at, s.practitioner_id; return; end if;
    return query select 'used'::text, null::timestamptz, null::uuid; return;
  end if;
  select greatest(now(), coalesce(max(x.ends_at), now())) into base
    from public.practitioner_seats x where x.claimed_by = p_user and x.status = 'claimed';
  ends := base + interval '1 year';
  update public.practitioner_seats set status = 'claimed', claimed_by = p_user, claimed_at = now(), ends_at = ends
   where id = s.id;
  -- A store subscription that is still running keeps its row; the trigger below
  -- takes over when it ends.
  -- The trial dates are NOT NULL and that check runs before ON CONFLICT, so they
  -- have to be here; an existing row keeps its own.
  insert into public.premium_status(user_id, trial_start, trial_end, rc_subscription_status, rc_product_id, rc_expiry_date)
  values (p_user, now(), now(), 'active', 'practitioner_seat', ends)
  on conflict (user_id) do update
    set rc_subscription_status = 'active',
        rc_product_id = case when premium_status.rc_subscription_status = 'active'
                              and coalesce(premium_status.rc_product_id, '') <> 'practitioner_seat'
                             then premium_status.rc_product_id else 'practitioner_seat' end,
        rc_expiry_date = case when premium_status.rc_subscription_status = 'active'
                               and coalesce(premium_status.rc_product_id, '') <> 'practitioner_seat'
                              then premium_status.rc_expiry_date else ends end;
  -- 10% when the client later stays and pays. Existing rates are never lowered.
  update public.partners pa set commission_pct = 10
    from public.practitioners pr
   where pr.id = s.practitioner_id and pa.id = pr.partner_id and coalesce(pa.commission_pct, 0) = 0;
  update public.partners pa set status = 'active'
    from public.practitioners pr
   where pr.id = s.practitioner_id and pa.id = pr.partner_id and pa.status = 'pending';
  return query select 'claimed'::text, ends, s.practitioner_id;
end $$;

-- A client on a seat whose old store subscription runs out: RevenueCat's webhook
-- writes 'expired', which must not cost them the year someone paid for.
create or replace function public.premium_status_keep_seat()
returns trigger language plpgsql security definer set search_path = public as $$
declare ends timestamptz;
begin
  if new.rc_subscription_status is distinct from 'active' then
    select max(x.ends_at) into ends from public.practitioner_seats x
     where x.claimed_by = new.user_id and x.status = 'claimed' and x.ends_at > now();
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

-- Ends seats whose year is up. A store subscription bought meanwhile is untouched.
create or replace function public.practitioner_seats_expire()
returns int language plpgsql security definer set search_path = public as $$
declare n int;
begin
  update public.practitioner_seats set status = 'ended' where status = 'claimed' and ends_at <= now();
  get diagnostics n = row_count;
  update public.premium_status ps set rc_subscription_status = 'expired'
   where ps.rc_product_id = 'practitioner_seat' and ps.rc_subscription_status = 'active'
     and not exists (select 1 from public.practitioner_seats x
                      where x.claimed_by = ps.user_id and x.status = 'claimed');
  return n;
end $$;

-- Someone who only ever used an emailed link has no way to sign in to the app yet.
create or replace function public.seat_user_needs_password(p_user uuid)
returns boolean language sql security definer set search_path = public, auth as $$
  select coalesce(u.encrypted_password, '') = ''
     and not exists (select 1 from auth.identities i where i.user_id = u.id and i.provider <> 'email')
    from auth.users u where u.id = p_user;
$$;

revoke all on function public.seat_new_code() from public, anon, authenticated;
revoke all on function public.practitioner_seats_fulfil(uuid) from public, anon, authenticated;
revoke all on function public.claim_practitioner_seat(uuid, text) from public, anon, authenticated;
revoke all on function public.practitioner_seats_expire() from public, anon, authenticated;
revoke all on function public.seat_user_needs_password(uuid) from public, anon, authenticated;
revoke all on function public.premium_status_keep_seat() from public, anon, authenticated;
grant execute on function public.practitioner_seats_fulfil(uuid) to service_role;
grant execute on function public.claim_practitioner_seat(uuid, text) to service_role;
grant execute on function public.practitioner_seats_expire() to service_role;
grant execute on function public.seat_user_needs_password(uuid) to service_role;

select cron.schedule('practitioner-seats-expire', '10 3 * * *', 'select public.practitioner_seats_expire()');

-- Daily practitioner reminders run through the edge function; the job is created
-- from the accrual job's command so the cron secret never appears in a file.
select cron.schedule('practitioner-seats-remind', '20 9 * * *',
  (select replace(replace(command, 'partner-commission-accrual', 'practitioner-seats'), '''{}''', '''{"action":"daily"}''')
     from cron.job where jobname = 'partner-commission-accrual-monthly'));
