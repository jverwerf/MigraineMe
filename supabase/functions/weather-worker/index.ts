// FILE: supabase/functions/weather-worker/index.ts
//
// Worker that processes jobs from sync_jobs_weather.
// Looks up user's location, finds nearest city, copies weather to user_weather_daily.
// Also copies 7-day forecast from city_weather_daily.
// NEW: If city_weather_daily has no data (new user / new city), fetches directly
//      from Open-Meteo (14 past + 7 forecast), stores in city_weather_daily, then proceeds.

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

/**
 * Find nearest city to given coordinates using Haversine distance
 */
async function findNearestCity(
  supabase: ReturnType<typeof createClient>,
  lat: number,
  lon: number
): Promise<{ cityId: number; name: string; lat: number; lon: number; timezone: string | null; distance: number } | null> {
  // Query cities within ~2 degrees (rough bounding box first for performance)
  const { data: cities, error } = await supabase
    .from("city")
    .select("id, name, lat, lon, timezone")
    .gte("lat", lat - 2)
    .lte("lat", lat + 2)
    .gte("lon", lon - 2)
    .lte("lon", lon + 2)
    .limit(100);

  if (error || !cities || cities.length === 0) {
    // Fallback: get any city (shouldn't happen often)
    const { data: fallback } = await supabase
      .from("city")
      .select("id, name, lat, lon, timezone")
      .limit(50);

    if (!fallback || fallback.length === 0) return null;

    let nearest = fallback[0];
    let minDist = haversine(lat, lon, nearest.lat, nearest.lon);

    for (const city of fallback) {
      const dist = haversine(lat, lon, city.lat, city.lon);
      if (dist < minDist) {
        minDist = dist;
        nearest = city;
      }
    }

    return { cityId: nearest.id, name: nearest.name, lat: nearest.lat, lon: nearest.lon, timezone: nearest.timezone, distance: minDist };
  }

  let nearest = cities[0];
  let minDist = haversine(lat, lon, nearest.lat, nearest.lon);

  for (const city of cities) {
    const dist = haversine(lat, lon, city.lat, city.lon);
    if (dist < minDist) {
      minDist = dist;
      nearest = city;
    }
  }

  return { cityId: nearest.id, name: nearest.name, lat: nearest.lat, lon: nearest.lon, timezone: nearest.timezone, distance: minDist };
}

function haversine(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c;
}

function toRad(deg: number): number {
  return deg * (Math.PI / 180);
}

/**
 * Fetch weather directly from Open-Meteo and store in city_weather_daily.
 * Used as fallback when the regular fetch-city-weather cron hasn't covered this city yet.
 */
async function fetchAndStoreCityWeather(
  supabase: ReturnType<typeof createClient>,
  cityId: number,
  cityLat: number,
  cityLon: number,
  cityTimezone: string | null
): Promise<boolean> {
  try {
    const tz = cityTimezone ?? "auto";
    const u = new URL("https://api.open-meteo.com/v1/forecast");
    u.searchParams.set("latitude", String(cityLat));
    u.searchParams.set("longitude", String(cityLon));
    u.searchParams.set(
      "daily",
      "temperature_2m_min,temperature_2m_max,temperature_2m_mean,surface_pressure_mean,surface_pressure_min,surface_pressure_max,relative_humidity_2m_mean,relative_humidity_2m_min,relative_humidity_2m_max,uv_index_max,wind_speed_10m_mean,wind_speed_10m_max,weathercode"
    );
    u.searchParams.set("past_days", "14");
    u.searchParams.set("forecast_days", "7");
    u.searchParams.set("timezone", tz);

    const resp = await fetch(u.toString(), {
      headers: { accept: "application/json" },
    });
    if (!resp.ok) throw new Error(`open-meteo ${resp.status}`);
    const d = (await resp.json())?.daily;
    if (!d?.time) throw new Error("missing daily block");

    const { error: rpcErr } = await supabase.rpc("upsert_city_weather_batch", {
      p_city_id: cityId,
      p_days: d.time,
      p_tmin: d.temperature_2m_min,
      p_tmax: d.temperature_2m_max,
      p_tmean: d.temperature_2m_mean,
      p_pmean: d.surface_pressure_mean,
      p_pmin: d.surface_pressure_min,
      p_pmax: d.surface_pressure_max,
      p_hmean: d.relative_humidity_2m_mean,
      p_hmin: d.relative_humidity_2m_min,
      p_hmax: d.relative_humidity_2m_max,
      p_uv_max: d.uv_index_max,
      p_wind_mean: d.wind_speed_10m_mean,
      p_wind_max: d.wind_speed_10m_max,
      p_weather_code: d.weathercode,
    });

    if (rpcErr) throw rpcErr;

    console.log(`[weather-worker] fetched Open-Meteo data for city=${cityId} (14 past + 7 forecast)`);
    return true;
  } catch (e: any) {
    console.error(`[weather-worker] Open-Meteo direct fetch failed for city=${cityId}: ${e.message}`);
    return false;
  }
}

/**
 * Upsert a single day of weather data from city_weather_daily to user_weather_daily
 */
async function upsertUserWeatherDay(
  supabase: ReturnType<typeof createClient>,
  userId: string,
  cityWeather: any,
  cityId: number,
  date: string,
  timezone: string,
  nowIso: string
): Promise<void> {
  const { error } = await supabase
    .from("user_weather_daily")
    .upsert(
      {
        user_id: userId,
        date: date,
        temp_c_min: cityWeather.temp_c_min,
        temp_c_max: cityWeather.temp_c_max,
        temp_c_mean: cityWeather.temp_c_mean,
        pressure_hpa_min: cityWeather.pressure_hpa_min,
        pressure_hpa_max: cityWeather.pressure_hpa_max,
        pressure_hpa_mean: cityWeather.pressure_hpa_mean,
        humidity_pct_min: cityWeather.humidity_pct_min,
        humidity_pct_max: cityWeather.humidity_pct_max,
        humidity_pct_mean: cityWeather.humidity_pct_mean,
        wind_speed_mps_mean: cityWeather.wind_speed_mps_mean,
        wind_speed_mps_max: cityWeather.wind_speed_mps_max,
        uv_index_max: cityWeather.uv_index_max,
        weather_code: cityWeather.weather_code,
        is_thunderstorm_day: cityWeather.is_thunderstorm_day,
        city_id: cityId,
        timezone: timezone,
        updated_at: nowIso,
      },
      { onConflict: "user_id,date" }
    );

  if (error) {
    throw new Error(`Failed to upsert user weather for ${date}: ${error.message}`);
  }
}

serve(async (req) => {
  console.log("[weather-worker] start", new Date().toISOString());

  try {
    if (req.method !== "POST" && req.method !== "GET") {
      return jsonResponse({ ok: false, error: "Method not allowed" }, 405);
    }

    const supabaseUrl = requireEnv("SUPABASE_URL");
    const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");

    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false },
    });

    const nowIso = new Date().toISOString();
    const MAX_ATTEMPTS = 3;
    const BATCH_SIZE = 20;
    const FORECAST_DAYS = 6;

    // Fetch queued jobs
    const { data: jobs, error: jobsErr } = await supabase
      .from("sync_jobs_weather")
      .select("*")
      .eq("status", "queued")
      .lt("attempts", MAX_ATTEMPTS)
      .order("created_at", { ascending: true })
      .limit(BATCH_SIZE);

    if (jobsErr) throw new Error(`Failed to fetch jobs: ${jobsErr.message}`);

    if (!jobs || jobs.length === 0) {
      console.log("[weather-worker] no queued jobs");
      return jsonResponse({ ok: true, processed: 0 });
    }

    console.log(`[weather-worker] processing ${jobs.length} jobs`);

    const results: { jobId: string; status: string; error?: string; cityName?: string; forecastDays?: number; directFetch?: boolean }[] = [];

    for (const job of jobs) {
      const jobId = job.id;
      const userId = job.user_id;
      const localDate = job.local_date;
      const timezone = job.timezone;

      try {
        // Mark as processing
        await supabase
          .from("sync_jobs_weather")
          .update({ status: "processing", attempts: job.attempts + 1, updated_at: nowIso })
          .eq("id", jobId);

        // Get user's location for this date (or nearby dates)
        const candidates = [localDate, addDaysIsoDate(localDate, -1), addDaysIsoDate(localDate, +1)];
        let userLat: number | null = null;
        let userLon: number | null = null;

        for (const d of candidates) {
          const { data: locData } = await supabase
            .from("user_location_daily")
            .select("latitude, longitude")
            .eq("user_id", userId)
            .eq("date", d)
            .maybeSingle();

          if (locData?.latitude && locData?.longitude) {
            userLat = locData.latitude;
            userLon = locData.longitude;
            break;
          }
        }

        if (userLat == null || userLon == null) {
          throw new Error("No location data found for user");
        }

        // Find nearest city
        const nearestCity = await findNearestCity(supabase, userLat, userLon);
        if (!nearestCity) {
          throw new Error("No cities found in database");
        }

        console.log(`[weather-worker] job=${jobId} user=${userId} city=${nearestCity.name} (${nearestCity.distance.toFixed(1)}km)`);

        // Get weather for this city and date
        let { data: cityWeather, error: weatherErr } = await supabase
          .from("city_weather_daily")
          .select("*")
          .eq("city_id", nearestCity.cityId)
          .eq("day", localDate)
          .maybeSingle();

        if (weatherErr) {
          throw new Error(`Failed to fetch city weather: ${weatherErr.message}`);
        }

        // ── NEW: If no city weather data, fetch directly from Open-Meteo ──
        let directFetched = false;
        if (!cityWeather) {
          console.log(`[weather-worker] job=${jobId} no city_weather_daily for city=${nearestCity.cityId} date=${localDate}, fetching from Open-Meteo`);
          const fetched = await fetchAndStoreCityWeather(
            supabase,
            nearestCity.cityId,
            nearestCity.lat,
            nearestCity.lon,
            nearestCity.timezone
          );

          if (fetched) {
            directFetched = true;
            // Re-read from city_weather_daily now that it's populated
            const { data: retryWeather } = await supabase
              .from("city_weather_daily")
              .select("*")
              .eq("city_id", nearestCity.cityId)
              .eq("day", localDate)
              .maybeSingle();

            cityWeather = retryWeather;
          }
        }

        if (!cityWeather) {
          // Still no data even after direct fetch (date too far in past for Open-Meteo free tier)
          await supabase
            .from("sync_jobs_weather")
            .update({ status: "done", updated_at: nowIso })
            .eq("id", jobId);

          results.push({ jobId, status: "no_weather_data", cityName: nearestCity.name, directFetch: directFetched });
          continue;
        }

        // Upsert today's weather
        await upsertUserWeatherDay(supabase, userId, cityWeather, nearestCity.cityId, localDate, timezone, nowIso);

        // ========== FORECAST: copy future days ==========
        let forecastCount = 0;
        const forecastEnd = addDaysIsoDate(localDate, FORECAST_DAYS);

        const { data: forecastRows, error: forecastErr } = await supabase
          .from("city_weather_daily")
          .select("*")
          .eq("city_id", nearestCity.cityId)
          .gt("day", localDate)
          .lte("day", forecastEnd)
          .order("day", { ascending: true });

        if (!forecastErr && forecastRows && forecastRows.length > 0) {
          for (const fRow of forecastRows) {
            try {
              await upsertUserWeatherDay(
                supabase, userId, fRow, nearestCity.cityId,
                fRow.day, timezone, nowIso
              );
              forecastCount++;
            } catch (e: any) {
              console.warn(`[weather-worker] forecast upsert failed for ${fRow.day}: ${e.message}`);
            }
          }
          console.log(`[weather-worker] job=${jobId} copied ${forecastCount} forecast days`);
        }

        // ========== BACKFILL: copy past days (for backfill jobs) ==========
        const backfillStart = addDaysIsoDate(localDate, -13); // 14 days total including localDate

        const { data: pastRows, error: pastErr } = await supabase
          .from("city_weather_daily")
          .select("*")
          .eq("city_id", nearestCity.cityId)
          .gte("day", backfillStart)
          .lt("day", localDate)
          .order("day", { ascending: true });

        let backfillCount = 0;
        if (!pastErr && pastRows && pastRows.length > 0) {
          for (const pRow of pastRows) {
            try {
              await upsertUserWeatherDay(
                supabase, userId, pRow, nearestCity.cityId,
                pRow.day, timezone, nowIso
              );
              backfillCount++;
            } catch (e: any) {
              console.warn(`[weather-worker] backfill upsert failed for ${pRow.day}: ${e.message}`);
            }
          }
          console.log(`[weather-worker] job=${jobId} backfilled ${backfillCount} past days`);
        }

        // Mark job as done
        await supabase
          .from("sync_jobs_weather")
          .update({ status: "done", updated_at: nowIso })
          .eq("id", jobId);

        results.push({ jobId, status: "done", cityName: nearestCity.name, forecastDays: forecastCount, directFetch: directFetched });
        console.log(`[weather-worker] job=${jobId} done (forecast=${forecastCount}, backfill=${backfillCount}, directFetch=${directFetched})`);

      } catch (e: any) {
        console.error(`[weather-worker] job=${jobId} error:`, e);

        const newStatus = job.attempts + 1 >= MAX_ATTEMPTS ? "failed" : "queued";
        await supabase
          .from("sync_jobs_weather")
          .update({
            status: newStatus,
            last_error: String(e?.message ?? e),
            updated_at: nowIso,
          })
          .eq("id", jobId);

        results.push({ jobId, status: "error", error: String(e?.message ?? e) });
      }
    }

    const summary = {
      total: results.length,
      done: results.filter((r) => r.status === "done").length,
      noWeatherData: results.filter((r) => r.status === "no_weather_data").length,
      errors: results.filter((r) => r.status === "error").length,
    };

    console.log("[weather-worker] done", summary);

    return jsonResponse({ ok: true, summary, results });

  } catch (e: any) {
    console.error("[weather-worker] error", e);
    return jsonResponse({ ok: false, error: String(e?.message ?? e) }, 500);
  }
});