// Every read the report needs, in one place.
//
// The client passes only a date range and its filter selection; everything
// else is fetched here with the caller's JWT so RLS scopes it to them. That
// is the whole point of moving generation server-side: four apps used to
// assemble this separately and drift apart.

import { createClient, SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2";
import { METRICS, type MetricDef } from "./metrics.ts";
import { asLang, type Lang } from "../_shared/i18n.ts";

/** Days of context drawn either side of an attack — the old Migraine Timeline
 *  section's window, now part of every attack card. */
export const WINDOW_BEFORE_DAYS = 3;
export const WINDOW_AFTER_DAYS = 2;

export type ReportParams = {
  from?: string;            // full ISO timestamp with offset, inclusive lower bound
  to?: string;              // full ISO timestamp with offset, EXCLUSIVE upper bound
  timeframeLabel?: string;  // "Last 3 Months" — printed on the cover
  /** The client's filter chips spelled out — printed on the cover when set. */
  filterSummary?: string;
  /** Ids of the migraines that survived every client-side filter. When the
   *  field is present — even as an empty array — the report covers exactly
   *  these attacks; an empty array means zero migraines, not "no filter". */
  episodeIds?: string[];
  /** Metric keys the reader ticked in Select Metrics. Auto-detected metrics
   *  are added per attack on top of these. */
  metricKeys?: string[];
  /** Keys the reader explicitly disabled. Subtracted after auto-detection, so
   *  a switched-off metric never sneaks back onto an attack chart. */
  disabledMetricKeys?: string[];
  /** Metric series the client already holds. Until the metric registry moves
   *  server-side, the client passes what it charted so the PDF matches the
   *  screen exactly rather than approximating it. */
  metricSeries?: MetricSeries[];
};

export type MetricSeries = {
  key: string;
  label: string;
  unit: string;
  color?: string;
  points: { date: string; value: number }[];
};

export type Migraine = {
  id: string;
  start_at: string;
  ended_at: string | null;
  severity: number | null;
  type: string | null;
  pain_locations: string[] | null;
  aura_locations: string[] | null;
  aura_duration_minutes: number | null;
  notes: string | null;
};

export type ChildRow = {
  id: string;
  migraine_id: string | null;
  type?: string | null;
  name?: string | null;
  amount?: string | null;
  /** Dose contract 2026-08-13: the real columns. `amount` is a formatted
   *  mirror the clients still write, and the only thing older rows have. */
  dose_value?: number | null;
  dose_unit?: string | null;
  /** NONE/LOW/MILD/HIGH — how much this relief or medicine actually helped. */
  relief_scale?: string | null;
  start_at: string;
  source?: string | null;
};

export type SymptomRow = {
  id: string;
  migraine_id: string | null;
  type: string | null;
  severity: string | null;
  start_at: string | null;
};

export type PainPoint = {
  migraine_id: string;
  location_id: string;
  severity: number | null;
  start_at: string;
};

/** One aura zone at one moment. Rows sharing (migraine_id, start_at) are one
 *  aura entry — the same shape as the pain timeline. */
export type AuraZone = {
  migraine_id: string;
  zone: string;
  start_at: string | null;
};

export type CorrelationStat = {
  factor_name: string;
  factor_type: string;
  factor_b: string | null;
  best_lag_days: number | null;
  lift_ratio: number;
  pct_migraine_windows: number | null;
  pct_control_windows: number | null;
  sample_size: number | null;
  /** NULL on rows where no statistical test ran — treatment rows whose only
   *  evidence is the patient's own relief rating, and symptom-segment rows.
   *  A rating is never converted into a p-value, so the column is nullable
   *  and every reader has to say what it draws when there is no test. */
  p_value: number | null;
  lag_details: Record<string, unknown> | null;
  symptom_outcome: string | null;
  symptom_segment: string | null;
};

export type ReportData = {
  /** Display language of the person the report is for. The document is built
   *  server-side, so nothing in it reaches the app's translation layer — the
   *  renderer has to translate it here or it prints English to a clinician
   *  whose patient reads German. */
  lang: Lang;
  params: ReportParams;
  migraines: Migraine[];
  triggers: ChildRow[];
  prodromes: ChildRow[];
  medicines: ChildRow[];
  reliefs: ChildRow[];
  activities: ChildRow[];
  locations: ChildRow[];
  missedActivities: ChildRow[];
  symptoms: SymptomRow[];
  painPoints: PainPoint[];
  auraZones: AuraZone[];
  correlations: CorrelationStat[];
  treatmentTiming: {
    treatment_name: string; cutoff_minutes: number;
    early_count: number; early_avg_peak: number;
    late_count: number; late_avg_peak: number;
  }[];
  painMigration: {
    attacks_analysed: number; onset_locations: string[];
    spread_locations: string[]; median_minutes_to_peak: number | null;
  } | null;
  /** Pain-response engine output (intraday_response_stats). Every row already
   *  passed the engine's honesty gates, so the report renders what it gets and
   *  renders nothing when the table is empty. */
  intradayResponse: {
    event_kind: "aggravator" | "easer"; label: string;
    median_effect: number; horizon_minutes: number;
    n: number; consistency: number; caution: boolean; p_value: number;
  }[];
  treatments: Record<string, unknown>[];
  narratives: Record<string, string>;
  sideEffects: Record<string, Record<string, unknown>[]>;
  recommendations: { title: string; body: string }[];
  /** A regeneration of the insight row is queued or running.
   *
   *  Changing the display language deletes the newest daily_insights row and
   *  re-queues its job (trg_requeue_daily_insight_on_lang_change), because the
   *  model prose cannot be re-translated at the render boundary the way t()
   *  strings are. For the minutes that takes, `recommendations` is empty for a
   *  reason that has nothing to do with the patient — and a clinical document
   *  must not present a transient gap as "no recommendations". */
  recommendationsPending: boolean;
  /** Normalised trigger/prodrome label -> the metric keys it stands for.
   *  Grouped labels such as "Poor sleep" cover several metrics, which is why
   *  this is a set per label and not a single key. */
  labelToMetricKeys: Record<string, string[]>;
  /** Metric key -> the display group it belongs to ("Poor sleep",
   *  "Irregular sleep"). Sleep duration, score, efficiency and disturbances
   *  are one idea, and the report says so instead of listing four lines. */
  metricGroups: Record<string, string>;
  /** Pool label -> category, per log type. Drives the breakdown section, which
   *  is how the old report showed what a person logs most of. */
  poolCategories: Record<string, Record<string, string>>;
  profile: Record<string, unknown> | null;
  symptomCategories: Record<string, string>;
  metricSeries: MetricSeries[];
  /** Lifestyle context rows: mean daily value in the report window against
   *  the equal-length window before it (last 30 days vs the prior 30 on an
   *  all-time report). Driven by the date range only — episode filters
   *  deliberately do not change these numbers. */
  lifestyle: {
    key: string; label: string; unit: string;
    prior: number | null; current: number | null;
  }[];
  /** Per-item occurrence counts, report window vs the equal prior window.
   *  Both sides are date-scoped only, so the comparison stays symmetric. */
  trends: {
    kind: "trigger" | "prodrome" | "medicine" | "relief" | "symptom";
    name: string; prior: number; current: number;
  }[];
  /** The migraines themselves, per comparison window. */
  trendStats: {
    prior: { count: number; avgSeverity: number | null; avgDurationH: number | null;
      symptomsPerAttack: number | null };
    current: { count: number; avgSeverity: number | null; avgDurationH: number | null;
      symptomsPerAttack: number | null };
  } | null;
};

/** "none"/"low"/"medium"/"high" -> 0..3, the scale the apps chart food-risk
 *  exposure on. */
const riskNum = (raw: unknown): number | null =>
  ({ high: 3, medium: 2, low: 1, none: 0 } as Record<string, number>)[
    String(raw ?? "").toLowerCase()] ?? null;

/** Rows linked to one of the filtered attacks. PostgREST caps `in.()` lists,
 *  so ids are chunked rather than sent as one enormous filter. */
async function childrenFor(
  sb: SupabaseClient, table: string, ids: string[], select: string,
): Promise<ChildRow[]> {
  if (ids.length === 0) return [];
  const out: ChildRow[] = [];
  for (let i = 0; i < ids.length; i += 80) {
    const chunk = ids.slice(i, i + 80);
    const { data, error } = await sb.from(table).select(select).in("migraine_id", chunk);
    if (error) continue;   // a missing optional table must not kill the report
    out.push(...(data as unknown as ChildRow[]));
  }
  return out;
}

export async function loadReportData(
  authHeader: string, params: ReportParams,
): Promise<ReportData> {
  const sb = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    { global: { headers: { Authorization: authHeader } } },
  );

  const { data: authUser } = await sb.auth.getUser();
  const readerId = authUser?.user?.id ?? null;
  let lang: Lang = "en";
  if (readerId) {
    const { data: langRow } = await sb
      .from("profiles").select("lang").eq("user_id", readerId).maybeSingle();
    lang = asLang((langRow as { lang?: unknown } | null)?.lang);
  }

  const migSelect =
    "id,start_at,ended_at,severity,type,pain_locations,aura_locations,aura_duration_minutes,notes";
  const migraines: Migraine[] = [];
  if (params.episodeIds) {
    // The client already decided which attacks survive its filters — fetch
    // exactly those, chunked the same way as children. An empty list is an
    // empty report, not "no filter".
    for (let i = 0; i < params.episodeIds.length; i += 150) {
      let mq = sb.from("migraines").select(migSelect)
        .in("id", params.episodeIds.slice(i, i + 150));
      if (params.from) mq = mq.gte("start_at", params.from);
      if (params.to) mq = mq.lt("start_at", params.to);
      const { data } = await mq;
      migraines.push(...((data ?? []) as Migraine[]));
    }
  } else {
    // Newest first, so a row cap would drop the oldest attacks, never the
    // most recent. `to` is exclusive: the start of the day after the range.
    let mq = sb.from("migraines").select(migSelect)
      .order("start_at", { ascending: false });
    if (params.from) mq = mq.gte("start_at", params.from);
    if (params.to) mq = mq.lt("start_at", params.to);
    const { data: migRows } = await mq;
    migraines.push(...((migRows ?? []) as Migraine[]));
  }
  migraines.sort((a, b) => (a.start_at < b.start_at ? 1 : -1));
  const ids = migraines.map((m) => m.id);

  const [
    triggers, prodromes, medicines, reliefs, activities, locations, missedActivities,
  ] = await Promise.all([
    childrenFor(sb, "triggers", ids, "id,migraine_id,type,start_at,source"),
    childrenFor(sb, "prodromes", ids, "id,migraine_id,type,start_at,source"),
    childrenFor(sb, "medicines", ids, "id,migraine_id,name,amount,dose_value,dose_unit,start_at,source"),
    childrenFor(sb, "reliefs", ids, "id,migraine_id,type,relief_scale,start_at,source"),
    childrenFor(sb, "activities", ids, "id,migraine_id,type,start_at,source"),
    childrenFor(sb, "locations", ids, "id,migraine_id,type,start_at,source"),
    childrenFor(sb, "missed_activities", ids, "id,migraine_id,type,start_at,source"),
  ]);

  const symptoms = (await childrenFor(
    sb, "symptoms", ids, "id,migraine_id,type,severity,start_at",
  )) as unknown as SymptomRow[];

  const painPoints: PainPoint[] = [];
  for (let i = 0; i < ids.length; i += 80) {
    const { data } = await sb.from("migraine_pain_points")
      .select("migraine_id,location_id,severity,start_at")
      .in("migraine_id", ids.slice(i, i + 80))
      .order("start_at", { ascending: true });
    painPoints.push(...((data ?? []) as PainPoint[]));
  }

  // Timestamped aura zones. Absent for attacks logged before the feature, and
  // those keep falling back to migraines.aura_locations.
  const auraZones: AuraZone[] = [];
  for (let i = 0; i < ids.length; i += 80) {
    const { data } = await sb.from("migraine_aura_zones")
      .select("migraine_id,zone,start_at")
      .in("migraine_id", ids.slice(i, i + 80))
      .order("start_at", { ascending: true });
    auraZones.push(...((data ?? []) as AuraZone[]));
  }

  const [
    { data: corr }, { data: timing }, { data: migration }, { data: intraday },
    { data: pool }, { data: prof }, { data: insights }, { data: insightJobs },
  ] = await Promise.all([
    sb.from("correlation_stats").select("*"),
    sb.from("treatment_timing_stats").select("*"),
    sb.from("pain_migration_stats").select("*").limit(1),
    sb.from("intraday_response_stats").select("*"),
    sb.from("user_symptoms").select("label,category"),
    sb.from("ai_setup_profiles").select("*").limit(1),
    sb.from("daily_insights").select("ai_recommendations")
      .not("ai_recommendations", "is", null)
      .order("date", { ascending: false }).limit(1),
    // Distinguishes "regenerating" from "never had any". daily_insights is not
    // language-stamped, so the row is deleted and rebuilt rather than filtered
    // — which means its absence alone cannot tell the two cases apart, and the
    // job queue is the only place that can.
    sb.from("daily_insight_jobs").select("status")
      .in("status", ["queued", "processing"]).limit(1),
  ]);

  // Treatments: the leaderboard is an RPC, its narratives and side effects are
  // per-regimen reads. A failure here drops the section, never the report.
  let treatments: Record<string, unknown>[] = [];
  const narratives: Record<string, string> = {};
  const sideEffects: Record<string, Record<string, unknown>[]> = {};
  try {
    const { data: lb } = await sb.rpc("compute_treatment_leaderboard");
    treatments = (lb ?? []) as Record<string, unknown>[];
    for (const row of treatments) {
      const regimenId = String(row.regimen_id ?? "");
      if (!regimenId) continue;
      // The cached narrative is model prose in whatever language the user had
      // when it was generated (treatment_narrative_cache.lang). A paragraph in
      // the wrong language is worse than no paragraph in a clinical document,
      // so a row that does not match the report's language is omitted — the
      // app's own Monitor screen regenerates it in the new language soon
      // enough via the lang-aware fingerprint. Until the lang column's
      // migration has run, the select falls back and behaves as before.
      const { data: nar, error: narErr } = await sb.from("treatment_narrative_cache")
        .select("narrative, lang").eq("regimen_id", regimenId).limit(1);
      if (narErr) {
        const { data: legacy } = await sb.from("treatment_narrative_cache")
          .select("narrative").eq("regimen_id", regimenId).limit(1);
        if (legacy?.[0]?.narrative) narratives[regimenId] = String(legacy[0].narrative);
      } else if (nar?.[0]?.narrative && asLang(nar[0].lang) === lang) {
        narratives[regimenId] = String(nar[0].narrative);
      }
      // Side-effect logs inside the regimen's active window — the same read
      // generate-treatment-narrative does. Logs are date-keyed, not
      // regimen-keyed, so the window IS the association.
      const seFrom = String(row.start_date ?? "1970-01-01");
      const seTo = String(row.stop_date ?? new Date().toISOString().slice(0, 10));
      const { data: se } = await sb.from("treatment_side_effect_logs")
        .select("log_date,selected_symptoms,notes")
        .gte("log_date", seFrom).lte("log_date", seTo)
        .order("log_date", { ascending: true }).limit(25);
      if (se?.length) sideEffects[regimenId] = se as Record<string, unknown>[];
    }
  } catch { /* section simply won't render */ }

  // Trigger/prodrome templates carry the metric each label was derived from.
  // Auto-detected metrics on a timeline come from this: an automated "Poor
  // sleep" trigger pulls in every sleep metric behind that display group.
  const labelToMetricKeys: Record<string, string[]> = {};
  const metricGroups: Record<string, string> = {};
  {
    const byTableCol = new Map<string, string>();
    for (const def of METRICS) {
      byTableCol.set(`${def.table}:${def.column}`, def.key);
      if (!byTableCol.has(def.table)) byTableCol.set(def.table, def.key);
    }
    const norm = (raw: string) => raw.toLowerCase().replace(/:/g, "").trim()
      .replace(/\s+(low|high|short|long|late|early|many|few)\b.*/, "").trim();

    for (const table of ["trigger_templates", "prodrome_templates"]) {
      const { data } = await sb.from(table)
        .select("label,metric_table,metric_column,display_group")
        .not("metric_table", "is", null);
      for (const row of (data ?? []) as Record<string, string | null>[]) {
        const key = byTableCol.get(`${row.metric_table}:${row.metric_column ?? ""}`)
          ?? byTableCol.get(String(row.metric_table));
        if (!key) continue;
        if (row.display_group) metricGroups[key] = row.display_group;
        for (const label of [row.label, row.display_group]) {
          if (!label) continue;
          const n = norm(label);
          const list = labelToMetricKeys[n] ?? [];
          if (!list.includes(key)) list.push(key);
          labelToMetricKeys[n] = list;
        }
      }
    }
  }

  const poolCategories: Record<string, Record<string, string>> = {};
  await Promise.all([
    ["trigger", "user_triggers"], ["prodrome", "user_prodromes"],
    ["medicine", "user_medicines"], ["relief", "user_reliefs"],
    ["location", "user_locations"], ["activity", "user_activities"],
    ["missed", "user_missed_activities"], ["symptom", "user_symptoms"],
  ].map(async ([kind, table]) => {
    const { data } = await sb.from(table).select("label,category");
    const map: Record<string, string> = {};
    for (const row of (data ?? []) as { label: string; category: string | null }[]) {
      map[row.label.toLowerCase()] = row.category ?? "Other";
    }
    poolCategories[kind] = map;
  }));

  for (const [kind, table] of [["trigger", "trigger_templates"], ["prodrome", "prodrome_templates"]]) {
    const { data } = await sb.from(table).select("label,category,display_group");
    const map = poolCategories[kind] ?? {};
    for (const row of (data ?? []) as Record<string, string | null>[]) {
      if (!row.category) continue;
      for (const name of [row.label, row.display_group]) {
        if (name) map[name.toLowerCase()] = row.category;
      }
    }
    poolCategories[kind] = map;
  }

  const symptomCategories: Record<string, string> = {};
  for (const row of (pool ?? []) as { label: string; category: string | null }[]) {
    symptomCategories[row.label.toLowerCase()] = (row.category ?? "Other").toLowerCase();
  }

  const recommendations: { title: string; body: string }[] = [];
  const rawRecs = insights?.[0]?.ai_recommendations;
  if (Array.isArray(rawRecs)) {
    for (const r of rawRecs as Record<string, unknown>[]) {
      const title = String(r.title ?? r.factor ?? r.name ?? "").trim();
      const body = String(r.body ?? r.text ?? r.recommendation ?? "").trim();
      if (title && body) recommendations.push({ title, body });
    }
  } else if (rawRecs && typeof rawRecs === "object") {
    // Production shape (ai-daily-insight-worker): per-category objects of
    // { "<factor name>": { text, evidence } } — flatten in category order.
    const cats = ["triggers", "prodromes", "medicines", "reliefs", "activities", "symptoms"];
    const recObj = rawRecs as Record<string, unknown>;
    for (const cat of cats.concat(Object.keys(recObj).filter((k) => !cats.includes(k)))) {
      const group = recObj[cat];
      if (!group || typeof group !== "object" || Array.isArray(group)) continue;
      for (const [name, item] of Object.entries(group as Record<string, unknown>)) {
        const body = typeof item === "string"
          ? item.trim()
          : String((item as Record<string, unknown>)?.text ?? "").trim();
        if (name.trim() && body) recommendations.push({ title: name.trim(), body });
      }
    }
  }

  // Bedtime and wake-up are clock readings; the only clue to the patient's
  // clock the server has is the offset baked into the client's date bounds.
  const offsetMin = (() => {
    const m = (params.from ?? params.to)?.match(/([+-])(\d{2}):?(\d{2})$/);
    if (!m) return 0;
    return (m[1] === "-" ? -1 : 1) * (Number(m[2]) * 60 + Number(m[3]));
  })();
  const hoursOf = (raw: unknown, shiftPastNoon: boolean): number => {
    const t = new Date(String(raw));
    if (isNaN(t.getTime())) return NaN;
    const local = new Date(t.getTime() + offsetMin * 60_000);
    const h = local.getUTCHours() + local.getUTCMinutes() / 60;
    return shiftPastNoon && h < 12 ? h + 24 : h;
  };

  // Health metrics for the whole reporting window, pulled once. Each attack's
  // chart then slices what it needs, which is what lets the attack card and the
  // old timeline section be the same component.
  const metricSeries: MetricSeries[] = [];
  if (migraines.length) {
    const starts = migraines.map((m) => new Date(m.start_at).getTime());
    const ends = migraines.map((m) => new Date(m.ended_at ?? m.start_at).getTime());
    const winFrom = new Date(Math.min(...starts) - WINDOW_BEFORE_DAYS * 86_400_000)
      .toISOString().slice(0, 10);
    const winTo = new Date(Math.max(...ends) + WINDOW_AFTER_DAYS * 86_400_000)
      .toISOString().slice(0, 10);

    const dailyDefs = METRICS.filter((def) => def.table !== "nutrition_records");
    const recordDefs = METRICS.filter((def) => def.table === "nutrition_records");

    await Promise.all([
      ...dailyDefs.map(async (def) => {
        const { data, error } = await sb.from(def.table)
          .select(`date,${def.column}`)
          .gte("date", winFrom).lte("date", winTo)
          .order("date", { ascending: true });
        if (error || !data) return;                 // metric simply isn't tracked
        const points = (data as unknown as Record<string, unknown>[])
          .map((r) => ({
            date: String(r.date),
            value: def.kind === "time"
              ? hoursOf(r[def.column], def.shiftPastNoon ?? false)
              : Number(r[def.column]),
          }))
          .filter((p) => Number.isFinite(p.value));
        if (points.length >= 2) {
          metricSeries.push({
            key: def.key, label: def.label, unit: def.unit, color: def.color, points,
          });
        }
      }),
      (async () => {
        if (!recordDefs.length) return;
        // Per-meal rows: one read covers every nutrient, then each metric is
        // summed (or max-risked) per day — the same aggregation the apps'
        // Insights charts do over nutrition_records.
        const cols = [...new Set(recordDefs.map((def) => def.column))].join(",");
        const { data, error } = await sb.from("nutrition_records")
          .select(`timestamp,${cols}`)
          .gte("timestamp", `${winFrom}T00:00:00Z`)
          .lte("timestamp", `${winTo}T23:59:59Z`)
          .order("timestamp", { ascending: true })
          .limit(5000);
        if (error || !data?.length) return;
        for (const def of recordDefs) {
          const byDate = new Map<string, number>();
          for (const r of data as unknown as Record<string, unknown>[]) {
            const date = String(r.timestamp).slice(0, 10);
            if (def.kind === "record_risk") {
              const v = riskNum(r[def.column]);
              if (v != null && v > (byDate.get(date) ?? -1)) byDate.set(date, v);
            } else {
              const v = Number(r[def.column]);
              if (Number.isFinite(v) && v > 0) byDate.set(date, (byDate.get(date) ?? 0) + v);
            }
          }
          const points = [...byDate.entries()].filter(([, v]) => v > 0)
            .sort((a, b) => (a[0] < b[0] ? -1 : 1))
            .map(([date, value]) => ({ date, value }));
          if (points.length >= 2) {
            metricSeries.push({
              key: def.key, label: def.label, unit: def.unit, color: def.color, points,
            });
          }
        }
      })(),
    ]);
  }

  // Registry order, not Promise.all completion order — the metrics page must
  // print the same way twice.
  const metricOrder = new Map(METRICS.map((def, i) => [def.key, i]));
  const chosenSeries = params.metricSeries?.length ? params.metricSeries : metricSeries;
  chosenSeries.sort((a, b) =>
    (metricOrder.get(a.key) ?? METRICS.length) - (metricOrder.get(b.key) ?? METRICS.length));

  // The comparison windows shared by Lifestyle context and Trends: the report
  // range against the equal period before it, or last-30 vs prior-30 for an
  // all-time report.
  const winFromD = params.from ? new Date(params.from) : null;
  const winToD = params.to ? new Date(params.to) : null;
  const scopedWin = !!(winFromD && winToD && !isNaN(winFromD.getTime())
    && !isNaN(winToD.getTime()) && winToD.getTime() > winFromD.getTime());
  const curTo = scopedWin ? winToD! : new Date(Date.now() + 86_400_000);
  const curFrom = scopedWin ? winFromD! : new Date(curTo.getTime() - 30 * 86_400_000);
  const priorFrom = new Date(curFrom.getTime() - (curTo.getTime() - curFrom.getTime()));

  // Lifestyle context: this period's daily averages against the equal period
  // before it. One doubled-window read per metric, split in code. Date range
  // only — episode filters do not move these numbers, by design.
  const lifestyle: ReportData["lifestyle"] = [];
  {
    const CURATED = ["sleep_dur", "sleep_score", "bedtime", "late_screen", "steps",
      "hydration", "caffeine", "alcohol", "tyramine", "stress", "hrv", "rhr", "mindfulness"];
    const keys = [...CURATED];
    for (const k of params.metricKeys ?? []) if (!keys.includes(k)) keys.push(k);
    const defs = keys.map((k) => METRICS.find((def) => def.key === k))
      .filter((def): def is MetricDef => !!def);
    const localDay = (t: Date) =>
      new Date(t.getTime() + offsetMin * 60_000).toISOString().slice(0, 10);
    const loDay = localDay(priorFrom), splitDay = localDay(curFrom), hiDay = localDay(curTo);

    const meanOf = (vals: number[]): number | null =>
      vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : null;
    const winSplit = (points: { date: string; value: number }[]) => {
      const prior: number[] = [], current: number[] = [];
      for (const p of points) {
        if (p.date < loDay || p.date >= hiDay) continue;
        (p.date < splitDay ? prior : current).push(p.value);
      }
      return { prior: meanOf(prior), current: meanOf(current) };
    };

    const results = new Map<string, { prior: number | null; current: number | null }>();
    const dailyLs = defs.filter((def) => def.table !== "nutrition_records");
    const recordLs = defs.filter((def) => def.table === "nutrition_records");
    await Promise.all([
      ...dailyLs.map(async (def) => {
        const { data, error } = await sb.from(def.table)
          .select(`date,${def.column}`)
          .gte("date", loDay).lt("date", hiDay)
          .order("date", { ascending: true });
        if (error || !data) return;
        const points = (data as unknown as Record<string, unknown>[])
          .map((r) => ({
            date: String(r.date),
            value: def.kind === "time"
              ? hoursOf(r[def.column], def.shiftPastNoon ?? false)
              : Number(r[def.column]),
          }))
          .filter((p) => Number.isFinite(p.value));
        if (points.length) results.set(def.key, winSplit(points));
      }),
      (async () => {
        if (!recordLs.length) return;
        const cols = [...new Set(recordLs.map((def) => def.column))].join(",");
        const { data, error } = await sb.from("nutrition_records")
          .select(`timestamp,${cols}`)
          .gte("timestamp", `${loDay}T00:00:00Z`)
          .lt("timestamp", `${hiDay}T00:00:00Z`)
          .order("timestamp", { ascending: true })
          .limit(5000);
        if (error || !data?.length) return;
        for (const def of recordLs) {
          const byDate = new Map<string, number>();
          for (const r of data as unknown as Record<string, unknown>[]) {
            const date = String(r.timestamp).slice(0, 10);
            if (def.kind === "record_risk") {
              // Days with records but no exposure count as 0 — an average
              // exposure level over days the patient actually logged food.
              const v = riskNum(r[def.column]);
              if (v != null && v > (byDate.get(date) ?? -1)) byDate.set(date, v);
            } else {
              const v = Number(r[def.column]);
              if (Number.isFinite(v) && v > 0) byDate.set(date, (byDate.get(date) ?? 0) + v);
            }
          }
          const points = [...byDate.entries()].map(([date, value]) => ({ date, value }));
          if (points.length) results.set(def.key, winSplit(points));
        }
      })(),
    ]);

    for (const def of defs) {
      const r = results.get(def.key);
      if (!r || (r.prior == null && r.current == null)) continue;
      lifestyle.push({
        key: def.key, label: def.label, unit: def.unit,
        prior: r.prior, current: r.current,
      });
    }
  }

  // Trends: per-item occurrence counts on this period's attacks vs the equal
  // period before. Date range only on BOTH sides — episodeIds are deliberately
  // ignored so the two windows are counted the same way.
  const trends: ReportData["trends"] = [];
  let trendStats: ReportData["trendStats"] = null;
  {
    type WinRow = { id: string; start_at: string; ended_at: string | null; severity: number | null };
    const rowsIn = async (lo: Date, hi: Date): Promise<WinRow[]> => {
      const { data } = await sb.from("migraines").select("id,start_at,ended_at,severity")
        .gte("start_at", lo.toISOString()).lt("start_at", hi.toISOString());
      return (data ?? []) as WinRow[];
    };
    const [curRows, priorRows] = await Promise.all([
      rowsIn(curFrom, curTo), rowsIn(priorFrom, curFrom),
    ]);
    const winStats = (rows: WinRow[]) => {
      const sevs = rows.map((r) => r.severity).filter((v): v is number => v != null);
      const durs = rows
        .map((r) => r.ended_at
          ? (Date.parse(r.ended_at) - Date.parse(r.start_at)) / 3_600_000 : null)
        .filter((v): v is number => v != null && v > 0);
      const avg = (a: number[]) => a.length ? a.reduce((x, y) => x + y, 0) / a.length : null;
      return { count: rows.length, avgSeverity: avg(sevs), avgDurationH: avg(durs) };
    };
    const curIds = curRows.map((r) => r.id), priorIds = priorRows.map((r) => r.id);
    const [curSym, priorSym] = await Promise.all([
      childrenFor(sb, "symptoms", curIds, "id,migraine_id,type,start_at"),
      childrenFor(sb, "symptoms", priorIds, "id,migraine_id,type,start_at"),
    ]);
    const perAttack = (n: number, attacks: number): number | null =>
      attacks ? n / attacks : null;
    trendStats = {
      prior: { ...winStats(priorRows),
        symptomsPerAttack: perAttack(priorSym.length, priorRows.length) },
      current: { ...winStats(curRows),
        symptomsPerAttack: perAttack(curSym.length, curRows.length) },
    };
    const TREND_TABLES: [ReportData["trends"][number]["kind"], string, "type" | "name"][] = [
      ["trigger", "triggers", "type"],
      ["prodrome", "prodromes", "type"],
      ["medicine", "medicines", "name"],
      ["relief", "reliefs", "type"],
      ["symptom", "symptoms", "type"],
    ];
    await Promise.all(TREND_TABLES.map(async ([kind, table, field]) => {
      const [cur, prior] = await Promise.all([
        childrenFor(sb, table, curIds, `id,migraine_id,${field},start_at`),
        childrenFor(sb, table, priorIds, `id,migraine_id,${field},start_at`),
      ]);
      const counts = new Map<string, { prior: number; current: number }>();
      const bump = (rows: ChildRow[], side: "prior" | "current") => {
        for (const r of rows) {
          const name = (field === "name" ? r.name : r.type)?.trim();
          if (!name) continue;
          const c = counts.get(name) ?? { prior: 0, current: 0 };
          c[side]++;
          counts.set(name, c);
        }
      };
      bump(cur, "current");
      bump(prior, "prior");
      for (const [name, c] of counts) {
        trends.push({ kind, name, prior: c.prior, current: c.current });
      }
    }));
  }

  return {
    lang,
    params,
    migraines,
    triggers, prodromes, medicines, reliefs, activities, locations, missedActivities,
    symptoms, painPoints, auraZones,
    correlations: (corr ?? []) as CorrelationStat[],
    treatmentTiming: (timing ?? []) as ReportData["treatmentTiming"],
    painMigration: (migration?.[0] ?? null) as ReportData["painMigration"],
    intradayResponse: (intraday ?? []) as ReportData["intradayResponse"],
    treatments, narratives, sideEffects,
    recommendations,
    recommendationsPending: (insightJobs?.length ?? 0) > 0,
    labelToMetricKeys,
    metricGroups,
    poolCategories,
    profile: (prof?.[0] ?? null) as Record<string, unknown> | null,
    symptomCategories,
    metricSeries: chosenSeries,
    lifestyle,
    trends,
    trendStats,
  };
}
