// supabase/functions/cliniko/index.ts
//
// The practitioner dashboard's bridge to Cliniko (practice-management
// software). One function, one `action` per call, always on behalf of the
// signed-in practitioner:
//
//   status        is this practitioner connected; for a link_id, which patient
//   connect       store an API key after checking it against Cliniko
//   disconnect    forget the key
//   match         find the client's Cliniko patient by e-mail, or by a pasted
//                 patient link when the e-mails differ
//   unmatch       forget the patient
//   appointments  the patient's sessions, newest first
//   send          file a PDF into the patient's record and add a DRAFT
//                 treatment note with the key numbers
//
// WHY A FUNCTION: the API key is a credential for the practitioner's own
// Cliniko account and the client's e-mail lives in auth.users. Neither may
// reach the browser, so both stay here, behind the service role. The caller's
// JWT must belong to a practitioner row; every link_id is checked against
// that practitioner's own active client links before anything is read.
//
// Cliniko API: https://docs.api.cliniko.com (Basic auth, key as username;
// host shard = suffix of the key; 200 requests/minute; no webhooks).
//
// Deploy: supabase functions deploy cliniko   (JWT verified: the caller is a
// signed-in practitioner, never public)

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

// MeSeries serves several apps and scopes practitioner rows by app_id; the
// MigraineMe project has no app_id anywhere. The MeSeries copy sets this true.
const APP_SCOPED = false;

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
// Cliniko asks every integration to say who it is.
const USER_AGENT = "MeSeries practitioner dashboard (help@migraineme.app)";
const PROVIDER = "cliniko";
const KNOWN_SHARDS = ["au1","au2","au3","au4","au5","ca1","uk1","uk2","uk3","us1","eu1"];

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, x-app-id, x-practitioner-view",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

class UserError extends Error { status: number; constructor(m: string, s = 400) { super(m); this.status = s; } }

type Integration = {
  id: string; practitioner_id: string; api_key: string; shard: string; web_base: string | null;
  account_name: string | null; user_name: string | null; user_email: string | null;
  connected_at: string; last_used_at: string | null;
};

// ---------------------------------------------------------------- Cliniko

function shardOf(key: string): string {
  const m = key.trim().match(/-([a-z]{2}\d{1,2})$/i);
  const s = m ? m[1].toLowerCase() : "au1";          // pre-sharding keys are au1
  if (!KNOWN_SHARDS.includes(s) && !/^[a-z]{2}\d{1,2}$/.test(s)) throw new UserError("That does not look like a Cliniko API key.");
  return s;
}
// Tests point this at a local stand-in; unset in production.
const API_BASE_OVERRIDE = Deno.env.get("CLINIKO_API_BASE") || "";
const apiBase = (shard: string) => API_BASE_OVERRIDE || `https://api.${shard}.cliniko.com/v1`;

async function ck(integ: { api_key: string; shard: string }, path: string, init: RequestInit = {}): Promise<any> {
  const url = path.startsWith("http") ? path : apiBase(integ.shard) + path;
  const res = await fetch(url, {
    ...init,
    headers: {
      Authorization: "Basic " + btoa(integ.api_key.trim() + ":"),
      Accept: "application/json",
      "User-Agent": USER_AGENT,
      ...(init.body && !(init.body instanceof FormData) ? { "Content-Type": "application/json" } : {}),
      ...(init.headers as Record<string, string> || {}),
    },
  });
  if (res.status === 401) throw new UserError("Cliniko did not accept the API key. Check it under My info, API keys in Cliniko.", 401);
  if (res.status === 403) {
    // Cliniko also answers 403 when an account's file storage is full; say what it said.
    const why = await res.clone().json().then((j) => String(j?.message || "")).catch(() => "");
    if (/file size limit/i.test(why)) throw new UserError("Cliniko says this account has reached its file size limit, so nothing more can be filed. Free up space in Cliniko or upgrade the account.", 403);
    throw new UserError("That Cliniko key is not allowed to do this. It needs a user with access to patients and treatment notes.", 403);
  }
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
const patientName = (p: any) => [p?.preferred_first_name || p?.first_name, p?.last_name].filter(Boolean).join(" ").trim() || "Patient";
const patientOut = (p: any, integ: Integration, matched_by: string) => ({
  id: String(p.id), name: patientName(p), date_of_birth: p.date_of_birth || null, email: p.email || null,
  url: integ.web_base ? `${integ.web_base}/patients/${p.id}` : null, matched_by,
});

// ---------------------------------------------------------------- handler

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  try {
    if (req.method !== "POST") throw new UserError("POST only", 405);
    const auth = req.headers.get("Authorization") || "";
    if (!auth.startsWith("Bearer ")) throw new UserError("Sign in first.", 401);
    const appId = APP_SCOPED ? (req.headers.get("x-app-id") || "vertigo") : null;

    // Who is calling: the JWT's user, then their practitioner row.
    const asUser = createClient(SUPABASE_URL, ANON_KEY, { global: { headers: { Authorization: auth } } });
    const { data: { user }, error: uerr } = await asUser.auth.getUser();
    if (uerr || !user) throw new UserError("Sign in first.", 401);
    const db = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });
    // Every practitioner row this person has (MeSeries: one per app). The
    // Cliniko connection is theirs as a PERSON (Jordy 2026-11-04: one key,
    // entered once, every board); client links are checked against all
    // their rows, so the same key serves every app board.
    const { data: prs } = await db.from("practitioners")
      .select("id,display_name,practice_name" + (APP_SCOPED ? ",app_id" : "")).eq("user_id", user.id);
    const rows: any[] = prs || [];
    if (!rows.length) throw new UserError("That account is not set up as a practitioner.", 403);
    const me = (APP_SCOPED && rows.find((r) => r.app_id === appId)) || rows[0];
    const myIds: string[] = rows.map((r) => r.id);

    const body = await req.json().catch(() => ({}));
    const action = String(body.action || "");
    const stamp: Record<string, string> = APP_SCOPED ? { app_id: appId! } : {};

    const loadInteg = async (): Promise<Integration | null> => {
      const { data } = await db.from("practitioner_integrations").select("*")
        .eq("user_id", user.id).eq("provider", PROVIDER).limit(1);
      return (data?.[0] as Integration) || null;
    };
    const publicInteg = (i: Integration | null) => i ? {
      connected: true, account_name: i.account_name, user_name: i.user_name, user_email: i.user_email,
      shard: i.shard, web_base: i.web_base, connected_at: i.connected_at, last_used_at: i.last_used_at,
    } : { connected: false };
    const needInteg = async () => { const i = await loadInteg(); if (!i) throw new UserError("Connect Cliniko first, under Your dashboard.", 412); return i; };
    const touch = (i: Integration, err: string | null = null) =>
      db.from("practitioner_integrations").update({ last_used_at: new Date().toISOString(), last_error: err }).eq("id", i.id);

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
    const lastSent = async (linkId: string) => {
      const { data } = await db.from("practitioner_integration_events").select("kind,external_id,created_at,detail")
        .eq("link_id", linkId).eq("provider", PROVIDER).order("created_at", { ascending: false }).limit(5);
      return data || [];
    };

    switch (action) {

      case "status": {
        const integ = await loadInteg();
        const out: any = publicInteg(integ);
        if (body.link_id && integ) {
          const link = await needLink();
          const row = await loadPatientRow(link.id);
          out.patient = row ? {
            id: row.external_id, name: row.external_name, url: row.external_url, matched_by: row.matched_by,
          } : null;
          out.sent = await lastSent(link.id);
        }
        return json(out);
      }

      case "connect": {
        const key = String(body.api_key || "").trim();
        if (key.length < 20) throw new UserError("Paste the whole API key.");
        const shard = shardOf(key);
        const probe = { api_key: key, shard };
        const u = await ck(probe, "/user");                       // 401 here = wrong key
        let account: string | null = null;
        try { const b = await ck(probe, "/businesses?per_page=1"); account = b?.businesses?.[0]?.business_name || null; } catch (_) { /* optional */ }
        const row = {
          ...stamp, practitioner_id: me.id, user_id: user.id, login_email: (user.email || "").toLowerCase() || null,
          provider: PROVIDER, api_key: key, shard,
          account_name: account, user_name: [u.first_name, u.last_name].filter(Boolean).join(" "), user_email: u.email || null,
          connected_at: new Date().toISOString(), last_used_at: new Date().toISOString(), last_error: null,
        };
        const { error } = await db.from("practitioner_integrations").upsert(row as any, { onConflict: "user_id,provider" });
        if (error) throw new Error(error.message);
        return json(publicInteg(await loadInteg()));
      }

      case "disconnect": {
        await db.from("practitioner_integrations").delete().eq("user_id", user.id).eq("provider", PROVIDER);
        // the patient matches belong to the connection, across all their app rows
        const { data: links } = await db.from("practitioner_clients").select("id").in("practitioner_id", myIds);
        const ids = (links || []).map((l: any) => l.id);
        if (ids.length) await db.from("practitioner_integration_patients").delete().in("link_id", ids).eq("provider", PROVIDER);
        return json({ connected: false });
      }

      case "match": {
        const integ = await needInteg();
        const link = await needLink();
        let patient: any = null, matched_by = "email";
        const pasted = String(body.patient_url || "").trim();
        if (pasted) {
          // https://<account>.<shard>.cliniko.com/patients/123456  (anything after the id is fine)
          const m = pasted.match(/^(https?:\/\/[a-z0-9-]+\.([a-z]{2}\d{1,2})\.cliniko\.com)\/patients\/(\d+)/i) || pasted.match(/^(\d{3,})$/);
          if (!m) throw new UserError("Paste the patient's page link from Cliniko, it looks like https://yourclinic.uk2.cliniko.com/patients/123456.");
          const pid = m[3] || m[1];
          if (m[3] && m[2].toLowerCase() !== integ.shard) throw new UserError(`That link is on ${m[2]} but your key is for ${integ.shard}.`);
          patient = await ck(integ, `/patients/${pid}`);
          matched_by = "manual";
          if (m[3] && integ.web_base !== m[1]) { integ.web_base = m[1]; await db.from("practitioner_integrations").update({ web_base: m[1] }).eq("id", integ.id); }
        } else {
          const { data: au } = await db.auth.admin.getUserById(link.user_id);
          const email = au?.user?.email?.trim().toLowerCase();
          if (!email) throw new UserError("This client's account has no e-mail to match on. Paste their Cliniko patient link instead.");
          const r = await ck(integ, `/patients?per_page=5&q[]=${encodeURIComponent("email:=" + email)}`);
          const list: any[] = r?.patients || [];
          if (!list.length) { await touch(integ); return json({ patient: null, reason: "no_match" }); }
          patient = list[0];
        }
        const row = {
          ...stamp, link_id: link.id, provider: PROVIDER, external_id: String(patient.id), external_name: patientName(patient),
          external_url: integ.web_base ? `${integ.web_base}/patients/${patient.id}` : null, matched_by, updated_at: new Date().toISOString(),
        };
        const { error } = await db.from("practitioner_integration_patients").upsert(row as any, { onConflict: "link_id,provider" });
        if (error) throw new Error(error.message);
        await touch(integ);
        return json({ patient: patientOut(patient, integ, matched_by) });
      }

      // The button inside Cliniko lands on me-practitioner.com/cliniko/?patient_id=…
      // which asks every backend: is this Cliniko patient one of my clients?
      case "find_link": {
        const pid = String(body.patient_id || "").replace(/\D/g, "");
        if (!pid) throw new UserError("patient_id missing");
        const { data: links } = await db.from("practitioner_clients").select("id,practitioner_id,status")
          .in("practitioner_id", myIds).eq("status", "active");
        const ids = (links || []).map((l: any) => l.id);
        if (!ids.length) return json({ link: null });
        const { data: hits } = await db.from("practitioner_integration_patients").select("link_id,external_name")
          .eq("provider", PROVIDER).eq("external_id", pid).in("link_id", ids).limit(1);
        const hit = hits?.[0];
        if (!hit) return json({ link: null });
        const link = (links || []).find((l: any) => l.id === hit.link_id);
        const row = rows.find((r) => r.id === link?.practitioner_id);
        return json({ link: { id: hit.link_id, app: APP_SCOPED ? (row?.app_id || "vertigo") : "migraine", name: hit.external_name } });
      }

      case "unmatch": {
        const link = await needLink();
        await db.from("practitioner_integration_patients").delete().eq("link_id", link.id).eq("provider", PROVIDER);
        return json({ patient: null });
      }

      case "appointments": {
        const integ = await needInteg();
        const link = await needLink();
        const row = await loadPatientRow(link.id);
        if (!row) throw new UserError("Match the patient first.", 412);
        const [appts, types, pracs] = await Promise.all([
          ck(integ, `/individual_appointments?per_page=40&sort=starts_at:desc&q[]=${encodeURIComponent("patient_id:=" + row.external_id)}`),
          ck(integ, "/appointment_types?per_page=100").catch(() => null),
          ck(integ, "/practitioners?per_page=100").catch(() => null),
        ]);
        const typeName: Record<string, string> = {}; for (const t of types?.appointment_types || []) typeName[String(t.id)] = t.name;
        const pracName: Record<string, string> = {}; for (const p of pracs?.practitioners || []) pracName[String(p.id)] = [p.first_name, p.last_name].filter(Boolean).join(" ");
        const out = (appts?.individual_appointments || []).map((a: any) => ({
          id: String(a.id), starts_at: a.starts_at, ends_at: a.ends_at,
          type: typeName[idFromLink(a.appointment_type) || ""] || null,
          practitioner: pracName[idFromLink(a.practitioner) || ""] || null,
          cancelled: !!a.cancelled_at, did_not_arrive: !!a.did_not_arrive,
          note_status: a.treatment_note_status || null,
        }));
        await touch(integ);
        return json({ appointments: out, total: appts?.total_entries ?? out.length });
      }

      case "calendar": {
        // The Calendar plugin: the connected person's own sessions in the range on screen (up to about 7 weeks).
        const integ = await needInteg();
        const parsed = (v: unknown) => { const d = new Date(String(v || "")); return isNaN(d.getTime()) ? null : d; };
        const fromD = parsed(body.from) || new Date();
        const toD = parsed(body.to) || new Date(fromD.getTime() + 14 * 864e5);
        if (toD.getTime() - fromD.getTime() > 50 * 864e5) throw new UserError("That range is too long.", 400);
        const from = fromD.toISOString(), to = toD.toISOString();
        const [cliUser, pracs, types] = await Promise.all([
          ck(integ, "/user").catch(() => null),
          ck(integ, "/practitioners?per_page=100").catch(() => null),
          ck(integ, "/appointment_types?per_page=100").catch(() => null),
        ]);
        const typeName: Record<string, string> = {}; for (const t of types?.appointment_types || []) typeName[String(t.id)] = t.name;
        const own = (pracs?.practitioners || []).find((p: any) => idFromLink(p.user) === String(cliUser?.id));
        const q = [`starts_at:>=${from}`, `starts_at:<=${to}`, ...(own ? [`practitioner_id:=${own.id}`] : [])]
          .map((x) => "q[]=" + encodeURIComponent(x)).join("&");
        const all: any[] = [];
        for (let page = 1; page <= 5; page++) {
          const res = await ck(integ, `/individual_appointments?per_page=100&page=${page}&sort=starts_at:asc&${q}`);
          const got = res?.individual_appointments || [];
          all.push(...got);
          if (got.length < 100) break;
        }
        const out = all.filter((a: any) => !a.cancelled_at).map((a: any) => ({
          id: String(a.id), starts_at: a.starts_at, ends_at: a.ends_at, patient: a.patient_name || null,
          type: typeName[idFromLink(a.appointment_type) || ""] || null,
        }));
        await touch(integ);
        return json({ appointments: out });
      }

      case "send": {
        const integ = await needInteg();
        const link = await needLink();
        const row = await loadPatientRow(link.id);
        if (!row) throw new UserError("Match the patient first.", 412);
        // Either file is optional: the practitioner ticks what goes, and a send with neither is refused.
        const b64 = String(body.pdf_base64 || "");
        if (!b64 && !body.zip_base64) throw new UserError("Nothing to send.");
        const filename = String(body.filename || "report.pdf").replace(/[^\w.-]+/g, "-");
        const description = String(body.description || "").slice(0, 255);
        let att: any = null;
        const events: any[] = [];
        if (b64) {
          const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
          if (bytes.length > 12 * 1024 * 1024) throw new UserError("That PDF is over 12 MB. Narrow the range.");
          // 1. presigned post for this patient  2. multipart to S3  3. the record
          const pre = await ck(integ, `/patients/${row.external_id}/attachment_presigned_post`);
          if (!pre?.url || !pre?.fields) throw new UserError("Cliniko gave no upload slot.", 502);
          const fd = new FormData();
          for (const [k, v] of Object.entries(pre.fields)) fd.append(k, String(v));
          fd.append("file", new Blob([bytes], { type: "application/pdf" }), filename);
          const up = await fetch(pre.url, { method: "POST", body: fd });
          const xml = await up.text();
          if (up.status !== 201 && up.status !== 204 && !up.ok) throw new UserError(`The file upload failed (${up.status}).`, 502);
          const key = (xml.match(/<Key>([^<]+)<\/Key>/) || [])[1] || String(pre.fields.key || "");
          if (!key) throw new UserError("The upload gave no file key.", 502);
          const uploadUrl = pre.url.replace(/\/?$/, "/") + key.replace(/^\//, "");
          att = await ck(integ, "/patient_attachments", {
            method: "POST", body: JSON.stringify({ patient_id: row.external_id, upload_url: uploadUrl, description: description || null, filename }),
          });
          events.push({ ...stamp, link_id: link.id, provider: PROVIDER, kind: "attachment", external_id: att?.id ? String(att.id) : null, detail: { filename, bytes: bytes.length } });
        }

        // Optional second file: the same zip of CSVs and charts as the board's Export button.
        let dataFile: any = null, dataError: string | null = null;
        const zb64 = String(body.zip_base64 || "");
        if (zb64) {
          try {
            const zbytes = Uint8Array.from(atob(zb64), (c) => c.charCodeAt(0));
            if (zbytes.length > 12 * 1024 * 1024) throw new UserError("That export is over 12 MB. Narrow the range.");
            const zname = String(body.zip_filename || "data.zip").replace(/[^\w.-]+/g, "-");
            const zpre = await ck(integ, `/patients/${row.external_id}/attachment_presigned_post`);
            if (!zpre?.url || !zpre?.fields) throw new UserError("Cliniko gave no upload slot.", 502);
            const zfd = new FormData();
            for (const [k, v] of Object.entries(zpre.fields)) zfd.append(k, String(v));
            zfd.append("file", new Blob([zbytes], { type: "application/zip" }), zname);
            const zup = await fetch(zpre.url, { method: "POST", body: zfd });
            const zxml = await zup.text();
            if (zup.status !== 201 && zup.status !== 204 && !zup.ok) throw new UserError(`The file upload failed (${zup.status}).`, 502);
            const zkey = (zxml.match(/<Key>([^<]+)<\/Key>/) || [])[1] || String(zpre.fields.key || "");
            if (!zkey) throw new UserError("The upload gave no file key.", 502);
            dataFile = await ck(integ, "/patient_attachments", {
              method: "POST", body: JSON.stringify({ patient_id: row.external_id, upload_url: zpre.url.replace(/\/?$/, "/") + zkey.replace(/^\//, ""), description: String(body.zip_description || "").slice(0, 255) || null, filename: zname }),
            });
            events.push({ ...stamp, link_id: link.id, provider: PROVIDER, kind: "attachment", external_id: dataFile?.id ? String(dataFile.id) : null, detail: { filename: zname, bytes: zbytes.length } });
          } catch (e) { dataError = (e as Error).message; }
        }

        // 4. a DRAFT treatment note: the practitioner finishes and signs it in Cliniko.
        let note: any = null, noteError: string | null = null;
        const n = body.note || {};
        if (n && Array.isArray(n.lines)) {
          const esc = (s: unknown) => String(s ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
          const html = (n.lines as any[]).map((l) => Array.isArray(l) ? `<p><b>${esc(l[0])}</b>: ${esc(l[1])}</p>` : `<p>${esc(l)}</p>`).join("");
          const plain = (n.lines as any[]).map((l) => Array.isArray(l) ? `${l[0]}: ${l[1]}` : String(l)).join("\n");
          const section = (type: string, answer: string) => ({
            patient_id: row.external_id, title: String(n.title || "Dashboard report").slice(0, 255), draft: true,
            content: { sections: [{ name: String(n.section || "Summary"), description: null, questions: [
              ...(n.summary ? [{ name: String(n.summary_label || "Summary"), type: "text", answer: String(n.summary).slice(0, 4000) }] : []),
              { name: String(n.question || "Key numbers"), type, answer },
              ...(n.shared ? [{ name: String(n.shared_label || "Shared data"), type: "text", answer: String(n.shared) }] : []),
              { name: String(n.attachment_label || "Attachment"), type: "text", answer: b64 ? filename : String(body.zip_filename || "") },
            ] }] },
          });
          try { note = await ck(integ, "/treatment_notes", { method: "POST", body: JSON.stringify(section("paragraph", html)) }); }
          catch (e1) {
            try { note = await ck(integ, "/treatment_notes", { method: "POST", body: JSON.stringify(section("text", plain)) }); }
            catch (e2) { noteError = (e2 as Error).message; }
          }
          if (note) events.push({ ...stamp, link_id: link.id, provider: PROVIDER, kind: "treatment_note", external_id: note?.id ? String(note.id) : null, detail: { title: n.title || null } });
        }
        await db.from("practitioner_integration_events").insert(events as any);
        await touch(integ, noteError);
        return json({ attachment: att ? { id: String(att.id), filename, processing_completed: att.processing_completed ?? false } : null,
                      note: note ? { id: String(note.id), draft: true } : null, note_error: noteError,
                      data_file: dataFile ? { id: String(dataFile.id) } : null, data_error: dataError });
      }

      default:
        throw new UserError(`Unknown action ${action || "(none)"}`);
    }
  } catch (e) {
    const status = e instanceof UserError ? e.status : 500;
    if (status === 500) console.error("cliniko", e);
    return json({ error: (e as Error).message || "Something went wrong" }, status);
  }
});
