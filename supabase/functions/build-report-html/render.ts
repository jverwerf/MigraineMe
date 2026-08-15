// The report itself. One template, printed by all four apps.
//
// Sections are built from the primitives in styles.ts — section header, card,
// row, badge, meta line — so a change to the look happens in one place rather
// than being re-implemented in Kotlin, Swift and twice in TypeScript.

import { STYLES } from "./styles.ts";
import { HEAD_BACK_PNG, HEAD_FRONT_PNG, LOGO_PNG } from "./assets.ts";
import { AURA_COLS, AURA_ROWS, AURA_ZONE_LABEL, PAIN_LABELS, PAIN_POINTS } from "./painpoints.ts";
import { WINDOW_AFTER_DAYS, WINDOW_BEFORE_DAYS } from "./data.ts";
import { METRICS } from "./metrics.ts";
import type { ChildRow, CorrelationStat, Migraine, ReportData, SymptomRow } from "./data.ts";
import { translatorFor } from "../_shared/i18n.ts";

// ── colours, one palette for the whole document ──────────────────
const C = {
  accent: "#CE93D8",
  pink: "#FF7BB0",
  red: "#E57373",
  orange: "#FFB74D",
  purple: "#9575CD",
  blue: "#4FC3F7",
  green: "#81C784",
  slate: "#90A4AE",
  muted: "#A794BD",
};

// ── small helpers ────────────────────────────────────────────────
const esc = (s: unknown): string =>
  String(s ?? "").replace(/[&<>"']/g, (ch) => (
    { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch]!
  ));

/**
 * Translator and locale for the document currently being rendered, set at the
 * top of renderReport. The report is assembled by dozens of small builders that
 * each take only the slice of data they need, so a module-level handle keeps
 * every signature in this file unchanged. The renderer is synchronous and
 * builds one document per call.
 */
let rt: (en: string, ...args: unknown[]) => string = (en) => en;

/** Region per language, matching how the copy is worded: en-GB not en-US, and
 *  European rather than Brazilian Portuguese. */
const LOCALE_TAGS: Record<string, string> = {
  en: "en-GB", de: "de-DE", es: "es-ES", nl: "nl-NL", fr: "fr-FR", it: "it-IT", pt: "pt-PT",
};
let rtLocale = "en-GB";

const pretty = (raw: unknown): string => {
  const s = String(raw ?? "").trim().replace(/_/g, " ").replace(/\s+/g, " ");
  if (!s) return "";
  // Counterpart to the app's prettyLabel: seeded names translate, names the
  // patient typed themselves pass through unchanged, because English is the key.
  return rt(s[0].toUpperCase() + s.slice(1));
};

const rgba = (hex: string, a: number): string => {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
};

const parseDate = (iso: string | null | undefined): Date | null => {
  if (!iso) return null;
  const d = new Date(iso);
  return isNaN(d.getTime()) ? null : d;
};

const fmtDate = (d: Date): string =>
  d.toLocaleDateString(rtLocale, { day: "2-digit", month: "short", year: "numeric" });

const fmtDateTime = (d: Date): string =>
  `${fmtDate(d)} · ${d.toLocaleTimeString(rtLocale, { hour: "2-digit", minute: "2-digit" })}`;

/** "+2h 10m", "-2d 4h", "at onset". Triggers and prodromes are routinely
 *  logged before the attack, so the sign carries clinical meaning. */
function offset(start: Date, at: Date | null): string {
  if (!at) return "";
  const mins = Math.round((at.getTime() - start.getTime()) / 60000);
  if (mins === 0) return "at onset";
  const sign = mins < 0 ? "-" : "+";
  const abs = Math.abs(mins);
  const d = Math.floor(abs / 1440), h = Math.floor((abs % 1440) / 60), m = abs % 60;
  if (d > 0 && h > 0) return `${sign}${d}d ${h}h`;
  if (d > 0) return `${sign}${d}d`;
  if (h > 0 && m > 0) return `${sign}${h}h ${m}m`;
  if (h > 0) return `${sign}${h}h`;
  return `${sign}${m}m`;
}

const sevColor = (s: number | null): string =>
  s == null ? C.muted : s <= 3 ? C.green : s <= 6 ? C.orange : C.red;

const durationHours = (m: Migraine): number | null => {
  const s = parseDate(m.start_at), e = parseDate(m.ended_at);
  return s && e ? (e.getTime() - s.getTime()) / 3_600_000 : null;
};

// ── primitives ───────────────────────────────────────────────────
function page(color: string, body: string, num: number | string): string {
  return `<section class="page" style="--sc:${color}">${body}
    <div class="page-num">${num}</div></section>`;
}

/** `allTime` marks the sections whose numbers come from the whole history
 *  rather than the selected range — the correlation engine works on everything
 *  it has, and a reader comparing them to the filtered sections deserves to
 *  know that. */
function header(color: string, icon: string, title: string, sub: string, allTime = false): string {
  return `<div class="sec">
    <div class="blob" style="background:linear-gradient(135deg, ${rgba(color, .34)}, ${rgba(color, .12)})">
      <svg viewBox="0 0 24 24" stroke="${color}">${icon}</svg>
    </div>
    <div><h2>${esc(title)}</h2><p>${esc(sub)}</p></div>
    <div class="rule"></div>
    ${allTime ? `<span class="badge" style="color:${C.muted};background:rgba(255,255,255,0.06)">
      ${rt("All logged data")}</span>` : ""}
  </div>`;
}

const sub = (color: string, text: string): string =>
  `<h3 class="sub" style="color:${color}">${esc(text)}</h3>`;

const badge = (color: string, text: string): string =>
  `<span class="badge" style="color:${color};background:${rgba(color, .16)}">${esc(text)}</span>`;

function dots(color: string, p: number): string {
  const filled = p < 0.01 ? 3 : p < 0.05 ? 2 : 1;
  let out = '<span class="dots">';
  for (let i = 0; i < 3; i++) {
    out += `<i style="background:${i < filled ? color : rgba(color, .25)}"></i>`;
  }
  return out + "</span>";
}

const bar = (color: string, pct: number): string =>
  `<div class="bar" style="background:${rgba(color, .22)}">
     <i style="width:${Math.max(0, Math.min(100, pct))}%;background:${color}"></i></div>`;

const ICON = {
  log: '<path d="M4 6h16M4 12h16M4 18h10"/>',
  freq: '<path d="M4 19V9M10 19V5M16 19v-7M22 19H2"/>',
  search: '<circle cx="11" cy="11" r="6"/><path d="M15.5 15.5L21 21"/>',
  check: '<path d="M20 6L9 17l-5-5"/>',
  leaf: '<path d="M12 20s6-4 6-9a6 6 0 10-12 0c0 5 6 9 6 9z"/><path d="M12 11v9"/>',
  run: '<path d="M13 3l-2 7h5l-4 11 1-8H8z"/>',
  pin: '<path d="M12 21s-7-4.5-7-10a7 7 0 0114 0c0 5.5-7 10-7 10z"/><circle cx="12" cy="11" r="2.5"/>',
  bulb: '<path d="M9 18h6M10 22h4M12 2a6 6 0 00-4 10.5c.7.8 1 1.6 1 2.5h6c0-.9.3-1.7 1-2.5A6 6 0 0012 2z"/>',
  pill: '<rect x="3" y="8" width="18" height="8" rx="4"/><path d="M12 8v8"/>',
  chart: '<path d="M3 17l5-6 4 3 4-7 5 5"/>',
  grid: '<path d="M12 3v18M3 12h18"/>',
  pulse: '<path d="M3 12h4l2 5 4-13 2 8h6"/>',
};


/**
 * The aura eye, drawn exactly as the app draws it (AuraDetailSheet.kt):
 * an almond lid with an iris behind a 3x3 zone grid, where only hit zones are
 * filled and the fill alpha is capped at 0.62 so the eye reads through it.
 */
function auraEye(
  side: "left" | "right",
  pctFor: (zoneId: string) => number,
  opts: { width: number; showPct: boolean; label: string },
): string {
  const w = opts.width, h = w / 1.25, mid = h / 2, iris = h * 0.24;
  const lid = `M0,${mid} C${w * .25},${mid - h * .42} ${w * .75},${mid - h * .42} ${w},${mid}`
            + ` C${w * .75},${mid + h * .42} ${w * .25},${mid + h * .42} 0,${mid} Z`;
  const cells = AURA_ROWS.flatMap((row) => AURA_COLS.map((col) => {
    const pct = pctFor(`${side}_${row}_${col}`);
    if (pct <= 0) return `<span></span>`;
    const alpha = Math.min(0.62, Math.max(0.20, pct / 100));
    return `<span style="background:${rgba(C.purple, alpha)}">${opts.showPct ? Math.round(pct) + "%" : ""}</span>`;
  })).join("");
  return `<div class="eye" style="width:${w}px">
    <div class="eye-box" style="height:${h.toFixed(1)}px">
      <svg viewBox="0 0 ${w} ${h.toFixed(1)}" style="width:${w}px;height:${h.toFixed(1)}px">
        <path d="${lid}" fill="none" stroke="rgba(255,255,255,0.18)" stroke-width="3"/>
        <circle cx="${w / 2}" cy="${mid.toFixed(1)}" r="${iris.toFixed(1)}" fill="${rgba(C.purple, .18)}"/>
        <circle cx="${w / 2}" cy="${mid.toFixed(1)}" r="${iris.toFixed(1)}" fill="none"
          stroke="rgba(255,255,255,0.16)" stroke-width="2"/>
        <circle cx="${w / 2}" cy="${mid.toFixed(1)}" r="${(iris * .38).toFixed(1)}" fill="rgba(255,255,255,0.20)"/>
      </svg>
      <div class="zones">${cells}</div>
    </div>
    <div class="cap">${esc(opts.label)}</div>
  </div>`;
}

// ── cover ────────────────────────────────────────────────────────
function cover(d: ReportData, contents: { title: string; page: number }[]): string {
  const migs = d.migraines;
  const sevs = migs.map((m) => m.severity).filter((s): s is number => s != null);
  const avgSev = sevs.length ? (sevs.reduce((a, b) => a + b, 0) / sevs.length).toFixed(1) : "—";
  const durs = migs.map(durationHours).filter((h): h is number => h != null);
  const avgDur = durs.length ? `${(durs.reduce((a, b) => a + b, 0) / durs.length).toFixed(1)}h` : "—";

  const dates = migs.map((m) => parseDate(m.start_at)).filter((x): x is Date => !!x);
  const first = dates.length ? dates[dates.length - 1] : null;
  const last = dates.length ? dates[0] : null;

  // The reported window is the range the reader SELECTED, not the span of the
  // attacks that happened to fall in it. Deriving it from attack dates made a
  // "Last 30 Days" report with one attack claim a 1-day period ending on that
  // attack; a clinician reads the header as the observation window, so it must
  // reflect from/to. from is the inclusive local start-of-day; to is exclusive
  // (start of the day after the last included day). Presets send from with no
  // to — the window then runs to now. All Time sends neither, so we fall back
  // to the attack span.
  const DAY = 86_400_000;
  const winStart = d.params.from ? parseDate(d.params.from) : first;
  const winEndExcl = d.params.to ? parseDate(d.params.to)
    : d.params.from ? new Date()
    : (last ? new Date(last.getTime() + DAY) : null);
  const winEndShown = d.params.to ? new Date(Date.parse(d.params.to) - DAY)
    : d.params.from ? new Date()
    : last;
  const days = winStart && winEndExcl
    ? Math.max(1, Math.round((winEndExcl.getTime() - winStart.getTime()) / DAY))
    : 0;
  const perMonth = days > 0 ? (migs.length / (days / 30)).toFixed(1) : "—";

  const range = winStart && winEndShown
    ? `${fmtDate(winStart)} — ${fmtDate(winEndShown)}`
    : rt("No attacks in range");

  return page(C.accent, `
    <div class="cover">
      <div class="brand">
        <div class="logo-blob"><img src="${LOGO_PNG}" alt=""></div>
        <div class="rule"></div>
        <h1>Migraine<span>Me</span></h1>
        <p class="kicker">${rt("Migraine report")}</p>
        <p class="range">${esc(rt(d.params.timeframeLabel ?? "All time"))} · ${esc(range)}
           · ${esc(rt("generated {0}", fmtDate(new Date())))}</p>
        ${d.params.filterSummary
          ? `<p class="range">${esc(d.params.filterSummary)}</p>` : ""}
      </div>

      <div class="stats" style="margin-top:38px">
        <div class="stat"><b>${migs.length}</b><span>${rt("Migraines")}</span></div>
        <div class="stat"><b>${avgSev}</b><span>${rt("Avg severity /10")}</span></div>
        <div class="stat"><b>${avgDur}</b><span>${rt("Avg duration")}</span></div>
        <div class="stat"><b>${perMonth}</b><span>${rt("Days / month")}</span></div>
      </div>

      <div class="contents">
        <h4>${rt("CONTENTS")}</h4>
        <ol>${contents.map((c) =>
          `<li><span>${esc(c.title)}</span><em>${c.page}</em></li>`).join("")}</ol>
      </div>

      <div class="patient">
        <div><span>${rt("Tracking since")}</span>${first ? esc(fmtDate(first)) : "—"}</div>
        <div><span>${rt("Report period")}</span>${rt("{0} days", days)}</div>
        <div><span>${rt("Attacks logged")}</span>${migs.length}</div>
      </div>
      <p class="disclaimer">
        ${rt("Generated by MigraineMe from the patient's own logged data.")}
        ${rt("Correlations describe what co-occurred in this record; they are not a diagnosis and do not establish causation.")}
        ${rt("MigraineMe is not a medical device.")}
      </p>
    </div>`, 1);
}

/** How a medicine's dose should print.
 *
 *  dose_value + dose_unit are the truth (dose contract 2026-08-13); `amount` is
 *  a formatted mirror the clients still write, and the ONLY thing rows logged
 *  before that contract have. So: real columns when present, legacy string
 *  otherwise, nothing when neither. A clinician reading "took 2" wants to know
 *  two of what, and the mirror is the one that goes stale.
 */
function doseText(r: ChildRow): string | null {
  const v = r.dose_value;
  const u = (r.dose_unit ?? "").trim();
  if (v != null && Number.isFinite(v)) {
    // Trailing zeros read as false precision on a dose.
    const n = String(Number(v.toFixed(3)));
    switch (u) {
      case "mg":      return `${n}mg`;
      case "mcg":     return `${n}mcg`;
      case "units":   return `${n} ${rt("units")}`;
      case "minutes": return `${n} ${rt("min")}`;
      case "amount":  return n;
      default:        return u ? `${n} ${rt(u)}` : n;
    }
  }
  const legacy = (r.amount ?? "").trim();
  return legacy.length > 0 ? legacy : null;
}

// ── attack log ───────────────────────────────────────────────────
const PAIN_ENTRIES_SHOWN = 10;

type LogRow = { label: string; at: Date | null; text: string; pain?: boolean };

function attackCard(m: Migraine, d: ReportData): string {
  const start = parseDate(m.start_at)!;
  const rows: LogRow[] = [];

  const symRows = d.symptoms.filter((s) => s.migraine_id === m.id);
  const catOf = (l: string) => d.symptomCategories[l.trim().toLowerCase()] ?? "";
  const rowFor = new Map<string, SymptomRow>();
  for (const s of symRows) if (s.type) rowFor.set(s.type.trim().toLowerCase(), s);

  // `symptoms` rows mirror migraines.type, so the type CSV is annotated in
  // place rather than printed a second time under a Postdrome heading.
  const typeSyms = (m.type ?? "").split(",").map((s) => s.trim())
    .filter((s) => s && s !== "Migraine");
  for (const label of typeSyms) {
    if (catOf(label) === "postdrome") continue;
    const src = rowFor.get(label.toLowerCase());
    const note = src?.severity ? ` <span style="color:${C.muted}">· ${esc(src.severity.toLowerCase())}</span>` : "";
    rows.push({ label: "Symptom", at: parseDate(src?.start_at ?? null), text: pretty(label) + note });
  }
  for (const s of symRows) {
    if (!s.type || catOf(s.type) !== "postdrome") continue;
    const note = s.severity ? ` <span style="color:${C.muted}">· ${esc(s.severity.toLowerCase())}</span>` : "";
    rows.push({ label: "Postdrome", at: parseDate(s.start_at), text: pretty(s.type) + note });
  }

  const points = d.painPoints.filter((p) => p.migraine_id === m.id);
  const byMoment = new Map<string, typeof points>();
  for (const p of points) {
    const list = byMoment.get(p.start_at) ?? [];
    list.push(p);
    byMoment.set(p.start_at, list);
  }
  const moments = [...byMoment.entries()].sort((a, b) => (a[0] < b[0] ? -1 : 1));
  if (moments.length === 0) {
    const legacy = m.pain_locations ?? [];
    if (legacy.length) {
      rows.push({
        label: "Pain location", at: null, pain: true,
        text: legacy.map((l) => rt(PAIN_LABELS[l] ?? pretty(l))).join(", "),
      });
    }
  } else {
    for (const [at, group] of moments.slice(0, PAIN_ENTRIES_SHOWN)) {
      const sev = group.map((g) => g.severity).filter((s): s is number => s != null);
      const locs = group.map((g) => rt(PAIN_LABELS[g.location_id] ?? pretty(g.location_id))).join(", ");
      rows.push({
        label: "Pain", at: parseDate(at), pain: true,
        text: sev.length ? `${Math.max(...sev)}/10 — ${locs}` : locs,
      });
    }
  }

  const auraZones = (m.aura_locations ?? []).map((z) => AURA_ZONE_LABEL(z, rt));
  if (auraZones.length || m.aura_duration_minutes) {
    const dur = m.aura_duration_minutes ? ` · ${m.aura_duration_minutes}m` : "";
    rows.push({ label: "Aura", at: null, text: esc(auraZones.join(", ")) + dur });
  }

  const push = (label: string, list: ChildRow[]) => {
    for (const r of list.filter((x) => x.migraine_id === m.id)) {
      const name = r.name ?? r.type;
      if (!name) continue;
      const dose = doseText(r);
      const amt = dose ? ` (${esc(dose)})` : "";
      rows.push({ label, at: parseDate(r.start_at), text: pretty(name) + amt });
    }
  };
  push("Trigger", d.triggers);
  push("Prodrome", d.prodromes);
  push("Medicine", d.medicines);
  push("Relief", d.reliefs);
  push("Activity", d.activities);
  push("Location", d.locations);
  push("Missed", d.missedActivities);


  const order = ["Symptom", "Prodrome", "Pain", "Pain location", "Postdrome", "Aura",
                 "Trigger", "Medicine", "Relief", "Activity", "Location", "Missed"];
  const timed = rows.filter((r) => r.at).sort((a, b) => a.at!.getTime() - b.at!.getTime());
  const untimed = rows.filter((r) => !r.at)
    .sort((a, b) => order.indexOf(a.label) - order.indexOf(b.label));

  const extra = moments.length > PAIN_ENTRIES_SHOWN
    ? `<tr class="note"><td class="k"></td><td class="o"></td>
         <td class="v">${rt("Showing {0} of {1} pain entries", PAIN_ENTRIES_SHOWN, moments.length)}</td></tr>`
    : "";

  const hrs = durationHours(m);

  // Events for the chart's lanes, numbered chronologically. Automated triggers
  // and prodromes also decide which metrics this attack's chart overlays: an
  // auto-detected "Poor sleep" pulls in the sleep metrics behind it.
  const chartEvents: { at: Date; label: string; category: string; color: string }[] = [];
  const autoKeys = new Set<string>(d.params.metricKeys ?? []);
  const catColor: Record<string, string> = {
    Prodrome: C.purple, Trigger: C.orange, Medicine: C.blue,
    Relief: C.green, Activity: C.orange, Location: C.slate, Missed: C.red,
  };
  // A logged "Poor sleep" IS the sleep data, so it belongs on that line in the
  // tracked-data panel rather than as another pin in the top one.
  const metricEvents: { at: Date; label: string; keys: string[]; auto: boolean }[] = [];
  const addEvents = (category: string, list: ChildRow[], auto = false) => {
    for (const r of list.filter((x) => x.migraine_id === m.id)) {
      const name = r.name ?? r.type;
      const at = parseDate(r.start_at);
      if (!name || !at) continue;
      const norm = name.toLowerCase().replace(/:/g, "").trim()
        .replace(/\s+(low|high|short|long|late|early|many|few)\b.*/, "").trim();
      const keys = auto ? (d.labelToMetricKeys[norm] ?? []) : [];
      // Same rule as autoMetricKeysForMigraines() in the app: any linked item
      // standing for a tracked metric pulls that metric onto the chart.
      for (const k of keys) autoKeys.add(k);
      if (keys.length > 0) {
        // "Poor sleep" is derived from the sleep data by the trigger engine —
        // saying "logged" would credit the patient with typing something they
        // never typed.
        metricEvents.push({ at, label: pretty(name), keys, auto: r.source === "system" });
      } else {
        chartEvents.push({ at, label: pretty(name), category, color: catColor[category] ?? C.accent });
      }
    }
  };
  addEvents("Prodrome", d.prodromes, true);
  addEvents("Trigger", d.triggers, true);
  addEvents("Medicine", d.medicines, true);
  addEvents("Relief", d.reliefs, true);
  // Symptoms and postdromes carry a real time and an intensity, so they belong
  // on the axis with the treatments rather than in the strip below.
  for (const sy of symRows) {
    const at = parseDate(sy.start_at);
    if (!at || !sy.type) continue;
    const isPost = catOf(sy.type) === "postdrome";
    chartEvents.push({
      at,
      label: sy.severity ? `${pretty(sy.type)} (${sy.severity.toLowerCase()})` : pretty(sy.type),
      category: isPost ? "Postdrome" : "Symptom",
      color: isPost ? C.purple : C.red,
    });
  }
  chartEvents.sort((a, b) => a.at.getTime() - b.at.getTime());
  // A metric the reader explicitly switched off stays off, even when the
  // trigger engine re-detects it on this attack.
  for (const k of d.params.disabledMetricKeys ?? []) autoKeys.delete(k);
  const chart = attackChart(m, d, chartEvents, metricEvents, [...autoKeys]);

  // Where they were and what the attack cost them: context, not timing, so it
  // reads better as a line under the chart than as more pins inside it.
  const contextStrip = (() => {
    const names = (list: ChildRow[]) => [...new Set(list
      .filter((r) => r.migraine_id === m.id)
      .map((r) => pretty(r.type ?? r.name ?? ""))
      .filter(Boolean))];
    const where = names(d.locations);
    const doing = names(d.activities);
    const missed = names(d.missedActivities);
    const bits = [
      where.length ? `<b>${rt("Where")}</b> ${esc(where.join(", "))}` : "",
      doing.length ? `<b>${rt("Doing")}</b> ${esc(doing.join(", "))}` : "",
      missed.length ? `<b style="color:${C.red}">${rt("Missed")}</b> ${esc(missed.join(", "))}` : "",
    ].filter(Boolean);
    return bits;
  })();


  // The visuals are the point of the card: where the pain sat, what the aura
  // looked like, and how the pain actually moved. Each one is omitted when the
  // attack has nothing to draw rather than printing an empty frame.
  const painIds = new Set<string>([
    ...(m.pain_locations ?? []),
    ...points.map((p) => p.location_id),
  ]);
  // Each pain entry gets its own colour and number, so the head shows the
  // order the pain moved rather than just where it ended up.
  const shown = moments.slice(0, PAIN_ENTRIES_SHOWN);
  const shade = (i: number) => {
    const steps = Math.max(1, shown.length - 1);
    return rgba(C.pink, 0.3 + (i / steps) * 0.7);
  };
  const stagePins = shown.flatMap(([, group], i) =>
    group.map((pt) => ({ id: pt.location_id, n: i + 1, color: shade(i) })));
  const untimedPins = stagePins.length === 0
    ? (m.pain_locations ?? []).map((id) => ({ id, n: 0, color: rgba(C.pink, .9) }))
    : [];
  const pins = [...stagePins, ...untimedPins].filter((pin) => {
    const pt = PAIN_POINTS[pin.id];
    return pt && pt.view !== "back";
  });

  const miniHead = pins.length === 0 ? "" : `
    <figure class="mini-head">
      <img src="${HEAD_FRONT_PNG}" alt="">
      ${pins.map((pin) => {
        const pt = PAIN_POINTS[pin.id];
        return `<span class="pin" style="left:${(pt.x * 100).toFixed(1)}%;top:${(pt.y * 100).toFixed(1)}%;
          background:${pin.color};border-color:${rgba(C.pink, .95)}">${pin.n > 0 ? pin.n : ""}</span>`;
      }).join("")}
    </figure>`;

  const auraSet = new Set(m.aura_locations ?? []);
  // Timed zones stage the eyes the way the head stages: entry 1 palest, the
  // most recent fullest. Untimed attacks fall back to a flat fill.
  const auraMoments = (() => {
    const rows = d.auraZones.filter((z) => z.migraine_id === m.id && z.start_at);
    if (rows.length === 0) return [] as { at: string; zones: string[] }[];
    const byMoment = new Map<string, string[]>();
    for (const r of rows) byMoment.set(r.start_at!, [...(byMoment.get(r.start_at!) ?? []), r.zone]);
    return [...byMoment.entries()].sort((a, b) => (a[0] < b[0] ? -1 : 1))
      .map(([at, zones]) => ({ at, zones }));
  })();
  const auraStage = new Map<string, number>();
  auraMoments.forEach(({ zones }, i) => {
    const steps = Math.max(1, auraMoments.length - 1);
    for (const z of zones) auraStage.set(z, 30 + (i / steps) * 70);
  });
  const miniEyes = auraSet.size === 0 ? "" : `
    <div class="eyes" style="gap:8px;padding:6px 0 0">
      ${(["left", "right"] as const).map((side) =>
        auraEye(side, (z) => auraStage.get(z) ?? (auraSet.has(z) ? 100 : 0),
          { width: 62, showPct: false, label: side === "left" ? "L" : "R" })
      ).join("")}
    </div>`;

  const peak = moments.flatMap(([, group]) => group.map((p) => p.severity ?? 0));
  const facts = [
    m.severity != null ? `<b>${rt("Peak")}</b> ${m.severity}/10` : "",
    hrs != null ? `<b>${rt("Lasted")}</b> ${hrs.toFixed(1)}h` : "",
    moments.length
      ? `<b>${rt("Pain")}</b> ${moments.map(([at, group]) => {
          const sev = group.map((g) => g.severity).filter((v): v is number => v != null);
          const locs = group.map((g) => rt(PAIN_LABELS[g.location_id] ?? pretty(g.location_id))).join(", ");
          return `${sev.length ? `${Math.max(...sev)}/10 ` : ""}${esc(locs)}`;
        }).join(" &rarr; ")}`
      : (painIds.size
        ? `<b>${rt("Pain")}</b> ${esc([...painIds].map((id) => rt(PAIN_LABELS[id] ?? pretty(id))).join(", "))}`
        : ""),
    auraSet.size
      ? `<b>${rt("Aura")}</b> ${esc([...auraSet].map((z) => AURA_ZONE_LABEL(z, rt)).join(", "))}${
          m.aura_duration_minutes ? ` · ${m.aura_duration_minutes}m` : ""}`
      : "",
  ].filter(Boolean);
  const factsBlock = facts.length
    ? `<div class="facts">${facts.map((f) => `<div>${f}</div>`).join("")}</div>`
    : "";
  return `<div class="card">
    <div class="log-head">
      <span class="date">${esc(fmtDateTime(start))}</span>
      <span class="sev" style="color:${sevColor(m.severity)}">
        ${m.severity != null ? rt("Severity {0}", m.severity) : rt("Severity —")}
        ${hrs != null ? ` · ${hrs.toFixed(1)}h` : ` · ${rt("ongoing")}`}
      </span>
    </div>
    <div class="attack">
      ${miniHead || miniEyes || factsBlock
        ? `<div class="attack-vis">${miniHead}${miniEyes}${factsBlock}</div>` : ""}
      <div class="attack-body">
        ${chart.svg}
        <div class="under">
          <div class="under-col">
            <div class="under-h">${rt("TIMELINE")}</div>
            <table class="log">
              ${[...timed, ...untimed].map((r) => `<tr>
                <td class="k"${r.pain ? ` style="color:${C.pink}"` : ""}>${esc(rt(r.label))}</td>
                <td class="o">${esc((() => {
                  // "at onset" is an internal sentinel (axisLabel compares
                  // against the English value); translate only here at output.
                  const o = offset(start, r.at);
                  return o === "at onset" ? rt("at onset") : o;
                })())}</td>
                <td class="v">${r.text}</td>
              </tr>`).join("")}
              ${extra}
            </table>
          </div>
          <div class="under-col narrow">
            <div class="under-h">${rt("BY CATEGORY")}</div>
            ${(() => {
              // Each category is its own little block — a heading with what was
              // logged under it — so it reads the same way as CONTEXT rather
              // than as another two-column table.
              const order = ["Prodrome", "Trigger", "Symptom", "Postdrome",
                             "Medicine", "Relief", "Activity", "Location", "Missed"];
              const byCat = new Map<string, string[]>();
              for (const r of [...timed, ...untimed]) {
                if (!r.label || r.pain || r.label === "Aura") continue;
                byCat.set(r.label, [...(byCat.get(r.label) ?? []), r.text]);
              }
              return [...byCat.entries()]
                .sort((a, b) => {
                  const ia = order.indexOf(a[0]), ib = order.indexOf(b[0]);
                  return (ia < 0 ? order.length : ia) - (ib < 0 ? order.length : ib);
                })
                .map(([cat, items]) => `<div class="cat">
                  <div class="cat-h">${esc(rt(cat).toUpperCase())}</div>
                  <div class="cat-v">${items.join(", ")}</div>
                </div>`).join("");
            })()}
          </div>
          ${chart.ranges.length ? `<div class="under-col narrow">
            ${chart.ranges.length ? `<div class="under-h">${rt("TRACKED DATA")}</div>` : ""}
            <table class="log">
              ${chart.ranges.map((r) => `<tr>
                <td class="k" style="color:${r.color};width:auto">${esc(r.label)}</td>
                <td class="v" style="text-align:right">${esc(r.range)}</td>
              </tr>`).join("")}
            </table>
          </div>` : ""}
        </div>
      </div>
    </div>
  </div>`;
}

/** Catmull-Rom through the points, converted to cubic beziers — a data line
 *  that curves rather than kinks reads as a trend instead of a zigzag. */
function smoothPath(pts: { x: number; y: number }[]): string {
  if (pts.length < 2) return "";
  if (pts.length === 2) {
    return `M${pts[0].x.toFixed(1)},${pts[0].y.toFixed(1)} L${pts[1].x.toFixed(1)},${pts[1].y.toFixed(1)}`;
  }
  let dpath = `M${pts[0].x.toFixed(1)},${pts[0].y.toFixed(1)}`;
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[i - 1] ?? pts[i], p1 = pts[i], p2 = pts[i + 1], p3 = pts[i + 2] ?? p2;
    const c1x = p1.x + (p2.x - p0.x) / 6, c1y = p1.y + (p2.y - p0.y) / 6;
    const c2x = p2.x - (p3.x - p1.x) / 6, c2y = p2.y - (p3.y - p1.y) / 6;
    dpath += ` C${c1x.toFixed(1)},${c1y.toFixed(1)} ${c2x.toFixed(1)},${c2y.toFixed(1)} ${p2.x.toFixed(1)},${p2.y.toFixed(1)}`;
  }
  return dpath;
}

/**
 * One chart per attack, two stacked panels on a single time axis so the dashed
 * onset line runs through both:
 *
 *   top    — the attack itself: pain curve plus every discrete event, each
 *            spelled out on the chart. Labels are stacked into free rows so
 *            they never sit on top of each other, which is why there is no
 *            legend to cross-reference.
 *   bottom — only the continuously tracked metrics, each named at the end of
 *            its own line.
 */
function attackChart(
  m: Migraine,
  d: ReportData,
  events: { at: Date; label: string; category: string; color: string }[],
  metricEvents: { at: Date; label: string; keys: string[]; auto: boolean }[],
  metricKeys: string[],
): { svg: string; ranges: { label: string; range: string; color: string }[] } {
  const start = parseDate(m.start_at)!;
  const end = parseDate(m.ended_at) ?? start;
  const hasMetricsEarly = d.metricSeries.some((s) => metricKeys.includes(s.key));

  // Days of context only earn their space when there is continuous data to
  // show across them. Without it the chart covers the attack and its own
  // events, which is all there is to see.
  const dayStart = (t: Date) => {
    const c = new Date(t);
    c.setHours(0, 0, 0, 0);
    return c;
  };
  const eventTimes = events.map((e) => e.at.getTime());
  const winStart = hasMetricsEarly
    ? dayStart(new Date(start.getTime() - WINDOW_BEFORE_DAYS * 86_400_000))
    : new Date(Math.min(start.getTime(), ...eventTimes, start.getTime()) - 30 * 60_000);
  const winEnd = hasMetricsEarly
    // Whole days on both edges, so every stripe is the same width instead of
    // ending on a sliver.
    ? new Date(dayStart(new Date(end.getTime() + WINDOW_AFTER_DAYS * 86_400_000)).getTime() + 86_400_000)
    : new Date(Math.max(end.getTime(), ...eventTimes, end.getTime()) + 30 * 60_000);
  const span = Math.max(winEnd.getTime() - winStart.getTime(), 3_600_000);

  const W = 470, padL = 26, padR = 10;
  const plotW = W - padL - padR;

  // One uniform scale: an hour is an hour anywhere on the axis. A segmented
  // axis gave the attack more room but made a Tuesday wider than a Monday,
  // which is worse than a short curve.
  const clamp = (t: Date) => Math.min(Math.max(t.getTime(), winStart.getTime()), winEnd.getTime());
  const x = (t: Date) => padL + ((clamp(t) - winStart.getTime()) / span) * plotW;

  // ── top panel: pain + every discrete event ──
  const moments = new Map<string, number[]>();
  for (const p of d.painPoints.filter((p) => p.migraine_id === m.id)) {
    if (p.severity == null) continue;
    moments.set(p.start_at, [...(moments.get(p.start_at) ?? []), p.severity]);
  }
  const pain = [...moments.entries()]
    .map(([at, sevs]) => ({ t: parseDate(at), sev: Math.max(...sevs) }))
    .filter((p): p is { t: Date; sev: number } => !!p.t)
    .sort((a, b) => a.t.getTime() - b.t.getTime());

  // Two clearly separate sections, each a framed plot box. Event names live
  // INSIDE the top box next to their own pins rather than floating above the
  // chart, so the reader's eye never leaves the plot.
  const p1Top = 8;
  const painTop = p1Top + 12, painH = 78;
  const p1Bottom = painTop + painH + 46;      // room for upright event labels
  const p2Top = p1Bottom + 10;
  const metricTop = p2Top + 12, metricH = 84;
  // Whether the lower panel exists at all decides the chart's height, so it is
  // settled before anything is positioned against the bottom edge.
  const hasMetrics = hasMetricsEarly;
  const p2Bottom = hasMetrics ? metricTop + metricH + 6 : p1Bottom;
  const H = p2Bottom + 16;

  const yPain = (sev: number) => painTop + (1 - sev / 10) * painH;

  // Event names are set upright at their own pin, inside the plot. Rotating
  // them is what makes a dozen events fit on one axis without a legend and
  // without a wall of text above the chart.
  const usedX: number[] = [];
  const eventSvg = events.map((e) => {
    let ex = x(e.at);
    while (usedX.some((u) => Math.abs(u - ex) < 7)) ex += 7;
    usedX.push(ex);
    const baseY = painTop + painH;
    return `<line x1="${ex.toFixed(1)}" y1="${(p1Top + 12).toFixed(1)}" x2="${ex.toFixed(1)}" y2="${p2Bottom.toFixed(1)}"
        stroke="${e.color}" stroke-width="0.7" opacity="0.3" stroke-dasharray="2 2"/>
      <circle cx="${ex.toFixed(1)}" cy="${baseY.toFixed(1)}" r="2.4" fill="${e.color}"/>
      <text x="${(ex + 2).toFixed(1)}" y="${(baseY + 6).toFixed(1)}" font-size="6.4" fill="rgba(228,218,239,0.8)"
        transform="rotate(45 ${(ex + 2).toFixed(1)} ${(baseY + 6).toFixed(1)})">${esc(e.label)}</text>`;
  }).join("");

  // Carry the last known severity out to the end of the attack — the pain did
  // not stop at the final entry, that is just when logging stopped.
  const painLine = pain.length >= 2 && end.getTime() > pain[pain.length - 1].t.getTime()
    ? [...pain, { t: end, sev: pain[pain.length - 1].sev }]
    : pain;
  const painPath = painLine.length >= 2
    ? smoothPath(painLine.map((p) => ({ x: x(p.t), y: yPain(p.sev) })))
    : "";
  const painArea = painPath
    ? `${painPath} L${x(painLine[painLine.length - 1].t).toFixed(1)},${(painTop + painH).toFixed(1)}
       L${x(painLine[0].t).toFixed(1)},${(painTop + painH).toFixed(1)} Z`
    : "";

  // ── bottom panel: continuous metrics only, named on the line ──
  const series = d.metricSeries.filter((s) => metricKeys.includes(s.key));
  const colorFor = (sr: typeof series[number]) => sr.color ?? C.accent;
  const metricSvg = series.map((s) => {
    const pts = s.points
      .map((p) => ({ t: new Date(`${p.date}T12:00:00Z`), v: p.value }))
      .filter((p) => p.t >= winStart && p.t <= winEnd);
    if (pts.length < 2) return null;
    const vals = pts.map((p) => p.v);
    const min = Math.min(...vals), max = Math.max(...vals), range = max - min || 1;
    const yOf = (v: number) => metricTop + (1 - (v - min) / range) * metricH;
    const path = smoothPath(pts.map((p) => ({ x: x(p.t), y: yOf(p.v) })));
    // Each line is min-max scaled to its own range so metrics in different
    // units share one panel. That is only honest if the range is printed, so
    // the reader can decode the shape back into real numbers.
    const fmt = (v: number) => (Math.abs(v) >= 100 ? v.toFixed(0) : v.toFixed(1));
    return {
      key: s.key,
      firstX: x(pts[0].t),
      firstY: yOf(pts[0].v),
      path: `<path d="${path}" fill="none" stroke="${colorFor(s)}" stroke-width="3.2"
          opacity="0.14" stroke-linecap="round" stroke-linejoin="round"/>
        <path d="${path}" fill="none" stroke="${colorFor(s)}" stroke-width="1.3"
          opacity="0.95" stroke-linecap="round" stroke-linejoin="round"/>`,
      /** y of the nearest sample to a moment — used to pin a logged flag on
       *  the line it actually refers to. */
      yAtX: (px: number) => {
        const near = pts.reduce((best, p) =>
          Math.abs(x(p.t) - px) < Math.abs(x(best.t) - px) ? p : best, pts[0]);
        return yOf(near.v);
      },
      yAt: (t: Date) => {
        const near = pts.reduce((best, p) =>
          Math.abs(p.t.getTime() - t.getTime()) < Math.abs(best.t.getTime() - t.getTime()) ? p : best, pts[0]);
        return yOf(near.v);
      },
      lastY: yOf(pts[pts.length - 1].v),
      label: `${rt(s.label)}${s.unit ? ` (${s.unit})` : ""}`,
      range: `${fmt(min)}–${fmt(max)}`,
      color: colorFor(s),
    };
  }).filter((x) => x != null);

  const boxes: { x: number; y: number; w: number; h: number }[] = [];
  const hits = (b: { x: number; y: number; w: number; h: number }) =>
    boxes.some((o) => !(b.x + b.w < o.x || o.x + o.w < b.x || b.y + b.h < o.y || o.y + o.h < b.y));
  const metricLabels = metricSvg.filter((mm) => mm.path).map((mm) => {
    const text = mm.label!;
    const w = 6 + text.length * 3.05, h = 9;
    let box = null as null | { x: number; y: number; w: number; h: number };
    for (const frac of [0.08, 0.22, 0.36, 0.5, 0.62, 0.15, 0.29, 0.44, 0.56]) {
      const lx = padL + (W - padL - padR) * frac;
      const above = { x: lx - 2, y: Math.max(metricTop + 1, mm.yAtX(lx) - 11), w, h };
      if (lx + w < W - padR && !hits(above)) { box = above; break; }
      const below = { x: lx - 2, y: Math.min(metricTop + metricH - h, mm.yAtX(lx) + 3), w, h };
      if (lx + w < W - padR && !hits(below)) { box = below; break; }
    }
    if (!box) box = { x: padL, y: metricTop + 1, w, h };
    boxes.push(box);
    return `<rect x="${box.x.toFixed(1)}" y="${box.y.toFixed(1)}" width="${w.toFixed(1)}" height="${h}"
        rx="3" fill="rgba(27,11,43,0.78)"/>
      <text x="${(box.x + 3).toFixed(1)}" y="${(box.y + 6.6).toFixed(1)}" font-size="6.2" fill="${mm.color}">
        ${esc(text)}</text>`;
  }).join("");

  // Flags for logged items that ARE tracked data, drawn on their own line.
  const flagged = metricEvents.map((ev) => {
    const target = metricSvg.find((mm) => ev.keys.some((k) => mm.key === k));
    if (!target) return "";
    const fx = x(ev.at);
    const fy = target.yAt(ev.at);
    const text = ev.auto ? rt("{0} detected", ev.label) : rt("{0} logged", ev.label);
    const w = 6 + text.length * 3.05;
    const bx = Math.min(fx + 4, W - padR - w), by = fy - 11;
    boxes.push({ x: bx, y: by, w, h: 9 });
    return `<circle cx="${fx.toFixed(1)}" cy="${fy.toFixed(1)}" r="2.6" fill="${target.color}"
        stroke="#1B0B2B" stroke-width="0.8"/>
      <rect x="${bx.toFixed(1)}" y="${by.toFixed(1)}" width="${w.toFixed(1)}" height="9" rx="3"
        fill="rgba(27,11,43,0.78)"/>
      <text x="${(bx + 3).toFixed(1)}" y="${(by + 6.6).toFixed(1)}" font-size="6" fill="${target.color}">${esc(text)}</text>`;
  }).join("");

  const dayMs = 86_400_000;
  /** Days from onset: -3d, -2d, +1d. Onset itself is left blank, because the
   *  pink marker on the dashed onset line already names it and sits within a
   *  few pixels of the day-0 tick — labelling both ran the two words together
   *  and printed "onsetonset" on the axis. */
  const axisLabel = (t: Date) => {
    const days = Math.round((t.getTime() - start.getTime()) / dayMs);
    if (!hasMetrics) {
      const o = offset(start, t);
      return o === "at onset" ? "" : o;
    }
    if (days === 0) return "";
    return days > 0 ? `+${days}d` : `${days}d`;
  };
  const tickTimes: Date[] = [];
  if (hasMetrics) {
    for (let t = winStart.getTime(); t <= winEnd.getTime(); t += dayMs) tickTimes.push(new Date(t));
  } else {
    const stepMs = Math.max(3_600_000, Math.round(span / 6 / 3_600_000) * 3_600_000);
    for (let t = winStart.getTime(); t <= winEnd.getTime(); t += stepMs) tickTimes.push(new Date(t));
  }
  const stripes = tickTimes.slice(0, -1).map((t, i) => {
    if (i % 2 === 1) return "";
    const x1 = x(t), x2 = x(tickTimes[i + 1]);
    return `<rect x="${x1.toFixed(1)}" y="${(p1Top + 12).toFixed(1)}" width="${Math.max(0, x2 - x1).toFixed(1)}"
      height="${(p2Bottom - p1Top - 12).toFixed(1)}" fill="rgba(255,255,255,0.018)"/>`;
  }).join("");

  const ticks = tickTimes.map((t) =>
    `<line x1="${x(t).toFixed(1)}" y1="${(p1Top + 12).toFixed(1)}" x2="${x(t).toFixed(1)}" y2="${p2Bottom.toFixed(1)}"
        stroke="rgba(255,255,255,0.06)"/>
      <text x="${x(t).toFixed(1)}" y="${H - 4}" font-size="6" fill="rgba(167,148,189,0.75)" text-anchor="middle">
        ${esc(axisLabel(t))}</text>`);

  const panel = (top: number, bottom: number) =>
    `<rect x="${(padL - 6).toFixed(1)}" y="${top.toFixed(1)}" width="${(W - padL - 2).toFixed(1)}"
       height="${(bottom - top).toFixed(1)}" rx="8" fill="rgba(255,255,255,0.022)"/>`;

  const svg = `<svg class="curve" viewBox="0 0 ${W} ${H}" style="height:${H}px" preserveAspectRatio="none">
    ${panel(p1Top, p1Bottom)}
    ${hasMetrics ? panel(p2Top, p2Bottom) : ""}
    ${stripes}
    ${ticks.join("")}
    ${[0, 5, 10].map((sv) => `<line x1="${padL}" y1="${yPain(sv).toFixed(1)}" x2="${(W - padR).toFixed(1)}" y2="${yPain(sv).toFixed(1)}"
        stroke="rgba(255,255,255,0.05)"/>
      <text x="8" y="${(yPain(sv) + 2).toFixed(1)}" font-size="5.5" fill="rgba(167,148,189,0.75)">${sv}</text>`).join("")}
    <defs><linearGradient id="atk-${esc(m.id).slice(0, 8)}" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stop-color="${rgba(C.pink, .16)}"/>
      <stop offset="100%" stop-color="${rgba(C.pink, .04)}"/></linearGradient></defs>
    <rect x="${x(start).toFixed(1)}" y="${(p1Top + 12).toFixed(1)}"
      width="${Math.max(1.5, x(end) - x(start)).toFixed(1)}"
      height="${(p2Bottom - p1Top - 12).toFixed(1)}" rx="3" fill="url(#atk-${esc(m.id).slice(0, 8)})"/>
    ${painArea ? `<path d="${painArea}" fill="${rgba(C.pink, .18)}"/>` : ""}
    ${painPath ? `<path d="${painPath}" fill="none" stroke="${C.pink}" stroke-width="4"
        opacity="0.16" stroke-linecap="round" stroke-linejoin="round"/>
      <path d="${painPath}" fill="none" stroke="${C.pink}" stroke-width="1.9"
        stroke-linecap="round" stroke-linejoin="round"/>` : ""}
    ${pain.map((p) => `<circle cx="${x(p.t).toFixed(1)}" cy="${yPain(p.sev).toFixed(1)}" r="2.4" fill="${C.pink}"
        stroke="#1B0B2B" stroke-width="0.7"/>
      <text x="${(x(p.t)).toFixed(1)}" y="${(yPain(p.sev) - 5).toFixed(1)}" font-size="6.4" fill="${C.pink}"
        text-anchor="middle">${p.sev}</text>`).join("")}
    ${eventSvg}
    <text x="${(padL - 2).toFixed(1)}" y="${(p1Top + 8).toFixed(1)}" font-size="6"
      fill="rgba(167,148,189,0.7)" letter-spacing="0.7">${rt("THE ATTACK")}</text>
    ${metricSvg.length ? `<text x="${(padL - 2).toFixed(1)}" y="${(p2Top + 8).toFixed(1)}" font-size="6"
      fill="rgba(167,148,189,0.7)" letter-spacing="0.7">${rt("TRACKED DATA · each line scaled to its own range")}</text>` : ""}
    ${metricSvg.map((mm) => mm.path).join("")}
    ${flagged}
    ${metricLabels}
    <line x1="${x(start).toFixed(1)}" y1="${p1Top}" x2="${x(start).toFixed(1)}" y2="${p2Bottom}"
      stroke="${rgba(C.pink, .55)}" stroke-width="0.9" stroke-dasharray="2 3"/>
    <text x="${(x(start) + 3).toFixed(1)}" y="${(p2Bottom + 9).toFixed(1)}" font-size="6"
      fill="${rgba(C.pink, .9)}" letter-spacing="0.4">${rt("onset")}</text>
  </svg>`;

  return {
    svg,
    ranges: metricSvg.filter((mm) => mm.path)
      .map((mm) => ({ label: mm.label!, range: mm.range!, color: mm.color })),
  };
}

function attackLog(d: ReportData, startPage: number): { html: string; pages: number } {
  if (d.migraines.length === 0) return { html: "", pages: 0 };
  // Four attacks a page keeps a card from ever splitting across the break.
  const perPage = 4;
  const chunks: Migraine[][] = [];
  for (let i = 0; i < d.migraines.length; i += perPage) {
    chunks.push(d.migraines.slice(i, i + perPage));
  }
  const html = chunks.map((chunk, i) => page(C.pink, `
    ${i === 0 ? header(C.pink, ICON.log, rt("Attack log"),
      rt("Every attack in the period, in the order things happened")) : ""}
    ${chunk.map((m) => attackCard(m, d)).join("")}
  `, startPage + i)).join("");
  return { html, pages: chunks.length };
}

// ── frequency trends ─────────────────────────────────────────────
function frequency(d: ReportData, pageNo: number): string {
  // The old generator's floor: below three attacks a "trend" is noise.
  if (d.migraines.length < 3) return "";
  const days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
  const counts = new Array(7).fill(0);
  const byMonth = new Map<string, number>();
  const byWeek = new Map<string, number>();
  const durByMonth = new Map<string, number[]>();
  const sevByMonth = new Map<string, number[]>();
  // Meteorological seasons, hemisphere-agnostic labels as the app uses.
  const seasons = new Map<string, number>([["Winter", 0], ["Spring", 0], ["Summer", 0], ["Autumn", 0]]);

  for (const m of d.migraines) {
    const dt = parseDate(m.start_at);
    if (!dt) continue;
    counts[(dt.getDay() + 6) % 7]++;

    const mk = `${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, "0")}`;
    byMonth.set(mk, (byMonth.get(mk) ?? 0) + 1);

    const monday = new Date(dt);
    monday.setDate(dt.getDate() - ((dt.getDay() + 6) % 7));
    const wk = monday.toISOString().slice(0, 10);
    byWeek.set(wk, (byWeek.get(wk) ?? 0) + 1);

    const hrs = durationHours(m);
    if (hrs != null) durByMonth.set(mk, [...(durByMonth.get(mk) ?? []), hrs]);
    if (m.severity != null) sevByMonth.set(mk, [...(sevByMonth.get(mk) ?? []), m.severity]);

    const season = [11, 0, 1].includes(dt.getMonth()) ? "Winter"
      : [2, 3, 4].includes(dt.getMonth()) ? "Spring"
      : [5, 6, 7].includes(dt.getMonth()) ? "Summer" : "Autumn";
    seasons.set(season, (seasons.get(season) ?? 0) + 1);
  }

  const worst = days[counts.indexOf(Math.max(...counts))];
  const months = [...byMonth.entries()].sort();
  const weeks = [...byWeek.entries()].sort().slice(-12);
  const worstSeason = [...seasons.entries()].sort((a, b) => b[1] - a[1])[0];

  // Whether it is getting better or worse: first half of the months against
  // the second, which is the same comparison the app's trend chip makes.
  // Translations of these must keep the " — " separator: the stat tile below
  // splits the string on it to print headline and detail on separate lines.
  const trend = (() => {
    if (months.length < 4) return rt("Need at least 4 months for a trend");
    const half = Math.floor(months.length / 2);
    const early = months.slice(0, half).reduce((a, [, n]) => a + n, 0) / half;
    const late = months.slice(half).reduce((a, [, n]) => a + n, 0) / (months.length - half);
    const pct = early === 0 ? 0 : Math.round(((late - early) / early) * 100);
    if (Math.abs(pct) < 10) return rt("Stable — consistent frequency");
    return pct > 0 ? rt("Worsening — up {0}%", pct) : rt("Improving — down {0}%", Math.abs(pct));
  })();

  /** "2026-05" -> "May 26" — a month should read as a month. */
  const monthLabel = (key: string) => {
    const [y, mo] = key.split("-");
    const dt = new Date(Number(y), Number(mo) - 1, 1);
    return `${dt.toLocaleDateString(rtLocale, { month: "short" })} ${y.slice(2)}`;
  };

  // Charts alternate between two palettes — purple, then pink — and within a
  // chart each bar shades darker as its value grows toward the chart's max.
  const PURPLE_RAMP = ["#E3D7F4", "#CDB9EA", "#B39DDB", "#9575CD", "#7E57C2", "#5E35B1"];
  const PINK_RAMP = ["#FBD9E7", "#F9B8D2", "#FF97C0", "#FF7BB0", "#E85A97", "#C2417B"];
  let chartNo = -1;
  const shadeFor = (v: number, max: number): string => {
    const ramp = chartNo % 2 ? PINK_RAMP : PURPLE_RAMP;
    return ramp[Math.min(ramp.length - 1,
      Math.floor((v / Math.max(1, max)) * ramp.length))];
  };

  const cols = (entries: [string, number][], _color: string, fmt = (k: string) => k) => {
    chartNo += 1;
    const max = Math.max(1, ...entries.map(([, n]) => n));
    return `<div class="card"><div class="cols">
      ${entries.map(([k, n]) => `<div>
        <span>${n}</span>
        <i style="height:${Math.max(3, Math.round((n / max) * 62))}px;border-radius:3px 3px 0 0;
          background:${shadeFor(n, max)}"></i>
        <span>${esc(fmt(k))}</span></div>`).join("")}
    </div></div>`;
  };

  const avgCols = (src: Map<string, number[]>, _color: string, unit: string) => {
    const entries = [...src.entries()].sort()
      .map(([k, arr]) => [k, arr.reduce((a, b) => a + b, 0) / arr.length] as [string, number]);
    if (!entries.length) return "";
    chartNo += 1;
    const max = Math.max(1, ...entries.map(([, v]) => v));
    return `<div class="card"><div class="cols">
      ${entries.map(([k, v]) => `<div>
        <span>${v.toFixed(1)}${esc(unit)}</span>
        <i style="height:${Math.max(3, Math.round((v / max) * 62))}px;border-radius:3px 3px 0 0;
          background:${shadeFor(v, max)}"></i>
        <span>${esc(monthLabel(k))}</span></div>`).join("")}
    </div></div>`;
  };

  return page(C.pink, `
    ${header(C.pink, ICON.freq, rt("Frequency trends"),
      rt("How often attacks come, and which way that is moving"))}
    <div class="stats" style="margin-bottom:12px">
      <div class="stat"><b>${d.migraines.length}</b><span>${rt("Attacks in range")}</span></div>
      <div class="stat"><b>${esc(rt(worst))}</b><span>${rt("Worst day")}</span></div>
      <div class="stat"><b>${esc(worstSeason ? rt(worstSeason[0]) : "—")}</b><span>${rt("Worst season")}</span></div>
      <div class="stat"><b style="font-size:13px">${esc(trend.split(" — ")[0])}</b>
        <span>${esc(trend.includes(" — ") ? trend.split(" — ")[1] : rt("Trend"))}</span></div>
    </div>

    <div class="freq">
      <div>${sub(C.pink, rt("By day of week"))}
        ${cols(days.map((dn, i) => [dn, counts[i]] as [string, number]), "#CE93D8", (k) => rt(k))}</div>
      <div>${sub(C.pink, rt("Per month"))}
        ${cols(months, "#9575CD", monthLabel)}</div>
      ${weeks.length >= 3 ? `<div>${sub(C.pink, rt("Last {0} weeks", weeks.length))}
        ${weeks.length < 12
          ? `<p class="meta" style="margin:-4px 0 4px">${rt("Only {0} weeks of data in this record", weeks.length)}</p>`
          : ""}
        ${cols(weeks, "#B39DDB", (k) => k.slice(5))}</div>` : ""}
      <div>${sub(C.pink, rt("By season"))}
        ${[...seasons.values()].some((n) => n === 0)
          ? `<p class="meta" style="margin:-4px 0 4px">${rt("Seasons with no bar were not covered by this record")}</p>`
          : ""}
        ${cols([...seasons.entries()], "#7E57C2", (k) => rt(k))}</div>
      ${durByMonth.size ? `<div>${sub(C.pink, rt("Average duration per month"))}
        ${avgCols(durByMonth, "#D1C4E9", "h")}</div>` : ""}
      ${sevByMonth.size ? `<div>${sub(C.pink, rt("Average severity per month"))}
        ${avgCols(sevByMonth, "#5E35B1", "")}</div>` : ""}
    </div>
  `, pageNo);
}

// ── severity spread ──────────────────────────────────────────────
/** The 1–10 histogram from the old report (and Android's ReportPdfGenerator):
 *  the average alone hides whether attacks are steady fives or a mix of twos
 *  and nines, and that spread is what a clinician actually asks about. */
function severitySpread(d: ReportData, pageNo: number): string {
  const sevs = d.migraines.map((m) => m.severity)
    .filter((v): v is number => typeof v === "number" && v >= 1 && v <= 10);
  if (!sevs.length) return "";
  const counts = Array.from({ length: 10 }, (_, i) =>
    sevs.filter((v) => Math.round(v) === i + 1).length);
  const max = Math.max(...counts, 1);
  const avg = sevs.reduce((a, b) => a + b, 0) / sevs.length;

  return page(C.red, `
    ${header(C.red, ICON.chart, rt("Severity spread"),
      rt("How hard attacks hit, across the 1–10 scale"))}
    <div class="stats" style="margin-bottom:12px">
      <div class="stat"><b>${avg.toFixed(1)}</b><span>${rt("Average /10")}</span></div>
      <div class="stat"><b>${Math.min(...sevs)}</b><span>${rt("Mildest")}</span></div>
      <div class="stat"><b>${Math.max(...sevs)}</b><span>${rt("Worst")}</span></div>
      <div class="stat"><b>${sevs.length}</b><span>${rt("Attacks rated")}</span></div>
    </div>
    <div class="card"><div class="cols">
      ${counts.map((n, i) => `<div>
        <span>${n || ""}</span>
        <i style="height:${Math.max(3, Math.round((n / max) * 62))}px;
          background:${sevColor(i + 1)}"></i>
        <span>${i + 1}</span></div>`).join("")}
    </div></div>
  `, pageNo);
}

// ── what happened ────────────────────────────────────────────────
function contextLine(s: CorrelationStat): string {
  const lag = (s.best_lag_days ?? 0) > 0 ? rt("{0} days before", s.best_lag_days) : rt("same day");
  const pct = s.pct_migraine_windows != null ? `${rt("{0}% of migraines", Math.round(s.pct_migraine_windows))} · ` : "";
  return `${pct}${lag}`;
}

function statRow(s: CorrelationStat, color: string, name: string): string {
  const hits = s.sample_size && s.pct_migraine_windows != null
    ? rt("{0} of {1}", Math.round((s.pct_migraine_windows / 100) * s.sample_size), s.sample_size)
    : `${s.lift_ratio.toFixed(1)}×`;
  return `<div class="card row">
    <div class="grow">
      <div class="name">${name}</div>
      <div class="meta">${esc(contextLine(s))}</div>
    </div>
    ${dots(color, s.p_value)}
    ${badge(color, hits)}
  </div>`;
}

// The correlation engine and the treatment leaderboard work on the whole
// record, not the report window. On a filtered report their findings are
// split into two labelled groups instead of pretending to be period-scoped.

/** True when the report covers a slice of the record rather than everything —
 *  the case where full-history statistics need labelling. */
const isScoped = (d: ReportData): boolean =>
  !!(d.params.from || d.params.to || d.params.episodeIds);

/** "Seen in this period" test: normalised names logged on the report's own
 *  attacks, plus the labels of metrics with data inside the window. Same
 *  normalisation as the auto-metric lookup, so grouped labels match. */
function namesInPeriod(d: ReportData): (raw: unknown) => boolean {
  const norm = (v: unknown) => String(v ?? "").toLowerCase().replace(/:/g, "").trim()
    .replace(/\s+(low|high|short|long|late|early|many|few)\b.*/, "").trim();
  const set = new Set<string>();
  const lists: ChildRow[][] = [d.triggers, d.prodromes, d.medicines, d.reliefs,
    d.activities, d.locations, d.missedActivities];
  for (const list of lists) {
    for (const r of list) {
      const n = norm(r.name ?? r.type);
      if (n) set.add(n);
    }
  }
  for (const s of d.symptoms) {
    const n = norm(s.type);
    if (n) set.add(n);
  }
  // Metric-backed findings count as seen when the metric has data in the
  // report window — metricSeries only ever covers that window.
  const withData = new Set(d.metricSeries.map((s) => s.key));
  for (const [label, keys] of Object.entries(d.labelToMetricKeys)) {
    if (keys.some((k) => withData.has(k))) set.add(label);
  }
  for (const def of METRICS) if (withData.has(def.key)) set.add(norm(def.label));
  return (raw) => set.has(norm(raw));
}

const split = <T>(rows: T[], f: (r: T) => boolean): [T[], T[]] =>
  [rows.filter(f), rows.filter((r) => !f(r))];

/** The line under a section title that says where all-time numbers come from. */
const historyNote = (text: string): string =>
  `<p class="meta" style="margin:-4px 0 10px">${esc(text)}</p>`;

/** One labelled half of the period/history split — omitted entirely when it
 *  has nothing to show. */
function group(color: string, title: string, note: string, body: string, count: number): string {
  if (!count) return "";
  return `${sub(color, title)}
    ${note ? `<p class="meta" style="margin:-4px 0 8px">${esc(note)}</p>` : ""}
    ${body}`;
}

// Functions, not consts: a module-level string would capture the identity
// translator at import time and render English forever (same trap as
// NARRATIVE_DEFS titles).
const SEEN_NOTE = () => rt("Established patterns that also appeared in the attacks in this report.");
const HISTORY_ONLY_NOTE = () => rt("Statistically established patterns that did not appear in this report's time frame.");
const STATS_NOTE = () => rt("Patterns are established across your full logging history, where there is enough data for statistical confidence.");

// A combination row is only meaningful when it names two DIFFERENT things. The
// engine no longer emits self-pairs, but rows written before that fix are still
// stored, so the report checks this before printing "X + X". Compared on the
// trimmed, case-folded label because both halves are display labels, not ids.
function realCombo(s: CorrelationStat): boolean {
  if (!s.factor_b) return false;
  const norm = (t: string) => t.trim().replace(/\s+/g, " ").toLowerCase();
  return norm(s.factor_b) !== norm(s.factor_name);
}

function whatHappened(d: ReportData, pageNo: number): string {
  // Engine-gated rows carry a mode ("comparison" | "prevalence") in
  // lag_details and are already vetted by the 3-step gate — show them
  // directly, the old client's hasGateMode rule. Older, untagged rows fall
  // back to the p/lift cut.
  const gated = (s: CorrelationStat) => typeof s.lag_details?.["mode"] === "string";
  const sig = (s: CorrelationStat) => gated(s) || (s.p_value <= 0.1 && s.lift_ratio > 1);
  const patterns = d.correlations
    .filter((s) => ["trigger", "metric", "prodrome"].includes(s.factor_type) && !s.symptom_outcome && sig(s))
    .sort((a, b) => b.lift_ratio - a.lift_ratio).slice(0, 8);
  const combos = d.correlations
    .filter((s) => s.factor_type === "interaction" && sig(s) && realCombo(s))
    .sort((a, b) => b.lift_ratio - a.lift_ratio).slice(0, 6);

  // Only links that actually moved the needle. A 0.9x row is noise dressed
  // as a finding, and printing dozens of them buried the real ones.
  const profiles = d.correlations
    .filter((s) => s.symptom_outcome && s.lift_ratio >= 1.2 && s.p_value <= 0.1)
    .sort((a, b) => b.lift_ratio - a.lift_ratio).slice(0, 8);
  const dropped = d.correlations.filter((s) => s.symptom_outcome).length - profiles.length;

  if (!patterns.length && !combos.length && !profiles.length) return "";

  const lists = (pat: CorrelationStat[], com: CorrelationStat[], pro: CorrelationStat[]) => `
    ${pat.length ? sub(C.accent, rt("Patterns")) : ""}
    ${pat.map((s) => statRow(s, C.accent, esc(pretty(s.factor_name)))).join("")}

    ${com.length ? sub(C.red, rt("Combinations")) : ""}
    ${com.map((s) => statRow(s, C.red,
      `${esc(pretty(s.factor_name))} <span style="color:${C.red};font-weight:700">+</span> ${esc(pretty(s.factor_b))}`
    )).join("")}

    ${pro.length ? sub(C.orange, rt("What these triggers do to you")) : ""}
    ${pro.map((s) => {
      const cond = Math.round(Number(s.lag_details?.["conditional_pct"] ?? s.pct_migraine_windows ?? 0));
      const base = Math.round(Number(s.lag_details?.["baseline_pct"] ?? s.pct_control_windows ?? 0));
      return `<div class="card row">
        <div class="grow">
          <div class="name">${esc(pretty(s.factor_name))}
            <span style="color:${C.orange};font-weight:700">→</span> ${esc(pretty(s.symptom_outcome))}</div>
          <div class="meta">${rt("{0}% of attacks with it vs {1}% without", cond, base)}</div>
        </div>
        ${badge(C.orange, `${s.lift_ratio.toFixed(1)}×`)}
      </div>`;
    }).join("")}
  `;

  const scoped = isScoped(d);
  let body: string;
  if (scoped) {
    const has = namesInPeriod(d);
    const inPeriod = (s: CorrelationStat) => has(s.factor_name) && (!s.factor_b || has(s.factor_b));
    const [pNow, pHist] = split(patterns, inPeriod);
    const [cNow, cHist] = split(combos, inPeriod);
    const [oNow, oHist] = split(profiles, inPeriod);
    body = group(C.accent, rt("Seen in this period"), SEEN_NOTE(),
        lists(pNow, cNow, oNow), pNow.length + cNow.length + oNow.length)
      + group(C.muted, rt("From your full history"), HISTORY_ONLY_NOTE(),
        lists(pHist, cHist, oHist), pHist.length + cHist.length + oHist.length);
  } else {
    body = lists(patterns, combos, profiles);
  }

  return page(C.accent, `
    ${header(C.accent, ICON.search, rt("What Happened"),
      rt("The factors that showed up before attacks, ranked by strength"), !scoped)}
    ${scoped ? historyNote(STATS_NOTE()) : ""}
    <div class="card row" style="padding:7px 12px">
      <span class="meta" style="margin:0">${rt("Confidence")}</span>
      ${dots(C.accent, 0.001)}<span class="meta" style="margin:0">${rt("strong")}</span>
      ${dots(C.accent, 0.03)}<span class="meta" style="margin:0">${rt("likely")}</span>
      ${dots(C.accent, 0.09)}<span class="meta" style="margin:0">${rt("possible")}</span>
    </div>
    ${body}
    ${dropped > 0
      ? `<p class="meta">${rt("{0} weaker links are not shown — they sit inside normal variation.", dropped)}</p>`
      : ""}
  `, pageNo);
}

// ── what worked ──────────────────────────────────────────────────
function whatWorked(d: ReportData, pageNo: number): string {
  const treatments = d.correlations
    .filter((s) => s.factor_type === "treatment" && s.lift_ratio > 1.2)
    .sort((a, b) => b.lift_ratio - a.lift_ratio).slice(0, 8);
  const combos = d.correlations
    .filter((s) => s.factor_type === "treatment_interaction" && s.lift_ratio > 1.2 && realCombo(s))
    .sort((a, b) => b.lift_ratio - a.lift_ratio).slice(0, 5);
  if (!treatments.length && !combos.length && !d.treatmentTiming.length) return "";

  const delay = (mins: number) => {
    if (mins < 90) return rt("{0} minutes", mins);
    const h = mins / 60;
    return h >= 10 ? rt("{0} hours", Math.round(h)) : rt("{0} hours", h.toFixed(1).replace(/\.0$/, ""));
  };

  const lists = (tr: CorrelationStat[], co: CorrelationStat[], ti: ReportData["treatmentTiming"]) => `
    ${tr.map((s) => statRow(s, C.green, esc(pretty(s.factor_name)))).join("")}

    ${co.length ? sub(C.green, rt("Works best when")) : ""}
    ${co.map((s) => statRow(s, C.green,
      `${esc(pretty(s.factor_name))} <span style="color:${C.green};font-weight:700">+</span> ${esc(pretty(s.factor_b))}`
    )).join("")}

    ${ti.length ? sub(C.blue, rt("Treatment timing")) : ""}
    ${ti.length
      ? `<p class="meta" style="margin:-4px 0 8px">${rt("Peak severity when treated earlier vs later, in this patient's own logged attacks. Correlation, not causation.")}</p>`
      : ""}
    ${ti.map((t) => `<div class="card">
      <div class="log-head">
        <span class="date">${esc(pretty(t.treatment_name))}</span>
        ${badge(C.blue, rt("{0} points milder", (t.late_avg_peak - t.early_avg_peak).toFixed(1)))}
      </div>
      <table class="log">
        <tr><td class="k" style="color:${C.green}">${esc(rt("Within {0}", delay(t.cutoff_minutes)))}</td>
            <td class="o">n = ${t.early_count}</td>
            <td class="v">${bar(C.green, t.early_avg_peak * 10)}
              <div class="meta">${rt("{0}/10 average peak", t.early_avg_peak.toFixed(1))}</div></td></tr>
        <tr><td class="k" style="color:${C.red}">${esc(rt("After {0}", delay(t.cutoff_minutes)))}</td>
            <td class="o">n = ${t.late_count}</td>
            <td class="v">${bar(C.red, t.late_avg_peak * 10)}
              <div class="meta">${rt("{0}/10 average peak", t.late_avg_peak.toFixed(1))}</div></td></tr>
      </table>
    </div>`).join("")}
  `;

  const scoped = isScoped(d);
  let body: string;
  if (scoped) {
    const has = namesInPeriod(d);
    const inPeriod = (s: CorrelationStat) => has(s.factor_name) && (!s.factor_b || has(s.factor_b));
    const [tNow, tHist] = split(treatments, inPeriod);
    const [cNow, cHist] = split(combos, inPeriod);
    const [iNow, iHist] = split(d.treatmentTiming, (t) => has(t.treatment_name));
    body = group(C.green, rt("Seen in this period"), SEEN_NOTE(),
        lists(tNow, cNow, iNow), tNow.length + cNow.length + iNow.length)
      + group(C.muted, rt("From your full history"), HISTORY_ONLY_NOTE(),
        lists(tHist, cHist, iHist), tHist.length + cHist.length + iHist.length);
  } else {
    body = lists(treatments, combos, d.treatmentTiming);
  }

  return page(C.green, `
    ${header(C.green, ICON.check, rt("What Worked"),
      rt("Shorter = reduced duration · milder = reduced severity"), !scoped)}
    ${scoped ? historyNote(STATS_NOTE()) : ""}
    ${body}
  `, pageNo);
}

// ── usage vs effectiveness ───────────────────────────────────────
/** Per relief category, how often it was reached for against how much it
 *  actually helped — the old report's port of Android's dual spider, as two
 *  bars per row because a two-series radar reads worse in print. */
function usageVsEffectiveness(d: ReportData, pageNo: number): string {
  if (!d.reliefs.length) return "";
  // NONE/LOW/MILD/HIGH is the reliefs.relief_scale set the apps write.
  const SCORE: Record<string, number> = { NONE: 0, LOW: 1, MILD: 2, HIGH: 3 };
  const cats = d.poolCategories["relief"] ?? {};
  const byCat = new Map<string, { count: number; scored: number[] }>();
  for (const r of d.reliefs) {
    const name = r.type ?? r.name;
    if (!name) continue;
    const cat = pretty(cats[name.toLowerCase()] ?? "Other");
    const entry = byCat.get(cat) ?? { count: 0, scored: [] };
    entry.count += 1;
    const sc = typeof r.relief_scale === "string" ? SCORE[r.relief_scale] : undefined;
    if (sc != null) entry.scored.push(sc);
    byCat.set(cat, entry);
  }
  const rows = [...byCat.entries()].map(([cat, v]) => ({
    cat, count: v.count,
    avg: v.scored.length ? v.scored.reduce((a, b) => a + b, 0) / v.scored.length : null,
  })).sort((a, b) => b.count - a.count);
  if (!rows.length) return "";
  const maxCount = Math.max(...rows.map((r) => r.count), 1);

  return page(C.green, `
    ${header(C.green, ICON.chart, rt("Usage vs effectiveness"),
      rt("How often each kind of relief was used, against how much it actually helped"))}
    ${rows.map((r) => `<div class="card row">
      <div class="grow">
        <div class="name">${esc(rt(r.cat))}</div>
        <div class="meta">${esc(rt("Used {0}×", r.count))} ·
          ${esc(r.avg == null ? rt("no relief ratings") : rt("avg relief {0} / 3", r.avg.toFixed(1)))}</div>
      </div>
      <div style="width:110px">${bar(C.green, (r.count / maxCount) * 100)}</div>
      <div style="width:110px">${bar(C.blue, r.avg == null ? 0 : (r.avg / 3) * 100)}</div>
    </div>`).join("")}
    <div class="card row" style="padding:7px 12px">
      <span class="meta" style="margin:0">${badge(C.green, rt("times used"))}
        ${badge(C.blue, rt("average relief, 0–3"))}</span>
    </div>
  `, pageNo);
}

// ── pain response ────────────────────────────────────────────────
// Rows come straight from intraday_response_stats — every one already passed
// the engine's honesty gates (>= 5 occurrences with a pain reading on both
// sides, |median effect| >= 1.0 pain points, >= 60% sign consistency), so
// this section never decides what is worth saying. Aggravators only ever
// describe rises; an easer whose pain consistently ROSE arrives flagged
// caution = true and is marked in amber, never hidden. No rows, no section —
// the report does not fabricate.
function painResponse(d: ReportData, pageNo: number): string {
  const horizon = (m: number) => (m % 60 === 0 ? `~${m / 60}h` : `~${m}m`);
  const byStrength = (rows: ReportData["intradayResponse"]) =>
    [...rows].sort((a, b) => Math.abs(b.median_effect) - Math.abs(a.median_effect));
  const agg = byStrength(d.intradayResponse.filter((r) => r.event_kind === "aggravator"));
  const eas = byStrength(d.intradayResponse.filter((r) => r.event_kind === "easer"));
  if (!agg.length && !eas.length) return "";

  const row = (r: ReportData["intradayResponse"][number], color: string) => {
    const effect = (r.median_effect > 0 ? "+" : "−") + Math.abs(r.median_effect).toFixed(1);
    const meta = rt("n = {0} · {1}% moved the same way", r.n, Math.round(r.consistency * 100));
    return `<div class="card row">
      <div class="grow">
        <div class="name">${esc(pretty(r.label))}</div>
        <div class="meta">${esc(meta)}${r.caution
          ? ` · <b style="color:${C.orange}">${esc(rt("pain rose after this"))}</b>` : ""}</div>
      </div>
      ${dots(color, r.p_value)}
      ${badge(color, rt("{0} within {1}", effect, horizon(r.horizon_minutes)))}
    </div>`;
  };

  return page(C.purple, `
    ${header(C.purple, ICON.pulse, rt("Pain response"),
      rt("Median change in pain after each logged item, against the 3 hours before it"), true)}
    ${agg.length ? sub(C.red, rt("Pain response — what pushed it up")) : ""}
    ${agg.map((r) => row(r, C.red)).join("")}
    ${eas.length ? sub(C.green, rt("Pain response — what brought it down")) : ""}
    ${eas.map((r) => row(r, r.caution ? C.orange : C.green)).join("")}
  `, pageNo);
}

// ── what's helping ───────────────────────────────────────────────
function whatsHelping(d: ReportData, pageNo: number): string {
  const direct = d.correlations.filter((s) => s.factor_type === "well_done")
    .sort((a, b) => b.lift_ratio - a.lift_ratio).slice(0, 10);
  const chains = d.correlations.filter((s) => s.factor_type === "well_done_chain")
    .sort((a, b) => b.lift_ratio - a.lift_ratio).slice(0, 10);
  if (!direct.length && !chains.length) return "";

  const lists = (dir: CorrelationStat[], ch: CorrelationStat[]) => `
    ${dir.map((s) => `<div class="card row">
      <div class="grow">
        <div class="name">${esc(pretty(s.factor_name))}</div>
        <div class="meta">${rt("Present on {0}% of migraine-free days", Math.round(s.pct_control_windows ?? 0))}</div>
      </div>
      ${badge(C.green, `${Math.round(s.pct_control_windows ?? 0)}%`)}
    </div>`).join("")}

    ${ch.length ? sub(C.green, rt("What drives it")) : ""}
    ${ch.map((s) => `<div class="card row">
      <div class="grow">
        <div class="name">${esc(pretty(s.factor_name))}
          <span style="color:${C.green};font-weight:700">→</span> ${esc(rt("steady {0}", pretty(s.factor_b).toLowerCase()))}</div>
        <div class="meta">${rt("{0}% with it · {1}% without",
          Math.round(s.pct_control_windows ?? 0), Math.round(s.pct_migraine_windows ?? 0))}</div>
      </div>
    </div>`).join("")}
  `;

  const scoped = isScoped(d);
  let body: string;
  if (scoped) {
    const has = namesInPeriod(d);
    const inPeriod = (s: CorrelationStat) => has(s.factor_name) && (!s.factor_b || has(s.factor_b));
    const [dNow, dHist] = split(direct, inPeriod);
    const [cNow, cHist] = split(chains, inPeriod);
    body = group(C.green, rt("Seen in this period"), SEEN_NOTE(),
        lists(dNow, cNow), dNow.length + cNow.length)
      + group(C.muted, rt("From your full history"), HISTORY_ONLY_NOTE(),
        lists(dHist, cHist), dHist.length + cHist.length);
  } else {
    body = lists(direct, chains);
  }

  return page(C.green, `
    ${header(C.green, ICON.leaf, rt("What's Helping"),
      rt("Habits that show up on migraine-free days, and what drives them"), !scoped)}
    ${scoped ? historyNote(STATS_NOTE()) : ""}
    ${body}
  `, pageNo);
}

// ── context ──────────────────────────────────────────────────────
function contextPage(d: ReportData, pageNo: number): string {
  const tally = (rows: ChildRow[]) => {
    const map = new Map<string, Set<string>>();
    for (const r of rows) {
      const name = r.type ?? r.name;
      if (!name || !r.migraine_id) continue;
      const set = map.get(name) ?? new Set<string>();
      set.add(r.migraine_id);
      map.set(name, set);
    }
    return [...map.entries()].map(([k, v]) => ({ name: k, n: v.size }))
      .sort((a, b) => b.n - a.n);
  };
  const acts = tally(d.activities);
  const locs = tally(d.locations);
  const missed = tally(d.missedActivities);
  if (!acts.length && !locs.length && !missed.length) return "";
  const total = Math.max(1, d.migraines.length);

  const rows = (list: { name: string; n: number }[], color: string) =>
    list.slice(0, 6).map((x) => `<div class="card row">
      <div class="grow">
        <div class="name">${esc(pretty(x.name))}</div>
        <div class="meta">${x.n === 1
          ? rt("1 migraine ({0}%)", Math.round((x.n / total) * 100))
          : rt("{0} migraines ({1}%)", x.n, Math.round((x.n / total) * 100))}</div>
      </div>
      <div style="width:120px">${bar(color, (x.n / total) * 100)}</div>
    </div>`).join("");

  return page(C.orange, `
    ${header(C.orange, ICON.run, rt("What Were You Doing"),
      rt("Activities and locations during attacks"))}
    ${locs.length ? sub(C.orange, rt("Where")) : ""}${rows(locs, C.orange)}
    ${acts.length ? sub(C.orange, rt("Doing what")) : ""}${rows(acts, C.orange)}
    ${missed.length ? sub(C.red, rt("What you missed")) : ""}${rows(missed, C.red)}
  `, pageNo);
}

// ── impact: severity, pain map, migration, aura ──────────────────
function impact(d: ReportData, pageNo: number): string {
  const migs = d.migraines;
  if (!migs.length) return "";
  const total = migs.length;

  const locCount = new Map<string, number>();
  for (const m of migs) for (const l of new Set(m.pain_locations ?? [])) {
    locCount.set(l, (locCount.get(l) ?? 0) + 1);
  }
  const locs = [...locCount.entries()].sort((a, b) => b[1] - a[1]);

  const dot = (id: string, n: number) => {
    const p = PAIN_POINTS[id];
    if (!p) return "";
    const pct = n / total;
    return `<span class="dot" style="left:${(p.x * 100).toFixed(1)}%;top:${(p.y * 100).toFixed(1)}%;
      background:${rgba(C.red, Math.max(.15, pct * .9))}"><b>${Math.round(pct * 100)}%</b></span>`;
  };

  const auraAttacks = migs.filter((m) => (m.aura_locations ?? []).length > 0);
  const zoneCount = new Map<string, number>();
  for (const m of auraAttacks) for (const z of new Set(m.aura_locations ?? [])) {
    zoneCount.set(z, (zoneCount.get(z) ?? 0) + 1);
  }
  const auraDurs = migs.map((m) => m.aura_duration_minutes)
    .filter((x): x is number => typeof x === "number" && x > 0);
  const avgAura = auraDurs.length
    ? Math.round(auraDurs.reduce((a, b) => a + b, 0) / auraDurs.length) : null;

  const eyePct = (zone: string) => {
    const n = zoneCount.get(zone) ?? 0;
    return auraAttacks.length ? (n / auraAttacks.length) * 100 : 0;
  };

  const pm = d.painMigration;
  const peak = pm?.median_minutes_to_peak;
  const peakText = peak == null ? null
    : peak < 90 ? rt("{0} minutes", peak) : rt("{0} hours", (peak / 60).toFixed(1).replace(/\.0$/, ""));

  return page(C.purple, `
    ${header(C.purple, ICON.pin, rt("How Did It Impact You"),
      rt("Severity, where the pain sat, and what it cost"))}

    ${sub(C.purple, rt("Pain locations"))}
    <div class="card">
      <div class="heads">
        <figure>
          <img src="${HEAD_FRONT_PNG}" alt="">
          ${locs.filter(([id]) => PAIN_POINTS[id]?.view !== "back").map(([id, n]) => dot(id, n)).join("")}
          <figcaption>${rt("Front")}</figcaption>
        </figure>
        <figure>
          <img src="${HEAD_BACK_PNG}" alt="">
          ${locs.filter(([id]) => PAIN_POINTS[id]?.view !== "front").map(([id, n]) => dot(id, n)).join("")}
          <figcaption>${rt("Back")}</figcaption>
        </figure>
        <table class="log" style="align-self:center">
          ${locs.slice(0, 6).map(([id, n]) => `<tr>
            <td class="k">${esc(rt(PAIN_LABELS[id] ?? pretty(id)))}</td>
            <td class="o">${n}</td>
            <td class="v">${bar(C.red, (n / total) * 100)}</td></tr>`).join("")}
        </table>
      </div>
    </div>

    ${pm ? sub(C.purple, rt("How the pain moves")) : ""}
    ${pm ? `<p class="meta" style="margin:-4px 0 8px">
        ${rt("Across {0} attacks with more than one timed pain entry, from your full logging history", pm.attacks_analysed)}</p>
      <div class="card">
        <div class="heads">
          ${(["front", "back"] as const).map((view) => {
            const img = view === "front" ? HEAD_FRONT_PNG : HEAD_BACK_PNG;
            const show = (ids: string[]) => ids.filter((id) => {
              const pt = PAIN_POINTS[id];
              return pt && (pt.view === "both" || pt.view === view);
            });
            const onset = show(pm.onset_locations);
            const spread = show(pm.spread_locations);
            if (!onset.length && !spread.length) return "";
            // Stage on the body rather than as a list of names: where it
            // starts in one colour, where it goes in another.
            const pin = (id: string, fill: string, label: string) => {
              const pt = PAIN_POINTS[id];
              return `<span class="dot" style="left:${(pt.x * 100).toFixed(1)}%;top:${(pt.y * 100).toFixed(1)}%;
                background:${fill};border-color:${rgba(C.pink, .9)}"><b>${label}</b></span>`;
            };
            return `<figure>
              <img src="${img}" alt="">
              ${onset.map((id) => pin(id, rgba(C.pink, .38), "1")).join("")}
              ${spread.map((id) => pin(id, rgba(C.pink, .95), "2")).join("")}
              <figcaption>${view === "front" ? rt("Front") : rt("Back")}</figcaption>
            </figure>`;
          }).join("")}
          <table class="log" style="align-self:center">
            <tr><td class="k" style="color:${rgba(C.pink, .7)}">1 · ${rt("Starts")}</td><td class="v">
              ${esc(pm.onset_locations.map((l) => rt(PAIN_LABELS[l] ?? pretty(l))).join(", ") || "—")}</td></tr>
            <tr><td class="k" style="color:${C.pink}">2 · ${rt("Spreads")}</td><td class="v">
              ${esc(pm.spread_locations.map((l) => rt(PAIN_LABELS[l] ?? pretty(l))).join(", ") || "—")}</td></tr>
            ${peakText ? `<tr><td class="k">${rt("Peaks after")}</td><td class="v">${esc(rt("about {0}", peakText))}</td></tr>` : ""}
          </table>
        </div>
      </div>` : ""}

    ${auraAttacks.length ? sub(C.purple, rt("Aura")) : ""}
    ${auraAttacks.length ? `<p class="meta" style="margin:-4px 0 8px">
        ${rt("Across {0} attacks with aura, from the patient's own point of view", auraAttacks.length)}</p>
      <div class="card">
        <div class="eyes">${auraEye("left", eyePct, { width: 150, showPct: true, label: rt("Left eye") })}${auraEye("right", eyePct, { width: 150, showPct: true, label: rt("Right eye") })}</div>
        <table class="log" style="margin-top:8px">
          ${avgAura != null ? `<tr><td class="k">${rt("Duration")}</td><td class="o">${avgAura}m</td>
            <td class="v">${rt("average (n = {0}) · shortest {1}m · longest {2}m",
              auraDurs.length, Math.min(...auraDurs), Math.max(...auraDurs))}</td></tr>` : ""}
        </table>
      </div>` : ""}
  `, pageNo);
}

// ── recommendations ──────────────────────────────────────────────
function recommendations(d: ReportData, pageNo: number): string {
  if (!d.recommendations.length) return "";
  const card = (r: { title: string; body: string }) => `<div class="card">
      <div class="name">${esc(r.title)}</div>
      <div class="meta" style="line-height:1.5">${esc(r.body)}</div>
    </div>`;
  const picked = d.recommendations.slice(0, 8);
  if (!isScoped(d)) {
    return page(C.purple, `
      ${header(C.purple, ICON.bulb, rt("Recommendations"),
      rt("Built from this patient's own patterns, reviewed monthly"), true)}
      ${picked.map(card).join("")}
    `, pageNo);
  }
  const seen = namesInPeriod(d);
  const [inPeriod, historyOnly] = split(picked, (r) => seen(r.title));
  return page(C.purple, `
    ${header(C.purple, ICON.bulb, rt("Recommendations"),
      rt("Built from this patient's own patterns, reviewed monthly"))}
    ${historyNote(STATS_NOTE())}
    ${group(C.purple, rt("Seen in this period"), SEEN_NOTE(),
      inPeriod.map(card).join(""), inPeriod.length)}
    ${group(C.slate, rt("From your full history"), HISTORY_ONLY_NOTE(),
      historyOnly.map(card).join(""), historyOnly.length)}
  `, pageNo);
}

// ── treatments ───────────────────────────────────────────────────
function treatments(d: ReportData, pageNo: number): string {
  if (!d.treatments.length) return "";
  const winFrom = parseDate(d.params.from ?? null);
  const winTo = parseDate(d.params.to ?? null);
  const scoped = !!(winFrom || winTo);
  // A regimen is "active during this period" when its span
  // [start_date, stop_date ?? still running] overlaps the report window —
  // the old generator's rule. The rest still render, under their own heading,
  // because effectiveness is an all-time number either way.
  const overlaps = (t: Record<string, unknown>): boolean => {
    const s = parseDate(String(t.start_date ?? ""));
    const e = t.stop_date ? parseDate(String(t.stop_date)) : null;
    if (winTo && s && s.getTime() >= winTo.getTime()) return false;
    if (winFrom && e && e.getTime() < winFrom.getTime()) return false;
    return true;
  };
  const [active, others] = scoped ? split(d.treatments, overlaps) : [d.treatments, []];
  const bandLabel: Record<string, string> = {
    working_well: "Working well",
    showing_progress: "Showing progress",
    some_effect: "Some effect",
    not_noticeable: "Not noticeable yet",
  };
  const card = (t: Record<string, unknown>): string => {
      const id = String(t.regimen_id ?? "");
      const pct = t.pct_change_mmd == null ? null : Number(t.pct_change_mmd);
      const band = String(t.band ?? "");
      // The leaderboard RPC speaks kind/amount/frequency; older shapes said
      // category/dose. Take whichever is there.
      const seLogs = (d.sideEffects[id] ?? []).slice(0, 8);
      return `<div class="card">
        <div class="log-head">
          <span class="date">${esc(pretty(t.name ?? t.treatment_name))}</span>
          ${badge(pct != null && pct < 0 ? C.green : C.muted,
            bandLabel[band] ? rt(bandLabel[band])
              : (pct != null ? `${pct > 0 ? "+" : ""}${pct.toFixed(0)}%` : rt("Not enough data")))}
        </div>
        <div class="meta">${esc([t.kind ?? t.category, t.amount ?? t.dose, t.frequency].filter(Boolean).join(" · "))}
          ${t.start_date ? esc(rt("· started {0}", String(t.start_date))) : ""}</div>
        ${d.narratives[id] ? `<div class="meta" style="line-height:1.5;margin-top:5px">${esc(d.narratives[id])}</div>` : ""}
        ${seLogs.length ? `<div class="meta" style="color:${C.pink};margin-top:5px">${rt("Side effects")}</div>
          <table class="log">
            ${seLogs.map((log) => {
              const pills = (Array.isArray(log.selected_symptoms) ? log.selected_symptoms : [])
                .map((sym) => pretty(sym)).join(", ");
              const note = String(log.notes ?? "").trim();
              return `<tr><td class="k">${esc(String(log.log_date ?? ""))}</td>
                <td class="v">${esc(note || pills)}</td></tr>`;
            }).join("")}
          </table>` : ""}
      </div>`;
  };

  return page(C.blue, `
    ${header(C.blue, ICON.pill, rt("Treatments"),
      rt("Per-regimen efficacy, baseline vs the most recent 28 days"), !scoped)}
    ${scoped
      ? historyNote(rt("Effectiveness is measured over each treatment's full duration, not just this report's window."))
        + group(C.blue, rt("Active during this period"), "", active.map(card).join(""), active.length)
        + group(C.muted, rt("Other treatments"), "", others.map(card).join(""), others.length)
      : d.treatments.map(card).join("")}
  `, pageNo);
}

// ── lifestyle context ────────────────────────────────────────────
/** The old Lifestyle Context page, rebuilt: mean daily habits over this
 *  report's window against the equal period before it (last 30 vs prior 30
 *  on an all-time report). Date range only — attack filters do not move
 *  these numbers, and the caption says so. */
function lifestyleBlock(d: ReportData): string {
  if (!d.lifestyle.length) return "";
  const fromD = parseDate(d.params.from ?? null);
  const toD = parseDate(d.params.to ?? null);
  // Same rule as the data loader: a usable window needs both bounds, ordered.
  const scoped = !!(fromD && toD && toD.getTime() > fromD.getTime());
  const windowDays = scoped
    ? Math.max(1, Math.round((toD!.getTime() - fromD!.getTime()) / 86_400_000))
    : 30;
  const caption = (scoped
    ? rt("Averages over this report's period compared with the equal period before it.")
    : rt("Averages over the last 30 days compared with the 30 days before them."))
    + " " + rt("Based on the date range only; attack filters do not change these numbers.")
    + (scoped && windowDays < 7 ? " " + rt("Short periods are noisy; interpret with care.") : "");

  const defOf = new Map(METRICS.map((def) => [def.key, def]));
  // Colour only where the direction is clinically unambiguous. Bedtime and
  // the rest stay neutral: later is not automatically worse.
  const GOOD_UP = new Set(["sleep_dur", "sleep_score", "hydration", "steps",
    "hrv", "mindfulness", "recovery"]);
  const GOOD_DOWN = new Set(["stress", "rhr", "late_screen", "alcohol",
    "tyramine", "screen_time"]);

  const clock = (v: number): string => {
    const h = ((v % 24) + 24) % 24;
    let hh = Math.floor(h), mm = Math.round((h - hh) * 60);
    if (mm === 60) { hh = (hh + 1) % 24; mm = 0; }
    return `${String(hh).padStart(2, "0")}:${String(mm).padStart(2, "0")}`;
  };
  const fmtVal = (row: ReportData["lifestyle"][number], v: number | null): string => {
    if (v == null) return "—";
    if (defOf.get(row.key)?.kind === "time") return clock(v);
    if (row.unit === "risk") return v.toFixed(1);
    const n = Math.abs(v) >= 100 ? v.toFixed(0) : v.toFixed(1);
    return row.unit ? `${n} ${row.unit}` : n;
  };

  // Same presentation as the item shifts above: a diverging bar per metric,
  // right = up, left = down, sized by percent change (minutes for bedtime).
  const rows = d.lifestyle.map((row) => {
    const isTime = defOf.get(row.key)?.kind === "time";
    // A missing window counts as zero, so a metric that only just appeared
    // (or vanished) still gets a bar "from 0". Clock metrics are the
    // exception: midnight is not zero, so they stay barless one-sided.
    const prior = row.prior ?? (isTime ? null : 0);
    const current = row.current ?? (isTime ? null : 0);
    const oneSided = prior == null || current == null;
    let magnitude = 0;   // 0..100 scale driving the bar width
    let up = false;
    let steady = false;
    let capText = "";
    let color = C.slate;
    if (!oneSided) {
      if (isTime) {
        const dMin = Math.round((current! - prior!) * 60);
        up = dMin > 0;
        steady = Math.abs(dMin) < 10;
        magnitude = Math.min(100, Math.abs(dMin));
        capText = steady ? rt("steady")
          : `${up ? "+" : "−"}${Math.abs(dMin)}m (${fmtVal(row, prior)}→${fmtVal(row, current)})`;
      } else {
        const base = Math.abs(prior!) > 1e-9 ? Math.abs(prior!) : null;
        const pct = base == null
          ? (Math.abs(current!) > 1e-9 ? 100 : 0)
          : ((current! - prior!) / base) * 100;
        up = pct > 0;
        steady = Math.abs(pct) < 3;
        magnitude = Math.min(100, Math.abs(pct));
        capText = steady ? rt("steady")
          : `${up ? "+" : "−"}${Math.round(Math.abs(pct))}% (${fmtVal(row, prior)}→${fmtVal(row, current)})`;
        if (!steady) {
          if (row.key === "caffeine") {
            // Guidance is "low or stable": any large swing is the finding.
            color = Math.abs(pct) > 30 ? C.red : C.slate;
          } else if (GOOD_UP.has(row.key)) {
            color = up ? C.green : C.red;
          } else if (GOOD_DOWN.has(row.key)) {
            color = up ? C.red : C.green;
          }
        }
      }
    } else {
      capText = `${fmtVal(row, prior)} → ${fmtVal(row, current)}`;
    }
    const w = steady || oneSided ? 0 : Math.max(4, (magnitude / 100) * 42);
    return `<div style="display:flex;align-items:center;gap:8px;padding:3px 0">
      <span style="width:118px;font-size:10px;color:#E4DAEF;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(rt(row.label))}</span>
      <div style="flex:1;position:relative;height:9px">
        <div style="position:absolute;left:50%;top:-2px;bottom:-2px;width:1px;background:rgba(255,255,255,0.18)"></div>
        ${w ? `<div style="position:absolute;top:0;height:9px;border-radius:4px;${up ? "left:50%" : "right:50%"};width:${w.toFixed(1)}%;background:${color}"></div>` : ""}
      </div>
      <span style="width:132px;text-align:right;font-size:8.5px;color:${C.muted};white-space:nowrap">${esc(capText)}</span>
    </div>`;
  }).join("");

  return `
    ${sub(C.purple, rt("Daily habits"))}
    <p class="meta" style="margin:-4px 0 8px">${esc(caption)}</p>
    <div class="card">${rows}</div>`;
}

// ── trends (logged items, period vs the equal prior period) ─────
function trendsPage(d: ReportData, pageNo: number): string {
  const changed = d.trends.filter((t) => t.current !== t.prior);
  const fromD = parseDate(d.params.from ?? null);
  const toD = parseDate(d.params.to ?? null);
  const scoped = !!(fromD && toD && toD.getTime() > fromD.getTime());
  const windowDays = scoped
    ? Math.max(1, Math.round((toD!.getTime() - fromD!.getTime()) / 86_400_000))
    : 30;
  const caption = (scoped
    ? rt("Occurrences per period, this report's range vs the equal period before it.")
    : rt("Occurrences over the last 30 days vs the 30 days before them."))
    + " " + rt("Based on the date range only; attack filters do not change these numbers.")
    + (scoped && windowDays < 7 ? " " + rt("Short periods are noisy; interpret with care.") : "");

  const unwanted = changed.filter((t) => t.kind !== "relief");
  const helpful = changed.filter((t) => t.kind === "relief");
  const top = (list: typeof changed) => [...list]
    .sort((a, b) => Math.abs(b.current - b.prior) - Math.abs(a.current - a.prior))
    .slice(0, 8);
  const u = top(unwanted), h = top(helpful);
  const maxDelta = Math.max(1, ...[...u, ...h].map((t) => Math.abs(t.current - t.prior)));

  const row = (t: ReportData["trends"][number]): string => {
    const delta = t.current - t.prior;
    const more = delta > 0;
    const w = Math.max(4, (Math.abs(delta) / maxDelta) * 42);
    const color = t.kind === "relief"
      ? (more ? C.green : C.slate)
      : (more ? C.red : C.green);
    return `<div style="display:flex;align-items:center;gap:8px;padding:3px 0">
      <span style="width:118px;font-size:10px;color:#E4DAEF;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(pretty(t.name))}</span>
      <div style="flex:1;position:relative;height:9px">
        <div style="position:absolute;left:50%;top:-2px;bottom:-2px;width:1px;background:rgba(255,255,255,0.18)"></div>
        <div style="position:absolute;top:0;height:9px;border-radius:4px;${more ? "left:50%" : "right:50%"};width:${w.toFixed(1)}%;background:${color}"></div>
      </div>
      <span style="width:66px;text-align:right;font-size:8.5px;color:${C.muted};white-space:nowrap">${more ? "+" : "−"}${Math.abs(delta)} (${t.prior}→${t.current})</span>
    </div>`;
  };

  const medWarn = u.some((t) => t.kind === "medicine" && t.current >= 3
    && (t.prior === 0 || t.current / t.prior >= 1.5));

  // The migraines themselves first, then what was logged with them, then the
  // daily habits — three answers to "what changed", one page.
  const st = d.trendStats;
  const statRowBar = (label: string, prior: number | null, current: number | null,
    fmtV: (v: number) => string, deltaFmt: (dAbs: number) => string): string => {
    const pr = prior ?? 0, cu = current ?? 0;
    if (!pr && !cu) return "";
    const pct = pr === 0 ? (cu === 0 ? 0 : 100) : ((cu - pr) / Math.abs(pr)) * 100;
    const up = pct > 0, steady = Math.abs(pct) < 3;
    const w = steady ? 0 : Math.max(4, (Math.min(100, Math.abs(pct)) / 100) * 42);
    const color = steady ? C.slate : (up ? C.red : C.green);
    const cap = steady ? rt("steady")
      : `${up ? "+" : "−"}${deltaFmt(Math.abs(cu - pr))} (${fmtV(pr)}→${fmtV(cu)})`;
    return `<div style="display:flex;align-items:center;gap:8px;padding:3px 0">
      <span style="width:118px;font-size:10px;color:#E4DAEF;white-space:nowrap">${esc(label)}</span>
      <div style="flex:1;position:relative;height:9px">
        <div style="position:absolute;left:50%;top:-2px;bottom:-2px;width:1px;background:rgba(255,255,255,0.18)"></div>
        ${w ? `<div style="position:absolute;top:0;height:9px;border-radius:4px;${up ? "left:50%" : "right:50%"};width:${w.toFixed(1)}%;background:${color}"></div>` : ""}
      </div>
      <span style="width:132px;text-align:right;font-size:8.5px;color:${C.muted};white-space:nowrap">${esc(cap)}</span>
    </div>`;
  };
  const statsRows = st ? [
    statRowBar(rt("Migraines"), st.prior.count, st.current.count,
      (v) => `${Math.round(v)}`, (v) => `${Math.round(v)}`),
    statRowBar(rt("Avg severity"), st.prior.avgSeverity, st.current.avgSeverity,
      (v) => v.toFixed(1), (v) => v.toFixed(1)),
    statRowBar(rt("Avg duration"), st.prior.avgDurationH, st.current.avgDurationH,
      (v) => `${v.toFixed(1)}h`, (v) => `${v.toFixed(1)}h`),
    statRowBar(rt("Symptoms per attack"), st.prior.symptomsPerAttack, st.current.symptomsPerAttack,
      (v) => v.toFixed(1), (v) => v.toFixed(1)),
  ].filter(Boolean).join("") : "";
  const statsBlock = statsRows ? `
    ${sub(C.pink, rt("Your migraines"))}
    <div class="card">${statsRows}</div>` : "";

  // Items and daily habits share the page: what was logged with the attacks,
  // then life around them.
  const habits = lifestyleBlock(d);
  const items = (u.length || h.length) ? `
    ${sub(C.orange, rt("Around your logged migraines"))}
    ${historyNote(caption)}
    <div class="card">
      ${u.length ? `<p class="meta" style="margin:0 0 2px;color:${C.orange}">${esc(rt("Triggers, prodromes, medicines & symptoms"))}</p>` : ""}
      ${u.map(row).join("")}
      ${medWarn ? `<p class="meta" style="color:${C.orange};margin:4px 0 0">${rt("Acute medication use rising — worth an overuse check.")}</p>` : ""}
      ${h.length ? `<p class="meta" style="margin:6px 0 2px;color:${C.green}">${rt("Reliefs")}</p>` : ""}
      ${h.map(row).join("")}
    </div>` : "";
  if (!statsBlock && !items && !habits) return "";
  return page(C.orange, `
    ${header(C.orange, ICON.freq, rt("What changed"),
      rt("This period against the equal one before it"))}
    ${statsBlock}
    ${items}
    ${habits}
  `, pageNo);
}

// ── health metrics (client-supplied series until the registry moves) ──
function metrics(d: ReportData, pageNo: number): string {
  // The reader's Select Metrics choice IS this page: exactly the ticked keys
  // that have data, in registry order (data.ts sorts the series). With no
  // selection, the first eight as before.
  const wanted = d.params.metricKeys?.length
    ? d.metricSeries.filter((s) => d.params.metricKeys!.includes(s.key))
    : d.metricSeries.slice(0, 8);
  if (!wanted.length) return "";
  const attackDays = new Set(d.migraines
    .map((m) => parseDate(m.start_at))
    .filter((x): x is Date => !!x)
    .map((dt) => dt.toISOString().slice(0, 10)));

  const spark = (points: { date: string; value: number }[], color: string, unit: string) => {
    if (points.length < 2) return "";
    const W = 660, H = 78, padL = 34, padR = 6, padT = 6, padB = 16;
    const vals = points.map((p) => p.value);
    const min = Math.min(...vals), max = Math.max(...vals);
    const span = max - min || 1;
    const step = (W - padL - padR) / (points.length - 1);
    const yOf = (v: number) => padT + (1 - (v - min) / span) * (H - padT - padB);
    const xOf = (i: number) => padL + i * step;
    const fmt = (v: number) => (Math.abs(v) >= 100 ? v.toFixed(0) : v.toFixed(1));
    const path = smoothPath(points.map((p, i) => ({ x: xOf(i), y: yOf(p.value) })));
    const marks = points.map((p, i) => (attackDays.has(p.date)
      ? `<line x1="${xOf(i).toFixed(1)}" y1="${padT}" x2="${xOf(i).toFixed(1)}" y2="${H - padB}"
           stroke="${rgba(C.pink, .45)}" stroke-width="1"/>
         <circle cx="${xOf(i).toFixed(1)}" cy="${yOf(p.value).toFixed(1)}" r="2.2" fill="${C.pink}"/>`
      : "")).join("");
    // Axes, so a shape becomes readable numbers: the range on the left, the
    // period on the bottom, and what the pink marks mean.
    const gridY = [max, (max + min) / 2, min].map((v) =>
      `<line x1="${padL}" y1="${yOf(v).toFixed(1)}" x2="${W - padR}" y2="${yOf(v).toFixed(1)}"
         stroke="rgba(255,255,255,0.06)"/>
       <text x="${padL - 4}" y="${(yOf(v) + 2).toFixed(1)}" font-size="7" fill="${C.muted}"
         text-anchor="end">${fmt(v)}</text>`).join("");
    const dates = [0, Math.floor((points.length - 1) / 2), points.length - 1].map((i) =>
      `<text x="${xOf(i).toFixed(1)}" y="${H - 4}" font-size="7" fill="${C.muted}"
         text-anchor="${i === 0 ? "start" : i === points.length - 1 ? "end" : "middle"}">
         ${new Date(points[i].date).toLocaleDateString(rtLocale, { day: "2-digit", month: "short" })}</text>`).join("");
    return `<svg viewBox="0 0 ${W} ${H}" style="width:100%;height:${H}px" preserveAspectRatio="none">
      ${gridY}${marks}
      <path d="${path}" fill="none" stroke="${color}" stroke-width="1.6"
        stroke-linecap="round" stroke-linejoin="round"/>
      ${dates}
      <text x="${W - padR}" y="${padT + 6}" font-size="7" fill="${C.muted}" text-anchor="end">${esc(unit)}</text>
    </svg>`;
  };

  return page(C.green, `
    ${header(C.green, ICON.pulse, rt("Health metrics"),
      rt("Everything tracked around the attacks in this period"))}
    ${wanted.map((s) => {
      const vals = s.points.map((p) => p.value);
      const avg = vals.length ? (vals.reduce((a, b) => a + b, 0) / vals.length) : 0;
      return `<div class="card">
        <div class="log-head">
          <span class="date">${esc(rt(s.label))}</span>
          ${badge(C.green, rt("{0}{1} avg", avg.toFixed(1), s.unit))}
        </div>
        ${spark(s.points, s.color ?? C.green, s.unit)}
        <div class="meta">${esc(rt("Pink lines mark migraine days · {0} days · range {1}", s.points.length,
          `${Math.min(...vals).toFixed(1)} – ${Math.max(...vals).toFixed(1)}${s.unit}`))}</div>
      </div>`;
    }).join("")}
  `, pageNo);
}

/** The category spider from the app's breakdown screens. Axes are the
 *  categories a log type was filed under, the polygon is how often each was
 *  logged. Falls back to nothing below three axes, where a radar is a lie. */
function spiderSvg(axes: [string, number][], color: string, size = 132): string {
  if (axes.length < 3) return "";
  const cx = size / 2, cy = size / 2 + 4, r = size * 0.32;
  const max = Math.max(...axes.map(([, n]) => n)) || 1;
  const pt = (i: number, frac: number) => {
    const a = (Math.PI * 2 * i) / axes.length - Math.PI / 2;
    return [cx + Math.cos(a) * r * frac, cy + Math.sin(a) * r * frac];
  };
  const rings = [0.33, 0.66, 1].map((f) =>
    `<polygon points="${axes.map((_, i) => pt(i, f).map((v) => v.toFixed(1)).join(",")).join(" ")}"
       fill="none" stroke="rgba(255,255,255,0.08)" stroke-width="0.7"/>`).join("");
  const shape = axes.map(([, n], i) => pt(i, n / max).map((v) => v.toFixed(1)).join(",")).join(" ");
  const labels = axes.map(([label], i) => {
    const [lx, ly] = pt(i, 1.28);
    const anchor = lx < cx - 6 ? "end" : lx > cx + 6 ? "start" : "middle";
    return `<text x="${lx.toFixed(1)}" y="${ly.toFixed(1)}" font-size="6" fill="${C.muted}"
      text-anchor="${anchor}">${esc(label)}</text>`;
  }).join("");
  return `<svg viewBox="0 0 ${size} ${size}" style="width:${size}px;height:${size}px">
    ${rings}
    <polygon points="${shape}" fill="${rgba(color, .28)}" stroke="${color}" stroke-width="1.2"/>
    ${axes.map(([, n], i) => {
      const [px, py] = pt(i, n / max);
      return `<circle cx="${px.toFixed(1)}" cy="${py.toFixed(1)}" r="1.8" fill="${color}"/>`;
    }).join("")}
    ${labels}
  </svg>`;
}

// ── narrative ────────────────────────────────────────────────────
// The recalibration's six-section clinical narrative, parsed with the same
// rules as the apps' parseNarrativeSections (services/insights.ts): a
// "=== Header ===" line opens each section, unknown headers are skipped
// rather than mis-bucketed, first occurrence of a key wins, output follows
// narrative order. The clients keep a legacy single-blob fallback; here a
// blob without headers is unparseable and the page is dropped instead —
// legacy raw text still prints on the Patient profile page.
// Titles are plain English keys: this array is built at import time, when `rt`
// is still the identity translator, so an rt() here would freeze English into
// the table. They are translated with rt(title) where they are rendered.
const NARRATIVE_DEFS: { key: string; title: string; color: string; aliases: string[] }[] = [
  { key: "profile",         title: "Profile",         color: C.purple, aliases: ["profile", "migraine profile", "spin profile", "episode profile"] },
  { key: "trigger_profile", title: "Trigger Profile", color: C.orange, aliases: ["trigger profile", "triggers"] },
  { key: "burden_profile",  title: "Burden Profile",  color: C.red,    aliases: ["burden profile", "burden"] },
  { key: "coping_profile",  title: "Coping Profile",  color: C.green,  aliases: ["coping profile", "coping"] },
  { key: "lifestyle",       title: "Lifestyle",       color: C.blue,   aliases: ["lifestyle"] },
  { key: "trend",           title: "Trend",           color: C.accent, aliases: ["trend", "trends"] },
];

function parseNarrativeSections(raw: string): { key: string; title: string; color: string; body: string }[] {
  const text = raw.replace(/\r\n/g, "\n").trim();
  if (!text) return [];
  const headerRe = /^[ \t]*===[ \t]*([^=]+?)[ \t]*===[ \t]*$/gm;
  const found: { rawTitle: string; start: number; bodyStart: number }[] = [];
  let match: RegExpExecArray | null;
  while ((match = headerRe.exec(text)) !== null) {
    found.push({
      rawTitle: match[1].trim().toLowerCase(),
      start: match.index,
      bodyStart: match.index + match[0].length,
    });
  }
  if (found.length === 0) return [];
  const byKey = new Map<string, { key: string; title: string; color: string; body: string }>();
  for (let i = 0; i < found.length; i++) {
    const cur = found[i], next = found[i + 1];
    const body = text.slice(cur.bodyStart, next?.start ?? text.length).trim();
    if (!body) continue;
    const def = NARRATIVE_DEFS.find((s) => s.aliases.includes(cur.rawTitle));
    if (!def) continue;
    if (!byKey.has(def.key)) byKey.set(def.key, { key: def.key, title: def.title, color: def.color, body });
  }
  return NARRATIVE_DEFS.map((s) => byKey.get(s.key))
    .filter((x): x is { key: string; title: string; color: string; body: string } => !!x);
}

const NARRATIVE_MAX_AGE_MS = 183 * 86_400_000; // ~6 months

/** Sections + timestamp when the stored assessment is present, parseable and
 *  written within the last six months — otherwise null and no page renders. */
function narrativeInfo(d: ReportData): {
  sections: { key: string; title: string; color: string; body: string }[]; writtenAt: Date;
} | null {
  const pr = d.profile;
  if (!pr) return null;
  const raw = typeof pr.clinical_assessment === "string" ? pr.clinical_assessment : "";
  const sections = parseNarrativeSections(raw);
  if (!sections.length) return null;
  // updated_at is bumped by trigger whenever the row changes — i.e. at the
  // recalibration that wrote this assessment. created_at covers legacy rows.
  const writtenAt = parseDate(String(pr.updated_at ?? pr.created_at ?? ""));
  if (!writtenAt) return null;
  if (Date.now() - writtenAt.getTime() > NARRATIVE_MAX_AGE_MS) return null;
  return { sections, writtenAt };
}

function narrative(d: ReportData, pageNo: number): string {
  const info = narrativeInfo(d);
  if (!info) return "";
  // One flowing document, the report's own idiom: colored subheadings over
  // plain body text inside a single card — not a stack of boxed answers.
  const blocks = info.sections.map((s, i) => `
      ${i ? `<div style="height:1px;background:rgba(255,255,255,0.06);margin:10px 0"></div>` : ""}
      ${sub(C.purple, rt(s.title))}
      ${s.body.split(/\n{2,}/).map((p) =>
        `<p style="margin:0 0 7px;font-size:10px;color:#E4DAEF;line-height:1.65">${esc(p.trim())}</p>`).join("")}
    `).join("");
  return page(C.accent, `
    ${header(C.accent, ICON.log, rt("Narrative"),
      rt("The story of this record, in plain words"))}
    ${historyNote(rt("Written at your recalibration on {0}, from your full logging history at the time; the report's filters do not change it.", fmtDate(info.writtenAt)))}
    <div class="card" style="padding:12px 14px">${blocks}</div>
  `, pageNo);
}

// ── profile ──────────────────────────────────────────────────────
function profile(d: ReportData, pageNo: number): string {
  const pr = d.profile;
  if (!pr) return "";
  const val = (k: string) => {
    const v = pr[k];
    return typeof v === "string" && v.trim() ? v.trim() : "";
  };
  const rows: [string, string][] = ([
    ["Frequency", val("frequency")],
    ["Typical duration", val("duration")],
    ["Years with migraine", val("experience")],
    ["Trajectory", val("trajectory")],
    ["Age range", val("age_range")],
    ["Gender", val("gender")],
    ["Seasonal pattern", val("seasonal_pattern")],
  ] as [string, string][]).filter(([, v]) => v);
  const areas = Array.isArray(pr.trigger_areas) ? (pr.trigger_areas as string[]) : [];
  // When the Narrative page carries the six-section assessment, this page
  // shows only the short summary rather than the same text twice — and if
  // that leaves it with barely anything (a stub summary and a couple of
  // demographic rows), the page is dropped: Narrative opens the report.
  const hasNarrative = !!narrativeInfo(d);
  const assessment = hasNarrative
    ? val("summary")
    : (val("clinical_assessment") || val("summary"));
  if (!rows.length && !assessment && !areas.length) return "";
  if (hasNarrative && rows.length < 3 && !areas.length) return "";

  return page(C.accent, `
    ${header(C.accent, ICON.bulb, rt("Patient profile"),
      rt("What the patient reported when setting up, in their own words"))}
    ${assessment ? `<div class="card"><div class="meta" style="line-height:1.6">${esc(assessment)}</div></div>` : ""}
    ${rows.length ? `<div class="card"><table class="log">
      ${rows.map(([k, v]) => `<tr><td class="k" style="width:120px">${esc(rt(k))}</td>
        <td class="v">${esc(pretty(v))}</td></tr>`).join("")}
    </table></div>` : ""}
    ${areas.length ? `<div class="card">
      <div class="name">${rt("Areas they suspect")}</div>
      <div class="meta">${esc(areas.map((a) => pretty(a)).join(", "))}</div>
    </div>` : ""}
  `, pageNo);
}

// ── breakdowns ───────────────────────────────────────────────────
/** What was logged, per type, by category and by item — the old report's
 *  spider pages, as counts a reader can actually check. */
function breakdowns(d: ReportData, pageNo: number): string {
  const types: [string, string, ChildRow[], string][] = [
    ["Triggers", "trigger", d.triggers, C.orange],
    ["Prodromes", "prodrome", d.prodromes, C.purple],
    ["Medicines", "medicine", d.medicines, C.blue],
    ["Reliefs", "relief", d.reliefs, C.green],
    ["Activities", "activity", d.activities, C.orange],
    ["Locations", "location", d.locations, C.slate],
    ["Missed activities", "missed", d.missedActivities, C.red],
    // Symptoms live in their own table but are logged like everything else.
    ["Symptoms", "symptom", d.symptoms.map((sy) => ({
      id: sy.id, migraine_id: sy.migraine_id, type: sy.type, start_at: sy.start_at ?? "",
    })) as ChildRow[], C.accent],
  ];

  const blocks = types.map(([title, kind, rows, color]) => {
    if (!rows.length) return "";
    const rawCats = d.poolCategories[kind] ?? {};
    const cats: Record<string, string> = {};
    for (const [label, cat] of Object.entries(rawCats)) {
      cats[label.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim()] = cat;
    }
    const byItem = new Map<string, number>();
    const byCat = new Map<string, number>();
    for (const r of rows) {
      const raw = String(r.name ?? r.type ?? "").trim().replace(/_/g, " ").replace(/\s+/g, " ");
      const name = pretty(r.name ?? r.type ?? "");
      if (!name) continue;
      byItem.set(name, (byItem.get(name) ?? 0) + 1);
      // Look the category up by the ENGLISH name. `cats` is keyed on the
      // seeded English pool labels, while pretty() has already translated
      // `name` — keying on that made every translated item miss and fall back
      // to "Other", which silently moved counts between categories and
      // reordered the chart in every language but English.
      const key = raw.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
      const cat = pretty(cats[key] ?? cats[raw.toLowerCase()] ?? "Other");
      byCat.set(cat, (byCat.get(cat) ?? 0) + 1);
    }
    const items = [...byItem.entries()].sort((a, b) => b[1] - a[1]);
    const catList = [...byCat.entries()].sort((a, b) => b[1] - a[1]);
    const max = Math.max(1, ...items.map(([, n]) => n));

    return `<div class="card">
      <div class="log-head">
        <span class="date" style="color:${color}">${esc(rt(title))}</span>
        <span class="meta" style="margin:0">${catList.length === 1
          ? rt("{0} logged · 1 category", rows.length)
          : rt("{0} logged · {1} categories", rows.length, catList.length)}</span>
      </div>
      <div class="under" style="margin-top:2px">
        ${(() => {
          const axes = catList.length >= 3 ? catList : items.slice(0, 6);
          const svg = spiderSvg(axes, color);
          return svg ? `<div style="flex:none">${svg}</div>` : "";
        })()}
        <div class="under-col">
          <div class="under-h">${rt("BY ITEM")}</div>
          <table class="log">
            ${items.length > 8
              ? `<tr><td class="k" colspan="3" style="width:auto;font-style:italic">
                  ${rt("Showing 8 of {0}", items.length)}</td></tr>`
              : ""}
            ${items.slice(0, 8).map(([name, n]) => `<tr>
              <td class="k" style="width:auto">${esc(name)}</td>
              <td class="o" style="width:22px;text-align:right">${n}</td>
              <td class="v" style="width:96px">${bar(color, (n / max) * 100)}</td>
            </tr>`).join("")}
          </table>
        </div>
        <div class="under-col narrow">
          <div class="under-h">${rt("BY CATEGORY")}</div>
          <table class="log">
            ${catList.map(([cat, n]) => `<tr>
              <td class="k" style="width:auto">${esc(cat)}</td>
              <td class="v" style="text-align:right">${n} (${Math.round((n / rows.length) * 100)}%)</td>
            </tr>`).join("")}
          </table>
        </div>
      </div>
    </div>`;
  }).filter(Boolean);

  if (!blocks.length) return "";
  // One page element; `page-break-inside: avoid` on each card lets the printer
  // fill each sheet and move a whole block down when it will not fit.
  return page(C.accent, `
    ${header(C.accent, ICON.grid, rt("What you logged"),
      rt("Everything recorded in this period, by type and category"))}
    ${blocks.join("")}
  `, pageNo);
}

// ── document ─────────────────────────────────────────────────────
export function renderReport(d: ReportData): string {
  rt = translatorFor(d.lang);
  rtLocale = LOCALE_TAGS[d.lang] ?? "en-GB";
  // Two passes: the section bodies are built first so the cover's contents
  // list can carry real page numbers rather than guesses.
  let p = 2;
  const contents: { title: string; page: number }[] = [];
  const parts: string[] = [];

  const add = (title: string, build: (d: ReportData, n: number) => string) => {
    const html = build(d, p);
    if (!html) return;
    contents.push({ title, page: p });
    parts.push(html);
    p += 1;
  };

  // Who the patient is, then how their migraines are trending, then the log
  // itself — the order a clinician reads in.
  add(rt("Patient profile"), profile);
  add(rt("Narrative"), narrative);
  add(rt("Frequency trends"), frequency);
  add(rt("What changed"), trendsPage);
  add(rt("Severity spread"), severitySpread);

  // Breakdowns can span several pages, so it counts its own.
  add(rt("What you logged"), breakdowns);

  add(rt("What Happened"), whatHappened);
  add(rt("What Worked"), whatWorked);
  add(rt("Pain response"), painResponse);
  add(rt("Usage vs effectiveness"), usageVsEffectiveness);
  add(rt("What's Helping"), whatsHelping);
  add(rt("What Were You Doing"), contextPage);
  add(rt("How Did It Impact You"), impact);
  add(rt("Recommendations"), recommendations);
  add(rt("Treatments"), treatments);
  add(rt("Health metrics"), metrics);

  const log = attackLog(d, p);
  if (log.pages) { contents.push({ title: rt("Attack log"), page: p }); parts.push(log.html); p += log.pages; }



  return `<!doctype html><html><head><meta charset="utf-8">
    <meta name="viewport" content="width=794">
    <title>${rt("MigraineMe clinical summary")}</title>
    <style>${STYLES}</style></head><body>
    ${cover(d, contents)}
    ${parts.join("")}
    </body></html>`;
}
