// supabase/functions/prodrome-worker/index.ts
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

function mean(arr: number[]): number | null {
  if (arr.length === 0) return null;
  return arr.reduce((a, b) => a + b, 0) / arr.length;
}

function stdDev(arr: number[], avg: number): number | null {
  if (arr.length < 2) return null;
  const squaredDiffs = arr.map((x) => (x - avg) ** 2);
  return Math.sqrt(squaredDiffs.reduce((a, b) => a + b, 0) / arr.length);
}

// ─── Time helpers ────────────────────────────────────────────────────────────
function isoToMinutesSinceMidnight(isoString: string, isBedtime: boolean): number | null {
  const d = new Date(isoString);
  if (Number.isNaN(d.getTime())) return null;
  const hh = d.getUTCHours();
  const mm = d.getUTCMinutes();
  let mins = hh * 60 + mm;
  if (isBedtime && hh < 12) {
    mins += 24 * 60;
  }
  return mins;
}

function hoursToMinutes(hours: number, isBedtime: boolean): number {
  let mins = hours * 60;
  if (isBedtime && hours < 12) {
    mins += 24 * 60;
  }
  return mins;
}

// ─── Types ───────────────────────────────────────────────────────────────────

type ProdromeDefinition = {
  id: string;
  label: string;
  category: string;
  direction: string;      // 'low' | 'high'
  default_threshold: number | null;
  unit: string | null;
  metric_table: string;
  metric_column: string;
  baseline_days: number;
  enabled_by_default: boolean;
  metric_type: string | null;
  display_group: string | null;  // ← NEW
};

type ProdromeSetting = {
  prodrome_type: string;
  enabled: boolean;
  threshold: number | null;
};

type ProdromeJobRow = {
  id: string;
  job_type: string;
  user_id: string;
  local_date: string;
  status: string;
  attempts: number;
  locked_at: string | null;
  timezone: string | null;
};

const STALE_LOCK_MINUTES = 10;
const MAX_ATTEMPTS = 3;
const MIN_BASELINE_DAYS = 7;

const RISK_EXPOSURE_COLUMNS = new Set([
  "max_tyramine_exposure",
  "max_alcohol_exposure",
  "max_gluten_exposure",
]);
const RISK_RANK: Record<string, number> = { none: 0, low: 1, medium: 2, high: 3 };

const CUMULATIVE_LOW_CHECK_HOUR = 21;

serve(async (req) => {
  console.log("[prodrome-worker] start", {
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
      global: { headers: { "X-Client-Info": "prodrome-worker" } },
    });

    const nowUtc = new Date();
    const nowIso = nowUtc.toISOString();
    const staleCutoff = new Date(nowUtc.getTime() - STALE_LOCK_MINUTES * 60_000).toISOString();

    // ─── Pick queued jobs ────────────────────────────────────────────────
    const { data: jobs, error: pickErr } = await supabase
      .from("prodrome_jobs")
      .select("*")
      .eq("status", "queued")
      .lt("attempts", MAX_ATTEMPTS)
      .or(`locked_at.is.null,locked_at.lt.${staleCutoff}`)
      .limit(20);

    if (pickErr) {
      throw new Error(`Failed to pick jobs: ${pickErr.message}`);
    }

    if (!jobs || jobs.length === 0) {
      return jsonResponse({ ok: true, message: "No jobs to process", processed: 0 });
    }

    const results: unknown[] = [];

    for (const job of jobs as ProdromeJobRow[]) {
      const jobResult: Record<string, unknown> = {
        jobId: job.id,
        userId: job.user_id,
        localDate: job.local_date,
        triggerResults: [],
      };

      // Lock the job
      const { error: lockErr } = await supabase
        .from("prodrome_jobs")
        .update({ locked_at: nowIso, attempts: job.attempts + 1, updated_at: nowIso })
        .eq("id", job.id);

      if (lockErr) {
        jobResult.status = "lock_failed";
        jobResult.error = lockErr.message;
        results.push(jobResult);
        continue;
      }

      try {
        const userId = job.user_id;
        const localDate = job.local_date;

        // ─── Load THIS USER's prodrome definitions from user_prodromes ─
        const { data: defsRaw, error: defsErr } = await supabase
          .from("user_prodromes")
          .select("id,label,category,direction,default_threshold,unit,metric_table,metric_column,baseline_days,enabled_by_default,metric_type,display_group")
          .eq("user_id", userId)
          .not("direction", "is", null);

        if (defsErr) {
          throw new Error(`user_prodromes query failed: ${defsErr.message}`);
        }

        const definitions = (defsRaw ?? []) as ProdromeDefinition[];

        if (definitions.length === 0) {
          await supabase
            .from("prodrome_jobs")
            .update({ status: "done", locked_at: null, last_error: null, updated_at: nowIso })
            .eq("id", job.id);
          jobResult.status = "done_no_definitions";
          results.push(jobResult);
          continue;
        }

        // Determine user's local hour
        let userLocalHour = 23;
        if (job.timezone) {
          try {
            const fmt = new Intl.DateTimeFormat("en-US", {
              timeZone: job.timezone,
              hour: "numeric",
              hour12: false,
            });
            const parts = fmt.formatToParts(new Date());
            const hourPart = parts.find((p) => p.type === "hour");
            if (hourPart) userLocalHour = Number(hourPart.value);
          } catch {
            // timezone parse failed
          }
        }

        // ─── Load user's prodrome settings ─────────────────────────────
        const { data: userSettings, error: settErr } = await supabase
          .from("prodrome_settings")
          .select("prodrome_type,enabled,threshold")
          .eq("user_id", userId);

        if (settErr) {
          throw new Error(`prodrome_settings query failed: ${settErr.message}`);
        }

        const settingsMap = new Map<string, ProdromeSetting>();
        for (const s of (userSettings ?? []) as ProdromeSetting[]) {
          settingsMap.set(s.prodrome_type, s);
        }

        // ─── Determine which definitions to process ────────────────────
        const activeDefs = definitions.filter((def) => {
          const setting = settingsMap.get(def.label);
          if (setting) return setting.enabled;
          return def.enabled_by_default;
        });

        jobResult.activeCount = activeDefs.length;
        jobResult.totalDefinitions = definitions.length;

        if (activeDefs.length === 0) {
          await supabase
            .from("prodrome_jobs")
            .update({ status: "done", locked_at: null, last_error: null, updated_at: nowIso })
            .eq("id", job.id);
          jobResult.status = "done_no_active_triggers";
          results.push(jobResult);
          continue;
        }

        // ─── Group definitions by metric_table+metric_column ───────────
        const metricGroups = new Map<string, ProdromeDefinition[]>();
        for (const def of activeDefs) {
          const key = `${def.metric_table}::${def.metric_column}`;
          const arr = metricGroups.get(key) ?? [];
          arr.push(def);
          metricGroups.set(key, arr);
        }

        // Track which display_groups already fired (to append notes instead of duplicating)
        const firedGroups = new Set<string>();

        // ─── Process each metric group ─────────────────────────────────
        for (const [key, defs] of metricGroups) {
          const table = defs[0].metric_table;
          const column = defs[0].metric_column;
          const baselineDays = defs[0].baseline_days || 14;
          const isTimeMetric = defs[0].unit === "time";
          const isBedtime = table === "fell_asleep_time_daily";

          // Fetch today's value
          const { data: todayRows, error: todayErr } = await supabase
            .from(table)
            .select(column)
            .eq("user_id", userId)
            .eq("date", localDate)
            .limit(1);

          const todayRow = (todayRows ?? [])[0] ?? null;

          if (todayErr) {
            (jobResult.triggerResults as unknown[]).push({
              metric: key,
              status: "fetch_error",
              error: todayErr.message,
            });
            continue;
          }

          const rawValue = todayRow ? (todayRow as Record<string, unknown>)[column] : null;

          if (rawValue == null) {
            (jobResult.triggerResults as unknown[]).push({
              metric: key,
              status: "no_data",
            });
            continue;
          }

          // Convert value to numeric
          let numericValue: number | null = null;
          const isRiskColumn = RISK_EXPOSURE_COLUMNS.has(column);

          if (isRiskColumn) {
            const strVal = typeof rawValue === "string" ? rawValue.toLowerCase() : "none";
            numericValue = RISK_RANK[strVal] ?? 0;
          } else if (isTimeMetric) {
            numericValue = isoToMinutesSinceMidnight(String(rawValue), isBedtime);
          } else {
            numericValue = typeof rawValue === "number" ? rawValue : Number(rawValue);
            if (Number.isNaN(numericValue)) numericValue = null;
          }

          if (numericValue == null) {
            (jobResult.triggerResults as unknown[]).push({
              metric: key,
              status: "invalid_value",
              rawValue,
            });
            continue;
          }

          // ─── Always fetch baseline for 2SD check ─────────────────────
          let baselineValues: number[] = [];
          let avg: number | null = null;
          let sd: number | null = null;

          const startDate = addDaysIsoDate(localDate, -baselineDays);
          const endDate = addDaysIsoDate(localDate, -1);

          const { data: historyRows, error: histErr } = await supabase
            .from(table)
            .select(column)
            .eq("user_id", userId)
            .gte("date", startDate)
            .lte("date", endDate);

          if (histErr) {
            (jobResult.triggerResults as unknown[]).push({
              metric: key,
              status: "baseline_fetch_error",
              error: histErr.message,
            });
          } else {
            baselineValues = (historyRows ?? [])
              .map((r: Record<string, unknown>) => {
                const v = r[column];
                if (isRiskColumn) {
                  const s = typeof v === "string" ? v.toLowerCase() : "none";
                  return RISK_RANK[s] ?? 0;
                }
                if (isTimeMetric) return isoToMinutesSinceMidnight(String(v), isBedtime);
                const n = typeof v === "number" ? v : Number(v);
                return Number.isFinite(n) ? n : null;
              })
              .filter((v): v is number => v != null);

            if (baselineValues.length >= MIN_BASELINE_DAYS) {
              avg = mean(baselineValues);
              sd = avg != null ? stdDev(baselineValues, avg) : null;
            }
          }

          // ─── Evaluate each definition (dual check: threshold OR 2SD) ─
          for (const def of defs) {
            const setting = settingsMap.get(def.label);
            const userThreshold = setting?.threshold ?? null;
            const defaultThreshold = def.default_threshold;
            const thresh = userThreshold ?? defaultThreshold;

            // Cumulative metrics: skip 'low' until end of day
            if (def.direction === "low" && def.metric_type === "cumulative" && userLocalHour < CUMULATIVE_LOW_CHECK_HOUR) {
              (jobResult.triggerResults as unknown[]).push({
                triggerType: def.label,
                status: "skipped_cumulative_low_before_eod",
                localHour: userLocalHour,
              });
              continue;
            }

            let fired = false;
            const reasons: string[] = [];

            // ── Check 1: Absolute threshold ──
            if (thresh != null) {
              let compareThresh = thresh;
              let compareValue = numericValue;

              if (isTimeMetric) {
                compareThresh = hoursToMinutes(thresh, isBedtime);
                compareValue = numericValue;
              }

              if (def.direction === "low" && compareValue < compareThresh) {
                fired = true;
                if (isTimeMetric) {
                  reasons.push(`before ${thresh}:00 threshold`);
                } else {
                  reasons.push(`below ${formatValue(thresh, def.unit, false)} threshold`);
                }
              } else if (def.direction === "high" && compareValue > compareThresh) {
                fired = true;
                if (isTimeMetric) {
                  reasons.push(`after ${thresh}:00 threshold`);
                } else {
                  reasons.push(`above ${formatValue(thresh, def.unit, false)} threshold`);
                }
              }
            }

            // ── Check 2: 2SD from baseline ──
            if (avg != null && sd != null && sd > 0) {
              if (def.direction === "low") {
                const threshold2SD = avg - 2 * sd;
                if (numericValue < threshold2SD) {
                  if (!fired) fired = true;
                  reasons.push(`2SD below avg ${formatValue(avg, def.unit, isTimeMetric)} (cutoff: ${formatValue(threshold2SD, def.unit, isTimeMetric)})`);
                }
              } else if (def.direction === "high") {
                const threshold2SD = avg + 2 * sd;
                if (numericValue > threshold2SD) {
                  if (!fired) fired = true;
                  reasons.push(`2SD above avg ${formatValue(avg, def.unit, isTimeMetric)} (cutoff: ${formatValue(threshold2SD, def.unit, isTimeMetric)})`);
                }
              }
            }

            if (fired) {
              // Use display_group name if set, otherwise use individual label
              const prodromeType = def.display_group ?? def.label;
              const notes = `${def.label}: ${formatValue(numericValue, def.unit, isTimeMetric)} — ${reasons.join("; ")}`;

              // If this display_group already fired, append note instead of creating duplicate
              if (def.display_group && firedGroups.has(def.display_group)) {
                const appended = await appendNoteToProdrome(
                  supabase, userId, prodromeType, localDate, notes
                );
                (jobResult.triggerResults as unknown[]).push({
                  triggerType: def.label,
                  displayGroup: def.display_group,
                  status: appended ? "appended_to_group" : "append_failed",
                  value: numericValue,
                  notes,
                });
              } else {
                const created = await createProdromeIfNotExists(
                  supabase, userId, prodromeType, localDate, notes
                );

                if (def.display_group) firedGroups.add(def.display_group);

                (jobResult.triggerResults as unknown[]).push({
                  triggerType: def.label,
                  displayGroup: def.display_group,
                  status: created ? "created" : "already_exists",
                  value: numericValue,
                  notes,
                });
              }
            } else {
              (jobResult.triggerResults as unknown[]).push({
                triggerType: def.label,
                status: "not_fired",
                value: numericValue,
                baselineCount: baselineValues.length,
              });
            }
          }
        }

        // Mark job done
        await supabase
          .from("prodrome_jobs")
          .update({ status: "done", locked_at: null, last_error: null, updated_at: nowIso })
          .eq("id", job.id);

        jobResult.status = "done";
      } catch (e) {
        const errMsg = (e as Error).message;
        jobResult.status = "error";
        jobResult.error = errMsg;

        const newStatus = job.attempts + 1 >= MAX_ATTEMPTS ? "error" : "queued";
        await supabase
          .from("prodrome_jobs")
          .update({ status: newStatus, locked_at: null, last_error: errMsg, updated_at: nowIso })
          .eq("id", job.id);
      }

      results.push(jobResult);
    }

    const summary = {
      picked: jobs.length,
      done: results.filter((r: any) => r.status?.startsWith("done")).length,
      errors: results.filter((r: any) => r.status === "error").length,
    };

    return jsonResponse({ ok: true, nowUtc: nowIso, summary, results });
  } catch (e) {
    console.error("[prodrome-worker] error", e);
    return jsonResponse({ ok: false, error: (e as Error).message }, 500);
  }
});

// ─── Helpers ─────────────────────────────────────────────────────────────────

function formatValue(value: number, unit: string | null, isTimeFromMinutes: boolean): string {
  if (isTimeFromMinutes) {
    let mins = Math.round(value);
    if (mins >= 24 * 60) mins -= 24 * 60;
    const hh = Math.floor(mins / 60);
    const mm = mins % 60;
    return `${String(hh).padStart(2, "0")}:${String(mm).padStart(2, "0")}`;
  }

  switch (unit) {
    case "hours":
      return `${value.toFixed(1)}h`;
    case "%":
      return `${value.toFixed(0)}%`;
    case "count":
      return `${Math.round(value)}`;
    default:
      return `${value.toFixed(1)}`;
  }
}

async function createProdromeIfNotExists(
  supabase: ReturnType<typeof createClient>,
  userId: string,
  triggerType: string,
  date: string,
  notes: string
): Promise<boolean> {
  const dayStart = `${date}T00:00:00Z`;
  const dayEnd = `${date}T23:59:59Z`;

  const { data: existing, error: checkErr } = await supabase
    .from("prodromes")
    .select("id")
    .eq("user_id", userId)
    .eq("type", triggerType)
    .eq("source", "system")
    .gte("start_at", dayStart)
    .lte("start_at", dayEnd)
    .limit(1);

  if (checkErr) {
    console.error(`[prodrome-worker] check existing failed: ${checkErr.message}`);
    return false;
  }

  if (existing && existing.length > 0) {
    return false;
  }

  const { error: insertErr } = await supabase.from("prodromes").insert({
    user_id: userId,
    type: triggerType,
    source: "system",
    start_at: `${date}T09:00:00Z`,
    notes,
  });

  if (insertErr) {
    console.error(`[prodrome-worker] insert failed: ${insertErr.message}`);
    return false;
  }

  return true;
}

/** Append a note to an existing prodrome that already fired for this display_group today */
async function appendNoteToProdrome(
  supabase: ReturnType<typeof createClient>,
  userId: string,
  prodromeType: string,
  date: string,
  newNote: string
): Promise<boolean> {
  const dayStart = `${date}T00:00:00Z`;
  const dayEnd = `${date}T23:59:59Z`;

  const { data: existing, error: fetchErr } = await supabase
    .from("prodromes")
    .select("id,notes")
    .eq("user_id", userId)
    .eq("type", prodromeType)
    .eq("source", "system")
    .gte("start_at", dayStart)
    .lte("start_at", dayEnd)
    .limit(1);

  if (fetchErr || !existing || existing.length === 0) {
    console.error(`[prodrome-worker] appendNote fetch failed: ${fetchErr?.message}`);
    return false;
  }

  const row = existing[0];
  const combinedNotes = row.notes ? `${row.notes}\n${newNote}` : newNote;

  const { error: updateErr } = await supabase
    .from("prodromes")
    .update({ notes: combinedNotes })
    .eq("id", row.id);

  if (updateErr) {
    console.error(`[prodrome-worker] appendNote update failed: ${updateErr.message}`);
    return false;
  }

  return true;
}