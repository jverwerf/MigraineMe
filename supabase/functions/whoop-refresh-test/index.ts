// supabase/functions/whoop-refresh-test/index.ts
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";

function clean(v: unknown) {
  return String(v ?? "").replace(/\r?\n/g, "").replace(/\r/g, "").trim();
}

function json(body: unknown) {
  return new Response(JSON.stringify(body, null, 2), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}

serve(async (req) => {
  try {
    if (req.method !== "POST") return json({ ok: true, note: "POST only" });

    const bodyIn = await req.json().catch(() => ({}));
    const refreshToken = clean(bodyIn?.refresh_token);

    if (!refreshToken) {
      return json({ ok: false, error: "missing refresh_token in body" });
    }

    const clientId = "354e4d44-3780-4e99-a655-a306776879ee";
    const clientSecret = "ce7314f4cdfab97a16467747a174a0ba8f1a8c561bc1dcc149674171ccd85d00";

    const params = new URLSearchParams();
    params.set("grant_type", "refresh_token");
    params.set("refresh_token", refreshToken);
    params.set("client_id", clientId);
    params.set("client_secret", clientSecret);

    console.log("[whoop-refresh-test] body:", params.toString());

    const res = await fetch("https://api.prod.whoop.com/oauth/oauth2/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: params,
    });

    const text = await res.text();
    let parsed: any = null;
    try { parsed = JSON.parse(text); } catch { /* ignore */ }

    const result = {
      ok: res.ok,
      whoop_status: res.status,
      whoop: parsed ?? text,
      sent: {
        grant_type: "refresh_token",
        client_id_prefix: clientId.slice(0, 8),
        client_secret_len: clientSecret.length,
        refresh_prefix: refreshToken.slice(0, 8),
        refresh_len: refreshToken.length,
      },
    };

    console.log("[whoop-refresh-test] result:", JSON.stringify(result));

    return json(result);

  } catch (e) {
    const err = { ok: false, error: String(e) };
    console.error("[whoop-refresh-test] error:", err);
    return json(err);
  }
});