// FILE: supabase/functions/env-fetcher/index.ts
//
// Daily pollen + air-quality fetcher. Runs identically in the MigraineMe and
// MeSeries/VertigoMe projects; each instance serves its own database.
//
// Per active city (user_city_daily last 30d ∪ city_weather_daily last 7d):
//   1. One Open-Meteo air-quality call: PM/gases (global) + 6 pollen species (Europe only).
//   2. Upsert city_air_daily (yesterday + today, local dates).
//   3. Pollen present  -> convert grains/m3 to the Google UPI 0-5 scale, upsert
//      city_pollen_daily, pollen_source='open-meteo'.
//   4. Pollen null     -> Google Pollen API (unless source='none' checked <30d ago,
//      or today's google row already exists). 404/400 -> pollen_source='none'.
// Nulls stay null, never 0. Google quota is capped at 160/day project-side; this
// worker additionally stops after MAX_GOOGLE_CALLS per run and skips cities whose
// google row for today already exists, so re-invocations are near-free.

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

const MAX_GOOGLE_CALLS = 120;
const CONCURRENCY = 6;

// grains/m3 that count as one full "high" for each species.
// Calibrated 2026-08-19 against Google UPI on London/Berlin/Madrid (weed 12.9/20 -> 2 etc).
const HIGH: Record<string, number> = {
  alder_pollen: 100,
  birch_pollen: 100,
  olive_pollen: 200,
  grass_pollen: 30,
  mugwort_pollen: 20,
  ragweed_pollen: 15,
};
const GROUPS: Record<string, string[]> = {
  tree: ["alder_pollen", "birch_pollen", "olive_pollen"],
  grass: ["grass_pollen"],
  weed: ["mugwort_pollen", "ragweed_pollen"],
};

function bandRatio(r: number): number {
  if (r <= 0) return 0;
  if (r < 0.4) return 1;
  if (r < 1.0) return 2;
  if (r < 2.0) return 3;
  if (r < 4.0) return 4;
  return 5;
}

function mean(xs: number[]): number | null {
  return xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : null;
}
function max(xs: number[]): number | null {
  return xs.length ? Math.max(...xs) : null;
}

type City = { id: number; lat: number; lon: number; pollen_source: string | null; pollen_source_checked_at: string | null };

serve(async (req) => {
  const started = Date.now();
  const url = new URL(req.url);
  const limit = Number(url.searchParams.get("limit")) || 0;
  const onlyCity = Number(url.searchParams.get("city_id")) || 0;

  const sb = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    { auth: { persistSession: false } },
  );
  const googleKey = Deno.env.get("GOOGLE_POLLEN_KEY") ?? "";

  // ---- active city set (own DB) ----
  const ids = new Set<number>();
  {
    const since30 = new Date(Date.now() - 30 * 864e5).toISOString().slice(0, 10);
    const since7 = new Date(Date.now() - 7 * 864e5).toISOString().slice(0, 10);
    const [a, b] = await Promise.all([
      sb.from("user_city_daily").select("city_id").gte("date", since30),
      sb.from("city_weather_daily").select("city_id").gte("day", since7),
    ]);
    for (const r of a.data ?? []) ids.add(r.city_id);
    for (const r of b.data ?? []) ids.add(r.city_id);
  }
  let cityIds = [...ids];
  if (onlyCity) cityIds = [onlyCity];
  if (limit) cityIds = cityIds.slice(0, limit);
  if (!cityIds.length) return json({ ok: true, cities: 0, note: "no active cities" });

  const { data: cities, error: cErr } = await sb
    .from("city")
    .select("id, lat, lon, pollen_source, pollen_source_checked_at")
    .in("id", cityIds);
  if (cErr) return json({ ok: false, error: cErr.message }, 500);

  // which cities already have a google row for today (skip re-spending quota)
  const todayIso = new Date().toISOString().slice(0, 10);
  const { data: gToday } = await sb
    .from("city_pollen_daily")
    .select("city_id")
    .eq("day", todayIso)
    .eq("source", "google");
  const googleDone = new Set((gToday ?? []).map((r) => r.city_id));

  let googleCalls = 0;
  let airRows = 0, pollenRows = 0, srcUpdates = 0;
  const errors: string[] = [];

  async function handleCity(city: City) {
    try {
      // ---- 1. Open-Meteo: AQ + pollen in one call ----
      const om = new URL("https://air-quality-api.open-meteo.com/v1/air-quality");
      om.searchParams.set("latitude", String(city.lat));
      om.searchParams.set("longitude", String(city.lon));
      om.searchParams.set("hourly", [
        "pm2_5", "pm10", "dust", "ozone", "nitrogen_dioxide", "sulphur_dioxide",
        ...Object.keys(HIGH),
      ].join(","));
      om.searchParams.set("past_days", "1");
      om.searchParams.set("forecast_days", "1");
      om.searchParams.set("timezone", "auto");
      const resp = await fetch(om, { signal: AbortSignal.timeout(15000) });
      if (!resp.ok) throw new Error(`open-meteo ${resp.status}`);
      const body = await resp.json();
      const h = body.hourly ?? {};
      const times: string[] = h.time ?? [];

      // bucket hour indices by local date
      const byDay = new Map<string, number[]>();
      times.forEach((t: string, i: number) => {
        const d = t.slice(0, 10);
        if (!byDay.has(d)) byDay.set(d, []);
        byDay.get(d)!.push(i);
      });

      const pick = (key: string, idxs: number[]) =>
        idxs.map((i) => h[key]?.[i]).filter((v: unknown) => v !== null && v !== undefined) as number[];

      // ---- 2. air rows ----
      const airUpserts = [];
      for (const [day, idxs] of byDay) {
        airUpserts.push({
          city_id: city.id,
          day,
          pm2_5_mean: mean(pick("pm2_5", idxs)),
          pm2_5_max: max(pick("pm2_5", idxs)),
          pm10_mean: mean(pick("pm10", idxs)),
          pm10_max: max(pick("pm10", idxs)),
          dust_max: max(pick("dust", idxs)),
          ozone_max: max(pick("ozone", idxs)),
          no2_mean: mean(pick("nitrogen_dioxide", idxs)),
          so2_mean: mean(pick("sulphur_dioxide", idxs)),
          updated_at: new Date().toISOString(),
        });
      }
      if (airUpserts.length) {
        const { error } = await sb.from("city_air_daily").upsert(airUpserts);
        if (error) throw new Error(`air upsert: ${error.message}`);
        airRows += airUpserts.length;
      }

      // ---- 3. pollen via Open-Meteo? ----
      const hasOmPollen = Object.keys(HIGH).some((k) =>
        (h[k] ?? []).some((v: unknown) => v !== null && v !== undefined)
      );

      if (hasOmPollen) {
        const pollenUpserts = [];
        for (const [day, idxs] of byDay) {
          const grains: Record<string, number | null> = {};
          const idx: Record<string, number> = {};
          for (const [group, species] of Object.entries(GROUPS)) {
            let ratio = 0, gMax: number | null = null, any = false;
            for (const s of species) {
              const vs = pick(s, idxs);
              if (!vs.length) continue;
              any = true;
              const m = Math.max(...vs);
              ratio += m / HIGH[s];
              gMax = gMax === null ? m : Math.max(gMax, m);
            }
            grains[group] = any ? gMax : null;
            idx[group] = any ? bandRatio(ratio) : 0;
          }
          pollenUpserts.push({
            city_id: city.id,
            day,
            tree_index: idx.tree,
            grass_index: idx.grass,
            weed_index: idx.weed,
            overall_index: Math.max(idx.tree, idx.grass, idx.weed),
            tree_grains_m3: grains.tree,
            grass_grains_m3: grains.grass,
            weed_grains_m3: grains.weed,
            source: "open-meteo",
            updated_at: new Date().toISOString(),
          });
        }
        const { error } = await sb.from("city_pollen_daily").upsert(pollenUpserts);
        if (error) throw new Error(`pollen upsert: ${error.message}`);
        pollenRows += pollenUpserts.length;
        if (city.pollen_source !== "open-meteo") {
          await sb.from("city").update({
            pollen_source: "open-meteo",
            pollen_source_checked_at: new Date().toISOString(),
          }).eq("id", city.id);
          srcUpdates++;
        }
        return;
      }

      // ---- 4. Google fallback ----
      if (!googleKey) return;
      const staleDays = city.pollen_source_checked_at
        ? (Date.now() - Date.parse(city.pollen_source_checked_at)) / 864e5
        : Infinity;
      if (city.pollen_source === "none" && staleDays < 30) return; // rechecked monthly
      if (googleDone.has(city.id)) return; // today already fetched
      if (googleCalls >= MAX_GOOGLE_CALLS) return;

      googleCalls++;
      const g = await fetch(
        `https://pollen.googleapis.com/v1/forecast:lookup?key=${googleKey}` +
          `&location.latitude=${city.lat}&location.longitude=${city.lon}&days=1`,
        { signal: AbortSignal.timeout(15000) },
      );
      if (g.status === 400 || g.status === 404) {
        await sb.from("city").update({
          pollen_source: "none",
          pollen_source_checked_at: new Date().toISOString(),
        }).eq("id", city.id);
        srcUpdates++;
        return;
      }
      if (!g.ok) throw new Error(`google ${g.status}`);
      const gBody = await g.json();
      const dayInfo = gBody.dailyInfo?.[0];
      if (!dayInfo?.pollenTypeInfo?.length) {
        await sb.from("city").update({
          pollen_source: "none",
          pollen_source_checked_at: new Date().toISOString(),
        }).eq("id", city.id);
        srcUpdates++;
        return;
      }
      const d = dayInfo.date;
      const day = `${d.year}-${String(d.month).padStart(2, "0")}-${String(d.day).padStart(2, "0")}`;
      const idx: Record<string, number | null> = { TREE: null, GRASS: null, WEED: null };
      for (const t of dayInfo.pollenTypeInfo) {
        // entry present without indexInfo = Google says "no pollen now" -> 0
        idx[t.code] = t.indexInfo?.value ?? 0;
      }
      const vals = [idx.TREE, idx.GRASS, idx.WEED].filter((v) => v !== null) as number[];
      const { error } = await sb.from("city_pollen_daily").upsert([{
        city_id: city.id,
        day,
        tree_index: idx.TREE,
        grass_index: idx.GRASS,
        weed_index: idx.WEED,
        overall_index: vals.length ? Math.max(...vals) : null,
        tree_grains_m3: null,
        grass_grains_m3: null,
        weed_grains_m3: null,
        source: "google",
        updated_at: new Date().toISOString(),
      }]);
      if (error) throw new Error(`google upsert: ${error.message}`);
      pollenRows++;
      if (city.pollen_source !== "google") {
        await sb.from("city").update({
          pollen_source: "google",
          pollen_source_checked_at: new Date().toISOString(),
        }).eq("id", city.id);
        srcUpdates++;
      }
    } catch (e) {
      if (errors.length < 10) errors.push(`city ${city.id}: ${(e as Error).message}`);
    }
  }

  // simple concurrency pool
  const queue = [...(cities ?? [])] as City[];
  await Promise.all(
    Array.from({ length: CONCURRENCY }, async () => {
      while (queue.length) {
        const c = queue.shift();
        if (c) await handleCity(c);
      }
    }),
  );

  return json({
    ok: true,
    cities: (cities ?? []).length,
    air_rows: airRows,
    pollen_rows: pollenRows,
    google_calls: googleCalls,
    source_updates: srcUpdates,
    errors,
    ms: Date.now() - started,
  });
});

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
