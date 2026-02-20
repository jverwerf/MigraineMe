// FILE: supabase/functions/stress-worker/index.ts
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

function median(xs: number[]): number | null {
  const a = xs.filter((n) => Number.isFinite(n)).slice().sort((p, q) => p - q);
  if (!a.length) return null;
  const mid = Math.floor(a.length / 2);
  return a.length % 2 ? a[mid] : (a[mid - 1] + a[mid]) / 2;
}

function mad(xs: number[], med: number): number | null {
  const dev = xs.map((x) => Math.abs(x - med)).filter((n) => Number.isFinite(n));
  return median(dev);
}

function robustZ(x: number, xs: number[]): number | null {
  const med = median(xs);
  if (med == null) return null;
  const m = mad(xs, med);
  if (m == null || m <= 1e-9) return null;
  const robustStd = 1.4826 * m;
  return (x - med) / robustStd;
}

function clamp(n: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, n));
}

function zToPct(z: number): number {
  const pct = 100 / (1 + Math.exp(-z));
  return clamp(pct, 0, 100);
}

type SyncJobRow = {
  id: string;
  job_type: string;
  user_id: string;
  local_date: string;
  status: string;
  attempts: number | null;
  locked_at: string | null;
};

async function insertBackendMetricRun(
  supabase: ReturnType<typeof createClient>,
  userId: string,
  localDate: string,
): Promise<boolean> {
  const { error } = await supabase.from("backend_metric_runs").insert({
    user_id: userId,
    local_date: localDate,
    metric: "stress_index_daily",
    source: "computed",
  });

  if (!error) return true;

  const msg = (error.message ?? "").toLowerCase();
  if (msg.includes("duplicate") || msg.includes("unique")) return false;

  throw new Error(`backend_metric_runs insert failed: ${error.message}`);
}

async function lockJob(
  supabase: ReturnType<typeof createClient>,
  job: SyncJobRow,
  staleCutoffIso: string,
): Promise<boolean> {
  const nowIso = new Date().toISOString();

  if (job.status === "queued") {
    const { data, error } = await supabase
      .from("sync_jobs")
      .update({ status: "running", locked_at: nowIso, updated_at: nowIso, last_error: null })
      .eq("id", job.id)
      .eq("status", "queued")
      .select("id")
      .maybeSingle();

    if (error) return false;
    return Boolean(data);
  }

  const { data, error } = await supabase
    .from("sync_jobs")
    .update({ status: "running", locked_at: nowIso, updated_at: nowIso })
    .eq("id", job.id)
    .eq("status", "running")
    .lt("locked_at", staleCutoffIso)
    .select("id")
    .maybeSingle();

  if (error) return false;
  return Boolean(data);
}

async function bumpAttempts(supabase: ReturnType<typeof createClient>, jobId: string, current: number): Promise<number> {
  const next = current + 1;
  const { error } = await supabase
    .from("sync_jobs")
    .update({ attempts: next, updated_at: new Date().toISOString() })
    .eq("id", jobId);

  if (error) throw new Error(`attempts_update_failed: ${error.message}`);
  return next;
}

async function markJobDone(
  supabase: ReturnType<typeof createClient>,
  jobId: string,
  lastError: string | null,
  attempts: number,
) {
  const nowIso = new Date().toISOString();
  await supabase
    .from("sync_jobs")
    .update({
      status: "done",
      finished_at: nowIso,
      updated_at: nowIso,
      locked_at: null,
      last_error: lastError,
      attempts,
    })
    .eq("id", jobId);
}

async function markJobError(supabase: ReturnType<typeof createClient>, jobId: string, errMsg: string) {
  const nowIso = new Date().toISOString();
  await supabase
    .from("sync_jobs")
    .update({
      status: "error",
      finished_at: nowIso,
      updated_at: nowIso,
      locked_at: null,
      last_error: errMsg,
    })
    .eq("id", jobId);
}

async function computeOne(
  supabase: ReturnType<typeof createClient>,
  userId: string,
  localDate: string,
): Promise<{ status: "written" | "already_done" | "skipped"; reason?: string; value?: number }> {
  const { data: stressSetting, error: msErr } = await supabase
    .from("metric_settings")
    .select("enabled")
    .eq("user_id", userId)
    .eq("metric", "stress_index_daily")
    .maybeSingle();

  if (msErr) throw new Error(`metric_settings read failed: ${msErr.message}`);
  if (!stressSetting?.enabled) return { status: "skipped", reason: "stress_index_disabled" };

  const [{ data: hrvRow, error: hrvErr }, { data: rhrRow, error: rhrErr }] = await Promise.all([
    supabase
      .from("hrv_daily")
      .select("value_rmssd_ms")
      .eq("user_id", userId)
      .eq("date", localDate)
      .limit(1)
      .maybeSingle(),
    supabase
      .from("resting_hr_daily")
      .select("value_bpm")
      .eq("user_id", userId)
      .eq("date", localDate)
      .limit(1)
      .maybeSingle(),
  ]);

  if (hrvErr) throw new Error(`hrv_daily read failed: ${hrvErr.message}`);
  if (rhrErr) throw new Error(`resting_hr_daily read failed: ${rhrErr.message}`);

  const hrv = typeof (hrvRow as any)?.value_rmssd_ms === "number" ? (hrvRow as any).value_rmssd_ms : null;
  const rhr = typeof (rhrRow as any)?.value_bpm === "number" ? (rhrRow as any).value_bpm : null;

  if (hrv == null || rhr == null) return { status: "skipped", reason: "missing_inputs" };

  const startDate = addDaysIsoDate(localDate, -14);
  const endDate = addDaysIsoDate(localDate, -1);

  const [{ data: hrvBase, error: hrvBaseErr }, { data: rhrBase, error: rhrBaseErr }] = await Promise.all([
    supabase
      .from("hrv_daily")
      .select("date,value_rmssd_ms")
      .eq("user_id", userId)
      .gte("date", startDate)
      .lte("date", endDate),
    supabase
      .from("resting_hr_daily")
      .select("date,value_bpm")
      .eq("user_id", userId)
      .gte("date", startDate)
      .lte("date", endDate),
  ]);

  if (hrvBaseErr) throw new Error(`hrv baseline read failed: ${hrvBaseErr.message}`);
  if (rhrBaseErr) throw new Error(`rhr baseline read failed: ${rhrBaseErr.message}`);

  const hrvXs = (hrvBase ?? [])
    .map((r: any) => (typeof r.value_rmssd_ms === "number" ? r.value_rmssd_ms : null))
    .filter((n: any) => typeof n === "number") as number[];

  const rhrXs = (rhrBase ?? [])
    .map((r: any) => (typeof r.value_bpm === "number" ? r.value_bpm : null))
    .filter((n: any) => typeof n === "number") as number[];

  if (hrvXs.length < 5 || rhrXs.length < 5) return { status: "skipped", reason: "insufficient_baseline" };

  const zRhr = robustZ(rhr, rhrXs);
  const zHrv = robustZ(hrv, hrvXs);
  if (zRhr == null || zHrv == null) return { status: "skipped", reason: "baseline_zero_variance" };

  const zCombined = (0.55 * zRhr) + (0.45 * (-zHrv));
  const stressValue = zToPct(zCombined);

  const proceed = await insertBackendMetricRun(supabase, userId, localDate);
  if (!proceed) return { status: "already_done" };

  const computedAt = new Date().toISOString();
  const baselineWindowDays = Math.min(hrvXs.length, rhrXs.length);

  const { error: upErr } = await supabase.from("stress_index_daily").upsert(
    {
      user_id: userId,
      date: localDate,
      value: stressValue,
      hrv_z: zHrv,
      rhr_z: zRhr,
      baseline_window_days: baselineWindowDays,
      computed_at: computedAt,
    },
    { onConflict: "user_id,date" },
  );

  if (upErr) throw new Error(`stress_index_daily upsert failed: ${upErr.message}`);

  return { status: "written", value: stressValue };
}

serve(async (req) => {
  try {
    if (req.method !== "POST" && req.method !== "GET") {
      return jsonResponse({ ok: false, error: "Method not allowed" }, 405);
    }

    const supabaseUrl = requireEnv("SUPABASE_URL");
    const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");

    const supabase = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false },
      global: { headers: { "X-Client-Info": "stress-worker" } },
    });

    const nowUtc = new Date();
    const staleCutoffIso = new Date(nowUtc.getTime() - 30 * 60_000).toISOString();

    const { data: jobs, error: jobsErr } = await supabase
      .from("sync_jobs")
      .select("id,job_type,user_id,local_date,status,attempts,locked_at")
      .eq("job_type", "stress_index_daily")
      .or(`status.eq.queued,status.eq.running.and(locked_at.lt.${staleCutoffIso})`)
      .order("local_date", { ascending: true })
      .order("created_at", { ascending: true })
      .limit(50);

    if (jobsErr) throw new Error(`sync_jobs select failed: ${jobsErr.message}`);

    const picked = (jobs ?? []) as SyncJobRow[];

    const results: any[] = [];

    for (const job of picked) {
      const locked = await lockJob(supabase, job, staleCutoffIso);
      if (!locked) {
        results.push({ jobId: job.id, status: "skipped_lock_race" });
        continue;
      }

      const userId = String(job.user_id);
      const localDate = String(job.local_date);

      try {
        const currentAttempts = typeof job.attempts === "number" ? job.attempts : 0;
        const attempts = await bumpAttempts(supabase, job.id, currentAttempts);

        const out = await computeOne(supabase, userId, localDate);

        if (out.status === "written" || out.status === "already_done") {
          await markJobDone(supabase, job.id, null, attempts);
          results.push({ jobId: job.id, userId, localDate, status: "done", result: out.status, value: out.value ?? null });
        } else {
          await markJobDone(supabase, job.id, out.reason ?? "skipped", attempts);
          results.push({ jobId: job.id, userId, localDate, status: "done", reason: out.reason ?? "skipped" });
        }
      } catch (e) {
        const msg = (e as Error).message;
        await markJobError(supabase, job.id, msg);
        results.push({ jobId: job.id, status: "error", error: msg });
      }
    }

    const summary = {
      picked: picked.length,
      done: results.filter((r) => r.status === "done").length,
      errors: results.filter((r) => r.status === "error").length,
      nowUtc: nowUtc.toISOString(),
      jobType: "stress_index_daily",
      staleReclaimMinutes: 30,
      maxPickAttempts: 10,
    };

    return jsonResponse({ ok: true, summary, results });
  } catch (e) {
    return jsonResponse({ ok: false, error: (e as Error).message }, 500);
  }
});
