// FILE: supabase/functions/daily-9am-nutrition/index.ts
//
// Dispatcher that runs every 10 minutes via cron.
// For users where it's 9:00-9:10 AM local time and nutrition is enabled,
// queues a job to sync_jobs_nutrition for yesterday's date.
//
// Pattern copied from daily-9am-noise-index

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

function addDaysIsoDate(isoDate: string, deltaDays: number): string {
  const [y, m, d] = isoDate.split("-").map((x) => Number(x));
  const dt = new Date(Date.UTC(y, m - 1, d, 12, 0, 0));
  dt.setUTCDate(dt.getUTCDate() + deltaDays);
  return dt.toISOString().slice(0, 10);
}

function getLocalTimeParts(timeZone: string, now = new Date()) {
  const fmt = new Intl.DateTimeFormat("en-GB", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });

  const parts = fmt.formatToParts(now);
  const get = (type: string) => parts.find((p) => p.type === type)?.value;

  const year = get("year");
  const month = get("month");
  const day = get("day");
  const hour = get("hour");
  const minute = get("minute");

  if (!year || !month || !day || !hour || !minute) {
    throw new Error(`Failed to compute local time parts for timezone=${timeZone}`);
  }

  const localDate = `${year}-${month}-${day}`;
  const hh = Number(hour);
  const mm = Number(minute);

  return { localDate, hh, mm };
}

async function resolveTimezone(
  supabase: ReturnType<typeof createClient>,
  userId: string,
  utcIsoDate: string
): Promise<string | null> {
  const candidates = [addDaysIsoDate(utcIsoDate, -1), utcIsoDate, addDaysIsoDate(utcIsoDate, +1)];

  for (const d of candidates) {
    const { data, error } = await supabase
      .from("user_location_daily")
      .select("timezone")
      .eq("user_id", userId)
      .eq("date", d)
      .not("timezone", "is", null)
      .order("updated_at", { ascending: false })
      .limit(1)
      .maybeSingle();

    if (error) continue;
    if (data?.timezone) return data.timezone;
  }

  return null;
}

async function mapLimit<T, R>(
  items: T[],
  limit: number,
  fn: (item: T) => Promise<R>
): Promise<R[]> {
  const results: R[] = new Array(items.length);
  let nextIndex = 0;

  async function worker() {
    while (true) {
      const i = nextIndex++;
      if (i >= items.length) return;
      results[i] = await fn(items[i]);
    }
  }

  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, () => worker()));
  return results;
}

serve(async (req) => {
  console.log("[daily-9am-nutrition] start", new Date().toISOString());

  try {
    if (req.method !== "POST" && req.method !== "GET") {
      return jsonResponse({ ok: false, error: "Method not allowed" }, 405);
    }

    const supabaseUrl = requireEnv("SUPABASE_URL");
    const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");

    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false },
    });

    const nowUtc = new Date();
    const utcDate = nowUtc.toISOString().slice(0, 10);

    // Get users with nutrition enabled
    const { data: metricRows, error: metricErr } = await supabase
      .from("metric_settings")
      .select("user_id")
      .eq("metric", "nutrition")
      .eq("enabled", true);

    if (metricErr) throw new Error(`metric_settings query failed: ${metricErr.message}`);

    const userIds = [...new Set((metricRows ?? []).map((r: any) => String(r.user_id)))].filter(Boolean);

    console.log(`[daily-9am-nutrition] ${userIds.length} users with nutrition enabled`);

    const results = await mapLimit(userIds, 10, async (userId) => {
      try {
        // Resolve timezone
        const timezone = await resolveTimezone(supabase, userId, utcDate);
        if (!timezone) {
          return { userId, status: "no_timezone" };
        }

        // Check if 9:00-9:10 AM local time
        const { localDate, hh, mm } = getLocalTimeParts(timezone, nowUtc);
        if (hh !== 9 || mm > 10) {
          return { userId, status: "not_9am", localHour: hh };
        }

        // Queue job for YESTERDAY (complete day)
        const yesterday = addDaysIsoDate(localDate, -1);

        // Upsert job (idempotent - won't duplicate if already queued)
        const { error: upsertErr } = await supabase
          .from("sync_jobs_nutrition")
          .upsert(
            {
              user_id: userId,
              local_date: yesterday,
              timezone: timezone,
              status: "queued",
              attempts: 0,
              updated_at: new Date().toISOString(),
            },
            { onConflict: "user_id,local_date", ignoreDuplicates: true }
          );

        if (upsertErr) {
          return { userId, status: "queue_error", error: upsertErr.message };
        }

        return { userId, status: "queued", date: yesterday, timezone };

      } catch (e: any) {
        return { userId, status: "error", error: String(e?.message ?? e) };
      }
    });

    const summary = {
      total: results.length,
      queued: results.filter((r: any) => r.status === "queued").length,
      not9am: results.filter((r: any) => r.status === "not_9am").length,
      noTimezone: results.filter((r: any) => r.status === "no_timezone").length,
      errors: results.filter((r: any) => r.status === "error" || r.status === "queue_error").length,
    };

    console.log("[daily-9am-nutrition] done", summary);

    return jsonResponse({ ok: true, summary, results });

  } catch (e: any) {
    console.error("[daily-9am-nutrition] error", e);
    return jsonResponse({ ok: false, error: String(e?.message ?? e) }, 500);
  }
});