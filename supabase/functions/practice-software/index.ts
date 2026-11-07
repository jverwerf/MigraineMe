// supabase/functions/practice-software/index.ts
//
// The practitioner dashboard's bridge to practice software other than Cliniko
// (Cliniko keeps its own `cliniko` function). One function, one `action` and
// one `provider` per call, always on behalf of the signed-in practitioner:
//
//   status        connected or not; with link_id, which patient. No provider:
//                 the status of every provider
//   connect       check the credentials against the vendor, then store them
//   disconnect    forget the credentials and this provider's patient matches
//   match         find the client's patient by e-mail, or by a pasted id/link.
//                 Only an EXACT e-mail hit on ONE patient matches; anything
//                 else asks for the id, because a wrong match files a report
//                 into a stranger's record
//   unmatch       forget the patient
//   appointments  the patient's sessions, newest first
//   send          file a PDF into the patient's record, plus a note where the
//                 vendor has an unsigned form of one
//
// WHY A FUNCTION: the credentials open the practitioner's own account and the
// client's e-mail lives in auth.users. Neither may reach the browser, so both
// stay here, behind the service role. The caller's JWT must belong to a
// practitioner row; every link_id is checked against that person's own active
// client links before anything is read.
//
// THE NOTE, per vendor (same promise as Cliniko: the practitioner signs it):
//   halaxy  DocumentReference docStatus "preliminary" = their documented draft
//   semble  a letter with reviewStatus AWAITING_REVIEW, the nearest unsigned
//           form (free text records have no draft state)
//   nookal  NOTHING: addTreatmentNote documents no draft state and needs a
//           case + practitioner, so the PDF is filed and no note is written
//
// NOT CONFIRMED BY THE DOCS, marked UNCONFIRMED below. No real account has
// been used; check these on the first real connection of each vendor.
//
// Docs: docs.semble.io, api.nookal.com/developers, developers.halaxy.com
// Deploy: supabase functions deploy practice-software  (JWT verified)

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

// MeSeries serves several apps and scopes practitioner rows by app_id; the
// MigraineMe project has no app_id anywhere. The MeSeries copy sets this true.
const APP_SCOPED = false;

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const USER_AGENT = "MeSeries practitioner dashboard (help@migraineme.app)";
const MAX_PDF = 12 * 1024 * 1024;

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, x-app-id, x-practitioner-view",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

class UserError extends Error { status: number; constructor(m: string, s = 400) { super(m); this.status = s; } }

type Integration = {
  id?: string; api_key: string; shard: string; config: Record<string, any>;
  account_name?: string | null; user_name?: string | null; user_email?: string | null;
  connected_at?: string; last_used_at?: string | null;
};
type Patient = { id: string; name: string; date_of_birth: string | null; email: string | null };
type Appointment = {
  id: string; starts_at: string | null; ends_at: string | null; type: string | null; practitioner: string | null;
  cancelled: boolean; did_not_arrive: boolean; note_status: string | null;
};
type Note = { title?: string; section?: string; question?: string; lines?: any[]; shared?: string; shared_label?: string; attachment_label?: string } | null;
type SendInput = { patientId: string; b64: string; bytes: Uint8Array; filename: string; description: string; note: Note };
type SendOutput = { attachmentId: string | null; noteId: string | null; noteError: string | null; noteSkipped: string | null };
type Connected = { api_key: string; shard: string; config: Record<string, any>; account_name: string | null; user_name: string | null; user_email: string | null };

type Adapter = {
  label: string;
  note: "draft" | "review" | "none";
  connect(body: any): Promise<Connected | { choose_role: { id: string; label: string }[] }>;
  byEmail(integ: Integration, email: string): Promise<Patient[]>;
  byId(integ: Integration, id: string): Promise<Patient | null>;
  idFromPaste(text: string): string | null;
  appointments(integ: Integration, patientId: string): Promise<Appointment[]>;
  send(integ: Integration, input: SendInput): Promise<SendOutput>;
};

// ---------------------------------------------------------------- helpers

// A network failure's message can hold the URL, and Nookal's URL holds the
// key, so vendor calls go through here and the message never travels on.
async function call(label: string, url: string, init: RequestInit = {}): Promise<Response> {
  try { return await fetch(url, init); }
  catch (_) { throw new UserError(`Could not reach ${label}. Try again in a minute.`, 503); }
}
function failOn(label: string, status: number, text: string): never {
  if (status === 401) throw new UserError(`${label} did not accept those credentials.`, 401);
  if (status === 403) throw new UserError(`Those ${label} credentials are not allowed to do this.`, 403);
  if (status === 404) throw new UserError(`${label} has no record with that id.`, 404);
  if (status === 429) throw new UserError(`${label} is rate-limiting us. Try again in a minute.`, 429);
  throw new UserError(`${label} answered ${status}: ${text.slice(0, 300)}`, 502);
}
const lineText = (l: any) => Array.isArray(l) ? `${l[0]}: ${l[1]}` : String(l);
const esc = (s: unknown) => String(s ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
const lineHtml = (l: any) => Array.isArray(l) ? `<p><b>${esc(l[0])}</b>: ${esc(l[1])}</p>` : `<p>${esc(l)}</p>`;
const newestFirst = (a: Appointment, b: Appointment) => String(b.starts_at || "").localeCompare(String(a.starts_at || ""));
const sameEmail = (a: unknown, b: string) => String(a ?? "").trim().toLowerCase() === b;
// id from a pasted address (/patients/<id>, /patient/<id>) or the bare id
function idFrom(text: string, shape: RegExp): string | null {
  const t = text.trim();
  const m = t.match(/\/patients?\/([^/?#]+)/i);
  const id = m ? m[1] : t;
  return shape.test(id) ? id : null;
}
function clipBytes(s: string, max: number): string {
  const enc = new TextEncoder();
  let out = s;
  while (enc.encode(out).length > max) out = out.slice(0, -1);
  return out;
}
function noteText(n: NonNullable<Note>, filename: string): string {
  const out = (n.lines || []).map(lineText);
  if (n.shared) out.push(`${n.shared_label || "Shared data"}: ${n.shared}`);
  out.push(`${n.attachment_label || "Attachment"}: ${filename}`);
  return out.join("\n");
}
function noteHtml(n: NonNullable<Note>, filename: string): string {
  const lines = [...(n.lines || []) as any[]];
  if (n.shared) lines.push([n.shared_label || "Shared data", n.shared]);
  lines.push([n.attachment_label || "Attachment", filename]);
  return lines.map(lineHtml).join("");
}

// ---------------------------------------------------------------- Semble
// GraphQL, x-token header, key from Settings > API credentials.

const SEMBLE_URL = Deno.env.get("SEMBLE_API_BASE") || "https://open.semble.io/graphql";

async function sb(integ: { api_key: string }, query: string, variables: Record<string, unknown> = {}): Promise<any> {
  const res = await call("Semble", SEMBLE_URL, {
    method: "POST",
    headers: { "x-token": integ.api_key.trim(), "Content-Type": "application/json", Accept: "application/json", "User-Agent": USER_AGENT },
    body: JSON.stringify({ query, variables }),
  });
  const text = await res.text();
  if (!res.ok) failOn("Semble", res.status, text);
  let body: any; try { body = JSON.parse(text); } catch (_) { throw new UserError("Semble gave an unreadable answer.", 502); }
  const err = body?.errors?.[0];
  if (err) {
    const code = String(err.extensions?.code || "");
    // Their docs: a bad or wrong-environment token reads "Your session expired".
    if (code === "UNAUTHENTICATED" || /session expired|missing token/i.test(String(err.message))) failOn("Semble", 401, "");
    if (code === "FORBIDDEN") failOn("Semble", 403, "");
    throw new UserError(`Semble: ${String(err.message).slice(0, 300)}`, 502);
  }
  return body.data;
}
const SB_PATIENT = "id firstName lastName fullName dob email";
const sbPatient = (p: any): Patient => ({
  id: String(p.id), name: p.fullName || [p.firstName, p.lastName].filter(Boolean).join(" ") || "Patient",
  date_of_birth: p.dob || null, email: p.email || null,
});

const semble: Adapter = {
  label: "Semble",
  note: "review",
  async connect(body) {
    const key = String(body.api_key || "").trim();
    if (key.length < 20) throw new UserError("Paste the whole API key.");
    const d = await sb({ api_key: key }, "query { practice { id name } }");
    // Settings credentials are not bound to a person, so there is no user name.
    return { api_key: key, shard: "live", config: {}, account_name: d?.practice?.name || null, user_name: null, user_email: null };
  },
  async byEmail(integ, email) {
    const d = await sb(integ, `query($s: String, $p: Pagination) { patients(search: $s, pagination: $p) { data { ${SB_PATIENT} } } }`,
      { s: email, p: { page: 1, pageSize: 10 } });
    return (d?.patients?.data || []).filter((p: any) => sameEmail(p?.email, email)).map(sbPatient);
  },
  async byId(integ, id) {
    const d = await sb(integ, `query($id: ID!) { patient(id: $id) { ${SB_PATIENT} } }`, { id });
    return d?.patient ? sbPatient(d.patient) : null;
  },
  idFromPaste: (t) => idFrom(t, /^[A-Za-z0-9_-]{1,64}$/),
  async appointments(integ, patientId) {
    // UNCONFIRMED: the Date scalar's format is undocumented; ISO date assumed.
    const day = (y: number) => { const d = new Date(); d.setUTCFullYear(d.getUTCFullYear() + y); return d.toISOString().slice(0, 10); };
    const d = await sb(integ, `query($id: ID!, $a: Date!, $b: Date!) { patient(id: $id) { bookings(start: $a, end: $b) {
      id start end deleted cancellationReason doctorName appointment { title } } } }`, { id: patientId, a: day(-3), b: day(1) });
    return (d?.patient?.bookings || []).filter((b: any) => !b.deleted).map((b: any): Appointment => ({
      id: String(b.id), starts_at: b.start || null, ends_at: b.end || null, type: b.appointment?.title || null,
      practitioner: b.doctorName || null, cancelled: !!b.cancellationReason, did_not_arrive: false, note_status: null,
    })).sort(newestFirst).slice(0, 40);
  },
  async send(integ, { patientId, bytes, filename, note }) {
    // UNCONFIRMED: `type` is documented only as String!; a MIME type is assumed.
    const d = await sb(integ, `mutation($p: ID!, $n: String!, $t: String!) { createPatientDocument(patient: $p, name: $n, type: $t) { data { id uploadUrl } error } }`,
      { p: patientId, n: clipBytes(filename, 128), t: "application/pdf" });
    const made = d?.createPatientDocument;
    if (made?.error) throw new UserError(`Semble: ${String(made.error).slice(0, 300)}`, 502);
    if (!made?.data?.uploadUrl) throw new UserError("Semble gave no upload slot.", 502);
    const up = await call("Semble", made.data.uploadUrl, { method: "PUT", headers: { "Content-Type": "application/octet-stream" }, body: bytes as BodyInit });
    if (!up.ok) throw new UserError(`The file upload failed (${up.status}).`, 502);

    const out: SendOutput = { attachmentId: String(made.data.id), noteId: null, noteError: null, noteSkipped: null };
    if (note && Array.isArray(note.lines)) {
      try {
        // UNCONFIRMED: whether `body` renders HTML is undocumented; plain text sent.
        const l = await sb(integ, `mutation($l: CreateLetterDataInput!) { createLetter(letterData: $l) { data { id } error } }`, { l: {
          patient: patientId, title: clipBytes(String(note.title || "Dashboard report"), 128),
          body: noteText(note, filename), reviewStatus: "AWAITING_REVIEW",
        } });
        if (l?.createLetter?.error) out.noteError = String(l.createLetter.error).slice(0, 300);
        else out.noteId = l?.createLetter?.data?.id ? String(l.createLetter.data.id) : null;
      } catch (e) { out.noteError = (e as Error).message; }
    }
    return out;
  },
};

// ---------------------------------------------------------------- Nookal
// api_key as a variable on every call; { status, data, details } answers.
//
// UNCONFIRMED: the docs describe the PHP SDK's objects, not the raw JSON, so
// field names are read loosely and arrays are searched for by name. Also
// unconfirmed: that POST takes form fields, and what `file_path` means over
// plain HTTP (the SDK passes a local path; the bytes go by PUT regardless).

const NOOKAL_BASE = Deno.env.get("NOOKAL_API_BASE") || "https://api.nookal.com/production/v2";
const norm = (k: string) => k.replace(/_/g, "").toLowerCase();
function pick(o: any, ...names: string[]): any {
  if (!o || typeof o !== "object") return undefined;
  const want = names.map(norm);
  for (const k of Object.keys(o)) if (want.includes(norm(k))) return o[k];
  return undefined;
}
function findDeep(o: any, name: string, depth = 0): any {
  if (!o || typeof o !== "object" || depth > 6) return undefined;
  const direct = pick(o, name);
  if (direct !== undefined) return direct;
  for (const v of Object.values(o)) { const hit = findDeep(v, name, depth + 1); if (hit !== undefined) return hit; }
  return undefined;
}
const asList = (v: any): any[] => Array.isArray(v) ? v : (v && typeof v === "object" ? (pick(v, "id") !== undefined ? [v] : Object.values(v)) : []);

async function nk(integ: { api_key: string }, fn: string, vars: Record<string, string> = {}, post = false): Promise<any> {
  const params = new URLSearchParams({ ...vars, api_key: integ.api_key.trim() });
  const headers: Record<string, string> = { Accept: "application/json", "User-Agent": USER_AGENT };
  const res = post
    ? await call("Nookal", `${NOOKAL_BASE}/${fn}`, { method: "POST", headers: { ...headers, "Content-Type": "application/x-www-form-urlencoded" }, body: params })
    : await call("Nookal", `${NOOKAL_BASE}/${fn}?${params}`, { headers });
  const text = await res.text();
  if (!res.ok) failOn("Nookal", res.status, "");
  let body: any; try { body = JSON.parse(text); } catch (_) { throw new UserError("Nookal gave an unreadable answer.", 502); }
  if (body?.status !== "success") {
    const msg = String(body?.details?.errorMessage || "");
    // Seen live: {"status":"failure","details":{"errorMessage":"Unable to verify account v2","errorCode":"L000_1"}}
    if (/unable to verify|api.?key/i.test(msg)) failOn("Nookal", 401, "");
    throw new UserError(`Nookal: ${msg.slice(0, 300) || "the request failed."}`, 502);
  }
  return body;
}
const nkPatient = (p: any): Patient => ({
  id: String(pick(p, "id", "patient_id")),
  name: [pick(p, "nickname") || pick(p, "first_name", "firstname"), pick(p, "last_name", "lastname")].filter(Boolean).join(" ").trim() || "Patient",
  date_of_birth: pick(p, "dob", "date_of_birth") || null, email: pick(p, "email") || null,
});

const nookal: Adapter = {
  label: "Nookal",
  note: "none",
  async connect(body) {
    const key = String(body.api_key || "").trim();
    if (key.length < 8) throw new UserError("Paste the whole API key.");
    await nk({ api_key: key }, "verify");
    let account: string | null = null;
    try {
      const first = asList(findDeep(await nk({ api_key: key }, "getLocations"), "locations"))[0];
      account = pick(first, "name", "location_name", "business_name") || null;
    } catch (_) { /* optional */ }
    return { api_key: key, shard: "live", config: {}, account_name: account, user_name: null, user_email: null };
  },
  async byEmail(integ, email) {
    // UNCONFIRMED: the docs list e-mail together with first name, last name and
    // date of birth, so an e-mail-only search may be refused. Then this is "no
    // match" and the practitioner pastes the id. Never falls back to a name.
    try {
      const r = await nk(integ, "searchPatients", { email, page_length: "10" });
      return asList(findDeep(r, "patients")).filter((p) => sameEmail(pick(p, "email"), email)).map(nkPatient);
    } catch (e) {
      if (e instanceof UserError && e.status === 502) return [];
      throw e;
    }
  },
  async byId(integ, id) {
    const r = await nk(integ, "searchPatients", { patient_id: id });
    const p = asList(findDeep(r, "patients"))[0];
    return p ? nkPatient(p) : null;
  },
  idFromPaste: (t) => idFrom(t, /^\d{1,12}$/),
  async appointments(integ, patientId) {
    const [appts, pracs] = await Promise.all([
      nk(integ, "getAppointments", { patient_id: patientId, page_length: "100" }),
      nk(integ, "getPractitioners").catch(() => null),
    ]);
    const pracName: Record<string, string> = {};
    for (const p of asList(findDeep(pracs, "practitioners"))) pracName[String(pick(p, "id"))] = [pick(p, "first_name", "firstname"), pick(p, "last_name", "lastname")].filter(Boolean).join(" ");
    const at = (d: any, t: any) => d ? (t ? `${d}T${t}` : String(d)) : null;
    const yes = (v: any) => v === 1 || v === "1" || v === true;
    return asList(findDeep(appts, "appointments")).map((a: any): Appointment => {
      const date = pick(a, "date", "appointment_date", "appt_date");
      return {
        id: String(pick(a, "id")), starts_at: at(date, pick(a, "start_time", "starttime")), ends_at: at(date, pick(a, "end_time", "endtime")),
        type: pick(a, "type") || null, practitioner: pracName[String(pick(a, "practitioner_id", "practitionerid"))] || null,
        cancelled: yes(pick(a, "cancelled")), did_not_arrive: yes(pick(a, "dna")), note_status: null,
      };
    }).sort(newestFirst).slice(0, 40);
  },
  async send(integ, { patientId, bytes, filename, note }) {
    // 1. the file's record + a PUT address  2. PUT the bytes  3. activate it
    const r = await nk(integ, "uploadFile", {
      patient_id: patientId, name: filename.replace(/\.pdf$/i, ""), extension: "pdf", file_type: "application/pdf", file_path: filename,
    }, true);
    const fileId = findDeep(r, "file_id"), url = findDeep(r, "url");
    if (!fileId || !url) throw new UserError("Nookal gave no upload slot.", 502);
    const up = await call("Nookal", String(url), { method: "PUT", body: bytes as BodyInit });
    if (!up.ok) throw new UserError(`The file upload failed (${up.status}).`, 502);
    await nk(integ, "setFileActive", { patient_id: patientId, file_id: String(fileId) }, true);
    return { attachmentId: String(fileId), noteId: null, noteError: null,
      noteSkipped: note ? "Nookal has no draft notes, so the report was filed without a note." : null };
  },
};

// ---------------------------------------------------------------- Halaxy
// FHIR R4B, OAuth client_credentials (tokens last 15 minutes; a 401 fetches a new one).
// UK + EU must use eu-api; everyone else au-api. The API is a paid add-on.

const halaxyBase = (region: string) => Deno.env.get("HALAXY_API_BASE") || `https://${region === "au" ? "au" : "eu"}-api.halaxy.com/main`;
const tokens = new Map<string, string>();

async function hxToken(secret: string, clientId: string, region: string): Promise<string> {
  const cached = tokens.get(clientId);
  if (cached) return cached;
  const res = await call("Halaxy", `${halaxyBase(region)}/oauth/token`, {
    method: "POST",
    headers: { Accept: "application/fhir+json", "Content-Type": "application/json", "User-Agent": USER_AGENT },
    body: JSON.stringify({ grant_type: "client_credentials", client_id: clientId, client_secret: secret }),
  });
  const text = await res.text();
  if (!res.ok) failOn("Halaxy", res.status === 400 ? 401 : res.status, text);
  const t = JSON.parse(text)?.access_token;
  if (!t) throw new UserError("Halaxy gave no access token.", 502);
  tokens.set(clientId, t);
  return t;
}
async function hx(integ: Integration, path: string, init: RequestInit = {}): Promise<any> {
  const clientId = String(integ.config?.client_id || "");
  const go = async () => call("Halaxy", `${halaxyBase(integ.shard)}${path}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${await hxToken(integ.api_key, clientId, integ.shard)}`,
      Accept: "application/fhir+json", "Content-Type": "application/fhir+json", "User-Agent": USER_AGENT,
    },
  });
  let res = await go();
  // A warm function can hold an expired token: drop it and try once more.
  if (res.status === 401) { tokens.delete(clientId); res = await go(); }
  const text = await res.text();
  if (!res.ok) failOn("Halaxy", res.status, text);
  return text ? JSON.parse(text) : null;
}
const entries = (b: any): any[] => (b?.entry || []).map((e: any) => e.resource).filter(Boolean);
const hxPatient = (p: any): Patient => {
  const n = p?.name?.[0] || {};
  return {
    id: String(p.id), name: n.text || [...(n.given || []), n.family].filter(Boolean).join(" ") || "Patient",
    date_of_birth: p.birthDate || null,
    email: (p.telecom || []).find((t: any) => t.system === "email")?.value || null,
  };
};
// PR- = a practitioner in this practice; EP- = an outside contact, never the author.
const hxRoleLabel = (r: any) => r?.practitioner?.display || r?.location?.[0]?.display || String(r?.id);

const halaxy: Adapter = {
  label: "Halaxy",
  note: "draft",
  async connect(body) {
    const client_id = String(body.client_id || "").trim(), secret = String(body.client_secret || "").trim();
    if (!client_id || !secret) throw new UserError("Paste both the Client ID and the Client Secret.");
    const shard = String(body.region || "eu") === "au" ? "au" : "eu";
    tokens.delete(client_id);
    const probe: Integration = { api_key: secret, shard, config: { client_id } };
    const roles = entries(await hx(probe, "/PractitionerRole?_count=100")).filter((r) => String(r.id).startsWith("PR-"));
    if (!roles.length) throw new UserError("Halaxy shows no practitioner on this account, and a note needs an author.");
    // The credentials belong to the practice, not a person: with several
    // practitioners or locations the note's author has to be chosen.
    const wanted = String(body.role_id || "");
    const role = wanted ? roles.find((r) => r.id === wanted) : (roles.length === 1 ? roles[0] : null);
    if (wanted && !role) throw new UserError("That practitioner is not on this Halaxy account.");
    if (!role) return { choose_role: roles.map((r) => ({ id: String(r.id), label: hxRoleLabel(r) })) };
    let account: string | null = null;
    try { account = entries(await hx(probe, "/Organization?_count=1"))[0]?.name || null; } catch (_) { /* optional */ }
    return { api_key: secret, shard, config: { client_id, role_id: role.id }, account_name: account, user_name: role.practitioner?.display || null, user_email: null };
  },
  async byEmail(integ, email) {
    const b = await hx(integ, `/Patient?_count=10&email=${encodeURIComponent(email)}`);
    return entries(b).map(hxPatient).filter((p) => sameEmail(p.email, email));
  },
  async byId(integ, id) { return hxPatient(await hx(integ, `/Patient/${encodeURIComponent(id)}`)); },
  idFromPaste: (t) => idFrom(t, /^\d{1,20}$/),
  async appointments(integ, patientId) {
    const b = await hx(integ, `/Appointment?_count=40&_sort=-date&patient=${encodeURIComponent(patientId)}`);
    return entries(b).map((a: any): Appointment => {
      const me = (a.participant || []).find((p: any) => p.actor?.type === "Patient" && String(p.actor?.reference || "").endsWith(`/${patientId}`));
      const status = String(me?.modifierExtension?.[0]?.valueCoding?.code || a.status || "").toLowerCase();
      const prac = (a.participant || []).find((p: any) => p.actor?.type === "PractitionerRole");
      return {
        id: String(a.id), starts_at: a.start || null, ends_at: a.end || null, type: null, practitioner: prac?.actor?.display || null,
        cancelled: status.includes("cancel"), did_not_arrive: status.includes("noshow") || status.includes("no-show"), note_status: null,
      };
    }).sort(newestFirst);
  },
  async send(integ, { patientId, b64, filename, note }) {
    // One record holds both: a draft clinical note with the PDF attached. A
    // note needs text/html content, so the bare file still gets a line of text.
    const base = halaxyBase(integ.shard);
    const n = note && Array.isArray(note.lines) ? note : { title: "Dashboard report", lines: [] };
    const r = await hx(integ, "/DocumentReference", { method: "POST", body: JSON.stringify({
      resourceType: "DocumentReference", status: "current", docStatus: "preliminary",
      description: String(n.title || "Dashboard report").slice(0, 255),
      subject: { type: "Patient", reference: `${base}/Patient/${patientId}` },
      author: [{ type: "PractitionerRole", reference: `${base}/PractitionerRole/${integ.config?.role_id}` }],
      content: [
        { attachment: { contentType: "text/html", data: noteHtml(n, filename) } },
        { attachment: { contentType: "application/pdf", data: b64, title: filename } },
      ],
    }) });
    const id = r?.id ? String(r.id) : null;
    return { attachmentId: id, noteId: id, noteError: null, noteSkipped: null };
  },
};

const ADAPTERS: Record<string, Adapter> = { semble, nookal, halaxy };

// ---------------------------------------------------------------- handler

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  try {
    if (req.method !== "POST") throw new UserError("POST only", 405);
    const auth = req.headers.get("Authorization") || "";
    if (!auth.startsWith("Bearer ")) throw new UserError("Sign in first.", 401);
    const appId = APP_SCOPED ? (req.headers.get("x-app-id") || "vertigo") : null;

    // Who is calling: the JWT's user, then their practitioner rows. The
    // connection belongs to the PERSON, across every app row they have.
    const asUser = createClient(SUPABASE_URL, ANON_KEY, { global: { headers: { Authorization: auth } } });
    const { data: { user }, error: uerr } = await asUser.auth.getUser();
    if (uerr || !user) throw new UserError("Sign in first.", 401);
    const db = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });
    const { data: prs } = await db.from("practitioners")
      .select("id,display_name,practice_name" + (APP_SCOPED ? ",app_id" : "")).eq("user_id", user.id);
    const rows: any[] = prs || [];
    if (!rows.length) throw new UserError("That account is not set up as a practitioner.", 403);
    const me = (APP_SCOPED && rows.find((r) => r.app_id === appId)) || rows[0];
    const myIds: string[] = rows.map((r) => r.id);

    const body = await req.json().catch(() => ({}));
    const action = String(body.action || "");
    const stamp: Record<string, string> = APP_SCOPED ? { app_id: appId! } : {};

    const loadInteg = async (provider: string): Promise<Integration | null> => {
      const { data } = await db.from("practitioner_integrations").select("*")
        .eq("user_id", user.id).eq("provider", provider).limit(1);
      return (data?.[0] as Integration) || null;
    };
    // Named fields only: api_key and config never leave this function.
    const publicInteg = (i: Integration | null, a: Adapter) => i ? {
      connected: true, note: a.note, account_name: i.account_name, user_name: i.user_name, user_email: i.user_email,
      connected_at: i.connected_at, last_used_at: i.last_used_at,
    } : { connected: false, note: a.note };

    if (action === "status" && !body.provider) {
      const out: Record<string, unknown> = {};
      for (const [name, a] of Object.entries(ADAPTERS)) out[name] = publicInteg(await loadInteg(name), a);
      return json({ providers: out });
    }

    const PROVIDER = String(body.provider || "");
    if (!Object.prototype.hasOwnProperty.call(ADAPTERS, PROVIDER)) throw new UserError("Unknown provider.");
    const adapter = ADAPTERS[PROVIDER];

    const needInteg = async () => {
      const i = await loadInteg(PROVIDER);
      if (!i) throw new UserError(`Connect ${adapter.label} first, on the me-practitioner.com home page.`, 412);
      return i;
    };
    const touch = (i: Integration, err: string | null = null) =>
      db.from("practitioner_integrations").update({ last_used_at: new Date().toISOString(), last_error: err }).eq("id", i.id!);
    // A link_id must be one of THIS practitioner's active client links.
    const needLink = async () => {
      const linkId = String(body.link_id || "");
      if (!linkId) throw new UserError("link_id missing");
      const { data } = await db.from("practitioner_clients").select("id,user_id,status,scopes")
        .eq("id", linkId).in("practitioner_id", myIds).limit(1);
      const link = data?.[0];
      if (!link || link.status !== "active") throw new UserError("That client is not connected to you.", 403);
      return link as { id: string; user_id: string; scopes: string[] };
    };
    const loadPatientRow = async (linkId: string) => {
      const { data } = await db.from("practitioner_integration_patients").select("*")
        .eq("link_id", linkId).eq("provider", PROVIDER).limit(1);
      return data?.[0] || null;
    };
    const needPatientRow = async (linkId: string) => {
      const row = await loadPatientRow(linkId);
      if (!row) throw new UserError("Match the patient first.", 412);
      return row;
    };

    switch (action) {

      case "status": {
        const integ = await loadInteg(PROVIDER);
        const out: any = publicInteg(integ, adapter);
        if (body.link_id && integ) {
          const link = await needLink();
          const row = await loadPatientRow(link.id);
          out.patient = row ? { id: row.external_id, name: row.external_name, url: row.external_url, matched_by: row.matched_by } : null;
          const { data } = await db.from("practitioner_integration_events").select("kind,external_id,created_at,detail")
            .eq("link_id", link.id).eq("provider", PROVIDER).order("created_at", { ascending: false }).limit(5);
          out.sent = data || [];
        }
        return json(out);
      }

      case "connect": {
        const c = await adapter.connect(body);
        if ("choose_role" in c) return json({ connected: false, choose_role: c.choose_role });
        const now = new Date().toISOString();
        const row = {
          ...stamp, practitioner_id: me.id, user_id: user.id, login_email: (user.email || "").toLowerCase() || null,
          provider: PROVIDER, ...c, connected_at: now, last_used_at: now, last_error: null,
        };
        const { error } = await db.from("practitioner_integrations").upsert(row as any, { onConflict: "user_id,provider" });
        if (error) throw new Error(error.message);
        return json(publicInteg(await loadInteg(PROVIDER), adapter));
      }

      case "disconnect": {
        await db.from("practitioner_integrations").delete().eq("user_id", user.id).eq("provider", PROVIDER);
        const { data: links } = await db.from("practitioner_clients").select("id").in("practitioner_id", myIds);
        const ids = (links || []).map((l: any) => l.id);
        if (ids.length) await db.from("practitioner_integration_patients").delete().in("link_id", ids).eq("provider", PROVIDER);
        return json({ connected: false, note: adapter.note });
      }

      case "match": {
        const integ = await needInteg();
        const link = await needLink();
        let patient: Patient | null = null, matched_by = "email";
        const pasted = String(body.patient_url || "").trim();
        if (pasted) {
          const id = adapter.idFromPaste(pasted);
          if (!id) throw new UserError(`Paste the patient's id or page link from ${adapter.label}.`);
          patient = await adapter.byId(integ, id);
          if (!patient) throw new UserError(`${adapter.label} has no patient with that id.`, 404);
          matched_by = "manual";
        } else {
          const { data: au } = await db.auth.admin.getUserById(link.user_id);
          const email = au?.user?.email?.trim().toLowerCase();
          if (!email) throw new UserError(`This client's account has no e-mail to match on. Paste their ${adapter.label} patient id instead.`);
          const hits = await adapter.byEmail(integ, email);
          if (hits.length !== 1) { await touch(integ); return json({ patient: null, reason: hits.length ? "several_matches" : "no_match" }); }
          patient = hits[0];
        }
        const row = {
          ...stamp, link_id: link.id, provider: PROVIDER, external_id: patient.id, external_name: patient.name,
          external_url: null, matched_by, updated_at: new Date().toISOString(),
        };
        const { error } = await db.from("practitioner_integration_patients").upsert(row as any, { onConflict: "link_id,provider" });
        if (error) throw new Error(error.message);
        await touch(integ);
        return json({ patient: { ...patient, url: null, matched_by } });
      }

      case "unmatch": {
        const link = await needLink();
        await db.from("practitioner_integration_patients").delete().eq("link_id", link.id).eq("provider", PROVIDER);
        return json({ patient: null });
      }

      case "appointments": {
        const integ = await needInteg();
        const link = await needLink();
        const row = await needPatientRow(link.id);
        const out = await adapter.appointments(integ, row.external_id);
        await touch(integ);
        return json({ appointments: out, total: out.length });
      }

      case "send": {
        const integ = await needInteg();
        const link = await needLink();
        const row = await needPatientRow(link.id);
        const b64 = String(body.pdf_base64 || "");
        if (!b64) throw new UserError("No PDF in the request.");
        let bytes: Uint8Array;
        try { bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0)); }
        catch (_) { throw new UserError("That PDF did not arrive intact."); }
        if (bytes.length > MAX_PDF) throw new UserError("That PDF is over 12 MB. Narrow the range.");
        const filename = String(body.filename || "report.pdf").replace(/[^\w.-]+/g, "-");
        const description = String(body.description || "").slice(0, 255);
        const r = await adapter.send(integ, { patientId: row.external_id, b64, bytes, filename, description, note: body.note || null });

        const events: any[] = [];
        if (r.attachmentId) events.push({ ...stamp, link_id: link.id, provider: PROVIDER, kind: "attachment", external_id: r.attachmentId, detail: { filename, bytes: bytes.length } });
        if (r.noteId) events.push({ ...stamp, link_id: link.id, provider: PROVIDER, kind: adapter.note === "review" ? "letter" : "treatment_note", external_id: r.noteId, detail: { title: body.note?.title || null } });
        if (events.length) await db.from("practitioner_integration_events").insert(events as any);
        await touch(integ, r.noteError);
        return json({
          attachment: r.attachmentId ? { id: r.attachmentId, filename } : null,
          note: r.noteId ? { id: r.noteId, draft: true } : null, note_error: r.noteError, note_skipped: r.noteSkipped,
        });
      }

      default:
        throw new UserError(`Unknown action ${action || "(none)"}`);
    }
  } catch (e) {
    const status = e instanceof UserError ? e.status : 500;
    if (status === 500) console.error("practice-software", e);
    return json({ error: status === 500 ? "Something went wrong" : (e as Error).message }, status);
  }
});
