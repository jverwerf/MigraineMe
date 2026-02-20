// supabase/functions/enqueue-login-backfill/index.ts
//
// Called on login / WHOOP connect / onboarding complete.
// Simply invokes backfill-all (fire-and-forget) which handles everything:
// WHOOP data, weather, triggers, and risk scores in one sequential run.

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function requireEnv(name: string): string {
  const v = Deno.env.get(name);
  if (!v) throw new Error(`Missing env var: ${name}`);
  return v;
}

serve(async (req) => {
  console.log("[enqueue-login-backfill] start", new Date().toISOString());

  try {
    if (req.method !== "POST" && req.method !== "GET") {
      return jsonResponse({ ok: false, error: "Method not allowed" }, 405);
    }

    const supabaseUrl = requireEnv("SUPABASE_URL");
    const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");

    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false },
      global: { headers: { "X-Client-Info": "enqueue-login-backfill" } },
    });

    // Extract user_id from JWT
    const authHeader = req.headers.get("Authorization") ?? "";
    const jwt = authHeader.replace("Bearer ", "").trim();
    if (!jwt) {
      return jsonResponse({ ok: false, error: "No auth token" }, 401);
    }

    let userId: string;
    try {
      const payload = JSON.parse(atob(jwt.split(".")[1]));
      userId = payload.sub;
      if (!userId) throw new Error("no sub");
    } catch {
      return jsonResponse({ ok: false, error: "Invalid token" }, 401);
    }

    console.log(`[enqueue-login-backfill] user=${userId}, invoking backfill-all`);

    // Fire-and-forget: invoke backfill-all with user_id
    supabase.functions.invoke("backfill-all", {
      body: { user_id: userId },
    }).catch((e: any) => {
      console.warn("[enqueue-login-backfill] backfill-all invoke failed:", e.message);
    });

    // Audit
    await supabase.from("edge_audit").insert({
      fn: "enqueue-login-backfill",
      user_id: userId,
      ok: true,
      stage: "invoked_backfill_all",
      message: `user=${userId}`,
    }).catch(() => {});

    return jsonResponse({ ok: true, userId, action: "backfill_all_invoked" });

  } catch (e: any) {
    console.error("[enqueue-login-backfill] error", e);
    return jsonResponse({ ok: false, error: String(e?.message ?? e) }, 500);
  }
});