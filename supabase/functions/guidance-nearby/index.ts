// FILE: supabase/functions/guidance-nearby/index.ts
//
// The "near you" half of the Guidance list: practices around the patient that
// actually treat migraine or headache, shown under our own practitioner cards
// in the same list.
//
// Two entry points:
//   default          a patient opened Guidance. Serves the cache, and searches
//                    inline only when this area has never been searched or has
//                    gone past Google's 30 day retention limit.
//   {mode:"refresh"} the nightly cron. Re-searches areas someone actually
//                    opened in the last 30 days, verifies what is new, and
//                    reconciles what came back.
//
// The waterfall, in order:
//   1. Google, with phone and website, while inside the free Enterprise
//      allowance (1000 calls a month).
//   2. Google on the cheap fields, inside the free Pro allowance (5000).
//   3. The model builds the candidate list itself from web search.
// Google narrows; it never decides. Every candidate's own website is read by
// the model before it is shown, and only a site that says migraine or
// headache gets a listing in a migraine app. That check also fills in the
// phone, the website and a one-line description, all of which are ours to
// keep, so the cache slowly stops depending on Google at all.
//
// A person opening Guidance somewhere new ALWAYS gets a search. Budget rules
// only ever apply to the nightly refresh.

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

// Google may not hold non-id fields past 30 days. Refresh at 25 so the nightly
// cron has room to get there before anything actually expires.
const STALE_DAYS = 25;
const HARD_EXPIRY_DAYS = 30;

const ENTERPRISE_FREE_CALLS = 1000;
const PRO_FREE_CALLS = 5000;
// How much of the month's free Google allowance the nightly refresh may spend.
// The rest is held back for people opening Guidance somewhere new.
const REFRESH_BUDGET_SHARE = 0.6;

// Verification: how many sites to read at once, and how long a live request
// will wait for it before returning what it has and finishing in the
// background.
const VERIFY_CONCURRENCY = 8;
const INLINE_VERIFY_BUDGET_MS = 22000;
const REFRESH_VERIFY_LIMIT = 80;
// A practice with no findable website is not asked about again for this long.
const REVERIFY_DAYS = 90;

const OPENAI_MODEL = "gpt-4.1-mini";
const NOMINATIM_UA = "MigraineMe guidance-nearby (help@migraineme.app)";

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

// ── Small helpers ───────────────────────────────────────────────────────────

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
  return `${Math.floor(lat / CELL_DEGREES)}_${Math.floor(lng / CELL_DEGREES)}`;
}

function cellCentre(key: string): { lat: number; lng: number } {
  const [y, x] = key.split("_").map(Number);
  return { lat: (y + 0.5) * CELL_DEGREES, lng: (x + 0.5) * CELL_DEGREES };
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

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function fetchWithTimeout(url: string, init: RequestInit, ms: number): Promise<Response> {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), ms);
  try {
    return await fetch(url, { ...init, signal: ctl.signal });
  } finally {
    clearTimeout(t);
  }
}

async function sha1(s: string): Promise<string> {
  const buf = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(s));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

// Run `fn` over `items` with at most `limit` in flight, stopping the launch of
// new work once `deadline` has passed. Returns how many finished.
async function pool<T>(items: T[], limit: number, deadline: number, fn: (t: T) => Promise<void>): Promise<number> {
  let next = 0;
  let done = 0;
  async function worker() {
    while (next < items.length && Date.now() < deadline) {
      const item = items[next++];
      try { await fn(item); } catch (e) { console.error(`[guidance-nearby] pool: ${e instanceof Error ? e.message : e}`); }
      done++;
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return done;
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
  "places.id", "places.displayName", "places.formattedAddress",
  "places.location", "places.types", "places.businessStatus",
];
const ENTERPRISE_FIELDS = [...PRO_FIELDS, "places.nationalPhoneNumber", "places.websiteUri"];

async function textSearch(apiKey: string, query: string, lat: number, lng: number, withContact: boolean): Promise<GooglePlace[]> {
  const res = await fetchWithTimeout("https://places.googleapis.com/v1/places:searchText", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Goog-Api-Key": apiKey,
      "X-Goog-FieldMask": (withContact ? ENTERPRISE_FIELDS : PRO_FIELDS).join(","),
    },
    body: JSON.stringify({
      textQuery: query,
      maxResultCount: PER_QUERY_RESULTS,
      locationBias: { circle: { center: { latitude: lat, longitude: lng }, radius: SEARCH_RADIUS_M } },
    }),
  }, 10000);
  if (!res.ok) throw new Error(`places:searchText ${res.status} ${(await res.text()).slice(0, 300)}`);
  const data = await res.json();
  return (data.places ?? []) as GooglePlace[];
}

function keepPlace(p: GooglePlace): boolean {
  if (!p.id || !p.location) return false;
  if (p.businessStatus === "CLOSED_PERMANENTLY") return false;
  const name = (p.displayName?.text ?? "").toLowerCase();
  if (!name) return false;
  if (NAME_BLOCKLIST.some((w) => name.includes(w))) return false;
  return (p.types ?? []).some((t) => HEALTH_TYPES.has(t));
}

// The town or district, taken off the formatted address rather than bought as
// its own field. Google puts it second from the end, before the country.
function cityFromAddress(address: string | undefined): string | null {
  if (!address) return null;
  const parts = address.split(",").map((s) => s.trim()).filter(Boolean);
  if (parts.length < 2) return null;
  const candidate = parts[parts.length - 2];
  // Strip a trailing postcode. Two passes: a full code ("Bristol BS6 6UP"),
  // then a bare outcode ("Bristol BS6"), which Google returns on its own often
  // enough to matter.
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
    model: data?.model_calls ?? 0,
  };
}

async function recordCalls(db: SupabaseClient, enterprise: number, pro: number, model: number) {
  const u = await monthUsage(db);
  await db.from("nearby_api_usage").upsert({
    month: u.month,
    enterprise_calls: u.enterprise + enterprise,
    pro_calls: u.pro + pro,
    model_calls: u.model + model,
    updated_at: new Date().toISOString(),
  }, { onConflict: "month" });
}

// ── The model ───────────────────────────────────────────────────────────────

/** One call to OpenAI's Responses API. With `search` the model may browse. */
async function askModel(db: SupabaseClient, input: string, search: boolean): Promise<string> {
  const res = await fetchWithTimeout("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${requireEnv("OPENAI_API_KEY")}`,
    },
    body: JSON.stringify({
      model: OPENAI_MODEL,
      input,
      ...(search ? { tools: [{ type: "web_search_preview" }] } : {}),
    }),
  }, 45000);
  await recordCalls(db, 0, 0, 1);
  if (!res.ok) throw new Error(`openai ${res.status} ${(await res.text()).slice(0, 300)}`);
  const data = await res.json();
  const parts: string[] = [];
  for (const item of data.output ?? []) {
    if (item.type !== "message") continue;
    for (const c of item.content ?? []) if (c.type === "output_text" && c.text) parts.push(c.text);
  }
  return parts.join("\n");
}

/** The first JSON value in a reply, tolerating prose and code fences around it. */
function firstJson(text: string): unknown {
  const start = Math.min(...["[", "{"].map((ch) => { const i = text.indexOf(ch); return i < 0 ? Infinity : i; }));
  if (!Number.isFinite(start)) return null;
  for (let end = text.length; end > start; end--) {
    const ch = text[end - 1];
    if (ch !== "]" && ch !== "}") continue;
    try { return JSON.parse(text.slice(start, end)); } catch { /* keep trimming */ }
  }
  return null;
}

/** A page as plain text, capped, or null when it cannot be read. */
async function pageText(url: string): Promise<string | null> {
  try {
    const res = await fetchWithTimeout(url, {
      headers: { "User-Agent": "Mozilla/5.0 (compatible; MigraineMe/1.0; +https://migraineme.app)" },
      redirect: "follow",
    }, 9000);
    if (!res.ok) return null;
    const type = res.headers.get("content-type") ?? "";
    if (!type.includes("html") && !type.includes("text")) return null;
    const html = await res.text();
    return html
      .replace(/<script[\s\S]*?<\/script>/gi, " ")
      .replace(/<style[\s\S]*?<\/style>/gi, " ")
      .replace(/<[^>]+>/g, " ")
      .replace(/&nbsp;/g, " ")
      .replace(/&amp;/g, "&")
      .replace(/\s+/g, " ")
      .trim()
      .slice(0, 16000);
  } catch {
    return null;
  }
}

const STOPWORDS = new Set(["the", "and", "of", "for", "clinic", "centre", "center", "ltd", "limited", "dr", "mr", "mrs", "ms", "practice", "health", "therapy", "physio", "physiotherapy"]);

function nameTokens(name: string): string[] {
  return name.toLowerCase().replace(/[^a-z0-9\s]/g, " ").split(/\s+/)
    .filter((t) => t.length >= 3 && !STOPWORDS.has(t));
}

/** Does this page plausibly belong to this practice? At least two distinctive
 *  words of the name, or the whole name when it only has one. */
function pageMatchesName(text: string, name: string): boolean {
  const lower = text.toLowerCase();
  const tokens = nameTokens(name);
  if (tokens.length === 0) return false;
  const hits = tokens.filter((t) => lower.includes(t)).length;
  return tokens.length === 1 ? hits === 1 : hits >= 2;
}

const digits = (s: string) => s.replace(/\D/g, "");

type Verdict = {
  treats_migraine: boolean;
  migraine_note: string | null;
  phone: string | null;
  description: string | null;
};

/**
 * Read a practice's site and decide. The one rule that matters: a phone number
 * is accepted only if its digits literally appear in the page. The model may
 * summarise; it may not invent a number a person will dial.
 */
async function judgeSite(db: SupabaseClient, name: string, text: string): Promise<Verdict | null> {
  const prompt =
`You are reading the website of "${name}", a health practice. Below is the page text.

Answer in JSON only, with exactly these keys:
{
  "treats_migraine": true or false. True ONLY if the page itself says they treat, help with, or specialise in migraine or headache (cervicogenic headache, tension headache, vestibular migraine all count). A general physio or psychology page that never mentions headache is false.
  "migraine_note": if true, one plain sentence in the practice's own terms on what they do for migraine or headache, under 160 characters. Else null.
  "phone": the main phone number, copied EXACTLY as it appears on the page, or null if none appears. Never guess.
  "description": one neutral sentence, under 160 characters, on what the practice is. No marketing adjectives.
}

PAGE TEXT:
${text}`;
  const reply = await askModel(db, prompt, false);
  const v = firstJson(reply) as Partial<Verdict> | null;
  if (!v || typeof v.treats_migraine !== "boolean") return null;
  let phone = typeof v.phone === "string" && v.phone.trim() ? v.phone.trim() : null;
  if (phone && (digits(phone).length < 6 || !digits(text).includes(digits(phone)))) phone = null;
  return {
    treats_migraine: v.treats_migraine,
    migraine_note: v.treats_migraine && typeof v.migraine_note === "string" ? v.migraine_note.trim().slice(0, 200) : null,
    phone,
    description: typeof v.description === "string" ? v.description.trim().slice(0, 200) : null,
  };
}

/** Find a practice's website when Google did not give us one. Accepted only if
 *  the page it points at carries the practice's name. */
async function findWebsite(db: SupabaseClient, name: string, address: string | null): Promise<string | null> {
  const reply = await askModel(db,
`Find the official website of the health practice "${name}"${address ? `, ${address}` : ""}.
Reply with JSON only: {"website": "https://..."} or {"website": null} if you cannot find their own site.
Directory pages (Yelp, Doctolib, Google Maps, NHS listings, Facebook) do not count.`, true);
  const v = firstJson(reply) as { website?: unknown } | null;
  const url = typeof v?.website === "string" ? v.website.trim() : "";
  if (!/^https?:\/\//i.test(url)) return null;
  const text = await pageText(url);
  return text && pageMatchesName(text, name) ? url : null;
}

type PlaceRow = {
  place_id: string;
  name: string;
  address: string | null;
  website: string | null;
  website_source: string | null;
  phone: string | null;
};

/**
 * The gate every listing passes through before it is shown. Reads the site,
 * records the verdict, and keeps whatever it learned: phone and website found
 * here are ours, not Google's, so they never expire.
 */
async function verifyPlace(db: SupabaseClient, p: PlaceRow) {
  const now = new Date().toISOString();
  const patch: Record<string, unknown> = { enriched_at: now };

  let website = p.website;
  let text = website ? await pageText(website) : null;
  if (website && text && !pageMatchesName(text, p.name)) {
    // Google's website field is sometimes a hospital group's homepage, or a
    // booking portal. Treat it as missing and go looking.
    text = null;
  }
  if (!text) {
    const found = await findWebsite(db, p.name, p.address);
    if (found) {
      website = found;
      text = await pageText(found);
      patch.website = found;
      patch.website_source = "site";
      patch.website_fetched_at = now;
    }
  }

  if (!text) {
    // Nothing to read means nothing to verify. Not shown, and not asked
    // about again for a while.
    patch.treats_migraine = null;
    await db.from("nearby_places").update(patch).eq("place_id", p.place_id);
    return;
  }

  const v = await judgeSite(db, p.name, text);
  if (!v) {
    await db.from("nearby_places").update(patch).eq("place_id", p.place_id);
    return;
  }

  patch.treats_migraine = v.treats_migraine;
  patch.migraine_note = v.migraine_note;
  patch.description = v.description;
  patch.description_source = "site";
  patch.description_fetched_at = now;
  if (v.phone) {
    patch.phone = v.phone;
    patch.phone_source = "site";
    patch.phone_fetched_at = now;
  }
  // A Google-sourced website that checked out is now also ours: the page was
  // read and matched, which is a better claim to it than a bought field.
  if (website && p.website_source === "google") {
    patch.website_source = "site";
    patch.website_fetched_at = now;
  }
  await db.from("nearby_places").update(patch).eq("place_id", p.place_id);
}

/** Verify what is unverified around a point. Live requests pass a deadline
 *  and get back what finished; the caller sends the rest to the background. */
async function verifyAround(db: SupabaseClient, lat: number, lng: number, limit: number, deadline: number): Promise<{ done: number; remaining: number }> {
  const dLat = SERVE_RADIUS_KM / 111;
  const dLng = SERVE_RADIUS_KM / (111 * Math.max(Math.cos(lat * Math.PI / 180), 0.1));
  const retryBefore = new Date(Date.now() - REVERIFY_DAYS * 86400000).toISOString();

  const { data } = await db
    .from("nearby_places")
    .select("place_id,name,address,website,website_source,phone,lat,lng")
    .is("delisted_at", null)
    .or(`enriched_at.is.null,enriched_at.lt.${retryBefore}`)
    .gte("lat", lat - dLat).lte("lat", lat + dLat)
    .gte("lng", lng - dLng).lte("lng", lng + dLng)
    .limit(limit);

  const rows = (data ?? [])
    .map((r) => ({ ...r, km: haversineKm(lat, lng, r.lat, r.lng) }))
    .sort((a, b) => a.km - b.km);

  const done = await pool(rows, VERIFY_CONCURRENCY, deadline, (r) => verifyPlace(db, r));
  return { done, remaining: rows.length - done };
}

// ── Model discovery, when Google is spent ───────────────────────────────────

async function nominatim(path: string): Promise<Record<string, unknown> | null> {
  try {
    const res = await fetchWithTimeout(`https://nominatim.openstreetmap.org/${path}`, {
      headers: { "User-Agent": NOMINATIM_UA, "Accept": "application/json" },
    }, 10000);
    if (!res.ok) return null;
    const data = await res.json();
    return Array.isArray(data) ? (data[0] ?? null) : data;
  } catch {
    return null;
  }
}

async function townFor(lat: number, lng: number): Promise<string | null> {
  const r = await nominatim(`reverse?lat=${lat}&lon=${lng}&zoom=10&format=json`);
  const a = (r?.address ?? {}) as Record<string, string>;
  const town = a.city ?? a.town ?? a.village ?? a.county ?? null;
  return town ? `${town}${a.country ? ", " + a.country : ""}` : null;
}

/**
 * Step 3 of the waterfall. Costs a few cents per discipline, has no place_id
 * and only as good a location as the address geocodes to, so it runs only
 * when both Google allowances are gone. Every entry still goes through
 * verifyPlace before anyone sees it.
 */
async function modelDiscover(db: SupabaseClient, key: string, lat: number, lng: number, disciplines: { key: string; query: string }[]): Promise<Map<string, Record<string, unknown>>> {
  const town = await townFor(lat, lng);
  const found = new Map<string, Record<string, unknown>>();
  if (!town) return found;

  for (const d of disciplines) {
    let reply = "";
    try {
      reply = await askModel(db,
`List up to ${PER_QUERY_RESULTS} real, currently operating practices matching "${d.query}" within 25 km of ${town}, that treat migraine or headache.
Reply with JSON only: an array of {"name": "...", "address": "street, town, postcode", "website": "https://..." or null}.
Only include practices you actually found on the web. No hospitals' general switchboards, no directories.`, true);
    } catch (e) {
      console.error(`[guidance-nearby] ${key} model ${d.key}: ${e instanceof Error ? e.message : e}`);
      continue;
    }
    const list = firstJson(reply);
    if (!Array.isArray(list)) continue;

    for (const item of list) {
      const name = typeof item?.name === "string" ? item.name.trim() : "";
      const address = typeof item?.address === "string" ? item.address.trim() : "";
      if (!name || !address) continue;
      if (NAME_BLOCKLIST.some((w) => name.toLowerCase().includes(w))) continue;

      const id = "model:" + (await sha1(`${name.toLowerCase()}|${address.toLowerCase()}`)).slice(0, 24);
      const existing = found.get(id);
      if (existing) {
        (existing.disciplines as string[]).push(d.key);
        continue;
      }

      // Nominatim asks for one request a second, and this is the slow path
      // anyway.
      await sleep(1100);
      const g = await nominatim(`search?q=${encodeURIComponent(address)}&format=json&limit=1`);
      const plat = Number(g?.lat), plng = Number(g?.lon);
      if (!Number.isFinite(plat) || !Number.isFinite(plng)) continue;
      if (haversineKm(lat, lng, plat, plng) > KEEP_RADIUS_KM) continue;

      const website = typeof item?.website === "string" && /^https?:\/\//i.test(item.website) ? item.website.trim() : null;
      found.set(id, {
        place_id: id,
        source: "model",
        discovered_in_cell: key,
        disciplines: [d.key],
        name,
        address,
        city: cityFromAddress(address),
        lat: plat,
        lng: plng,
        types: [],
        website,
        website_source: website ? "model" : null,
        website_fetched_at: website ? new Date().toISOString() : null,
        miss_count: 0,
        delisted_at: null,
        last_seen_at: new Date().toISOString(),
      });
    }
  }
  return found;
}

// ── Searching one cell ──────────────────────────────────────────────────────

/**
 * @param forUser someone is waiting on this. A person opening Guidance in a
 *        town nobody has opened before ALWAYS gets a search: an empty section
 *        is the feature not working, and no budget rule is worth that. Only
 *        the nightly refresh yields, and only to keep room for people.
 */
async function searchCell(db: SupabaseClient, apiKey: string, key: string, lat: number, lng: number, forUser: boolean) {
  const { data: disciplines } = await db
    .from("nearby_disciplines")
    .select("key, query")
    .eq("enabled", true)
    .order("sort");
  if (!disciplines?.length) return;

  const usage = await monthUsage(db);
  const needed = disciplines.length;
  const googleUsed = usage.enterprise + usage.pro;

  // The refresh keeps its hands off the last 40% of the month's free calls so
  // that a new town opened on the 28th is still searched by Google.
  if (!forUser && googleUsed + needed > Math.floor(PRO_FREE_CALLS * REFRESH_BUDGET_SHARE)) {
    console.log(`[guidance-nearby] ${key}: refresh yielding, budget reserved for live requests`);
    return;
  }

  // Which step of the waterfall this run is on.
  const withContact = usage.enterprise + needed <= ENTERPRISE_FREE_CALLS;
  const googleAffordable = googleUsed + needed <= PRO_FREE_CALLS;

  const now = new Date().toISOString();
  const rowsById = new Map<string, Record<string, unknown>>();
  let succeeded = 0;

  if (googleAffordable) {
    const seen = new Map<string, { place: GooglePlace; disciplines: Set<string> }>();
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
        if (haversineKm(lat, lng, p.location!.latitude, p.location!.longitude) > KEEP_RADIUS_KM) continue;
        const entry = seen.get(p.id) ?? { place: p, disciplines: new Set<string>() };
        entry.disciplines.add(d.key);
        seen.set(p.id, entry);
      }
    }
    await recordCalls(db, withContact ? succeeded : 0, withContact ? 0 : succeeded, 0);

    for (const { place, disciplines: ds } of seen.values()) {
      const row: Record<string, unknown> = {
        place_id: place.id,
        source: "google",
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
      // A Pro run must leave a phone number an earlier run found, and a
      // verified one (source 'site') is never overwritten by Google at all.
      if (withContact) {
        if (place.nationalPhoneNumber) {
          row.phone = place.nationalPhoneNumber;
          row.phone_source = "google";
          row.phone_fetched_at = now;
        }
        if (place.websiteUri) {
          row.website = place.websiteUri;
          row.website_source = "google";
          row.website_fetched_at = now;
        }
      }
      rowsById.set(place.id, row);
    }
  } else {
    // Both Google allowances spent this month. Step 3.
    const found = await modelDiscover(db, key, lat, lng, disciplines);
    for (const [id, row] of found) rowsById.set(id, row);
    succeeded = found.size > 0 ? disciplines.length : 0;
  }

  // A key that has been revoked, a quota wall, an outage: every query fails and
  // nothing comes back. Reconciling that would read as "every practice in this
  // town has closed" and, two runs later, empty the area. Nothing found is only
  // meaningful when the search actually ran.
  if (succeeded === 0) {
    console.error(`[guidance-nearby] ${key}: every query failed, leaving the area untouched`);
    return;
  }
  // The same argument at discipline granularity: a partial sweep upserts what
  // came back but does not reconcile.
  const complete = succeeded === disciplines.length;

  // What this cell held before, so we can tell a listing that has gone from one
  // that simply was not in this run's results.
  const { data: existing } = await db
    .from("nearby_places")
    .select("place_id, miss_count, phone_source, website_source")
    .eq("discovered_in_cell", key);
  const previous = new Map((existing ?? []).map((r) => [r.place_id as string, r]));

  // Never let a bought field overwrite a verified one.
  for (const [id, row] of rowsById) {
    const prev = previous.get(id);
    if (prev?.phone_source === "site") { delete row.phone; delete row.phone_source; delete row.phone_fetched_at; }
    if (prev?.website_source === "site") { delete row.website; delete row.website_source; delete row.website_fetched_at; }
  }

  if (rowsById.size) {
    const { error } = await db.from("nearby_places").upsert([...rowsById.values()], { onConflict: "place_id" });
    if (error) console.error(`[guidance-nearby] upsert ${key}: ${error.message}`);
  }

  // A single missing result is Google flapping. Two in a row is a closure.
  const missing = complete ? [...previous.keys()].filter((id) => !rowsById.has(id)) : [];
  for (const id of missing) {
    const misses = ((previous.get(id)?.miss_count as number) ?? 0) + 1;
    await db.from("nearby_places").update({ miss_count: misses, delisted_at: misses >= 2 ? now : null }).eq("place_id", id);
  }

  await db.from("nearby_areas").update({ last_searched_at: now }).eq("cell_key", key);
}

// ── Serving a patient ───────────────────────────────────────────────────────

async function placesAround(db: SupabaseClient, lat: number, lng: number) {
  const dLat = SERVE_RADIUS_KM / 111;
  const dLng = SERVE_RADIUS_KM / (111 * Math.max(Math.cos(lat * Math.PI / 180), 0.1));

  const { data } = await db
    .from("nearby_places")
    .select("place_id,source,name,disciplines,types,address,city,lat,lng,phone,website,description,migraine_note,business_status")
    .is("delisted_at", null)
    .eq("treats_migraine", true)
    .gte("lat", lat - dLat).lte("lat", lat + dLat)
    .gte("lng", lng - dLng).lte("lng", lng + dLng);

  return (data ?? [])
    .map((p) => ({
      ...p,
      // The migraine line is the one worth reading in a migraine app.
      description: p.migraine_note ?? p.description,
      distance_km: Number(haversineKm(lat, lng, p.lat, p.lng).toFixed(1)),
    }))
    .filter((p) => p.distance_km <= SERVE_RADIUS_KM)
    .sort((a, b) => a.distance_km - b.distance_km)
    .slice(0, RESULT_LIMIT);
}

async function handlePatient(db: SupabaseClient, apiKey: string, req: Request, body: Record<string, unknown>) {
  const started = Date.now();
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
    // Worth the wait: this is the first person ever to open Guidance here.
    await searchCell(db, apiKey, key, lat, lng, true);
  } else if (age >= STALE_DAYS) {
    // Serve now, refresh behind them.
    EdgeRuntime.waitUntil(searchCell(db, apiKey, key, lat, lng, true));
  }

  // Verify whatever is still unverified nearby, for as long as this request
  // can afford, then hand the rest to the background. The list paints with
  // what passed; the next open has the rest.
  const deadline = started + INLINE_VERIFY_BUDGET_MS;
  const { remaining } = await verifyAround(db, lat, lng, 60, deadline);
  if (remaining > 0) {
    EdgeRuntime.waitUntil(verifyAround(db, lat, lng, 60, Date.now() + 120000));
  }

  return json({ places: await placesAround(db, lat, lng), pending: remaining });
}

// ── The nightly refresh ─────────────────────────────────────────────────────

/**
 * Drop Google-sourced contact details that have reached their retention limit.
 *
 * Google lets us keep a place_id forever and nothing else past 30 days. A row
 * whose area stopped being refreshed, because nobody opens Guidance there any
 * more, would otherwise sit on a phone number we are no longer allowed to
 * hold. The listing itself stays; only the bought fields go. Fields the model
 * verified from the practice's own site are ours and are not touched.
 */
async function expireGoogleFields(db: SupabaseClient) {
  const cutoff = new Date(Date.now() - HARD_EXPIRY_DAYS * 86400000).toISOString();
  const { error: phoneError } = await db.from("nearby_places")
    .update({ phone: null, phone_source: null, phone_fetched_at: null })
    .eq("phone_source", "google").lt("phone_fetched_at", cutoff);
  if (phoneError) console.error(`[guidance-nearby] phone expiry: ${phoneError.message}`);
  const { error: siteError } = await db.from("nearby_places")
    .update({ website: null, website_source: null, website_fetched_at: null })
    .eq("website_source", "google").lt("website_fetched_at", cutoff);
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

  let searched = 0;
  let verified = 0;
  for (const area of due ?? []) {
    await searchCell(db, apiKey, area.cell_key, area.lat, area.lng, false);
    searched++;
    // New candidates from tonight's search, and anything a live request left
    // for the background that never finished.
    const { done } = await verifyAround(db, area.lat, area.lng, REFRESH_VERIFY_LIMIT, Date.now() + 90000);
    verified += done;
  }
  return json({ refreshed: searched, verified });
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
      return await handleRefresh(db, apiKey, Number(body.limit ?? 12));
    }

    return await handlePatient(db, apiKey, req, body);
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e);
    console.error(`[guidance-nearby] ${message}`);
    return json({ error: message }, 500);
  }
});
