// supabase/functions/generate-treatment-narrative/index.ts
//
// Given a treatment_regimens.id, fetches efficacy + confounders +
// trigger-shift + side-effect logs, asks GPT-4o-mini for a plain-English
// paragraph, and caches the result in treatment_narrative_cache keyed
// by a stats fingerprint. Regenerates only when the fingerprint changes
// or the cached value is older than 7 days.

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";
import { asLang, languageDirective } from "../_shared/i18n.ts";
import { crypto } from "https://deno.land/std@0.224.0/crypto/mod.ts";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...CORS },
  });
}

async function fingerprint(obj: unknown): Promise<string> {
  const enc = new TextEncoder().encode(JSON.stringify(obj));
  const buf = await crypto.subtle.digest("SHA-256", enc);
  return Array.from(new Uint8Array(buf)).map((b) => b.toString(16).padStart(2, "0")).join("").slice(0, 32);
}

const SYSTEM_PROMPT = `You are a calm, clear health-data assistant explaining a migraine treatment's effects to its user. You are NOT a doctor. You write a single short paragraph (4-6 sentences), grounded only in the numbers provided. Plain English, no medical jargon, no diagnoses. Tone: matter-of-fact, supportive, neutral.

Rules:
- Never invent numbers. If a stat is null or missing, do not mention it.
- Use the band labels: "Working well", "Showing progress", "Some effect", "Not noticeable yet", "Not enough data".
- Mention confounders only when they shifted (up/down). Frame as context, not as drug effects.
- For drugs under 8 weeks active, remind the user the readout is preliminary.
- If side effects are present, mention them once and note if they match the drug's known profile.
- Suggest a next-step at the end (e.g. "Week 8 is the right time to evaluate" or "Worth flagging the [symptom] to your doctor"). Never recommend dose changes.
- Output the paragraph only. No headings, no bullet points, no markdown.`;

function buildUserPrompt(stats: {
  regimen: { name: string; kind: string; amount: string | null; frequency: string | null; weeks_active: number };
  efficacy: any;
  confounders: any[];
  triggers: any[];
  side_effects: { date: string; pills: string[]; notes: string | null }[];
}): string {
  const lines: string[] = [];
  lines.push(`Treatment: ${stats.regimen.name} (${stats.regimen.kind})`);
  if (stats.regimen.amount || stats.regimen.frequency) {
    lines.push(`Dose: ${[stats.regimen.amount, stats.regimen.frequency].filter(Boolean).join(", ")}`);
  }
  lines.push(`Weeks active: ${stats.regimen.weeks_active}`);
  lines.push(``);
  lines.push(`Efficacy:`);
  lines.push(`  baseline MMD ${stats.efficacy.baseline_mmd} -> rolling MMD ${stats.efficacy.rolling_mmd}`);
  lines.push(`  pct change ${stats.efficacy.pct_change_mmd}%`);
  lines.push(`  band: ${stats.efficacy.band}`);
  if (stats.efficacy.baseline_severity != null && stats.efficacy.rolling_severity != null) {
    lines.push(`  severity ${stats.efficacy.baseline_severity} -> ${stats.efficacy.rolling_severity}`);
  }
  if (stats.efficacy.baseline_duration_h != null && stats.efficacy.rolling_duration_h != null) {
    lines.push(`  duration hours ${stats.efficacy.baseline_duration_h} -> ${stats.efficacy.rolling_duration_h}`);
  }
  lines.push(`  attacks tracked in rolling window: ${stats.efficacy.n_attacks_rolling}`);
  lines.push(`  ramp_complete: ${stats.efficacy.ramp_complete}`);

  if (stats.confounders.length > 0) {
    lines.push(``);
    lines.push(`Confounders (baseline -> rolling, direction):`);
    for (const c of stats.confounders) {
      if (c.direction !== "stable" && c.direction !== "unknown") {
        lines.push(`  ${c.metric}: ${c.baseline_value} -> ${c.rolling_value} (${c.direction})`);
      }
    }
  }

  if (stats.triggers.length > 0) {
    lines.push(``);
    lines.push(`Trigger profile shift (top triggers):`);
    for (const t of stats.triggers) {
      lines.push(`  ${t.trigger_type}: rank ${t.baseline_rank ?? "-"} -> ${t.rolling_rank ?? "-"}`);
    }
  }

  if (stats.side_effects.length > 0) {
    lines.push(``);
    lines.push(`Side-effect logs during this regimen:`);
    for (const s of stats.side_effects) {
      const pills = (s.pills ?? []).join(", ");
      const note = s.notes ? ` ("${s.notes.slice(0, 120)}")` : "";
      lines.push(`  ${s.date}: ${pills || "(no pills)"}${note}`);
    }
  }

  return lines.join("\n");
}

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  const url = Deno.env.get("SUPABASE_URL");
  const anon = Deno.env.get("SUPABASE_ANON_KEY");
  const svc = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const openaiKey = Deno.env.get("OPENAI_API_KEY");
  if (!url || !anon || !svc || !openaiKey) return json({ error: "missing env" }, 500);

  const authHeader = req.headers.get("Authorization") ?? "";
  const accessToken = authHeader.replace(/^Bearer /i, "");
  if (!accessToken) return json({ error: "no auth" }, 401);

  let body: { regimen_id?: string; force?: boolean } = {};
  try { body = await req.json(); } catch { return json({ error: "bad json" }, 400); }
  const regimenId = body.regimen_id;
  if (!regimenId) return json({ error: "regimen_id required" }, 400);

  // User-scoped client (RLS applies)
  const userClient = createClient(url, anon, {
    global: { headers: { Authorization: `Bearer ${accessToken}` } },
  });
  // Service client for cache write
  const admin = createClient(url, svc);

  // Load regimen + run RPCs in parallel
  const [{ data: regimen }, { data: efficacy }, { data: confounders }, { data: triggers }] = await Promise.all([
    userClient.from("treatment_regimens").select("id, user_id, name, kind, amount, frequency, start_date, stop_date").eq("id", regimenId).maybeSingle(),
    userClient.rpc("compute_treatment_efficacy", { p_regimen: regimenId }),
    userClient.rpc("compute_treatment_confounders", { p_regimen: regimenId }),
    userClient.rpc("compute_treatment_trigger_shift", { p_regimen: regimenId }),
  ]);

  if (!regimen) return json({ error: "regimen not found" }, 404);
  const eff = (efficacy && efficacy[0]) || null;
  if (!eff) return json({ error: "no efficacy data" }, 500);

  // Side-effect logs that fall inside the regimen's active window
  const endDate = regimen.stop_date ?? new Date().toISOString().slice(0, 10);
  const { data: seLogs } = await userClient
    .from("treatment_side_effect_logs")
    .select("log_date, selected_symptoms, notes")
    .gte("log_date", regimen.start_date)
    .lte("log_date", endDate)
    .order("log_date", { ascending: true })
    .limit(25);

  const stats = {
    regimen: {
      name: regimen.name,
      kind: regimen.kind,
      amount: regimen.amount,
      frequency: regimen.frequency,
      weeks_active: eff.weeks_active,
    },
    efficacy: eff,
    confounders: (confounders ?? []) as any[],
    triggers: (triggers ?? []) as any[],
    side_effects: (seLogs ?? []).map((s: any) => ({ date: s.log_date, pills: s.selected_symptoms ?? [], notes: s.notes })),
  };

  // The narrative is model prose generated in the user's display language, so
  // the language is part of what the cache is a cache OF. Without it in the
  // fingerprint, switching language kept serving the old-language paragraph
  // until the stats happened to move. English is left out of the hash input so
  // every pre-existing (English) cache row stays valid.
  const { data: langRow } = await admin
    .from("profiles").select("lang").eq("user_id", regimen.user_id).maybeSingle();
  const narrativeLang = asLang((langRow as { lang?: unknown } | null)?.lang);

  const fp = await fingerprint(narrativeLang === "en" ? stats : { stats, lang: narrativeLang });

  // Cache check
  if (!body.force) {
    const { data: cached } = await userClient
      .from("treatment_narrative_cache")
      .select("narrative, stats_fingerprint, generated_at")
      .eq("regimen_id", regimenId)
      .maybeSingle();
    if (cached && cached.stats_fingerprint === fp) {
      const age = (Date.now() - new Date(cached.generated_at).getTime()) / 1000 / 60 / 60 / 24;
      if (age < 7) {
        return json({ narrative: cached.narrative, cached: true, generated_at: cached.generated_at });
      }
    }
  }

  // OpenAI call
  // Model-written prose, so the language has to be asked for (narrativeLang,
  // resolved above so it could join the fingerprint).
  const oaiResp = await fetch("https://api.openai.com/v1/chat/completions", {
    method: "POST",
    headers: { Authorization: `Bearer ${openaiKey}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      model: "gpt-4o-mini",
      temperature: 0.4,
      messages: [
        { role: "system", content: SYSTEM_PROMPT + languageDirective(narrativeLang) },
        { role: "user", content: buildUserPrompt(stats) },
      ],
    }),
  });
  if (!oaiResp.ok) {
    const errText = await oaiResp.text();
    return json({ error: "openai_failed", detail: errText.slice(0, 500) }, 502);
  }
  const oaiJson = await oaiResp.json();
  const narrative: string = oaiJson?.choices?.[0]?.message?.content?.trim() ?? "";
  if (!narrative) return json({ error: "empty_narrative" }, 502);

  // Cache write (service role — bypasses RLS, but we set user_id correctly)
  // For MeSeries variant, app_id is also stored; we read it from the regimen row.
  const cachePayload: Record<string, unknown> = {
    regimen_id: regimenId,
    user_id: regimen.user_id,
    stats_fingerprint: fp,
    narrative,
    generated_at: new Date().toISOString(),
  };
  // If table has app_id (MeSeries variant), include it.
  if ("app_id" in (regimen as any)) {
    cachePayload.app_id = (regimen as any).app_id;
  }
  await admin.from("treatment_narrative_cache").upsert(cachePayload, { onConflict: "regimen_id" });

  return json({ narrative, cached: false, generated_at: cachePayload.generated_at });
});
