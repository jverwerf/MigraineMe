// supabase/functions/sync-worker-polar/index.ts
//
// Standalone sync worker for Polar data.
// Processes "polar_daily" jobs from sync_jobs table.
// Fetches from Polar AccessLink API v3 and writes to metric tables.
//
// Architecture: same as sync-worker-oura
//   - 9am retry window with MAX_PICK_ATTEMPTS
//   - tryMarkMetricRan dedup via backend_daily_runs
//   - Fire-and-forget from dispatcher or webhook
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";
import { hrDaySamples, hrSpacingOpts, hrThresholdSummary, hrWithTimeout } from "../_shared/hrThreshold.ts";
// ══════════════════════════════════════════════════════════════════════
// Helpers
// ══════════════════════════════════════════════════════════════════════
function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json"
    }
  });
}
function requireEnv(name) {
  const v = Deno.env.get(name);
  if (!v) throw new Error(`Missing env var: ${name}`);
  return v;
}
function numOrNull(v) {
  const n = typeof v === "number" ? v : typeof v === "string" ? Number(v) : NaN;
  return Number.isFinite(n) ? n : null;
}
function intOrNull(v) {
  const n = numOrNull(v);
  if (n == null) return null;
  return Math.trunc(n);
}
// ══════════════════════════════════════════════════════════════════════
// Polar API helpers
// ══════════════════════════════════════════════════════════════════════
const POLAR_BASE = "https://www.polaraccesslink.com/v3";
async function polarFetch(path, accessToken) {
  const url = POLAR_BASE + path;
  const res = await fetch(url, {
    headers: {
      Authorization: `Bearer ${accessToken}`,
      Accept: "application/json"
    }
  });
  const text = await res.text();
  if (res.status === 204) return null; // No content
  if (!res.ok) {
    throw new Error(`Polar ${path} failed (${res.status}): ${text.slice(0, 500)}`);
  }
  try {
    return JSON.parse(text);
  } catch  {
    throw new Error(`Polar ${path} response not JSON: ${text.slice(0, 200)}`);
  }
}
// ══════════════════════════════════════════════════════════════════════
// Retry window config
// ══════════════════════════════════════════════════════════════════════
const MAX_PICK_ATTEMPTS = 10;
const RETRY_START_HOUR = 7;
const RETRY_END_HOUR = 11;
const RETRY_END_MINUTE = 30;
function isInsideRetryWindow(localTime) {
  const [hStr, mStr] = localTime.split(":");
  const h = Number(hStr);
  const m = Number(mStr);
  const totalMinutes = h * 60 + m;
  const startMinutes = RETRY_START_HOUR * 60;
  const endMinutes = RETRY_END_HOUR * 60 + RETRY_END_MINUTE;
  return totalMinutes >= startMinutes && totalMinutes <= endMinutes;
}
function getLocalTimeParts(timeZone, now = new Date()) {
  const fmt = new Intl.DateTimeFormat("en-GB", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  });
  const parts = fmt.formatToParts(now);
  const get = (type)=>parts.find((p)=>p.type === type)?.value;
  return {
    localDate: `${get("year")}-${get("month")}-${get("day")}`,
    localTime: `${get("hour")}:${get("minute")}`
  };
}
// Convert seconds to HH.MM decimal (for sleep stages)
function toHM(seconds) {
  if (seconds == null || !Number.isFinite(seconds)) return null;
  const totalMinutes = seconds / 60;
  const hours = Math.floor(totalMinutes / 60);
  const minutes = Math.round(totalMinutes % 60);
  return hours + minutes / 100;
}
// Parse ISO 8601 duration like "PT7H30M" or "PT45M10S" to seconds
function parsePTDuration(dur) {
  if (typeof dur !== "string") return null;
  const match = dur.match(/PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?/);
  if (!match) return null;
  const h = Number(match[1] || 0);
  const m = Number(match[2] || 0);
  const s = Number(match[3] || 0);
  return h * 3600 + m * 60 + s;
}
// Convert seconds to decimal hours (e.g. 27000 → 7.5)
function secondsToDecimalHours(sec) {
  if (sec == null || !Number.isFinite(sec)) return null;
  return Math.round(sec / 3600 * 100) / 100;
}
async function mapLimit(items, limit, fn) {
  const results = new Array(items.length);
  let nextIndex = 0;
  async function worker() {
    while(true){
      const i = nextIndex++;
      if (i >= items.length) return;
      results[i] = await fn(items[i]);
    }
  }
  const workers = Array.from({
    length: Math.min(limit, items.length)
  }, ()=>worker());
  await Promise.all(workers);
  return results;
}
// ══════════════════════════════════════════════════════════════════════
// MAIN
// ══════════════════════════════════════════════════════════════════════
serve(async (req)=>{
  console.log("[sync-worker-polar] start", new Date().toISOString());
  try {
    const supabaseUrl = requireEnv("SUPABASE_URL");
    const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");
    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: {
        persistSession: false
      },
      global: {
        headers: {
          "X-Client-Info": "sync-worker-polar"
        }
      }
    });
    const body = await req.json().catch(()=>({}));
    const force = body?.force === true;
    const forceUserId = body?.user_id ?? null;
    const nowUtc = new Date();
    const nowIso = nowUtc.toISOString();
    // ── Pick jobs ──────────────────────────────────────────────────
    let query = supabase.from("sync_jobs").select("*").eq("job_type", "polar_daily").in("status", [
      "queued",
      "error"
    ]).lt("attempts", MAX_PICK_ATTEMPTS).order("local_date", {
      ascending: true
    }).limit(50);
    if (forceUserId) {
      query = query.eq("user_id", forceUserId);
    }
    const { data: jobs, error: pickErr } = await query;
    if (pickErr) throw new Error(`sync_jobs pick failed: ${pickErr.message}`);
    const picked = jobs ?? [];
    if (!picked.length) {
      return jsonResponse({
        ok: true,
        summary: {
          picked: 0
        }
      });
    }
    // ── Helper functions ──────────────────────────────────────────
    async function markJobDone(jobId, note) {
      await supabase.from("sync_jobs").update({
        status: "done",
        updated_at: nowIso,
        last_error: note
      }).eq("id", jobId);
    }
    async function markJobError(jobId, errorMsg) {
      await supabase.from("sync_jobs").update({
        status: "error",
        last_error: errorMsg,
        updated_at: nowIso
      }).eq("id", jobId);
    }
    async function incrementAttempt(jobId, currentAttempts) {
      await supabase.from("sync_jobs").update({
        attempts: currentAttempts + 1,
        updated_at: nowIso
      }).eq("id", jobId);
    }
    // Dedup: mark metric as ran, returns true if we should proceed
    async function tryMarkMetricRan(sb, userId, localDate, metric, source) {
      const { data: existing } = await sb.from("backend_daily_runs").select("user_id").eq("user_id", userId).eq("local_date", localDate).eq("metric", metric).eq("source", source).maybeSingle();
      if (existing) return false; // Already written
      const { error } = await sb.from("backend_daily_runs").insert({
        user_id: userId,
        local_date: localDate,
        metric,
        source,
        ran_at: nowIso
      });
      // Unique constraint violation = already ran
      if (error && error.code === "23505") return false;
      if (error) console.warn(`[sync-worker-polar] backend_daily_runs insert warn: ${error.message}`);
      return true;
    }
    // Upsert with standard onConflict
    async function upsertDailyRow(sb, table, row) {
      const { error } = await sb.from(table).upsert(row, {
        onConflict: "user_id,date,source"
      });
      if (error) throw new Error(`Upsert to ${table} failed: ${error.message}`);
    }
    // ── Process each job ──────────────────────────────────────────
    async function processJob(job) {
      const userId = job.user_id;
      const jobLocalDate = job.local_date;
      const tz = job.timezone || "UTC";
      const currentAttempt = (job.attempts ?? 0) + 1;
      await incrementAttempt(job.id, job.attempts ?? 0);
      try {
        // Check timezone + retry window
        const { localDate: todayLocal, localTime } = getLocalTimeParts(tz, nowUtc);
        const isTodayJob = jobLocalDate === todayLocal;
        if (isTodayJob && !force && !isInsideRetryWindow(localTime)) {
          return {
            jobId: job.id,
            userId,
            localDate: jobLocalDate,
            status: "outside_retry_window",
            localTime
          };
        }
        // Get Polar token
        const { data: tokenRow, error: tokenErr } = await supabase.from("polar_tokens").select("access_token,polar_user_id").eq("user_id", userId).maybeSingle();
        if (tokenErr || !tokenRow?.access_token) {
          await markJobError(job.id, "polar_not_connected");
          return {
            jobId: job.id,
            userId,
            status: "polar_not_connected"
          };
        }
        const accessToken = tokenRow.access_token;
        const polarUserId = tokenRow.polar_user_id;
        // Get user's enabled Polar metrics
        const { data: metricRows } = await supabase.from("metric_settings").select("metric,enabled,preferred_source").eq("user_id", userId);
        const enabledMap = new Map();
        (metricRows ?? []).forEach((r)=>{
          const isPolar = (r.preferred_source ?? "").toLowerCase() === "polar";
          enabledMap.set(r.metric, r.enabled === true && isPolar);
        });
        function polarEnabled(metric) {
          return enabledMap.get(metric) === true;
        }
        const metricResults = [];
        let anyData = false;
        const SOURCE = "polar";
        // ─────────────────────────────────────────────────────────
        // 1. SLEEP
        //    GET /v3/users/{user-id}/sleep/{date}
        // ─────────────────────────────────────────────────────────
        try {
          if (polarUserId) {
            const sleepData = await polarFetch(`/users/sleep/${jobLocalDate}`, accessToken);
            if (sleepData) {
              anyData = true;
              // Sleep score
              const sleepScore = numOrNull(sleepData.sleep_score);
              if (polarEnabled("sleep_score_daily") && sleepScore != null) {
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "sleep_score_daily", SOURCE);
                if (proceed) {
                  await upsertDailyRow(supabase, "sleep_score_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_pct: sleepScore,
                    source: SOURCE,
                    source_measure_id: null,
                    created_at: nowIso
                  });
                }
                metricResults.push({
                  metric: "sleep_score_daily",
                  status: proceed ? "written" : "already_done"
                });
              }
              // Sleep duration (sum of stages in seconds → decimal hours)
              // Polar returns light_sleep/deep_sleep/rem_sleep as raw seconds, not PT duration
              const totalSleepSec = numOrNull((numOrNull(sleepData.light_sleep) ?? 0) + (numOrNull(sleepData.deep_sleep) ?? 0) + (numOrNull(sleepData.rem_sleep) ?? 0)) || parsePTDuration(sleepData.duration);
              if (polarEnabled("sleep_duration_daily") && totalSleepSec != null) {
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "sleep_duration_daily", SOURCE);
                if (proceed) {
                  await upsertDailyRow(supabase, "sleep_duration_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_hours: secondsToDecimalHours(totalSleepSec),
                    source: SOURCE,
                    source_measure_id: null,
                    created_at: nowIso
                  });
                }
                metricResults.push({
                  metric: "sleep_duration_daily",
                  status: proceed ? "written" : "already_done"
                });
              }
              // Sleep efficiency (continuity 1.0-5.0 → percentage)
              const continuity = numOrNull(sleepData.continuity);
              if (polarEnabled("sleep_efficiency_daily") && continuity != null) {
                const efficiencyPct = Math.round((continuity - 1) / 4 * 100);
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "sleep_efficiency_daily", SOURCE);
                if (proceed) {
                  await upsertDailyRow(supabase, "sleep_efficiency_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_pct: efficiencyPct,
                    source: SOURCE,
                    source_measure_id: null,
                    created_at: nowIso
                  });
                }
                metricResults.push({
                  metric: "sleep_efficiency_daily",
                  status: proceed ? "written" : "already_done"
                });
              }
              // Sleep stages (deep, light, rem — Polar returns raw seconds, fallback to PT parse)
              const deepSec = numOrNull(sleepData.deep_sleep) ?? parsePTDuration(sleepData.deep_sleep);
              const lightSec = numOrNull(sleepData.light_sleep) ?? parsePTDuration(sleepData.light_sleep);
              const remSec = numOrNull(sleepData.rem_sleep) ?? parsePTDuration(sleepData.rem_sleep);
              if (polarEnabled("sleep_stages_daily") && (deepSec != null || lightSec != null || remSec != null)) {
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "sleep_stages_daily", SOURCE);
                if (proceed) {
                  await upsertDailyRow(supabase, "sleep_stages_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_sws_hm: toHM(deepSec),
                    value_rem_hm: toHM(remSec),
                    value_light_hm: toHM(lightSec),
                    source: SOURCE,
                    source_measure_id: null,
                    created_at: nowIso
                  });
                }
                metricResults.push({
                  metric: "sleep_stages_daily",
                  status: proceed ? "written" : "already_done"
                });
              }
              // Sleep disturbances (total_interruption_duration — Polar returns raw seconds)
              const interruptionSec = numOrNull(sleepData.total_interruption_duration) ?? parsePTDuration(sleepData.total_interruption_duration);
              if (polarEnabled("sleep_disturbances_daily") && interruptionSec != null) {
                const interruptionMinutes = Math.round(interruptionSec / 60);
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "sleep_disturbances_daily", SOURCE);
                if (proceed) {
                  await upsertDailyRow(supabase, "sleep_disturbances_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_count: interruptionMinutes,
                    source: SOURCE,
                    source_measure_id: null,
                    created_at: nowIso
                  });
                }
                metricResults.push({
                  metric: "sleep_disturbances_daily",
                  status: proceed ? "written" : "already_done"
                });
              }
              // Fell asleep / woke up times
              const sleepStart = sleepData.sleep_start_time;
              if (polarEnabled("fell_asleep_time_daily") && sleepStart) {
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "fell_asleep_time_daily", SOURCE);
                if (proceed) {
                  await upsertDailyRow(supabase, "fell_asleep_time_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_at: sleepStart,
                    source: SOURCE,
                    source_measure_id: null,
                    created_at: nowIso
                  });
                }
                metricResults.push({
                  metric: "fell_asleep_time_daily",
                  status: proceed ? "written" : "already_done"
                });
              }
              const sleepEnd = sleepData.sleep_end_time;
              if (polarEnabled("woke_up_time_daily") && sleepEnd) {
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "woke_up_time_daily", SOURCE);
                if (proceed) {
                  await upsertDailyRow(supabase, "woke_up_time_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_at: sleepEnd,
                    source: SOURCE,
                    source_measure_id: null,
                    created_at: nowIso
                  });
                }
                metricResults.push({
                  metric: "woke_up_time_daily",
                  status: proceed ? "written" : "already_done"
                });
              }
            }
          }
        } catch (e) {
          metricResults.push({
            endpoint: "sleep",
            status: "error",
            error: e.message
          });
        }
        // ─────────────────────────────────────────────────────────
        // 2. NIGHTLY RECHARGE
        //    The per-date endpoint 404s for any past day. The range endpoint returns
        //    an array of available recharges, including history — use it so backfill works.
        //    GET /v3/users/nightly-recharge?from={date}&to={date}
        // ─────────────────────────────────────────────────────────
        try {
          if (polarUserId) {
            const rechargeList = await polarFetch(`/users/nightly-recharge?from=${jobLocalDate}&to=${jobLocalDate}`, accessToken);
            const rechargeData = rechargeList?.recharges?.[0] ?? (Array.isArray(rechargeList) ? rechargeList[0] : null);
            if (rechargeData) {
              anyData = true;
              // Recovery score: map Polar's nightly_recharge_status (1–5) to 20–100%.
              // ans_charge is a ~-10..+10 z-score, not a percentage — clamping it directly produced meaningless 0–10 values.
              const rechargeStatus = numOrNull(rechargeData.nightly_recharge_status);
              if (polarEnabled("recovery_score_daily") && rechargeStatus != null) {
                const recoveryScore = Math.max(0, Math.min(100, Math.round(rechargeStatus * 20)));
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "recovery_score_daily", SOURCE);
                if (proceed) {
                  await upsertDailyRow(supabase, "recovery_score_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_pct: recoveryScore,
                    source: SOURCE,
                    source_measure_id: null,
                    created_at: nowIso
                  });
                }
                metricResults.push({
                  metric: "recovery_score_daily",
                  status: proceed ? "written" : "already_done"
                });
              }
              // Resting HR (heart_rate_avg during sleep)
              const hrAvg = numOrNull(rechargeData.heart_rate_avg);
              if (polarEnabled("resting_hr_daily") && hrAvg != null) {
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "resting_hr_daily", SOURCE);
                if (proceed) {
                  await upsertDailyRow(supabase, "resting_hr_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_bpm: Math.round(hrAvg),
                    source: SOURCE,
                    source_measure_id: null,
                    created_at: nowIso
                  });
                }
                metricResults.push({
                  metric: "resting_hr_daily",
                  status: proceed ? "written" : "already_done"
                });
              }
              // HRV in ms — Polar's field is heart_rate_variability_avg, not hrv_avg.
              const hrvAvg = numOrNull(rechargeData.heart_rate_variability_avg);
              if (polarEnabled("hrv_daily") && hrvAvg != null) {
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "hrv_daily", SOURCE);
                if (proceed) {
                  await upsertDailyRow(supabase, "hrv_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_rmssd_ms: Math.round(hrvAvg),
                    source: SOURCE,
                    source_measure_id: null,
                    created_at: nowIso
                  });
                }
                metricResults.push({
                  metric: "hrv_daily",
                  status: proceed ? "written" : "already_done"
                });
              }
              // Respiratory rate (breathing_rate_avg in rpm)
              const breathingRate = numOrNull(rechargeData.breathing_rate_avg);
              if (polarEnabled("respiratory_rate_daily") && breathingRate != null) {
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "respiratory_rate_daily", SOURCE);
                if (proceed) {
                  await upsertDailyRow(supabase, "respiratory_rate_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_brpm: Math.round(breathingRate * 10) / 10,
                    source: SOURCE,
                    source_measure_id: null,
                    created_at: nowIso
                  });
                }
                metricResults.push({
                  metric: "respiratory_rate_daily",
                  status: proceed ? "written" : "already_done"
                });
              }
            }
          }
        } catch (e) {
          metricResults.push({
            endpoint: "nightly-recharge",
            status: "error",
            error: e.message
          });
        }
        // ─────────────────────────────────────────────────────────
        // 3. DAILY ACTIVITY (modern, non-deprecated)
        //    GET /v3/users/activities/{date}
        //    Returns: steps, active_calories, calories, active_duration, daily_activity, distance_from_steps
        // ─────────────────────────────────────────────────────────
        try {
          const activityData = await polarFetch(`/users/activities/${jobLocalDate}`, accessToken);
          if (activityData) {
            anyData = true;
            const steps = intOrNull(activityData.steps);
            if (polarEnabled("steps_daily") && steps != null && steps > 0) {
              const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "steps_daily", SOURCE);
              if (proceed) {
                await supabase.from("steps_daily").delete().eq("user_id", userId).eq("date", jobLocalDate).eq("source", SOURCE);
                const { error: stepsErr } = await supabase.from("steps_daily").insert({
                  user_id: userId,
                  date: jobLocalDate,
                  value_count: steps,
                  source: SOURCE,
                  source_measure_id: null,
                  created_at: nowIso
                });
                if (stepsErr) throw new Error(`Insert to steps_daily failed: ${stepsErr.message}`);
              }
              metricResults.push({ metric: "steps_daily", status: proceed ? "written" : "already_done" });
            }
            // Strain fallback from active_calories (used if cardio-load strain not available below)
            const activeCals = numOrNull(activityData.active_calories);
            if (polarEnabled("strain_daily") && activeCals != null && activeCals > 0) {
              const activeKj = Math.round(activeCals * 4.184);
              const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "strain_daily", SOURCE);
              if (proceed) {
                await upsertDailyRow(supabase, "strain_daily", {
                  user_id: userId,
                  date: jobLocalDate,
                  value_kilojoule: activeKj,
                  avg_heart_rate: null,
                  max_heart_rate: null,
                  source: SOURCE,
                  source_measure_id: null,
                  created_at: nowIso
                });
              }
              metricResults.push({ metric: "strain_daily", status: proceed ? "written" : "already_done" });
            }
          }
        } catch (e) {
          if (!e.message.includes("404") && !e.message.includes("204")) {
            metricResults.push({ endpoint: "activities", status: "error", error: e.message });
          }
        }
        // ─────────────────────────────────────────────────────────
        // 4. EXERCISES → HR zones (transaction-based, only option)
        //    POST /v3/users/{user-id}/exercise-transactions
        //    GET  /v3/users/{user-id}/exercise-transactions/{txn}
        //    GET  /v3/users/{user-id}/exercise-transactions/{txn}/exercises/{id}
        //    GET  .../exercises/{id}/heart-rate-zones
        // Commit is intentionally skipped so subsequent runs can re-read.
        // ─────────────────────────────────────────────────────────
        try {
          if (polarUserId && polarEnabled("time_in_high_hr_zones_daily")) {
            const txnUrl = POLAR_BASE + `/users/${polarUserId}/exercise-transactions`;
            const txnRes = await fetch(txnUrl, {
              method: "POST",
              headers: { Authorization: `Bearer ${accessToken}`, Accept: "application/json" }
            });
            if (txnRes.status === 201) {
              const txn = await txnRes.json();
              const txnId = txn["transaction-id"];
              const listData = await polarFetch(`/users/${polarUserId}/exercise-transactions/${txnId}`, accessToken);
              const urls = Array.isArray(listData?.exercises) ? listData.exercises : [];
              let z1Sec = 0, z2Sec = 0, z3Sec = 0, z4Sec = 0, z5Sec = 0;
              let matched = false;
              for (const exUrl of urls) {
                const path = String(exUrl).replace(POLAR_BASE, "");
                const ex = await polarFetch(path, accessToken);
                const startTime = ex?.["start-time"] ?? ex?.start_time ?? "";
                if (!startTime.startsWith(jobLocalDate)) continue;
                matched = true;

                const exId = ex?.id ?? ex?.["transaction-id"] ?? null;
                const activityType =
                  (typeof ex?.["detailed-sport-info"] === "string" && ex["detailed-sport-info"]) ||
                  (typeof ex?.detailed_sport_info === "string" && ex.detailed_sport_info) ||
                  (typeof ex?.sport === "string" && ex.sport) ||
                  "workout";
                const exDurSec = parsePTDuration(ex?.duration) ?? 0;
                const exEndAt = exDurSec > 0 ? new Date(new Date(startTime).getTime() + exDurSec * 1000).toISOString() : null;
                if (exId) {
                  await supabase.from("activities").upsert({
                    user_id: userId,
                    type: String(activityType),
                    source: SOURCE,
                    source_measure_id: String(exId),
                    start_at: startTime,
                    end_at: exEndAt,
                    duration_minutes: exDurSec > 0 ? Math.round(exDurSec / 60) : null,
                  }, { onConflict: "user_id,source,source_measure_id" });
                }

                const zones = await polarFetch(path + "/heart-rate-zones", accessToken);
                const zoneList = zones?.zones ?? zones?.["heart-rate-zones"] ?? [];
                for (const z of zoneList) {
                  const durSec = parsePTDuration(z["in-zone"] ?? z.in_zone ?? z.duration) ?? 0;
                  const idx = z.index ?? z.zone ?? 0;
                  if (idx === 1) z1Sec += durSec;
                  else if (idx === 2) z2Sec += durSec;
                  else if (idx === 3) z3Sec += durSec;
                  else if (idx === 4) z4Sec += durSec;
                  else if (idx >= 5) z5Sec += durSec;
                }
              }
              if (matched) {
                anyData = true;
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "time_in_high_hr_zones_daily", SOURCE);
                if (proceed) {
                  await upsertDailyRow(supabase, "time_in_high_hr_zones_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_z1_sec: z1Sec,
                    value_z2_sec: z2Sec,
                    value_z3_sec: z3Sec,
                    value_z4_sec: z4Sec,
                    value_z5_sec: z5Sec,
                    source: SOURCE,
                    source_measure_id: null,
                    created_at: nowIso
                  });
                }
                metricResults.push({ metric: "time_in_high_hr_zones_daily", status: proceed ? "written" : "already_done" });
              }
            }
            // 204 = no new exercises; nothing to do
          }
        } catch (e) {
          metricResults.push({ endpoint: "exercises", status: "error", error: e.message });
        }

        // ─────────────────────────────────────────────────────────
        // 4b. CARDIO LOAD (modern) → strain override when available
        //     GET /v3/users/cardio-load/{date}
        //     cardio_load_status = "LOAD_STATUS_NOT_AVAILABLE" on devices without Training Load Pro.
        // ─────────────────────────────────────────────────────────
        try {
          if (polarEnabled("strain_daily")) {
            const cardioLoadArr = await polarFetch(`/users/cardio-load/${jobLocalDate}`, accessToken);
            const entry = Array.isArray(cardioLoadArr) ? cardioLoadArr[0] : cardioLoadArr;
            const strainRaw = numOrNull(entry?.cardio_load);
            const status = String(entry?.cardio_load_status ?? "");
            if (strainRaw != null && strainRaw > 0 && !status.includes("NOT_AVAILABLE")) {
              anyData = true;
              const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "strain_daily", SOURCE);
              if (proceed) {
                await upsertDailyRow(supabase, "strain_daily", {
                  user_id: userId,
                  date: jobLocalDate,
                  value_kilojoule: Math.round(strainRaw),
                  avg_heart_rate: null,
                  max_heart_rate: null,
                  source: SOURCE,
                  source_measure_id: null,
                  created_at: nowIso
                });
              }
              metricResults.push({ metric: "strain_daily", status: proceed ? "written" : "already_done", source_detail: "cardio-load" });
            }
          }
        } catch (e) {
          if (!e.message.includes("404")) {
            metricResults.push({ endpoint: "cardio-load", status: "error", error: e.message });
          }
        }
        // ─────────────────────────────────────────────────────────
        // 5. BIOSENSING: Skin temperature (modern, Elixir devices only)
        //    GET /v3/users/biosensing/skintemperature?from={date}&to={date}
        // ─────────────────────────────────────────────────────────
        try {
          if (polarEnabled("skin_temp_daily")) {
            const skinTempArr = await polarFetch(`/users/biosensing/skintemperature?from=${jobLocalDate}&to=${jobLocalDate}`, accessToken);
            const skinTempData = Array.isArray(skinTempArr) ? skinTempArr[0] : skinTempArr;
            if (skinTempData) {
              // Only write Polar's API-provided deviation. Skip if Polar returns only
              // absolute readings (older devices) — would otherwise corrupt the dataset
              // by writing ~37 into a deviation column.
              const tempValue = numOrNull(skinTempData.temperature_deviation);
              if (tempValue != null) {
                anyData = true;
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "skin_temp_daily", SOURCE);
                if (proceed) {
                  await upsertDailyRow(supabase, "skin_temp_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_celsius: tempValue,
                    source: SOURCE,
                    source_measure_id: null,
                    created_at: nowIso
                  });
                }
                metricResults.push({
                  metric: "skin_temp_daily",
                  status: proceed ? "written" : "already_done"
                });
              }
            }
          }
        } catch (e) {
          // Elixir endpoints may 404 on unsupported devices — not an error
          if (!e.message.includes("404")) {
            metricResults.push({
              endpoint: "skin-temp",
              status: "error",
              error: e.message
            });
          }
        }
        // ─────────────────────────────────────────────────────────
        // 6. BIOSENSING: SpO2 (modern, Elixir devices only)
        //    GET /v3/users/biosensing/spo2?from={date}&to={date}
        // ─────────────────────────────────────────────────────────
        try {
          if (polarEnabled("spo2_daily")) {
            const spo2Arr = await polarFetch(`/users/biosensing/spo2?from=${jobLocalDate}&to=${jobLocalDate}`, accessToken);
            const spo2Data = Array.isArray(spo2Arr) ? spo2Arr[0] : spo2Arr;
            if (spo2Data) {
              const spo2Value = numOrNull(spo2Data.spo2_value) ?? numOrNull(spo2Data.value);
              if (spo2Value != null) {
                anyData = true;
                const proceed = await tryMarkMetricRan(supabase, userId, jobLocalDate, "spo2_daily", SOURCE);
                if (proceed) {
                  await upsertDailyRow(supabase, "spo2_daily", {
                    user_id: userId,
                    date: jobLocalDate,
                    value_pct: Math.round(spo2Value),
                    source: SOURCE,
                    source_measure_id: null,
                    created_at: nowIso
                  });
                }
                metricResults.push({
                  metric: "spo2_daily",
                  status: proceed ? "written" : "already_done"
                });
              }
            }
          }
        } catch (e) {
          // SpO2 may 404 on unsupported devices
          if (!e.message.includes("404")) {
            metricResults.push({
              endpoint: "spo2",
              status: "error",
              error: e.message
            });
          }
        }
        // ─────────────────────────────────────────────────────────
        // 7. HEART-RATE GOALS → hr_threshold_daily
        //    Only for users with an active heart-rate goal (practitioner_goals,
        //    kind hr_threshold); everyone else costs one cheap query.
        //    Reduced to "minutes at or above N bpm" and "longest stretch" for
        //    the job's day and the day before, from two sources:
        //      GET /v3/exercises, then /v3/exercises/{id}?samples=true
        //        sample type 0 = heart rate at the recording rate (1-5 s) → exact
        //      GET /v3/users/continuous-heart-rate/{date}
        //        all-day heart rate every ~5 minutes → estimate
        //    Per threshold the exercise figure wins when it shows any time at
        //    or above the threshold; otherwise the all-day estimate is used.
        //    Strictly optional: any failure is logged and the job carries on.
        // ─────────────────────────────────────────────────────────
        try {
          const { data: hrGoalRows } = await supabase.from("practitioner_goals")
            .select("threshold_bpm").eq("user_id", userId).eq("kind", "hr_threshold").eq("status", "active");
          const hrThresholds = Array.from(new Set(
            ((hrGoalRows ?? []) as any[]).map((r) => Number(r.threshold_bpm)).filter((n) => n > 0),
          ));
          if (hrThresholds.length) {
            const hrDeadline = Date.now() + 25_000;
            // Polar reports wall-clock times in the user's own zone, so both
            // sources are placed on a zone-less day (local midnight = 0).
            const hrJobDayS = Date.parse(jobLocalDate + "T00:00:00Z") / 1000;
            const hrPrevDate = new Date((hrJobDayS - 86400) * 1000).toISOString().slice(0, 10);
            const hrWallS = (v: unknown) => Date.parse(String(v ?? "").slice(0, 19) + "Z") / 1000;
            const exPoints: { t: number; bpm: number }[] = [];
            try {
              const exListRaw = await hrWithTimeout(polarFetch(`/exercises`, accessToken), 10_000, "Polar /exercises");
              const exList: any[] = Array.isArray(exListRaw) ? exListRaw : (exListRaw?.exercises ?? []);
              const wanted = exList.filter((ex: any) => {
                const stS = hrWallS(ex?.start_time ?? ex?.["start-time"]);
                // Two days back so a session that ran past midnight still counts.
                return ex?.id && Number.isFinite(stS) && stS >= hrJobDayS - 2 * 86400 && stS < hrJobDayS + 86400;
              }).slice(-8);
              for (const ex of wanted) {
                if (Date.now() > hrDeadline) break;
                const full = await hrWithTimeout(
                  polarFetch(`/exercises/${encodeURIComponent(String(ex.id))}?samples=true`, accessToken),
                  10_000, "Polar exercise samples",
                );
                const stS = hrWallS(full?.start_time ?? full?.["start-time"] ?? ex?.start_time ?? ex?.["start-time"]);
                for (const s of (Array.isArray(full?.samples) ? full.samples : []) as any[]) {
                  if (String(s?.sample_type ?? s?.["sample-type"]) !== "0") continue;   // 0 = heart rate
                  const rate = Number(s?.recording_rate ?? s?.["recording-rate"]);
                  if (!(rate > 0) || typeof s?.data !== "string") continue;
                  s.data.split(",").forEach((v: string, i: number) => {
                    exPoints.push({ t: stS + i * rate, bpm: Number(v) });
                  });
                }
              }
            } catch (e) {
              console.warn("[sync-worker-polar] hr goal exercise samples skipped:", (e as Error).message);
            }
            const hrDays: [string, number][] = [[hrPrevDate, hrJobDayS - 86400], [jobLocalDate, hrJobDayS]];
            for (const [hrDay, dayS] of hrDays) {
              const exSamples = hrDaySamples(exPoints, dayS);
              let contSamples: number[][] = [];
              try {
                if (Date.now() < hrDeadline) {
                  const cont = await hrWithTimeout(
                    polarFetch(`/users/continuous-heart-rate/${hrDay}`, accessToken),
                    10_000, "Polar continuous heart rate",
                  );
                  const contPoints = ((cont?.heart_rate_samples ?? []) as any[]).map((s) => {
                    const [h, m, sec] = String(s?.sample_time ?? "").split(":").map(Number);
                    return { t: dayS + h * 3600 + m * 60 + (sec || 0), bpm: Number(s?.heart_rate) };
                  });
                  contSamples = hrDaySamples(contPoints, dayS);
                }
              } catch (e) {
                // 404 = no all-day heart rate for that day or device: not an error
                if (!(e as Error).message.includes("404")) {
                  console.warn("[sync-worker-polar] hr goal continuous heart rate skipped:", (e as Error).message);
                }
              }
              if (!exSamples.length && !contSamples.length) continue;
              const exSpacing = hrSpacingOpts(exSamples);
              const contSpacing = hrSpacingOpts(contSamples);
              let hrWritten = 0, hrEstimates = 0;
              for (const thr of hrThresholds) {
                const exRes = exSamples.length ? hrThresholdSummary(exSamples, thr, exSpacing.opts) : null;
                const contRes = contSamples.length ? hrThresholdSummary(contSamples, thr, contSpacing.opts) : null;
                const useExercise = exRes != null && (exRes.minutesAbove > 0 || contRes == null || contRes.minutesAbove === 0);
                const res = useExercise ? exRes : contRes;
                if (!res) continue;
                const isEstimate = useExercise ? exSpacing.isEstimate : contSpacing.isEstimate;
                const { error: hrErr } = await supabase.from("hr_threshold_daily").upsert({
                  user_id: userId,
                  date: hrDay,
                  threshold_bpm: thr,
                  minutes_above: res.minutesAbove,
                  longest_run_minutes: res.longestRunMinutes,
                  is_estimate: isEstimate,
                  source: SOURCE,
                  updated_at: new Date().toISOString()
                }, { onConflict: "user_id,date,threshold_bpm,source" });
                if (hrErr) console.error("[sync-worker-polar] upsert hr_threshold_daily:", hrErr.message);
                else { hrWritten++; if (isEstimate) hrEstimates++; }
              }
              metricResults.push({
                metric: "hr_threshold_daily", date: hrDay,
                exercise_samples: exSamples.length, continuous_samples: contSamples.length,
                estimates: hrEstimates, status: hrWritten ? "written" : "upsert_error"
              });
            }
          }
        } catch (e) {
          console.error("[sync-worker-polar] hr_threshold_daily skipped:", (e as Error).message);
          metricResults.push({ endpoint: "hr-threshold", status: "error", error: (e as Error).message });
        }
        // ─────────────────────────────────────────────────────────
        // Finalize
        // ─────────────────────────────────────────────────────────
        if (!anyData && isTodayJob && currentAttempt < MAX_PICK_ATTEMPTS) {
          return {
            jobId: job.id,
            userId,
            localDate: jobLocalDate,
            status: "retry_waiting_for_polar_data",
            localTime,
            metricResults,
            attempt: currentAttempt
          };
        }
        if (!anyData && currentAttempt >= MAX_PICK_ATTEMPTS) {
          await markJobError(job.id, "no_polar_data");
          return {
            jobId: job.id,
            userId,
            localDate: jobLocalDate,
            status: "no_polar_data_error",
            metricResults,
            attempt: currentAttempt
          };
        }
        await markJobDone(job.id, null);
        return {
          jobId: job.id,
          userId,
          localDate: jobLocalDate,
          status: "done",
          timezone: tz,
          localTime,
          metricResults,
          attempt: currentAttempt
        };
      } catch (e) {
        const msg = e.message;
        await markJobError(job.id, msg);
        return {
          jobId: job.id,
          status: "error",
          error: msg
        };
      }
    }
    // Process all picked jobs (up to 6 concurrently)
    const results = await mapLimit(picked, 6, async (j)=>processJob(j));
    const summary = {
      picked: picked.length,
      done: results.filter((r)=>r.status === "done").length,
      requeued: results.filter((r)=>r.status === "outside_retry_window" || String(r.status).includes("retry_waiting") || r.status === "polar_not_connected").length,
      errors: results.filter((r)=>r.status === "error" || String(r.status).includes("error")).length,
      nowUtc: nowIso,
      forced: force
    };
    return jsonResponse({
      ok: true,
      summary,
      results
    });
  } catch (e) {
    return jsonResponse({
      ok: false,
      error: e.message
    }, 500);
  }
});
