// supabase/functions/whoop-token-exchange/index.ts
//
// WHOOP OAuth Token Exchange — server-side proxy
//
// The WHOOP client_secret lives here in Supabase secrets,
// never in the Android APK. The client sends the authorization
// code + PKCE verifier, and this function adds the secret and
// exchanges with WHOOP's token endpoint.
//
// Deploy:
//   supabase functions deploy whoop-token-exchange
//   supabase secrets set WHOOP_CLIENT_ID=354e4d44-...
//   supabase secrets set WHOOP_CLIENT_SECRET=ce7314f4...
//
// The function requires a valid Supabase JWT (user must be logged in).

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

const WHOOP_CLIENT_ID = Deno.env.get("WHOOP_CLIENT_ID") ?? "";
const WHOOP_CLIENT_SECRET = Deno.env.get("WHOOP_CLIENT_SECRET") ?? "";
const WHOOP_TOKEN_URL = "https://api.prod.whoop.com/oauth/oauth2/token";
const WHOOP_REDIRECT_URI = "whoop://migraineme/callback";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

serve(async (req) => {
  if (req.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }

  // ── Verify Supabase JWT ──
  const authHeader = req.headers.get("Authorization") ?? "";
  if (!authHeader.startsWith("Bearer ")) {
    return json({ error: "Missing auth token" }, 401);
  }

  const jwt = authHeader.replace("Bearer ", "");
  const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);

  const {
    data: { user },
    error: authError,
  } = await supabase.auth.getUser(jwt);

  if (authError || !user) {
    return json({ error: "Invalid or expired token" }, 401);
  }

  // ── Parse request ──
  let code: string;
  let codeVerifier: string;

  try {
    const body = await req.json();
    code = body.code;
    codeVerifier = body.code_verifier;

    if (!code || !codeVerifier) {
      return json({ error: "Missing code or code_verifier" }, 400);
    }
  } catch {
    return json({ error: "Invalid JSON body" }, 400);
  }

  // ── Check server config ──
  if (!WHOOP_CLIENT_ID || !WHOOP_CLIENT_SECRET) {
    console.error("WHOOP_CLIENT_ID or WHOOP_CLIENT_SECRET not set in secrets");
    return json({ error: "Server misconfigured" }, 500);
  }

  // ── Exchange code with WHOOP ──
  const formBody = new URLSearchParams({
    grant_type: "authorization_code",
    client_id: WHOOP_CLIENT_ID,
    client_secret: WHOOP_CLIENT_SECRET,
    redirect_uri: WHOOP_REDIRECT_URI,
    code,
    code_verifier: codeVerifier,
  });

  try {
    const whoopRes = await fetch(WHOOP_TOKEN_URL, {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
        Accept: "application/json",
      },
      body: formBody.toString(),
    });

    const whoopBody = await whoopRes.text();

    if (!whoopRes.ok) {
      console.error(
        `WHOOP token exchange failed: ${whoopRes.status} ${whoopBody}`
      );
      return json(
        {
          error: "WHOOP token exchange failed",
          status: whoopRes.status,
          detail: whoopBody,
        },
        502
      );
    }

    // Parse and forward the token response to the client.
    // The response contains: access_token, refresh_token, token_type, expires_in
    const tokenData = JSON.parse(whoopBody);

    console.log(
      `WHOOP token exchange OK for user=${user.id}, expires_in=${tokenData.expires_in}`
    );

    // Return the token data directly — client parses with WhoopToken.fromTokenResponse()
    return json(tokenData);
  } catch (err) {
    console.error("WHOOP token exchange error:", err);
    return json({ error: "Token exchange failed", detail: String(err) }, 500);
  }
});