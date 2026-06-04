// supabase/functions/sync-worker-garmin/index.ts
//
// Pull-based sync worker for Garmin data.
// Processes "garmin_daily" jobs from sync_jobs table.
// Fetches from Garmin Health API REST endpoints and writes to metric tables.
//
// This is a SAFETY NET — primary data flow is webhook-based (garmin-webhook).
// The sync worker only fetches data that the webhook might have missed.
//
// Architecture: same pattern as sync-worker-polar
//   - 9am retry window with MAX_PICK_ATTEMPTS
//   - tryMarkMetricRan dedup via backend_daily_runs
//   - Fire-and-forget from dispatcher
//   - Token refresh via garmin-token-refresh before fetching
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";
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
function secondsToDecimalHours(sec) {
  if (sec == null || !Number.isFinite(sec)) return null;
  return Math.round(sec / 3600 * 100) / 100;
}
function epochToLocalHM(epochSec, offsetSec) {
  if (!Number.isFinite(epochSec)) return null;
  const localSec = epochSec + offsetSec;
  const d = new Date(localSec * 1000);
  const h = d.getUTCHours();
  const m = d.getUTCMinutes();
  return Math.round((h + m / 60) * 100) / 100;
}
// ══════════════════════════════════════════════════════════════════════
// Garmin API helpers
// ══════════════════════════════════════════════════════════════════════
const GARMIN_BASE = "https://apis.garmin.com";
async function garminFetch(path, accessToken, params = {}) {
  const qs = new URLSearchParams(params).toString();
  const url = `${GARMIN_BASE}${path}${qs ? "?" + qs : ""}`;
  const resp = await fetch(url, {
    headers: {
      "Authorization": `Bearer ${accessToken}`,
      "Accept": "application/json"
    }
  });
  if (!resp.ok) {
    const text = await resp.text().catch(()=>"");
    throw new Error(`Garmin ${path} → ${resp.status}: ${text.slice(0, 200)}`);
  }
  return resp.json();
}
// ══════════════════════════════════════════════════════════════════════
// Retry window
// ══════════════════════════════════════════════════════════════════════
function isInsideRetryWindow(localTime) {
  const [h, m] = localTime.split(":").map(Number);
  const mins = h * 60 + m;
  return mins >= 7 * 60 && mins <= 11 * 60 + 30; // 07:00 – 11:30
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
const MAX_PICK_ATTEMPTS = 10;
async function mapLimit(items, limit, fn) {
  const results = new Array(items.length);
  let nextIndex = 0;
  async function worker() {
    while(nextIndex < items.length){
      const i = nextIndex++;
      results[i] = await fn(items[i]);
    }
  }
  await Promise.all(Array.from({
    length: Math.min(limit, items.length)
  }, ()=>worker()));
  return results;
}
serve(async (req)=>{
  if (req.method === "OPTIONS") {
    return new Response("ok", {
      headers: {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "POST, OPTIONS",
        "Access-Control-Allow-Headers": "Content-Type, Authorization"
      }
    });
  }
  const SUPABASE_URL = requireEnv("SUPABASE_URL");
  const SERVICE_KEY = requireEnv("SUPABASE_SERVICE_ROLE_KEY");
  const supabase = createClient(SUPABASE_URL, SERVICE_KEY);
  let body = {};
  try {
    body = await req.json();
  } catch  {}
  const force = body.force === true;
  const nowUtc = new Date();
  // Pick pending garmin_daily jobs
  const { data: jobs, error: jobErr } = await supabase.from("sync_jobs").select("*").eq("job_type", "garmin_daily").eq("status", "queued").lt("attempts", MAX_PICK_ATTEMPTS).order("local_date", {
    ascending: true
  }).limit(50);
  if (jobErr || !jobs?.length) {
    return jsonResponse({
      ok: true,
      jobs_found: 0
    });
  }
  const jobResults = await mapLimit(jobs, 5, async (job)=>{
    const userId = job.user_id;
    const jobLocalDate = job.local_date;
    const tz = job.timezone || "UTC";
    const currentAttempt = (job.attempts ?? 0) + 1;
    // Increment attempt
    await supabase.from("sync_jobs").update({
      attempts: currentAttempt
    }).eq("id", job.id);
    try {
      // Check retry window
      const { localDate: todayLocal, localTime } = getLocalTimeParts(tz, nowUtc);
      const isTodayJob = jobLocalDate === todayLocal;
      if (isTodayJob && !force && !isInsideRetryWindow(localTime)) {
        return {
          jobId: job.id,
          userId,
          status: "outside_retry_window",
          localTime
        };
      }
      // Get Garmin token (refresh if needed)
      let { data: tokenRow } = await supabase.from("garmin_tokens").select("access_token,garmin_user_id,access_expires_at").eq("user_id", userId).maybeSingle();
      if (!tokenRow?.access_token) {
        await supabase.from("sync_jobs").update({
          status: "error",
          last_error: "garmin_not_connected"
        }).eq("id", job.id);
        return {
          jobId: job.id,
          userId,
          status: "garmin_not_connected"
        };
      }
      // Check if token needs refresh
      let accessToken = tokenRow.access_token;
      const expiresAt = tokenRow.access_expires_at ? new Date(tokenRow.access_expires_at).getTime() : 0;
      if (expiresAt > 0 && expiresAt - Date.now() < 5 * 60 * 1000) {
        try {
          const refreshResp = await fetch(`${SUPABASE_URL}/functions/v1/garmin-token-refresh`, {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
              "Authorization": `Bearer ${SERVICE_KEY}`
            },
            body: JSON.stringify({
              user_id: userId
            })
          });
          if (refreshResp.ok) {
            const rd = await refreshResp.json();
            if (rd.access_token) accessToken = rd.access_token;
          }
        } catch (e) {
          console.warn("Token refresh failed:", e.message);
        }
      }
      // Get enabled Garmin metrics
      const { data: metricRows } = await supabase.from("metric_settings").select("metric,enabled,preferred_source").eq("user_id", userId);
      const enabledMap = new Map();
      (metricRows ?? []).forEach((r)=>{
        const isGarmin = (r.preferred_source ?? "").toLowerCase() === "garmin";
        enabledMap.set(r.metric, r.enabled === true && isGarmin);
      });
      function garminEnabled(metric) {
        return enabledMap.get(metric) === true;
      }
      async function tryMarkMetricRan(metric, localDate) {
        const { error } = await supabase.from("backend_metric_runs").insert({
          user_id: userId,
          local_date: localDate,
          metric,
          source: "garmin", source_device: d?.deviceName ?? null
        }).select();
        if (error) {
          return false;
        }
        return true;
      }
      async function upsertDailyRow(table, row, conflict = "user_id,date,source") {
        const { error } = await supabase.from(table).upsert(row, {
          onConflict: conflict
        });
        if (error) console.error(`upsert ${table}:`, error.message);
        return !error;
      }
      // Compute 24h window for the job's local date
      const dateStart = new Date(jobLocalDate + "T00:00:00Z");
      const uploadStart = Math.floor(dateStart.getTime() / 1000) - 86400; // 1 day buffer
      const uploadEnd = Math.floor(dateStart.getTime() / 1000) + 86400 * 2;
      const timeParams = {
        uploadStartTimeInSeconds: String(uploadStart),
        uploadEndTimeInSeconds: String(uploadEnd)
      };
      let anyData = false;
      // ── Fetch dailies ──
      try {
        const dailies = await garminFetch("/wellness-api/rest/dailies", accessToken, timeParams);
        if (Array.isArray(dailies)) {
          for (const d of dailies){
            const calDate = d.calendarDate;
            if (calDate !== jobLocalDate) continue;
            if (garminEnabled("steps_daily")) {
              const val = intOrNull(d.steps);
              if (val != null && await tryMarkMetricRan("steps_daily", calDate)) {
                await upsertDailyRow("steps_daily", {
                  user_id: userId,
                  date: calDate,
                  value_count: val,
                  source: "garmin", source_device: d?.deviceName ?? null
                });
                anyData = true;
              }
            }
            if (garminEnabled("resting_hr_daily")) {
              const val = intOrNull(d.restingHeartRateInBeatsPerMinute);
              if (val != null && await tryMarkMetricRan("resting_hr_daily", calDate)) {
                await upsertDailyRow("resting_hr_daily", {
                  user_id: userId,
                  date: calDate,
                  value_bpm: val,
                  source: "garmin", source_device: d?.deviceName ?? null
                });
                anyData = true;
              }
            }
            if (garminEnabled("stress_index_daily")) {
              const val = intOrNull(d.averageStressLevel);
              if (val != null && val > 0 && await tryMarkMetricRan("stress_index_daily", calDate)) {
                await upsertDailyRow("stress_index_daily", {
                  user_id: userId,
                  date: calDate,
                  value: val
                }, "user_id,date");
                anyData = true;
              }
            }
            if (garminEnabled("strain_daily")) {
              const kcal = numOrNull(d.activeKilocalories);
              if (kcal != null && await tryMarkMetricRan("strain_daily", calDate)) {
                await upsertDailyRow("strain_daily", {
                  user_id: userId,
                  date: calDate,
                  value_kilojoule: Math.round(kcal * 4.184),
                  source: "garmin", source_device: d?.deviceName ?? null
                });
                anyData = true;
              }
            }
          }
        }
      } catch (e) {
        console.warn("Garmin dailies fetch:", e.message);
      }
      // ── Fetch sleeps ──
      try {
        const sleeps = await garminFetch("/wellness-api/rest/sleeps", accessToken, timeParams);
        if (Array.isArray(sleeps)) {
          for (const s of sleeps){
            const calDate = s.calendarDate || jobLocalDate;
            const startSec = s.startTimeInSeconds;
            const offsetSec = s.startTimeOffsetInSeconds ?? 0;
            if (garminEnabled("sleep_duration_daily")) {
              const val = secondsToDecimalHours(numOrNull(s.durationInSeconds));
              if (val != null && await tryMarkMetricRan("sleep_duration_daily", calDate)) {
                await upsertDailyRow("sleep_duration_daily", {
                  user_id: userId,
                  date: calDate,
                  value_hours: val,
                  source: "garmin", source_device: s?.deviceName ?? null
                });
                anyData = true;
              }
            }
            if (garminEnabled("sleep_score_daily")) {
              const scoreObj = s.sleepScores;
              const val = intOrNull(scoreObj?.overall?.value ?? scoreObj?.overall ?? s.overallScore);
              if (val != null && await tryMarkMetricRan("sleep_score_daily", calDate)) {
                await upsertDailyRow("sleep_score_daily", {
                  user_id: userId,
                  date: calDate,
                  value_pct: val,
                  source: "garmin", source_device: s?.deviceName ?? null
                });
                anyData = true;
              }
            }
            if (garminEnabled("sleep_efficiency_daily")) {
              const total = numOrNull(s.durationInSeconds);
              const awake = numOrNull(s.awakeDurationInSeconds);
              if (total && total > 0 && awake != null) {
                const eff = Math.round((total - awake) / total * 10000) / 100;
                if (await tryMarkMetricRan("sleep_efficiency_daily", calDate)) {
                  await upsertDailyRow("sleep_efficiency_daily", {
                    user_id: userId,
                    date: calDate,
                    value_pct: eff,
                    source: "garmin", source_device: s?.deviceName ?? null
                  });
                  anyData = true;
                }
              }
            }
            if (garminEnabled("sleep_stages_daily")) {
              const deep = secondsToDecimalHours(numOrNull(s.deepSleepDurationInSeconds));
              const light = secondsToDecimalHours(numOrNull(s.lightSleepDurationInSeconds));
              const rem = secondsToDecimalHours(numOrNull(s.remSleepInSeconds));
              const awake = secondsToDecimalHours(numOrNull(s.awakeDurationInSeconds));
              if ((deep != null || light != null || rem != null) && await tryMarkMetricRan("sleep_stages_daily", calDate)) {
                await upsertDailyRow("sleep_stages_daily", {
                  user_id: userId,
                  date: calDate,
                  source: "garmin", source_device: s?.deviceName ?? null,
                  value_sws_hm: deep,
                  value_rem_hm: rem,
                  value_light_hm: light
                });
                anyData = true;
              }
            }
            if (garminEnabled("sleep_disturbances_daily")) {
              const awakeSec = numOrNull(s.awakeDurationInSeconds);
              if (awakeSec != null && await tryMarkMetricRan("sleep_disturbances_daily", calDate)) {
                await upsertDailyRow("sleep_disturbances_daily", {
                  user_id: userId,
                  date: calDate,
                  value_count: Math.round(awakeSec / 60),
                  source: "garmin", source_device: s?.deviceName ?? null
                });
                anyData = true;
              }
            }
            if (garminEnabled("fell_asleep_time_daily") && startSec != null) {
              const val = epochToLocalHM(startSec, offsetSec);
              if (val != null && await tryMarkMetricRan("fell_asleep_time_daily", calDate)) {
                await upsertDailyRow("fell_asleep_time_daily", {
                  user_id: userId,
                  date: calDate,
                  value_at: val,
                  source: "garmin", source_device: s?.deviceName ?? null
                });
                anyData = true;
              }
            }
            if (garminEnabled("woke_up_time_daily")) {
              const dur = numOrNull(s.durationInSeconds);
              if (startSec != null && dur != null) {
                const val = epochToLocalHM(startSec + dur, offsetSec);
                if (val != null && await tryMarkMetricRan("woke_up_time_daily", calDate)) {
                  await upsertDailyRow("woke_up_time_daily", {
                    user_id: userId,
                    date: calDate,
                    value_at: val,
                    source: "garmin", source_device: s?.deviceName ?? null
                  });
                  anyData = true;
                }
              }
            }
            if (garminEnabled("respiratory_rate_daily")) {
              const val = numOrNull(s.averageRespiration);
              if (val != null && await tryMarkMetricRan("respiratory_rate_daily", calDate)) {
                await upsertDailyRow("respiratory_rate_daily", {
                  user_id: userId,
                  date: calDate,
                  value_bpm: Math.round(val * 10) / 10,
                  source: "garmin", source_device: s?.deviceName ?? null
                });
                anyData = true;
              }
            }
            if (garminEnabled("spo2_daily")) {
              const val = numOrNull(s.averageSpO2Value ?? s.averageSpO2);
              if (val != null && val > 0 && await tryMarkMetricRan("spo2_daily", calDate)) {
                await upsertDailyRow("spo2_daily", {
                  user_id: userId,
                  date: calDate,
                  value_pct: Math.round(val * 10) / 10,
                  source: "garmin", source_device: s?.deviceName ?? null
                });
                anyData = true;
              }
            }
          }
        }
      } catch (e) {
        console.warn("Garmin sleeps fetch:", e.message);
      }
      // ── Fetch stress details (for recovery/body battery) ──
      try {
        const stressDetails = await garminFetch("/wellness-api/rest/stressDetails", accessToken, timeParams);
        if (Array.isArray(stressDetails)) {
          for (const sd of stressDetails){
            const calDate = sd.calendarDate || jobLocalDate;
            if (garminEnabled("recovery_score_daily")) {
              const bbOffsets = sd.timeOffsetBodyBatteryValues;
              if (bbOffsets && typeof bbOffsets === "object") {
                const vals = Object.values(bbOffsets).map(Number).filter((v)=>Number.isFinite(v) && v >= 0 && v <= 100);
                if (vals.length >= 2) {
                  const recharge = Math.max(...vals) - Math.min(...vals);
                  if (recharge > 0 && await tryMarkMetricRan("recovery_score_daily", calDate)) {
                    await upsertDailyRow("recovery_score_daily", {
                      user_id: userId,
                      date: calDate,
                      value_pct: recharge,
                      source: "garmin", source_device: sd?.deviceName ?? null
                    });
                    anyData = true;
                  }
                }
              }
            }
          }
        }
      } catch (e) {
        console.warn("Garmin stressDetails fetch:", e.message);
      }
      // ── Fetch body compositions (weight + body fat) ──
      try {
        const bodyComps = await garminFetch("/wellness-api/rest/bodyComps", accessToken, timeParams);
        if (Array.isArray(bodyComps)) {
          for (const bc of bodyComps){
            const calDate = bc.calendarDate || jobLocalDate;
            if (garminEnabled("weight_daily")) {
              const weightG = numOrNull(bc.weightInGrams);
              if (weightG != null && weightG > 0) {
                const kg = Math.round(weightG / 10) / 100;
                if (await tryMarkMetricRan("weight_daily", calDate)) {
                  await upsertDailyRow("weight_daily", {
                    user_id: userId,
                    date: calDate,
                    value_kg: kg,
                    source: "garmin", source_device: bc?.deviceName ?? null
                  });
                  anyData = true;
                }
              }
            }
            if (garminEnabled("body_fat_daily")) {
              const fatPct = numOrNull(bc.bodyFatPercentage);
              if (fatPct != null && fatPct > 0) {
                if (await tryMarkMetricRan("body_fat_daily", calDate)) {
                  await upsertDailyRow("body_fat_daily", {
                    user_id: userId,
                    date: calDate,
                    value_pct: Math.round(fatPct * 100) / 100,
                    source: "garmin", source_device: bc?.deviceName ?? null
                  });
                  anyData = true;
                }
              }
            }
          }
        }
      } catch (e) {
        console.warn("Garmin bodyComps fetch:", e.message);
      }
      // Mark job done
      const doneNote = anyData ? "data_written" : "no_new_data";
      await supabase.from("sync_jobs").update({
        status: "done",
        last_error: doneNote
      }).eq("id", job.id);
      return {
        jobId: job.id,
        userId,
        localDate: jobLocalDate,
        status: "done",
        anyData
      };
    } catch (err) {
      const msg = err.message;
      await supabase.from("sync_jobs").update({
        status: "error",
        last_error: msg.slice(0, 500)
      }).eq("id", job.id);
      return {
        jobId: job.id,
        userId,
        status: "error",
        msg
      };
    }
  });
  return jsonResponse({
    ok: true,
    jobs_found: jobs.length,
    results: jobResults
  });
});
