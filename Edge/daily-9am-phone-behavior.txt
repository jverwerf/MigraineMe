// FILE: supabase/functions/daily-9am-phone-behavior/index.ts
//
// Cron-triggered edge function that runs every 10 minutes.
// For users where it's 9:00–9:10 AM local time and any phone behavior metric is enabled,
// aggregates yesterday's samples into daily tables:
//
//   phone_brightness_samples  → phone_brightness_daily  (AVG, MAX, COUNT)
//   phone_volume_samples      → phone_volume_daily      (AVG, COUNT)
//   phone_dark_mode_samples   → phone_dark_mode_daily   (estimated hours, dark count, total count)
//   phone_unlock_samples      → phone_unlock_daily      (MAX cumulative count)

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

/** Concurrency-limited parallel mapper */
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

const PHONE_BEHAVIOR_METRICS = [
  "phone_brightness_daily",
  "phone_volume_daily",
  "phone_dark_mode_daily",
  "phone_unlock_daily",
];

serve(async (req) => {
  console.log("[daily-9am-phone-behavior] start", new Date().toISOString());

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

    // Get users with ANY phone behavior metric enabled
    const { data: metricRows, error: metricErr } = await supabase
      .from("metric_settings")
      .select("user_id, metric")
      .in("metric", PHONE_BEHAVIOR_METRICS)
      .eq("enabled", true);

    if (metricErr) throw new Error(`metric_settings query failed: ${metricErr.message}`);

    // Group enabled metrics by user
    const userMetrics = new Map<string, Set<string>>();
    for (const row of metricRows ?? []) {
      const uid = String(row.user_id);
      if (!userMetrics.has(uid)) userMetrics.set(uid, new Set());
      userMetrics.get(uid)!.add(row.metric);
    }

    const userIds = [...userMetrics.keys()];
    console.log(`[daily-9am-phone-behavior] ${userIds.length} users with phone behavior enabled`);

    const results = await mapLimit(userIds, 10, async (userId) => {
      try {
        // Resolve timezone
        const timezone = await resolveTimezone(supabase, userId, utcDate);
        if (!timezone) {
          return { userId, status: "no_timezone" };
        }

        // Check if 9:00–9:10 AM local time
        const { localDate, hh, mm } = getLocalTimeParts(timezone, nowUtc);
        if (hh !== 9 || mm > 10) {
          return { userId, status: "not_9am", localHour: hh };
        }

        const yesterday = addDaysIsoDate(localDate, -1);
        const enabledMetrics = userMetrics.get(userId)!;
        const aggregated: string[] = [];
        const errors: string[] = [];

        // ── Brightness ───────────────────────────────────────────────
        if (enabledMetrics.has("phone_brightness_daily")) {
          try {
            const { data, error } = await supabase.rpc("aggregate_phone_brightness", {
              p_user_id: userId,
              p_date: yesterday,
              p_timezone: timezone,
            });

            if (error) throw error;
            if (data && data.length > 0 && data[0].sample_count > 0) {
              const row = data[0];
              const { error: upsertErr } = await supabase
                .from("phone_brightness_daily")
                .upsert(
                  {
                    user_id: userId,
                    date: yesterday,
                    value_mean: row.value_mean,
                    value_max: row.value_max,
                    sample_count: row.sample_count,
                    source: "computed",
                    updated_at: new Date().toISOString(),
                  },
                  { onConflict: "user_id,date" }
                );
              if (upsertErr) throw upsertErr;
              aggregated.push("brightness");
            }
          } catch (e: any) {
            errors.push(`brightness: ${e.message}`);
          }
        }

        // ── Volume ───────────────────────────────────────────────────
        if (enabledMetrics.has("phone_volume_daily")) {
          try {
            const { data, error } = await supabase.rpc("aggregate_phone_volume", {
              p_user_id: userId,
              p_date: yesterday,
              p_timezone: timezone,
            });

            if (error) throw error;
            if (data && data.length > 0 && data[0].sample_count > 0) {
              const row = data[0];
              const { error: upsertErr } = await supabase
                .from("phone_volume_daily")
                .upsert(
                  {
                    user_id: userId,
                    date: yesterday,
                    value_mean_pct: row.value_mean_pct,
                    sample_count: row.sample_count,
                    source: "computed",
                    updated_at: new Date().toISOString(),
                  },
                  { onConflict: "user_id,date" }
                );
              if (upsertErr) throw upsertErr;
              aggregated.push("volume");
            }
          } catch (e: any) {
            errors.push(`volume: ${e.message}`);
          }
        }

        // ── Dark Mode ────────────────────────────────────────────────
        if (enabledMetrics.has("phone_dark_mode_daily")) {
          try {
            const { data, error } = await supabase.rpc("aggregate_phone_dark_mode", {
              p_user_id: userId,
              p_date: yesterday,
              p_timezone: timezone,
            });

            if (error) throw error;
            if (data && data.length > 0 && data[0].sample_count > 0) {
              const row = data[0];
              const { error: upsertErr } = await supabase
                .from("phone_dark_mode_daily")
                .upsert(
                  {
                    user_id: userId,
                    date: yesterday,
                    value_hours: row.value_hours,
                    dark_samples: row.dark_samples,
                    sample_count: row.sample_count,
                    source: "computed",
                    updated_at: new Date().toISOString(),
                  },
                  { onConflict: "user_id,date" }
                );
              if (upsertErr) throw upsertErr;
              aggregated.push("dark_mode");
            }
          } catch (e: any) {
            errors.push(`dark_mode: ${e.message}`);
          }
        }

        // ── Unlocks ──────────────────────────────────────────────────
        if (enabledMetrics.has("phone_unlock_daily")) {
          try {
            const { data, error } = await supabase.rpc("aggregate_phone_unlocks", {
              p_user_id: userId,
              p_date: yesterday,
              p_timezone: timezone,
            });

            if (error) throw error;
            if (data && data.length > 0 && data[0].sample_count > 0) {
              const row = data[0];
              const { error: upsertErr } = await supabase
                .from("phone_unlock_daily")
                .upsert(
                  {
                    user_id: userId,
                    date: yesterday,
                    value_count: row.value_count,
                    source: "computed",
                    updated_at: new Date().toISOString(),
                  },
                  { onConflict: "user_id,date" }
                );
              if (upsertErr) throw upsertErr;
              aggregated.push("unlocks");
            }
          } catch (e: any) {
            errors.push(`unlocks: ${e.message}`);
          }
        }

        return {
          userId,
          status: errors.length > 0 ? "partial" : "ok",
          date: yesterday,
          aggregated,
          errors: errors.length > 0 ? errors : undefined,
        };
      } catch (e: any) {
        return { userId, status: "error", error: String(e?.message ?? e) };
      }
    });

    const summary = {
      total: results.length,
      ok: results.filter((r: any) => r.status === "ok").length,
      partial: results.filter((r: any) => r.status === "partial").length,
      not9am: results.filter((r: any) => r.status === "not_9am").length,
      noTimezone: results.filter((r: any) => r.status === "no_timezone").length,
      errors: results.filter((r: any) => r.status === "error").length,
    };

    console.log("[daily-9am-phone-behavior] done", summary);

    return jsonResponse({ ok: true, summary, results });
  } catch (e: any) {
    console.error("[daily-9am-phone-behavior] error", e);
    return jsonResponse({ ok: false, error: String(e?.message ?? e) }, 500);
  }
});