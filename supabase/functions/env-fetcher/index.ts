// FILE: supabase/functions/env-fetcher/index.ts
//
// Daily pollen + air-quality fetcher for BOTH Supabase projects. Deployed ONLY
// to MigraineMe; it also serves MeSeries/VertigoMe (secret
// MESERIES_SERVICE_ROLE_KEY), so a city both apps use costs one API call.
//
// Active places = union over both projects of (user_city_daily last 30d ∪
// city_weather_daily last 7d), matched by coordinates, not id: the two city
// tables started identical but each project auto-creates its own rows since
// 09-14, so the same place can carry different ids. Each place is fetched once
// and every row is written to each project that has the place.
//
// Per active place:
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
import { createClient, SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

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
type Target = { name: string; sb: SupabaseClient };
// One physical place: the id it carries in each project that has it.
type Place = City & { ids: Map<string, number> };

const MESERIES_URL = "https://vpwnhpwiwxwoyjfytiye.supabase.co";
const PAGE = 1000;

// ~11 m: identical seed rows and 0.1-degree auto-created rows collapse onto one key.
const placeKey = (lat: number, lon: number) => `${lat.toFixed(4)},${lon.toFixed(4)}`;

// PostgREST caps a select at 1000 rows, so every multi-row read is paged.
async function pagedIds(sb: SupabaseClient, table: string, col: string, dateCol: string, since: string, extra?: (q: any) => any): Promise<number[]> {
  const out = new Set<number>();
  for (let from = 0; ; from += PAGE) {
    let q = sb.from(table).select(col).gte(dateCol, since).not(col, "is", null)
      .order(col).order(dateCol).range(from, from + PAGE - 1);
    if (extra) q = extra(q);
    const { data, error } = await q;
    if (error) throw new Error(`${table}: ${error.message}`);
    for (const r of (data ?? []) as unknown as Record<string, number>[]) out.add(r[col]);
    if (!data || data.length < PAGE) break;
  }
  return [...out];
}

async function citiesById(sb: SupabaseClient, ids: number[]): Promise<City[]> {
  const out: City[] = [];
  for (let i = 0; i < ids.length; i += 200) {
    const { data, error } = await sb.from("city")
      .select("id, lat, lon, pollen_source, pollen_source_checked_at")
      .in("id", ids.slice(i, i + 200));
    if (error) throw new Error(`city: ${error.message}`);
    out.push(...((data ?? []) as City[]));
  }
  return out;
}

serve(async (req) => {
  const started = Date.now();
  const url = new URL(req.url);
  const limit = Number(url.searchParams.get("limit")) || 0;
  const onlyCity = Number(url.searchParams.get("city_id")) || 0;

  const opts = { auth: { persistSession: false } };
  const targets: Target[] = [
    { name: "migraineme", sb: createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, opts) },
  ];
  const meseriesKey = Deno.env.get("MESERIES_SERVICE_ROLE_KEY");
  if (meseriesKey) targets.push({ name: "meseries", sb: createClient(MESERIES_URL, meseriesKey, opts) });
  const googleKey = Deno.env.get("GOOGLE_POLLEN_KEY") ?? "";

  // ---- active places, unioned over both projects ----
  const since30 = new Date(Date.now() - 30 * 864e5).toISOString().slice(0, 10);
  const since7 = new Date(Date.now() - 7 * 864e5).toISOString().slice(0, 10);
  const todayIso = new Date().toISOString().slice(0, 10);
  const places = new Map<string, Place>();
  // "migraineme:123" -> place key, for the google-done lookup below
  const keyOf = new Map<string, string>();
  const googleDoneIn = new Map<string, Set<string>>(); // place key -> projects with today's google row
  const active: Record<string, number> = {};
  for (const t of targets) {
    let ids: number[];
    if (onlyCity && t.name === "migraineme") ids = [onlyCity];
    else if (onlyCity) ids = [];
    else {
      const [a, b] = await Promise.all([
        pagedIds(t.sb, "user_city_daily", "city_id", "date", since30),
        pagedIds(t.sb, "city_weather_daily", "city_id", "day", since7),
      ]);
      ids = [...new Set([...a, ...b])];
    }
    active[t.name] = ids.length;
    for (const c of await citiesById(t.sb, ids)) {
      const k = placeKey(c.lat, c.lon);
      const p = places.get(k) ?? { ...c, ids: new Map<string, number>() };
      // keep the most recently checked pollen source across projects
      if (c.pollen_source_checked_at && (!p.pollen_source_checked_at || c.pollen_source_checked_at > p.pollen_source_checked_at)) {
        p.pollen_source = c.pollen_source;
        p.pollen_source_checked_at = c.pollen_source_checked_at;
      }
      p.ids.set(t.name, c.id);
      places.set(k, p);
      keyOf.set(`${t.name}:${c.id}`, k);
    }
    // which of its cities already have a google row for today (skip re-spending quota)
    const g = await pagedIds(t.sb, "city_pollen_daily", "city_id", "day", todayIso, (q) => q.eq("source", "google"));
    for (const id of g) {
      const k = keyOf.get(`${t.name}:${id}`);
      if (!k) continue;
      if (!googleDoneIn.has(k)) googleDoneIn.set(k, new Set());
      googleDoneIn.get(k)!.add(t.name);
    }
  }
  let cities = [...places.values()];
  if (limit) cities = cities.slice(0, limit);
  if (!cities.length) return json({ ok: true, cities: 0, note: "no active cities", active });

  // write the same rows (city_id swapped) into every project that has the place
  async function upsertAll(table: string, place: Place, rows: Record<string, unknown>[]) {
    for (const t of targets) {
      const id = place.ids.get(t.name);
      if (id == null) continue;
      const { error } = await t.sb.from(table).upsert(rows.map((r) => ({ ...r, city_id: id })));
      if (error) throw new Error(`${t.name} ${table}: ${error.message}`);
    }
  }
  async function setSource(place: Place, source: string) {
    for (const t of targets) {
      const id = place.ids.get(t.name);
      if (id == null) continue;
      await t.sb.from("city").update({ pollen_source: source, pollen_source_checked_at: new Date().toISOString() }).eq("id", id);
    }
  }

  let googleCalls = 0;
  let airRows = 0, pollenRows = 0, srcUpdates = 0;
  const errors: string[] = [];

  async function handleCity(city: Place) {
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
        await upsertAll("city_air_daily", city, airUpserts);
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
        await upsertAll("city_pollen_daily", city, pollenUpserts);
        pollenRows += pollenUpserts.length;
        if (city.pollen_source !== "open-meteo") {
          await setSource(city, "open-meteo");
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
      const done = googleDoneIn.get(placeKey(city.lat, city.lon));
      if (done && [...city.ids.keys()].every((n) => done.has(n))) return; // today already fetched everywhere
      if (googleCalls >= MAX_GOOGLE_CALLS) return;

      googleCalls++;
      const g = await fetch(
        `https://pollen.googleapis.com/v1/forecast:lookup?key=${googleKey}` +
          `&location.latitude=${city.lat}&location.longitude=${city.lon}&days=1`,
        { signal: AbortSignal.timeout(15000) },
      );
      if (g.status === 400 || g.status === 404) {
        await setSource(city, "none");
        srcUpdates++;
        return;
      }
      if (!g.ok) throw new Error(`google ${g.status}`);
      const gBody = await g.json();
      const dayInfo = gBody.dailyInfo?.[0];
      if (!dayInfo?.pollenTypeInfo?.length) {
        await setSource(city, "none");
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
      await upsertAll("city_pollen_daily", city, [{
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
      pollenRows++;
      if (city.pollen_source !== "google") {
        await setSource(city, "google");
        srcUpdates++;
      }
    } catch (e) {
      if (errors.length < 10) errors.push(`city ${city.id}: ${(e as Error).message}`);
    }
  }

  // simple concurrency pool
  const queue = [...cities];
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
    cities: cities.length,
    active,
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
