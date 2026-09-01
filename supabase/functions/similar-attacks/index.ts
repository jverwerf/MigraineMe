// supabase/functions/similar-attacks/index.ts
//
// Mid-attack forecast: given the user's open attack, find the past attacks
// that overlap with what has been logged on this one, and read duration /
// severity / what-helped off that set as a similarity-WEIGHTED middle.
// The more a past attack shares with this one, the harder it counts.
//
// Design (agreed 2026-08-31):
//  * No duration filter on the matched set. The client renders two states
//    instead: before the weighted median it says "usually over by X"; past
//    it, "running longer than your similar attacks usually do, longest ran Y".
//  * One floor only: fewer than 4 past attacks with any overlap -> nothing.
//  * Pain points deliberately NOT a feature (owner's call).
//  * Factors the correlation engine already found significant for this user
//    weigh double in the similarity score.
//  * During-attack rows (medicines/reliefs) on past attacks only count up to
//    the current attack's elapsed time, so a med taken at hour 30 of an old
//    attack doesn't make it "similar" to an attack that is 5 hours old.
//
// Numbers only in the response; every sentence is built client-side so the
// apps' own i18n does the talking.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

// ── platform config: the ONLY block that differs between the MigraineMe and
// MeSeries copies of this function. Keep everything below it byte-identical.
const EPISODE_TABLE = "migraines";
const FK_COL = "migraine_id";
const APP_SCOPED = false;
// ─────────────────────────────────────────────────────────────────────────

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

const MAX_DURATION_H = 168; // same sanity cut as compute-correlation-stats
const MIN_OVERLAP_ATTACKS = 4;
const EARLY_TREATMENT_H = 2;

type Feature = { key: string; kind: string; name: string; band: string | null; weight: number };

interface EpisodeRow {
  id: string;
  start_at: string;
  ended_at: string | null;
  severity: number | null;
  aura_locations: unknown;
  aura_duration_minutes: number | null;
}

interface ChildRow {
  type?: string | null;
  name?: string | null;
  start_at?: string | null;
  [key: string]: unknown;
}

const hoursBetween = (a: string, b: string): number =>
  (new Date(b).getTime() - new Date(a).getTime()) / 3_600_000;

const norm = (s: string): string => s.trim().toLowerCase();

function sleepBand(h: number): string | null {
  if (h < 6) return "under6";
  if (h < 7) return "6to7";
  if (h < 8) return "7to8";
  return "over8";
}

// Weighted quantile over (value, weight) pairs; linear between points.
function weightedQuantile(pairs: [number, number][], q: number): number | null {
  const rows = pairs.filter(([, w]) => w > 0).sort((a, b) => a[0] - b[0]);
  if (!rows.length) return null;
  const total = rows.reduce((s, [, w]) => s + w, 0);
  let acc = 0;
  for (const [v, w] of rows) {
    acc += w;
    if (acc / total >= q) return v;
  }
  return rows[rows.length - 1][0];
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    const supabase = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_ANON_KEY") ?? "",
      { global: { headers: { Authorization: req.headers.get("Authorization") ?? "" } } },
    );
    const { data: { user } } = await supabase.auth.getUser();
    if (!user) return json({ error: "Unauthorized" }, 401);

    const body = await req.json().catch(() => ({}));
    const appId: string | null = APP_SCOPED ? (body?.app_id ?? "vertigo") : null;
    const targetId: string | null = body?.[FK_COL] ?? body?.episode_id ?? null;

    const scoped = <T>(q: T): T =>
      // deno-lint-ignore no-explicit-any
      APP_SCOPED ? (q as any).eq("app_id", appId) : q;

    // ── 1. episodes ──────────────────────────────────────────────────────
    const { data: epRows, error: epErr } = await scoped(
      supabase.from(EPISODE_TABLE)
        .select("id, start_at, ended_at, severity, aura_locations, aura_duration_minutes")
        .eq("user_id", user.id)
        .not("start_at", "is", null)
        .neq("source", "import"),
    ).order("start_at", { ascending: false });
    if (epErr) return json({ ok: false, error: epErr.message }, 500);

    const episodes = (epRows ?? []) as EpisodeRow[];
    const open = targetId
      ? episodes.find((e) => e.id === targetId && !e.ended_at) ?? null
      // no id given: the longest-running open one, same as the Monitor row
      : episodes.filter((e) => !e.ended_at)
          .sort((a, b) => a.start_at.localeCompare(b.start_at))[0] ?? null;
    if (!open) return json({ ok: true, status: "no_open_attack" });

    const nowIso = new Date().toISOString();
    const elapsedH = hoursBetween(open.start_at, nowIso);

    const completed = episodes
      .map((e) => ({ ...e, durationH: e.ended_at ? hoursBetween(e.start_at, e.ended_at) : null }))
      .filter((e) => e.id !== open.id && e.durationH != null && e.durationH > 0 && e.durationH < MAX_DURATION_H) as
      (EpisodeRow & { durationH: number })[];

    if (completed.length < MIN_OVERLAP_ATTACKS) {
      return json({ ok: true, status: "insufficient", matchedCount: completed.length, elapsedHours: elapsedH });
    }

    // ── 2. linked rows, one query per table ─────────────────────────────
    const child = async (table: string, cols: string): Promise<ChildRow[]> => {
      const { data } = await scoped(
        supabase.from(table).select(`${cols}, ${FK_COL}`).eq("user_id", user.id),
      );
      return (data ?? []) as unknown as ChildRow[];
    };
    const [triggers, prodromes, symptoms, medicines, reliefs, activities, locations] =
      await Promise.all([
        child("triggers", "type, start_at, source"),
        child("prodromes", "type, start_at, source"),
        child("symptoms", "type"),
        child("medicines", "name, start_at, source, relief_scale"),
        child("reliefs", "type, start_at, source, relief_scale"),
        child("activities", "type"),
        child("locations", "type"),
      ]);

    const byEp = (rows: ChildRow[]): Map<string, ChildRow[]> => {
      const m = new Map<string, ChildRow[]>();
      for (const r of rows) {
        const id = r[FK_COL] as string | null;
        if (!id || r.source === "import") continue;
        (m.get(id) ?? m.set(id, []).get(id)!).push(r);
      }
      return m;
    };
    const tByEp = byEp(triggers), pByEp = byEp(prodromes), sByEp = byEp(symptoms),
      mByEp = byEp(medicines), rByEp = byEp(reliefs), aByEp = byEp(activities), lByEp = byEp(locations);

    // ── 3. daily metrics for the start dates we care about ──────────────
    const dates = new Set<string>([open.start_at.substring(0, 10)]);
    for (const e of completed) dates.add(e.start_at.substring(0, 10));
    const dateList = [...dates];
    const metricByDate = async (table: string, column: string): Promise<Record<string, number>> => {
      const { data } = await supabase.from(table)
        .select(`date, ${column}`)
        .eq("user_id", user.id)
        .in("date", dateList)
        .not(column, "is", null);
      const out: Record<string, number> = {};
      for (const r of (data ?? []) as unknown as Record<string, unknown>[]) {
        out[r.date as string] = Number(r[column]);
      }
      return out;
    };
    const [sleepMap, humidityMap, tempMap] = await Promise.all([
      metricByDate("sleep_duration_daily", "value_hours"),
      metricByDate("user_weather_daily", "humidity_pct_mean"),
      metricByDate("user_weather_daily", "temp_c_mean"),
    ]);

    // ── 4. significance weights from the correlation engine ────────────
    const sigNames = new Set<string>();
    {
      const { data } = await scoped(
        supabase.from("correlation_stats")
          .select("factor_name, p_value")
          .eq("user_id", user.id),
      );
      for (const r of (data ?? []) as { factor_name: string | null; p_value: number | null }[]) {
        if (r.factor_name && r.p_value != null && r.p_value <= 0.1) sigNames.add(norm(r.factor_name));
      }
    }
    const weightOf = (name: string): number => (sigNames.has(norm(name)) ? 2 : 1);

    // ── 5. feature extraction ───────────────────────────────────────────
    const featuresOf = (ep: EpisodeRow, cutoffH: number): Feature[] => {
      const out: Feature[] = [];
      const seen = new Set<string>();
      const push = (kind: string, name: string, band: string | null, weightName: string) => {
        const key = `${kind}:${norm(name)}${band ? `|${band}` : ""}`;
        if (seen.has(key)) return;
        seen.add(key);
        out.push({ key, kind, name, band, weight: weightOf(weightName) });
      };
      for (const r of tByEp.get(ep.id) ?? []) if (r.type) push("trigger", r.type, null, r.type);
      for (const r of pByEp.get(ep.id) ?? []) if (r.type) push("prodrome", r.type, null, r.type);
      for (const r of sByEp.get(ep.id) ?? []) if (r.type) push("symptom", r.type, null, r.type);
      for (const r of aByEp.get(ep.id) ?? []) if (r.type) push("activity", r.type, null, r.type);
      for (const r of lByEp.get(ep.id) ?? []) if (r.type) push("location", r.type, null, r.type);
      // During-attack treatments: only what happened by the same elapsed point,
      // bucketed early (first 2h) vs later.
      const treat = (rows: ChildRow[], kind: string, nameKey: "name" | "type") => {
        for (const r of rows) {
          const nm = r[nameKey] as string | null;
          if (!nm) continue;
          let band: string | null = null;
          if (r.start_at) {
            const off = hoursBetween(ep.start_at, r.start_at as string);
            if (off > cutoffH) continue;
            band = off <= EARLY_TREATMENT_H ? "early" : "later";
          }
          push(kind, nm, band, nm);
        }
      };
      treat(mByEp.get(ep.id) ?? [], "med", "name");
      treat(rByEp.get(ep.id) ?? [], "relief", "type");
      const d = ep.start_at.substring(0, 10);
      const sl = sleepMap[d];
      if (sl != null) {
        const b = sleepBand(sl);
        if (b) push("sleep", "Sleep duration", b, "Sleep duration");
      }
      const hu = humidityMap[d];
      if (hu != null && (hu >= 75 || hu <= 35)) push("humidity", "Humidity", hu >= 75 ? "high" : "low", "Humidity");
      const te = tempMap[d];
      if (te != null && (te >= 27 || te <= 3)) push("temp", "Temperature", te >= 27 ? "hot" : "cold", "Temperature");
      const aura = (Array.isArray(ep.aura_locations) && ep.aura_locations.length > 0) ||
        (ep.aura_duration_minutes ?? 0) > 0;
      if (aura) push("aura", "Aura", null, "Aura");
      return out;
    };

    const current = featuresOf(open, elapsedH);
    if (!current.length) {
      return json({ ok: true, status: "nothing_logged", matchedCount: 0, elapsedHours: elapsedH });
    }
    const denom = current.reduce((s, f) => s + f.weight, 0);
    const currentKeys = new Map(current.map((f) => [f.key, f]));
    // Bucket-agnostic med/relief keys of the current attack, for takenThisTime.
    const takenNow = new Map<string, string | null>(); // kind:name -> band
    for (const f of current) if (f.kind === "med" || f.kind === "relief") takenNow.set(`${f.kind}:${norm(f.name)}`, f.band);

    // ── 6. similarity per completed attack ──────────────────────────────
    type Scored = { ep: EpisodeRow & { durationH: number }; sim: number; feats: Set<string>; featsLoose: Set<string> };
    const scored: Scored[] = [];
    for (const ep of completed) {
      const feats = featuresOf(ep, Math.min(elapsedH, ep.durationH));
      let matched = 0;
      const keySet = new Set(feats.map((f) => f.key));
      // loose (bucket-agnostic) set for the what-helped split
      const loose = new Set(feats.map((f) => `${f.kind}:${norm(f.name)}`));
      for (const f of current) if (keySet.has(f.key)) matched += f.weight;
      const sim = matched / denom;
      if (sim > 0) scored.push({ ep, sim, feats: keySet, featsLoose: loose });
    }

    if (scored.length < MIN_OVERLAP_ATTACKS) {
      return json({ ok: true, status: "insufficient", matchedCount: scored.length, elapsedHours: elapsedH });
    }

    // ── 7. weighted middle ─────────────────────────────────────────────
    const durPairs: [number, number][] = scored.map((s) => [s.ep.durationH, s.sim]);
    const sevPairs: [number, number][] = scored
      .filter((s) => s.ep.severity != null)
      .map((s) => [s.ep.severity as number, s.sim]);
    const duration = {
      p25: weightedQuantile(durPairs, 0.25),
      median: weightedQuantile(durPairs, 0.5),
      p75: weightedQuantile(durPairs, 0.75),
      longest: Math.max(...scored.map((s) => s.ep.durationH)),
    };
    const severity = sevPairs.length
      ? { median: weightedQuantile(sevPairs, 0.5), worst: Math.max(...sevPairs.map(([v]) => v)) }
      : null;

    // ── 8. shared factors, for the "why these matched" list ────────────
    const sharedFactors = current.map((f) => {
      const count = scored.filter((s) => s.feats.has(f.key)).length;
      return {
        kind: f.kind, name: f.name, band: f.band,
        count, of: scored.length,
        significant: f.weight > 1,
        usable: count >= 3,
      };
    }).sort((a, b) => (b.significant ? 1 : 0) - (a.significant ? 1 : 0) || b.count - a.count);

    // ── 9. what helped ─────────────────────────────────────────────────
    //
    // Two lists, same honest computation:
    //  * `helped`        — inside the matched set only ("attacks like this").
    //  * `helpedOverall` — across ALL completed attacks ("you in general").
    // Two reads per treatment, both aware of confounding:
    //  * relief: the user's own relief_scale ratings from attacks where they
    //    took it — a within-attack answer, immune to the "mild attacks never
    //    get a med" skew.
    //  * duration: took-vs-not compared ONLY inside the same severity band
    //    (1-3 / 4-6 / 7-10). An unstratified split makes every med look bad,
    //    because the attacks that never needed one are the short mild ones.
    const RELIEF_NUM: Record<string, number> = {
      HIGH: 3, STRONG: 3, MILD: 2, MODERATE: 2, LOW: 1,
    };
    const bandOfSev = (sev: number | null): number | null =>
      sev == null ? null : sev <= 3 ? 0 : sev <= 6 ? 1 : 2;

    // Per-episode best relief rating per treatment key.
    const reliefByEpTreat = new Map<string, number>(); // `${epId}|${key}` -> 0..3
    const noteRelief = (rows: ChildRow[], kind: string, nameKey: "name" | "type") => {
      for (const r of rows) {
        const id = r[FK_COL] as string | null;
        const nm = r[nameKey] as string | null;
        if (!id || !nm || r.source === "import") continue;
        const num = RELIEF_NUM[String(r.relief_scale ?? "").toUpperCase()] ?? 0;
        const k = `${id}|${kind}:${norm(nm)}`;
        reliefByEpTreat.set(k, Math.max(reliefByEpTreat.get(k) ?? 0, num));
      }
    };
    noteRelief(medicines, "med", "name");
    noteRelief(reliefs, "relief", "type");

    // Prefer original casing from the raw rows.
    const casing = new Map<string, string>();
    for (const r of medicines) if (r.name) casing.set(`med:${norm(r.name as string)}`, r.name as string);
    for (const r of reliefs) if (r.type) casing.set(`relief:${norm(r.type as string)}`, r.type as string);

    interface HelpedRow {
      kind: string; name: string; takenThisTime: boolean; atBand: string | null;
      deltaDurationH: number | null; clear: boolean;
      reliefHigh: number; reliefSome: number; reliefNone: number; reliefRated: number;
      tookCount: number; notCount: number;
    }
    type PoolRow = { ep: EpisodeRow & { durationH: number }; sim: number; featsLoose: Set<string> };

    const computeHelped = (pool: PoolRow[]): HelpedRow[] => {
      const names = new Set<string>();
      for (const row of pool) {
        for (const k of row.featsLoose) {
          if (k.startsWith("med:") || k.startsWith("relief:")) names.add(k);
        }
      }
      const out: HelpedRow[] = [];
      for (const k of names) {
        const kind = k.startsWith("med:") ? "med" : "relief";
        const took = pool.filter((row) => row.featsLoose.has(k));
        const not = pool.filter((row) => !row.featsLoose.has(k));
        if (took.length < 3) continue;

        let reliefHigh = 0, reliefSome = 0, reliefNone = 0, reliefRated = 0;
        for (const row of took) {
          const num = reliefByEpTreat.get(`${row.ep.id}|${k}`);
          if (num == null) continue;
          reliefRated++;
          if (num >= 3) reliefHigh++;
          else if (num >= 1) reliefSome++;
          else reliefNone++;
        }

        let deltaDur: number | null = null;
        {
          let wSum = 0, dSum = 0;
          for (const band of [0, 1, 2]) {
            const t = took.filter((row) => bandOfSev(row.ep.severity) === band);
            const n = not.filter((row) => bandOfSev(row.ep.severity) === band);
            if (t.length < 3 || n.length < 3) continue;
            const md = (rows: PoolRow[]): number | null =>
              weightedQuantile(rows.map((row) => [row.ep.durationH, row.sim] as [number, number]), 0.5);
            const dT = md(t), dN = md(n);
            if (dT == null || dN == null) continue;
            const w = t.length + n.length;
            dSum += (dN - dT) * w;
            wSum += w;
          }
          if (wSum > 0) deltaDur = dSum / wSum;
        }

        const clear = deltaDur != null && Math.abs(deltaDur) >= 1;
        out.push({
          kind, name: casing.get(k) ?? k.slice(kind.length + 1),
          takenThisTime: takenNow.has(k), atBand: takenNow.get(k) ?? null,
          deltaDurationH: deltaDur, clear,
          reliefHigh, reliefSome, reliefNone, reliefRated,
          tookCount: took.length, notCount: not.length,
        });
      }
      out.sort((a, b) =>
        (b.reliefHigh - a.reliefHigh) || ((b.deltaDurationH ?? 0) - (a.deltaDurationH ?? 0)));
      return out;
    };

    const helped = computeHelped(
      scored.map((sc) => ({ ep: sc.ep, sim: sc.sim, featsLoose: sc.featsLoose })));

    // General pool: every completed attack, weight 1, whole-attack treatments.
    const overallPool: PoolRow[] = completed.map((ep) => {
      const loose = new Set<string>();
      for (const r of mByEp.get(ep.id) ?? []) if (r.name) loose.add(`med:${norm(r.name as string)}`);
      for (const r of rByEp.get(ep.id) ?? []) if (r.type) loose.add(`relief:${norm(r.type as string)}`);
      return { ep, sim: 1, featsLoose: loose };
    });
    const helpedOverall = computeHelped(overallPool)
      .filter((h) => h.clear || h.reliefRated > 0);

    // ── 10. match strength ─────────────────────────────────────────────
    const topSims = scored.map((s) => s.sim).sort((a, b) => b - a).slice(0, 10);
    const matchStrength = topSims.reduce((s, v) => s + v, 0) / topSims.length;

    return json({
      ok: true,
      status: "ok",
      attackId: open.id,
      startAt: open.start_at,
      elapsedHours: elapsedH,
      matchedCount: scored.length,
      matchStrength,
      duration,
      severity,
      sharedFactors: sharedFactors.slice(0, 10),
      helped: helped.slice(0, 6),
      helpedOverall: helpedOverall.slice(0, 6),
    });
  } catch (err) {
    return json({ ok: false, error: (err as Error).message ?? "Internal error" }, 500);
  }
});

function json(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}
