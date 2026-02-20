// supabase/functions/ai-daily-insight-dispatcher/index.ts
//
// Runs hourly via cron. For each premium user:
//   1. Resolve their timezone
//   2. Check if it's 10am local
//   3. Check if insight already exists for today
//   4. If eligible, insert a job into daily_insight_jobs
//
// The worker picks up queued jobs and generates the actual GPT insights.

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

function addDays(base: string, days: number): string {
  const d = new Date(base + "T00:00:00Z");
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().substring(0, 10);
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

function getLocalHour(timeZone: string, now = new Date()): number {
  try {
    const fmt = new Intl.DateTimeFormat("en-US", {
      timeZone,
      hour: "numeric",
      hour12: false,
    });
    const parts = fmt.formatToParts(now);
    const hourPart = parts.find((p) => p.type === "hour");
    return hourPart ? Number(hourPart.value) : -1;
  } catch {
    return -1;
  }
}

serve(async (_req) => {
  const t0 = Date.now();
  console.log("[ai-insight-dispatcher] === START ===");

  try {
    const supabaseUrl = requireEnv("SUPABASE_URL");
    const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");
    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false },
    });

    const now = new Date();
    const utcDate = now.toISOString().slice(0, 10);

    // Get all premium users (trial active OR subscription active)
    const { data: premiumRows } = await supabase
      .from("premium_status")
      .select("user_id,trial_end,rc_subscription_status")

    const nowMs = Date.now();
    const userIds = (premiumRows ?? [])
      .filter((r: any) => {
        const trialActive = r.trial_end && new Date(r.trial_end).getTime() > nowMs;
        const subActive = r.rc_subscription_status === "active";
        return trialActive || subActive;
      })
      .map((r: any) => r.user_id);

    if (userIds.length === 0) {
      console.log("[ai-insight-dispatcher] no premium users");
      return jsonResponse({ ok: true, queued: 0 });
    }

    let queued = 0;
    let skipped = 0;

    for (const userId of userIds) {
      try {
        // Resolve timezone
        let timezone = "UTC";
        for (const d of [utcDate, addDays(utcDate, -1), addDays(utcDate, 1)]) {
          const { data } = await supabase
            .from("user_location_daily")
            .select("timezone")
            .eq("user_id", userId)
            .eq("date", d)
            .not("timezone", "is", null)
            .order("updated_at", { ascending: false })
            .limit(1)
            .maybeSingle();
          if (data?.timezone) { timezone = data.timezone; break; }
        }

        // Check if it's 10am local
        const localHour = getLocalHour(timezone, now);
        if (localHour !== 10) {
          skipped++;
          continue;
        }

        const localDate = getLocalDate(timezone, now);

        // Check if insight already exists for today
        const { data: existing } = await supabase
          .from("daily_insights")
          .select("id")
          .eq("user_id", userId)
          .eq("date", localDate)
          .maybeSingle();

        if (existing) {
          skipped++;
          continue;
        }

        // Check if job already queued
        const { data: existingJob } = await supabase
          .from("daily_insight_jobs")
          .select("id")
          .eq("user_id", userId)
          .eq("local_date", localDate)
          .maybeSingle();

        if (existingJob) {
          skipped++;
          continue;
        }

        // Enqueue
        const { error: insertErr } = await supabase
          .from("daily_insight_jobs")
          .insert({
            user_id: userId,
            local_date: localDate,
            status: "queued",
          });

        if (insertErr) {
          console.warn(`[ai-insight-dispatcher] ${userId}: insert error: ${insertErr.message}`);
        } else {
          queued++;
        }
      } catch (e) {
        console.warn(`[ai-insight-dispatcher] ${userId}: error: ${(e as Error).message}`);
      }
    }

    const elapsed = Date.now() - t0;
    console.log(`[ai-insight-dispatcher] === DONE ${elapsed}ms, queued=${queued}, skipped=${skipped} ===`);
    return jsonResponse({ ok: true, queued, skipped, elapsedMs: elapsed });

  } catch (e) {
    console.error("[ai-insight-dispatcher] FATAL:", e);
    return jsonResponse({ ok: false, error: (e as Error).message }, 500);
  }
});