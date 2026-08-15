// supabase/functions/risk-score-worker/index.ts
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

// ─── Types ───────────────────────────────────────────────────────────────────

type RiskScoreJobRow = {
  id: string;
  user_id: string;
  local_date: string;
  status: string;
  attempts: number;
  locked_at: string | null;
  timezone: string | null;
};

interface DecayWeight {
  severity: string;
  day_0: number; day_1: number; day_2: number; day_3: number;
  day_4: number; day_5: number; day_6: number;
}

interface GaugeThreshold {
  zone: string;
  min_value: number;
}

interface MenstruationDecayWeight {
  day_m7: number; day_m6: number; day_m5: number; day_m4: number;
  day_m3: number; day_m2: number; day_m1: number; day_0: number;
  day_p1: number; day_p2: number; day_p3: number; day_p4: number;
  day_p5: number; day_p6: number; day_p7: number;
}

interface PoolItem {
  label: string;
  prediction_value: string | null;
  category: string | null;
  display_group: string | null;
}

interface EventRow {
  type: string | null;
  start_at: string | null;
}

interface ScoredEvent {
  name: string;
  severity: string;
  eventDate: string;
  category: string | null;
}

interface TriggerScore {
  name: string;
  score: number;
  severity: string;
  days_active: number;
}

interface DayRisk {
  date: string;
  score: number;
  zone: string;
  percent: number;
  top_triggers: TriggerScore[];
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

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

function dateOffset(base: string, days: number): string {
  const d = new Date(base + "T00:00:00Z");
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().substring(0, 10);
}

function toLocalDate(iso: string): string | null {
  try { return iso.substring(0, 10); } catch { return null; }
}

function daysBetween(dateA: string, dateB: string): number {
  const a = new Date(dateA + "T00:00:00Z");
  const b = new Date(dateB + "T00:00:00Z");
  return Math.round((b.getTime() - a.getTime()) / 86400000);
}

function zoneForScore(score: number, high: number, mild: number, low: number): string {
  if (score >= high) return "HIGH";
  if (score >= mild) return "MILD";
  if (score >= low) return "LOW";
  return "NONE";
}

// ─── Gauge alerts ────────────────────────────────────────────────────────────
//
// The gauge is not a once-a-day event: this worker recomputes it every 5
// minutes, so a day can open MILD and turn HIGH by the afternoon. We therefore
// check for a threshold crossing on every write rather than snapshotting once
// in the morning, and lean on public.gauge_alerts to make sure each zone is
// only ever announced once per user per local day.

const ZONE_RANK: Record<string, number> = { NONE: 0, LOW: 1, MILD: 2, HIGH: 3 };

/** Zone we assume when a user has never touched the setting. */
const DEFAULT_GAUGE_THRESHOLD = "MILD";

/** No gauge push before this local hour, even if the day already qualifies. */
const GAUGE_ALERT_MIN_LOCAL_HOUR = 8;

function getLocalDateIn(timeZone: string, now: Date): string | null {
  try {
    const parts = new Intl.DateTimeFormat("en-GB", {
      timeZone, year: "numeric", month: "2-digit", day: "2-digit", hour12: false,
    }).formatToParts(now);
    const get = (t: string) => parts.find((p) => p.type === t)?.value;
    const y = get("year"), m = get("month"), d = get("day");
    return y && m && d ? `${y}-${m}-${d}` : null;
  } catch {
    return null;
  }
}

function getLocalHourIn(timeZone: string, now: Date): number {
  try {
    const parts = new Intl.DateTimeFormat("en-US", {
      timeZone, hour: "numeric", hour12: false,
    }).formatToParts(now);
    const h = parts.find((p) => p.type === "hour")?.value;
    return h === undefined ? -1 : Number(h);
  } catch {
    return -1;
  }
}

/**
 * Push a gauge alert if today just met (or just exceeded) the user's threshold.
 * Returns a short reason string for the job log. Never throws: a failed alert
 * must not fail the risk calculation that produced it.
 */
async function maybeSendGaugeAlert(
  supabase: ReturnType<typeof createClient>,
  job: RiskScoreJobRow,
  zone: string,
  percent: number,
  topTriggers: TriggerScore[],
  nowUtc: Date,
): Promise<string> {
  try {
    const tz = job.timezone;
    if (!tz) return "no_timezone";

    // Only ever alert about the user's actual today. Jobs for a stale date, or
    // for a forecast day, must stay silent.
    if (getLocalDateIn(tz, nowUtc) !== job.local_date) return "not_today";

    const localHour = getLocalHourIn(tz, nowUtc);
    if (localHour < GAUGE_ALERT_MIN_LOCAL_HOUR) return "too_early";

    const rank = ZONE_RANK[zone] ?? 0;
    if (rank === 0) return "zone_none";

    // Preference. An absent row means the user has never touched the setting,
    // which is ON at the default threshold.
    const { data: pref } = await supabase
      .from("notification_settings")
      .select("enabled, threshold")
      .eq("user_id", job.user_id)
      .eq("type", "daily_gauge")
      .maybeSingle();

    if (pref && pref.enabled === false) return "disabled";

    const threshold = String(pref?.threshold ?? DEFAULT_GAUGE_THRESHOLD).toUpperCase();
    const thresholdRank = ZONE_RANK[threshold] ?? ZONE_RANK[DEFAULT_GAUGE_THRESHOLD];
    if (rank < thresholdRank) return "below_threshold";

    // Already announced this zone (or a higher one) today?
    const { data: already } = await supabase
      .from("gauge_alerts")
      .select("zone")
      .eq("user_id", job.user_id)
      .eq("local_date", job.local_date)
      .maybeSingle();

    const alreadyRank = already ? (ZONE_RANK[String(already.zone)] ?? 0) : 0;
    if (alreadyRank >= rank) return "already_sent";

    const { data: prof } = await supabase
      .from("profiles")
      .select("fcm_token")
      .eq("user_id", job.user_id)
      .maybeSingle();

    if (!prof?.fcm_token) return "no_token";

    // Claim the slot before sending. If the push then fails the user misses one
    // alert, which is far better than a retry loop pushing the same zone twice.
    const { error: markErr } = await supabase
      .from("gauge_alerts")
      .upsert({
        user_id: job.user_id,
        local_date: job.local_date,
        zone,
        sent_at: nowUtc.toISOString(),
      }, { onConflict: "user_id,local_date" });

    if (markErr) return `mark_failed:${markErr.message}`;

    await supabase.functions.invoke("send-fcm-push", {
      body: {
        type: "daily_gauge",
        tokens: [prof.fcm_token],
        // FCM data values must be strings.
        data: {
          zone,
          percent: String(percent),
          top_trigger: topTriggers?.[0]?.name ?? "",
          // Lets the app say "risk just went up" instead of "today's risk".
          escalated: alreadyRank > 0 ? "1" : "0",
        },
      },
    });

    return "sent";
  } catch (e) {
    console.error("[risk-score-worker] gauge alert failed", {
      userId: job.user_id, error: (e as Error).message,
    });
    return `error:${(e as Error).message}`;
  }
}

function getMenstruationWeight(mWeights: MenstruationDecayWeight, offset: number): number {
  if (offset < -7 || offset > 7) return 0;
  const key = offset < 0
    ? `day_m${Math.abs(offset)}` as keyof MenstruationDecayWeight
    : offset === 0
      ? "day_0" as keyof MenstruationDecayWeight
      : `day_p${offset}` as keyof MenstruationDecayWeight;
  return mWeights[key] ?? 0;
}

async function mapLimit<T, R>(
  items: T[], limit: number, fn: (item: T, idx: number) => Promise<R>,
): Promise<R[]> {
  const results: R[] = new Array(items.length);
  let nextIndex = 0;
  async function worker() {
    while (true) {
      const i = nextIndex++;
      if (i >= items.length) return;
      results[i] = await fn(items[i], i);
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, () => worker()));
  return results;
}

// ─── Risk calculation (per user) ─────────────────────────────────────────────

async function calculateRiskForUser(
  supabase: ReturnType<typeof createClient>,
  userId: string,
  localDate: string,
): Promise<{
  score: number; zone: string; percent: number;
  topTriggers: TriggerScore[]; forecast: number[]; dayRisks: DayRisk[];
} | null> {
  // 1. Decay weights
  const { data: decayRows } = await supabase
    .from("risk_decay_weights")
    .select("severity, day_0, day_1, day_2, day_3, day_4, day_5, day_6")
    .eq("user_id", userId);

  if (!decayRows || decayRows.length === 0) return null;

  const decayMap: Record<string, number[]> = {};
  for (const row of decayRows as DecayWeight[]) {
    decayMap[row.severity.toUpperCase()] = [
      row.day_0, row.day_1, row.day_2, row.day_3, row.day_4, row.day_5, row.day_6,
    ];
  }

  // 1b. Menstruation decay weights (centered curve)
  const { data: mDecayRows } = await supabase
    .from("menstruation_decay_weights")
    .select("*")
    .eq("user_id", userId)
    .limit(1);

  const menstruationWeights: MenstruationDecayWeight | null =
    (mDecayRows && mDecayRows.length > 0) ? mDecayRows[0] as MenstruationDecayWeight : null;

  // 2. Gauge thresholds
  const { data: thresholdRows } = await supabase
    .from("risk_gauge_thresholds")
    .select("zone, min_value")
    .eq("user_id", userId);

  const thresholdMap: Record<string, number> = {};
  for (const row of (thresholdRows || []) as GaugeThreshold[]) {
    thresholdMap[row.zone.toUpperCase()] = row.min_value;
  }
  const thresholdHigh = thresholdMap["HIGH"] ?? 10.0;
  const thresholdMild = thresholdMap["MILD"] ?? 5.0;
  const thresholdLow  = thresholdMap["LOW"]  ?? 3.0;
  const gaugeMax = thresholdHigh * 1.2;

  // 3. Trigger pool (label → severity + category + display_group)
  const { data: triggerPool } = await supabase
    .from("user_triggers")
    .select("label, prediction_value, category, display_group")
    .eq("user_id", userId);

  const triggerSeverityMap: Record<string, string> = {};
  const triggerCategoryMap: Record<string, string | null> = {};
  // label → display_group. Consolidated metrics (the seven sleep signals all
  // carry "Poor sleep") describe ONE night, so they must contribute to the
  // score once, not seven times. Grouping happens here at scoring time, never
  // when the trigger is logged — the individual trigger stays in the user's
  // history because "REM sleep low" is more use to them than "Poor sleep".
  const triggerGroupMap: Record<string, string> = {};

  for (const t of (triggerPool || []) as PoolItem[]) {
    const sev = (t.prediction_value || "NONE").toUpperCase();
    triggerSeverityMap[t.label.toLowerCase()] = sev;
    triggerCategoryMap[t.label.toLowerCase()] = t.category;
    if (t.display_group) {
      triggerGroupMap[t.label.toLowerCase()] = t.display_group;
      const gk = t.display_group.toLowerCase();
      const existing = triggerSeverityMap[gk];
      const sevOrder = ["HIGH", "MILD", "LOW", "NONE"];
      if (!existing || sevOrder.indexOf(sev) < sevOrder.indexOf(existing)) {
        triggerSeverityMap[gk] = sev;
      }
      triggerCategoryMap[gk] = t.category;
    }
  }

  // 4. Prodrome pool
  const { data: prodromePool } = await supabase
    .from("user_prodromes")
    .select("label, prediction_value")
    .eq("user_id", userId);

  const prodromeSeverityMap: Record<string, string> = {};
  for (const p of (prodromePool || []) as PoolItem[]) {
    prodromeSeverityMap[p.label.toLowerCase()] = (p.prediction_value || "NONE").toUpperCase();
  }

  // 5. Trigger events (last 7 + next 7 days)
  const cutoffStart = dateOffset(localDate, -7) + "T00:00:00Z";
  const cutoffEnd   = dateOffset(localDate, 8)  + "T00:00:00Z";

  const { data: triggerEvents } = await supabase
    .from("triggers")
    .select("type, start_at")
    .eq("user_id", userId)
    .eq("active", true) // retired menstruation_predicted rows must not score
    .gte("start_at", cutoffStart)
    .lte("start_at", cutoffEnd)
    .order("start_at", { ascending: false });

  // 6. Prodrome events
  const { data: prodromeEvents } = await supabase
    .from("prodromes")
    .select("type, start_at")
    .eq("user_id", userId)
    .gte("start_at", cutoffStart)
    .lte("start_at", cutoffEnd)
    .order("start_at", { ascending: false });

  // 7. Build scored events
  const events: ScoredEvent[] = [];
  const menstruationPredictedEvents: { eventDate: string; severity: string }[] = [];

  for (const t of (triggerEvents || []) as EventRow[]) {
    if (!t.type || !t.start_at) continue;

    // Handle menstruation_predicted separately for centered decay
    if (t.type === "menstruation_predicted") {
      const severity = triggerSeverityMap[t.type.toLowerCase()] || "NONE";
      if (severity === "NONE") continue;
      const eventDate = toLocalDate(t.start_at);
      if (!eventDate) continue;
      menstruationPredictedEvents.push({ eventDate, severity });
      continue;
    }

    const severity = triggerSeverityMap[t.type.toLowerCase()] || "NONE";
    if (severity === "NONE") continue;
    const eventDate = toLocalDate(t.start_at);
    if (!eventDate) continue;
    events.push({ name: t.type, severity, eventDate, category: triggerCategoryMap[t.type.toLowerCase()] || null });
  }

  for (const p of (prodromeEvents || []) as EventRow[]) {
    if (!p.type || !p.start_at) continue;
    const severity = prodromeSeverityMap[p.type.toLowerCase()] || "NONE";
    if (severity === "NONE") continue;
    const eventDate = toLocalDate(p.start_at);
    if (!eventDate) continue;
    events.push({ name: p.type, severity, eventDate, category: "Prodrome" });
  }

  // 8. Calculate 7-day forecast
  const forecastPercents: number[] = [];
  const dayRisks: DayRisk[] = [];

  for (let dayOffset = 0; dayOffset < 7; dayOffset++) {
    const perspectiveDate = dateOffset(localDate, dayOffset);
    let dayScore = 0;
    const contributions: { name: string; contribution: number; severity: string }[] = [];

    for (const event of events) {
      const daysAgo = daysBetween(event.eventDate, perspectiveDate);
      if (daysAgo < 0 || daysAgo > 6) continue;

      const weights = decayMap[event.severity];
      if (!weights) continue;
      const weight = weights[daysAgo] ?? 0;
      if (weight > 0) {
        dayScore += weight;
        contributions.push({ name: event.name, contribution: weight, severity: event.severity });
      }
    }

    // Menstruation predicted — centered decay (day_m7…day_0…day_p7)
    if (menstruationWeights) {
      for (const mp of menstruationPredictedEvents) {
        const offset = daysBetween(mp.eventDate, perspectiveDate);
        const weight = getMenstruationWeight(menstruationWeights, offset);
        if (weight > 0) {
          dayScore += weight;
          contributions.push({ name: "menstruation_predicted", contribution: weight, severity: mp.severity });
        }
      }
    }

    // Collapse contributions onto their display_group, keeping the strongest
    // member rather than adding them up — several breached sleep metrics are
    // one bad night, not several separate risks. Severity resolution matches
    // the documented pool collapse: highest of HIGH > MILD > LOW > NONE.
    const sevOrder = ["HIGH", "MILD", "LOW", "NONE"];
    const grouped: Record<string, { total: number; severity: string }> = {};
    for (const c of contributions) {
      const key = triggerGroupMap[c.name.toLowerCase()] ?? c.name;
      if (!grouped[key]) grouped[key] = { total: 0, severity: c.severity };
      // Same group, same day: keep the biggest single contribution.
      grouped[key].total = Math.max(grouped[key].total, c.contribution);
      if (sevOrder.indexOf(c.severity) < sevOrder.indexOf(grouped[key].severity)) {
        grouped[key].severity = c.severity;
      }
    }

    // Count distinct dates each trigger appeared on in the 7-day lookback window
    const triggerDatesMap: Record<string, Set<string>> = {};
    for (const event of events) {
      const daysFromToday = daysBetween(event.eventDate, perspectiveDate);
      if (daysFromToday < 0 || daysFromToday > 6) continue;
      const dateKey = triggerGroupMap[event.name.toLowerCase()] ?? event.name;
      if (!triggerDatesMap[dateKey]) triggerDatesMap[dateKey] = new Set();
      triggerDatesMap[dateKey].add(event.eventDate);
    }

    const topTriggers = Object.entries(grouped)
      .map(([name, { total, severity }]) => ({
        name,
        score: Math.round(total),
        severity,
        days_active: triggerDatesMap[name]?.size ?? 1,
      }))
      .sort((a, b) => b.score - a.score);

    const roundedScore = topTriggers.reduce((sum, t) => sum + t.score, 0);
    const dayPercent = Math.min(100, Math.max(0, Math.round((roundedScore / gaugeMax) * 100)));
    const dayZone = zoneForScore(roundedScore, thresholdHigh, thresholdMild, thresholdLow);

    dayRisks.push({ date: perspectiveDate, score: roundedScore, zone: dayZone, percent: dayPercent, top_triggers: topTriggers });
    forecastPercents.push(dayPercent);
  }

  const todayRisk = dayRisks[0];
  return {
    score: todayRisk.score,
    zone: todayRisk.zone,
    percent: todayRisk.percent,
    topTriggers: todayRisk.top_triggers,
    forecast: forecastPercents,
    dayRisks,
  };
}

// ─── Main ────────────────────────────────────────────────────────────────────

const STALE_LOCK_MINUTES = 10;
const MAX_ATTEMPTS = 3;

serve(async (req) => {
  console.log("[risk-score-worker] start", {
    nowUtc: new Date().toISOString(),
    method: req.method,
  });

  try {
    if (req.method !== "POST" && req.method !== "GET") {
      return jsonResponse({ ok: false, error: "Method not allowed" }, 405);
    }

    const supabaseUrl = requireEnv("SUPABASE_URL");
    const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");

    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false },
      global: { headers: { "X-Client-Info": "risk-score-worker" } },
    });

    const nowUtc = new Date();
    const nowIso = nowUtc.toISOString();
    const staleCutoff = new Date(nowUtc.getTime() - STALE_LOCK_MINUTES * 60_000).toISOString();

    // Pick queued jobs
    const { data: jobs, error: pickErr } = await supabase
      .from("risk_score_jobs")
      .select("*")
      .eq("status", "queued")
      .lt("attempts", MAX_ATTEMPTS)
      .or(`locked_at.is.null,locked_at.lt.${staleCutoff}`)
      .order("created_at", { ascending: true })
      .limit(50);

    if (pickErr) throw new Error(`Failed to pick jobs: ${pickErr.message}`);

    if (!jobs || jobs.length === 0) {
      return jsonResponse({ ok: true, message: "No jobs to process", processed: 0 });
    }

    const results = await mapLimit(jobs as RiskScoreJobRow[], 6, async (job) => {
      const jobResult: Record<string, unknown> = {
        jobId: job.id,
        userId: job.user_id,
        localDate: job.local_date,
      };

      // Lock
      const { error: lockErr } = await supabase
        .from("risk_score_jobs")
        .update({
          status: "running",
          locked_at: nowIso,
          attempts: job.attempts + 1,
          updated_at: nowIso,
        })
        .eq("id", job.id)
        .eq("status", "queued");

      if (lockErr) {
        jobResult.status = "lock_failed";
        return jobResult;
      }

      try {
        const result = await calculateRiskForUser(supabase, job.user_id, job.local_date);

        if (!result) {
          // No config — mark done (nothing to calculate)
          await supabase
            .from("risk_score_jobs")
            .update({ status: "done", locked_at: null, last_error: null, finished_at: nowIso, updated_at: nowIso })
            .eq("id", job.id);
          jobResult.status = "done_no_config";
          return jobResult;
        }

        // Write risk_score_live
        const { error: liveErr } = await supabase
          .from("risk_score_live")
          .upsert({
            user_id: job.user_id,
            score: result.score,
            zone: result.zone,
            percent: result.percent,
            top_triggers: result.topTriggers,
            forecast: result.forecast,
            day_risks: result.dayRisks,
            updated_at: nowIso,
          }, { onConflict: "user_id" });

        if (liveErr) throw new Error(`risk_score_live upsert failed: ${liveErr.message}`);

        // Write risk_score_daily (last run of the day becomes the snapshot)
        const { error: dailyErr } = await supabase
          .from("risk_score_daily")
          .upsert({
            user_id: job.user_id,
            date: job.local_date,
            score: result.score,
            zone: result.zone,
            percent: result.percent,
            top_triggers: result.topTriggers,
            created_at: nowIso,
          }, { onConflict: "user_id,date" });

        if (dailyErr) throw new Error(`risk_score_daily upsert failed: ${dailyErr.message}`);

        // Gauge alert — piggybacks on this 5-minute cycle so an afternoon
        // escalation reaches the user within minutes. Never fails the job.
        jobResult.gaugeAlert = await maybeSendGaugeAlert(
          supabase, job, result.zone, result.percent, result.topTriggers, nowUtc,
        );

        // Mark done
        await supabase
          .from("risk_score_jobs")
          .update({ status: "done", locked_at: null, last_error: null, finished_at: nowIso, updated_at: nowIso })
          .eq("id", job.id);

        jobResult.status = "done";
        jobResult.score = result.score;
        jobResult.zone = result.zone;
        jobResult.topTriggers = result.topTriggers.length;
        return jobResult;

      } catch (e) {
        const errMsg = (e as Error).message;
        jobResult.status = "error";
        jobResult.error = errMsg;

        const newStatus = job.attempts + 1 >= MAX_ATTEMPTS ? "error" : "queued";
        await supabase
          .from("risk_score_jobs")
          .update({ status: newStatus, locked_at: null, last_error: errMsg, updated_at: nowIso })
          .eq("id", job.id);

        return jobResult;
      }
    });

    const summary = {
      picked: jobs.length,
      done: results.filter((r: any) => String(r.status).startsWith("done")).length,
      errors: results.filter((r: any) => r.status === "error").length,
    };

    console.log("[risk-score-worker] done", { summary });
    return jsonResponse({ ok: true, nowUtc: nowIso, summary, results });
  } catch (e) {
    console.error("[risk-score-worker] error", e);
    return jsonResponse({ ok: false, error: (e as Error).message }, 500);
  }
});