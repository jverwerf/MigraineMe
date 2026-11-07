// supabase/functions/appointments/index.ts
//
// Two-way appointment suggestions between a client (in the app) and their
// practitioner (on the board). Either side suggests a time; the other accepts
// or declines. An accepted time is booked into the practitioner's Cliniko when
// they have it connected and the client is matched to a patient; otherwise it
// is just kept here. The client gets a push when the practitioner acts.
//
// Practitioner actions, always for one of their own active client links:
//   list      this client's requests and sessions, and what Cliniko offers
//   slots     open times for a week, from Cliniko
//   propose   suggest a time to the client
//   respond   accept or decline what the client suggested
//   cancel    withdraw a suggestion, or cancel an accepted session
//   pending   the client's suggestions waiting on this practitioner, all clients
//   set_calendar  the choice under Plugins, and where "Book an intro call" goes in
//             the apps: none = their own link, me = the request in the app (no link),
//             cliniko = their Cliniko online booking page when that is switched on.
//             The apps only read booking_url, so released versions follow this too.
//             The own link is kept in plugins so it comes back on None.
//   calendar  every suggested or confirmed time in a range, so the calendar works
//             without any practice software
// Client actions, always the caller's own rows:
//   mine            requests and sessions across their practitioners
//   client_suggest  suggest a time to a linked practitioner
//   client_respond  accept or decline what the practitioner suggested
//   client_cancel   withdraw a request that nobody has answered yet
//   client_asked    the app just saved a first intro call request: email the
//                   practitioner. At most 3 per person per practitioner per hour,
//                   and only with a fresh request on file.
//
// CALENDAR CHOICE: practitioners.plugins.calendar, set under Plugins. "none" means
// no calendar and no appointments, "me" means only what is agreed here, "cliniko"
// adds Cliniko's sessions and booking. Unset: cliniko when connected, otherwise me.
//
// VIDEO CALL: practitioners.plugins.meet_url, one link saved under Plugins, shown on
// upcoming sessions to both sides. Only Google Meet, Microsoft Teams, Zoom and Webex
// addresses are ever passed on.
//
// PRACTITIONER EMAIL: when a client suggests a time, or answers one, the practitioner
// gets a short email in their first listed language, with no client name and no health
// detail, only a link to sign in. They have no app to push to.
//
// OPEN TIMES: Cliniko's available_times answers 404 unless the practice has
// online booking on, so when it does we work the times out from Cliniko's own
// working hours minus appointments, group appointments and unavailable blocks.
//
// WHY A FUNCTION: the Cliniko key must never reach a browser or a phone, and
// accepting has to book and record as one step.
//
// Deploy: supabase functions deploy appointments

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { asLang, translatorFor } from "../_shared/i18n.ts";

// MeSeries serves several apps and scopes rows by app_id; the MigraineMe project
// has no app_id anywhere. The MeSeries copy sets this true. Nothing else differs.
const APP_SCOPED = false;

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const USER_AGENT = "MeSeries practitioner dashboard (help@migraineme.app)";
const PROVIDER = "cliniko";
const DAY_MS = 864e5;

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, x-app-id, x-practitioner-view",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

class UserError extends Error { status: number; constructor(m: string, s = 400) { super(m); this.status = s; } }

type Integ = { id: string; api_key: string; shard: string };

// ---------------------------------------------------------------- Cliniko

const API_BASE_OVERRIDE = Deno.env.get("CLINIKO_API_BASE") || "";
const apiBase = (shard: string) => API_BASE_OVERRIDE || `https://api.${shard}.cliniko.com/v1`;

async function ck(integ: Integ, path: string, init: RequestInit = {}): Promise<any> {
  const res = await fetch(apiBase(integ.shard) + path, {
    ...init,
    headers: {
      Authorization: "Basic " + btoa(integ.api_key.trim() + ":"),
      Accept: "application/json",
      "User-Agent": USER_AGENT,
      ...(init.body ? { "Content-Type": "application/json" } : {}),
    },
  });
  if (res.status === 401) throw new UserError("Cliniko did not accept the API key. Check it under My info, API keys in Cliniko.", 401);
  if (res.status === 403) throw new UserError("That Cliniko key is not allowed to book appointments.", 403);
  if (res.status === 404) throw new UserError("Cliniko has no record with that id.", 404);
  if (res.status === 429) throw new UserError("Cliniko is rate-limiting us. Try again in a minute.", 429);
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new UserError(`Cliniko answered ${res.status}: ${text.slice(0, 300)}`, 502);
  }
  if (res.status === 204) return null;
  return await res.json();
}
const idFromLink = (obj: any): string | null => {
  const self = obj?.links?.self; if (!self) return null;
  const m = String(self).match(/\/(\d+)(?:\?.*)?$/); return m ? m[1] : null;
};

/** The Cliniko practitioner behind this key, and the business they work at. */
async function whoAmI(integ: Integ) {
  const [user, pracs, avail, biz] = await Promise.all([
    ck(integ, "/user"),
    ck(integ, "/practitioners?per_page=100"),
    ck(integ, "/daily_availabilities?per_page=100").catch(() => null),
    ck(integ, "/businesses?per_page=100"),
  ]);
  const own = (pracs?.practitioners || []).find((p: any) => idFromLink(p.user) === String(user?.id));
  if (!own) throw new UserError("This Cliniko login is not set up as a practitioner, so it cannot take appointments.", 412);
  const days = (avail?.daily_availabilities || []).filter((d: any) => idFromLink(d.practitioner) === String(own.id));
  const businessId = (days[0] && idFromLink(days[0].business)) || String(biz?.businesses?.[0]?.id || "");
  if (!businessId) throw new UserError("Cliniko has no business set up yet.", 412);
  const business = (biz?.businesses || []).find((b: any) => String(b.id) === businessId);
  const tz = days[0]?.time_zone_identifier || "UTC";
  return { practitionerId: String(own.id), businessId, businessName: business?.business_name || null, days: days.filter((d: any) => idFromLink(d.business) === businessId), tz };
}
async function appointmentTypes(integ: Integ) {
  const r = await ck(integ, "/appointment_types?per_page=100");
  return (r?.appointment_types || []).filter((t: any) => !t.archived_at)
    .map((t: any) => ({ id: String(t.id), name: t.name, minutes: t.duration_in_minutes || 30 }));
}

/** ms to add to a UTC instant to get the wall clock in tz. */
function tzOffsetMs(at: number, tz: string): number {
  const p: Record<string, string> = {};
  for (const x of new Intl.DateTimeFormat("en-US", { timeZone: tz, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit" }).formatToParts(new Date(at))) p[x.type] = x.value;
  return Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute, +p.second) - Math.floor(at / 1000) * 1000;
}
/** A wall-clock time in tz, as a UTC instant. The second pass settles clock-change days. */
function wallToUtc(ymd: string, hm: string, tz: string): number {
  const [y, mo, d] = ymd.split("-").map(Number); const [h, mi] = hm.split(":").map(Number);
  const wall = Date.UTC(y, mo - 1, d, h, mi);
  const first = wall - tzOffsetMs(wall, tz);
  return wall - tzOffsetMs(first, tz);
}
const addDays = (ymd: string, n: number) => new Date(Date.parse(ymd + "T00:00:00Z") + n * DAY_MS).toISOString().slice(0, 10);

/** Everything that takes up this practitioner's time in the range, as [start, end] pairs. */
async function busyTimes(integ: Integ, practitionerId: string, from: string, to: string) {
  const q = [`starts_at:>=${from}`, `starts_at:<=${to}`, `practitioner_id:=${practitionerId}`].map((x) => "q[]=" + encodeURIComponent(x)).join("&");
  const [ind, grp, blk] = await Promise.all([
    ck(integ, `/individual_appointments?per_page=100&${q}`),
    ck(integ, `/group_appointments?per_page=100&${q}`).catch(() => null),
    ck(integ, `/unavailable_blocks?per_page=100&${q}`).catch(() => null),
  ]);
  const rows = [...(ind?.individual_appointments || []), ...(grp?.group_appointments || []), ...(blk?.unavailable_blocks || [])];
  return rows.filter((a: any) => !a.cancelled_at && !a.deleted_at && !a.archived_at)
    .map((a: any) => [Date.parse(a.starts_at), Date.parse(a.ends_at)] as [number, number]);
}

/** Open times for one week. Cliniko's own answer when the practice allows it. */
async function openTimes(integ: Integ, typeId: string, from: string, days: number) {
  const who = await whoAmI(integ);
  const types = await appointmentTypes(integ);
  const type = types.find((t: any) => t.id === typeId) || types[0];
  if (!type) throw new UserError("Cliniko has no appointment types yet.", 412);
  const span = Math.min(Math.max(days, 1), 7);
  const to = addDays(from, span - 1);
  const now = Date.now();
  const toSlot = (start: number) => ({ start: new Date(start).toISOString(), end: new Date(start + type.minutes * 60000).toISOString() });
  try {
    const r = await ck(integ, `/businesses/${who.businessId}/practitioners/${who.practitionerId}/appointment_types/${type.id}/available_times?from=${from}&to=${to}&per_page=100`);
    const slots = (r?.available_times || []).map((a: any) => Date.parse(a.appointment_start)).filter((t: number) => t > now).map(toSlot);
    return { slots, tz: who.tz, source: "cliniko", type_id: type.id };
  } catch (e) {
    if (!(e instanceof UserError) || e.status !== 404) throw e;
  }
  const busy = await busyTimes(integ, who.practitionerId, new Date(wallToUtc(from, "00:00", who.tz)).toISOString(), new Date(wallToUtc(addDays(to, 1), "00:00", who.tz)).toISOString());
  const slots: { start: string; end: string }[] = [];
  for (let i = 0; i < span; i++) {
    const ymd = addDays(from, i);
    const dow = new Date(ymd + "T12:00:00Z").getUTCDay();
    for (const day of who.days.filter((d: any) => d.day_of_week === dow)) {
      for (const win of day.availabilities || []) {
        const end = wallToUtc(ymd, win.ends_at, who.tz);
        for (let t = wallToUtc(ymd, win.starts_at, who.tz); t + type.minutes * 60000 <= end; t += type.minutes * 60000) {
          const te = t + type.minutes * 60000;
          if (t > now && !busy.some(([a, b]) => a < te && b > t)) slots.push(toSlot(t));
        }
      }
    }
  }
  return { slots: slots.sort((a, b) => (a.start < b.start ? -1 : 1)), tz: who.tz, source: "hours", type_id: type.id };
}

async function book(integ: Integ, patientId: string, startIso: string, typeId: string | null) {
  const who = await whoAmI(integ);
  const types = await appointmentTypes(integ);
  const type = types.find((t: any) => t.id === typeId) || types[0];
  if (!type) throw new UserError("Cliniko has no appointment types yet.", 412);
  const start = Date.parse(startIso);
  const ends = new Date(start + type.minutes * 60000).toISOString();
  const made = await ck(integ, "/individual_appointments", {
    method: "POST",
    body: JSON.stringify({
      starts_at: new Date(start).toISOString(), ends_at: ends, appointment_type_id: type.id,
      business_id: who.businessId, patient_id: patientId, practitioner_id: who.practitionerId,
    }),
  });
  return { id: String(made.id), ends_at: made.ends_at || ends, type_id: type.id };
}

// ---------------------------------------------------------------- handler

const NOTICE: Record<string, [string, string]> = {
  proposed: ["New appointment time", "Your practitioner suggested a time. Open the app to accept or decline."],
  accepted: ["Appointment confirmed", "Your practitioner accepted the time you suggested."],
  declined: ["Appointment time declined", "Your practitioner could not do that time. You can suggest another."],
  cancelled: ["Appointment cancelled", "Your practitioner cancelled this appointment."],
};

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  let lang: unknown = null; // for error messages: what the page asks for, else the profile
  try {
    if (req.method !== "POST") throw new UserError("POST only", 405);
    const auth = req.headers.get("Authorization") || "";
    if (!auth.startsWith("Bearer ")) throw new UserError("Sign in first.", 401);
    const appId = APP_SCOPED ? (req.headers.get("x-app-id") || "vertigo") : null;
    const asUser = createClient(SUPABASE_URL, ANON_KEY, { global: { headers: { Authorization: auth } } });
    const { data: { user }, error: uerr } = await asUser.auth.getUser();
    if (uerr || !user) throw new UserError("Sign in first.", 401);
    const db = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });
    const body = await req.json().catch(() => ({}));
    lang = body.lang || null;
    if (!lang) { const { data: pf } = await db.from("profiles").select("lang").eq("user_id", user.id).limit(1); lang = pf?.[0]?.lang || null; }
    const action = String(body.action || "");
    const scoped = (q: any) => (APP_SCOPED ? q.eq("app_id", appId) : q);
    const stamp: Record<string, string> = APP_SCOPED ? { app_id: appId! } : {};

    const COLS = "id,practitioner_id,user_id,kind,proposed_by,status,message,preferred_times,response_note,proposed_start,proposed_end,scheduled_for,external_provider,external_id,external_type_id,created_at,responded_at";

    const meetOf = async (practitionerId: string): Promise<string | null> => {
      const { data } = await db.from("practitioners").select("plugins").eq("id", practitionerId).limit(1);
      const u = String(data?.[0]?.plugins?.meet_url || "").trim();
      return /^https:\/\/([a-z0-9-]+\.)*(meet\.google\.com|teams\.microsoft\.com|teams\.live\.com|zoom\.us|webex\.com)\/\S*$/i.test(u) ? u : null;
    };
    /** The main page calls with one app's header for every MeSeries app, so a practitioner's row says which app it is. */
    const appOf = async (practitionerId: string): Promise<string | null> => {
      if (!APP_SCOPED) return null;
      const { data } = await db.from("practitioners").select("app_id").eq("id", practitionerId).limit(1);
      return data?.[0]?.app_id || appId;
    };
    const calendarChoice = async (practitionerId: string): Promise<string | null> => {
      const { data } = await db.from("practitioners").select("plugins").eq("id", practitionerId).limit(1);
      return data?.[0]?.plugins?.calendar || null;
    };
    const needOpen = async (practitionerId: string) => {
      if ((await calendarChoice(practitionerId)) === "none") throw new UserError("This practitioner is not taking appointments here.", 409);
    };
    /** The Cliniko key, unless they chose not to use Cliniko as their calendar. anyChoice is for undoing a booking already made there. */
    const integrationOf = async (practitionerId: string, anyChoice = false): Promise<Integ | null> => {
      const { data: pr } = await db.from("practitioners").select("user_id,plugins").eq("id", practitionerId).limit(1);
      const owner = pr?.[0]?.user_id; if (!owner) return null;
      const choice = pr?.[0]?.plugins?.calendar;
      if (!anyChoice && (choice === "none" || choice === "me")) return null;
      const { data } = await db.from("practitioner_integrations").select("id,api_key,shard").eq("user_id", owner).eq("provider", PROVIDER).limit(1);
      return (data?.[0] as Integ) || null;
    };
    const patientOf = async (linkId: string): Promise<string | null> => {
      const { data } = await db.from("practitioner_integration_patients").select("external_id").eq("link_id", linkId).eq("provider", PROVIDER).limit(1);
      return data?.[0]?.external_id ? String(data[0].external_id) : null;
    };
    const linkBetween = async (practitionerId: string, userId: string) => {
      const { data } = await db.from("practitioner_clients").select("id,status").eq("practitioner_id", practitionerId).eq("user_id", userId).limit(5);
      return (data || []).find((l: any) => l.status === "active") || null;
    };
    const sessionsFor = async (integ: Integ, patientId: string) => {
      const [appts, types, pracs] = await Promise.all([
        ck(integ, `/individual_appointments?per_page=40&sort=starts_at:desc&q[]=${encodeURIComponent("patient_id:=" + patientId)}`),
        ck(integ, "/appointment_types?per_page=100").catch(() => null),
        ck(integ, "/practitioners?per_page=100").catch(() => null),
      ]);
      const typeName: Record<string, string> = {}; for (const t of types?.appointment_types || []) typeName[String(t.id)] = t.name;
      const pracName: Record<string, string> = {}; for (const p of pracs?.practitioners || []) pracName[String(p.id)] = [p.first_name, p.last_name].filter(Boolean).join(" ");
      return (appts?.individual_appointments || []).filter((a: any) => !a.cancelled_at).map((a: any) => ({
        id: String(a.id), starts_at: a.starts_at, ends_at: a.ends_at,
        type: typeName[idFromLink(a.appointment_type) || ""] || null,
        with: pracName[idFromLink(a.practitioner) || ""] || null,
      }));
    };
    /** Suggestions whose time has gone by are no longer open. */
    const expireOld = async (rows: any[]) => {
      const now = Date.now();
      const old = rows.filter((r) => r.status === "requested" && r.proposed_start && Date.parse(r.proposed_start) < now);
      if (old.length) await db.from("practitioner_appointment_requests").update({ status: "expired" }).in("id", old.map((r) => r.id));
      for (const r of old) r.status = "expired";
      return rows;
    };
    /** FCM data values must be strings. Times let the app offer to add a confirmed session to the phone's calendar. */
    const notify = async (userId: string, what: keyof typeof NOTICE, requestId: string, extra: Record<string, string> = {}, forApp: string | null = appId) => {
      const [title, text] = NOTICE[what];
      try {
        if (APP_SCOPED) {
          const { data } = await db.from("profiles").select("fcm_token,fcm_app_id,lang").eq("user_id", userId).limit(1);
          const p: any = data?.[0];
          const owner = typeof p?.fcm_app_id === "string" && p.fcm_app_id ? p.fcm_app_id : "vertigo";
          if (!p?.fcm_token || owner !== forApp) return;
          const t = translatorFor(asLang(p.lang));
          await db.functions.invoke("send-fcm-push", { body: {
            type: "practitioner_appointment", app_id: forApp, tokens: [p.fcm_token],
            notification: { title: t(title), body: t(text) }, data: { request_id: requestId, ...extra },
          } });
        } else {
          await db.functions.invoke("send-fcm-push", { body: {
            type: "practitioner_appointment", user_ids: [userId], ios_alert: true,
            notification_key: { title, body_parts: [{ key: text }] }, data: { request_id: requestId, ...extra },
          } });
        }
      } catch (e) { console.warn("appointment push failed", e); }
    };
    const emailPractitioner = async (practitionerId: string, subjectKey: string) => {
      try {
        const { data } = await db.from("practitioners").select("user_id,login_email,languages,plugins").eq("id", practitionerId).limit(1);
        const p: any = data?.[0];
        if (!p || p.plugins?.calendar === "none") return;
        let to = String(p.login_email || "").trim();
        if (!to && p.user_id) { const { data: u } = await db.auth.admin.getUserById(p.user_id); to = u?.user?.email || ""; }
        if (!to) return;
        const app = String(appId || "migraine");
        const FROM: Record<string, string> = {
          migraine: "MigraineMe <help@migraineme.app>", vertigo: "VertigoMe <help@vertigome.com>", tinnitus: "TinnitusMe <help@tinnitusme.com>",
          gout: "GoutMe <help@goutme.app>", anxiety: "AnxietyMe <help@anxietyme.app>",
        };
        const key = Deno.env.get(`RESEND_API_KEY_${app.toUpperCase()}`) || Deno.env.get("RESEND_API_KEY") || "";
        if (!key) { console.warn("no email key"); return; }
        const first = Array.isArray(p.languages) ? String(p.languages[0] || "") : "";
        const t = translatorFor(asLang(first.toLowerCase().slice(0, 2)));
        const res = await fetch("https://api.resend.com/emails", {
          method: "POST",
          headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
          body: JSON.stringify({ from: FROM[app] || FROM.migraine, to: [to], subject: t(subjectKey), text: `${t(subjectKey)}\n\n${t("Sign in to see it:")} https://me-practitioner.com/` }),
        });
        if (!res.ok) console.warn("practitioner email failed", res.status);
      } catch (e) { console.warn("practitioner email failed", e); }
    };
    const update = async (id: string, patch: Record<string, unknown>) => {
      const { data, error } = await db.from("practitioner_appointment_requests").update(patch).eq("id", id).select(COLS).single();
      if (error) throw error;
      return data;
    };
    /** Accepting: book first, so a clash in Cliniko leaves the suggestion open. */
    const acceptRow = async (row: any, startIso: string, typeId: string | null, linkId: string | null) => {
      if (Date.parse(startIso) <= Date.now()) throw new UserError("That time has already passed.");
      const integ = await integrationOf(row.practitioner_id);
      const patient = integ && linkId ? await patientOf(linkId) : null;
      const made = integ && patient ? await book(integ, patient, startIso, typeId || row.external_type_id) : null;
      return await update(row.id, {
        status: "accepted", scheduled_for: new Date(Date.parse(startIso)).toISOString(), proposed_start: new Date(Date.parse(startIso)).toISOString(),
        proposed_end: made ? made.ends_at : row.proposed_end, responded_at: new Date().toISOString(),
        ...(made ? { external_provider: PROVIDER, external_id: made.id, external_type_id: made.type_id } : {}),
      });
    };
    const validStart = (v: unknown) => {
      const t = Date.parse(String(v || ""));
      if (isNaN(t)) throw new UserError("Pick a date and time.");
      if (t <= Date.now()) throw new UserError("That time has already passed.");
      return new Date(t).toISOString();
    };

    // ------------------------------------------------------------ client
    if (action.startsWith("client_") || action === "mine") {
      const own = async () => {
        const { data } = await db.from("practitioner_appointment_requests").select(COLS).eq("id", String(body.id || "")).eq("user_id", user.id).limit(1);
        if (!data?.[0]) throw new UserError("That request was not found.", 404);
        return data[0] as any;
      };
      switch (action) {
        case "mine": {
          const { data: rows } = await scoped(db.from("practitioner_appointment_requests").select(COLS).eq("user_id", user.id)).order("created_at", { ascending: false }).limit(100);
          await expireOld(rows || []);
          const { data: links } = await scoped(db.from("practitioner_clients").select("id,practitioner_id").eq("user_id", user.id).eq("status", "active"));
          const ids = [...new Set([...(rows || []).map((r: any) => r.practitioner_id), ...(links || []).map((l: any) => l.practitioner_id)])];
          const { data: prs } = ids.length ? await db.from("practitioners").select("id,display_name").in("id", ids) : { data: [] as any[] };
          const nameOf: Record<string, string> = {}; for (const p of prs || []) nameOf[p.id] = p.display_name;
          const sessions: any[] = [];
          const closed: string[] = [];
          const meet_urls: Record<string, string> = {};
          for (const l of links || []) { const u = await meetOf(l.practitioner_id); if (u) meet_urls[l.practitioner_id] = u; }
          for (const id of ids) if ((await calendarChoice(id as string)) === "none") closed.push(id as string);
          for (const l of links || []) {
            try {
              const integ = await integrationOf(l.practitioner_id);
              const patient = integ ? await patientOf(l.id) : null;
              if (integ && patient) for (const s of await sessionsFor(integ, patient)) sessions.push({ ...s, practitioner_id: l.practitioner_id, practitioner_name: nameOf[l.practitioner_id] || null });
            } catch (e) { console.warn("sessions skipped", e); }
          }
          return json({ requests: (rows || []).map((r: any) => ({ ...r, practitioner_name: nameOf[r.practitioner_id] || null })), sessions, closed, meet_urls });
        }
        case "client_asked": {
          const pid = String(body.practitioner_id || "");
          const hourAgo = new Date(Date.now() - 36e5).toISOString();
          const { data: recent } = await db.from("practitioner_appointment_requests").select("id,created_at").eq("practitioner_id", pid).eq("user_id", user.id).eq("status", "requested").gte("created_at", hourAgo);
          const rows = recent || [];
          const fresh = rows.some((r: any) => Date.parse(r.created_at) > Date.now() - 5 * 60000);
          if (fresh && rows.length <= 3) await emailPractitioner(pid, "A client suggested an appointment time");
          return json({ ok: true });
        }
        case "client_suggest": {
          const pid = String(body.practitioner_id || "");
          await needOpen(pid);
          if (!(await linkBetween(pid, user.id))) throw new UserError("You are not connected to that practitioner.", 403);
          const start = validStart(body.start);
          const { data, error } = await db.from("practitioner_appointment_requests").insert({
            practitioner_id: pid, user_id: user.id, kind: "followup", proposed_by: "client", status: "requested", proposed_start: start,
            message: String(body.message || "").slice(0, 500) || null, ...stamp,
          }).select(COLS).single();
          if (error) throw error;
          await emailPractitioner(pid, "A client suggested an appointment time");
          return json({ request: data });
        }
        case "client_respond": {
          const row = await own();
          if (row.proposed_by !== "practitioner" || row.status !== "requested") throw new UserError("That suggestion is no longer open.", 409);
          if (!body.accept) {
            const done = await update(row.id, { status: "declined", responded_at: new Date().toISOString(), response_note: String(body.note || "").slice(0, 500) || null });
            await emailPractitioner(row.practitioner_id, "A client declined your appointment time");
            return json({ request: done });
          }
          const link = await linkBetween(row.practitioner_id, user.id);
          if (!link) throw new UserError("You are not connected to that practitioner.", 403);
          const done = await acceptRow(row, row.proposed_start, row.external_type_id, link.id);
          await emailPractitioner(row.practitioner_id, "A client accepted your appointment time");
          return json({ request: done });
        }
        case "client_cancel": {
          const row = await own();
          if (row.status !== "requested") throw new UserError("Only a request nobody has answered can be withdrawn. Ask your practitioner to cancel a confirmed session.", 409);
          return json({ request: await update(row.id, { status: "cancelled", responded_at: new Date().toISOString() }) });
        }
      }
      throw new UserError("Unknown action");
    }

    // ------------------------------------------------------------ practitioner
    const { data: prs } = await db.from("practitioners").select("id").eq("user_id", user.id);
    const myIds: string[] = (prs || []).map((r: any) => r.id);
    if (!myIds.length) throw new UserError("That account is not set up as a practitioner.", 403);
    const needLink = async () => {
      const { data } = await db.from("practitioner_clients").select("id,user_id,practitioner_id,status").eq("id", String(body.link_id || "")).in("practitioner_id", myIds).limit(1);
      const l = data?.[0];
      if (!l || l.status !== "active") throw new UserError("That client is not connected to you.", 403);
      return l as { id: string; user_id: string; practitioner_id: string };
    };
    const linksByPair = async (ids: string[]) => {
      const { data } = await db.from("practitioner_clients").select("id,practitioner_id,user_id,status").in("practitioner_id", ids).eq("status", "active");
      const out: Record<string, string> = {};
      for (const l of data || []) out[l.practitioner_id + "|" + l.user_id] = l.id;
      return out;
    };
    const mineRow = async () => {
      const { data } = await db.from("practitioner_appointment_requests").select(COLS).eq("id", String(body.id || "")).in("practitioner_id", myIds).limit(1);
      if (!data?.[0]) throw new UserError("That request was not found.", 404);
      return data[0] as any;
    };

    switch (action) {
      case "list": {
        const link = await needLink();
        const { data: rows } = await db.from("practitioner_appointment_requests").select(COLS).eq("practitioner_id", link.practitioner_id).eq("user_id", link.user_id).order("created_at", { ascending: false }).limit(100);
        await expireOld(rows || []);
        const integ = await integrationOf(link.practitioner_id);
        const patient = integ ? await patientOf(link.id) : null;
        let types: any[] = [], sessions: any[] = [], cliniko_error: string | null = null;
        if (integ) {
          try {
            types = await appointmentTypes(integ);
            if (patient) sessions = await sessionsFor(integ, patient);
          } catch (e) { cliniko_error = (e as Error).message; }
        }
        return json({ requests: rows || [], sessions, calendar: await calendarChoice(link.practitioner_id), meet_url: await meetOf(link.practitioner_id), cliniko: { connected: !!integ, matched: !!patient, types, error: cliniko_error } });
      }
      case "slots": {
        const link = await needLink();
        const integ = await integrationOf(link.practitioner_id);
        if (!integ) throw new UserError("Connect Cliniko first, under Your dashboard.", 412);
        const from = /^\d{4}-\d{2}-\d{2}$/.test(String(body.from || "")) ? String(body.from) : new Date().toISOString().slice(0, 10);
        return json(await openTimes(integ, String(body.type_id || ""), from, Number(body.days) || 7));
      }
      case "propose": {
        const link = await needLink();
        await needOpen(link.practitioner_id);
        const start = validStart(body.start);
        const linkApp = await appOf(link.practitioner_id);
        const { data, error } = await db.from("practitioner_appointment_requests").insert({
          practitioner_id: link.practitioner_id, user_id: link.user_id, kind: "followup", proposed_by: "practitioner", status: "requested",
          proposed_start: start, proposed_end: body.end ? new Date(Date.parse(String(body.end))).toISOString() : null,
          external_type_id: body.type_id ? String(body.type_id) : null, message: String(body.message || "").slice(0, 500) || null, ...(linkApp ? { app_id: linkApp } : {}),
        }).select(COLS).single();
        if (error) throw error;
        await notify(link.user_id, "proposed", data.id, {}, linkApp);
        return json({ request: data });
      }
      case "respond": {
        const row = await mineRow();
        if (row.proposed_by !== "client" || row.status !== "requested") throw new UserError("That request is no longer open.", 409);
        if (!body.accept) {
          const done = await update(row.id, { status: "declined", responded_at: new Date().toISOString(), response_note: String(body.note || "").slice(0, 500) || null });
          await notify(row.user_id, "declined", row.id, {}, await appOf(row.practitioner_id));
          return json({ request: done });
        }
        const link = await linkBetween(row.practitioner_id, row.user_id);
        const start = body.start ? validStart(body.start) : row.proposed_start;
        if (!start) throw new UserError("Pick a date and time.");
        const done = await acceptRow(row, start, body.type_id ? String(body.type_id) : null, link?.id || null);
        const { data: who } = await db.from("practitioners").select("display_name").eq("id", row.practitioner_id).limit(1);
        await notify(row.user_id, "accepted", row.id, {
          starts_at: String(done.scheduled_for), ends_at: String(done.proposed_end || new Date(Date.parse(done.scheduled_for) + 30 * 60000).toISOString()),
          practitioner_name: String(who?.[0]?.display_name || ""),
          meet_url: (await meetOf(row.practitioner_id)) || "",
        }, await appOf(row.practitioner_id));
        return json({ request: done });
      }
      case "cancel": {
        const row = await mineRow();
        if (!["requested", "accepted"].includes(row.status)) throw new UserError("That is no longer open.", 409);
        if (row.status === "accepted" && row.external_id) {
          const integ = await integrationOf(row.practitioner_id, true);
          if (integ) await ck(integ, `/individual_appointments/${row.external_id}/cancel`, { method: "PATCH", body: JSON.stringify({ cancellation_reason: 50 }) });
        }
        const done = await update(row.id, { status: "cancelled", responded_at: new Date().toISOString() });
        if (row.proposed_by === "practitioner" && row.status === "requested") return json({ request: done });
        await notify(row.user_id, "cancelled", row.id, {}, await appOf(row.practitioner_id));
        return json({ request: done });
      }
      case "set_calendar": {
        const choice = String(body.choice || "");
        if (!["none", "me", "cliniko"].includes(choice)) throw new UserError("Pick a calendar.");
        const custom = body.custom_url === undefined ? undefined : String(body.custom_url || "").trim();
        if (custom && !/^https?:\/\/\S+\.\S+/i.test(custom)) throw new UserError("That does not look like a web address. Start it with https://");
        let clinikoUrl: string | null = null, onlineBooking: boolean | null = null, clinikoHost: string | null = null;
        if (choice === "cliniko") {
          const { data } = await db.from("practitioner_integrations").select("id,api_key,shard,web_base").eq("user_id", user.id).eq("provider", PROVIDER).limit(1);
          const integ: any = data?.[0];
          if (!integ) throw new UserError("Connect Cliniko first.", 412);
          clinikoHost = integ.web_base ? new URL(integ.web_base).host.toLowerCase() : null;
          const settings = await ck(integ, "/settings").catch(() => null);
          onlineBooking = settings?.online_bookings?.enabled === true;
          if (onlineBooking && integ.web_base) clinikoUrl = `${integ.web_base}/bookings`;
        }
        const { data: rows } = await db.from("practitioners").select("id,plugins,booking_url,booking_urls").in("id", myIds);
        // A booking link they already have into this same Cliniko is more exact than the
        // plain /bookings page (it can name their business and practitioner), so it stays.
        const ownCliniko = (u: unknown): boolean => {
          try {
            const x = new URL(String(u || ""));
            const host = x.host.toLowerCase();
            // A connection saved without its web address still matches any Cliniko booking link.
            return (clinikoHost ? host === clinikoHost : host.endsWith(".cliniko.com")) && x.pathname.startsWith("/bookings");
          } catch { return false; }
        };
        let shown: string | null = null, live: string | null = choice === "cliniko" ? clinikoUrl : null;
        for (const r of rows || []) {
          const plugins: any = { ...(r.plugins || {}) };
          // First time through: what they already had is their own link.
          if (!("calendar" in plugins) && !("own_booking" in plugins)) plugins.own_booking = { url: r.booking_url || null, urls: r.booking_urls || null };
          if (custom !== undefined) plugins.own_booking = { url: custom || null, urls: null };
          plugins.calendar = choice;
          const own = plugins.own_booking || { url: null, urls: null };
          const keepOwn = choice === "cliniko" && ownCliniko(own.url);
          const patch = choice === "none" || keepOwn ? { booking_url: own.url, booking_urls: own.urls } : { booking_url: clinikoUrl, booking_urls: null };
          if (keepOwn) { live = own.url; onlineBooking = true; }
          const { error } = await db.from("practitioners").update({ plugins, ...patch }).eq("id", r.id);
          if (error) throw error;
          shown = own.url;
        }
        return json({ choice, own_url: shown, booking_url: choice === "none" ? shown : live, online_booking: onlineBooking });
      }
      case "pending": {
        const { data: rows } = await db.from("practitioner_appointment_requests").select("id,practitioner_id,user_id,status,message,preferred_times,proposed_start,kind,proposed_by").in("practitioner_id", myIds).eq("status", "requested").eq("proposed_by", "client");
        await expireOld((rows || []) as any[]);
        const open = (rows || []).filter((r: any) => r.status !== "expired");
        const linkOf = await linksByPair(myIds);
        return json({ requests: open.map((r: any) => ({ ...r, link_id: linkOf[r.practitioner_id + "|" + r.user_id] || null })) });
      }
      case "calendar": {
        const fromD = new Date(String(body.from || "")), toD = new Date(String(body.to || ""));
        if (isNaN(fromD.getTime()) || isNaN(toD.getTime())) throw new UserError("Pick a date range.");
        if (toD.getTime() - fromD.getTime() > 50 * DAY_MS) throw new UserError("That range is too long.");
        const { data: rows } = await db.from("practitioner_appointment_requests").select(COLS).in("practitioner_id", myIds).in("status", ["requested", "accepted"]);
        await expireOld((rows || []) as any[]);
        const linkOf = await linksByPair(myIds);
        const out = (rows || []).filter((r: any) => r.status !== "expired").map((r: any) => {
          const at = r.status === "accepted" ? r.scheduled_for : r.proposed_start;
          if (!at) return null;
          const t = Date.parse(at);
          if (t < fromD.getTime() || t >= toD.getTime()) return null;
          return {
            request_id: r.id, starts_at: at, ends_at: r.proposed_end || new Date(t + 30 * 60000).toISOString(), status: r.status,
            proposed_by: r.proposed_by, message: r.message, external_id: r.external_id, link_id: linkOf[r.practitioner_id + "|" + r.user_id] || null,
          };
        }).filter(Boolean);
        return json({ appointments: out });
      }
    }
    throw new UserError("Unknown action");
  } catch (e) {
    if (e instanceof UserError) return json({ error: translatorFor(asLang(lang))(e.message) }, e.status);
    console.error(e);
    return json({ error: (e as any)?.message || "Something went wrong." }, 500);
  }
});
