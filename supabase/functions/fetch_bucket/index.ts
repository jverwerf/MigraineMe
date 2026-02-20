import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

type City = { id: number; lat: number; lon: number; timezone: string | null };

const SB_URL = "https://qykflarpibofvffmzghi.supabase.co";
const SB_KEY = Deno.env.get("SB_SERVICE_ROLE_KEY")!;

const sb = createClient(SB_URL, SB_KEY);

function bad(status: number, msg: string) {
  return new Response(msg, { status });
}

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

    // ─── Only fetch weather for cities that at least one user uses ────
    const { data: activeCities } = await sb
      .from("user_weather_daily")
      .select("city_id")
      .not("city_id", "is", null);

    const activeCityIds = [...new Set((activeCities ?? []).map((r) => r.city_id))];

    if (activeCityIds.length === 0) {
      return new Response(
        JSON.stringify({ bucket, ok: 0, err: 0, skip: "no_active_cities" }),
        { headers: { "content-type": "application/json" } }
      );
    }

    const { data: cities, error } = await sb
      .from("city")
      .select("id,lat,lon,timezone")
      .eq("bucket", bucket)
      .in("id", activeCityIds)
      .returns<City[]>();

    if (error) return bad(500, error.message);
    if (!cities?.length)
      return new Response(
        JSON.stringify({ bucket, ok: 0, err: 0, skip: "no_active_cities_in_bucket" }),
        { headers: { "content-type": "application/json" } }
      );

    let ok = 0,
      err = 0;
    for (const c of cities) {
      try {
        const tz = c.timezone ?? "auto";
        const u = new URL("https://api.open-meteo.com/v1/forecast");
        u.searchParams.set("latitude", String(c.lat));
        u.searchParams.set("longitude", String(c.lon));
        u.searchParams.set(
          "daily",
          "temperature_2m_min,temperature_2m_max,temperature_2m_mean,surface_pressure_mean,surface_pressure_min,surface_pressure_max,relative_humidity_2m_mean,relative_humidity_2m_min,relative_humidity_2m_max,uv_index_max,wind_speed_10m_mean,wind_speed_10m_max,weathercode"
        );
        u.searchParams.set("past_days", "2");
        u.searchParams.set("forecast_days", "7");
        u.searchParams.set("timezone", tz);

        const resp = await fetch(u.toString(), {
          headers: { accept: "application/json" },
        });
        if (!resp.ok) throw new Error(`open-meteo ${resp.status}`);
        const d = (await resp.json())?.daily;
        if (!d?.time) throw new Error("missing daily block");

        const { error: rpcErr } = await sb.rpc("upsert_city_weather_batch", {
          p_city_id: c.id,
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
        ok++;
      } catch {
        err++;
      }
    }
    return new Response(JSON.stringify({ bucket, ok, err, totalActive: activeCityIds.length }), {
      headers: { "content-type": "application/json" },
    });
  } catch (e) {
    return bad(500, String(e));
  }
});