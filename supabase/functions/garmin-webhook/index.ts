// supabase/functions/garmin-webhook/index.ts
//
// Receives Garmin Health API PUSH notifications (real-time + backfill).
// Garmin sends POST with JSON body containing one or more summary types.
// Each summary type key contains an array of summary objects.
//
// Supported summary types (ALL that Garmin may push):
//   dailies, sleeps, stressDetails, pulseOx, respiration, bodyComps,
//   bloodPressures, healthSnapshot, skinTemperatures, hrv/hrvSummaries,
//   epochs, moveIQ, activityFiles, activities, manuallyUpdatedActivities,
//   activityDetails, deregistrations
//
// Architecture:
//   - Webhook receives push data → parses → upserts into daily metric tables
//   - Same endpoint handles both real-time pushes and backfill responses
//   - Uses tryMarkMetricRan dedup via backend_daily_runs
//   - Maps garmin_user_id → user_id via garmin_tokens table
//   - CRITICAL: Must return 200 for ALL summary types, even unused ones.
//     Garmin retries on non-200 and marks endpoints as failing in prod verification.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";
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
// Convert epoch seconds + offset to HH:MM local time as decimal hours
function epochToLocalHM(epochSec, offsetSec) {
  if (!Number.isFinite(epochSec)) return null;
  const localSec = epochSec + offsetSec;
  const d = new Date(localSec * 1000);
  const h = d.getUTCHours();
  const m = d.getUTCMinutes();
  return Math.round((h + m / 60) * 100) / 100;
}
// Get calendarDate from summary, or compute from startTimeInSeconds + offset
function getCalendarDate(summary) {
  if (summary.calendarDate) return summary.calendarDate;
  if (summary.startTimeInSeconds != null && summary.startTimeOffsetInSeconds != null) {
    const localMs = (summary.startTimeInSeconds + summary.startTimeOffsetInSeconds) * 1000;
    const d = new Date(localMs);
    return d.toISOString().slice(0, 10);
  }
  return null;
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
  if (req.method !== "POST") {
    return jsonResponse({
      error: "Method not allowed"
    }, 405);
  }
  // ── Optional Garmin webhook token verification ──
  // If GARMIN_WEBHOOK_TOKEN is set AND ?token= is in the URL, verify they match.
  // If no token param is provided (e.g. Garmin portal URLs without ?token=),
  // still process the request — Garmin's own delivery system is the auth layer.
  const garminWebhookToken = Deno.env.get("GARMIN_WEBHOOK_TOKEN");
  const url = new URL(req.url);
  const incomingToken = url.searchParams.get("token");
  if (garminWebhookToken && incomingToken && incomingToken !== garminWebhookToken) {
    console.warn("[garmin-webhook] Token mismatch — rejecting");
    return jsonResponse({
      ok: true
    });
  }
  const SUPABASE_URL = requireEnv("SUPABASE_URL");
  const SERVICE_KEY = requireEnv("SUPABASE_SERVICE_ROLE_KEY");
  const supabase = createClient(SUPABASE_URL, SERVICE_KEY);
  let body;
  try {
    body = await req.json();
  } catch  {
    // Even if body is malformed, return 200 so Garmin doesn't retry
    return jsonResponse({
      ok: true,
      note: "empty_or_invalid_body"
    });
  }
  // Log all incoming keys for debugging
  const incomingKeys = Object.keys(body);
  console.log("[garmin-webhook] Received keys:", incomingKeys.join(", "));
  const results = [];
  // ════════════════════════════════════════════════════════════════════
  // Helper: resolve garmin_user_id → user_id
  // ════════════════════════════════════════════════════════════════════
  const userCache = new Map();
  async function resolveUserId(garminUserId) {
    if (!garminUserId) return null;
    if (userCache.has(garminUserId)) return userCache.get(garminUserId);
    const { data } = await supabase.from("garmin_tokens").select("user_id").eq("garmin_user_id", garminUserId).maybeSingle();
    const uid = data?.user_id ?? null;
    userCache.set(garminUserId, uid);
    return uid;
  }
  // Resolve from userAccessToken field (Garmin push notifications include this)
  async function resolveUserIdFromUAT(summary) {
    // Garmin push notifications include userId (garmin user id)
    const garminUserId = summary.userId || summary.userAccessToken;
    if (!garminUserId) return null;
    return resolveUserId(garminUserId);
  }
  // ════════════════════════════════════════════════════════════════════
  // Helper: save device_name to garmin_tokens if not yet set
  // ════════════════════════════════════════════════════════════════════
  const deviceNameSaved = new Set();
  async function trySaveDeviceName(summary) {
    const deviceName = summary?.deviceName;
    const garminUserId = summary?.userId || summary?.userAccessToken;
    if (!deviceName || !garminUserId || deviceNameSaved.has(garminUserId)) return;
    deviceNameSaved.add(garminUserId);
    const { data } = await supabase.from("garmin_tokens").select("device_name").eq("garmin_user_id", garminUserId).maybeSingle();
    if (data && !data.device_name) {
      await supabase.from("garmin_tokens").update({ device_name: deviceName }).eq("garmin_user_id", garminUserId);
    }
  }
  // ════════════════════════════════════════════════════════════════════
  // Helper: upsert a daily metric row
  // ════════════════════════════════════════════════════════════════════
  async function upsertDailyRow(table, row, conflict = "user_id,date,source") {
    const { error } = await supabase.from(table).upsert(row, {
      onConflict: conflict
    });
    if (error) console.error(`upsert ${table}:`, error.message);
    return !error;
  }
  // ════════════════════════════════════════════════════════════════════
  // Helper: check if metric enabled for user with source=garmin
  // ════════════════════════════════════════════════════════════════════
  const metricCache = new Map();
  async function isGarminEnabled(userId, metric) {
    if (!metricCache.has(userId)) {
      const { data } = await supabase.from("metric_settings").select("metric,enabled,preferred_source").eq("user_id", userId);
      const map = new Map();
      (data ?? []).forEach((r)=>{
        const isGarmin = (r.preferred_source ?? "").toLowerCase() === "garmin";
        map.set(r.metric, r.enabled === true && isGarmin);
      });
      metricCache.set(userId, map);
    }
    return metricCache.get(userId).get(metric) === true;
  }
  // ════════════════════════════════════════════════════════════════════
  // Helper: tryMarkMetricRan dedup
  // ════════════════════════════════════════════════════════════════════
  async function tryMarkMetricRan(userId, metric, localDate, source = "garmin") {
    const { error } = await supabase.from("backend_metric_runs").insert({
      user_id: userId,
      local_date: localDate,
      metric,
      source
    }).select();
    if (error) {
      if (error.code === "23505") return false; // duplicate
      console.warn("tryMarkMetricRan error:", error.message);
      return false;
    }
    return true;
  }
  // ════════════════════════════════════════════════════════════════════
  // Process: DAILIES
  // ════════════════════════════════════════════════════════════════════
  if (body.dailies && Array.isArray(body.dailies)) {
    for (const d of body.dailies){
      try {
        const userId = await resolveUserIdFromUAT(d);
        if (!userId) {
          results.push({
            type: "dailies",
            status: "no_user"
          });
          continue;
        }
        await trySaveDeviceName(d);
        const localDate = getCalendarDate(d);
        if (!localDate) {
          results.push({
            type: "dailies",
            status: "no_date"
          });
          continue;
        }
        // steps_daily
        if (await isGarminEnabled(userId, "steps_daily")) {
          const val = intOrNull(d.steps);
          if (val != null && await tryMarkMetricRan(userId, "steps_daily", localDate)) {
            await upsertDailyRow("steps_daily", {
              user_id: userId,
              date: localDate,
              value_count: val,
              source: "garmin", source_device: d?.deviceName ?? null
            });
          }
        }
        // resting_hr_daily
        if (await isGarminEnabled(userId, "resting_hr_daily")) {
          const val = intOrNull(d.restingHeartRateInBeatsPerMinute);
          if (val != null && await tryMarkMetricRan(userId, "resting_hr_daily", localDate)) {
            await upsertDailyRow("resting_hr_daily", {
              user_id: userId,
              date: localDate,
              value_bpm: val,
              source: "garmin", source_device: d?.deviceName ?? null
            });
          }
        }
        // stress_index_daily (DIRECT 1-100 from Garmin)
        if (await isGarminEnabled(userId, "stress_index_daily")) {
          const val = intOrNull(d.averageStressLevel);
          if (val != null && val > 0 && await tryMarkMetricRan(userId, "stress_index_daily", localDate)) {
            await upsertDailyRow("stress_index_daily", {
              user_id: userId,
              date: localDate,
              value: val
            }, "user_id,date");
          }
        }
        // strain_daily (active kcal → kJ: ×4.184)
        if (await isGarminEnabled(userId, "strain_daily")) {
          const kcal = numOrNull(d.activeKilocalories);
          if (kcal != null) {
            const kj = Math.round(kcal * 4.184);
            if (await tryMarkMetricRan(userId, "strain_daily", localDate)) {
              await upsertDailyRow("strain_daily", {
                user_id: userId,
                date: localDate,
                value_kilojoule: kj,
                source: "garmin", source_device: d?.deviceName ?? null
              });
            }
          }
        }
        results.push({
          type: "dailies",
          userId,
          localDate,
          status: "ok"
        });
      } catch (err) {
        results.push({
          type: "dailies",
          status: "error",
          msg: err.message
        });
      }
    }
  }
  // ════════════════════════════════════════════════════════════════════
  // Process: SLEEPS
  // ════════════════════════════════════════════════════════════════════
  if (body.sleeps && Array.isArray(body.sleeps)) {
    for (const s of body.sleeps){
      try {
        const userId = await resolveUserIdFromUAT(s);
        if (!userId) {
          results.push({
            type: "sleeps",
            status: "no_user"
          });
          continue;
        }
        const localDate = getCalendarDate(s);
        if (!localDate) {
          results.push({
            type: "sleeps",
            status: "no_date"
          });
          continue;
        }
        const startSec = s.startTimeInSeconds;
        const offsetSec = s.startTimeOffsetInSeconds ?? 0;
        // sleep_duration_daily (seconds → decimal hours)
        if (await isGarminEnabled(userId, "sleep_duration_daily")) {
          const val = secondsToDecimalHours(numOrNull(s.durationInSeconds));
          if (val != null && await tryMarkMetricRan(userId, "sleep_duration_daily", localDate)) {
            await upsertDailyRow("sleep_duration_daily", {
              user_id: userId,
              date: localDate,
              value_hours: val,
              source: "garmin", source_device: s?.deviceName ?? null
            });
          }
        }
        // sleep_score_daily
        if (await isGarminEnabled(userId, "sleep_score_daily")) {
          const scoreObj = s.sleepScores;
          const val = intOrNull(scoreObj?.overall?.value ?? scoreObj?.overall ?? s.overallScore);
          if (val != null && await tryMarkMetricRan(userId, "sleep_score_daily", localDate)) {
            await upsertDailyRow("sleep_score_daily", {
              user_id: userId,
              date: localDate,
              value_pct: val,
              source: "garmin", source_device: s?.deviceName ?? null
            });
          }
        }
        // sleep_efficiency_daily (computed: (total - awake) / total × 100)
        if (await isGarminEnabled(userId, "sleep_efficiency_daily")) {
          const total = numOrNull(s.durationInSeconds);
          const awake = numOrNull(s.awakeDurationInSeconds);
          if (total != null && total > 0 && awake != null) {
            const eff = Math.round((total - awake) / total * 10000) / 100;
            if (await tryMarkMetricRan(userId, "sleep_efficiency_daily", localDate)) {
              await upsertDailyRow("sleep_efficiency_daily", {
                user_id: userId,
                date: localDate,
                value_pct: eff,
                source: "garmin", source_device: s?.deviceName ?? null
              });
            }
          }
        }
        // sleep_stages_daily (JSON: deep, light, rem, awake in hours)
        if (await isGarminEnabled(userId, "sleep_stages_daily")) {
          const deep = secondsToDecimalHours(numOrNull(s.deepSleepDurationInSeconds));
          const light = secondsToDecimalHours(numOrNull(s.lightSleepDurationInSeconds));
          const rem = secondsToDecimalHours(numOrNull(s.remSleepInSeconds));
          const awake = secondsToDecimalHours(numOrNull(s.awakeDurationInSeconds));
          if ((deep != null || light != null || rem != null) && await tryMarkMetricRan(userId, "sleep_stages_daily", localDate)) {
            await upsertDailyRow("sleep_stages_daily", {
              user_id: userId,
              date: localDate,
              source: "garmin", source_device: s?.deviceName ?? null,
              value_sws_hm: deep,
              value_rem_hm: rem,
              value_light_hm: light
            });
          }
        }
        // sleep_disturbances_daily (awake minutes → approximate count)
        // Garmin provides total awake duration, not a disturbance count.
        // Approximation: 1 disturbance per 5 awake minutes (consistent with typical fragmentation).
        if (await isGarminEnabled(userId, "sleep_disturbances_daily")) {
          const awakeSec = numOrNull(s.awakeDurationInSeconds);
          if (awakeSec != null) {
            const awakeMin = awakeSec / 60;
            const approxCount = Math.max(1, Math.round(awakeMin / 5));
            if (await tryMarkMetricRan(userId, "sleep_disturbances_daily", localDate)) {
              await upsertDailyRow("sleep_disturbances_daily", {
                user_id: userId,
                date: localDate,
                value_count: approxCount,
                source: "garmin", source_device: s?.deviceName ?? null
              });
            }
          }
        }
        // fell_asleep_time_daily (epoch seconds → ISO UTC timestamp)
        if (await isGarminEnabled(userId, "fell_asleep_time_daily")) {
          if (startSec != null) {
            const val = new Date(startSec * 1000).toISOString();
            if (await tryMarkMetricRan(userId, "fell_asleep_time_daily", localDate)) {
              await upsertDailyRow("fell_asleep_time_daily", {
                user_id: userId,
                date: localDate,
                value_at: val,
                source: "garmin", source_device: s?.deviceName ?? null
              });
            }
          }
        }
        // woke_up_time_daily (epoch seconds → ISO UTC timestamp)
        if (await isGarminEnabled(userId, "woke_up_time_daily")) {
          const dur = numOrNull(s.durationInSeconds);
          if (startSec != null && dur != null) {
            const endSec = startSec + dur;
            const val = new Date(endSec * 1000).toISOString();
            if (await tryMarkMetricRan(userId, "woke_up_time_daily", localDate)) {
              await upsertDailyRow("woke_up_time_daily", {
                user_id: userId,
                date: localDate,
                value_at: val,
                source: "garmin", source_device: s?.deviceName ?? null
              });
            }
          }
        }
        // respiratory_rate_daily (from sleep averageRespiration)
        if (await isGarminEnabled(userId, "respiratory_rate_daily")) {
          const val = numOrNull(s.averageRespiration);
          if (val != null && await tryMarkMetricRan(userId, "respiratory_rate_daily", localDate)) {
            await upsertDailyRow("respiratory_rate_daily", {
              user_id: userId,
              date: localDate,
              value_bpm: Math.round(val * 10) / 10,
              source: "garmin", source_device: s?.deviceName ?? null
            });
          }
        }
        // spo2_daily (from sleep averageSpO2Value)
        if (await isGarminEnabled(userId, "spo2_daily")) {
          const val = numOrNull(s.averageSpO2Value ?? s.averageSpO2);
          if (val != null && val > 0 && await tryMarkMetricRan(userId, "spo2_daily", localDate)) {
            await upsertDailyRow("spo2_daily", {
              user_id: userId,
              date: localDate,
              value_pct: Math.round(val * 10) / 10,
              source: "garmin", source_device: s?.deviceName ?? null
            });
          }
        }
        results.push({
          type: "sleeps",
          userId,
          localDate,
          status: "ok"
        });
      } catch (err) {
        results.push({
          type: "sleeps",
          status: "error",
          msg: err.message
        });
      }
    }
  }
  // ════════════════════════════════════════════════════════════════════
  // Process: STRESS DETAILS
  // ════════════════════════════════════════════════════════════════════
  if (body.stressDetails && Array.isArray(body.stressDetails)) {
    for (const sd of body.stressDetails){
      try {
        const userId = await resolveUserIdFromUAT(sd);
        if (!userId) {
          results.push({
            type: "stressDetails",
            status: "no_user"
          });
          continue;
        }
        const localDate = getCalendarDate(sd);
        if (!localDate) continue;
        // stress_index_daily — average of all valid stress values (1-100, exclude -1/-2 rest/inactive)
        if (await isGarminEnabled(userId, "stress_index_daily")) {
          const offsets = sd.timeOffsetStressLevelValues;
          if (offsets && typeof offsets === "object") {
            const values = Object.values(offsets).map(Number).filter((v)=>v >= 1 && v <= 100);
            if (values.length > 0) {
              const avg = Math.round(values.reduce((a, b)=>a + b, 0) / values.length);
              if (await tryMarkMetricRan(userId, "stress_index_daily", localDate)) {
                await upsertDailyRow("stress_index_daily", {
                  user_id: userId,
                  date: localDate,
                  value: avg
                }, "user_id,date");
              }
            }
          }
        }
        // recovery_score_daily — Body Battery overnight recharge from stress details
        if (await isGarminEnabled(userId, "recovery_score_daily")) {
          const bbOffsets = sd.timeOffsetBodyBatteryValues;
          if (bbOffsets && typeof bbOffsets === "object") {
            const entries = Object.entries(bbOffsets).map(([k, v])=>[
                Number(k),
                Number(v)
              ]).filter(([_, v])=>Number.isFinite(v) && v >= 0 && v <= 100).sort((a, b)=>a[0] - b[0]);
            if (entries.length >= 2) {
              const vals = entries.map(([_, v])=>v);
              const min = Math.min(...vals);
              const max = Math.max(...vals);
              const recharge = max - min;
              if (recharge > 0 && await tryMarkMetricRan(userId, "recovery_score_daily", localDate)) {
                await upsertDailyRow("recovery_score_daily", {
                  user_id: userId,
                  date: localDate,
                  value_pct: recharge,
                  source: "garmin", source_device: sd?.deviceName ?? null
                });
              }
            }
          }
        }
        results.push({
          type: "stressDetails",
          userId,
          localDate,
          status: "ok"
        });
      } catch (err) {
        results.push({
          type: "stressDetails",
          status: "error",
          msg: err.message
        });
      }
    }
  }
  // ════════════════════════════════════════════════════════════════════
  // Process: PULSE OX
  // ════════════════════════════════════════════════════════════════════
  if (body.pulseOx && Array.isArray(body.pulseOx)) {
    for (const po of body.pulseOx){
      try {
        const userId = await resolveUserIdFromUAT(po);
        if (!userId) continue;
        const localDate = getCalendarDate(po);
        if (!localDate) continue;
        if (await isGarminEnabled(userId, "spo2_daily")) {
          const offsets = po.timeOffsetSpo2Values;
          if (offsets && typeof offsets === "object") {
            const values = Object.values(offsets).map(Number).filter((v)=>v > 0 && v <= 100);
            if (values.length > 0) {
              const avg = Math.round(values.reduce((a, b)=>a + b, 0) / values.length * 10) / 10;
              if (await tryMarkMetricRan(userId, "spo2_daily_pulseox", localDate)) {
                await upsertDailyRow("spo2_daily", {
                  user_id: userId,
                  date: localDate,
                  value_pct: avg,
                  source: "garmin", source_device: po?.deviceName ?? null
                });
              }
            }
          }
        }
        results.push({
          type: "pulseOx",
          userId,
          localDate,
          status: "ok"
        });
      } catch (err) {
        results.push({
          type: "pulseOx",
          status: "error",
          msg: err.message
        });
      }
    }
  }
  // ════════════════════════════════════════════════════════════════════
  // Process: RESPIRATION
  // ════════════════════════════════════════════════════════════════════
  if (body.allDayRespiration && Array.isArray(body.allDayRespiration)) {
    for (const r of body.allDayRespiration){
      try {
        const userId = await resolveUserIdFromUAT(r);
        if (!userId) continue;
        const localDate = getCalendarDate(r);
        if (!localDate) continue;
        if (await isGarminEnabled(userId, "respiratory_rate_daily")) {
          const offsets = r.timeOffsetEpochToBreaths;
          if (offsets && typeof offsets === "object") {
            const values = Object.values(offsets).map(Number).filter((v)=>v > 0 && v < 60);
            if (values.length > 0) {
              const avg = Math.round(values.reduce((a, b)=>a + b, 0) / values.length * 10) / 10;
              if (await tryMarkMetricRan(userId, "respiratory_rate_daily_resp", localDate)) {
                await upsertDailyRow("respiratory_rate_daily", {
                  user_id: userId,
                  date: localDate,
                  value_bpm: avg,
                  source: "garmin", source_device: po?.deviceName ?? null
                });
              }
            }
          }
        }
        results.push({
          type: "respiration",
          userId,
          localDate,
          status: "ok"
        });
      } catch (err) {
        results.push({
          type: "respiration",
          status: "error",
          msg: err.message
        });
      }
    }
  }
  // ════════════════════════════════════════════════════════════════════
  // Process: HRV (from body.hrv or body.hrvSummaries)
  // ════════════════════════════════════════════════════════════════════
  const hrvData = body.hrv || body.hrvSummaries;
  if (hrvData && Array.isArray(hrvData)) {
    for (const h of hrvData){
      try {
        const userId = await resolveUserIdFromUAT(h);
        if (!userId) continue;
        const localDate = getCalendarDate(h);
        if (!localDate) continue;
        if (await isGarminEnabled(userId, "hrv_daily")) {
          const val = numOrNull(h.lastNightAvg ?? h.lastNight5MinHigh ?? h.weeklyAvg);
          if (val != null && val > 0 && await tryMarkMetricRan(userId, "hrv_daily", localDate)) {
            await upsertDailyRow("hrv_daily", {
              user_id: userId,
              date: localDate,
              value_rmssd_ms: Math.round(val),
              source: "garmin", source_device: po?.deviceName ?? null
            });
          }
        }
        results.push({
          type: "hrv",
          userId,
          localDate,
          status: "ok"
        });
      } catch (err) {
        results.push({
          type: "hrv",
          status: "error",
          msg: err.message
        });
      }
    }
  }
  // ════════════════════════════════════════════════════════════════════
  // Process: SKIN TEMP (from body.skinTemperatures)
  // ════════════════════════════════════════════════════════════════════
  const skinTemps = body.skinTemperatures;
  if (skinTemps && Array.isArray(skinTemps)) {
    for (const st of skinTemps){
      try {
        const userId = await resolveUserIdFromUAT(st);
        if (!userId) continue;
        const localDate = getCalendarDate(st);
        if (!localDate) continue;
        if (await isGarminEnabled(userId, "skin_temp_daily")) {
          const val = numOrNull(st.deviation ?? st.relativeDeviation ?? st.averageDeviation);
          if (val != null && await tryMarkMetricRan(userId, "skin_temp_daily", localDate)) {
            await upsertDailyRow("skin_temp_daily", {
              user_id: userId,
              date: localDate,
              value_celsius: Math.round(val * 100) / 100,
              source: "garmin", source_device: po?.deviceName ?? null
            });
          }
        }
        results.push({
          type: "skinTemp",
          userId,
          localDate,
          status: "ok"
        });
      } catch (err) {
        results.push({
          type: "skinTemp",
          status: "error",
          msg: err.message
        });
      }
    }
  }
  // ════════════════════════════════════════════════════════════════════
  // Process: BODY COMPOSITIONS (WELLNESS_BODY_COMP)
  // Stores weight → weight_daily, body fat → body_fat_daily
  // ════════════════════════════════════════════════════════════════════
  if (body.bodyComps && Array.isArray(body.bodyComps)) {
    for (const bc of body.bodyComps){
      try {
        const userId = await resolveUserIdFromUAT(bc);
        if (!userId) {
          results.push({
            type: "bodyComps",
            status: "no_user"
          });
          continue;
        }
        const localDate = getCalendarDate(bc);
        if (!localDate) {
          results.push({
            type: "bodyComps",
            status: "no_date"
          });
          continue;
        }
        // weight_daily (grams → kg)
        if (await isGarminEnabled(userId, "weight_daily")) {
          const weightG = numOrNull(bc.weightInGrams);
          if (weightG != null && weightG > 0) {
            const kg = Math.round(weightG / 10) / 100;
            if (await tryMarkMetricRan(userId, "weight_daily", localDate)) {
              await upsertDailyRow("weight_daily", {
                user_id: userId,
                date: localDate,
                value_kg: kg,
                source: "garmin", source_device: bc?.deviceName ?? null
              });
            }
          }
        }
        // body_fat_daily (percentage)
        if (await isGarminEnabled(userId, "body_fat_daily")) {
          const fatPct = numOrNull(bc.bodyFatPercentage);
          if (fatPct != null && fatPct > 0) {
            if (await tryMarkMetricRan(userId, "body_fat_daily", localDate)) {
              await upsertDailyRow("body_fat_daily", {
                user_id: userId,
                date: localDate,
                value_pct: Math.round(fatPct * 100) / 100,
                source: "garmin", source_device: bc?.deviceName ?? null
              });
            }
          }
        }
        results.push({
          type: "bodyComps",
          userId,
          localDate,
          status: "ok"
        });
      } catch (err) {
        results.push({
          type: "bodyComps",
          status: "error",
          msg: err.message
        });
      }
    }
  }
  // ════════════════════════════════════════════════════════════════════
  // Process: BLOOD PRESSURE
  // Acknowledged and logged — not mapped to a metric currently.
  // Must return 200 for Garmin production verification.
  // ════════════════════════════════════════════════════════════════════
  if (body.bloodPressures && Array.isArray(body.bloodPressures)) {
    for (const bp of body.bloodPressures){
      try {
        const userId = await resolveUserIdFromUAT(bp);
        const localDate = getCalendarDate(bp);
        console.log(`[garmin-webhook] bloodPressures: user=${userId}, date=${localDate}, systolic=${bp.systolic}, diastolic=${bp.diastolic}`);
        results.push({
          type: "bloodPressures",
          userId,
          localDate,
          status: "ack"
        });
      } catch (err) {
        results.push({
          type: "bloodPressures",
          status: "error",
          msg: err.message
        });
      }
    }
  }
  // ════════════════════════════════════════════════════════════════════
  // Process: HEALTH SNAPSHOT
  // Acknowledged and logged — not mapped to a metric currently.
  // Must return 200 for Garmin production verification.
  // ════════════════════════════════════════════════════════════════════
  if (body.healthSnapshot && Array.isArray(body.healthSnapshot)) {
    for (const hs of body.healthSnapshot){
      try {
        const userId = await resolveUserIdFromUAT(hs);
        const localDate = getCalendarDate(hs);
        console.log(`[garmin-webhook] healthSnapshot: user=${userId}, date=${localDate}`);
        results.push({
          type: "healthSnapshot",
          userId,
          localDate,
          status: "ack"
        });
      } catch (err) {
        results.push({
          type: "healthSnapshot",
          status: "error",
          msg: err.message
        });
      }
    }
  }
  // ════════════════════════════════════════════════════════════════════
  // Process: DEREGISTRATIONS (USER_DEREG)
  // ════════════════════════════════════════════════════════════════════
  if (body.deregistrations && Array.isArray(body.deregistrations)) {
    for (const dereg of body.deregistrations){
      try {
        const garminUserId = dereg.userId;
        if (!garminUserId) continue;
        // Delete the token row — user revoked access in Garmin Connect
        await supabase.from("garmin_tokens").delete().eq("garmin_user_id", garminUserId);
        results.push({
          type: "deregistration",
          garminUserId,
          status: "deleted"
        });
      } catch (err) {
        results.push({
          type: "deregistration",
          status: "error",
          msg: err.message
        });
      }
    }
  }
  // ════════════════════════════════════════════════════════════════════
  // Acknowledge ALL other summary types Garmin might push.
  // We don't use these, but must return 200 for them.
  // Known additional types: epochs, moveIQ, activityFiles, activities,
  // manuallyUpdatedActivities, activityDetails, thirdPartyDailies
  // ════════════════════════════════════════════════════════════════════
  const handledKeys = new Set([
    "dailies",
    "sleeps",
    "stressDetails",
    "pulseOx",
    "allDayRespiration",
    "hrv",
    "hrvSummaries",
    "skinTemperatures",
    "bodyComps",
    "bloodPressures",
    "healthSnapshot",
    "deregistrations"
  ]);
  for (const key of incomingKeys){
    if (!handledKeys.has(key)) {
      const arr = body[key];
      const count = Array.isArray(arr) ? arr.length : 1;
      console.log(`[garmin-webhook] Unhandled summary type "${key}" with ${count} items — acknowledged`);
      results.push({
        type: key,
        status: "ack_unhandled",
        count
      });
    }
  }
  // Garmin expects HTTP 200 — anything else triggers retries
  return jsonResponse({
    ok: true,
    processed: results.length,
    results
  });
});
