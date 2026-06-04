// supabase/functions/backfill-garmin/index.ts
//
// Backfills Garmin data for a single user on first connect.
// Called by backfill-all after the WHOOP step.
//
// 1. Verifies garmin_tokens exist for user
// 2. Ensures sync_jobs are enqueued (30 days)
// 3. Invokes sync-worker-garmin with force=true + user_id filter
// 4. Returns summary of what was processed
//
// Deploy: supabase functions deploy backfill-garmin --no-verify-jwt

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

function dateRangeInclusive(startIso: string, endIso: string): string[] {
  const out: string[] = [];
  let cur = startIso;
  for (let guard = 0; guard < 500; guard++) {
    out.push(cur);
    if (cur === endIso) break;
    cur = addDaysIsoDate(cur, +1);
  }
  return out;
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

const BACKFILL_DAYS = 30; // Garmin supports up to ~30 days of history

serve(async (req) => {
  const t0 = Date.now();
  console.log("[backfill-garmin] === START ===", new Date().toISOString());

  try {
    const supabaseUrl = requireEnv("SUPABASE_URL");
    const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");
    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false },
      global: { headers: { "X-Client-Info": "backfill-garmin" } },
    });

    // ── Extract user_id ──
    let userId: string | null = null;
    try {
      const body = await req.clone().json();
      userId = typeof body?.user_id === "string" ? body.user_id : null;
    } catch { /* no body */ }

    if (!userId) {
      return jsonResponse({ ok: false, error: "no user_id" }, 400);
    }

    console.log(`[backfill-garmin] user=${userId}`);

    // ── Verify Garmin token exists ──
    const { data: garminTok, error: tokErr } = await supabase
      .from("garmin_tokens")
      .select("user_id,garmin_user_id")
      .eq("user_id", userId)
      .maybeSingle();

    if (tokErr || !garminTok) {
      console.log(`[backfill-garmin] no Garmin token for user=${userId}`);
      return jsonResponse({ ok: true, status: "no_token", userId });
    }

    console.log(`[backfill-garmin] Garmin token found, garmin_user_id=${garminTok.garmin_user_id}`);

    // ── Resolve timezone ──
    const nowUtc = new Date();
    const nowIso = nowUtc.toISOString();
    const utcDate = nowIso.slice(0, 10);

    let timezone: string | null = null;
    for (const d of [addDaysIsoDate(utcDate, -1), utcDate, addDaysIsoDate(utcDate, 1)]) {
      const { data } = await supabase
        .from("user_location_daily")
        .select("timezone")
        .eq("user_id", userId).eq("date", d)
        .not("timezone", "is", null)
        .order("updated_at", { ascending: false }).limit(1).maybeSingle();
      if (data?.timezone) { timezone = data.timezone; break; }
    }
    if (!timezone) timezone = "UTC";

    const localToday = getLocalDate(timezone, nowUtc);

    // ── Ensure sync_jobs exist for backfill window ──
    const startDate = addDaysIsoDate(localToday, -(BACKFILL_DAYS - 1));
    const days = dateRangeInclusive(startDate, localToday);

    const { data: existingJobs } = await supabase
      .from("sync_jobs")
      .select("local_date")
      .eq("job_type", "garmin_daily")
      .eq("user_id", userId)
      .in("local_date", days);

    const existingSet = new Set((existingJobs ?? []).map((r: any) => String(r.local_date)));

    const newJobs = days
      .filter((d) => !existingSet.has(d))
      .map((d) => ({
        job_type: "garmin_daily",
        user_id: userId,
        local_date: d,
        status: "queued",
        updated_at: nowIso,
        created_by: "backfill-garmin",
        timezone,
        city_id: null,
      }));

    let enqueuedCount = 0;
    if (newJobs.length > 0) {
      const { error: jobErr } = await supabase.from("sync_jobs").upsert(newJobs, {
        onConflict: "job_type,user_id,local_date",
        ignoreDuplicates: true,
      });
      if (jobErr) {
        console.error(`[backfill-garmin] sync_jobs upsert error: ${jobErr.message}`);
      } else {
        enqueuedCount = newJobs.length;
      }
    }

    console.log(`[backfill-garmin] sync_jobs: ${existingSet.size} existing, ${enqueuedCount} new, ${days.length} total days`);

    // ── Invoke sync-worker-garmin to process the jobs ──
    console.log(`[backfill-garmin] invoking sync-worker-garmin (force=true, user_id=${userId})`);

    const { error: invokeErr } = await supabase.functions.invoke("sync-worker-garmin", {
      body: { force: true, user_id: userId },
    });

    if (invokeErr) {
      console.error(`[backfill-garmin] sync-worker-garmin invoke error: ${invokeErr.message}`);
    } else {
      console.log(`[backfill-garmin] sync-worker-garmin completed`);
    }

    // ── Audit ──
    const elapsed = Date.now() - t0;
    const summary = {
      userId, timezone, days: days.length,
      existingJobs: existingSet.size, newJobsEnqueued: enqueuedCount,
      workerInvoked: !invokeErr, elapsedMs: elapsed,
    };

    try {
      await supabase.from("edge_audit").insert({
        fn: "backfill-garmin", user_id: userId, ok: true,
        stage: "complete", message: JSON.stringify(summary).slice(0, 500),
      });
    } catch (_) { /* best-effort */ }

    console.log(`[backfill-garmin] === DONE in ${elapsed}ms ===`, JSON.stringify(summary));
    return jsonResponse({ ok: true, summary });

  } catch (e) {
    console.error("[backfill-garmin] FATAL:", e);
    return jsonResponse({ ok: false, error: (e as Error).message }, 500);
  }
});