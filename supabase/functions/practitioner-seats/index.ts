// supabase/functions/practitioner-seats/index.ts
//
// Practitioner seats: optional, on top of the store subscriptions. One pool of
// seats works in all five apps: the client's app is decided when they claim.
//
// THE SEAT LIST LIVES HERE, on the MigraineMe backend. The MeSeries copy of this
// function (VertigoMe, TinnitusMe, GoutMe, AnxietyMe) reaches it through the
// "peer" action with the shared SEAT_PEER_SECRET. Each backend then records the
// seat locally and turns premium on for its own account.
//
//   Practitioner (me-practitioner.com, their own sign-in):
//     status                     -> seats + price; picks up any paid order first
//     checkout {quantity,currency,lang} -> Stripe Checkout url
//     label {seat_id,label}      -> their own note on who a seat is for
//   Client (the app's website /seat, their own sign-in):
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
const PEER_SECRET = Deno.env.get("SEAT_PEER_SECRET") ?? "";
const RESEND_KEY = Deno.env.get("RESEND_API_KEY") ?? "";
const PORTAL = "https://me-practitioner.com";
const COMMISSION_PCT = 10;

// The normal yearly price, the same in all five apps (App Store checked 2026-11-07).
// No bulk discount (Jordy, 2026-11-07).
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
// deno-lint-ignore no-explicit-any
type Args = Record<string, any>;

async function stripe(path: string, form?: URLSearchParams) {
  const r = await fetch(`https://api.stripe.com/v1/${path}`, {
    method: form ? "POST" : "GET",
    headers: { Authorization: `Bearer ${STRIPE_KEY}`, ...(form ? { "Content-Type": "application/x-www-form-urlencoded" } : {}) },
    body: form?.toString(),
  });
  const body = await r.json().catch(() => ({}));
  return { ok: r.ok, status: r.status, body };
}

const normCode = (v: unknown) => {
  const c = String(v ?? "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  return c.length === 10 ? `${c.slice(0, 5)}-${c.slice(5)}` : "";
};

// ── The seat list (LEDGER START) ─────────────────────────────────────────────

async function settleOrders(db: Db, owner: string) {
  if (!STRIPE_KEY) return;
  const { data: open } = await db.from("practitioner_seat_orders").select("id,stripe_session_id")
    .eq("owner_email", owner).eq("status", "pending");
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

async function ledger(op: string, a: Args): Promise<Args> {
  const db = createClient(SUPABASE_URL, SERVICE_KEY);
  if (op === "status") {
    await settleOrders(db, a.owner);
    const { data: seats } = await db.from("practitioner_seats")
      .select("id,code,label,status,claimed_app,claimed_at,ends_at").eq("owner_email", a.owner)
      .order("status").order("created_at", { ascending: false });
    const now = Date.now();
    return {
      ok: true, prices: PRICES,
      seats: (seats ?? []).map((s: Args) => ({ ...s, status: s.ends_at && new Date(s.ends_at).getTime() <= now ? "ended" : s.status })),
    };
  }
  if (op === "checkout") {
    if (!STRIPE_KEY) return { error: "payment_unavailable" };
    const quantity = Number(a.quantity), currency = String(a.currency ?? "").toLowerCase();
    if (!Number.isInteger(quantity) || quantity < 1 || quantity > 100) return { error: "bad_quantity" };
    if (!PRICES[currency]) return { error: "bad_currency" };
    const { data: order, error } = await db.from("practitioner_seat_orders").insert({
      owner_email: a.owner, owner_name: a.name ?? null, owner_lang: a.lang ?? null,
      quantity, currency, unit_amount: PRICES[currency],
    }).select("id").single();
    if (error) { console.error("seats: order insert", error.message); return { error: "order_failed" }; }
    const lang = String(a.lang ?? "");
    const form = new URLSearchParams({
      mode: "payment",
      client_reference_id: order.id,
      customer_email: a.owner,
      success_url: `${PORTAL}/#seats`,
      cancel_url: `${PORTAL}/#seats`,
      "line_items[0][quantity]": String(quantity),
      "line_items[0][price_data][currency]": currency,
      "line_items[0][price_data][unit_amount]": String(PRICES[currency]),
      "line_items[0][price_data][product_data][name]": "Client seat: one year of Premium in any Me app",
      "invoice_creation[enabled]": "true",
      "metadata[seat_order_id]": order.id,
      "locale": CHECKOUT_LOCALES.has(lang) ? (lang === "zh" ? "zh-TW" : lang) : "auto",
    });
    const s = await stripe("checkout/sessions", form);
    if (!s.ok) {
      console.error(`seats: checkout failed ${s.status}: ${JSON.stringify(s.body).slice(0, 300)}`);
      await db.from("practitioner_seat_orders").delete().eq("id", order.id);
      return { error: "payment_unavailable" };
    }
    await db.from("practitioner_seat_orders").update({ stripe_session_id: s.body.id }).eq("id", order.id);
    return { ok: true, url: s.body.url };
  }
  if (op === "label") {
    const label = String(a.label ?? "").trim().slice(0, 80) || null;
    const { error } = await db.from("practitioner_seats").update({ label })
      .eq("id", String(a.seat_id ?? "")).eq("owner_email", a.owner);
    return error ? { error: "failed" } : { ok: true };
  }
  if (op === "peek") {
    const { data: s } = await db.from("practitioner_seats").select("status,client_ref,ends_at,order_id").eq("code", a.code).maybeSingle();
    if (!s) return { state: "invalid" };
    if (s.status !== "available") return s.client_ref === a.client ? { state: "yours", ends_at: s.ends_at } : { state: "used" };
    const { data: o } = await db.from("practitioner_seat_orders").select("owner_name").eq("id", s.order_id).maybeSingle();
    return { state: "available", practitioner: o?.owner_name ?? null };
  }
  if (op === "take") {
    const { data, error } = await db.rpc("practitioner_seat_take", { p_code: a.code, p_app: a.app, p_client: a.client, p_ends: a.ends });
    if (error) { console.error("seats: take", error.message); return { error: "failed" }; }
    const row = Array.isArray(data) ? data[0] : data;
    let name = null;
    if (row?.seat_owner) {
      const { data: o } = await db.from("practitioner_seat_orders").select("owner_name").eq("owner_email", row.seat_owner).limit(1);
      name = o?.[0]?.owner_name ?? null;
    }
    return { result: row?.result, ends_at: row?.seat_ends_at, owner: row?.seat_owner, owner_name: name };
  }
  return { error: "unknown_op" };
}

const REMINDER: Record<string, { subject: string; body: string }> = {
  en: { subject: "A client seat is ending soon", body: "Hello {name},\n\n{n} of your client seats end(s) within the next 30 days:\n{list}\n\nA seat does not renew by itself. To carry on covering a client, buy a new seat at {url} and send them the new code. It starts when the old one ends. If you do nothing, they can still subscribe themselves in the app.\n\nJordy, Me Practitioner" },
  de: { subject: "Ein Klientenplatz läuft bald aus", body: "Guten Tag {name},\n\n{n} Ihrer Klientenplätze läuft/laufen in den nächsten 30 Tagen aus:\n{list}\n\nEin Platz verlängert sich nicht von selbst. Um weiter zu übernehmen, kaufen Sie unter {url} einen neuen Platz und senden den neuen Code. Er beginnt, wenn der alte endet. Wenn Sie nichts tun, kann die Person das Abo weiterhin selbst in der App abschließen.\n\nJordy, Me Practitioner" },
  es: { subject: "Una plaza de cliente termina pronto", body: "Hola, {name}:\n\n{n} de sus plazas de cliente termina(n) en los próximos 30 días:\n{list}\n\nUna plaza no se renueva sola. Para seguir cubriendo a un cliente, compre una plaza nueva en {url} y envíele el nuevo código. Empieza cuando termina la anterior. Si no hace nada, esa persona puede suscribirse por su cuenta en la app.\n\nJordy, Me Practitioner" },
  fr: { subject: "Une place client arrive bientôt à échéance", body: "Bonjour {name},\n\n{n} de vos places clients se termine(nt) dans les 30 prochains jours :\n{list}\n\nUne place ne se renouvelle pas toute seule. Pour continuer à couvrir un client, achetez une nouvelle place sur {url} et envoyez-lui le nouveau code. Elle commence à la fin de l'ancienne. Sinon, cette personne peut toujours s'abonner elle-même dans l'application.\n\nJordy, Me Practitioner" },
  it: { subject: "Un posto cliente sta per scadere", body: "Buongiorno {name},\n\n{n} dei suoi posti cliente scade/scadono nei prossimi 30 giorni:\n{list}\n\nUn posto non si rinnova da solo. Per continuare a coprire un cliente, acquisti un nuovo posto su {url} e gli invii il nuovo codice. Inizia quando termina il precedente. Altrimenti la persona può sempre abbonarsi da sola nell'app.\n\nJordy, Me Practitioner" },
  nl: { subject: "Een cliëntplek loopt binnenkort af", body: "Beste {name},\n\n{n} van uw cliëntplekken loopt/lopen binnen 30 dagen af:\n{list}\n\nEen plek verlengt niet vanzelf. Wilt u een cliënt blijven dekken, koop dan een nieuwe plek op {url} en stuur de nieuwe code. Die gaat in wanneer de oude afloopt. Doet u niets, dan kan de cliënt zich nog altijd zelf abonneren in de app.\n\nJordy, Me Practitioner" },
  pt: { subject: "Um lugar de cliente termina em breve", body: "Olá {name},\n\n{n} dos seus lugares de cliente termina(m) nos próximos 30 dias:\n{list}\n\nUm lugar não se renova sozinho. Para continuar a cobrir um cliente, compre um novo lugar em {url} e envie-lhe o novo código. Começa quando o anterior termina. Caso contrário, a pessoa pode sempre subscrever na app.\n\nJordy, Me Practitioner" },
  ja: { subject: "クライアント枠の期限が近づいています", body: "{name} 様\n\nクライアント枠のうち {n} 件が30日以内に終了します。\n{list}\n\n枠は自動更新されません。引き続きご負担いただく場合は、{url} で新しい枠をご購入のうえ、新しいコードをお送りください。現在の枠の終了後に開始します。何もされない場合でも、クライアントはアプリからご自身で登録できます。\n\nJordy, Me Practitioner" },
  ko: { subject: "고객 이용권이 곧 종료됩니다", body: "{name}님, 안녕하세요.\n\n고객 이용권 중 {n}개가 30일 이내에 종료됩니다.\n{list}\n\n이용권은 자동으로 갱신되지 않습니다. 계속 지원하시려면 {url}에서 새 이용권을 구매해 새 코드를 보내 주세요. 기존 이용권이 끝나면 시작됩니다. 아무 조치를 하지 않아도 고객은 앱에서 직접 구독할 수 있습니다.\n\nJordy, Me Practitioner" },
  tr: { subject: "Bir danışan koltuğu yakında sona eriyor", body: "Merhaba {name},\n\nDanışan koltuklarınızdan {n} tanesi 30 gün içinde sona eriyor:\n{list}\n\nKoltuk kendiliğinden yenilenmez. Danışanınızı karşılamaya devam etmek için {url} adresinden yeni bir koltuk alın ve yeni kodu gönderin. Eskisi bitince başlar. Hiçbir şey yapmazsanız danışan uygulamadan kendisi abone olabilir.\n\nJordy, Me Practitioner" },
  pl: { subject: "Miejsce klienta wkrótce wygasa", body: "Dzień dobry {name},\n\nLiczba miejsc klientów wygasających w ciągu 30 dni: {n}\n{list}\n\nMiejsce nie odnawia się samo. Aby nadal opłacać subskrypcję klienta, kup nowe miejsce na {url} i wyślij nowy kod. Zacznie działać po wygaśnięciu poprzedniego. Jeśli nic Pan/Pani nie zrobi, klient nadal może sam wykupić subskrypcję w aplikacji.\n\nJordy, Me Practitioner" },
  zh: { subject: "客戶名額即將到期", body: "{name} 您好：\n\n您有 {n} 個客戶名額將在 30 天內到期：\n{list}\n\n名額不會自動續期。若要繼續為客戶付費，請到 {url} 購買新名額並傳送新代碼，新名額會在舊名額結束後開始。如果您不採取任何行動，客戶仍可在 App 內自行訂閱。\n\nJordy, Me Practitioner" },
};

async function daily() {
  const db = createClient(SUPABASE_URL, SERVICE_KEY);
  const soon = new Date(Date.now() + 30 * 864e5).toISOString();
  const { data: seats } = await db.from("practitioner_seats")
    .select("id,code,label,ends_at,owner_email,order_id").eq("status", "claimed").is("reminded_at", null)
    .lte("ends_at", soon).gt("ends_at", new Date().toISOString());
  const byOwner = new Map<string, Args[]>();
  for (const s of seats ?? []) byOwner.set(s.owner_email, [...(byOwner.get(s.owner_email) ?? []), s]);
  let sent = 0;
  for (const [to, list] of byOwner) {
    if (!RESEND_KEY) { console.error("seats: no RESEND_API_KEY"); break; }
    const { data: o } = await db.from("practitioner_seat_orders").select("owner_name,owner_lang").eq("id", list[0].order_id).maybeSingle();
    const m = REMINDER[o?.owner_lang ?? "en"] ?? REMINDER.en;
    const lines = list.map((s) => `- ${s.label || s.code}: ${String(s.ends_at).slice(0, 10)}`).join("\n");
    const text = m.body.replaceAll("{name}", o?.owner_name ?? "").replaceAll("{n}", String(list.length))
      .replaceAll("{list}", lines).replaceAll("{url}", `${PORTAL}/#seats`);
    const r = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: { Authorization: `Bearer ${RESEND_KEY}`, "Content-Type": "application/json" },
      body: JSON.stringify({ from: "Me Practitioner <help@migraineme.app>", to: [to], subject: m.subject, text }),
    });
    if (!r.ok) { console.error(`seats: reminder failed ${to}: ${r.status}`); continue; }
    await db.from("practitioner_seats").update({ reminded_at: new Date().toISOString() }).in("id", list.map((s) => s.id));
    sent++;
  }
  return json({ ok: true, sent });
}
// ── (LEDGER END) ─────────────────────────────────────────────────────────────

// ── This backend's own side ──────────────────────────────────────────────────

const clientRef = (userId: string) => `migraine:${userId}`;

async function needsPassword(db: Db, userId: string) {
  const { data } = await db.rpc("seat_user_needs_password", { p_user: userId });
  return data === true;
}

async function practitionerOf(db: Db, userId: string) {
  const { data } = await db.from("practitioners").select("id,partner_id,display_name,login_email,languages")
    .eq("user_id", userId).limit(1);
  return data?.[0] ?? null;
}

// E-mail match with _ and % taken literally. The practitioner's own row comes first:
// a partner's contact address is not always the address they sign in with.
const likeEmail = (e: string) => e.replace(/[\\%_]/g, "\\$&");
async function partnerOf(db: Db, owner: string) {
  const cols = "id,commission_pct,status";
  const { data: pr } = await db.from("practitioners").select("partner_id").ilike("login_email", likeEmail(owner)).limit(1);
  if (pr?.[0]?.partner_id) {
    const { data } = await db.from("partners").select(cols).eq("id", pr[0].partner_id).maybeSingle();
    if (data) return data;
  }
  const { data: rows } = await db.from("partners").select(cols).ilike("contact_email", likeEmail(owner)).limit(1);
  return rows?.[0] ?? null;
}

// Tags the client to the practitioner, for the 10% if they stay. A practitioner
// may have bought the seat while listed only in another app, so a partner row for
// this app is made when missing. First touch, permanent; rates are never lowered.
async function attribute(db: Db, userId: string, owner: string, ownerName: string | null, baseline: number) {
  let partner = await partnerOf(db, owner);
  if (!partner) {
    const { data: made, error } = await db.from("partners").insert({
      name: ownerName || owner, contact_email: owner, commission_pct: COMMISSION_PCT, status: "active",
      notes: "Created when a client claimed a practitioner seat.",
    }).select("id,commission_pct,status").single();
    if (error) { console.error(`seats: partner create failed ${owner}: ${error.message}`); return; }
    partner = made;
  } else if (!(Number(partner.commission_pct) > 0) || partner.status === "pending") {
    await db.from("partners").update({
      commission_pct: Number(partner.commission_pct) > 0 ? partner.commission_pct : COMMISSION_PCT, status: "active",
    }).eq("id", partner.id);
  }
  const { error } = await db.from("partner_attributions").upsert(
    { user_id: userId, partner_id: partner.id, source: "seat", baseline_proceeds_usd: baseline },
    { onConflict: "user_id", ignoreDuplicates: true },
  );
  if (error) console.error(`seats: attribution failed user=${userId} partner=${partner.id}: ${error.message}`);
}

async function claimInfo(db: Db, userId: string, body: Args) {
  const code = normCode(body.code);
  if (!code) return json({ ok: true, state: "invalid" });
  const p = await ledger("peek", { code, client: clientRef(userId) });
  if (p.state === "yours") return json({ ok: true, state: "yours", ends_at: p.ends_at, needs_password: await needsPassword(db, userId) });
  if (p.state !== "available") return json({ ok: true, state: p.state ?? "invalid" });
  const { data: ps } = await db.from("premium_status").select("rc_subscription_status,rc_product_id").eq("user_id", userId).maybeSingle();
  const store = ps?.rc_subscription_status === "active" && ps?.rc_product_id !== "practitioner_seat";
  return json({ ok: true, state: "available", practitioner: p.practitioner, already_subscribed: store });
}

async function claim(db: Db, userId: string, body: Args) {
  const code = normCode(body.code);
  if (!code) return json({ ok: false, error: "invalid" });
  // A second seat for someone already on one starts when the first ends.
  const { data: live } = await db.from("practitioner_seat_grants").select("ends_at")
    .eq("user_id", userId).eq("status", "live").order("ends_at", { ascending: false }).limit(1);
  const base = new Date(Math.max(Date.now(), live?.[0] ? new Date(live[0].ends_at).getTime() : 0));
  base.setFullYear(base.getFullYear() + 1);
  // Whatever they had already paid stays out of the practitioner's commission.
  const baseline = await baselineProceeds(userId);
  const t = await ledger("take", { code, app: "migraine", client: clientRef(userId), ends: base.toISOString() });
  if (t.result !== "claimed" && t.result !== "already_yours") return json({ ok: false, error: t.result ?? "failed" });
  const { error } = await db.rpc("practitioner_seat_grant", { p_user: userId, p_code: code, p_ends: t.ends_at });
  // The seat is taken but premium is not on: claiming again retries this step.
  if (error) { console.error(`seats: GRANT FAILED user=${userId}: ${error.message}`); return json({ ok: false, error: "failed" }, 500); }
  if (t.result === "claimed") await attribute(db, userId, t.owner, t.owner_name ?? null, baseline);
  return json({ ok: true, ends_at: t.ends_at, needs_password: await needsPassword(db, userId) });
}

serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
  if (req.method !== "POST") return json({ error: "method" }, 405);
  try {
    const db = createClient(SUPABASE_URL, SERVICE_KEY);
    const body = await req.json().catch(() => ({})) as Args;
    const action = String(body.action ?? "");

    if (action === "daily") {
      if (!CRON_SECRET || req.headers.get("x-cron-secret") !== CRON_SECRET) return json({ error: "unauthorized" }, 401);
      return await daily();
    }
    // The MeSeries backend reaching the seat list.
    if (action === "peer") {
      if (!PEER_SECRET || req.headers.get("x-seat-secret") !== PEER_SECRET) return json({ error: "unauthorized" }, 401);
      return json(await ledger(String(body.op ?? ""), body.args ?? {}));
    }

    const token = (req.headers.get("Authorization") ?? "").replace("Bearer ", "");
    const { data: { user }, error: authErr } = await db.auth.getUser(token);
    if (authErr || !user) return json({ error: "unauthorized" }, 401);

    if (action === "claim_info") return await claimInfo(db, user.id, body);
    if (action === "claim") return await claim(db, user.id, body);

    const pr = await practitionerOf(db, user.id);
    if (!pr) return json({ error: "not_a_practitioner" }, 403);
    const owner = String(pr.login_email || user.email || "").toLowerCase();
    if (action === "status") {
      const r = await ledger("status", { owner });
      const pa = await partnerOf(db, owner);
      return json({ ...r, commission_pct: Number(pa?.commission_pct) > 0 ? Number(pa.commission_pct) : COMMISSION_PCT });
    }
    if (action === "checkout" || action === "label") {
      const r = await ledger(action, { ...body, owner, name: pr.display_name });
      return json(r, r.error === "payment_unavailable" ? 503 : r.error ? 400 : 200);
    }
    return json({ error: "unknown_action" }, 400);
  } catch (err) {
    console.error("practitioner-seats error:", err);
    return json({ error: "internal_error" }, 500);
  }
});
