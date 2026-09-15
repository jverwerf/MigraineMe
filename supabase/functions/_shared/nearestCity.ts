// FILE: supabase/functions/_shared/nearestCity.ts
//
// Resolve the weather city for a user's coordinates.
//
// The `city` table is a fixed list of ~3k cities. Users far from all of them
// (Hawaii, Shetland, ...) used to fall through to "closest of 50 arbitrary
// cities" and got weather from another continent (Keaau HI -> Aguascalientes MX).
//
// Now: nearest existing city within MAX_CITY_KM, otherwise a new city row is
// created at the user's rounded coordinates. fetch_bucket picks it up like any
// other city once user_weather_daily references it.

import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

// deno-lint-ignore no-explicit-any
type Db = SupabaseClient<any, "public", any>;

export const MAX_CITY_KM = 50;
// 0.1 deg ~ 11 km: close enough for weather, coarse enough not to store a home address.
const ROUND_DEG = 0.1;

export type ResolvedCity = {
  cityId: number;
  name: string;
  lat: number;
  lon: number;
  timezone: string | null;
  distance: number;
  created: boolean;
};

export function haversineKm(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371;
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

async function nearestWithin(
  supabase: Db,
  lat: number,
  lon: number,
): Promise<Omit<ResolvedCity, "created"> | null> {
  const dLat = MAX_CITY_KM / 111;
  const dLon = Math.min(180, MAX_CITY_KM / (111 * Math.max(0.05, Math.cos((lat * Math.PI) / 180))));

  const { data: cities, error } = await supabase
    .from("city")
    .select("id, name, lat, lon, timezone")
    .gte("lat", lat - dLat)
    .lte("lat", lat + dLat)
    .gte("lon", lon - dLon)
    .lte("lon", lon + dLon);

  if (error) throw new Error(`city lookup failed: ${error.message}`);

  let best: Omit<ResolvedCity, "created"> | null = null;
  for (const c of cities ?? []) {
    const dist = haversineKm(lat, lon, c.lat, c.lon);
    if (dist <= MAX_CITY_KM && (!best || dist < best.distance)) {
      best = { cityId: c.id, name: c.name, lat: c.lat, lon: c.lon, timezone: c.timezone, distance: dist };
    }
  }
  return best;
}

function gmtOffsetLabel(seconds: number): string {
  const sign = seconds < 0 ? "-" : "+";
  const abs = Math.abs(seconds);
  const h = String(Math.floor(abs / 3600)).padStart(2, "0");
  const m = String(Math.floor((abs % 3600) / 60)).padStart(2, "0");
  return `GMT${sign}${h}:${m}`;
}

async function fetchWithTimeout(url: string, init: RequestInit = {}, ms = 5000): Promise<Response> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), ms);
  try {
    return await fetch(url, { ...init, signal: ctrl.signal });
  } finally {
    clearTimeout(timer);
  }
}

// Best-effort place name. The row works without it, so failures fall back to coordinates.
async function reverseGeocode(lat: number, lon: number): Promise<{ name: string | null; country: string | null }> {
  try {
    const u = new URL("https://nominatim.openstreetmap.org/reverse");
    u.searchParams.set("lat", String(lat));
    u.searchParams.set("lon", String(lon));
    u.searchParams.set("format", "jsonv2");
    u.searchParams.set("zoom", "13"); // town level; 10 returns counties/regions
    u.searchParams.set("accept-language", "en");
    const resp = await fetchWithTimeout(u.toString(), {
      headers: { "User-Agent": "MigraineMe/1.0 (help@migraineme.app)" },
    });
    if (!resp.ok) return { name: null, country: null };
    const j = await resp.json();
    const a = j?.address ?? {};
    const name = a.city ?? a.town ?? a.village ?? a.hamlet ?? a.suburb ?? a.municipality ?? a.county ?? null;
    return { name: name || null, country: a.country ?? null };
  } catch {
    return { name: null, country: null };
  }
}

async function createCity(
  supabase: Db,
  lat: number,
  lon: number,
): Promise<ResolvedCity> {
  // Timezone + offset from Open-Meteo, same source the weather comes from.
  const om = new URL("https://api.open-meteo.com/v1/forecast");
  om.searchParams.set("latitude", String(lat));
  om.searchParams.set("longitude", String(lon));
  om.searchParams.set("current", "temperature_2m");
  om.searchParams.set("timezone", "auto");
  const resp = await fetchWithTimeout(om.toString(), { headers: { accept: "application/json" } });
  if (!resp.ok) throw new Error(`open-meteo timezone lookup ${resp.status}`);
  const j = await resp.json();
  const timezone: string | null = j?.timezone ?? null;
  const gmtOffset = typeof j?.utc_offset_seconds === "number" ? gmtOffsetLabel(j.utc_offset_seconds) : null;

  const place = await reverseGeocode(lat, lon);
  const name = place.name ?? `${lat.toFixed(1)}, ${lon.toFixed(1)}`;

  // Least-populated bucket, so fetch_bucket runs stay evenly sized.
  // Count per bucket with head requests: a plain select is capped at 1000 rows.
  const counts = await Promise.all(
    Array.from({ length: 12 }, async (_, i) => {
      const { count } = await supabase.from("city").select("id", { count: "exact", head: true }).eq("bucket", i + 1);
      return { bucket: i + 1, count: count ?? Infinity };
    }),
  );
  const bucket = counts.sort((a, b) => a.count - b.count)[0].bucket;

  const { data: row, error } = await supabase
    .from("city")
    .insert({ name, country: place.country ?? "", lat, lon, timezone, gmt_offset: gmtOffset, bucket })
    .select("id, name, lat, lon, timezone")
    .single();
  if (error) throw new Error(`city insert failed: ${error.message}`);

  // Two jobs racing for the same spot: keep the oldest row, drop ours.
  const { data: twins } = await supabase
    .from("city")
    .select("id, name, lat, lon, timezone")
    .eq("lat", lat)
    .eq("lon", lon)
    .order("id", { ascending: true });
  const keeper = twins?.[0];
  if (keeper && keeper.id !== row.id) {
    await supabase.from("city").delete().eq("id", row.id);
    return { cityId: keeper.id, name: keeper.name, lat: keeper.lat, lon: keeper.lon, timezone: keeper.timezone, distance: 0, created: false };
  }

  console.log(`[nearestCity] created city id=${row.id} "${row.name}" (${lat}, ${lon}) tz=${timezone} bucket=${bucket}`);
  return { cityId: row.id, name: row.name, lat: row.lat, lon: row.lon, timezone: row.timezone, distance: 0, created: true };
}

/**
 * Nearest city within MAX_CITY_KM of (lat, lon); creates one at the rounded
 * coordinates when there is none. Returns null only if creation fails.
 */
export async function resolveCity(
  supabase: Db,
  lat: number,
  lon: number,
): Promise<ResolvedCity | null> {
  const existing = await nearestWithin(supabase, lat, lon);
  if (existing) return { ...existing, created: false };

  const rLat = Math.round(lat / ROUND_DEG) * ROUND_DEG;
  const rLon = Math.round(lon / ROUND_DEG) * ROUND_DEG;
  const roundedLat = Number(rLat.toFixed(1));
  const roundedLon = Number(rLon.toFixed(1));

  try {
    const created = await createCity(supabase, roundedLat, roundedLon);
    return { ...created, distance: haversineKm(lat, lon, roundedLat, roundedLon) };
  } catch (e: any) {
    console.error(`[nearestCity] could not create city for (${roundedLat}, ${roundedLon}): ${e.message}`);
    return null;
  }
}
