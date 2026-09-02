// FILE: supabase/functions/guidance-nearby/index.ts
//
// The "near you" half of the Guidance list: practices around the patient,
// shown under our own practitioner cards in the same list.
//
// Two entry points:
//   default          a patient opened Guidance. Serves the cache, and searches
//                    inline only when this area has never been searched or has
//                    gone past Google's 30 day retention limit.
//   {mode:"refresh"} the daily cron. Re-searches areas someone actually opened
//                    in the last 30 days, and reconciles what came back.
//
// Cost rule this whole file is shaped around: Google bills a request at the
// highest tier any requested FIELD belongs to. Name and address are Pro (5000
// free calls a month). Adding phone and website makes the same call Enterprise
// (1000 free). So the field mask is chosen per run from what is left of this
// month's allowance, and running out degrades the data rather than the list.

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient, SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

const CELL_DEGREES = 0.25;        // ~28km of latitude. Bigger cells, fewer searches.
const SEARCH_RADIUS_M = 25000;
const SERVE_RADIUS_KM = 30;
// locationBias is a hint, not a fence: a "headache clinic" search around
// Bristol happily returns London. Storing those means holding rows no serve
// query can ever reach, so they are dropped on the way in.
const KEEP_RADIUS_KM = 35;
const RESULT_LIMIT = 30;
const PER_QUERY_RESULTS = 10;

// Google may not hold non-id fields past 30 days. Refresh at 25 so the daily
// cron has room to get there before anything actually expires.
const STALE_DAYS = 25;
const HARD_EXPIRY_DAYS = 30;

const ENTERPRISE_FREE_CALLS = 1000;
const PRO_FREE_CALLS = 5000;

// Google's own category types, kept because they are what the card's chips
// show. Anything matching none of these is a shop, not a practice.
// Deliberately excludes point_of_interest and establishment: Google puts those
// on everything, so allowing them turns the filter off.
const HEALTH_TYPES = new Set([
  "doctor", "physiotherapist", "chiropractor", "hospital", "medical_clinic",
  "medical_lab", "dental_clinic", "wellness_center", "health",
]);

const NAME_BLOCKLIST = [
  "pharmacy", "apotheke", "apotheek", "pharmacie", "boots", "superdrug",
  "walgreens", "lloyds", "optician", "specsavers", "vet", "dentist",
];

function json(body: unknown, status = 200) {
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

function cellKey(lat: number, lng: number): string {
  const y = Math.floor(lat / CELL_DEGREES);
  const x = Math.floor(lng / CELL_DEGREES);
  return `${y}_${x}`;
}

function cellCentre(key: string): { lat: number; lng: number } {
  const [y, x] = key.split("_").map(Number);
  return {
    lat: (y + 0.5) * CELL_DEGREES,
    lng: (x + 0.5) * CELL_DEGREES,
  };
}

function haversineKm(aLat: number, aLng: number, bLat: number, bLng: number): number {
  const R = 6371;
  const dLat = (bLat - aLat) * Math.PI / 180;
  const dLng = (bLng - aLng) * Math.PI / 180;
  const s = Math.sin(dLat / 2) ** 2 +
    Math.cos(aLat * Math.PI / 180) * Math.cos(bLat * Math.PI / 180) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
}

function daysSince(iso: string | null): number {
  if (!iso) return Number.POSITIVE_INFINITY;
  return (Date.now() - new Date(iso).getTime()) / 86400000;
}

// ── Google ──────────────────────────────────────────────────────────────────

type GooglePlace = {
  id: string;
  displayName?: { text?: string };
  formattedAddress?: string;
  location?: { latitude: number; longitude: number };
  types?: string[];
  businessStatus?: string;
  nationalPhoneNumber?: string;
  websiteUri?: string;
};

const PRO_FIELDS = [
  "places.id",
  "places.displayName",
  "places.formattedAddress",
  "places.location",
  "places.types",
  "places.businessStatus",
];

const ENTERPRISE_FIELDS = [
  ...PRO_FIELDS,
  "places.nationalPhoneNumber",
  "places.websiteUri",
];

async function textSearch(
  apiKey: string,
  query: string,
  lat: number,
  lng: number,
  withContact: boolean,
): Promise<GooglePlace[]> {
  const res = await fetch("https://places.googleapis.com/v1/places:searchText", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Goog-Api-Key": apiKey,
      "X-Goog-FieldMask": (withContact ? ENTERPRISE_FIELDS : PRO_FIELDS).join(","),
    },
    body: JSON.stringify({
      textQuery: query,
      maxResultCount: PER_QUERY_RESULTS,
      locationBias: {
        circle: { center: { latitude: lat, longitude: lng }, radius: SEARCH_RADIUS_M },
      },
    }),
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`places:searchText ${res.status} ${body.slice(0, 300)}`);
  }
  const data = await res.json();
  return (data.places ?? []) as GooglePlace[];
}

function keepPlace(p: GooglePlace): boolean {
  if (!p.id || !p.location) return false;
  if (p.businessStatus === "CLOSED_PERMANENTLY") return false;
  const name = (p.displayName?.text ?? "").toLowerCase();
  if (!name) return false;
  if (NAME_BLOCKLIST.some((w) => name.includes(w))) return false;
  const types = p.types ?? [];
  return types.some((t) => HEALTH_TYPES.has(t));
}

// The town or district, taken off the formatted address rather than bought as
// its own field. Google puts it second from the end, before the country.
function cityFromAddress(address: string | undefined): string | null {
  if (!address) return null;
  const parts = address.split(",").map((s) => s.trim()).filter(Boolean);
  if (parts.length < 2) return null;
  const candidate = parts[parts.length - 2];
  // Strip a trailing postcode, which most locales append to the town. Two
  // passes: a full code ("Bristol BS6 6UP"), then a bare outcode ("Bristol
  // BS6"), which Google returns on its own often enough to matter.
  return candidate
    .replace(/\s+[A-Z0-9]{2,4}\s+[A-Z0-9]{2,3}$/i, "")
    .replace(/\s+(?=[A-Z0-9]*\d)[A-Z0-9]{2,5}$/i, "")
    .trim() || null;
}

// ── Budget ──────────────────────────────────────────────────────────────────

async function monthUsage(db: SupabaseClient) {
  const month = new Date().toISOString().slice(0, 8) + "01";
  const { data } = await db.from("nearby_api_usage").select("*").eq("month", month).maybeSingle();
  return {
    month,
    enterprise: data?.enterprise_calls ?? 0,
    pro: data?.pro_calls ?? 0,
  };
}

async function recordCalls(db: SupabaseClient, month: string, enterprise: number, pro: number) {
  const current = await monthUsage(db);
  await db.from("nearby_api_usage").upsert({
    month,
    enterprise_calls: current.enterprise + enterprise,
    pro_calls: current.pro + pro,
    updated_at: new Date().toISOString(),
  }, { onConflict: "month" });
}

// ── Searching one cell ──────────────────────────────────────────────────────

async function searchCell(db: SupabaseClient, apiKey: string, key: string, lat: number, lng: number) {
  const { data: disciplines } = await db
    .from("nearby_disciplines")
    .select("key, query")
    .eq("enabled", true)
    .order("sort");

  if (!disciplines?.length) return;

  const usage = await monthUsage(db);
  const needed = disciplines.length;
  // Only ask for contact details while the whole run fits inside the free
  // Enterprise allowance. Half a run on each tier would bill for nothing.
  const withContact = usage.enterprise + needed <= ENTERPRISE_FREE_CALLS;
  if (!withContact && usage.pro + needed > PRO_FREE_CALLS) {
    // Both allowances spent. Serve whatever is cached rather than start billing.
    return;
  }

  const now = new Date().toISOString();
  const seen = new Map<string, { place: GooglePlace; disciplines: Set<string> }>();
  let succeeded = 0;

  for (const d of disciplines) {
    let places: GooglePlace[] = [];
    try {
      places = await textSearch(apiKey, d.query, lat, lng, withContact);
      succeeded++;
    } catch (e) {
      console.error(`[guidance-nearby] ${key} ${d.key}: ${e instanceof Error ? e.message : e}`);
      continue;
    }
    for (const p of places) {
      if (!keepPlace(p)) continue;
      const km = haversineKm(lat, lng, p.location!.latitude, p.location!.longitude);
      if (km > KEEP_RADIUS_KM) continue;
      const entry = seen.get(p.id) ?? { place: p, disciplines: new Set<string>() };
      entry.disciplines.add(d.key);
      seen.set(p.id, entry);
    }
  }

  // A key that has been revoked, a quota wall, an outage: every query fails and
  // nothing comes back. Reconciling that would read as "every practice in this
  // town has closed" and, two runs later, empty the area. Nothing found is only
  // meaningful when the search actually ran.
  if (succeeded === 0) {
    console.error(`[guidance-nearby] ${key}: every query failed, leaving the area untouched`);
    return;
  }

  // The same argument at discipline granularity. If the acupuncture query
  // failed, every acupuncturist is missing from `seen` through no fault of
  // their own, and two such runs would delist the lot. Upsert what did come
  // back, but only reconcile when the whole sweep ran.
  const complete = succeeded === disciplines.length;

  await recordCalls(db, usage.month, withContact ? succeeded : 0, withContact ? 0 : succeeded);

  // What this cell held before, so we can tell a listing that has gone from one
  // that simply was not in this run's results.
  const { data: existing } = await db
    .from("nearby_places")
    .select("place_id, miss_count")
    .eq("discovered_in_cell", key);
  const previous = new Map((existing ?? []).map((r) => [r.place_id as string, r.miss_count as number]));

  const rows = [...seen.values()].map(({ place, disciplines: ds }) => {
    const row: Record<string, unknown> = {
      place_id: place.id,
      discovered_in_cell: key,
      disciplines: [...ds],
      name: place.displayName?.text ?? "",
      address: place.formattedAddress ?? null,
      city: cityFromAddress(place.formattedAddress),
      lat: place.location!.latitude,
      lng: place.location!.longitude,
      types: place.types ?? [],
      business_status: place.businessStatus ?? null,
      miss_count: 0,
      delisted_at: null,
      last_seen_at: now,
    };
    // Only overwrite contact details when this run actually bought them.
    // A Pro run must leave a phone number an earlier Enterprise run found.
    if (withContact) {
      row.phone = place.nationalPhoneNumber ?? null;
      row.phone_source = place.nationalPhoneNumber ? "google" : null;
      row.phone_fetched_at = place.nationalPhoneNumber ? now : null;
      row.website = place.websiteUri ?? null;
      row.website_source = place.websiteUri ? "google" : null;
      row.website_fetched_at = place.websiteUri ? now : null;
    }
    return row;
  });

  if (rows.length) {
    const { error } = await db.from("nearby_places").upsert(rows, { onConflict: "place_id" });
    if (error) console.error(`[guidance-nearby] upsert ${key}: ${error.message}`);
  }

  // A single missing result is Google flapping. Two in a row is a closure.
  const missing = complete ? [...previous.keys()].filter((id) => !seen.has(id)) : [];
  for (const id of missing) {
    const misses = (previous.get(id) ?? 0) + 1;
    await db.from("nearby_places").update({
      miss_count: misses,
      delisted_at: misses >= 2 ? now : null,
    }).eq("place_id", id);
  }

  await db.from("nearby_areas").update({ last_searched_at: now }).eq("cell_key", key);
}

// ── Serving a patient ───────────────────────────────────────────────────────

async function placesAround(db: SupabaseClient, lat: number, lng: number) {
  const dLat = SERVE_RADIUS_KM / 111;
  const dLng = SERVE_RADIUS_KM / (111 * Math.max(Math.cos(lat * Math.PI / 180), 0.1));

  const { data } = await db
    .from("nearby_places")
    .select("place_id,name,disciplines,types,address,city,lat,lng,phone,website,description,business_status")
    .is("delisted_at", null)
    .gte("lat", lat - dLat).lte("lat", lat + dLat)
    .gte("lng", lng - dLng).lte("lng", lng + dLng);

  return (data ?? [])
    .map((p) => ({ ...p, distance_km: Number(haversineKm(lat, lng, p.lat, p.lng).toFixed(1)) }))
    .filter((p) => p.distance_km <= SERVE_RADIUS_KM)
    .sort((a, b) => a.distance_km - b.distance_km)
    .slice(0, RESULT_LIMIT);
}

async function handlePatient(db: SupabaseClient, apiKey: string, req: Request, body: Record<string, unknown>) {
  const auth = req.headers.get("Authorization") ?? "";
  const token = auth.replace(/^Bearer\s+/i, "");
  if (!token) return json({ error: "unauthorised" }, 401);

  const { data: userData } = await createClient(
    requireEnv("SUPABASE_URL"),
    requireEnv("SUPABASE_ANON_KEY"),
    { global: { headers: { Authorization: `Bearer ${token}` } } },
  ).auth.getUser();
  const userId = userData?.user?.id;
  if (!userId) return json({ error: "unauthorised" }, 401);

  // An explicit area beats the device: it is what the patient typed when
  // location is off, and it is also how the app previews another town.
  let lat = typeof body.lat === "number" ? body.lat as number : null;
  let lng = typeof body.lng === "number" ? body.lng as number : null;

  if (lat === null || lng === null) {
    const { data: loc } = await db
      .from("user_location_daily")
      .select("latitude, longitude")
      .eq("user_id", userId)
      .order("date", { ascending: false })
      .limit(1)
      .maybeSingle();
    if (!loc) return json({ places: [], reason: "no_location" });
    lat = loc.latitude as number;
    lng = loc.longitude as number;
  }

  const key = cellKey(lat, lng);
  const { data: area } = await db.from("nearby_areas").select("*").eq("cell_key", key).maybeSingle();

  await db.from("nearby_areas").upsert({
    cell_key: key,
    lat: cellCentre(key).lat,
    lng: cellCentre(key).lng,
    last_requested_at: new Date().toISOString(),
    request_count: (area?.request_count ?? 0) + 1,
  }, { onConflict: "cell_key" });

  const age = daysSince(area?.last_searched_at ?? null);

  if (age >= HARD_EXPIRY_DAYS) {
    // Never searched, or everything on it has passed the retention limit.
    // Worth the second: this is the first person ever to open Guidance here.
    await searchCell(db, apiKey, key, lat, lng);
  } else if (age >= STALE_DAYS) {
    // Serve now, refresh behind them.
    EdgeRuntime.waitUntil(searchCell(db, apiKey, key, lat, lng));
  }

  return json({ places: await placesAround(db, lat, lng) });
}

// ── The daily refresh ───────────────────────────────────────────────────────

/**
 * Drop Google-sourced contact details that have reached their retention limit.
 *
 * Google lets us keep a place_id forever and nothing else past 30 days. A row
 * whose area stopped being refreshed, because nobody opens Guidance there any
 * more, would otherwise sit on a phone number we are no longer allowed to
 * hold. The listing itself stays; only the bought fields go.
 */
async function expireGoogleFields(db: SupabaseClient) {
  const cutoff = new Date(Date.now() - HARD_EXPIRY_DAYS * 86400000).toISOString();

  const { error: phoneError } = await db
    .from("nearby_places")
    .update({ phone: null, phone_source: null, phone_fetched_at: null })
    .eq("phone_source", "google")
    .lt("phone_fetched_at", cutoff);
  if (phoneError) console.error(`[guidance-nearby] phone expiry: ${phoneError.message}`);

  const { error: siteError } = await db
    .from("nearby_places")
    .update({ website: null, website_source: null, website_fetched_at: null })
    .eq("website_source", "google")
    .lt("website_fetched_at", cutoff);
  if (siteError) console.error(`[guidance-nearby] website expiry: ${siteError.message}`);
}

async function handleRefresh(db: SupabaseClient, apiKey: string, limit: number) {
  // First, before anything else can fail: the retention limit is a licence
  // term, not a nicety, so it is honoured even on a day when every search
  // errors.
  await expireGoogleFields(db);

  const cutoff = new Date(Date.now() - 30 * 86400000).toISOString();
  const staleBefore = new Date(Date.now() - STALE_DAYS * 86400000).toISOString();

  const { data: due } = await db
    .from("nearby_areas")
    .select("cell_key, lat, lng, last_searched_at")
    .gte("last_requested_at", cutoff)
    .or(`last_searched_at.is.null,last_searched_at.lt.${staleBefore}`)
    .order("last_searched_at", { ascending: true, nullsFirst: true })
    .limit(limit);

  let done = 0;
  for (const area of due ?? []) {
    await searchCell(db, apiKey, area.cell_key, area.lat, area.lng);
    done++;
  }
  return json({ refreshed: done });
}

serve(async (req) => {
  try {
    const db = createClient(requireEnv("SUPABASE_URL"), requireEnv("SUPABASE_SERVICE_ROLE_KEY"));
    const apiKey = requireEnv("GOOGLE_PLACES_API_KEY");
    const body = await req.json().catch(() => ({})) as Record<string, unknown>;

    if (body.mode === "refresh") {
      // Same shape as the other workers: the cron proves itself with the
      // shared secret, never by carrying the service role key through
      // cron.job, where it would sit in plain text.
      if (req.headers.get("x-cron-secret") !== requireEnv("CRON_SECRET")) {
        return json({ error: "unauthorised" }, 401);
      }
      return await handleRefresh(db, apiKey, Number(body.limit ?? 25));
    }

    return await handlePatient(db, apiKey, req, body);
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e);
    console.error(`[guidance-nearby] ${message}`);
    return json({ error: message }, 500);
  }
});
