// FILE: supabase/functions/fetch_bucket/index.ts
//
// Shared city weather fetcher for BOTH Supabase projects. Deployed ONLY to
// MigraineMe; it also serves MeSeries/VertigoMe, so a city both apps use costs
// one Open-Meteo call, and a city only one app uses is still fetched.
//
// Per bucket (cron fetch_bucket_1..12, twice a day):
//   1. Active cities from each project: city_ids in user_weather_daily over the
//      last 30 days (paged, PostgREST caps a select at 1000 rows).
//   2. Union them by coordinates, not id: both city tables started identical,
//      but each project auto-creates its own rows since 09-14 (_shared/nearestCity.ts),
//      so the same place can carry different ids.
//   3. One Open-Meteo call per place, written to every project that has that
//      place through upsert_city_weather_batch (same 15-arg function in both).
//
// MeSeries is reached with the MESERIES_SERVICE_ROLE_KEY secret. Without it the
// function still serves MigraineMe alone.

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient, SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2";

type City = { id: number; lat: number; lon: number; timezone: string | null };
type Target = { name: string; sb: SupabaseClient };
type Place = { lat: number; lon: number; timezone: string | null; ids: Map<string, number> };

const MESERIES_URL = "https://vpwnhpwiwxwoyjfytiye.supabase.co";
const ACTIVE_DAYS = 30;
const PAGE = 1000;

const targets: Target[] = [
  { name: "migraineme", sb: createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SB_SERVICE_ROLE_KEY")!) },
];
const meseriesKey = Deno.env.get("MESERIES_SERVICE_ROLE_KEY");
if (meseriesKey) targets.push({ name: "meseries", sb: createClient(MESERIES_URL, meseriesKey) });

function bad(status: number, msg: string) {
  return new Response(msg, { status });
}

function isoDaysAgo(n: number): string {
  return new Date(Date.now() - n * 86400000).toISOString().slice(0, 10);
}

async function activeCityIds(sb: SupabaseClient): Promise<number[]> {
  const since = isoDaysAgo(ACTIVE_DAYS);
  const ids = new Set<number>();
  for (let from = 0; ; from += PAGE) {
    const { data, error } = await sb
      .from("user_weather_daily")
      .select("city_id")
      .gte("date", since)
      .not("city_id", "is", null)
      .order("id")
      .range(from, from + PAGE - 1);
    if (error) throw new Error(error.message);
    for (const r of data ?? []) ids.add(r.city_id);
    if (!data || data.length < PAGE) break;
  }
  return [...ids];
}

async function bucketCities(sb: SupabaseClient, bucket: number, ids: number[]): Promise<City[]> {
  if (!ids.length) return [];
  const { data, error } = await sb
    .from("city")
    .select("id,lat,lon,timezone")
    .eq("bucket", bucket)
    .in("id", ids)
    .returns<City[]>();
  if (error) throw new Error(error.message);
  return data ?? [];
}

// ~11 m: identical seed rows and 0.1-degree auto-created rows both collapse onto one key.
const placeKey = (lat: number, lon: number) => `${lat.toFixed(4)},${lon.toFixed(4)}`;

serve(async (req) => {
  try {
    // bucket can be in query (?bucket=5) or JSON body { "bucket": 5 }
    const url = new URL(req.url);
    let bucket = Number(url.searchParams.get("bucket"));
    if (!bucket) {
      const body = await req.json().catch(() => ({}));
      bucket = Number(body?.bucket);
    }
    if (!Number.isInteger(bucket) || bucket < 1 || bucket > 12) {
      return bad(400, "bucket=1.12 required");
    }

    const places = new Map<string, Place>();
    const active: Record<string, number> = {};
    for (const t of targets) {
      const ids = await activeCityIds(t.sb);
      active[t.name] = ids.length;
      for (const c of await bucketCities(t.sb, bucket, ids)) {
        const k = placeKey(c.lat, c.lon);
        const p = places.get(k) ?? { lat: c.lat, lon: c.lon, timezone: c.timezone, ids: new Map() };
        p.timezone ??= c.timezone;
        p.ids.set(t.name, c.id);
        places.set(k, p);
      }
    }

    if (!places.size) {
      return new Response(
        JSON.stringify({ bucket, ok: 0, err: 0, skip: "no_active_cities_in_bucket", active }),
        { headers: { "content-type": "application/json" } }
      );
    }

    let ok = 0, err = 0, calls = 0;
    const written: Record<string, number> = {};
    for (const p of places.values()) {
      try {
        const u = new URL("https://api.open-meteo.com/v1/forecast");
        u.searchParams.set("wind_speed_unit", "ms"); // stored in wind_speed_mps_* columns; Open-Meteo defaults to km/h
        u.searchParams.set("latitude", String(p.lat));
        u.searchParams.set("longitude", String(p.lon));
        u.searchParams.set(
          "daily",
          "temperature_2m_min,temperature_2m_max,temperature_2m_mean,surface_pressure_mean,surface_pressure_min,surface_pressure_max,relative_humidity_2m_mean,relative_humidity_2m_min,relative_humidity_2m_max,uv_index_max,wind_speed_10m_mean,wind_speed_10m_max,weathercode"
        );
        u.searchParams.set("past_days", "2");
        u.searchParams.set("forecast_days", "7");
        u.searchParams.set("timezone", p.timezone ?? "auto");

        calls++;
        const resp = await fetch(u.toString(), { headers: { accept: "application/json" } });
        if (!resp.ok) throw new Error(`open-meteo ${resp.status}`);
        const d = (await resp.json())?.daily;
        if (!d?.time) throw new Error("missing daily block");

        for (const t of targets) {
          const cityId = p.ids.get(t.name);
          if (cityId == null) continue;
          const { error: rpcErr } = await t.sb.rpc("upsert_city_weather_batch", {
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
          written[t.name] = (written[t.name] ?? 0) + 1;
        }
        ok++;
      } catch {
        err++;
      }
    }
    return new Response(JSON.stringify({ bucket, ok, err, calls, written, active }), {
      headers: { "content-type": "application/json" },
    });
  } catch (e) {
    return bad(500, String(e));
  }
});
