// supabase/functions/whoop-webhook/index.ts
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
  const supabaseUrl = requireEnv("SUPABASE_URL");
  const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");

  const supabase = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false },
  });

  async function audit(ok: boolean, stage: string, message: string | null, userId: string | null = null) {
    try {
      await supabase.from("edge_audit").insert({
        fn: "whoop-webhook",
        user_id: userId,
        ok,
        stage,
        message,
      });
    } catch (e) {
      console.log("[whoop-webhook] audit failed", e);
    }
  }

  try {
    if (req.method !== "POST") {
      return jsonResponse({ ok: true });
    }

    const body = await req.json();
    const whoopUserId = body?.user_id;
    const eventType = body?.type;

    console.log("[whoop-webhook] event", { whoopUserId, eventType });

    await audit(true, "received", `whoop_user_id=${whoopUserId} type=${eventType}`, null);

    if (!whoopUserId || !eventType) {
      return jsonResponse({ ok: true });
    }

    // Find our user
    const { data: tokenRow } = await supabase
      .from("whoop_tokens")
      .select("user_id")
      .eq("whoop_user_id", whoopUserId)
      .maybeSingle();

    if (!tokenRow) {
      await audit(false, "user_not_found", `whoop_user_id=${whoopUserId}`, null);
      return jsonResponse({ ok: true });
    }

    const userId = tokenRow.user_id;
    const today = new Date().toISOString().slice(0, 10);

    // Get timezone
    const { data: locData } = await supabase
      .from("user_location_daily")
      .select("timezone")
      .eq("user_id", userId)
      .not("timezone", "is", null)
      .order("date", { ascending: false })
      .limit(1)
      .maybeSingle();

    const timezone = locData?.timezone || "UTC";

    // Queue sync job and trigger immediate sync
    if (eventType === "sleep.updated" || eventType === "recovery.updated" || eventType === "workout.updated") {
      // Still queue the job as a fallback/record
      const { error: jobErr } = await supabase
        .from("sync_jobs")
        .upsert({
          job_type: "whoop_daily",
          user_id: userId,
          local_date: today,
          status: "queued",
          attempts: 0,
          created_by: `webhook:${eventType}`,
          timezone: timezone,
          updated_at: new Date().toISOString(),
        }, { onConflict: "job_type,user_id,local_date" });

      if (jobErr) {
        await audit(false, "job_queue_failed", jobErr.message, userId);
      } else {
        await audit(true, "job_queued", `event=${eventType}`, userId);
      }

      // Immediately trigger sync-worker for this user
      try {
        await supabase.functions.invoke('sync-worker', {
          body: { userId, force: true }
        });
        await audit(true, "sync_invoked", `event=${eventType}`, userId);
      } catch (invokeErr: any) {
        await audit(false, "sync_invoke_failed", invokeErr?.message ?? String(invokeErr), userId);
      }
    }

    return jsonResponse({ ok: true });

  } catch (e) {
    console.error("[whoop-webhook] error", e);
    return jsonResponse({ ok: true });
  }
});