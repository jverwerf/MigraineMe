// supabase/functions/upsert-whoop-token/index.ts
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

type MetricToggle = { metric: string; enabled: boolean };

type Body = {
  access_token?: string;
  refresh_token?: string;
  token_type?: string;
  expires_at?: string | null;
  expires_in?: number | null;  // WHOOP OAuth returns this (seconds until expiry)
  metric_settings?: MetricToggle[] | null;
};

type ResolveRow = {
  city_id: number;
  timezone: string | null;
  resolved_date?: string | null;
};

type SyncJobInsert = {
  job_type: string;
  user_id: string;
  local_date: string;
  status: string;
  updated_at: string;
  created_by: string;
  timezone: string;
  city_id: number | null;
};

const WHOOP_METRICS = [
  "recovery_score_daily",
  "resting_hr_daily",
  "hrv_daily",
  "skin_temp_daily",
  "spo2_daily",
  "sleep_duration_daily",
  "sleep_score_daily",
  "sleep_efficiency_daily",
  "sleep_disturbances_daily",
  "sleep_stages_daily",
  "fell_asleep_time_daily",
  "woke_up_time_daily",
  "activity_hr_zones_sessions",
  "hr_zones_daily",
  "steps_daily",
  "time_in_high_hr_zones_daily",
  "stress_index_daily",
] as const;

const WHOOP_METRIC_SET = new Set<string>(WHOOP_METRICS as unknown as string[]);

function addDaysIsoDate(isoDate: string, deltaDays: number): string {
  const [y, m, d] = isoDate.split("-").map((x) => Number(x));
  const dt = new Date(Date.UTC(y, m - 1, d, 12, 0, 0));
  dt.setUTCDate(dt.getUTCDate() + deltaDays);
  return dt.toISOString().slice(0, 10);
}

function dateRangeInclusive(startIso: string, endIso: string) {
  const out: string[] = [];
  let cur = startIso;
  for (let guard = 0; guard < 500; guard++) {
    out.push(cur);
    if (cur === endIso) break;
    cur = addDaysIsoDate(cur, +1);
  }
  return out;
}

function getLocalTimeParts(timeZone: string, now = new Date()) {
  const fmt = new Intl.DateTimeFormat("en-GB", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  });

  const parts = fmt.formatToParts(now);
  const get = (type: string) => parts.find((p) => p.type === type)?.value;

  const year = get("year");
  const month = get("month");
  const day = get("day");
  const hour = get("hour");
  const minute = get("minute");

  if (!year || !month || !day || !hour || !minute) {
    throw new Error(`Failed to compute local time parts for timezone=${timeZone}`);
  }

  const localDate = `${year}-${month}-${day}`;
  const hh = Number(hour);
  const mm = Number(minute);

  return { localDate, hh, mm };
}

async function resolveWithDateFallback(
  supabase: ReturnType<typeof createClient>,
  userId: string,
  utcIsoDate: string,
): Promise<{ row: ResolveRow | null; usedDate: string | null }> {
  const candidates = [addDaysIsoDate(utcIsoDate, -1), utcIsoDate, addDaysIsoDate(utcIsoDate, +1)];

  for (const d of candidates) {
    const { data, error } = await supabase.rpc("resolve_user_city_for_date", {
      p_user_id: userId,
      p_date: d,
    });

    if (error) {
      throw new Error(`resolve_user_city_for_date failed for date=${d}: ${error.message}`);
    }

    const row: ResolveRow | undefined = Array.isArray(data) ? data[0] : data;
    if (row?.timezone) return { row, usedDate: row.resolved_date ?? d };
  }

  return { row: null, usedDate: null };
}

function normalizeClientMetricToggles(raw: unknown): { applied: MetricToggle[]; ignored: string[] } {
  if (!Array.isArray(raw)) return { applied: [], ignored: [] };

  const applied: MetricToggle[] = [];
  const ignored: string[] = [];

  for (const item of raw) {
    const metric = typeof (item as any)?.metric === "string" ? String((item as any).metric).trim() : "";
    const enabled = (item as any)?.enabled;

    if (!metric || typeof enabled !== "boolean") {
      ignored.push(metric || "(invalid)");
      continue;
    }

    if (!WHOOP_METRIC_SET.has(metric)) {
      ignored.push(metric);
      continue;
    }

    applied.push({ metric, enabled });
  }

  const map = new Map<string, boolean>();
  for (const t of applied) map.set(t.metric, t.enabled);
  return {
    applied: Array.from(map.entries()).map(([metric, enabled]) => ({ metric, enabled })),
    ignored,
  };
}

function sanitizeTokenField(raw: unknown): string {
  if (typeof raw !== "string") return "";
  return raw.replace(/\r?\n/g, "").replace(/\r/g, "").trim();
}

// FIXED: Now handles both expires_at (ISO string) and expires_in (seconds)
function computeExpiresAt(expiresAtRaw: unknown, expiresInRaw: unknown, nowUtc: Date): string {
  // First try expires_at (ISO timestamp string)
  if (expiresAtRaw != null && typeof expiresAtRaw === "string") {
    const s = expiresAtRaw.trim();
    if (s) {
      const ms = new Date(s).getTime();
      if (!Number.isNaN(ms) && ms > nowUtc.getTime()) {
        return new Date(ms).toISOString();
      }
    }
  }

  // Then try expires_in (seconds until expiry) - this is what WHOOP OAuth returns
  if (expiresInRaw != null) {
    const seconds = typeof expiresInRaw === "number" ? expiresInRaw : Number(expiresInRaw);
    if (!Number.isNaN(seconds) && seconds > 0) {
      return new Date(nowUtc.getTime() + seconds * 1000).toISOString();
    }
  }

  // Default: 1 hour from now (WHOOP tokens typically last ~1 hour)
  console.log("[upsert-whoop-token] No valid expires_at or expires_in provided, defaulting to 1 hour");
  return new Date(nowUtc.getTime() + 3600 * 1000).toISOString();
}

// Fetch WHOOP user ID from their API - WITH LOGGING
async function fetchWhoopUserId(accessToken: string): Promise<number | null> {
  try {
    console.log("[upsert-whoop-token] fetching WHOOP user profile...");
    
    const res = await fetch("https://api.prod.whoop.com/developer/v2/user/profile/basic", {
      headers: {
        Authorization: `Bearer ${accessToken}`,
        Accept: "application/json",
      },
    });

    console.log("[upsert-whoop-token] WHOOP profile response status:", res.status);

    if (!res.ok) {
      const text = await res.text();
      console.log("[upsert-whoop-token] WHOOP profile error:", text);
      return null;
    }

    const data = await res.json();
    console.log("[upsert-whoop-token] WHOOP profile data:", JSON.stringify(data));
    
    const whoopUserId = data?.user_id;
    
    if (typeof whoopUserId === "number") {
      return whoopUserId;
    }
    
    return null;
  } catch (e) {
    console.log("[upsert-whoop-token] fetch error:", (e as Error).message);
    return null;
  }
}

serve(async (req) => {
  console.log("[upsert-whoop-token] start", {
    nowUtc: new Date().toISOString(),
    method: req.method,
    url: req.url,
  });

  const supabaseUrl = requireEnv("SUPABASE_URL");
  const anonKey = requireEnv("SUPABASE_ANON_KEY");
  const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");

  const admin = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false },
    global: { headers: { "X-Client-Info": "upsert-whoop-token-admin" } },
  });

  async function audit(fn: string, userId: string | null, ok: boolean, stage: string, message: string | null) {
    try {
      await admin.from("edge_audit").insert({ fn, user_id: userId, ok, stage, message });
    } catch (e) {
      console.log("[upsert-whoop-token] audit failed", e);
    }
  }

  try {
    if (req.method !== "POST") {
      await audit("upsert-whoop-token", null, false, "method_not_allowed", req.method);
      return jsonResponse({ ok: false, error: "Method not allowed" }, 405);
    }

    const authHeader = req.headers.get("Authorization") ?? "";
    if (!authHeader.toLowerCase().startsWith("bearer ")) {
      await audit("upsert-whoop-token", null, false, "missing_auth", "Authorization header missing/invalid");
      return jsonResponse({ ok: false, error: "Unauthorized" }, 401);
    }

    const jwt = authHeader.slice(7);
    const userClient = createClient(supabaseUrl, anonKey, {
      auth: { persistSession: false },
      global: { headers: { Authorization: `Bearer ${jwt}` } },
    });

    const { data: userData, error: userErr } = await userClient.auth.getUser();
    if (userErr || !userData?.user?.id) {
      await audit("upsert-whoop-token", null, false, "auth_failed", userErr?.message ?? "no user");
      return jsonResponse({ ok: false, error: "Unauthorized" }, 401);
    }

    const userId = userData.user.id;
    await audit("upsert-whoop-token", userId, true, "auth_ok", null);

    const body = (await req.json().catch(() => null)) as Body | null;

    const access = sanitizeTokenField(body?.access_token);
    const refresh = sanitizeTokenField(body?.refresh_token);

    if (!access || !refresh) {
      await audit("upsert-whoop-token", userId, false, "bad_body", "missing access_token or refresh_token");
      return jsonResponse({ ok: false, error: "Missing access_token/refresh_token" }, 400);
    }

    const tokenType = sanitizeTokenField(body?.token_type) || "Bearer";

    const nowUtc = new Date();
    const nowIso = nowUtc.toISOString();
    const utcDate = nowIso.slice(0, 10);

    // FIXED: Now handles both expires_at and expires_in
    const expiresAt = computeExpiresAt(body?.expires_at, body?.expires_in, nowUtc);

    // Fetch WHOOP user ID for webhook mapping
    const whoopUserId = await fetchWhoopUserId(access);
    if (whoopUserId) {
      await audit("upsert-whoop-token", userId, true, "whoop_user_id_fetched", `whoop_user_id=${whoopUserId}`);
    } else {
      await audit("upsert-whoop-token", userId, false, "whoop_user_id_fetch_failed", "see logs");
    }

    // Upsert WHOOP tokens
    const tokenData: Record<string, unknown> = {
      user_id: userId,
      access_token: access,
      refresh_token: refresh,
      token_type: tokenType,
      expires_at: expiresAt,
      updated_at: nowIso,
      created_at: nowIso,
    };

    if (whoopUserId) {
      tokenData.whoop_user_id = whoopUserId;
    }

    const { error: upsertErr } = await admin.from("whoop_tokens").upsert(tokenData, { onConflict: "user_id" });

    if (upsertErr) {
      await audit("upsert-whoop-token", userId, false, "whoop_tokens_upsert_failed", upsertErr.message);
      return jsonResponse({ ok: false, error: upsertErr.message }, 500);
    }

    await audit("upsert-whoop-token", userId, true, "whoop_tokens_upsert_ok", `expires_at=${expiresAt}`);

    // Parse client-provided toggles
    const { applied: clientMetricToggles, ignored: ignoredClientMetrics } = normalizeClientMetricToggles(
      (body as any)?.metric_settings,
    );

    // Enable all WHOOP metrics
    let enabledCount = 0;
    const enableRows = WHOOP_METRICS.map((metric) => ({
      user_id: userId,
      metric,
      enabled: true,
      preferred_source: "whoop",
      allowed_sources: ["whoop"],
      updated_at: nowIso,
    }));

    const { error: enableErr } = await admin.from("metric_settings").upsert(enableRows, {
      onConflict: "user_id,metric",
    });

    if (!enableErr) {
      enabledCount = WHOOP_METRICS.length;
      await audit("upsert-whoop-token", userId, true, "metric_enable_ok", `enabled=${enabledCount}`);
    }

    // Enqueue backfill
    let enqueuedJobs = 0;
    try {
      const resolved = await resolveWithDateFallback(admin, userId, utcDate);
      const row = resolved.row;

      if (row?.timezone) {
        const tz = row.timezone;
        const cityId = typeof row.city_id === "number" ? row.city_id : null;
        const { localDate } = getLocalTimeParts(tz, nowUtc);

        const startLocalDate = addDaysIsoDate(localDate, -29);
        const endLocalDate = localDate;
        const days = dateRangeInclusive(startLocalDate, endLocalDate);

        const { data: alreadyRan } = await admin
          .from("backend_daily_runs")
          .select("local_date")
          .eq("user_id", userId)
          .in("local_date", days);

        const ranSet = new Set((alreadyRan ?? []).map((r: any) => String(r.local_date)));

        const jobs: SyncJobInsert[] = [];
        for (const d of days) {
          if (ranSet.has(d)) continue;
          jobs.push({
            job_type: "whoop_daily",
            user_id: userId,
            local_date: d,
            status: "queued",
            updated_at: nowIso,
            created_by: "whoop-connect",
            timezone: tz,
            city_id: cityId,
          });
        }

        if (jobs.length) {
          await admin.from("sync_jobs").upsert(jobs, {
            onConflict: "job_type,user_id,local_date",
            ignoreDuplicates: true,
          });
          enqueuedJobs = jobs.length;
          await audit("upsert-whoop-token", userId, true, "backfill_enqueued", `jobs=${enqueuedJobs}`);
        }
      }
    } catch (e) {
      await audit("upsert-whoop-token", userId, false, "backfill_exception", (e as Error).message);
    }

    return jsonResponse({
      ok: true,
      userId,
      whoopUserId,
      enqueuedJobs,
      expiresAt,
      metricSettings: { enabled: enabledCount },
    });
  } catch (e) {
    const msg = (e as Error).message;
    console.error("[upsert-whoop-token] exception", e);
    return jsonResponse({ ok: false, error: msg }, 500);
  }
});