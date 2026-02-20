// supabase/functions/check-recovery-triggers/index.ts
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
    second: "2-digit",
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

async function resolveUserTimezone(
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

function mean(arr: number[]): number | null {
  if (arr.length === 0) return null;
  return arr.reduce((a, b) => a + b, 0) / arr.length;
}

function stdDev(arr: number[], avg: number): number | null {
  if (arr.length < 2) return null;
  const squaredDiffs = arr.map((x) => (x - avg) ** 2);
  return Math.sqrt(squaredDiffs.reduce((a, b) => a + b, 0) / arr.length);
}

const ABSOLUTE_LOW_THRESHOLD = 33;
const MIN_DAYS_FOR_SD = 14;

serve(async (req) => {
  console.log("[check-recovery-triggers] start", {
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
      global: { headers: { "X-Client-Info": "check-recovery-triggers" } },
    });

    const nowUtc = new Date();
    const utcDate = nowUtc.toISOString().slice(0, 10);

    // Parse optional force flag and date override from request body
    let force = false;
    let dateOverride: string | null = null;
    try {
      const body = await req.json();
      force = body?.force === true;
      dateOverride = typeof body?.date === "string" ? body.date : null;
    } catch {
      // No body or invalid JSON
    }

    // Get users with recovery triggers enabled
    const { data: triggerSettings, error: tsErr } = await supabase
      .from("trigger_settings")
      .select("user_id, trigger_type, enabled")
      .in("trigger_type", ["recovery_low", "recovery_unusually_low"])
      .eq("enabled", true);

    if (tsErr) {
      throw new Error(`trigger_settings query failed: ${tsErr.message}`);
    }

    if (!triggerSettings || triggerSettings.length === 0) {
      return jsonResponse({
        ok: true,
        message: "No users with recovery triggers enabled",
        processed: 0,
      });
    }

    // Group by user
    const userTriggerTypes = new Map<string, Set<string>>();
    for (const ts of triggerSettings) {
      if (!userTriggerTypes.has(ts.user_id)) {
        userTriggerTypes.set(ts.user_id, new Set());
      }
      userTriggerTypes.get(ts.user_id)!.add(ts.trigger_type);
    }

    const results: unknown[] = [];

    for (const [userId, enabledTypes] of userTriggerTypes) {
      const userResult: Record<string, unknown> = { userId, triggers: [] };

      // Resolve user timezone
      const timezone = await resolveUserTimezone(supabase, userId, utcDate);
      if (!timezone) {
        userResult.status = "no_timezone";
        results.push(userResult);
        continue;
      }

      userResult.timezone = timezone;

      // Get local time for this user
      const { localDate, hh, mm } = getLocalTimeParts(timezone, nowUtc);
      userResult.localTime = `${String(hh).padStart(2, "0")}:${String(mm).padStart(2, "0")}`;

      // Use date override if provided, otherwise use local date
      const targetDate = dateOverride ?? localDate;
      userResult.targetDate = targetDate;

      // Only process if 9:00-9:09 AM local time (unless forced or date override)
      if (!force && !dateOverride && (hh !== 9 || mm >= 10)) {
        userResult.status = "outside_9am_window";
        results.push(userResult);
        continue;
      }

      // Get target date's recovery score
      const { data: todayRecovery, error: recErr } = await supabase
        .from("recovery_score_daily")
        .select("date, value_pct")
        .eq("user_id", userId)
        .eq("date", targetDate)
        .maybeSingle();

      if (recErr) {
        userResult.error = `recovery fetch failed: ${recErr.message}`;
        results.push(userResult);
        continue;
      }

      if (!todayRecovery || todayRecovery.value_pct == null) {
        userResult.status = "no_recovery_data_for_date";
        results.push(userResult);
        continue;
      }

      const todayValue = todayRecovery.value_pct as number;
      userResult.todayValue = todayValue;

      // Check absolute threshold
      if (enabledTypes.has("recovery_low") && todayValue < ABSOLUTE_LOW_THRESHOLD) {
        const created = await createTriggerIfNotExists(
          supabase,
          userId,
          "recovery_low",
          targetDate,
          `Recovery ${todayValue.toFixed(0)}% - below ${ABSOLUTE_LOW_THRESHOLD}% threshold`
        );
        (userResult.triggers as unknown[]).push({
          type: "recovery_low",
          created,
          value: todayValue,
        });
      }

      // Check 2SD threshold
      if (enabledTypes.has("recovery_unusually_low")) {
        const startDate = addDaysIsoDate(targetDate, -MIN_DAYS_FOR_SD);
        const endDate = addDaysIsoDate(targetDate, -1);

        const { data: historyData, error: histErr } = await supabase
          .from("recovery_score_daily")
          .select("value_pct")
          .eq("user_id", userId)
          .gte("date", startDate)
          .lte("date", endDate);

        if (histErr) {
          userResult.sdError = `history fetch failed: ${histErr.message}`;
        } else {
          const values = (historyData ?? [])
            .map((r: { value_pct: unknown }) => r.value_pct)
            .filter((v): v is number => typeof v === "number");

          if (values.length >= MIN_DAYS_FOR_SD) {
            const avg = mean(values);
            const sd = avg != null ? stdDev(values, avg) : null;

            if (avg != null && sd != null && sd > 0) {
              const threshold = avg - 2 * sd;
              userResult.sdStats = { avg: avg.toFixed(1), sd: sd.toFixed(1), threshold: threshold.toFixed(1) };

              if (todayValue < threshold) {
                const created = await createTriggerIfNotExists(
                  supabase,
                  userId,
                  "recovery_unusually_low",
                  targetDate,
                  `Recovery ${todayValue.toFixed(0)}% - below 2SD (threshold: ${threshold.toFixed(0)}%, avg: ${avg.toFixed(0)}%)`
                );
                (userResult.triggers as unknown[]).push({
                  type: "recovery_unusually_low",
                  created,
                  value: todayValue,
                  threshold: threshold.toFixed(1),
                });
              }
            } else {
              userResult.sdStatus = "insufficient_variance";
            }
          } else {
            userResult.sdStatus = `insufficient_history (${values.length}/${MIN_DAYS_FOR_SD} days)`;
          }
        }
      }

      userResult.status = "processed";
      results.push(userResult);
    }

    const summary = {
      total: results.length,
      processed: results.filter((r: any) => r.status === "processed").length,
      outside9am: results.filter((r: any) => r.status === "outside_9am_window").length,
      noTimezone: results.filter((r: any) => r.status === "no_timezone").length,
      noData: results.filter((r: any) => r.status === "no_recovery_data_for_date").length,
    };

    return jsonResponse({
      ok: true,
      nowUtc: nowUtc.toISOString(),
      utcDate,
      forced: force,
      summary,
      results,
    });
  } catch (e) {
    console.error("[check-recovery-triggers] error", e);
    return jsonResponse({ ok: false, error: (e as Error).message }, 500);
  }
});

async function createTriggerIfNotExists(
  supabase: ReturnType<typeof createClient>,
  userId: string,
  triggerType: string,
  date: string,
  notes: string
): Promise<boolean> {
  const dayStart = `${date}T00:00:00Z`;
  const dayEnd = `${date}T23:59:59Z`;

  const { data: existing, error: checkErr } = await supabase
    .from("triggers")
    .select("id")
    .eq("user_id", userId)
    .eq("type", triggerType)
    .eq("source", "system")
    .gte("start_at", dayStart)
    .lte("start_at", dayEnd)
    .limit(1);

  if (checkErr) {
    console.error(`[check-recovery-triggers] check existing failed: ${checkErr.message}`);
    return false;
  }

  if (existing && existing.length > 0) {
    return false;
  }

  const { error: insertErr } = await supabase.from("triggers").insert({
    user_id: userId,
    type: triggerType,
    source: "system",
    start_at: `${date}T09:00:00Z`,
    notes,
    active: true,
  });

  if (insertErr) {
    console.error(`[check-recovery-triggers] insert failed: ${insertErr.message}`);
    return false;
  }

  return true;
}