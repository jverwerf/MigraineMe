// supabase/functions/import-history/index.ts
//
// Import Anything: turn a person's migraine history from another app, a
// spreadsheet, an Apple Health export or plain notes into MigraineMe rows that
// behave exactly like rows the app wrote itself, tagged source='import'.
//
// Design: docs/import-anything-spec.md (2026-09-14).
//
// Pipeline: sniff -> adapter -> canonical model -> note mining -> linking
//           -> preview (stored in import_batches) -> commit -> verify -> manifest
//
// Free users may import (journal only). Paid subscribers may also tick categories
// that count in insights. Trial users are treated as free for the insights part.
//
// Actions (POST, user JWT required, verify_jwt stays true):
//   { action: "preview", file_name, content_text | content_base64, timezone? }
//       -> { import_id, preview }
//   { action: "commit",  import_id, answers?: { "<class>|<phrase>": "<label>" | null },
//                        accept_regimens?: [names], cutoff_date?: "YYYY-MM-DD" | null,
//                        assumptions?: { timezone?, end_fill_hours?: number, no_time_hour?: number },
//                        exclude_refs?: [attack ref], attack_edits?: { "<ref>": AttackEdit } }
//   AttackEdit = { start_local?, end_local?, severity?: number|null, symptoms?, prodromes?, triggers?,
//                  foods?, reliefs?, activities?, missed?, location?, meds?: [{name, dose_value, dose_unit, taken_local, effect}],
//                  pain_locations?, aura?: { present, zones, duration_minutes }, notes? }
//   A list given in an edit REPLACES that list on the attack; removing an item = sending the list without it.
//   doses?: { "<medicine label>": "40 mg" | "2 tablets" }  fills the dose on that medicine's entries that have none
//   engine_use?: { attacks, medicines, reliefs, places_activities, triggers, foods, period, treatments, metric:<key> : bool }
//     Per category: use for insights (true) or journal only (false). Defaults come from preview.engine_use[].recommended.
//     Rows for insights carry source='import_scored' (the engine's `source <> 'import'` filters let them through).
//     The engine has NO source filter on triggers/prodromes/activities, so unticked triggers, warning signs and food tags
//     are not written as rows at all: they are appended to the attack's note as text ("Triggers you tagged: chocolate, stress"),
//     visible in the journal, invisible to the engine. Unticked daily metrics, foods-as-nutrition, period and treatments are not written.
//       -> { import_id, written, verify }
//   { action: "undo",    import_id }
//       -> { import_id, deleted }
//   { action: "status",  import_id }
//       -> the batch row
//
// Judgment lives ONLY in the model calls (column map, note mining, linking) and
// every judgment is tiered certain / likely / ambiguous. Ambiguous phrases are
// never written as a specific label: they become a question for the person and,
// unanswered, are written in the person's own words. Scale conversion, timezone
// conversion, date parsing and every insert are deterministic.

import { createClient, SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2";

const OPENAI_URL = "https://api.openai.com/v1/chat/completions";
const MODEL_REASON = "gpt-4o";        // column map, linking, note mining, text extraction
const PRICE: Record<string, [number, number]> = { "gpt-4o": [2.5, 10], "gpt-4o-mini": [0.15, 0.6] }; // USD per 1M in/out
const COST_CAP_USD = 6;
const MAX_CONTENT_BYTES = 6 * 1024 * 1024;

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

// ─────────────────────────────────────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────────────────────────────────────

type Tier = "certain" | "likely" | "ambiguous";
type SourceClass = "type" | "symptom" | "prodrome" | "postdrome" | "trigger" | "medicine" | "relief" |
  "activity" | "location" | "missed" | "food" | "pain_location" | "side_effect";
type Concept = SourceClass | "regimen" | "drop";

interface Med { name: string; dose_value: number | null; dose_unit: string | null; taken_local: string | null; effect: string | null; side_effect: string | null; }
interface Relief { name: string; start_local: string | null; end_local: string | null; effect: string | null; side_effect?: string | null; }
interface Missed { name: string; reasons: string[]; anticipated: boolean; }
interface SideEffectLog { symptoms: string[]; notes: string | null; regimen: string | null; }
interface RegimenMention { name: string; dose_value: number | null; dose_unit: string | null; frequency: string | null; kind: "drug" | "lifestyle" | "device"; started: string | null; stopped: string | null; }
interface Attack {
  ref: string;
  start_local: string;          // YYYY-MM-DDTHH:MM:00, wall clock
  end_local: string | null;
  end_inferred: boolean;
  intensity: { value: number | null; scale: string };
  types: string[];
  symptoms: string[];
  pain_locations: string[];     // source words OR canonical ids (marked by canonical_pain=true)
  canonical_pain: boolean;
  aura: { present: boolean; zones: string[]; duration_minutes: number | null };
  prodromes: string[];
  postdromes: string[];
  triggers: string[];
  meds: Med[];
  reliefs: Relief[];
  activities: string[];
  missed: string[];
  location: string | null;
  foods: string[];
  notes: string | null;
  provenance: { row: number | null; page: number | null; quote: string | null };
  symptom_severity: Record<string, string>;   // symptom label -> MILD|MODERATE|SEVERE
  times: Record<string, string>;              // "prodrome|<name>" / "trigger|<name>" / "activity_end|<name>" -> local time
  missed_detail: Missed[];
}
interface Day {
  date: string;
  metrics: Record<string, number>;            // sleep_hours, hydration_ml, weight_kg, steps, sleep_disturbances, mindfulness_minutes, body_fat_pct, bp_systolic, bp_diastolic, blood_glucose, mood, stress
  times: Record<string, string>;              // bedtime, wake_time -> local ISO
  foods: { name: string; time_local: string | null }[];
  period: "start" | "end" | "flow" | null;
  daily_meds: Med[];
  triggers: string[];
  prodromes: string[];
  missed: Missed[];
  side_effects: SideEffectLog[];
  regimens: RegimenMention[];
  notes?: string | null;
}
interface VocabEntry { phrase: string; count: number; examples: string[]; first: string; last: string; }
interface Model {
  source: { app_guess: string | null; kind: string; file_name: string; sha256: string; shape: Record<string, unknown> };
  timezone: string;
  attacks: Attack[];
  days: Day[];
  vocabulary: Record<SourceClass, VocabEntry[]>;
  inferences: string[];
}
interface LinkDecision {
  class: SourceClass; phrase: string; concept: Concept; tier: Tier;
  pool_label: string | null;
  new_item: { label: string; category: string | null; icon_key: string | null } | null;
  reasoning: string; count: number;
  pain_ids?: string[];
  also?: { concept: Concept; pool_label: string | null; new_item: { label: string; category: string | null; icon_key: string | null } | null }[];
  siblings?: string[];
}
interface Usage { model: string; prompt: number; completion: number; }

// ─────────────────────────────────────────────────────────────────────────────
// Small deterministic helpers
// ─────────────────────────────────────────────────────────────────────────────

const pad2 = (n: number) => String(n).padStart(2, "0");
const uniq = <T,>(a: T[]) => Array.from(new Set(a));
const norm = (s: string) => s.trim().replace(/\s+/g, " ");
const lower = (s: string) => norm(s).toLowerCase();

async function sha256Hex(s: string): Promise<string> {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return Array.from(new Uint8Array(buf)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

// Local wall clock ("YYYY-MM-DDTHH:MM:00") + IANA zone -> UTC ISO. Iterative offset search, DST-safe.
function localToUtcIso(local: string, tz: string): string {
  const m = local.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/);
  if (!m) throw new Error(`bad local time ${local}`);
  const asUtc = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], 0);
  let guess = asUtc;
  for (let i = 0; i < 3; i++) {
    const off = tzOffsetMinutes(new Date(guess), tz);
    const next = asUtc - off * 60_000;
    if (next === guess) break;
    guess = next;
  }
  return new Date(guess).toISOString().replace(/\.\d{3}Z$/, "Z");
}
function tzOffsetMinutes(d: Date, tz: string): number {
  const f = new Intl.DateTimeFormat("en-US", { timeZone: tz, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit" });
  const p: Record<string, string> = {};
  for (const part of f.formatToParts(d)) p[part.type] = part.value;
  const asIf = Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute, +p.second);
  return Math.round((asIf - d.getTime()) / 60_000);
}
function addMinutesLocal(local: string, mins: number): string {
  const m = local.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/)!;
  const d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]));
  d.setUTCMinutes(d.getUTCMinutes() + mins);
  return `${d.getUTCFullYear()}-${pad2(d.getUTCMonth() + 1)}-${pad2(d.getUTCDate())}T${pad2(d.getUTCHours())}:${pad2(d.getUTCMinutes())}:00`;
}
function localMinutesBetween(a: string, b: string): number {
  const p = (s: string) => { const m = s.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/)!; return Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]); };
  return Math.round((p(b) - p(a)) / 60_000);
}
const dateOf = (local: string) => local.slice(0, 10);

const MONTHS: Record<string, number> = {
  jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6, jul: 7, aug: 8, sep: 9, sept: 9, oct: 10, nov: 11, dec: 12,
  mrt: 3, mei: 5, okt: 10, // nl
  mär: 3, mai: 5, okt_: 10, dez: 12, // de
  janv: 1, févr: 2, fevr: 2, mars: 3, avr: 4, juin: 6, juil: 7, août: 8, aout: 8, déc: 12, // fr
};

// Parse a date (+ optional time) cell into local "YYYY-MM-DDTHH:MM:00" or null. `order` is DMY|MDY|YMD.
function parseLocal(cell: string | null | undefined, order: string, timeCell?: string | null): string | null {
  if (!cell) return null;
  const s = norm(String(cell)).replace(/^[A-Za-zäöüéèêçàñ]+,?\s+(?=\d)/, ""); // drop a leading weekday
  if (!s) return null;
  let y = 0, mo = 0, d = 0, hh = -1, mi = 0;
  let m: RegExpMatchArray | null;
  if ((m = s.match(/^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{1,2}):(\d{2})(?::\d{2})?(?:\.\d+)?)?\s*(?:Z|[+-]\d{2}:?\d{2})?$/))) {
    y = +m[1]; mo = +m[2]; d = +m[3]; if (m[4]) { hh = +m[4]; mi = +m[5]; }
  } else if ((m = s.match(/^(\d{1,2})[\/.\-](\d{1,2})[\/.\-](\d{2,4})(?:[ ,T]+(\d{1,2})[:.](\d{2})(?::\d{2})?\s*([AaPp][Mm])?)?/))) {
    const a = +m[1], b = +m[2]; y = +m[3]; if (y < 100) y += 2000;
    if (order === "MDY") { mo = a; d = b; } else { d = a; mo = b; }
    if (a > 12 && order === "MDY") { d = a; mo = b; }
    if (b > 12 && order !== "MDY") { mo = a; d = b; }
    if (m[4]) { hh = +m[4]; mi = +m[5]; if (m[6]) { const pm = m[6].toLowerCase() === "pm"; if (pm && hh < 12) hh += 12; if (!pm && hh === 12) hh = 0; } }
  } else if ((m = s.match(/^(\d{1,2})\.?\s+([A-Za-zäéû]+)\.?\s+(\d{4})(?:[ ,]+(\d{1,2})[:.](\d{2}))?/))) {
    d = +m[1]; y = +m[3]; mo = MONTHS[m[2].toLowerCase().slice(0, 4)] ?? MONTHS[m[2].toLowerCase().slice(0, 3)] ?? 0;
    if (m[4]) { hh = +m[4]; mi = +m[5]; }
  } else if ((m = s.match(/^([A-Za-z]+)\.?\s+(\d{1,2}),?\s+(\d{4})(?:[ ,]+(\d{1,2})[:.](\d{2})\s*([AaPp][Mm])?)?/))) {
    mo = MONTHS[m[1].toLowerCase().slice(0, 3)] ?? 0; d = +m[2]; y = +m[3];
    if (m[4]) { hh = +m[4]; mi = +m[5]; if (m[6]) { const pm = m[6].toLowerCase() === "pm"; if (pm && hh < 12) hh += 12; if (!pm && hh === 12) hh = 0; } }
  } else if ((m = s.match(/^(\d{4})(\d{2})(\d{2})$/))) {
    y = +m[1]; mo = +m[2]; d = +m[3];
  }
  if (!y || !mo || !d || mo > 12 || d > 31) return null;
  if (timeCell) {
    const t = norm(String(timeCell)).match(/^(\d{1,2})[:.h](\d{2})\s*([AaPp][Mm])?/);
    if (t) { hh = +t[1]; mi = +t[2]; if (t[3]) { const pm = t[3].toLowerCase() === "pm"; if (pm && hh < 12) hh += 12; if (!pm && hh === 12) hh = 0; } }
  }
  if (hh < 0) return `${y}-${pad2(mo)}-${pad2(d)}T12:00:00`; // no time known: noon, flagged by caller
  return `${y}-${pad2(mo)}-${pad2(d)}T${pad2(hh)}:${pad2(mi)}:00`;
}
const hadTime = (cell: string | null | undefined, timeCell?: string | null) =>
  !!(timeCell && /\d{1,2}[:.h]\d{2}/.test(String(timeCell))) || !!(cell && /\d{1,2}[:.]\d{2}/.test(String(cell)));

// Intensity in any source scale -> our 1..10 (or null).
function toSeverity(raw: string | number | null | undefined, scale: string): number | null {
  if (raw === null || raw === undefined || raw === "") return null;
  const s = String(raw).trim().toLowerCase();
  const words: Record<string, number> = { none: 0, "no headache": 0, "no migraine": 0, "geen hoofdpijn": 0, "geen migraine": 0, "kein kopfschmerz": 0, "keine migräne": 0, "pas de migraine": 0, mild: 3, light: 3, licht: 3, leicht: 3, moderate: 6, medium: 6, matig: 6, mittel: 6, severe: 9, strong: 9, heavy: 9, zwaar: 9, ernstig: 9, stark: 9, "very severe": 10, extreme: 10 };
  if (words[s] !== undefined) return words[s] === 0 ? null : words[s];
  let n = parseFloat(s.replace(",", "."));
  if (isNaN(n)) { const inside = s.match(/\((\d+(?:[.,]\d+)?)\)/) ?? s.match(/(\d+(?:[.,]\d+)?)\s*(?:\/\s*\d+)?$/) ?? s.match(/(\d+(?:[.,]\d+)?)/); if (!inside) return null; n = parseFloat(inside[1].replace(",", ".")); }
  switch (scale) {
    case "1-3": return n <= 0 ? null : Math.min(10, Math.round(n * 3));
    case "0-5": case "1-5": return n <= 0 ? null : Math.min(10, Math.round(n * 2));
    case "0-100": return n <= 0 ? null : Math.max(1, Math.round(n / 10));
    default: return n <= 0 ? null : Math.min(10, Math.max(1, Math.round(n)));
  }
}

function parseDose(s: string | null | undefined): { dose_value: number | null; dose_unit: string | null; rest: string } {
  if (!s) return { dose_value: null, dose_unit: null, rest: "" };
  const t = String(s);
  let m = t.match(/(\d+(?:[.,]\d+)?)\s*(mg|mcg|µg|ug|g|ml|units?|iu)\b/i);
  if (m) {
    const v = parseFloat(m[1].replace(",", "."));
    let u = m[2].toLowerCase(); if (u === "µg" || u === "ug") u = "mcg"; if (u === "iu" || u === "unit") u = "units";
    if (u === "g") return { dose_value: v * 1000, dose_unit: "mg", rest: t.replace(m[0], "").trim() };
    return { dose_value: v, dose_unit: u, rest: t.replace(m[0], "").trim() };
  }
  m = t.match(/(\d+(?:[.,]\d+)?)\s*(?:x|tabs?|tablets?|tabletten|pills?|caps?|capsules?|stuks?|st\.?)\b/i) || t.match(/^(\d+(?:[.,]\d+)?)\s*$/);
  if (m) return { dose_value: parseFloat(m[1].replace(",", ".")), dose_unit: "amount", rest: t.replace(m[0], "").trim() };
  return { dose_value: null, dose_unit: null, rest: t.trim() };
}

// "1:43 PM Eletriptan", "07:12 sumatriptan", "Paracetamol at 8:00" -> name + local time on the given day.
function parseMedCell(raw: string, day: string): { name: string; taken_local: string | null; dose_value: number | null; dose_unit: string | null; effect: string | null } {
  let m = raw; let takenLocal: string | null = null; let effect: string | null = null;
  const eff = m.match(/^(.*?)\s*[-–:]\s*((?:did ?n['’]?t|not|no|somewhat|partly|partially|fully|really|very|)\s*(?:help(?:ed|s)?|work(?:ed|s)?|effect(?:ive)?|relief|geholpen|gewerkt|geholfen)[^,;]*)$/i);
  if (eff && eff[1].trim()) { m = eff[1].trim(); effect = eff[2].trim(); }
  const lead = m.match(/^(\d{1,2})[:.h](\d{2})\s*([AaPp][Mm])?\s+(.+)$/) ?? m.match(/^(.+?)\s+(?:at|om|um|à)\s+(\d{1,2})[:.h](\d{2})\s*([AaPp][Mm])?$/);
  if (lead) {
    const isLead = /^\d/.test(m);
    let h = +(isLead ? lead[1] : lead[2]); const mi2 = isLead ? lead[2] : lead[3]; const ap = isLead ? lead[3] : lead[4];
    if (ap) { const pm = ap.toLowerCase() === "pm"; if (pm && h < 12) h += 12; if (!pm && h === 12) h = 0; }
    if (h <= 23) { takenLocal = `${day}T${pad2(h)}:${mi2}:00`; m = norm(isLead ? lead[4] : lead[1]); }
  }
  const pd = parseDose(m);
  const name = (pd.rest || m).replace(/\(\s*\)/g, "").replace(/\s{2,}/g, " ").trim();
  return { name: name || m, taken_local: takenLocal, dose_value: pd.dose_value, dose_unit: pd.dose_unit, effect };
}

function splitList(cell: string | null | undefined, sep: string | null): string[] {
  if (!cell) return [];
  const s = String(cell).trim();
  if (!s || /^\(?(none|no|nee|nein|geen|keine?|n\/a|-|0)\)?$/i.test(s)) return [];
  const re = sep && sep !== "auto" ? new RegExp(sep.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "g") : /[;|\n]|,(?!\s*\d)/g;
  return s.split(re).map(norm).filter(Boolean);
}

// ─────────────────────────────────────────────────────────────────────────────
// Sniff + parsers
// ─────────────────────────────────────────────────────────────────────────────

function sniff(fileName: string, text: string): string {
  const ext = (fileName.split(".").pop() ?? "").toLowerCase();
  const head = text.slice(0, 4000);
  if (/<HealthData\b/i.test(head) || /HKCategoryTypeIdentifier/.test(head)) return "apple_health";
  if (ext === "json" || /^\s*[\[{]/.test(head)) { try { JSON.parse(text); return "json"; } catch { /* fallthrough */ } }
  if (ext === "html" || ext === "htm" || /<table\b/i.test(head) || /<html\b/i.test(head)) return "html";
  if (ext === "csv" || ext === "tsv" || ext === "txt" && /[,;\t].*\n.*[,;\t]/.test(head) && head.split("\n")[0].split(/[,;\t]/).length >= 3) return "csv";
  if (ext === "xml") return "xml";
  return "text";
}

function detectDelimiter(headerLine: string): string {
  const c: Record<string, number> = { ",": 0, ";": 0, "\t": 0, "|": 0 };
  for (const ch of headerLine) if (ch in c) c[ch]++;
  return Object.entries(c).sort((a, b) => b[1] - a[1])[0][0];
}

function parseCsv(text: string): { headers: string[]; rows: string[][] } {
  const clean = text.replace(/^﻿/, "");
  const firstLine = clean.split(/\r?\n/, 1)[0];
  const delim = detectDelimiter(firstLine);
  const rows: string[][] = [];
  let row: string[] = [], field = "", q = false;
  for (let i = 0; i < clean.length; i++) {
    const ch = clean[i];
    if (q) {
      if (ch === '"') { if (clean[i + 1] === '"') { field += '"'; i++; } else q = false; }
      else field += ch;
    } else if (ch === '"') q = true;
    else if (ch === delim) { row.push(field); field = ""; }
    else if (ch === "\n" || ch === "\r") {
      if (ch === "\r" && clean[i + 1] === "\n") i++;
      row.push(field); field = "";
      if (row.some((c) => c.trim() !== "")) rows.push(row);
      row = [];
    } else field += ch;
  }
  row.push(field); if (row.some((c) => c.trim() !== "")) rows.push(row);
  const headers = (rows.shift() ?? []).map((h) => norm(h) || "col");
  return { headers, rows };
}

function stripTags(html: string): string {
  return html.replace(/<br\s*\/?>/gi, "\n").replace(/<[^>]+>/g, "").replace(/&nbsp;/g, " ").replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;/g, "'").trim();
}

// Read EVERY data table in an HTML export and merge the ones that share a header row.
// A data table = a header row plus body rows whose first cell is a date. Summary grids
// (month x day calendars, totals) have no date column and are skipped. Migraine Log
// exports one such table per month; other apps export one big one. Both just work.
export function parseHtmlTable(html: string): { headers: string[]; rows: string[][] } | null {
  const tables = Array.from(html.matchAll(/<table[\s\S]*?<\/table>/gi)).map((m) => m[0]);
  if (!tables.length) return null;
  const cells = (tr: string) => Array.from(tr.matchAll(/<t[hd][^>]*>([\s\S]*?)<\/t[hd]>/gi)).map((m) => stripTags(m[1]).split("\n").map((l) => l.replace(/[ \t]+/g, " ").trim()).filter(Boolean).join("\n"));
  const groups = new Map<string, { headers: string[]; rows: string[][] }>();
  for (const t of tables) {
    const trs = Array.from(t.matchAll(/<tr[\s\S]*?<\/tr>/gi)).map((m) => m[0]);
    const all = trs.map(cells).filter((r) => r.length);
    if (all.length < 2) continue;
    const headers = all[0].map((h) => h || "col");
    const body = all.slice(1).filter((r) => r.length >= 2);
    const dated = body.filter((r) => parseLocal(r[0], "DMY") !== null).length;
    if (!body.length || dated < body.length * 0.6) continue;
    const key = headers.join("\u0001");
    const g = groups.get(key) ?? { headers, rows: [] };
    g.rows.push(...body); groups.set(key, g);
  }
  if (!groups.size) return null;
  // The biggest family of tables wins; others are usually per-section summaries.
  return Array.from(groups.values()).sort((a, b) => b.rows.length - a.rows.length)[0];
}

function parseJsonTable(text: string): { headers: string[]; rows: string[][] } | null {
  let data = JSON.parse(text);
  if (!Array.isArray(data)) {
    const arr = Object.values(data).find((v) => Array.isArray(v) && v.length && typeof v[0] === "object");
    if (!arr) return null; data = arr;
  }
  const keys: string[] = uniq((data as Record<string, unknown>[]).flatMap((o) => Object.keys(o ?? {})));
  const rows = (data as Record<string, unknown>[]).map((o) => keys.map((k) => { const v = o?.[k]; return v == null ? "" : typeof v === "object" ? JSON.stringify(v) : String(v); }));
  return { headers: keys, rows };
}

// ─────────────────────────────────────────────────────────────────────────────
// OpenAI call with usage accounting
// ─────────────────────────────────────────────────────────────────────────────

export class Spend {
  usages: Usage[] = [];
  add(u: Usage) { this.usages.push(u); }
  usd(): number {
    return this.usages.reduce((s, u) => { const p = PRICE[u.model] ?? PRICE["gpt-4o"]; return s + (u.prompt * p[0] + u.completion * p[1]) / 1_000_000; }, 0);
  }
  tokens() { return this.usages.reduce((s, u) => s + u.prompt + u.completion, 0); }
}

export async function askJson(system: string, user: string, spend: Spend, model = MODEL_REASON, maxTokens = 8000): Promise<Record<string, unknown>> {
  if (spend.usd() > COST_CAP_USD) throw new Error(`cost cap ${COST_CAP_USD} USD reached`);
  const key = Deno.env.get("OPENAI_API_KEY");
  if (!key) throw new Error("OPENAI_API_KEY missing");
  const res = await fetch(OPENAI_URL, {
    method: "POST",
    headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({ model, max_tokens: maxTokens, temperature: 0.1, response_format: { type: "json_object" }, messages: [{ role: "system", content: system }, { role: "user", content: user }] }),
  });
  if (!res.ok) throw new Error(`OpenAI ${res.status}: ${(await res.text()).slice(0, 300)}`);
  const j = await res.json();
  spend.add({ model, prompt: j.usage?.prompt_tokens ?? 0, completion: j.usage?.completion_tokens ?? 0 });
  const content = j.choices?.[0]?.message?.content ?? "{}";
  try { return JSON.parse(content); } catch { throw new Error("model returned non-JSON"); }
}

// ─────────────────────────────────────────────────────────────────────────────
// Adapter: generic table (CSV / JSON / HTML)
// ─────────────────────────────────────────────────────────────────────────────

const ROLES = ["attack_start", "attack_end", "date", "start_time", "end_time", "duration_minutes", "duration_hours", "intensity", "types", "symptoms", "pain_locations", "aura", "aura_duration_minutes", "prodromes", "postdromes", "triggers", "meds", "med_time", "med_dose", "med_effect", "med_side_effects", "reliefs", "relief_effect", "activities", "missed", "missed_reason", "location", "notes", "foods", "sleep_hours", "bedtime", "wake_time", "sleep_disturbances", "hydration_ml", "weight_kg", "steps", "mindfulness_minutes", "body_fat_pct", "blood_pressure", "blood_glucose", "period", "period_end", "side_effects", "mood", "stress", "ignore"];

const COLUMN_MAP_SYSTEM = `You map the columns of a migraine-history export to a fixed set of roles. You see the headers, a few sample rows and the row count. Reply with JSON only.

Roles: ${ROLES.join(", ")}.
- attack_start / attack_end: a full date+time of the attack. date / start_time / end_time when they are split.
- intensity: pain level. Also report intensity_scale: one of "1-10", "0-10", "1-3", "0-5", "0-100", "mild/moderate/severe", "unknown".
- List columns (types, symptoms, pain_locations, prodromes, postdromes, triggers, meds, reliefs, activities, missed, foods): report the separator string used inside a cell, or "auto".
- notes: free text that may contain meds, symptoms, triggers in prose.
- med_side_effects: side effects of the medicines. relief_effect: how well the reliefs worked. missed_reason: why an activity was missed.
- Sleep: sleep_hours, bedtime (clock time fell asleep), wake_time, sleep_disturbances (count of wake-ups). Body: weight_kg, body_fat_pct, blood_pressure ("120/80"), blood_glucose. mindfulness_minutes. hydration_ml (convert glasses at 250 ml).
- period: menstruation start marker column; period_end: menstruation end marker. side_effects: daily side effects of a preventive treatment.
- Anything else: ignore.

row_kind: "attack" if each row is one attack; "day" if each row is one calendar day (attack only when intensity > 0 or an attack column is filled); "event" if each row is one logged item with a category column (long format).
For "event" rows also return event: { category_col, detail_col, value_col, time_col } and event_categories mapping each distinct category value you were given to a role from the list above.
marker_values: intensity or type values that mean "no attack that day" (e.g. "No headache", "-2", "0").
date_order: "DMY", "MDY", "YMD" or "ISO" for numeric dates. If you cannot tell, use the hint given.
app_guess: the app that produced this file, if recognisable, else null.

Return: { "row_kind": ..., "date_order": ..., "intensity_scale": ..., "marker_values": [...], "app_guess": ..., "columns": { "<header>": { "role": "...", "separator": "..." } }, "event": {...} | null, "event_categories": {...} | null }`;

interface ColumnMap {
  row_kind: "attack" | "day" | "event"; date_order: string; intensity_scale: string; marker_values: string[]; app_guess: string | null;
  columns: Record<string, { role: string; separator?: string }>;
  event: { category_col: string; detail_col: string; value_col: string | null; time_col: string | null } | null;
  event_categories: Record<string, string> | null;
}

function dateOrderHint(rows: string[][], headers: string[]): string {
  let dmy = 0, mdy = 0;
  for (const r of rows.slice(0, 400)) for (const c of r) {
    const m = String(c).match(/^(\d{1,2})[\/.\-](\d{1,2})[\/.\-]\d{2,4}/);
    if (!m) continue;
    if (+m[1] > 12) dmy++; if (+m[2] > 12) mdy++;
  }
  void headers;
  if (dmy && !mdy) return "DMY"; if (mdy && !dmy) return "MDY"; return "unknown";
}

export async function inferColumnMap(headers: string[], rows: string[][], spend: Spend, country: string | null): Promise<ColumnMap> {
  const sample = rows.slice(0, 8).map((r) => Object.fromEntries(headers.map((h, i) => [h, (r[i] ?? "").slice(0, 160)])));
  const hint = dateOrderHint(rows, headers);
  const catCol = headers.find((h) => /categor|type|kind/i.test(h));
  const cats = catCol ? uniq(rows.map((r) => r[headers.indexOf(catCol)] ?? "").filter(Boolean)).slice(0, 40) : [];
  const user = JSON.stringify({ headers, row_count: rows.length, sample_rows: sample, date_order_hint: hint === "unknown" ? (country === "US" ? "MDY" : "DMY") : hint, distinct_category_values: cats });
  const j = await askJson(COLUMN_MAP_SYSTEM, user, spend) as unknown as ColumnMap;
  j.columns = j.columns ?? {};
  for (const h of headers) if (!j.columns[h]) j.columns[h] = { role: "ignore" };
  if (!["attack", "day", "event"].includes(j.row_kind)) j.row_kind = "attack";
  if (!j.date_order || j.date_order === "unknown") j.date_order = hint === "unknown" ? "DMY" : hint;
  // The model may propose marker values ("No headache", "-2"); code decides. Anything that parses to a
  // positive strength on the file's scale is a real attack, never a marker, whatever the model said.
  j.marker_values = (j.marker_values ?? []).map((v) => String(v).toLowerCase()).filter((v) => toSeverity(v, j.intensity_scale) === null);
  return j;
}

function emptyAttack(ref: string, start: string, scale: string): Attack {
  return { ref, start_local: start, end_local: null, end_inferred: false, intensity: { value: null, scale }, types: [], symptoms: [], pain_locations: [], canonical_pain: false, aura: { present: false, zones: [], duration_minutes: null }, prodromes: [], postdromes: [], triggers: [], meds: [], reliefs: [], activities: [], missed: [], location: null, foods: [], notes: null, provenance: { row: null, page: null, quote: null }, symptom_severity: {}, times: {}, missed_detail: [] };
}
function emptyDay(date: string): Day { return { date, metrics: {}, times: {}, foods: [], period: null, daily_meds: [], triggers: [], prodromes: [], missed: [], side_effects: [], regimens: [] }; }

export function tableToModel(headers: string[], rows: string[][], cm: ColumnMap, inferences: string[]): { attacks: Attack[]; days: Day[] } {
  const attacks: Attack[] = []; const days = new Map<string, Day>();
  const day = (d: string) => { if (!days.has(d)) days.set(d, emptyDay(d)); return days.get(d)!; };
  const byRole = (role: string) => headers.map((h, i) => [h, i] as const).filter(([h]) => cm.columns[h]?.role === role).map(([, i]) => i);
  const cellOf = (r: string[], role: string) => { const i = byRole(role)[0]; return i === undefined ? null : (r[i] ?? "").trim() || null; };
  const sepOf = (role: string) => { const h = headers.find((h) => cm.columns[h]?.role === role); return h ? (cm.columns[h].separator ?? "auto") : "auto"; };
  let noTime = 0, inferredEnd = 0;

  if (cm.row_kind === "event" && cm.event) {
    const ci = headers.indexOf(cm.event.category_col), di = headers.indexOf(cm.event.detail_col);
    const vi = cm.event.value_col ? headers.indexOf(cm.event.value_col) : -1, ti = cm.event.time_col ? headers.indexOf(cm.event.time_col) : -1;
    const dateI = byRole("date")[0] ?? byRole("attack_start")[0];
    const perDay = new Map<string, Attack>();
    rows.forEach((r, idx) => {
      const rawTime = ti >= 0 ? (r[ti] ?? "") : "";
      const bucket = /^(morning|am|ochtend|morgen)$/i.test(rawTime.trim()) ? "09:00" : /^(afternoon|pm|middag|nachmittag)$/i.test(rawTime.trim()) ? "15:00" : /^(evening|avond|abend|night|nacht)$/i.test(rawTime.trim()) ? "20:00" : null;
      let local = parseLocal(r[dateI], cm.date_order, bucket ? null : rawTime); if (!local) return;
      if (bucket) local = `${dateOf(local)}T${bucket}:00`;
      const role = cm.event_categories?.[r[ci] ?? ""] ?? "ignore";
      const detail = norm(r[di] ?? ""), value = vi >= 0 ? (r[vi] ?? "") : "";
      const d = dateOf(local);
      const isAttackWord = /migraine|headache|hoofdpijn|kopfschmerz|migräne|migraña|emicrania|enxaqueca/i.test(detail);
      if (role === "intensity" || role === "types" || role === "symptoms") {
        const sev = role === "intensity" || isAttackWord ? toSeverity(value, cm.intensity_scale) : null;
        if (role === "intensity" && sev === null) return;
        if ((role === "symptoms" || role === "types") && !isAttackWord && !perDay.has(d)) { day(d).prodromes.push(detail); return; } // a symptom on a day with no attack row is a day-level note
        let a = perDay.get(d); if (!a) { a = emptyAttack(`r${idx}`, local, cm.intensity_scale); a.provenance.row = idx + 2; perDay.set(d, a); attacks.push(a); }
        if (sev !== null) a.intensity.value = Math.max(a.intensity.value ?? 0, sev);
        if ((role === "symptoms" || role === "types") && !isAttackWord) a.symptoms.push(detail);
        if (isAttackWord && role !== "intensity") a.types.push(detail);
      } else if (role === "triggers") day(d).triggers.push(detail);
      else if (role === "meds") { const pm = parseMedCell(detail, d); day(d).daily_meds.push({ name: pm.name, dose_value: pm.dose_value ?? parseDose(value).dose_value, dose_unit: pm.dose_unit ?? parseDose(value).dose_unit, taken_local: pm.taken_local ?? (hadTime(null, rawTime) || bucket ? local : null), effect: pm.effect, side_effect: null }); }
      else if (role === "foods") day(d).foods.push({ name: detail, time_local: local });
      else if (["sleep_hours", "hydration_ml", "weight_kg", "steps", "mood", "stress"].includes(role)) { const n = parseFloat(String(value).replace(",", ".")); if (!isNaN(n)) day(d).metrics[role] = n; }
      else if (role === "period") day(d).period = "flow";
    });
    // Attach same-day meds/triggers to the attack of that day.
    for (const a of attacks) { const dd = days.get(dateOf(a.start_local)); if (dd) { a.meds.push(...dd.daily_meds); dd.daily_meds = []; a.triggers.push(...dd.triggers); dd.triggers = []; } }
    inferences.push(`Long-format file pivoted by day: ${attacks.length} attack days.`);
    return { attacks, days: Array.from(days.values()) };
  }

  rows.forEach((r, idx) => {
    const startCell = cellOf(r, "attack_start") ?? cellOf(r, "date");
    const start = parseLocal(startCell, cm.date_order, cellOf(r, "start_time"));
    if (!start) return;
    const intensityRaw = cellOf(r, "intensity");
    const typesRaw = cellOf(r, "types");
    const isMarker = (intensityRaw && (cm.marker_values.includes(intensityRaw.toLowerCase()) || /^0([.,]0+)?$/.test(intensityRaw.trim()))) || (typesRaw && cm.marker_values.includes(typesRaw.toLowerCase()));
    const sev = isMarker ? null : toSeverity(intensityRaw, cm.intensity_scale);
    const hasAttackSignal = !isMarker && (sev !== null || !!cellOf(r, "attack_start") || !!typesRaw || !!cellOf(r, "symptoms") || !!cellOf(r, "pain_locations"));
    const d = dateOf(start);
    // Day-level things live on the day whether or not there is an attack.
    for (const mr of ["sleep_hours", "hydration_ml", "weight_kg", "steps", "mood", "stress", "sleep_disturbances", "mindfulness_minutes", "body_fat_pct", "blood_glucose"]) { const v = cellOf(r, mr); if (v) { const n = parseFloat(v.replace(",", ".")); if (!isNaN(n)) day(d).metrics[mr] = mr === "hydration_ml" && n < 20 ? n * 250 : n; } }
    const bp = cellOf(r, "blood_pressure"); if (bp) { const m = bp.match(/(\d{2,3})\s*\/\s*(\d{2,3})/); if (m) { day(d).metrics.bp_systolic = +m[1]; day(d).metrics.bp_diastolic = +m[2]; } }
    for (const tr of ["bedtime", "wake_time"] as const) { const v = cellOf(r, tr); if (v) { const t = v.match(/(\d{1,2})[:.h](\d{2})/); if (t) day(d).times[tr] = `${d}T${pad2(+t[1])}:${t[2]}:00`; } }
    const per = cellOf(r, "period"); if (per && !/^(no|nee|nein|0|false|-)$/i.test(per)) day(d).period = /start|begin/i.test(per) ? "start" : "flow";
    const perEnd = cellOf(r, "period_end"); if (perEnd && !/^(no|nee|nein|0|false|-)$/i.test(perEnd)) day(d).period = "end";
    for (const f of splitList(cellOf(r, "foods"), sepOf("foods"))) day(d).foods.push({ name: f, time_local: null });
    const se = splitList(cellOf(r, "side_effects"), sepOf("side_effects")); if (se.length) day(d).side_effects.push({ symptoms: se, notes: null, regimen: null });
    const rowNote = byRole("notes").map((i) => (r[i] ?? "").trim()).filter(Boolean).join("\n") || null;
    if ((cm.row_kind === "day" && !hasAttackSignal) || isMarker) {
      for (const m of splitList(cellOf(r, "meds"), sepOf("meds"))) { const pm = parseMedCell(m, d); day(d).daily_meds.push({ name: pm.name, dose_value: pm.dose_value, dose_unit: pm.dose_unit, taken_local: pm.taken_local, effect: null, side_effect: null }); }
      day(d).triggers.push(...splitList(cellOf(r, "triggers"), sepOf("triggers")));
      if (rowNote) day(d).notes = [day(d).notes, rowNote].filter(Boolean).join("\n");
      return;
    }
    if (!hasAttackSignal) { if (rowNote && !/no (headache|migraine|attack)|geen (hoofdpijn|migraine)|kein(e)? (kopfschmerz|migräne)/i.test(rowNote)) { /* prose-only row: keep as attack candidate for note mining */ } else { if (rowNote) day(d).notes = [day(d).notes, rowNote].filter(Boolean).join("\n"); return; } }
    const a = emptyAttack(`r${idx}`, start, cm.intensity_scale);
    a.provenance.row = idx + 2;
    if (!hadTime(startCell, cellOf(r, "start_time"))) noTime++;
    a.intensity.value = sev;
    const endCell = cellOf(r, "attack_end");
    let end = endCell ? parseLocal(endCell, cm.date_order, cellOf(r, "end_time")) : (cellOf(r, "end_time") ? parseLocal(startCell, cm.date_order, cellOf(r, "end_time")) : null);
    const durM = cellOf(r, "duration_minutes"), durH = cellOf(r, "duration_hours");
    if (!end && durM && !isNaN(parseFloat(durM))) end = addMinutesLocal(start, Math.round(parseFloat(durM.replace(",", "."))));
    if (!end && durH && !isNaN(parseFloat(durH))) end = addMinutesLocal(start, Math.round(parseFloat(durH.replace(",", ".")) * 60));
    if (end && localMinutesBetween(start, end) < 0 && dateOf(end) === dateOf(start)) end = addMinutesLocal(end, 24 * 60); // end time past midnight
    a.end_local = end;
    a.types = splitList(typesRaw, sepOf("types"));
    a.symptoms = splitList(cellOf(r, "symptoms"), sepOf("symptoms"));
    a.pain_locations = splitList(cellOf(r, "pain_locations"), sepOf("pain_locations"));
    const auraRaw = cellOf(r, "aura");
    if (auraRaw && !/^(no|nee|nein|0|false|-|none)$/i.test(auraRaw)) { a.aura.present = true; if (!/^(yes|ja|1|true|y)$/i.test(auraRaw)) a.symptoms.push(auraRaw); }
    const ad = cellOf(r, "aura_duration_minutes"); if (ad && !isNaN(parseFloat(ad))) a.aura.duration_minutes = Math.round(parseFloat(ad));
    a.prodromes = splitList(cellOf(r, "prodromes"), sepOf("prodromes"));
    a.postdromes = splitList(cellOf(r, "postdromes"), sepOf("postdromes"));
    a.triggers = splitList(cellOf(r, "triggers"), sepOf("triggers"));
    const medTime = cellOf(r, "med_time"), medDose = cellOf(r, "med_dose"), medEff = cellOf(r, "med_effect");
    const medList = splitList(cellOf(r, "meds"), sepOf("meds"));
    a.meds = medList.map((m0) => {
      const pm = parseMedCell(m0, dateOf(start)); const extra = medList.length === 1 ? parseDose(medDose) : { dose_value: null, dose_unit: null, rest: "" };
      const taken = pm.taken_local ?? (medTime && hadTime(null, medTime) ? (parseLocal(startCell, cm.date_order, medTime) ?? null) : null);
      return { name: pm.name, dose_value: pm.dose_value ?? extra.dose_value, dose_unit: pm.dose_unit ?? extra.dose_unit, taken_local: taken, effect: pm.effect ?? medEff, side_effect: null };
    });
    // Attack start = first medicine time when the row itself has no time (what the person actually recorded).
    if (!hadTime(startCell, cellOf(r, "start_time"))) { const first = a.meds.map((m) => m.taken_local).filter((t): t is string => !!t).sort()[0]; if (first) { a.start_local = first; noTime--; } }
    const relEff = cellOf(r, "relief_effect");
    a.reliefs = splitList(cellOf(r, "reliefs"), sepOf("reliefs")).map((n) => ({ name: n, start_local: null, end_local: null, effect: relEff, side_effect: null }));
    const medSe = cellOf(r, "med_side_effects"); if (medSe && a.meds.length) a.meds.forEach((m) => { m.side_effect = medSe; });
    a.activities = splitList(cellOf(r, "activities"), sepOf("activities"));
    a.missed = splitList(cellOf(r, "missed"), sepOf("missed"));
    const mReason = splitList(cellOf(r, "missed_reason"), sepOf("missed_reason"));
    a.missed_detail = a.missed.map((n) => ({ name: n, reasons: mReason, anticipated: false }));
    a.location = cellOf(r, "location");
    a.notes = byRole("notes").map((i) => (r[i] ?? "").trim()).filter(Boolean).join("\n") || null;
    attacks.push(a);
  });
  if (noTime) inferences.push(`${noTime} of ${attacks.length} attacks had a date but no time. They were placed at 12:00.`);
  void inferredEnd;
  return { attacks, days: Array.from(days.values()) };
}

// ─────────────────────────────────────────────────────────────────────────────
// Adapter: Apple Health export.xml
// ─────────────────────────────────────────────────────────────────────────────

function appleHealthToModel(xml: string, inferences: string[]): { attacks: Attack[]; days: Day[] } {
  const attacks: Attack[] = []; const days = new Map<string, Day>();
  const day = (d: string) => { if (!days.has(d)) days.set(d, emptyDay(d)); return days.get(d)!; };
  const attr = (rec: string, k: string) => rec.match(new RegExp(`\\b${k}="([^"]*)"`))?.[1] ?? null;
  const toLocal = (s: string | null) => { const m = s?.match(/^(\d{4}-\d{2}-\d{2}) (\d{2}):(\d{2})/); return m ? `${m[1]}T${m[2]}:${m[3]}:00` : null; };
  const sevOf = (v: string | null) => v?.includes("Mild") ? 3 : v?.includes("Moderate") ? 6 : v?.includes("Severe") ? 9 : null;
  const SYMPTOM_TYPES: Record<string, string> = { Nausea: "Nausea", Vomiting: "Vomiting", Dizziness: "Dizziness", Fatigue: "Fatigue", MoodChanges: "Mood change", SensitivityToLight: "Light sensitivity", SensitivityToSound: "Sound sensitivity", Sleepiness: "Fatigue", Vision: "Blurred vision" };
  let n = 0;
  for (const m of xml.matchAll(/<Record\b[^>]*\/>|<Record\b[^>]*>[\s\S]*?<\/Record>/g)) {
    const rec = m[0]; const type = attr(rec, "type") ?? ""; n++;
    const start = toLocal(attr(rec, "startDate")), end = toLocal(attr(rec, "endDate"));
    if (!start) continue;
    if (type === "HKCategoryTypeIdentifierHeadache") {
      const v = attr(rec, "value"); if (v?.includes("NotPresent")) continue;
      const last = attacks[attacks.length - 1];
      if (last && dateOf(last.start_local) === dateOf(start) && localMinutesBetween(last.end_local ?? last.start_local, start) <= 360) {
        if (end && (!last.end_local || localMinutesBetween(last.end_local, end) > 0)) last.end_local = end;
        last.intensity.value = Math.max(last.intensity.value ?? 0, sevOf(v) ?? 0) || null; continue;
      }
      const a = emptyAttack(`hk${n}`, start, "mild/moderate/severe");
      a.intensity.value = sevOf(v); a.end_local = end && end !== start ? end : null; a.provenance.quote = attr(rec, "sourceName");
      attacks.push(a);
    } else if (type === "HKCategoryTypeIdentifierMenstrualFlow") {
      const v = attr(rec, "value"); if (v?.includes("None")) continue;
      const cycleStart = /HKMenstrualCycleStart"\s+value="1"/.test(rec) || /HKMetadataKeyMenstrualCycleStart[^>]*value="1"/.test(rec);
      day(dateOf(start)).period = cycleStart ? "start" : "flow";
    } else {
      const key = type.replace("HKCategoryTypeIdentifier", "");
      if (SYMPTOM_TYPES[key] && !attr(rec, "value")?.includes("NotPresent")) { const a = attacks.find((x) => dateOf(x.start_local) === dateOf(start)); if (a) a.symptoms.push(SYMPTOM_TYPES[key]); }
    }
  }
  inferences.push(`Apple Health: ${attacks.length} headache episodes merged from records within 6 hours of each other on the same day.`);
  return { attacks, days: Array.from(days.values()) };
}

// ─────────────────────────────────────────────────────────────────────────────
// Adapter: unstructured text (notes, diary, letter). Model extracts rows.
// ─────────────────────────────────────────────────────────────────────────────

const TEXT_EXTRACT_SYSTEM = `You extract migraine attacks from a person's own diary text. Use ONLY what the text says. Never invent an attack, a medicine, or a time.
Return JSON: { "attacks": [ { "start_local": "YYYY-MM-DDTHH:MM:00" (12:00 if only a date is known), "time_known": bool, "end_local": "..."|null, "intensity": number|null, "intensity_scale": "1-10"|"0-5"|"1-3"|"mild/moderate/severe"|"unknown", "symptoms": [words as written], "pain_locations": [words as written], "aura": {"present": bool, "duration_minutes": number|null}, "prodromes": [], "triggers": [], "foods": [], "meds": [ {"name": as written, "dose": "as written"|null, "time_local": "..."|null, "effect": "as written"|null } ], "reliefs": [], "activities": [], "missed": [], "location": string|null, "quote": "the sentence(s) this came from" } ],
  "days": [ { "date": "YYYY-MM-DD", "period": "start"|null, "daily_meds": [ {"name":..., "dose":...} ], "foods": [] } ] }
Keep every label in the person's own words and language. If the year is missing, use the year given in context. Medicines named only as "tablets" stay "tablets". "Skipped work", "cancelled dinner with friends" -> missed. "Slept only 5 hours" -> day sleep, on the attack's day.`;

async function textToModel(text: string, spend: Spend, inferences: string[]): Promise<{ attacks: Attack[]; days: Day[] }> {
  const attacks: Attack[] = []; const days: Day[] = [];
  const chunks: string[] = []; for (let i = 0; i < text.length; i += 9000) chunks.push(text.slice(i, i + 9000));
  const year = new Date().getUTCFullYear();
  for (let c = 0; c < chunks.length; c++) {
    const j = await askJson(TEXT_EXTRACT_SYSTEM, `Context: current year ${year}. Text (part ${c + 1} of ${chunks.length}):\n\n${chunks[c]}`, spend) as { attacks?: Record<string, unknown>[]; days?: Record<string, unknown>[] };
    for (const [k, x] of (j.attacks ?? []).entries()) {
      const start = parseLocal(String(x.start_local ?? ""), "ISO"); if (!start) continue;
      const a = emptyAttack(`t${c}_${k}`, start, String(x.intensity_scale ?? "unknown"));
      a.intensity.value = toSeverity(x.intensity as number, String(x.intensity_scale ?? "1-10"));
      a.end_local = parseLocal(String(x.end_local ?? ""), "ISO");
      a.symptoms = (x.symptoms as string[]) ?? []; a.pain_locations = (x.pain_locations as string[]) ?? [];
      const au = x.aura as { present?: boolean; duration_minutes?: number } | undefined; if (au?.present) { a.aura.present = true; a.aura.duration_minutes = au.duration_minutes ?? null; }
      a.prodromes = (x.prodromes as string[]) ?? []; a.triggers = (x.triggers as string[]) ?? []; a.foods = (x.foods as string[]) ?? [];
      a.meds = ((x.meds as Record<string, string>[]) ?? []).map((m) => { const pd = parseDose(m.dose); return { name: m.name, dose_value: pd.dose_value, dose_unit: pd.dose_unit, taken_local: parseLocal(m.time_local, "ISO"), effect: m.effect ?? null, side_effect: null }; });
      a.reliefs = ((x.reliefs as string[]) ?? []).map((n) => ({ name: n, start_local: null, end_local: null, effect: null }));
      a.activities = (x.activities as string[]) ?? []; a.missed = (x.missed as string[]) ?? []; a.location = (x.location as string) ?? null;
      a.provenance = { row: null, page: c + 1, quote: (x.quote as string) ?? null };
      attacks.push(a);
    }
    for (const d of j.days ?? []) {
      const dd = emptyDay(String(d.date)); dd.period = (d.period as "start") ?? null;
      dd.daily_meds = ((d.daily_meds as Record<string, string>[]) ?? []).map((m) => { const pd = parseDose(m.dose); return { name: m.name, dose_value: pd.dose_value, dose_unit: pd.dose_unit, taken_local: null, effect: null, side_effect: null }; });
      dd.foods = ((d.foods as string[]) ?? []).map((f) => ({ name: f, time_local: null })); days.push(dd);
    }
  }
  inferences.push(`Free text: ${attacks.length} attacks extracted by the model from ${chunks.length} part(s); every row carries the sentence it came from.`);
  return { attacks, days };
}

// ─────────────────────────────────────────────────────────────────────────────
// Note mining: prose inside cells -> items in the person's words
// ─────────────────────────────────────────────────────────────────────────────

export const NOTE_MINE_SYSTEM = `You read short migraine diary notes and pull out EVERYTHING they state, the way a careful nurse would transcribe them into a structured log. The input is a JSON object keyed by note id. The output MUST be one JSON object containing EVERY input id as a key, in the same order, even when a note yields nothing (then its value is {}). Never stop after the first note. Keep the output SHORT: omit every key whose value would be an empty list, null or false; only write what the note actually states. Use ONLY what the note says; never invent. Keep labels in the person's own words and language, except pain_locations and aura zones which use the fixed ids below.
Pain location ids: forehead_left, forehead_center, forehead_right, brow_left, brow_right, temple_left, temple_right, eye_left, eye_right, sinus_left, sinus_right, nose_bridge, vertex, occipital_left, occipital_center, occipital_right, base_skull_left, base_skull_center, base_skull_right, neck_left, neck_right, behind_ear_left, behind_ear_right, jaw_left, jaw_right. "left side" -> temple_left, eye_left, brow_left. "both sides" -> temple_left, temple_right. "behind my eyes" -> eye_left, eye_right. "top of head" -> vertex. "neck" -> neck_left, neck_right. "back of head" -> occipital_center, base_skull_center.
Aura zone ids: <left|right>_<top|center|bottom>_<left|center|right>. Only when the note describes a visual position.
Return, per id:
{ "types": [migraine type words, e.g. "migraine with aura", "cluster"],
  "symptoms": [ {"name": as written, "severity": "mild"|"moderate"|"severe"|null} ]   (during the attack),
  "prodromes": [ {"name": as written, "when": "evening before"|"morning"|"night before"|"HH:MM"|"2 days before"|null} ]   (before it hit),
  "postdromes": [words for after-effects: "hangover feeling the next day", "wiped out after"],
  "triggers": [ {"name": as written, "when": same options as prodromes or null} ]   (NOT foods),
  "foods": [ {"name": as written, "when": "night before"|"lunch"|"HH:MM"|null} ]   (every food or drink named, including coffee and alcohol),
  "meds": [ {"name": as written ("tablets"/"tabletten"/"my usual" stay exactly that), "dose": "as written"|null, "time": "HH:MM"|null, "effect": "as written"|null, "side_effects": "as written"|null} ],
  "reliefs": [ {"name": as written, "start": "HH:MM"|null, "end": "HH:MM"|null, "effect": "as written"|null} ],
  "pain_locations": [ids], "aura": {"present": bool, "zones": [], "duration_minutes": null},
  "intensity": number|null, "end_time": "HH:MM"|null,
  "activities": [ {"name": as written, "start": "HH:MM"|null, "end": "HH:MM"|null} ],
  "missed": [ {"name": as written ("work", "the gym", "dinner with friends"), "reason": as written|null, "expected_attack": bool (true when they skipped it because they felt one coming, not because they had one)} ],
  "location": string|null,
  "sleep_hours": number|null, "bedtime": "HH:MM"|null, "wake_time": "HH:MM"|null, "sleep_disturbances": number|null,
  "water_ml": number|null (glasses x 250), "weight_kg": number|null, "steps": number|null, "mindfulness_minutes": number|null, "blood_pressure": "120/80"|null,
  "mood": string|null, "stress": string|null,
  "period_start": bool, "period_end": bool,
  "regimen_started": [ {"name": as written, "dose": "as written"|null, "frequency": "daily"|"nightly"|"weekly"|as written|null, "kind": "drug"|"lifestyle"|"device"} ]   (a preventive they started or take every day),
  "regimen_stopped": [names],
  "treatment_side_effects": [ {"symptoms": [words], "notes": as written|null, "regimen": name|null} ]   (side effects of a daily preventive, on any day) }
Rules: "helped", "hielp goed", "didn't work", "goed resultaat" describe how a medicine worked: put them in that medicine's effect, never as a relief or symptom. Generic words like "medication", "tablets taken", "medicatie", "ingenomen" are NOT a medicine name when the note (or its row) already names one; leave them out. Pre-attack signs (before it hit, the evening before, "before the pain started") are prodromes; during-attack signs are symptoms; next-day or after-it-passed signs are postdromes. What a medicine DID to the person ("made me drowsy", "upset my stomach") is that medicine's side_effects, never a symptom. "Took it at 8:15" refers to the medicine already known for that day: return the name "it" and the time, we resolve it. Never return the attack itself as a symptom ("mild one", "bad migraine"): that is intensity. Visual disturbances before the pain (zigzags, flickering, blind spot) are aura, not a prodrome. Example: "Neck was stiff all yesterday evening and I kept yawning" -> prodromes [{"name":"stiff neck","when":"evening before"},{"name":"yawning","when":"evening before"}]. "Period started today" -> period_start true. "Couldn't go to work" -> missed [{"name":"work","reason":null,"expected_attack":false}]. "Skipped the gym because I felt one coming" -> missed [{"name":"the gym","reason":"felt one coming","expected_attack":true}]. "Slept about 4 hours" -> sleep_hours 4. "Started topiramate 25mg every night last week" -> regimen_started [{"name":"topiramate","dose":"25mg","frequency":"nightly","kind":"drug"}]. "Skipped work" / "cancelled dinner with friends" -> missed [{"name":"work","reason":null,"expected_attack":false}].`;


async function mineNotes(attacks: Attack[], days: Day[], spend: Spend, inferences: string[]) {
  const dayOf = (d: string) => { let x = days.find((y) => y.date === d); if (!x) { x = emptyDay(d); days.push(x); } return x; };
  const dayPseudo: Attack[] = days.filter((d) => d.notes && d.notes.length > 3).map((d) => { const a = emptyAttack(`day:${d.date}`, `${d.date}T12:00:00`, "none"); a.notes = d.notes!; return a; });
  const withNotes = [...attacks.filter((a) => a.notes && a.notes.length > 3), ...dayPseudo];
  if (!withNotes.length) return;
  const batchSize = 12; let mined = 0;
  const hhmm = (v: unknown) => typeof v === "string" && /^\d{1,2}:\d{2}$/.test(v) ? v.padStart(5, "0") : null;
  const whenToLocal = (a: Attack, when: unknown): string | null => {
    if (typeof when !== "string" || !when) return null;
    const d = dateOf(a.start_local); const w = when.toLowerCase();
    const t = hhmm(when); if (t) return `${d}T${t}:00`;
    if (/(evening|avond|abend) (before|ervoor|davor)|night before|nacht ervoor|vorige avond|the day before|dag ervoor|yesterday|gisteren|gestern/.test(w)) return addMinutesLocal(`${d}T20:00:00`, -1440);
    if (/2 days|twee dagen|zwei tage/.test(w)) return addMinutesLocal(`${d}T12:00:00`, -2880);
    if (/morning|ochtend|morgen/.test(w)) return `${d}T07:00:00`;
    if (/afternoon|middag|nachmittag/.test(w)) return `${d}T14:00:00`;
    if (/evening|avond|abend/.test(w)) return `${d}T20:00:00`;
    if (/lunch/.test(w)) return `${d}T12:30:00`;
    if (/dinner|avondeten|abendessen/.test(w)) return `${d}T19:00:00`;
    if (/breakfast|ontbijt|frühstück/.test(w)) return `${d}T08:00:00`;
    return null;
  };
  const sevWord = (v: unknown) => typeof v === "string" && /^(mild|moderate|severe)$/i.test(v) ? v.toUpperCase() : null;
  const batches: Attack[][] = []; for (let i = 0; i < withNotes.length; i += batchSize) batches.push(withNotes.slice(i, i + batchSize));
  const results = await Promise.all(batches.map((batch) => askJson(NOTE_MINE_SYSTEM, JSON.stringify(Object.fromEntries(batch.map((a) => [a.ref, { date: dateOf(a.start_local), note: a.notes!.slice(0, 1500) }]))), spend, MODEL_REASON, 6000)));
  for (let bi = 0; bi < batches.length; bi++) {
    const batch = batches[bi]; const j = results[bi];
    for (const a of batch) {
      const x = j[a.ref] as Record<string, unknown> | undefined; if (!x) continue; mined++;
      const d = dateOf(a.start_local); const dd = () => dayOf(d);
      const items = (k: string): Record<string, unknown>[] => Array.isArray(x[k]) ? (x[k] as unknown[]).map((v) => typeof v === "string" ? { name: v } : (v as Record<string, unknown>)).filter((v) => v && typeof v.name === "string" && norm(String(v.name))) : [];
      const strs = (k: string): string[] => Array.isArray(x[k]) ? (x[k] as unknown[]).map((v) => typeof v === "string" ? norm(v) : norm(String((v as Record<string, unknown>)?.name ?? ""))).filter(Boolean) : [];
      a.types.push(...strs("types"));
      for (const it of items("symptoms")) { const n = norm(String(it.name)); a.symptoms.push(n); const sv = sevWord(it.severity); if (sv) a.symptom_severity[n.toLowerCase()] = sv; }
      for (const it of items("prodromes")) { const n = norm(String(it.name)); a.prodromes.push(n); const t = whenToLocal(a, it.when); if (t) a.times[`prodrome|${n.toLowerCase()}`] = t; }
      a.postdromes.push(...strs("postdromes"));
      for (const it of items("triggers")) { const n = norm(String(it.name)); a.triggers.push(n); const t = whenToLocal(a, it.when); if (t) a.times[`trigger|${n.toLowerCase()}`] = t; }
      for (const it of items("foods")) { const n = norm(String(it.name)); a.foods.push(n); const t = whenToLocal(a, it.when); if (t) a.times[`food|${n.toLowerCase()}`] = t; }
      for (const it of items("activities")) { const n = norm(String(it.name)); a.activities.push(n); const st = hhmm(it.start), en = hhmm(it.end); if (st) a.times[`activity|${n.toLowerCase()}`] = `${d}T${st}:00`; if (en) a.times[`activity_end|${n.toLowerCase()}`] = `${d}T${en}:00`; }
      for (const it of items("missed")) { const n = norm(String(it.name)); a.missed.push(n); a.missed_detail.push({ name: n, reasons: it.reason ? [norm(String(it.reason))] : [], anticipated: it.expected_attack === true }); }
      if (!a.location && x.location) a.location = norm(String(x.location));
      const pl = strs("pain_locations").filter((id) => PAIN_IDS.includes(id)); if (pl.length && !a.pain_locations.length) { a.pain_locations = pl; a.canonical_pain = true; }
      const au = x.aura as { present?: boolean; zones?: string[]; duration_minutes?: number | null } | undefined;
      if (au?.present) { a.aura.present = true; a.aura.zones = uniq([...a.aura.zones, ...(au.zones ?? [])]); a.aura.duration_minutes = a.aura.duration_minutes ?? au.duration_minutes ?? null; }
      if (a.intensity.value === null && typeof x.intensity === "number") a.intensity.value = toSeverity(x.intensity, "1-10");
      const et = hhmm(x.end_time); if (!a.end_local && et) { a.end_local = `${d}T${et}:00`; if (localMinutesBetween(a.start_local, a.end_local) < 0) a.end_local = addMinutesLocal(a.end_local, 1440); }
      for (const m of items("meds")) {
        let name = norm(String(m.name));
        if (TIME_OF_DAY.test(name)) { // "s ochtends": a time, attach to the row's only medicine
          const one = a.meds.length === 1 ? a.meds[0] : null; const hh = /ochtend|morning|morgen|matin/i.test(name) ? "08:00" : /middag|afternoon|mittag|après/i.test(name) ? "14:00" : "20:00";
          if (one && !one.taken_local) one.taken_local = `${d}T${hh}:00`;
          continue;
        }
        if (EFFECT_WORDS.test(name)) { const one = a.meds.length === 1 ? a.meds[0] : null; if (one && !one.effect) one.effect = name; continue; }
        if (GENERIC_MED.test(name) && a.meds.length) continue; // "medicatie", "ingenomen": the medicines are already listed on the row
        if (QUANTITY_ONLY.test(name)) { // a dose remark, not a drug: attach to the row's only medicine
          const qty = name.match(/^(\d+([.,]\d+)?)/); const one = a.meds.length === 1 ? a.meds[0] : null;
          if (one && qty && one.dose_value === null) { one.dose_value = parseFloat(qty[1].replace(",", ".")); one.dose_unit = "amount"; }
          if (one && m.effect && !one.effect) one.effect = String(m.effect);
          continue;
        }
        if (/^(it|that|them|this|die|dat|het|deze|es|das|sie)$/i.test(name) && a.meds.length === 1) name = a.meds[0].name; // pronoun -> the row's own medicine
        else if (/^(it|that|them|this|die|dat|het|deze|es|das|sie)$/i.test(name)) continue;
        const existing = a.meds.find((e) => lower(e.name) === name.toLowerCase());
        const pd = parseDose(m.dose as string | null); const t = hhmm(m.time);
        if (existing) { existing.taken_local ??= t ? `${d}T${t}:00` : null; existing.effect ??= (m.effect as string) ?? null; existing.side_effect ??= (m.side_effects as string) ?? null; if (existing.dose_value === null && pd.dose_value !== null) { existing.dose_value = pd.dose_value; existing.dose_unit = pd.dose_unit; } continue; }
        a.meds.push({ name, dose_value: pd.dose_value, dose_unit: pd.dose_unit, taken_local: t ? `${d}T${t}:00` : null, effect: (m.effect as string) ?? null, side_effect: (m.side_effects as string) ?? null });
      }
      for (const r of items("reliefs")) { const n = norm(String(r.name)); if (EFFECT_WORDS.test(n)) { const one = a.meds.length === 1 ? a.meds[0] : null; if (one && !one.effect) one.effect = n; continue; } const ex = a.reliefs.find((e) => lower(e.name) === n.toLowerCase()); const st = hhmm(r.start), en = hhmm(r.end); const rel: Relief = { name: n, start_local: st ? `${d}T${st}:00` : null, end_local: en ? `${d}T${en}:00` : null, effect: (r.effect as string) ?? null, side_effect: null }; if (ex) { ex.start_local ??= rel.start_local; ex.end_local ??= rel.end_local; ex.effect ??= rel.effect; } else a.reliefs.push(rel); }
      const num = (k: string) => typeof x[k] === "number" && isFinite(x[k] as number) ? x[k] as number : null;
      const met: [string, number | null][] = [["sleep_hours", num("sleep_hours")], ["sleep_disturbances", num("sleep_disturbances")], ["hydration_ml", num("water_ml")], ["weight_kg", num("weight_kg")], ["steps", num("steps")], ["mindfulness_minutes", num("mindfulness_minutes")]];
      for (const [k, v] of met) if (v !== null) dd().metrics[k] = v;
      const bt = hhmm(x.bedtime), wt = hhmm(x.wake_time);
      if (bt) dd().times.bedtime = +bt.slice(0, 2) >= 12 ? `${addMinutesLocal(`${d}T00:00:00`, -1440).slice(0, 10)}T${bt}:00` : `${d}T${bt}:00`;
      if (wt) dd().times.wake_time = `${d}T${wt}:00`;
      const bp = typeof x.blood_pressure === "string" ? x.blood_pressure.match(/(\d{2,3})\s*\/\s*(\d{2,3})/) : null; if (bp) { dd().metrics.bp_systolic = +bp[1]; dd().metrics.bp_diastolic = +bp[2]; }
      if (x.period_start === true) dd().period = "start"; else if (x.period_end === true) dd().period = "end";
      for (const rg of items("regimen_started")) { const pd = parseDose(rg.dose as string | null); dd().regimens.push({ name: norm(String(rg.name)), dose_value: pd.dose_value, dose_unit: pd.dose_unit, frequency: (rg.frequency as string) ?? "daily", kind: (["drug", "lifestyle", "device"].includes(String(rg.kind)) ? rg.kind : "drug") as RegimenMention["kind"], started: d, stopped: null }); }
      for (const n of strs("regimen_stopped")) dd().regimens.push({ name: n, dose_value: null, dose_unit: null, frequency: null, kind: "drug", started: null, stopped: d });
      for (const se of Array.isArray(x.treatment_side_effects) ? x.treatment_side_effects as Record<string, unknown>[] : []) { const sy = Array.isArray(se?.symptoms) ? (se.symptoms as string[]).map(norm).filter(Boolean) : []; if (sy.length) dd().side_effects.push({ symptoms: sy, notes: (se.notes as string) ?? null, regimen: (se.regimen as string) ?? null }); }
    }
  }
  // Day notes: what the reader found on a no-attack day belongs to the day, not to an attack.
  for (const pa of dayPseudo) {
    const d = dayOf(dateOf(pa.start_local));
    d.daily_meds.push(...pa.meds); d.triggers.push(...pa.triggers); d.prodromes.push(...pa.prodromes); d.missed.push(...pa.missed_detail);
    d.foods.push(...pa.foods.map((f) => ({ name: f, time_local: pa.times[`food|${f.toLowerCase()}`] ?? null })));
    if (pa.meds.some((m) => m.side_effect)) d.side_effects.push(...pa.meds.filter((m) => m.side_effect).map((m) => ({ symptoms: [m.side_effect!], notes: null, regimen: m.name })));
  }
  inferences.push(`${mined} notes read for symptoms, warning signs, after-effects, triggers, foods, medicines with effect and side effects, reliefs, activities, missed plans, sleep, period and treatment mentions. Items were kept in the person's own words.`);
}

// ─────────────────────────────────────────────────────────────────────────────
// Vocabulary + linking
// ─────────────────────────────────────────────────────────────────────────────

function buildVocabulary(attacks: Attack[], days: Day[]): Record<SourceClass, VocabEntry[]> {
  const acc: Record<string, Map<string, VocabEntry>> = {};
  const add = (cls: SourceClass, phrase: string, when: string, example: string | null) => {
    const p = norm(phrase); if (!p) return;
    acc[cls] ??= new Map();
    const key = p.toLowerCase();
    const e = acc[cls].get(key) ?? { phrase: p, count: 0, examples: [], first: when, last: when };
    e.count++; if (when < e.first) e.first = when; if (when > e.last) e.last = when;
    if (example && e.examples.length < 3 && !e.examples.includes(example)) e.examples.push(example.slice(0, 160));
    acc[cls].set(key, e);
  };
  for (const a of attacks) {
    const w = dateOf(a.start_local), ex = a.notes ?? a.provenance.quote;
    a.types.forEach((t) => add("type", t, w, ex)); a.symptoms.forEach((t) => add("symptom", t, w, ex));
    if (!a.canonical_pain) a.pain_locations.forEach((t) => add("pain_location", t, w, ex));
    a.prodromes.forEach((t) => add("prodrome", t, w, ex)); a.postdromes.forEach((t) => add("postdrome", t, w, ex));
    a.triggers.forEach((t) => add("trigger", t, w, ex)); a.foods.forEach((t) => add("food", t, w, ex));
    a.meds.forEach((m) => add("medicine", m.name, w, ex)); a.reliefs.forEach((r) => add("relief", r.name, w, ex));
    a.activities.forEach((t) => add("activity", t, w, ex)); a.missed.forEach((t) => add("missed", t, w, ex));
    if (a.location) add("location", a.location, w, ex);
  }
  for (const d of days) { d.daily_meds.forEach((m) => add("medicine", m.name, d.date, null)); d.triggers.forEach((t) => add("trigger", t, d.date, null)); d.prodromes.forEach((t) => add("prodrome", t, d.date, null)); d.foods.forEach((f) => add("food", f.name, d.date, null)); d.missed.forEach((m) => add("missed", m.name, d.date, null)); d.side_effects.forEach((se) => se.symptoms.forEach((t) => add("side_effect", t, d.date, se.notes))); d.regimens.forEach((r) => add("medicine", r.name, d.date, null)); }

  const out = {} as Record<SourceClass, VocabEntry[]>;
  for (const [k, m] of Object.entries(acc)) out[k as SourceClass] = Array.from(m.values()).sort((a, b) => b.count - a.count);
  return out;
}

interface Pools { [cls: string]: { label: string; category: string | null; icon_key: string | null }[] }
const POOL_TABLE: Record<string, string> = { type: "user_migraines_pool", symptom: "user_symptoms", prodrome: "user_prodromes", postdrome: "user_symptoms", trigger: "user_triggers", medicine: "user_medicines", relief: "user_reliefs", activity: "user_activities", location: "user_locations", missed: "user_missed_activities", side_effect: "user_treatment_side_effects" };
const PREF_TABLE: Record<string, [string, string]> = { type: ["migraine_preferences", "migraine_id"], symptom: ["symptom_preferences", "symptom_id"], prodrome: ["prodrome_user_preferences", "prodrome_id"], trigger: ["trigger_preferences", "trigger_id"], medicine: ["medicine_preferences", "medicine_id"], relief: ["relief_preferences", "relief_id"], activity: ["activity_preferences", "activity_id"], location: ["location_preferences", "location_id"], missed: ["missed_activity_preferences", "missed_activity_id"], side_effect: ["treatment_side_effect_preferences", "side_effect_id"] };
const CATEGORIES: Record<string, string[]> = {
  symptom: ["accompanying", "Cognitive", "Motor", "pain_character", "Postdrome", "Visual"], prodrome: ["Autonomic", "Cognitive", "Digestive", "Mood", "Physical", "Sensory"],
  trigger: ["Cognitive", "Diet", "Environment", "Menstrual Cycle", "Physical", "Sleep"], medicine: ["Analgesic", "Anti-Nausea", "CGRP", "Combination", "Ditan", "Ergotamine", "Other", "Preventive", "Supplement", "Triptan"],
  relief: ["Breathing", "Cold/Heat", "Darkness", "Device", "Hydration", "Massage", "Meditation", "Movement", "Other", "Rest", "Supplement"], location: ["Exercise", "Home", "Medical", "Other", "Outdoors", "Social", "Transport", "Work"],
  activity: ["Exercise", "Leisure", "Other", "Screen", "Sleep", "Social", "Travel", "Work"], missed: ["Care", "Exercise", "Leisure", "Other", "Social", "Travel", "Work"], type: [], side_effect: [],
};
const ICONS: Record<string, string[]> = {
  symptom: ["aura", "blur", "body_aches", "brainfog", "cluster", "cold", "dizziness", "fatigue", "hangover", "hemiplegic", "light_lingering", "migraine_starburst", "mood_crash", "mood_lift", "nausea", "neck", "ocular", "sinus", "sound_lingering", "tension", "vestibular", "weakness"],
  prodrome: ["background_pain", "brainfog", "depression", "euphoria", "fatigue", "fluid_retention", "food_cravings", "frequent_urination", "irritability", "light", "loss_appetite", "mood_change", "nasal_congestion", "neck", "neck_stiffness", "smell", "sound", "tearing", "thirst", "tingling", "yawning"],
  trigger: ["allergies", "anger", "anxiety", "computer", "contraceptive", "fluorescent", "hydration", "illness", "jet_lag", "letdown", "low_blood_sugar", "medication", "menstruation", "motion_sickness", "ovulation", "sexual_activity", "skipped_meals", "sleep_apnea", "smoke", "stress", "strong_smell", "tobacco", "travel"],
  relief: ["acupressure", "bath", "box_breathing", "breathing", "coffee", "darkness", "electrolytes", "eye_mask", "fresh_air", "ginger", "heat", "ice", "massage", "meditation", "peppermint", "quiet", "rest", "shower", "stretch", "sunglasses", "walk", "water", "yoga"],
  location: ["bar", "beach", "bedroom", "bus", "cafe", "car", "church", "cinema", "doctor", "forest", "friends_house", "garden", "gym", "home", "hospital", "mountains", "office", "outdoors", "park", "plane", "restaurant", "school", "shop", "supermarket", "train", "wfh", "work"],
  activity: ["childcare", "cleaning", "commuting", "concert", "cooking", "cycling", "driving", "eating_out", "exercising", "flying", "gaming", "gym", "hiking", "hobbies", "meeting", "napping", "partying", "phone", "presenting", "reading", "running", "screen_time", "shopping", "sleeping", "socialising", "studying", "swimming", "tv_film", "walking", "working", "yoga"],
  missed: ["childcare", "chores", "concert", "cooking", "date", "driving", "exercise", "family_event", "flying", "gym", "hiking", "hobbies", "meeting", "night_out", "pet_care", "school", "self_care", "shopping", "social_plans", "sport", "study", "travel", "walk", "work"],
  medicine: [], type: [], side_effect: [],
};

const PAIN_IDS = ["forehead_left", "forehead_center", "forehead_right", "brow_left", "brow_right", "temple_left", "temple_right", "eye_left", "eye_right", "sinus_left", "sinus_right", "nose_bridge", "vertex", "occipital_left", "occipital_center", "occipital_right", "base_skull_left", "base_skull_center", "base_skull_right", "neck_left", "neck_right", "behind_ear_left", "behind_ear_right", "jaw_left", "jaw_right"];

const PAIN_WORDS: [RegExp, string[]][] = [
  [/^(whole|entire|all over|hele|ganzer?) ?(head|hoofd|kopf)?$/i, ["forehead_center", "temple_left", "temple_right", "vertex", "occipital_center"]],
  [/^both temples|beide slapen|beide schläfen$/i, ["temple_left", "temple_right"]],
  [/^(behind|achter|hinter) (the |both |my |de |den )?(eyes|ogen|augen)$/i, ["eye_left", "eye_right"]],
  [/^(behind |achter |hinter )?(the |my )?(left|linker?|linkes?) (eye|oog|auge)$/i, ["eye_left"]], [/^(behind |achter |hinter )?(the |my )?(right|rechter?|rechtes?) (eye|oog|auge)$/i, ["eye_right"]],
  [/^(left|linker?|linke) (temple|slaap|schläfe)$/i, ["temple_left"]], [/^(right|rechter?|rechte) (temple|slaap|schläfe)$/i, ["temple_right"]],
  [/^(left|linker) (side|kant|seite)$/i, ["temple_left", "eye_left", "brow_left"]], [/^(right|rechter) (side|kant|seite)$/i, ["temple_right", "eye_right", "brow_right"]],
  [/^(both sides|beide kanten|beide seiten|bilateral)$/i, ["temple_left", "temple_right"]],
  [/^(forehead|voorhoofd|stirn|front)$/i, ["forehead_center"]], [/^(left|linker) (forehead|voorhoofd)$/i, ["forehead_left"]], [/^(right|rechter) (forehead|voorhoofd)$/i, ["forehead_right"]],
  [/^(top of (the |my )?head|crown|kruin|scheitel|vertex)$/i, ["vertex"]],
  [/^(back of (the |my )?head|achterhoofd|hinterkopf|occipital)$/i, ["occipital_center", "base_skull_center"]],
  [/^(neck|nek|nacken)$/i, ["neck_left", "neck_right"]], [/^(left|linker) (neck|nek)$/i, ["neck_left"]], [/^(right|rechter) (neck|nek)$/i, ["neck_right"]],
  [/^(base of (the )?skull|schedelbasis|schädelbasis)$/i, ["base_skull_center"]],
  [/^(sinus(es)?|bijholtes?|nebenhöhlen)$/i, ["sinus_left", "sinus_right"]], [/^(behind (the |my )?ears?)$/i, ["behind_ear_left", "behind_ear_right"]],
  [/^(jaw|kaak|kiefer)$/i, ["jaw_left", "jaw_right"]], [/^(brow|eyebrow|wenkbrauw)s?$/i, ["brow_left", "brow_right"]],
  [/^(left|linker) (brow|eyebrow)$/i, ["brow_left"]], [/^(right|rechter) (brow|eyebrow)$/i, ["brow_right"]],
];
function painWordsToIds(phrase: string): string[] | null {
  const p = norm(phrase).replace(/[.,;]+$/, "");
  for (const [re, ids] of PAIN_WORDS) if (re.test(p)) return ids;
  if (PAIN_IDS.includes(p.toLowerCase().replace(/\s+/g, "_"))) return [p.toLowerCase().replace(/\s+/g, "_")];
  return null;
}
const GENERIC_MED = /^((the |my |mijn |de |het |meine?n? )?(tablet|tabletten|tablets?|pil|pillen|pills?|medicijn|medicijnen|medication|medicatie|meds?|ingenomen|genomen|taken|eingenommen|pris|painkillers?|pijnstillers?|schmerzmittel|tabletten genomen|pillen genomen|took (something|meds|tablets|my usual)|iets genomen|usual|usuals|gebruikelijke|gewone|the strong one|sterke|other one|normal one|standard)( medication| medicatie| meds| pills?| tablets?| one| dose)?)$/i;
const TIME_OF_DAY = /^(s\s*|'s\s*|in the |im |am |le )?(ochtends|middags|avonds|nachts|morning|afternoon|evening|night|morgens|mittags|abends|matin|après-midi|soir)$/i;
const QUANTITY_ONLY = /^(tab|tabs|st|stuks|pcs|pil|pillen)$|^(\d+\s*x\s*\d+(\s*(st|stuks?|tab|tabs|tablets?|tabletten))?(\s*(\+|en|and)\s*\d+\s*x?\s*\d*\s*(st|x|tab)?)?|\d+([.,]\d+)?\s*(st|stuks?|tab|tabs|tablets?|tabletten|pcs?|pil{1,2}(en)?|x)?(\s*(\+|en|and)\s*\d+\s*(st|x|tab)?)?(\s+(s\s*)?(ochtends|middags|avonds|in the morning|in the afternoon|in the evening))?|dubbele dosering|double dose|new dose|nieuwe dosis|2e x|2nd dose|second dose|extra dosis|extra dose)$/i;
const EFFECT_WORDS = /^((it |het |dat |es )?(helped|worked|didn['’]?t (help|work)|did not (help|work)|no effect|effective|ineffective|hielp|hielp goed|hielp niet|werkte|werkte niet|geholpen|goed geholpen|niet geholpen|goed resultaat|geen effect|zonder effect|geholfen|gewirkt|nicht geholfen|efficace|inefficace|a aidé|n['’]a pas aidé)( well| a lot| a bit| goed| niet| nicht| within .*| binnen .*)?)$/i;
const BARE_ATTACK = /^(migraine|migraines|headache|hoofdpijn|migräne|kopfschmerz(en)?|migraña|emicrania|enxaqueca|mal de tête|attack|aanval|anfall)$/i;
const GENERIC_ANY = /^((the |my |mijn |de |het |meine?n? )?(usual|usuals|gebruikelijke|gewone|same( as always| as usual)?|routine|usual routine|normal routine|something|iets|etwas|stuff|things?|the strong one|other one|normal one|standard|as always|whatever|nothing special|the works)( routine| stuff| thing| one)?)$/i;

const LINK_SYSTEM = `You link words from a person's migraine diary to the vocabulary of the MigraineMe app. You see every distinct phrase of one class with how often it occurs, example sentences, and the whole file's vocabulary in other classes for context. Decide for EACH phrase:

concept: what it really is: type (migraine type), symptom, prodrome (pre-attack sign), postdrome, trigger, food (any food or drink, even if the person listed it as a trigger), medicine, relief, activity, location, missed, side_effect (a side effect of a daily preventive), regimen (a preventive taken daily), pain_location, drop (not health data, or unusable).
A phrase that names nothing specific ("my usual", "the usual routine", "the strong one", "something", "stuff", "same as always") is ambiguous in EVERY class, never a new item.
tier is about what the phrase MEANS, never about whether we already have a label for it:
  "certain"   the phrase clearly names one thing ("Rizatriptan", "nausea", "dark room", "loud noise", "weather change", "heat"). Certain even when no existing label fits: then fill new_item.
  "likely"    the phrase is vague but the file settles it: "tablets" in a file whose only named drug is Rizatriptan is likely Rizatriptan. State the evidence in reasoning.
  "ambiguous" the phrase is vague AND nothing in the file settles it: "tabletten genomen" (took tablets) in a file that names several drugs or none. NEVER pick a specific label for an ambiguous phrase.
Worked examples:
  "Red wine" listed as a trigger -> concept food, certain (foods and drinks are foods, whatever column they came from).
  "Chocolate cake", "cheese board", "coffee" -> food, certain.
  "Loud noise" with no existing label -> trigger, certain, new_item {label "Loud noise", category "Environment", icon_key "sound" or null}.
  "Poor sleep" when the pool has "Poor sleep" -> pool_label "Poor sleep", certain.
  "Screen time" when the pool has "Computer / screen" -> pool_label "Computer / screen", certain.
  "Quiet room" when the pool has "Quiet environment" -> pool_label "Quiet environment", likely.
  "Neck was stiff the evening before" -> prodrome (it happened before the attack).
  "Whole head" as a pain position -> pain_location, certain.
  "zigzag lines in vision", "flickering", "blind spot" before the pain -> symptom, pool_label "Aura", certain.
  "drowsy" / "dizzy" as a side effect of a medicine -> side_effect, pool_label "Drowsiness" / "Dizziness" when those labels exist.
pool_label: the EXACT existing label (case and spelling as given) when one fits. Brand names map to the generic in our pool (Imigran -> Sumatriptan, Advil/Nurofen -> Ibuprofen, Tylenol -> Paracetamol, Maxalt -> Rizatriptan, Relpax -> Eletriptan). Translate other languages to our English labels.
new_item: when no existing label fits and the tier is certain or likely: { "label": short English label (or the person's word if it is a proper noun), "category": one of the allowed categories, "icon_key": the closest allowed icon key or null }.
For ambiguous phrases set pool_label null and new_item null.
For class pain_location return instead: { "concept": "pain_location", "tier": "certain", "ids": [one or more of the allowed pain ids], "reasoning": ... } using ONLY the allowed ids given; "left side" -> temple_left, eye_left, brow_left; "both temples" -> temple_left, temple_right; "behind eyes" -> eye_left, eye_right; "whole head" -> forehead_center, temple_left, temple_right, vertex, occipital_center; "forehead" -> forehead_center.
reasoning: one short sentence.

Return JSON: { "<phrase>": { "concept": ..., "tier": ..., "pool_label": ...|null, "new_item": {...}|null, "reasoning": "..." } }`;

async function link(model: Model, pools: Pools, spend: Spend): Promise<LinkDecision[]> {
  const out: LinkDecision[] = [];
  const namedMeds = uniq((model.vocabulary.medicine ?? []).map((e) => e.phrase).filter((p) => !GENERIC_MED.test(p) && !GENERIC_ANY.test(p)).map((p) => p.toLowerCase().replace(/\s*\d+\s*(mg|mcg)?$/, "")));
  const context = Object.fromEntries(Object.entries(model.vocabulary).map(([k, v]) => [k, v.slice(0, 40).map((e) => e.phrase)]));
  const classJobs: Promise<void>[] = [];
  for (const [clsRaw, entries] of Object.entries(model.vocabulary)) {
    const cls = clsRaw as SourceClass;
    if (!entries.length) continue;
    classJobs.push((async () => {
    if (cls === "pain_location") {
      const rest: VocabEntry[] = [];
      for (const e of entries) { const ids = painWordsToIds(e.phrase); if (ids) out.push({ class: cls, phrase: e.phrase, concept: "pain_location", tier: "certain", pool_label: null, new_item: null, reasoning: "known head-map word", count: e.count, pain_ids: ids }); else rest.push(e); }
      if (!rest.length) return;
      model.vocabulary.pain_location = rest; // fall through to the model for the unknown words only
    }
    if (cls === "food") { entries.forEach((e) => out.push({ class: cls, phrase: e.phrase, concept: "food", tier: "certain", pool_label: null, new_item: null, reasoning: "food goes to nutrition records and the exposure classifier", count: e.count })); return; }
    const poolCls = cls === "postdrome" ? "symptom" : cls;
    const labels = (pools[poolCls] ?? []).map((p) => p.label);
    const chunk = 80;
    const todo = cls === "pain_location" ? model.vocabulary.pain_location : entries;
    for (let i = 0; i < todo.length; i += chunk) {
      const part = todo.slice(i, i + chunk);
      const user = JSON.stringify({ class: cls, phrases: part.map((e) => ({ phrase: e.phrase, count: e.count, examples: e.examples, from: e.first, to: e.last })), existing_labels: cls === "pain_location" ? [] : labels, allowed_pain_ids: cls === "pain_location" ? PAIN_IDS : undefined, allowed_categories: CATEGORIES[poolCls] ?? [], allowed_icon_keys: ICONS[poolCls] ?? [], file_vocabulary_for_context: context, medicines_named_in_file: namedMeds, person_language: "unknown" });
      const j = await askJson(LINK_SYSTEM, user, spend);
      for (const e of part) {
        const d = (j[e.phrase] ?? j[e.phrase.toLowerCase()]) as Record<string, unknown> | undefined;
        const dec: LinkDecision = { class: cls, phrase: e.phrase, concept: cls, tier: "ambiguous", pool_label: null, new_item: null, reasoning: "no decision returned", count: e.count };
        if (d && cls === "pain_location") {
          const rawIds = Array.isArray(d.ids) ? d.ids as string[] : typeof d.pool_label === "string" ? String(d.pool_label).split(/[,\s]+/) : (d.new_item as { label?: string } | null)?.label ? String((d.new_item as { label: string }).label).split(/[,\s]+/) : [];
          const ids = rawIds.map((x) => x.toLowerCase().replace(/\s+/g, "_")).filter((x) => PAIN_IDS.includes(x));
          dec.concept = "pain_location"; dec.tier = ids.length ? "certain" : "ambiguous"; dec.pain_ids = ids; dec.reasoning = String(d.reasoning ?? (ids.length ? "" : "no head-map position for this word"));
          if (!ids.length) dec.concept = "drop";
        } else if (d) {
          const KNOWN: Concept[] = ["type", "symptom", "prodrome", "postdrome", "trigger", "medicine", "relief", "activity", "location", "missed", "food", "pain_location", "side_effect", "regimen", "drop"];
          dec.concept = (KNOWN.includes(d.concept as Concept) ? d.concept : (typeof d.concept === "string" ? "drop" : cls)) as Concept;
          if (dec.concept === "drop") dec.reasoning = String(d.reasoning ?? `"${e.phrase}" is not a ${cls}`);
          dec.tier = (["certain", "likely", "ambiguous"].includes(String(d.tier)) ? d.tier : "ambiguous") as Tier;
          dec.reasoning = String(d.reasoning ?? "");
          const targetCls = dec.concept === "regimen" ? "medicine" : dec.concept === "postdrome" ? "symptom" : dec.concept;
          const targetLabels = (pools[targetCls] ?? []).map((p) => p.label);
          if (typeof d.pool_label === "string" && d.pool_label) {
            const exact = targetLabels.find((l) => l === d.pool_label) ?? targetLabels.find((l) => l.toLowerCase() === String(d.pool_label).toLowerCase());
            if (exact) dec.pool_label = exact; else { dec.tier = dec.tier === "ambiguous" ? "ambiguous" : "likely"; dec.new_item = { label: String(d.pool_label), category: null, icon_key: null }; dec.reasoning += " (label not in pool, kept as new item)"; }
          }
          const ni = d.new_item as { label?: string; category?: string; icon_key?: string } | null;
          if (!dec.pool_label && ni?.label && dec.tier !== "ambiguous") {
            const cats = CATEGORIES[targetCls] ?? [], icons = ICONS[targetCls] ?? [];
            dec.new_item = { label: norm(ni.label), category: ni.category && cats.includes(ni.category) ? ni.category : (targetCls === "symptom" ? "accompanying" : null), icon_key: ni.icon_key && icons.includes(ni.icon_key) ? ni.icon_key : null };
          }
          if ((cls === "medicine" || dec.concept === "medicine") && GENERIC_MED.test(e.phrase) && namedMeds.length !== 1) {
            dec.tier = "ambiguous"; dec.reasoning = namedMeds.length ? `"${e.phrase}" names no drug and the file names ${namedMeds.length} different medicines` : `"${e.phrase}" names no drug and the file names none`;
          } else if ((cls === "relief" && EFFECT_WORDS.test(e.phrase)) || (cls === "type" && BARE_ATTACK.test(e.phrase)) || TIME_OF_DAY.test(e.phrase)) {
            dec.concept = "drop"; dec.tier = "certain"; dec.reasoning = cls === "relief" ? `"${e.phrase}" says how well something worked, it is not a relief` : cls === "type" ? `"${e.phrase}" is the attack itself` : `"${e.phrase}" is a time of day, not a ${cls}`;
          } else if (GENERIC_MED.test(e.phrase) && cls !== "medicine" && dec.concept !== "medicine") {
            dec.concept = "drop"; dec.tier = "certain"; dec.reasoning = `"${e.phrase}" just says medication was taken; the medicines themselves are listed separately`;
          } else if (GENERIC_ANY.test(e.phrase) || QUANTITY_ONLY.test(e.phrase)) {
            dec.tier = "ambiguous"; dec.reasoning = `"${e.phrase}" names nothing specific`;
          }
          if (dec.tier === "ambiguous") { dec.pool_label = null; dec.new_item = null; }
        }
        out.push(dec);
      }
    }
    })());
  }
  await Promise.all(classJobs);
  return out;
}

const RESOLVE_SYSTEM = `A first pass could not decide what these diary phrases mean. You now get, for each phrase, the full sentences it came from and everything else logged on those days. Decide again, carefully:
- If the sentences settle it, resolve it: give the concept and the exact existing label (or a new_item). Tier "likely", with the evidence in reasoning.
- A phrase may mean TWO things: "long day at the office on the laptop" is activity Working AND trigger Stress (or Computer / screen if that label exists). Return the main one in pool_label/new_item and the others in "also".
- A generic medicine word ("tablets", "pillen") is resolved ONLY if exactly one medicine is named anywhere in the file (medicines_named_in_file has one entry). Otherwise it stays ambiguous, whatever the sentence says.
- If nothing settles it, keep tier "ambiguous" and say in one sentence what is missing. Never guess.
Return JSON: { "<phrase>": { "concept": ..., "tier": "likely"|"ambiguous", "pool_label": ...|null, "new_item": {label,category,icon_key}|null, "also": [ {concept, pool_label|null, new_item|null} ], "reasoning": "..." } }`;

async function resolveAmbiguous(model: Model, pools: Pools, decisions: LinkDecision[], namedMeds: string[], spend: Spend) {
  const open = decisions.filter((d) => d.tier === "ambiguous" && d.concept !== "drop" && d.class !== "pain_location");
  if (!open.length) return;
  const dayContext = (phrase: string) => {
    const lp = phrase.toLowerCase();
    const hits = model.attacks.filter((a) => [...a.symptoms, ...a.triggers, ...a.prodromes, ...a.foods, ...a.activities, ...a.missed, ...a.meds.map((m) => m.name), ...a.reliefs.map((r) => r.name), a.location ?? ""].some((x) => x.toLowerCase() === lp)).slice(0, 3);
    return hits.map((a) => ({ date: dateOf(a.start_local), sentence: a.notes ?? a.provenance.quote, symptoms: a.symptoms, triggers: a.triggers, meds: a.meds.map((m) => m.name), reliefs: a.reliefs.map((r) => r.name), activities: a.activities, location: a.location }));
  };
  const allLabels = Object.fromEntries(Object.entries(pools).map(([k, v]) => [k, v.map((p) => p.label)]));
  for (let i = 0; i < open.length; i += 25) {
    const part = open.slice(i, i + 25);
    const user = JSON.stringify({ phrases: part.map((d) => ({ phrase: d.phrase, first_pass_class: d.class, days: dayContext(d.phrase) })), existing_labels_by_class: allLabels, allowed_categories: CATEGORIES, allowed_icon_keys: ICONS, medicines_named_in_file: namedMeds });
    const j = await askJson(RESOLVE_SYSTEM, user, spend);
    for (const d of part) {
      const r = (j[d.phrase] ?? j[d.phrase.toLowerCase()]) as Record<string, unknown> | undefined; if (!r) continue;
      const fix = (concept: string, pl: unknown, ni: unknown) => {
        const tc = concept === "regimen" ? "medicine" : concept === "postdrome" ? "symptom" : concept;
        const labels = (pools[tc] ?? []).map((p) => p.label);
        const exact = typeof pl === "string" ? (labels.find((l) => l === pl) ?? labels.find((l) => l.toLowerCase() === String(pl).toLowerCase())) : undefined;
        const n = ni as { label?: string; category?: string; icon_key?: string } | null;
        const newItem = !exact && n?.label ? { label: norm(n.label), category: n.category && (CATEGORIES[tc] ?? []).includes(n.category) ? n.category : (tc === "symptom" ? "accompanying" : null), icon_key: n.icon_key && (ICONS[tc] ?? []).includes(n.icon_key) ? n.icon_key : null } : (!exact && typeof pl === "string" && pl ? { label: norm(String(pl)), category: null, icon_key: null } : null);
        return { pool_label: exact ?? null, new_item: newItem };
      };
      const KNOWN2: Concept[] = ["type", "symptom", "prodrome", "postdrome", "trigger", "medicine", "relief", "activity", "location", "missed", "food", "pain_location", "side_effect", "regimen", "drop"];
      const concept = (KNOWN2.includes(r.concept as Concept) ? r.concept : d.class) as Concept;
      const generic = (d.class === "medicine" || concept === "medicine") && GENERIC_MED.test(d.phrase);
      const vague = GENERIC_ANY.test(d.phrase) || (GENERIC_MED.test(d.phrase) && concept !== "medicine");
      if (r.tier === "likely" && !(generic && namedMeds.length !== 1) && !vague) {
        const f = fix(concept, r.pool_label, r.new_item);
        if (f.pool_label || f.new_item) {
          d.concept = concept; d.tier = "likely"; d.pool_label = f.pool_label; d.new_item = f.new_item; d.reasoning = String(r.reasoning ?? "resolved from the sentence");
          d.also = Array.isArray(r.also) ? (r.also as Record<string, unknown>[]).map((x) => { const c = String(x.concept ?? ""); const g = fix(c, x.pool_label, x.new_item); return (g.pool_label || g.new_item) ? { concept: c as Concept, ...g } : null; }).filter((x): x is NonNullable<typeof x> => !!x) : undefined;
          continue;
        }
      }
      d.reasoning = String(r.reasoning ?? d.reasoning);
    }
  }
}

// One answer settles its siblings: same class, same generic family.
function siblingKeys(decisions: LinkDecision[]): void {
  const generics = decisions.filter((d) => d.tier === "ambiguous" && (d.class === "medicine" || d.concept === "medicine") && (GENERIC_MED.test(d.phrase) || GENERIC_ANY.test(d.phrase)));
  for (const d of generics) d.siblings = generics.filter((x) => x !== d).map((x) => `${x.class}|${x.phrase}`);
}

// ─────────────────────────────────────────────────────────────────────────────
// Regimen proposals (deterministic)
// ─────────────────────────────────────────────────────────────────────────────

interface RegimenProposal { name: string; days: number; start_date: string; stop_date: string | null; dose_value: number | null; dose_unit: string | null; frequency?: string | null; kind?: "drug" | "lifestyle" | "device"; from?: "pattern" | "mention" }

function proposeRegimens(model: Model, decisions: LinkDecision[]): RegimenProposal[] {
  const canon = (n: string) => { const d = decisions.find((x) => x.class === "medicine" && x.phrase.toLowerCase() === lower(n)); return d?.pool_label ?? d?.new_item?.label ?? n; };
  const byName = new Map<string, { days: Set<string>; dose: Med | null }>();
  const seen = (name: string, day: string, m: Med) => { const k = canon(name); const e = byName.get(k) ?? { days: new Set(), dose: null }; e.days.add(day); if (!e.dose && m.dose_value) e.dose = m; byName.set(k, e); };
  for (const a of model.attacks) a.meds.forEach((m) => seen(m.name, dateOf(a.start_local), m));
  for (const d of model.days) d.daily_meds.forEach((m) => seen(m.name, d.date, m));
  const out: RegimenProposal[] = []; const today = new Date().toISOString().slice(0, 10);
  for (const [name, e] of byName) {
    const days = Array.from(e.days).sort(); if (days.length < 20) continue;
    let dense = false;
    for (let i = 0; i + 19 < days.length; i++) { if (localMinutesBetween(days[i] + "T00:00:00", days[i + 19] + "T00:00:00") <= 30 * 1440) { dense = true; break; } }
    if (!dense) continue;
    const last = days[days.length - 1];
    const stop = localMinutesBetween(last + "T00:00:00", today + "T00:00:00") > 14 * 1440 ? last : null;
    out.push({ name, days: days.length, start_date: days[0], stop_date: stop, dose_value: e.dose?.dose_value ?? null, dose_unit: e.dose?.dose_unit ?? null, frequency: "daily", kind: "drug", from: "pattern" });
  }
  for (const d of [...model.days].sort((a, b) => a.date.localeCompare(b.date))) for (const r of d.regimens) {
    const name = canon(r.name); const ex = out.find((o) => o.name.toLowerCase() === name.toLowerCase());
    if (r.stopped) { if (ex) ex.stop_date = r.stopped; continue; }
    if (ex) { ex.start_date = r.started && r.started < ex.start_date ? r.started : ex.start_date; ex.frequency = r.frequency ?? ex.frequency; continue; }
    out.push({ name, days: 0, start_date: r.started ?? d.date, stop_date: null, dose_value: r.dose_value, dose_unit: r.dose_unit, frequency: r.frequency, kind: r.kind, from: "mention" });
  }
  return out;
}

// ─────────────────────────────────────────────────────────────────────────────
// Use for insights: per category, recommended from what the file actually holds
// ─────────────────────────────────────────────────────────────────────────────

interface EngineUse { key: string; label: string; recommended: boolean; reason: string; count: number }

const METRIC_LABEL: Record<string, string> = { sleep_hours: "Sleep hours", hydration_ml: "Water", weight_kg: "Weight", steps: "Steps", sleep_disturbances: "Night wake-ups", mindfulness_minutes: "Mindfulness", body_fat_pct: "Body fat", blood_glucose: "Blood glucose", bp_systolic: "Blood pressure", bedtime: "Bedtime", wake_time: "Wake-up time" };

export function engineUseRecommendations(model: Model): EngineUse[] {
  const attacks = model.attacks, days = model.days;
  const attackDates = new Set(attacks.map((a) => dateOf(a.start_local)));
  const dates = attacks.map((a) => dateOf(a.start_local)).sort();
  const spanDays = dates.length ? Math.max(1, Math.round(localMinutesBetween(dates[0] + "T00:00:00", dates[dates.length - 1] + "T00:00:00") / 1440) + 1) : 1;
  const out: EngineUse[] = [];
  const n = (f: (a: Attack) => number) => attacks.reduce((s, a) => s + f(a), 0);
  out.push({ key: "attacks", label: "Attacks, symptoms, pain positions, aura", recommended: true, reason: "An attack is an attack. Used for how often, how long, at what time, and which symptoms come together.", count: attacks.length });
  const meds = n((a) => a.meds.length) + days.reduce((s, d) => s + d.daily_meds.length, 0);
  if (meds) out.push({ key: "medicines", label: "Medicines", recommended: true, reason: "Used for which medicine helped and how fast.", count: meds });
  const rel = n((a) => a.reliefs.length);
  if (rel) out.push({ key: "reliefs", label: "What helped", recommended: true, reason: "Used for which reliefs worked.", count: rel });
  const pa = n((a) => a.activities.length + a.missed.length + (a.location ? 1 : 0)) + days.reduce((s, d) => s + d.missed.length, 0);
  if (pa) out.push({ key: "places_activities", label: "Places, activities, missed plans", recommended: true, reason: "Used as they are.", count: pa });
  const trigAttack = n((a) => a.triggers.length + a.prodromes.length);
  const quietTrigDays = days.filter((d) => !attackDates.has(d.date) && (d.triggers.length || d.prodromes.length)).length;
  if (trigAttack || quietTrigDays) out.push({ key: "triggers", label: "Triggers and warning signs", recommended: quietTrigDays >= 3,
    reason: quietTrigDays >= 3 ? `Your old app recorded them on ${quietTrigDays} days without an attack too, so they can be scored fairly.` : "Your old app only recorded these on migraine days. Scoring them would make every one look like a cause. They stay readable in each attack's note.", count: trigAttack + quietTrigDays });
  const foodAttack = n((a) => a.foods.length);
  const quietFoodDays = days.filter((d) => !attackDates.has(d.date) && d.foods.length).length;
  if (foodAttack || quietFoodDays) out.push({ key: "foods", label: "Foods", recommended: quietFoodDays >= 3,
    reason: quietFoodDays >= 3 ? `Logged on ${quietFoodDays} days without an attack too, so exposure can be scored fairly.` : "Only recorded on migraine days, so exposure scoring would be one-sided. They stay in the journal as the triggers you tagged.", count: foodAttack + quietFoodDays });
  const metricKeys = uniq([...days.flatMap((d) => Object.keys(d.metrics)), ...days.flatMap((d) => Object.keys(d.times))]).filter((k) => METRIC_LABEL[k]);
  for (const k of metricKeys) {
    const have = days.filter((d) => d.metrics[k] !== undefined || d.times[k] !== undefined).length;
    const share = have / spanDays;
    out.push({ key: `metric:${k}`, label: METRIC_LABEL[k], recommended: share >= 0.5, reason: share >= 0.5 ? `Logged on ${have} of ${spanDays} days, enough to compare good and bad days.` : `Logged on only ${have} of ${spanDays} days, mostly around attacks. Not enough good days to compare against.`, count: have });
  }
  const periods = days.filter((d) => d.period === "start").length;
  if (periods) out.push({ key: "period", label: "Period", recommended: true, reason: "Calendar dates, used for the cycle pattern.", count: periods });
  if (days.some((d) => d.regimens.length)) out.push({ key: "treatments", label: "Treatments started or stopped", recommended: true, reason: "Used to compare attacks before and after.", count: days.reduce((s, d) => s + d.regimens.length, 0) });
  return out;
}

// ─────────────────────────────────────────────────────────────────────────────
// Preview
// ─────────────────────────────────────────────────────────────────────────────

function fillEnds(model: Model) {
  const known = model.attacks.filter((a) => a.end_local).map((a) => localMinutesBetween(a.start_local, a.end_local!)).filter((m) => m > 0 && m < 72 * 60).sort((a, b) => a - b);
  const median = known.length ? known[Math.floor(known.length / 2)] : 8 * 60;
  let n = 0;
  for (const a of model.attacks) {
    if (a.end_local) continue;
    let end = addMinutesLocal(a.start_local, median);
    if (dateOf(end) !== dateOf(a.start_local)) end = `${dateOf(a.start_local)}T23:59:00`;
    a.end_local = end; a.end_inferred = true; n++;
  }
  if (n) model.inferences.push(`${n} attacks had no end time. End set to start plus ${Math.round(median / 60 * 10) / 10} hours (${known.length ? "the median of this file's known durations" : "our default"}), capped at 23:59 the same day.`);
}

export function buildPreview(model: Model, decisions: LinkDecision[], regimens: RegimenProposal[], overlap: { first_app_log: string | null; overlapping: number }, spend: Spend) {
  const attacks = model.attacks;
  const dates = attacks.map((a) => dateOf(a.start_local)).sort();
  const NOUN: Record<string, string> = { medicine: "medicine", trigger: "trigger", symptom: "symptom", prodrome: "warning sign", relief: "relief", activity: "activity", location: "place", missed: "missed activity", type: "migraine type", postdrome: "after-effect", food: "food" };
  const questions = decisions.filter((d) => d.tier === "ambiguous" && d.concept !== "drop" && d.class !== "pain_location").sort((a, b) => b.count - a.count).map((d) => ({
    key: `${d.class}|${d.phrase}`, class: d.class, phrase: d.phrase, count: d.count,
    question: `On ${d.count} ${d.count === 1 ? "day" : "days"} you wrote "${d.phrase}". Which ${NOUN[d.class] ?? d.class} was that?`,
    why: d.reasoning, siblings: d.siblings ?? [], examples: (model.vocabulary[d.class] ?? []).find((e) => e.phrase === d.phrase)?.examples ?? [],
    if_unanswered: `Kept as "${d.phrase}" in your own words`,
  }));
  const sevCounts: Record<string, number> = {};
  for (const a of attacks) { const k = a.intensity.value === null ? "unknown" : String(a.intensity.value); sevCounts[k] = (sevCounts[k] ?? 0) + 1; }
  return {
    source: model.source, timezone: model.timezone,
    counts: {
      attacks: attacks.length, meds: attacks.reduce((s, a) => s + a.meds.length, 0) + model.days.reduce((s, d) => s + d.daily_meds.length, 0),
      symptoms: attacks.reduce((s, a) => s + a.symptoms.length + a.types.length, 0), triggers: attacks.reduce((s, a) => s + a.triggers.length, 0) + model.days.reduce((s, d) => s + d.triggers.length, 0),
      reliefs: attacks.reduce((s, a) => s + a.reliefs.length, 0), prodromes: attacks.reduce((s, a) => s + a.prodromes.length, 0),
      foods: attacks.reduce((s, a) => s + a.foods.length, 0) + model.days.reduce((s, d) => s + d.foods.length, 0),
      notes: attacks.filter((a) => a.notes).length, days_with_metrics: model.days.filter((d) => Object.keys(d.metrics).length).length,
      periods: model.days.filter((d) => d.period === "start").length, ends_inferred: attacks.filter((a) => a.end_inferred).length,
      postdromes: attacks.reduce((s, a) => s + a.postdromes.length, 0), activities: attacks.reduce((s, a) => s + a.activities.length, 0), missed: attacks.reduce((s, a) => s + a.missed.length, 0) + model.days.reduce((s, d) => s + d.missed.length, 0),
      locations: attacks.filter((a) => a.location).length, side_effects_meds: attacks.reduce((s, a) => s + a.meds.filter((m) => m.side_effect).length, 0), side_effect_logs: model.days.reduce((s, d) => s + d.side_effects.length, 0),
      sleep_days: model.days.filter((d) => d.metrics.sleep_hours || d.times.bedtime || d.times.wake_time).length, aura: attacks.filter((a) => a.aura.present).length, pain_positions: attacks.filter((a) => a.pain_locations.length).length,
    },
    range: { from: dates[0] ?? null, to: dates[dates.length - 1] ?? null },
    overlap,
    severity_distribution: sevCounts,
    inferences: model.inferences,
    questions, questions_total: questions.length,
    // One list the screen shows as "What we assumed", every row adjustable. Vague phrases sit here too:
    // the default is the person's own words, the options are what they could mean.
    assumptions: [
      ...(model.source.shape.date_order ? [{ key: "date_order", text: `Dates read as ${model.source.shape.date_order === "MDY" ? "month first" : model.source.shape.date_order === "YMD" || model.source.shape.date_order === "ISO" ? "year first" : "day first"}`, value: model.source.shape.date_order, options: ["DMY", "MDY", "YMD"] }] : []),
      ...(model.source.shape.intensity_scale ? [{ key: "intensity_scale", text: `Pain scale ${model.source.shape.intensity_scale}, converted to 1 to 10`, value: model.source.shape.intensity_scale, options: ["0-10", "1-10", "0-5", "1-3", "mild/moderate/severe"] }] : []),
      { key: "timezone", text: `Times are ${model.timezone.replace(/_/g, " ")} time`, value: model.timezone, options: null },
      ...(attacks.some((a) => a.start_local.endsWith("T12:00:00") && a.provenance.row !== null) ? [{ key: "no_time_hour", text: `Attacks with a date but no time start at 12:00`, value: 12, options: [7, 9, 12, 18] }] : []),
      ...(sevCounts && attacks.some((a) => a.end_inferred) ? [{ key: "end_fill_hours", text: model.inferences.find((i) => i.includes("no end time")) ?? "Missing end times filled in", value: null, options: null }] : []),
      ...(decisions.some((d) => d.concept === "food" && d.class === "trigger") ? [{ key: "foods", text: "Foods you listed as triggers are tracked as food", value: "food", options: ["food", "trigger", "skip"] }] : []),
      ...regimens.map((r) => ({ key: `regimen|${r.name}`, text: r.from === "mention" ? `"${r.name}" is added as a treatment from ${r.start_date}` : `${r.name} on ${r.days} days is a daily treatment from ${r.start_date}`, value: "add", options: ["add", "skip"] })),
      ...Object.entries(attacks.reduce((acc, a) => { for (const m of a.meds) if (m.dose_value === null) { const d = decisions.find((x) => x.class === "medicine" && x.phrase.toLowerCase() === m.name.toLowerCase()); const label = d?.pool_label ?? d?.new_item?.label ?? m.name; if (d?.tier !== "ambiguous") acc[label] = (acc[label] ?? 0) + 1; } return acc; }, {} as Record<string, number>)).sort((a, b) => b[1] - a[1]).map(([label, n]) => ({ key: `dose|${label}`, text: `${label}: no dose in your file (${n} ${n === 1 ? "entry" : "entries"}). Type your usual dose to fill them in, or leave it empty.`, value: null, options: null })),
      ...questions.map((q) => ({ key: q.key, text: `"${q.phrase}" is kept as you wrote it (${q.count} ${q.count === 1 ? "day" : "days"}${q.siblings.length ? `, together with ${q.siblings.map((k) => `"${k.split("|")[1]}"`).join(" and ")}` : ""})`, value: null, options: q.class === "medicine" ? uniq(decisions.filter((d) => d.concept === "medicine" && d.pool_label).map((d) => d.pool_label!)) : uniq(decisions.filter((d) => d.concept === q.class && d.pool_label).map((d) => d.pool_label!)), why: q.why })),
    ],
    new_items: decisions.filter((d) => d.new_item && d.tier !== "ambiguous" && d.concept !== "food").map((d) => ({ class: d.concept, phrase: d.phrase, label: d.new_item!.label, category: d.new_item!.category, icon_key: d.new_item!.icon_key, count: d.count, tier: d.tier, reasoning: d.reasoning })),
    likely: decisions.filter((d) => d.tier === "likely" && d.pool_label).map((d) => ({ class: d.concept, phrase: d.phrase, label: d.pool_label, count: d.count, reasoning: d.reasoning })),
    certain: decisions.filter((d) => d.tier === "certain" && d.pool_label).map((d) => ({ class: d.concept, phrase: d.phrase, label: d.pool_label, count: d.count })),
    foods: decisions.filter((d) => d.concept === "food").map((d) => ({ phrase: d.phrase, count: d.count })),
    pain_locations: decisions.filter((d) => d.class === "pain_location").map((d) => ({ phrase: d.phrase, ids: d.pain_ids ?? [], count: d.count })),
    dropped: decisions.filter((d) => d.concept === "drop").map((d) => ({ class: d.class, phrase: d.phrase, count: d.count, reasoning: d.reasoning })),
    regimen_proposals: regimens,
    engine_use: engineUseRecommendations(model),
    attacks: attacks.map((a) => ({ ref: a.ref, start_local: a.start_local, end_local: a.end_local, end_inferred: a.end_inferred, severity: a.intensity.value, types: a.types, symptoms: a.symptoms, symptom_severity: a.symptom_severity, prodromes: a.prodromes, postdromes: a.postdromes, triggers: a.triggers, foods: a.foods, meds: a.meds, reliefs: a.reliefs, activities: a.activities, missed: a.missed_detail.length ? a.missed_detail : a.missed.map((n) => ({ name: n, reasons: [], anticipated: false })), location: a.location, pain_locations: a.pain_locations, aura: a.aura, times: a.times, notes: a.notes, provenance: a.provenance })),
    days: model.days.filter((d) => Object.keys(d.metrics).length || Object.keys(d.times).length || d.period || d.side_effects.length || d.missed.length || d.regimens.length || d.foods.length || d.daily_meds.length).map((d) => ({ date: d.date, metrics: d.metrics, times: d.times, period: d.period, foods: d.foods.map((f) => f.name), daily_meds: d.daily_meds.map((m) => m.name), side_effects: d.side_effects, missed: d.missed, regimens: d.regimens })),
    cost: { usd: Math.round(spend.usd() * 1000) / 1000, tokens: spend.tokens(), calls: spend.usages.length },
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// Edits from the preview screen (deterministic, applied before the write)
// ─────────────────────────────────────────────────────────────────────────────

interface Assumptions { timezone?: string; end_fill_hours?: number; no_time_hour?: number }
type AttackEdit = Partial<Pick<Attack, "start_local" | "end_local" | "symptoms" | "prodromes" | "postdromes" | "triggers" | "foods" | "reliefs" | "activities" | "missed" | "location" | "meds" | "pain_locations" | "aura" | "notes" | "symptom_severity" | "times">> & { severity?: number | null; missed_detail?: Missed[] };

const LOCAL_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/;
const clean = (v: unknown): string[] => Array.isArray(v) ? v.map((x) => norm(String(x))).filter(Boolean) : [];

export function applyEdits(model: Model, assumptions: Assumptions, excludeRefs: string[], edits: Record<string, AttackEdit>): string[] {
  const log: string[] = [];
  if (assumptions.timezone) { model.timezone = assumptions.timezone; log.push(`timezone set to ${assumptions.timezone}`); }
  if (typeof assumptions.no_time_hour === "number" && assumptions.no_time_hour >= 0 && assumptions.no_time_hour <= 23) {
    let n = 0;
    for (const a of model.attacks) if (a.start_local.endsWith("T12:00:00") && a.provenance.row !== null) { const shift = (assumptions.no_time_hour - 12) * 60; a.start_local = addMinutesLocal(a.start_local, shift); if (a.end_local && a.end_inferred) a.end_local = addMinutesLocal(a.end_local, shift); n++; }
    log.push(`${n} attacks without a time moved to ${pad2(assumptions.no_time_hour)}:00`);
  }
  if (typeof assumptions.end_fill_hours === "number" && assumptions.end_fill_hours > 0 && assumptions.end_fill_hours <= 72) {
    let n = 0;
    for (const a of model.attacks) if (a.end_inferred) { let end = addMinutesLocal(a.start_local, Math.round(assumptions.end_fill_hours * 60)); if (dateOf(end) !== dateOf(a.start_local)) end = `${dateOf(a.start_local)}T23:59:00`; a.end_local = end; n++; }
    log.push(`${n} inferred end times set to start plus ${assumptions.end_fill_hours} hours`);
  }
  const ex = new Set(excludeRefs);
  if (ex.size) { const before = model.attacks.length; model.attacks = model.attacks.filter((a) => !ex.has(a.ref)); log.push(`${before - model.attacks.length} attacks removed by the person`); }
  let edited = 0;
  for (const a of model.attacks) {
    const e = edits[a.ref]; if (!e) continue; edited++;
    if (typeof e.start_local === "string" && LOCAL_RE.test(e.start_local)) a.start_local = e.start_local.slice(0, 16) + ":00";
    if (e.end_local === null) { a.end_local = null; a.end_inferred = true; }
    else if (typeof e.end_local === "string" && LOCAL_RE.test(e.end_local)) { a.end_local = e.end_local.slice(0, 16) + ":00"; a.end_inferred = false; }
    if (e.severity === null) a.intensity.value = null; else if (typeof e.severity === "number") a.intensity.value = Math.min(10, Math.max(1, Math.round(e.severity)));
    for (const k of ["symptoms", "prodromes", "postdromes", "triggers", "foods", "activities", "pain_locations"] as const) if (e[k] !== undefined) (a as unknown as Record<string, string[]>)[k] = clean(e[k]);
    if (e.pain_locations !== undefined) a.canonical_pain = clean(e.pain_locations).every((x) => PAIN_IDS.includes(x));
    if (e.reliefs !== undefined) a.reliefs = (e.reliefs as unknown[]).map((r) => typeof r === "string" ? { name: norm(r), start_local: null, end_local: null, effect: null, side_effect: null } : r as Relief).filter((r) => r?.name);
    if (e.missed !== undefined || e.missed_detail !== undefined) { const src = (e.missed_detail ?? e.missed ?? []) as unknown[]; a.missed_detail = src.map((m) => typeof m === "string" ? { name: norm(m), reasons: [], anticipated: false } : { name: norm(String((m as Missed).name)), reasons: clean((m as Missed).reasons), anticipated: !!(m as Missed).anticipated }).filter((m) => m.name); a.missed = a.missed_detail.map((m) => m.name); }
    if (e.symptom_severity && typeof e.symptom_severity === "object") a.symptom_severity = Object.fromEntries(Object.entries(e.symptom_severity).filter(([, v]) => ["MILD", "MODERATE", "SEVERE"].includes(String(v))).map(([k, v]) => [k.toLowerCase(), String(v)]));
    if (e.times && typeof e.times === "object") a.times = Object.fromEntries(Object.entries(e.times).filter(([, v]) => typeof v === "string" && LOCAL_RE.test(v)).map(([k, v]) => [k, String(v).slice(0, 16) + ":00"]));
    if (e.meds !== undefined) a.meds = (e.meds as Partial<Med>[]).filter((m) => m?.name).map((m) => ({ name: norm(String(m.name)), dose_value: typeof m.dose_value === "number" ? m.dose_value : null, dose_unit: m.dose_unit ?? null, taken_local: typeof m.taken_local === "string" && LOCAL_RE.test(m.taken_local) ? m.taken_local.slice(0, 16) + ":00" : null, effect: m.effect ?? null, side_effect: m.side_effect ?? null }));
    if (e.location !== undefined) a.location = e.location ? norm(String(e.location)) : null;
    if (e.aura !== undefined && e.aura) a.aura = { present: !!e.aura.present, zones: clean(e.aura.zones).filter((z) => /^(left|right)_(top|center|bottom)_(left|center|right)$/.test(z)), duration_minutes: typeof e.aura.duration_minutes === "number" ? e.aura.duration_minutes : null };
    if (e.notes !== undefined) a.notes = e.notes ? String(e.notes) : null;
    if (a.end_local && localMinutesBetween(a.start_local, a.end_local) <= 0) { a.end_local = addMinutesLocal(a.start_local, 60); a.end_inferred = true; }
    if (!a.end_local) { a.end_local = addMinutesLocal(a.start_local, 8 * 60); if (dateOf(a.end_local) !== dateOf(a.start_local)) a.end_local = `${dateOf(a.start_local)}T23:59:00`; a.end_inferred = true; }
  }
  if (edited) log.push(`${edited} attacks edited by the person`);
  // A label the person typed that the linker never saw is written in their words: resolveLabel already does that.
  return log;
}

// ─────────────────────────────────────────────────────────────────────────────
// Writer
// ─────────────────────────────────────────────────────────────────────────────

type Manifest = Record<string, string[]>;

export async function loadPools(admin: SupabaseClient, userId: string): Promise<Pools> {
  const pools: Pools = {};
  for (const [cls, table] of Object.entries(POOL_TABLE)) {
    if (cls === "postdrome") continue;
    const cols = table === "user_migraines_pool" ? "id,label" : table === "user_medicines" ? "id,label,category" : "id,label,category,icon_key";
    const { data, error } = await admin.from(table).select(cols).eq("user_id", userId);
    if (error) throw new Error(`${table}: ${error.message}`);
    pools[cls] = ((data ?? []) as unknown as Record<string, unknown>[]).map((r) => ({ label: String(r.label), category: (r.category as string) ?? null, icon_key: (r.icon_key as string) ?? null, id: r.id as string })) as Pools[string];
  }
  return pools;
}

function lbl0(decisions: LinkDecision[], answers: Record<string, string | null>, cls: SourceClass, phrase: string) { const r = resolveLabel(decisions, answers, cls, phrase); return r && r.concept !== "drop" ? r : null; }
function resolveLabel(decisions: LinkDecision[], answers: Record<string, string | null>, cls: SourceClass, phrase: string): { label: string; concept: Concept; isNew: boolean; category: string | null; icon_key: string | null } | null {
  const d = decisions.find((x) => x.class === cls && x.phrase.toLowerCase() === lower(phrase));
  if (!d) return { label: norm(phrase), concept: cls, isNew: true, category: null, icon_key: null };
  if (d.concept === "drop") return null;
  const ans = answers[`${d.class}|${d.phrase}`];
  if (ans) return { label: ans, concept: d.concept === "regimen" ? "medicine" : d.concept, isNew: true, category: null, icon_key: null };
  if (d.pool_label) return { label: d.pool_label, concept: d.concept === "regimen" ? "medicine" : d.concept, isNew: false, category: null, icon_key: null };
  if (d.new_item) return { label: d.new_item.label, concept: d.concept === "regimen" ? "medicine" : d.concept, isNew: true, category: d.new_item.category, icon_key: d.new_item.icon_key };
  return { label: norm(d.phrase), concept: d.concept === "regimen" ? "medicine" : d.concept, isNew: true, category: null, icon_key: null }; // ambiguous, unanswered: their words
}

async function insertReturningIds(admin: SupabaseClient, table: string, rows: Record<string, unknown>[], manifest: Manifest): Promise<Record<string, unknown>[]> {
  const out: Record<string, unknown>[] = [];
  for (let i = 0; i < rows.length; i += 200) {
    const { data, error } = await admin.from(table).insert(rows.slice(i, i + 200)).select("id");
    if (error) throw new Error(`${table}: ${error.message}`);
    for (const r of data ?? []) { manifest[table] ??= []; manifest[table].push(String(r.id)); out.push(r); }
  }
  return out;
}

export async function commit(admin: SupabaseClient, userId: string, model: Model, decisions: LinkDecision[], answers: Record<string, string | null>, acceptRegimens: string[], regimens: RegimenProposal[], cutoff: string | null, importId: string, engineUse: Record<string, boolean> | null = null) {
  const manifest: Manifest = {};
  try {
    return await commitInner(admin, userId, model, decisions, answers, acceptRegimens, regimens, cutoff, importId, manifest, engineUse);
  } catch (e) {
    // Nothing half-written survives: every row inserted so far is in the manifest.
    let rolledBack: Record<string, number> = {};
    try { rolledBack = await undo(admin, userId, manifest); } catch (u) { throw new Error(`${String((e as Error).message ?? e)} (and rollback failed: ${String((u as Error).message ?? u)})`); }
    throw new Error(`${String((e as Error).message ?? e)} (rolled back ${Object.values(rolledBack).reduce((a, b) => a + b, 0)} rows)`);
  }
}

async function commitInner(admin: SupabaseClient, userId: string, model: Model, decisions: LinkDecision[], answers: Record<string, string | null>, acceptRegimens: string[], regimens: RegimenProposal[], cutoff: string | null, importId: string, manifest: Manifest, engineUseIn: Record<string, boolean> | null) {
  const tz = model.timezone; const marker = ""; // provenance = source column + import_batches.manifest, never the person's note
  // Which categories feed insights. Defaults = the recommendations; the person can flip any.
  const engineUse: Record<string, boolean> = Object.fromEntries(engineUseRecommendations(model).map((r) => [r.key, r.recommended]));
  for (const [k, v] of Object.entries(engineUseIn ?? {})) if (typeof v === "boolean") engineUse[k] = v;
  const src = (key: string) => (engineUse[key] ? "import_scored" : "import");
  const pools = await loadPools(admin, userId);
  // Usual doses typed on the assumptions screen fill the entries that had none. Never invented: only what the person typed.
  const doses = (model as unknown as { __doses?: Record<string, string> }).__doses ?? {};
  for (const [label, txt] of Object.entries(doses)) { const pd = parseDose(String(txt)); if (pd.dose_value === null) continue; for (const a of model.attacks) for (const m of a.meds) { const r = lbl0(decisions, answers, "medicine", m.name); if (r && r.label === label && m.dose_value === null) { m.dose_value = pd.dose_value; m.dose_unit = pd.dose_unit ?? "mg"; } } for (const d of model.days) for (const m of d.daily_meds) { const r = lbl0(decisions, answers, "medicine", m.name); if (r && r.label === label && m.dose_value === null) { m.dose_value = pd.dose_value; m.dose_unit = pd.dose_unit ?? "mg"; } } }
  // An answer settles its siblings too.
  for (const d of decisions) { const a = answers[`${d.class}|${d.phrase}`]; if (a === undefined) continue; for (const k of d.siblings ?? []) if (answers[k] === undefined) answers[k] = a; }
  // A phrase that meant two things: add the extra meanings to every attack that carries the phrase, as certain decisions.
  const LIST_OF: Record<string, keyof Attack> = { trigger: "triggers", symptom: "symptoms", prodrome: "prodromes", postdrome: "postdromes", activity: "activities", missed: "missed", food: "foods" };
  for (const d of decisions.filter((x) => x.also?.length)) {
    for (const extra of d.also!) {
      const label = extra.pool_label ?? extra.new_item?.label; if (!label) continue;
      const listKey = LIST_OF[extra.concept]; 
      const synthetic: LinkDecision = { class: (extra.concept === "food" ? "food" : extra.concept) as SourceClass, phrase: label, concept: extra.concept, tier: "certain", pool_label: extra.pool_label, new_item: extra.new_item, reasoning: `part of "${d.phrase}"`, count: d.count };
      if (!decisions.some((x) => x.class === synthetic.class && x.phrase.toLowerCase() === label.toLowerCase())) decisions.push(synthetic);
      const srcList = LIST_OF[d.class] ?? (d.class === "medicine" ? "meds" : d.class === "relief" ? "reliefs" : "triggers");
      for (const a of model.attacks) {
        const has = srcList === "meds" ? a.meds.some((m) => lower(m.name) === lower(d.phrase)) : srcList === "reliefs" ? a.reliefs.some((r) => lower(r.name) === lower(d.phrase)) : (a[srcList] as string[]).some((x) => lower(x) === lower(d.phrase)) || lower(a.location ?? "") === lower(d.phrase);
        if (!has) continue;
        if (extra.concept === "location") a.location = a.location ?? label;
        else if (extra.concept === "relief") { if (!a.reliefs.some((r) => lower(r.name) === lower(label))) a.reliefs.push({ name: label, start_local: null, end_local: null, effect: null }); }
        else if (extra.concept === "medicine") { if (!a.meds.some((m) => lower(m.name) === lower(label))) a.meds.push({ name: label, dose_value: null, dose_unit: null, taken_local: null, effect: null, side_effect: null }); }
        else if (listKey) { const arr = a[listKey] as string[]; if (!arr.some((x) => lower(x) === lower(label))) arr.push(label); }
      }
    }
  }
  const poolId = (cls: string, label: string) => (pools[cls] as ({ label: string; id?: string })[] | undefined)?.find((p) => p.label === label)?.id ?? null;

  // 1. Pool items. Collect every resolved label per class, create missing ones.
  const wanted: Record<string, Map<string, { category: string | null; icon_key: string | null; uses: number }>> = {};
  const want = (cls: SourceClass, phrase: string) => {
    const r = resolveLabel(decisions, answers, cls, phrase); if (!r || r.concept === "food" || r.concept === "pain_location" || r.concept === "drop") return r;
    const pc = r.concept === "postdrome" ? "symptom" : r.concept;
    wanted[pc] ??= new Map(); const e = wanted[pc].get(r.label) ?? { category: r.category, icon_key: r.icon_key, uses: 0 }; e.uses++; wanted[pc].set(r.label, e); return r;
  };
  const attacks = cutoff ? model.attacks.filter((a) => dateOf(a.start_local) < cutoff) : model.attacks;
  const days = cutoff ? model.days.filter((d) => d.date < cutoff) : model.days;
  for (const a of attacks) {
    a.types.forEach((t) => want("type", t)); a.symptoms.forEach((t) => want("symptom", t)); a.prodromes.forEach((t) => want("prodrome", t)); a.postdromes.forEach((t) => want("postdrome", t));
    a.triggers.forEach((t) => want("trigger", t)); a.meds.forEach((m) => want("medicine", m.name)); a.reliefs.forEach((r) => want("relief", r.name)); a.activities.forEach((t) => want("activity", t)); a.missed.forEach((t) => want("missed", t)); if (a.location) want("location", a.location);
  }
  for (const d of days) { d.daily_meds.forEach((m) => want("medicine", m.name)); d.triggers.forEach((t) => want("trigger", t)); }
  for (const [cls, m] of Object.entries(wanted)) {
    const table = POOL_TABLE[cls]; if (!table) continue;
    const missing = Array.from(m.entries()).filter(([label]) => !poolId(cls, label));
    if (missing.length) {
      const rows = missing.map(([label, e]) => table === "user_migraines_pool" ? { user_id: userId, label } : table === "user_medicines" ? { user_id: userId, label, category: e.category, dose_unit: "mg" } : { user_id: userId, label, category: e.category, icon_key: e.icon_key });
      const { error } = await admin.from(table).upsert(rows, { onConflict: "user_id,label", ignoreDuplicates: true });
      if (error) throw new Error(`${table}: ${error.message}`);
      const { data } = await admin.from(table).select("id,label").eq("user_id", userId).in("label", missing.map(([l]) => l));
      for (const r of data ?? []) { (pools[cls] as ({ label: string; id?: string })[]).push({ label: String(r.label), id: String(r.id) }); manifest[table] ??= []; manifest[table].push(String(r.id)); }
    }
    // 2. Favourites for everything used, ordered by use count, after existing ones.
    const [prefTable, fk] = PREF_TABLE[cls] ?? [];
    if (!prefTable) continue;
    const { data: existingRaw } = await admin.from(prefTable).select(`${fk},position`).eq("user_id", userId);
    const existing = (existingRaw ?? []) as unknown as Record<string, unknown>[];
    const have = new Set(existing.map((r) => String(r[fk])));
    let pos = existing.reduce((mx: number, r) => Math.max(mx, Number(r.position ?? 0)), -1) + 1;
    const prefRows = Array.from(m.entries()).sort((a, b) => b[1].uses - a[1].uses).map(([label]) => poolId(cls, label)).filter((id): id is string => !!id && !have.has(id)).map((id) => ({ user_id: userId, [fk]: id, status: "frequent", position: pos++ }));
    if (prefRows.length) await insertReturningIds(admin, prefTable, prefRows, manifest);
  }

  // 3. Attacks and children.
  const lbl = (cls: SourceClass, phrase: string) => { const r = resolveLabel(decisions, answers, cls, phrase); return r && r.concept !== "drop" ? r : null; };
  const regimenNames = new Set(acceptRegimens);
  let painPoints = 0, auraRows = 0;
  for (const a of attacks) {
    const typeLabels = uniq([...a.types.map((t) => lbl("type", t)).filter(Boolean).map((r) => r!.label), ...a.symptoms.map((t) => lbl("symptom", t)).filter((r) => r && r.concept !== "prodrome" && r.concept !== "food").map((r) => r!.label)]);
    if (a.aura.present && (pools["symptom"] ?? []).some((p) => p.label === "Aura") && !typeLabels.includes("Aura")) typeLabels.push("Aura");
    const start = localToUtcIso(a.start_local, tz), end = localToUtcIso(a.end_local!, tz);
    const painIds = a.canonical_pain ? a.pain_locations : uniq(a.pain_locations.flatMap((w) => decisions.find((x) => x.class === "pain_location" && x.phrase.toLowerCase() === lower(w))?.pain_ids ?? []));
    // Journal-only tags (engine unticked) become note text instead of rows.
    const taggedTrig = engineUse["triggers"] ? [] : uniq([...a.triggers.map((t) => lbl("trigger", t)).filter((r) => r && r.concept === "trigger").map((r) => r!.label), ...a.symptoms.map((t) => lbl("symptom", t)).filter((r) => r?.concept === "trigger").map((r) => r!.label)]);
    const taggedProd = engineUse["triggers"] ? [] : uniq([...a.prodromes.map((t) => lbl("prodrome", t)), ...a.symptoms.map((t) => lbl("symptom", t)).filter((r) => r?.concept === "prodrome")].filter(Boolean).map((r) => r!.label));
    const taggedFood = Array.from(new Map([...a.foods, ...a.triggers.filter((t) => lbl("trigger", t)?.concept === "food")].map(norm).map((f) => [f.toLowerCase(), f])).values());
    const taggedActs = engineUse["places_activities"] ? [] : uniq(a.activities.map((t) => lbl("activity", t)).filter((r) => r?.concept === "activity").map((r) => r!.label));
    const taggedMissed = engineUse["places_activities"] ? [] : uniq(a.missed.map((t) => lbl("missed", t)).filter((r) => r?.concept === "missed").map((r) => r!.label));
    const tagNote = [taggedTrig.length ? `Triggers you tagged: ${taggedTrig.join(", ")}.` : null, taggedProd.length ? `Warning signs you tagged: ${taggedProd.join(", ")}.` : null, taggedFood.length && !engineUse["foods"] ? `Foods: ${taggedFood.join(", ")}.` : null, taggedActs.length ? `Activities: ${taggedActs.join(", ")}.` : null, taggedMissed.length ? `Missed: ${taggedMissed.join(", ")}.` : null].filter(Boolean).join(" ");
    const [mig] = await insertReturningIds(admin, "migraines", [{
      user_id: userId, type: typeLabels.length ? typeLabels.join(", ") : "Migraine", severity: a.intensity.value, start_at: start, ended_at: end,
      pain_locations: painIds.length ? painIds : null, aura_locations: a.aura.zones.length ? a.aura.zones : null, aura_duration_minutes: a.aura.duration_minutes,
      notes: [a.notes, tagNote].filter(Boolean).join(" ") || null, source: src("attacks"),
    }], manifest);
    const migId = String(mig.id);
    if (painIds.length && a.intensity.value !== null) { painPoints += painIds.length; await insertReturningIds(admin, "migraine_pain_points", painIds.map((loc) => ({ user_id: userId, migraine_id: migId, location_id: loc, severity: a.intensity.value, start_at: start })), manifest); }
    if (a.aura.zones.length) { auraRows += a.aura.zones.length; await insertReturningIds(admin, "migraine_aura_zones", a.aura.zones.map((z) => ({ user_id: userId, migraine_id: migId, zone: z, start_at: start, duration_minutes: a.aura.duration_minutes })), manifest); }
    for (const [key, sev] of Object.entries(a.symptom_severity)) { const r = lbl("symptom", key); if (r && typeLabels.includes(r.label)) await admin.from("symptoms").update({ severity: sev }).eq("migraine_id", migId).eq("type", r.label); }
    const post = uniq(a.postdromes.map((t) => lbl("postdrome", t)).filter(Boolean).map((r) => r!.label));
    if (post.length) await insertReturningIds(admin, "symptoms", post.map((t) => ({ user_id: userId, migraine_id: migId, type: t, phase: "postdrome" })), manifest);
    const trig = uniq([...a.triggers, ...a.symptoms.filter((s) => lbl("symptom", s)?.concept === "trigger")].map((t) => lbl(t === undefined ? "trigger" : "trigger", t) ?? lbl("symptom", t)).filter((r) => r && r.concept === "trigger").map((r) => r!.label));
    const whenOf = (cls: string, srcNames: string[]) => { for (const n of srcNames) { const t = a.times[`${cls}|${n.toLowerCase()}`]; if (t) return localToUtcIso(t, tz); } return start; };
    const srcOf = (cls: SourceClass, list: string[], label: string) => list.filter((n) => lbl(cls, n)?.label === label);
    const periodDay = days.some((d) => d.date === dateOf(a.start_local) && (d.period === "start" || d.period === "flow"));
    const trigRows = trig.filter((t) => !(periodDay && t.toLowerCase() === "menstruation")); // the period row already says it
    if (trigRows.length && engineUse["triggers"]) await insertReturningIds(admin, "triggers", trigRows.map((t) => ({ user_id: userId, migraine_id: migId, type: t, start_at: whenOf("trigger", srcOf("trigger", a.triggers, t)), source: src("triggers"), active: true })), manifest);
    const prod = uniq([...a.prodromes.map((t) => lbl("prodrome", t)), ...a.symptoms.map((t) => lbl("symptom", t)).filter((r) => r?.concept === "prodrome")].filter(Boolean).map((r) => r!.label));
    if (prod.length && engineUse["triggers"]) await insertReturningIds(admin, "prodromes", prod.map((t) => ({ user_id: userId, migraine_id: migId, type: t, start_at: whenOf("prodrome", srcOf("prodrome", a.prodromes, t)), source: src("triggers"), active: true })), manifest);
    const medRows = a.meds.map((m) => ({ m, r: lbl("medicine", m.name) })).filter((x) => x.r && x.r.concept === "medicine" && !regimenNames.has(x.r.label)).map(({ m, r }) => ({
      user_id: userId, migraine_id: migId, name: r!.label, start_at: m.taken_local ? localToUtcIso(m.taken_local, tz) : start,
      dose_value: m.dose_value, dose_unit: m.dose_value ? (m.dose_unit ?? "mg") : null, amount: m.dose_value ? `${m.dose_value}${m.dose_unit === "amount" ? "" : " " + (m.dose_unit ?? "mg")}`.trim() : null,
      relief_scale: effectScale(m.effect), side_effect_scale: sideEffectScale(m.side_effect), side_effect_notes: m.side_effect ?? null, category: (pools["medicine"] ?? []).find((p) => p.label === r!.label)?.category ?? null, source: src("medicines"),
    }));
    if (medRows.length) await insertReturningIds(admin, "medicines", medRows, manifest);
    const relRows = a.reliefs.map((rl) => ({ rl, r: lbl("relief", rl.name) })).filter((x) => x.r && x.r.concept === "relief").map(({ rl, r }) => ({
      user_id: userId, migraine_id: migId, type: r!.label, start_at: rl.start_local ? localToUtcIso(rl.start_local, tz) : start, end_at: rl.end_local ? localToUtcIso(rl.end_local, tz) : null,
      relief_scale: effectScale(rl.effect), side_effect_scale: sideEffectScale(rl.side_effect ?? null), side_effect_notes: rl.side_effect ?? null, category: (pools["relief"] ?? []).find((p) => p.label === r!.label)?.category ?? null, source: src("reliefs"),
    }));
    if (relRows.length) await insertReturningIds(admin, "reliefs", relRows, manifest);
    const loc = a.location ? lbl("location", a.location) : null;
    if (loc && loc.concept === "location") await insertReturningIds(admin, "locations", [{ user_id: userId, migraine_id: migId, type: loc.label, start_at: start, source: src("places_activities") }], manifest);
    const acts = uniq(a.activities.map((t) => lbl("activity", t)).filter((r) => r?.concept === "activity").map((r) => r!.label));
    if (acts.length && engineUse["places_activities"]) await insertReturningIds(admin, "activities", acts.map((t) => { const names = srcOf("activity", a.activities, t); const st = whenOf("activity", names); const enLocal = names.map((n) => a.times[`activity_end|${n.toLowerCase()}`]).find(Boolean); return { user_id: userId, migraine_id: migId, type: t, start_at: st, end_at: enLocal ? localToUtcIso(enLocal, tz) : null, source: src("places_activities") }; }), manifest);
    const miss = uniq(a.missed.map((t) => lbl("missed", t)).filter((r) => r?.concept === "missed").map((r) => r!.label));
    if (miss.length && engineUse["places_activities"]) await insertReturningIds(admin, "missed_activities", miss.map((t) => {
      const det = a.missed_detail.filter((m) => lbl("missed", m.name)?.label === t);
      const reasons = uniq(det.flatMap((m) => m.reasons).map((rs) => lbl("trigger", rs)?.label ?? lbl("prodrome", rs)?.label ?? rs));
      return { user_id: userId, migraine_id: migId, type: t, start_at: start, source: src("places_activities"), anticipated: det.some((m) => m.anticipated), reason_labels: reasons.length ? reasons : null };
    }), manifest);
    const foodsRaw = [...a.foods, ...a.triggers.filter((t) => lbl("trigger", t)?.concept === "food"), ...a.symptoms.filter((t) => lbl("symptom", t)?.concept === "food")];
    const foods = Array.from(new Map(foodsRaw.map((f) => [lower(f), norm(f)])).values());
    // Foods the person tagged as triggers stay visible as the trigger chip they logged (journal only, never scored as a manual trigger).
    const foodChips = a.triggers.filter((t) => lbl("trigger", t)?.concept === "food").map(norm);
    if (foodChips.length && engineUse["triggers"]) await insertReturningIds(admin, "triggers", uniq(foodChips).map((t) => ({ user_id: userId, migraine_id: migId, type: t, start_at: start, source: "import", active: true })), manifest);
    if (foods.length && engineUse["foods"]) await insertReturningIds(admin, "nutrition_records", foods.map((f, i) => { const tl = a.times[`food|${f.toLowerCase()}`] ?? addMinutesLocal(a.start_local, -240); return { user_id: userId, date: dateOf(tl), timestamp: localToUtcIso(tl, tz), food_name: f, meal_type: "unknown", source: "import", health_connect_id: `import:${importId.slice(0, 8)}:${a.ref}:${i}` }; }), manifest);
  }

  // 4. Days: metrics, foods, periods, daily meds not covered by an accepted regimen.
  const METRIC_TABLE: Record<string, [string, string]> = { sleep_hours: ["sleep_duration_daily", "value_hours"], hydration_ml: ["hydration_daily", "value_ml"], weight_kg: ["weight_daily", "value_kg"], steps: ["steps_daily", "value_count"], sleep_disturbances: ["sleep_disturbances_daily", "value_count"], mindfulness_minutes: ["mindfulness_daily", "duration_minutes"], body_fat_pct: ["body_fat_daily", "value_pct"], blood_glucose: ["blood_glucose_daily", "value_mmol_l"] };
  const periodStarts: { date: string; id: string }[] = [];
  for (const d of days) {
    for (const [k, v] of Object.entries(d.metrics)) { const t = METRIC_TABLE[k]; if (!t || !engineUse[`metric:${k}`]) continue; await insertReturningIds(admin, t[0], [{ user_id: userId, date: d.date, [t[1]]: v, source: "import" }], manifest); }
    if (d.metrics.bp_systolic && engineUse["metric:bp_systolic"]) await insertReturningIds(admin, "blood_pressure_daily", [{ user_id: userId, date: d.date, systolic_mmhg: d.metrics.bp_systolic, diastolic_mmhg: d.metrics.bp_diastolic ?? null, source: "import" }], manifest);
    if (d.times.bedtime && engineUse["metric:bedtime"]) await insertReturningIds(admin, "fell_asleep_time_daily", [{ user_id: userId, date: d.date, value_at: localToUtcIso(d.times.bedtime, tz), source: "import" }], manifest);
    if (d.times.wake_time && engineUse["metric:wake_time"]) await insertReturningIds(admin, "woke_up_time_daily", [{ user_id: userId, date: d.date, value_at: localToUtcIso(d.times.wake_time, tz), source: "import" }], manifest);
    for (const se of engineUse["treatments"] === false ? [] : d.side_effects) {
      const labels = uniq(se.symptoms.map((x) => lbl("side_effect", x)?.label ?? x));
      await insertReturningIds(admin, "treatment_side_effect_logs", [{ user_id: userId, log_date: d.date, selected_symptoms: labels, notes: [se.regimen ? `(${se.regimen})` : null, se.notes].filter(Boolean).join(" ") || null, source: "manual" }], manifest);
    }
    const dmiss = d.missed.map((m) => ({ m, r: lbl("missed", m.name) })).filter((x) => x.r?.concept === "missed");
    if (dmiss.length && engineUse["places_activities"]) await insertReturningIds(admin, "missed_activities", dmiss.map(({ m, r }) => ({ user_id: userId, type: r!.label, start_at: localToUtcIso(`${d.date}T12:00:00`, tz), source: src("places_activities"), anticipated: m.anticipated, reason_labels: m.reasons.length ? m.reasons.map((rs) => lbl("trigger", rs)?.label ?? lbl("prodrome", rs)?.label ?? rs) : null })), manifest);
    const dprod = uniq(d.prodromes.map((t) => lbl("prodrome", t)).filter((r) => r?.concept === "prodrome").map((r) => r!.label));
    if (dprod.length && engineUse["triggers"]) await insertReturningIds(admin, "prodromes", dprod.map((t) => ({ user_id: userId, type: t, start_at: localToUtcIso(`${d.date}T12:00:00`, tz), source: src("triggers"), active: true })), manifest);
    if (d.foods.length && engineUse["foods"]) await insertReturningIds(admin, "nutrition_records", d.foods.map((f, i) => ({ user_id: userId, date: d.date, timestamp: localToUtcIso(f.time_local ?? `${d.date}T12:00:00`, tz), food_name: f.name, meal_type: "unknown", source: "import", health_connect_id: `import:${importId.slice(0, 8)}:${d.date}:${i}` })), manifest);
    if (d.period === "start" && engineUse["period"] !== false) { const [row] = await insertReturningIds(admin, "triggers", [{ user_id: userId, type: "Menstruation", start_at: `${d.date}T09:00:00Z`, source: "manual", source_measure_id: `import:${importId.slice(0, 8)}:${d.date}` }], manifest); periodStarts.push({ date: d.date, id: String(row.id) }); }
    if (d.period === "end" && engineUse["period"] !== false) { const ps = periodStarts.filter((x) => x.date <= d.date && localMinutesBetween(`${x.date}T00:00:00`, `${d.date}T00:00:00`) <= 12 * 1440).pop(); if (ps) await admin.from("triggers").update({ notes: `end_date=${d.date}` }).eq("id", ps.id); }
    const dm = d.daily_meds.map((m) => ({ m, r: lbl("medicine", m.name) })).filter((x) => x.r && x.r.concept === "medicine" && !regimenNames.has(x.r.label));
    if (dm.length) await insertReturningIds(admin, "medicines", dm.map(({ m, r }) => ({ user_id: userId, name: r!.label, start_at: localToUtcIso(m.taken_local ?? `${d.date}T09:00:00`, tz), dose_value: m.dose_value, dose_unit: m.dose_value ? (m.dose_unit ?? "mg") : null, relief_scale: "NONE", side_effect_scale: "NONE", source: src("medicines") })), manifest);
    const dt = uniq(d.triggers.map((t) => lbl("trigger", t)).filter((r) => r?.concept === "trigger").map((r) => r!.label));
    if (dt.length && engineUse["triggers"]) await insertReturningIds(admin, "triggers", dt.map((t) => ({ user_id: userId, type: t, start_at: localToUtcIso(`${d.date}T12:00:00`, tz), source: src("triggers"), active: true })), manifest);
  }

  // 5. Accepted regimens.
  const regRows = (engineUse["treatments"] === false ? [] : regimens.filter((r) => regimenNames.has(r.name))).map((r) => ({ user_id: userId, kind: r.kind ?? "drug", name: r.name, dose_value: r.dose_value, dose_unit: r.dose_value ? (r.dose_unit ?? "mg") : null, frequency: r.frequency ?? "daily", start_date: r.start_date, stop_date: r.stop_date, notes: r.from === "mention" ? "Mentioned in your imported diary" : `From ${r.days} logged days in your imported diary` }));
  if (regRows.length) await insertReturningIds(admin, "treatment_regimens", regRows, manifest);

  // 6. Verify.
  const migIds = manifest["migraines"] ?? [];
  const { count: openCount } = await admin.from("migraines").select("id", { count: "exact", head: true }).in("id", migIds).is("ended_at", null);
  const { count: symCount } = await admin.from("symptoms").select("id", { count: "exact", head: true }).in("migraine_id", migIds).eq("phase", "active");
  const expectedSym = attacks.reduce((s, a) => s + uniq([...a.types.map((t) => lbl("type", t)).filter(Boolean).map((r) => r!.label), ...a.symptoms.map((t) => lbl("symptom", t)).filter((r) => r && r.concept !== "prodrome" && r.concept !== "food" && r.concept !== "trigger").map((r) => r!.label), ...(a.aura.present ? ["Aura"] : [])]).length, 0);
  const verify = {
    attacks_written: migIds.length, attacks_open: openCount ?? -1,
    symptom_rows_by_trigger: symCount ?? -1, symptom_labels_expected_at_most: expectedSym,
    pain_points: painPoints, aura_rows: auraRows,
    rows_per_table: Object.fromEntries(Object.entries(manifest).map(([t, ids]) => [t, ids.length])),
    ok: (openCount ?? 1) === 0 && migIds.length === attacks.length,
  };
  return { manifest, verify };
}

function sideEffectScale(text: string | null): string {
  if (!text) return "NONE";
  const e = text.toLowerCase();
  if (/(severe|bad|terrible|awful|erg|ernstig|schlimm|heftig|unbearable)/.test(e)) return "SEVERE";
  if (/(moderate|quite|matig|mäßig|noticeable|annoying)/.test(e)) return "MODERATE";
  if (/(none|no side|geen|keine)/.test(e)) return "NONE";
  return "SOFT";
}
function effectScale(effect: string | null): string {
  if (!effect) return "NONE";
  const e = effect.toLowerCase();
  if (/(very|fully|complete|great|excellent|resolved|gone|helped a lot|goed|sehr gut|hoog|high)/.test(e)) return "HIGH";
  if (/(some|partial|a bit|little|slight|mild|beetje|etwas|low|weinig)/.test(e)) return "LOW";
  if (/(helped|worked|better|relief|yes|ja|good|ok)/.test(e)) return "MILD";
  return "NONE";
}

const UNDO_ORDER = ["treatment_side_effect_logs", "fell_asleep_time_daily", "woke_up_time_daily", "sleep_disturbances_daily", "mindfulness_daily", "body_fat_daily", "blood_pressure_daily", "blood_glucose_daily", "nutrition_records", "treatment_regimens", "missed_activities", "activities", "locations", "reliefs", "medicines", "prodromes", "triggers", "symptoms", "migraine_aura_zones", "migraine_pain_points", "migraines", "sleep_duration_daily", "hydration_daily", "weight_daily", "steps_daily", "trigger_preferences", "symptom_preferences", "medicine_preferences", "relief_preferences", "prodrome_user_preferences", "location_preferences", "activity_preferences", "missed_activity_preferences", "migraine_preferences", "treatment_side_effect_preferences", "user_treatment_side_effects", "user_symptoms", "user_triggers", "user_medicines", "user_reliefs", "user_prodromes", "user_locations", "user_activities", "user_missed_activities", "user_migraines_pool"];

export async function undo(admin: SupabaseClient, userId: string, manifest: Manifest): Promise<Record<string, number>> {
  const deleted: Record<string, number> = {};
  for (const table of UNDO_ORDER) {
    const ids = manifest[table]; if (!ids?.length) continue;
    for (let i = 0; i < ids.length; i += 200) {
      const { error, count } = await admin.from(table).delete({ count: "exact" }).eq("user_id", userId).in("id", ids.slice(i, i + 200));
      if (error) throw new Error(`${table}: ${error.message}`);
      deleted[table] = (deleted[table] ?? 0) + (count ?? 0);
    }
  }
  return deleted;
}

// ─────────────────────────────────────────────────────────────────────────────
// Pipeline entry (exported for local runs)
// ─────────────────────────────────────────────────────────────────────────────

export function applyPriorAnswers(decisions: LinkDecision[], prior: Record<string, string | null>) {
  for (const d of decisions) {
    const a = prior[`${d.class}|${d.phrase}`] ?? prior[`${d.class}|${d.phrase.toLowerCase()}`];
    if (a === undefined) continue;
    if (a === null) { d.tier = "certain"; d.pool_label = null; d.new_item = { label: d.phrase, category: null, icon_key: null }; d.reasoning = "you chose to keep your own words on an earlier import"; }
    else { d.tier = "certain"; d.pool_label = null; d.new_item = { label: a, category: null, icon_key: null }; d.reasoning = "you answered this on an earlier import"; }
  }
}

export async function runPreview(fileName: string, text: string, timezone: string, pools: Pools, spend: Spend, country: string | null, priorAnswers: Record<string, string | null> | null = null) {
  if (text.length > MAX_CONTENT_BYTES) throw new Error("file too large; 6 MB max");
  const kind = sniff(fileName, text);
  const inferences: string[] = [];
  let attacks: Attack[] = [], days: Day[] = [], appGuess: string | null = null, shape: Record<string, unknown> = { kind };
  if (kind === "apple_health") ({ attacks, days } = appleHealthToModel(text, inferences));
  else if (kind === "text" || kind === "xml") ({ attacks, days } = await textToModel(text, spend, inferences));
  else {
    const table = kind === "csv" ? parseCsv(text) : kind === "json" ? parseJsonTable(text) : parseHtmlTable(text);
    if (!table || !table.rows.length) {
      if (kind === "html") ({ attacks, days } = await textToModel(stripTags(text), spend, inferences));
      else throw new Error("no rows found in file");
    } else {
      const cm = await inferColumnMap(table.headers, table.rows, spend, country);
      appGuess = cm.app_guess; shape = { kind, headers: table.headers, rows: table.rows.length, row_kind: cm.row_kind, date_order: cm.date_order, intensity_scale: cm.intensity_scale, column_map: cm.columns, marker_values: cm.marker_values };
      ({ attacks, days } = tableToModel(table.headers, table.rows, cm, inferences));
      inferences.unshift(`${table.rows.length} rows read as one row per ${cm.row_kind}. Dates read as ${cm.date_order}. Pain scale ${cm.intensity_scale}.`);
    }
  }
  attacks.sort((a, b) => a.start_local.localeCompare(b.start_local));
  const model: Model = { source: { app_guess: appGuess, kind, file_name: fileName, sha256: await sha256Hex(text), shape }, timezone, attacks, days, vocabulary: {} as Model["vocabulary"], inferences };
  await mineNotes(model.attacks, model.days, spend, model.inferences);
  fillEnds(model);
  model.vocabulary = buildVocabulary(model.attacks, model.days);
  const decisions = await link(model, pools, spend);
  const namedMeds = uniq((model.vocabulary.medicine ?? []).map((e) => e.phrase).filter((p) => !GENERIC_MED.test(p) && !GENERIC_ANY.test(p)).map((p) => p.toLowerCase().replace(/\s*\d+\s*(mg|mcg)?$/, "")));
  await resolveAmbiguous(model, pools, decisions, namedMeds, spend);
  if (priorAnswers) applyPriorAnswers(decisions, priorAnswers);
  siblingKeys(decisions);
  const regimens = proposeRegimens(model, decisions);
  return { model, decisions, regimens };
}

// ─────────────────────────────────────────────────────────────────────────────
// HTTP
// ─────────────────────────────────────────────────────────────────────────────

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { ...corsHeaders, "Content-Type": "application/json" } });

if (import.meta.main) Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL")!, serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const userClient = createClient(supabaseUrl, Deno.env.get("SUPABASE_ANON_KEY")!, { global: { headers: { Authorization: req.headers.get("Authorization") ?? "" } } });
    const { data: { user } } = await userClient.auth.getUser();
    if (!user) return json({ error: "unauthorized" }, 401);
    const admin = createClient(supabaseUrl, serviceKey);
    const body = await req.json();
    const action = String(body.action ?? "preview");

    // Everyone may import into the journal. Only paid subscribers (not the signup
    // trial) may have imported data count in insights: without a paid sub every
    // category is forced to journal-only (source='import'), whatever was ticked.
    // Same premium_status mirror recalibrate/chat-assistant read; grace_period counts as paid.
    const { data: prem } = await admin.from("premium_status").select("rc_subscription_status").eq("user_id", user.id).maybeSingle();
    const paid = prem?.rc_subscription_status === "active" || prem?.rc_subscription_status === "grace_period";

    if (action === "preview") {
      const fileName = String(body.file_name ?? "upload.csv");
      const text: string = body.content_text ?? (body.content_base64 ? new TextDecoder().decode(Uint8Array.from(atob(body.content_base64), (c) => c.charCodeAt(0))) : "");
      if (!text.trim()) return json({ error: "empty file" }, 400);
      const { data: prof } = await admin.from("profiles").select("timezone,lang").eq("user_id", user.id).maybeSingle();
      const timezone = String(body.timezone ?? prof?.timezone ?? "UTC");
      const sha = await sha256Hex(text);
      const { data: dup } = await admin.from("import_batches").select("id,status").eq("user_id", user.id).eq("file_sha256", sha).eq("status", "committed").maybeSingle();
      if (dup) return json({ error: "this file was already imported", import_id: dup.id }, 409);
      const spend = new Spend();
      const pools = await loadPools(admin, user.id);
      const { data: earlier } = await admin.from("import_batches").select("answers").eq("user_id", user.id).eq("status", "committed").not("answers", "is", null);
      const prior: Record<string, string | null> = Object.assign({}, ...((earlier ?? []).map((b: { answers: Record<string, string | null> }) => b.answers ?? {})));
      const { model, decisions, regimens } = await runPreview(fileName, text, timezone, pools, spend, null, prior);
      const { data: firstApp } = await admin.from("migraines").select("start_at").eq("user_id", user.id).neq("source", "import").order("start_at", { ascending: true }).limit(1).maybeSingle();
      const firstAppDate = firstApp?.start_at ? String(firstApp.start_at).slice(0, 10) : null;
      const overlapping = firstAppDate ? model.attacks.filter((a) => dateOf(a.start_local) >= firstAppDate).length : 0;
      const preview = { ...buildPreview(model, decisions, regimens, { first_app_log: firstAppDate, overlapping }, spend), paid, insights_locked: !paid };
      const { data: batch, error } = await admin.from("import_batches").insert({ user_id: user.id, status: "preview", source_app: model.source.app_guess, file_name: fileName, file_sha256: sha, content_type: model.source.kind, timezone, model, linking: decisions, regimens, preview, cost_usd: spend.usd() }).select("id").single();
      if (error) throw new Error(error.message);
      return json({ import_id: batch.id, preview });
    }

    if (action === "commit") {
      const { data: b, error } = await admin.from("import_batches").select("*").eq("id", body.import_id).eq("user_id", user.id).single();
      if (error || !b) return json({ error: "unknown import" }, 404);
      if (b.status !== "preview") return json({ error: `import is ${b.status}` }, 409);
      const answers = (body.answers ?? {}) as Record<string, string | null>;
      const accept = (body.accept_regimens ?? []) as string[];
      const cutoff = body.cutoff_date === undefined ? (b.preview?.overlap?.first_app_log ?? null) : (body.cutoff_date as string | null);
      const model = b.model as Model;
      (model as unknown as { __doses?: Record<string, string> }).__doses = (body.doses ?? {}) as Record<string, string>;
      const editLog = applyEdits(model, (body.assumptions ?? {}) as Assumptions, (body.exclude_refs ?? []) as string[], (body.attack_edits ?? {}) as Record<string, AttackEdit>);
      await admin.from("import_batches").update({ status: "writing", answers, model, edits: { assumptions: body.assumptions ?? null, doses: body.doses ?? null, exclude_refs: body.exclude_refs ?? [], attack_edits: body.attack_edits ?? {}, engine_use: body.engine_use ?? null, log: editLog } }).eq("id", b.id);
      try {
        const requested = (body.engine_use ?? null) as Record<string, boolean> | null;
        const engineUse = paid ? requested : Object.fromEntries(engineUseRecommendations(model).map((r) => [r.key, false]));
        const { manifest, verify } = await commit(admin, user.id, model, b.linking as LinkDecision[], answers, accept, (b.regimens ?? []) as RegimenProposal[], cutoff, b.id, engineUse);
        if (!verify.ok) { await undo(admin, user.id, manifest); await admin.from("import_batches").update({ status: "failed", manifest, verify }).eq("id", b.id); return json({ error: "verification failed, nothing kept", verify }, 500); }
        await admin.from("import_batches").update({ status: "committed", manifest, verify: { ...verify, paid, insights: paid ? "as ticked" : "locked, journal only" }, committed_at: new Date().toISOString() }).eq("id", b.id);
        admin.functions.invoke("compute-correlation-stats", { body: { user_id: user.id } }).catch(() => {});
        return json({ import_id: b.id, written: verify.rows_per_table, verify });
      } catch (e) {
        await admin.from("import_batches").update({ status: "failed", verify: { error: String(e) } }).eq("id", b.id);
        throw e;
      }
    }

    if (action === "undo") {
      const { data: b } = await admin.from("import_batches").select("*").eq("id", body.import_id).eq("user_id", user.id).single();
      if (!b) return json({ error: "unknown import" }, 404);
      if (b.status !== "committed" && b.status !== "failed") return json({ error: `import is ${b.status}` }, 409);
      const deleted = await undo(admin, user.id, (b.manifest ?? {}) as Manifest);
      await admin.from("import_batches").update({ status: "undone", undone_at: new Date().toISOString() }).eq("id", b.id);
      return json({ import_id: b.id, deleted });
    }

    if (action === "status") {
      const { data: b } = await admin.from("import_batches").select("id,status,source_app,file_name,preview,verify,cost_usd,created_at,committed_at,undone_at").eq("id", body.import_id).eq("user_id", user.id).single();
      return b ? json(b) : json({ error: "unknown import" }, 404);
    }
    return json({ error: "unknown action" }, 400);
  } catch (e) {
    console.error("import-history:", e);
    return json({ error: String((e as Error).message ?? e) }, 500);
  }
});
