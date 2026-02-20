// FILE: supabase/functions/convert-predicted-menstruations/index.ts
// Runs HOURLY and converts predicted triggers ONLY when it's 9:00-9:10 AM local time
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

serve(async (req) => {
  try {
    console.log("[convert-predicted-menstruations] start", {
      nowUtc: new Date().toISOString(),
      method: req.method,
      url: req.url
    });

    if (req.method !== "POST" && req.method !== "GET") {
      return jsonResponse({ ok: false, error: "Method not allowed" }, 405);
    }

    const supabaseUrl = requireEnv("SUPABASE_URL");
    const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");

    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false },
      global: { headers: { "X-Client-Info": "convert-predicted-menstruations" } },
    });

    const nowUtc = new Date();
    const nowIso = nowUtc.toISOString();
    const utcDate = nowIso.slice(0, 10);

    // Get all users with menstruation enabled
    const { data: userRows, error: userErr } = await supabase
      .from("metric_settings")
      .select("user_id")
      .eq("metric", "menstruation")
      .eq("enabled", true);

    if (userErr) {
      throw new Error(`Failed to query users: ${userErr.message}`);
    }

    if (!userRows || userRows.length === 0) {
      console.log("[convert-predicted-menstruations] No users with menstruation enabled");
      return jsonResponse({
        ok: true,
        message: "No users with menstruation enabled",
        converted: 0
      });
    }

    console.log(`[convert-predicted-menstruations] Checking ${userRows.length} users`);

    const results = [];

    for (const userRow of userRows) {
      const userId = userRow.user_id;

      try {
        // Resolve user's timezone
        const timezone = await resolveUserTimezone(supabase, userId, utcDate);
        if (!timezone) {
          console.log(`[convert-predicted-menstruations] User ${userId}: No timezone, skipping`);
          continue;
        }

        // Get local time for this user RIGHT NOW
        const localTime = getLocalTimeParts(timezone, nowUtc);
        
        // CRITICAL: Only process if it's between 9:00 and 9:10 AM local time
        if (localTime.hh !== 9 || localTime.mm >= 10) {
          // Not 9:00-9:10 AM for this user, skip
          continue;
        }

        console.log(`[convert-predicted-menstruations] User ${userId}: It's 9:0${localTime.mm} AM! (${timezone})`);

        // Find predicted trigger
        const { data: predictedTriggers, error: queryError } = await supabase
          .from("triggers")
          .select("id, user_id, type, start_at, source, active")
          .eq("user_id", userId)
          .eq("type", "menstruation_predicted")
          .eq("source", "system")
          .eq("active", true);

        if (queryError) {
          console.error(`[convert-predicted-menstruations] User ${userId}: Query error:`, queryError.message);
          results.push({ userId, status: "error", reason: queryError.message });
          continue;
        }

        if (!predictedTriggers || predictedTriggers.length === 0) {
          console.log(`[convert-predicted-menstruations] User ${userId}: No predicted trigger`);
          continue;
        }

        const predicted = predictedTriggers[0];
        const predictedDate = predicted.start_at.slice(0, 10);

        // Check if predicted date is TODAY (in user's local timezone)
        if (predictedDate !== localTime.localDate) {
          console.log(`[convert-predicted-menstruations] User ${userId}: Predicted date ${predictedDate} != today ${localTime.localDate}`);
          continue;
        }

        console.log(`[convert-predicted-menstruations] User ${userId}: Predicted trigger is DUE TODAY!`);

        // Check if already converted today (avoid duplicates if function runs multiple times in the 9:00-9:10 window)
        const { data: existingToday, error: checkError } = await supabase
          .from("triggers")
          .select("id")
          .eq("user_id", userId)
          .eq("type", "menstruation")
          .eq("source", "auto")
          .gte("start_at", `${localTime.localDate}T00:00:00Z`)
          .lte("start_at", `${localTime.localDate}T23:59:59Z`);

        if (checkError) {
          console.error(`[convert-predicted-menstruations] User ${userId}: Check error:`, checkError.message);
          results.push({ userId, status: "error", reason: checkError.message });
          continue;
        }

        if (existingToday && existingToday.length > 0) {
          console.log(`[convert-predicted-menstruations] User ${userId}: Already converted today, skipping`);
          results.push({ userId, status: "skipped", reason: "Already converted today" });
          continue;
        }

        // Check if user manually logged period nearby (within 3 days)
        const threeDaysAgo = addDaysIsoDate(localTime.localDate, -3);
        const threeDaysLater = addDaysIsoDate(localTime.localDate, 3);

        const { data: manualPeriods, error: manualError } = await supabase
          .from("triggers")
          .select("id")
          .eq("user_id", userId)
          .eq("type", "menstruation")
          .in("source", ["manual", "health_connect"])
          .gte("start_at", `${threeDaysAgo}T00:00:00Z`)
          .lte("start_at", `${threeDaysLater}T23:59:59Z`);

        if (manualError) {
          console.error(`[convert-predicted-menstruations] User ${userId}: Manual check error:`, manualError.message);
          results.push({ userId, status: "error", reason: manualError.message });
          continue;
        }

        if (manualPeriods && manualPeriods.length > 0) {
          console.log(`[convert-predicted-menstruations] User ${userId}: User logged period manually nearby, skipping auto-conversion`);
          results.push({ userId, status: "skipped", reason: "Manual period logged nearby" });
          continue;
        }

        // INSERT REAL MENSTRUATION TRIGGER
        const { error: insertError } = await supabase
          .from("triggers")
          .insert({
            user_id: userId,
            type: "menstruation",
            start_at: `${localTime.localDate}T09:00:00Z`,
            source: "auto",
            active: true,
            notes: "Auto-generated from predicted menstruation"
          });

        if (insertError) {
          console.error(`[convert-predicted-menstruations] User ${userId}: Insert error:`, insertError.message);
          results.push({ userId, status: "error", reason: insertError.message });
          continue;
        }

        console.log(`[convert-predicted-menstruations] ✓ User ${userId}: Converted predicted to real!`);
        results.push({ 
          userId, 
          status: "converted", 
          timezone, 
          localDate: localTime.localDate,
          localTime: `${localTime.hh}:${String(localTime.mm).padStart(2, '0')}`
        });

        // Note: Database trigger will automatically update the predicted trigger's date

      } catch (userError) {
        console.error(`[convert-predicted-menstruations] User ${userId}: Error:`, userError);
        results.push({ userId, status: "error", reason: String(userError) });
      }
    }

    const summary = {
      ok: true,
      nowUtc: nowIso,
      utcDate,
      totalUsers: userRows.length,
      converted: results.filter(r => r.status === "converted").length,
      skipped: results.filter(r => r.status === "skipped").length,
      errors: results.filter(r => r.status === "error").length,
      results: results
    };

    console.log("[convert-predicted-menstruations] done", summary);
    return jsonResponse(summary);

  } catch (e) {
    console.error("[convert-predicted-menstruations] error", e);
    return jsonResponse({
      ok: false,
      error: String(e)
    }, 500);
  }
});