// supabase/functions/risk-score-dispatcher/index.ts
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

function getLocalDate(timeZone: string, now = new Date()): string {
  const fmt = new Intl.DateTimeFormat("en-GB", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour12: false,
  });
  const parts = fmt.formatToParts(now);
  const get = (type: string) => parts.find((p) => p.type === type)?.value;
  return `${get("year")}-${get("month")}-${get("day")}`;
}

async function mapLimit<T, R>(
  items: T[],
  limit: number,
  fn: (item: T, idx: number) => Promise<R>,
): Promise<R[]> {
  const results: R[] = new Array(items.length);
  let nextIndex = 0;
  async function worker() {
    while (true) {
      const i = nextIndex++;
      if (i >= items.length) return;
      results[i] = await fn(items[i], i);
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, () => worker()));
  return results;
}

serve(async (req) => {
  console.log("[risk-score-dispatcher] start", {
    nowUtc: new Date().toISOString(),
    method: req.method,
  });

  try {
    if (req.method !== "POST" && req.method !== "GET") {
      return jsonResponse({ ok: false, error: "Method not allowed" }, 405);
    }

    const supabaseUrl = requireEnv("SUPABASE_URL");
    const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");

    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false },
      global: { headers: { "X-Client-Info": "risk-score-dispatcher" } },
    });

    const nowUtc = new Date();
    const nowIso = nowUtc.toISOString();

    // Find all users who have risk config (decay weights = they've done setup)
    const { data: userRows, error: userErr } = await supabase
      .from("risk_decay_weights")
      .select("user_id")
      .limit(10000);

    if (userErr) throw new Error(`risk_decay_weights query failed: ${userErr.message}`);

    const userIds = Array.from(new Set((userRows ?? []).map((r: any) => String(r.user_id)))).filter(Boolean);

    if (!userIds.length) {
      return jsonResponse({ ok: true, summary: { checkedUsers: 0, enqueued: 0 }, results: [] });
    }

    const perUser = await mapLimit(userIds, 12, async (userId) => {
      // Resolve timezone from most recent location
      const { data: locRow, error: locErr } = await supabase
        .from("user_location_daily")
        .select("timezone")
        .eq("user_id", userId)
        .not("timezone", "is", null)
        .order("date", { ascending: false })
        .limit(1)
        .maybeSingle();

      if (locErr) {
        return { userId, status: "resolver_error", error: locErr.message };
      }

      const tz = locRow?.timezone ?? null;
      if (!tz) {
        return { userId, status: "no_timezone" };
      }

      const localDate = getLocalDate(tz, nowUtc);

      // Upsert job — repair existing stuck jobs for today
      const { error: insErr } = await supabase
        .from("risk_score_jobs")
        .upsert(
          {
            user_id: userId,
            local_date: localDate,
            job_type: "risk_score",
            status: "queued",
            updated_at: nowIso,
            created_by: "dispatcher",
            timezone: tz,
            attempts: 0,
            locked_at: null,
            last_error: null,
          },
          { onConflict: "user_id,local_date" },
        );

      if (insErr) {
        return { userId, status: "enqueue_error", error: insErr.message, timezone: tz, localDate };
      }

      return { userId, status: "enqueued", timezone: tz, localDate };
    });

    const summary = {
      checkedUsers: userIds.length,
      enqueued: perUser.filter((r: any) => r.status === "enqueued").length,
      noTimezone: perUser.filter((r: any) => r.status === "no_timezone").length,
      errors: perUser.filter((r: any) => String(r.status).includes("error")).length,
      nowUtc: nowIso,
    };

    console.log("[risk-score-dispatcher] done", { summary });
    return jsonResponse({ ok: true, summary, results: perUser });
  } catch (e) {
    console.error("[risk-score-dispatcher] error", e);
    return jsonResponse({ ok: false, error: (e as Error).message }, 500);
  }
});