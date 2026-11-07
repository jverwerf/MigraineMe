// supabase/functions/practitioner-seats/index.ts
//
// Practitioner seats: optional, on top of the store subscriptions.
//
//   Practitioner (me-practitioner.com, their own sign-in):
//     status                     -> seats + price; picks up any paid order first
//     checkout {quantity,currency,lang} -> Stripe Checkout url
//     label {seat_id,label}      -> their own note on who a seat is for
//   Client (migraineme.app/seat, their own sign-in):
//     claim_info {code}          -> is the code good, who paid, already subscribed?
//     claim {code}               -> premium on for a year
//   Cron (x-cron-secret):
//     daily                      -> mail practitioners whose seats end within 30 days
//
// Orders are confirmed by asking Stripe about the session, so there is no webhook
// to miss: a paid order turns into seats on the next visit to the seats page.

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { baselineProceeds } from "../_shared/rcProceeds.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const STRIPE_KEY = Deno.env.get("STRIPE_SECRET_KEY") ?? "";
const CRON_SECRET = Deno.env.get("PARTNER_ACCRUAL_SECRET") ?? "";
const RESEND_KEY = Deno.env.get("RESEND_API_KEY") ?? "";
const PORTAL = "https://me-practitioner.com";
const COMMISSION_PCT = 10;

// The normal yearly price, per currency. No bulk discount (Jordy, 2026-11-07).
const PRICES: Record<string, number> = { gbp: 5499, usd: 5599, eur: 6399 };
const CHECKOUT_LOCALES = new Set(["de", "es", "fr", "it", "nl", "pt", "ja", "ko", "tr", "pl", "zh"]);

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "Authorization, Content-Type, apikey, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json", ...CORS } });

// deno-lint-ignore no-explicit-any
type Db = any;

async function stripe(path: string, form?: URLSearchParams) {
  const r = await fetch(`https://api.stripe.com/v1/${path}`, {
    method: form ? "POST" : "GET",
    headers: { Authorization: `Bearer ${STRIPE_KEY}`, ...(form ? { "Content-Type": "application/x-www-form-urlencoded" } : {}) },
    body: form?.toString(),
  });
  const body = await r.json().catch(() => ({}));
  return { ok: r.ok, status: r.status, body };
}

async function practitionerOf(db: Db, userId: string) {
  const { data } = await db.from("practitioners")
    .select("id,partner_id,display_name,login_email,languages").eq("user_id", userId).maybeSingle();
  return data;
}

// Asks Stripe about every open order, so a paid one always becomes seats.
async function settleOrders(db: Db, practitionerId: string) {
  if (!STRIPE_KEY) return;
  const { data: open } = await db.from("practitioner_seat_orders").select("id,stripe_session_id")
    .eq("practitioner_id", practitionerId).eq("status", "pending");
  for (const o of open ?? []) {
    if (!o.stripe_session_id) continue;
    const s = await stripe(`checkout/sessions/${o.stripe_session_id}`);
    if (!s.ok) { console.error(`seats: session lookup failed ${o.id}: ${s.status}`); continue; }
    if (s.body.payment_status === "paid") {
      const { error } = await db.rpc("practitioner_seats_fulfil", { p_order: o.id });
      if (error) console.error(`seats: FULFIL FAILED, PAID ORDER HAS NO SEATS ${o.id}: ${error.message}`);
    } else if (s.body.status === "expired") {
      await db.from("practitioner_seat_orders").update({ status: "expired" }).eq("id", o.id);
    }
  }
}

async function status(db: Db, pr: { id: string; partner_id: string | null }) {
  await settleOrders(db, pr.id);
  const { data: seats } = await db.from("practitioner_seats")
    .select("id,code,label,status,claimed_at,ends_at").eq("practitioner_id", pr.id)
    .order("status").order("created_at", { ascending: false });
  let pct = COMMISSION_PCT;
  if (pr.partner_id) {
    const { data: p } = await db.from("partners").select("commission_pct").eq("id", pr.partner_id).maybeSingle();
    if (p && Number(p.commission_pct) > 0) pct = Number(p.commission_pct);
  }
  return json({ ok: true, seats: seats ?? [], prices: PRICES, commission_pct: pct });
}

async function checkout(db: Db, pr: { id: string; login_email: string | null }, email: string, body: Record<string, unknown>) {
  if (!STRIPE_KEY) return json({ error: "payment_unavailable" }, 503);
  const quantity = Number(body.quantity);
  const currency = String(body.currency ?? "").toLowerCase();
  if (!Number.isInteger(quantity) || quantity < 1 || quantity > 100) return json({ error: "bad_quantity" }, 400);
  if (!PRICES[currency]) return json({ error: "bad_currency" }, 400);

  const { data: order, error } = await db.from("practitioner_seat_orders")
    .insert({ practitioner_id: pr.id, quantity, currency, unit_amount: PRICES[currency] }).select("id").single();
  if (error) { console.error("seats: order insert", error.message); return json({ error: "order_failed" }, 500); }

  const lang = String(body.lang ?? "");
  const form = new URLSearchParams({
    mode: "payment",
    client_reference_id: order.id,
    customer_email: pr.login_email || email,
    success_url: `${PORTAL}/#seats`,
    cancel_url: `${PORTAL}/#seats`,
    "line_items[0][quantity]": String(quantity),
    "line_items[0][price_data][currency]": currency,
    "line_items[0][price_data][unit_amount]": String(PRICES[currency]),
    "line_items[0][price_data][product_data][name]": "MigraineMe Premium, one client for one year",
    "invoice_creation[enabled]": "true",
    "metadata[seat_order_id]": order.id,
    "metadata[practitioner_id]": pr.id,
    "locale": CHECKOUT_LOCALES.has(lang) ? (lang === "zh" ? "zh-TW" : lang) : "auto",
  });
  const s = await stripe("checkout/sessions", form);
  if (!s.ok) {
    console.error(`seats: checkout failed ${s.status}: ${JSON.stringify(s.body).slice(0, 300)}`);
    await db.from("practitioner_seat_orders").delete().eq("id", order.id);
    return json({ error: "payment_unavailable" }, 503);
  }
  await db.from("practitioner_seat_orders").update({ stripe_session_id: s.body.id }).eq("id", order.id);
  return json({ ok: true, url: s.body.url });
}

const normCode = (v: unknown) => {
  const c = String(v ?? "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  return c.length === 10 ? `${c.slice(0, 5)}-${c.slice(5)}` : "";
};

async function needsPassword(db: Db, userId: string) {
  const { data } = await db.rpc("seat_user_needs_password", { p_user: userId });
  return data === true;
}

async function claimInfo(db: Db, userId: string, body: Record<string, unknown>) {
  const code = normCode(body.code);
  if (!code) return json({ ok: true, state: "invalid" });
  const { data: seat } = await db.from("practitioner_seats").select("status,claimed_by,ends_at,practitioner_id").eq("code", code).maybeSingle();
  if (!seat) return json({ ok: true, state: "invalid" });
  if (seat.status !== "available") {
    return seat.claimed_by === userId
      ? json({ ok: true, state: "yours", ends_at: seat.ends_at, needs_password: await needsPassword(db, userId) })
      : json({ ok: true, state: "used" });
  }
  const { data: pr } = await db.from("practitioners").select("display_name").eq("id", seat.practitioner_id).maybeSingle();
  const { data: ps } = await db.from("premium_status").select("rc_subscription_status,rc_product_id").eq("user_id", userId).maybeSingle();
  const store = ps?.rc_subscription_status === "active" && ps?.rc_product_id !== "practitioner_seat";
  return json({ ok: true, state: "available", practitioner: pr?.display_name ?? null, already_subscribed: store });
}

async function claim(db: Db, userId: string, body: Record<string, unknown>) {
  const code = normCode(body.code);
  if (!code) return json({ ok: false, error: "invalid" });
  // Whatever they had already paid stays out of the practitioner's commission.
  const baseline = await baselineProceeds(userId);
  const { data, error } = await db.rpc("claim_practitioner_seat", { p_user: userId, p_code: code });
  if (error) { console.error("seats: claim", error.message); return json({ ok: false, error: "failed" }, 500); }
  const row = Array.isArray(data) ? data[0] : data;
  if (row?.result !== "claimed" && row?.result !== "already_yours") return json({ ok: false, error: row?.result ?? "failed" });

  if (row.result === "claimed") {
    const { data: pr } = await db.from("practitioners").select("partner_id").eq("id", row.seat_practitioner).maybeSingle();
    if (pr?.partner_id) {
      // First touch, permanent: an earlier partner keeps the client.
      const { error: attrErr } = await db.from("partner_attributions").upsert(
        { user_id: userId, partner_id: pr.partner_id, source: "seat", baseline_proceeds_usd: baseline },
        { onConflict: "user_id", ignoreDuplicates: true },
      );
      if (attrErr) console.error(`seats: attribution failed user=${userId} partner=${pr.partner_id}: ${attrErr.message}`);
    }
  }
  return json({ ok: true, ends_at: row.seat_ends_at, needs_password: await needsPassword(db, userId) });
}

const REMINDER: Record<string, { subject: string; body: string }> = {
  en: { subject: "A client seat is ending soon", body: "Hello {name},\n\n{n} of your client seats end(s) within the next 30 days:\n{list}\n\nA seat does not renew by itself. To carry on covering a client, buy a new seat at {url} and send them the new code. It starts when the old one ends. If you do nothing, they can still subscribe themselves in the app.\n\nJordy, MigraineMe" },
  de: { subject: "Ein Klientenplatz läuft bald aus", body: "Guten Tag {name},\n\n{n} Ihrer Klientenplätze läuft/laufen in den nächsten 30 Tagen aus:\n{list}\n\nEin Platz verlängert sich nicht von selbst. Um weiter zu übernehmen, kaufen Sie unter {url} einen neuen Platz und senden den neuen Code. Er beginnt, wenn der alte endet. Wenn Sie nichts tun, kann die Person das Abo weiterhin selbst in der App abschließen.\n\nJordy, MigraineMe" },
  es: { subject: "Una plaza de cliente termina pronto", body: "Hola, {name}:\n\n{n} de sus plazas de cliente termina(n) en los próximos 30 días:\n{list}\n\nUna plaza no se renueva sola. Para seguir cubriendo a un cliente, compre una plaza nueva en {url} y envíele el nuevo código. Empieza cuando termina la anterior. Si no hace nada, esa persona puede suscribirse por su cuenta en la app.\n\nJordy, MigraineMe" },
  fr: { subject: "Une place client arrive bientôt à échéance", body: "Bonjour {name},\n\n{n} de vos places clients se termine(nt) dans les 30 prochains jours :\n{list}\n\nUne place ne se renouvelle pas toute seule. Pour continuer à couvrir un client, achetez une nouvelle place sur {url} et envoyez-lui le nouveau code. Elle commence à la fin de l'ancienne. Sinon, cette personne peut toujours s'abonner elle-même dans l'application.\n\nJordy, MigraineMe" },
  it: { subject: "Un posto cliente sta per scadere", body: "Buongiorno {name},\n\n{n} dei suoi posti cliente scade/scadono nei prossimi 30 giorni:\n{list}\n\nUn posto non si rinnova da solo. Per continuare a coprire un cliente, acquisti un nuovo posto su {url} e gli invii il nuovo codice. Inizia quando termina il precedente. Altrimenti la persona può sempre abbonarsi da sola nell'app.\n\nJordy, MigraineMe" },
  nl: { subject: "Een cliëntplek loopt binnenkort af", body: "Beste {name},\n\n{n} van uw cliëntplekken loopt/lopen binnen 30 dagen af:\n{list}\n\nEen plek verlengt niet vanzelf. Wilt u een cliënt blijven dekken, koop dan een nieuwe plek op {url} en stuur de nieuwe code. Die gaat in wanneer de oude afloopt. Doet u niets, dan kan de cliënt zich nog altijd zelf abonneren in de app.\n\nJordy, MigraineMe" },
  pt: { subject: "Um lugar de cliente termina em breve", body: "Olá {name},\n\n{n} dos seus lugares de cliente termina(m) nos próximos 30 dias:\n{list}\n\nUm lugar não se renova sozinho. Para continuar a cobrir um cliente, compre um novo lugar em {url} e envie-lhe o novo código. Começa quando o anterior termina. Caso contrário, a pessoa pode sempre subscrever na app.\n\nJordy, MigraineMe" },
  ja: { subject: "クライアント枠の期限が近づいています", body: "{name} 様\n\nクライアント枠のうち {n} 件が30日以内に終了します。\n{list}\n\n枠は自動更新されません。引き続きご負担いただく場合は、{url} で新しい枠をご購入のうえ、新しいコードをお送りください。現在の枠の終了後に開始します。何もされない場合でも、クライアントはアプリからご自身で登録できます。\n\nJordy, MigraineMe" },
  ko: { subject: "고객 이용권이 곧 종료됩니다", body: "{name}님, 안녕하세요.\n\n고객 이용권 중 {n}개가 30일 이내에 종료됩니다.\n{list}\n\n이용권은 자동으로 갱신되지 않습니다. 계속 지원하시려면 {url}에서 새 이용권을 구매해 새 코드를 보내 주세요. 기존 이용권이 끝나면 시작됩니다. 아무 조치를 하지 않아도 고객은 앱에서 직접 구독할 수 있습니다.\n\nJordy, MigraineMe" },
  tr: { subject: "Bir danışan koltuğu yakında sona eriyor", body: "Merhaba {name},\n\nDanışan koltuklarınızdan {n} tanesi 30 gün içinde sona eriyor:\n{list}\n\nKoltuk kendiliğinden yenilenmez. Danışanınızı karşılamaya devam etmek için {url} adresinden yeni bir koltuk alın ve yeni kodu gönderin. Eskisi bitince başlar. Hiçbir şey yapmazsanız danışan uygulamadan kendisi abone olabilir.\n\nJordy, MigraineMe" },
  pl: { subject: "Miejsce klienta wkrótce wygasa", body: "Dzień dobry {name},\n\nLiczba miejsc klientów wygasających w ciągu 30 dni: {n}\n{list}\n\nMiejsce nie odnawia się samo. Aby nadal opłacać subskrypcję klienta, kup nowe miejsce na {url} i wyślij nowy kod. Zacznie działać po wygaśnięciu poprzedniego. Jeśli nic Pan/Pani nie zrobi, klient nadal może sam wykupić subskrypcję w aplikacji.\n\nJordy, MigraineMe" },
  zh: { subject: "客戶名額即將到期", body: "{name} 您好：\n\n您有 {n} 個客戶名額將在 30 天內到期：\n{list}\n\n名額不會自動續期。若要繼續為客戶付費，請到 {url} 購買新名額並傳送新代碼，新名額會在舊名額結束後開始。如果您不採取任何行動，客戶仍可在 App 內自行訂閱。\n\nJordy, MigraineMe" },
};

async function daily(db: Db) {
  const soon = new Date(Date.now() + 30 * 864e5).toISOString();
  const { data: seats } = await db.from("practitioner_seats")
    .select("id,code,label,ends_at,practitioner_id").eq("status", "claimed").is("reminded_at", null).lte("ends_at", soon);
  const byPr = new Map<string, Array<{ id: string; code: string; label: string | null; ends_at: string }>>();
  for (const s of seats ?? []) byPr.set(s.practitioner_id, [...(byPr.get(s.practitioner_id) ?? []), s]);
  let sent = 0;
  for (const [id, list] of byPr) {
    const { data: pr } = await db.from("practitioners").select("display_name,login_email,languages,partner_id").eq("id", id).maybeSingle();
    let to = pr?.login_email;
    if (!to && pr?.partner_id) {
      const { data: p } = await db.from("partners").select("contact_email").eq("id", pr.partner_id).maybeSingle();
      to = p?.contact_email;
    }
    if (!to || !RESEND_KEY) { console.error(`seats: cannot remind practitioner ${id}`); continue; }
    const m = REMINDER[pr?.languages?.[0] ?? "en"] ?? REMINDER.en;
    const lines = list.map((s) => `- ${s.label || s.code}: ${s.ends_at.slice(0, 10)}`).join("\n");
    const text = m.body.replaceAll("{name}", pr?.display_name ?? "").replaceAll("{n}", String(list.length))
      .replaceAll("{list}", lines).replaceAll("{url}", `${PORTAL}/#seats`);
    const r = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: { Authorization: `Bearer ${RESEND_KEY}`, "Content-Type": "application/json" },
      body: JSON.stringify({ from: "MigraineMe <help@migraineme.app>", to: [to], subject: m.subject, text }),
    });
    if (!r.ok) { console.error(`seats: reminder failed ${id}: ${r.status}`); continue; }
    await db.from("practitioner_seats").update({ reminded_at: new Date().toISOString() }).in("id", list.map((s) => s.id));
    sent++;
  }
  return json({ ok: true, sent });
}

serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
  if (req.method !== "POST") return json({ error: "method" }, 405);
  try {
    const db = createClient(SUPABASE_URL, SERVICE_KEY);
    const body = await req.json().catch(() => ({})) as Record<string, unknown>;
    const action = String(body.action ?? "");

    if (action === "daily") {
      if (!CRON_SECRET || req.headers.get("x-cron-secret") !== CRON_SECRET) return json({ error: "unauthorized" }, 401);
      return await daily(db);
    }

    const token = (req.headers.get("Authorization") ?? "").replace("Bearer ", "");
    const { data: { user }, error: authErr } = await db.auth.getUser(token);
    if (authErr || !user) return json({ error: "unauthorized" }, 401);

    if (action === "claim_info") return await claimInfo(db, user.id, body);
    if (action === "claim") return await claim(db, user.id, body);

    const pr = await practitionerOf(db, user.id);
    if (!pr) return json({ error: "not_a_practitioner" }, 403);
    if (action === "status") return await status(db, pr);
    if (action === "checkout") return await checkout(db, pr, user.email ?? "", body);
    if (action === "label") {
      const label = String(body.label ?? "").trim().slice(0, 80) || null;
      const { error } = await db.from("practitioner_seats").update({ label })
        .eq("id", String(body.seat_id ?? "")).eq("practitioner_id", pr.id);
      return error ? json({ error: "failed" }, 400) : json({ ok: true });
    }
    return json({ error: "unknown_action" }, 400);
  } catch (err) {
    console.error("practitioner-seats error:", err);
    return json({ error: "internal_error" }, 500);
  }
});
