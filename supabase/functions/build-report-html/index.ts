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

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });

  const authHeader = req.headers.get("Authorization");
  if (!authHeader) {
    return new Response(JSON.stringify({ error: "missing auth" }), {
      status: 401, headers: { ...cors, "Content-Type": "application/json" },
    });
  }

  let params: ReportParams = {};
  try {
    if (req.method === "POST") params = (await req.json()) as ReportParams;
  } catch { /* an empty body means "everything, all time" */ }

  try {
    const data = await loadReportData(authHeader, params);
    const html = renderReport(data);
    return new Response(html, {
      headers: { ...cors, "Content-Type": "text/html; charset=utf-8" },
    });
  } catch (e) {
    console.error("build-report-html failed", e);
    return new Response(JSON.stringify({ error: String(e) }), {
      status: 500, headers: { ...cors, "Content-Type": "application/json" },
    });
  }
});
