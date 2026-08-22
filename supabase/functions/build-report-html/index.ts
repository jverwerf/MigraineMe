// supabase/functions/build-report-html/index.ts
//
// Returns the clinical summary as HTML. Each client prints that HTML to a PDF
// locally (WKWebView on iOS, WebView on Android, expo-print on the Expo apps),
// so the document's layout lives here once instead of being re-implemented in
// Kotlin, Swift and TypeScript.
//
// Deploy: supabase functions deploy build-report-html

import { loadReportData, ReportParams } from "./data.ts";
import { renderReport } from "./render.ts";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

const json = (body: unknown, status: number) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, "Content-Type": "application/json" },
  });

/** The bearer token's payload, or null when it is not a readable JWT.
 *
 *  Read, not verified — PostgREST verifies the signature again on every query
 *  below, and nothing here grants a privilege the token does not already
 *  carry. This only needs to know what KIND of caller is asking. */
function readClaims(authHeader: string): Record<string, unknown> | null {
  const token = authHeader.replace(/^Bearer\s+/i, "").trim();
  const part = token.split(".")[1];
  if (!part) return null;
  try {
    const b64 = part.replace(/-/g, "+").replace(/_/g, "/");
    return JSON.parse(atob(b64 + "=".repeat((4 - (b64.length % 4)) % 4)));
  } catch {
    return null;
  }
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });

  const authHeader = req.headers.get("Authorization");
  if (!authHeader) return json({ error: "missing auth" }, 401);

  let body: Record<string, unknown> = {};
  try {
    if (req.method === "POST") body = (await req.json()) as Record<string, unknown>;
  } catch { /* an empty body means "everything, all time" */ }
  if (!body || typeof body !== "object" || Array.isArray(body)) body = {};

  const params = body as ReportParams;
  // The list params must really be arrays — episodeIds especially, where
  // present-but-empty means "zero attacks" and absent means "no filter".
  for (const k of ["episodeIds", "metricKeys", "disabledMetricKeys", "sections"] as const) {
    if (params[k] !== undefined && !Array.isArray(params[k])) delete params[k];
  }

  // Every query below relies on row-level security to keep the document to one
  // patient. A service-role token bypasses RLS entirely, and the same code then
  // happily assembles one clinical summary out of every user in the project —
  // a patient-data breach, not a report. MigraineMe has no server-side caller,
  // so the honest answer is to refuse rather than to guess a subject.
  const claims = readClaims(authHeader);
  if (claims?.role === "service_role") {
    return json({
      error: "service-role tokens cannot build reports",
      detail:
        "This function scopes the document by row-level security, which a "
        + "service-role token bypasses. Call it with the patient's own access "
        + "token.",
    }, 403);
  }

  // A practitioner reading a client. Consent is checked here, against the
  // caller's own token, before anything is fetched: practitioner_can_read
  // returns true only for an active, unrevoked link that carries the scope.
  // Without this the subject parameter would be a way to read any user id.
  if (typeof params.subject === "string" && params.subject) {
    const gate = await fetch(
      `${Deno.env.get("SUPABASE_URL")}/rest/v1/rpc/practitioner_can_read`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          apikey: Deno.env.get("SUPABASE_ANON_KEY")!,
          Authorization: authHeader,
        },
        body: JSON.stringify({ target_user: params.subject, required_scope: "attacks" }),
      },
    );
    const allowed = gate.ok && (await gate.json()) === true;
    if (!allowed) {
      return json({
        error: "not permitted",
        detail: "You do not hold an active consent to read this person's diary.",
      }, 403);
    }
  }

  try {
    const data = await loadReportData(authHeader, params);
    const html = renderReport(data);
    return new Response(html, {
      headers: { ...cors, "Content-Type": "text/html; charset=utf-8" },
    });
  } catch (e) {
    console.error("build-report-html failed", e);
    return json({ error: String(e) }, 500);
  }
});
