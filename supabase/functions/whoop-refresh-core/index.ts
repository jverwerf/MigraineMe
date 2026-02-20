import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

function requireEnv(name: string): string {
  const v = Deno.env.get(name);
  if (!v) throw new Error(`Missing env var: ${name}`);
  return v.trim();
}
function clean(v: unknown) {
  return String(v ?? "").replace(/\r?\n/g, "").replace(/\r/g, "").trim();
}

async function whoopRefresh(refreshToken: string) {
  const clientId = requireEnv("WHOOP_CLIENT_ID");
  const clientSecret = requireEnv("WHOOP_CLIENT_SECRET");

  const params = new URLSearchParams();
  params.set("grant_type", "refresh_token");
  params.set("client_id", clientId);
  params.set("client_secret", clientSecret);
  params.set("refresh_token", refreshToken);
  params.set("scope", "offline");
  params.set("redirect_uri", "whoop://migraineme/callback");

  const res = await fetch("https://api.prod.whoop.com/oauth/oauth2/token", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      "Accept": "application/json",
    },
    body: params,
  });

  const text = await res.text();
  if (!res.ok) throw new Error(text);
  return JSON.parse(text);
}

Deno.serve(async (req) => {
  try {
    const bodyIn = await req.json();
    const userId = clean(bodyIn.user_id);

    if (!userId) {
      return new Response(JSON.stringify({ ok: false, error: "missing user_id" }), {
        status: 400,
        headers: { "Content-Type": "application/json" },
      });
    }

    const supabase = createClient(
      requireEnv("SUPABASE_URL"),
      requireEnv("SUPABASE_SERVICE_ROLE_KEY"),
      { auth: { persistSession: false } },
    );

    // Load current token from DB (single source of truth)
    const { data: tok, error: tokErr } = await supabase
      .from("whoop_tokens")
      .select("refresh_token, token_type")
      .eq("user_id", userId)
      .maybeSingle();

    if (tokErr) throw new Error(`db_read_failed: ${tokErr.message}`);
    const refreshToken = clean(tok?.refresh_token);
    if (!refreshToken) {
      return new Response(JSON.stringify({ ok: false, error: "no_refresh_token_in_db" }), {
        status: 404,
        headers: { "Content-Type": "application/json" },
      });
    }

    // Refresh
    const json = await whoopRefresh(refreshToken);

    const newAccess = clean(json.access_token);
    const newRefresh = clean(json.refresh_token ?? refreshToken);
    const expiresIn = Number(json.expires_in ?? 3600) || 3600;
    const expiresAt = new Date(Date.now() + expiresIn * 1000).toISOString();
    const tokenType = clean(json.token_type ?? tok?.token_type ?? "bearer");

    // Write BOTH tokens back (prevents staleness)
    const { error: updErr } = await supabase
      .from("whoop_tokens")
      .update({
        access_token: newAccess,
        refresh_token: newRefresh,
        token_type: tokenType,
        expires_at: expiresAt,
        updated_at: new Date().toISOString(),
      })
      .eq("user_id", userId);

    if (updErr) throw new Error(`db_update_failed: ${updErr.message}`);

    return new Response(JSON.stringify({
      ok: true,
      user_id: userId,
      access_prefix: newAccess.slice(0, 8),
      refresh_prefix: newRefresh.slice(0, 8),
      expires_at: expiresAt,
      scope: json.scope,
    }), { headers: { "Content-Type": "application/json" } });

  } catch (e) {
    return new Response(JSON.stringify({ ok: false, error: String(e) }), {
      status: 500,
      headers: { "Content-Type": "application/json" },
    });
  }
});
