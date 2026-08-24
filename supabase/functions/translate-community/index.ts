import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

// Translates Community CONTENT into the six app languages.
//
// The app's t() tables translate our own fixed copy, keyed on the English
// string. That cannot cover the Community feed: ingested article headlines,
// AI-companion comments, forum posts and thread summaries are unbounded prose
// that arrives every day, so it has to be translated once and stored.
//
// Shape is deliberately the existing dispatcher/worker one. public.
// community_translation_queue(kind, lang, limit) owns the "what is stale"
// rule and hands back the source hash it computed; this function translates a
// bounded number of batches and writes the hash straight back, so the staleness
// rule can never drift between the two halves. Edit the English text of an
// article and its hash changes, so all six languages redo themselves on the
// next run rather than sitting stale for ever.
//
// Bounded per invocation (MAX_CALLS) so a run always finishes well inside the
// wall clock. It returns pending:true while work remains, and the cron simply
// runs often enough to drain it; that is also how the initial backfill of a
// few thousand rows was done, without a special one-off script.

const LANGS = ["de", "es", "nl", "fr", "it", "pt"];

const LANG_NAMES = {
  de: "German",
  es: "Spanish",
  nl: "Dutch",
  fr: "French",
  it: "Italian",
  pt: "Portuguese (European)"
};

// kind -> the table its translations live in, the column that points back at
// the source row, and the prose columns. Nothing outside this map is writable,
// so a bad `kinds` in the request body cannot reach an arbitrary table.
const KINDS = {
  articles: {
    table: "article_translations",
    key: "article_id",
    uuid: true,
    fields: ["title", "ai_summary"],
    what: "a headline and short plain-language summary of a migraine or health news article"
  },
  article_comments: {
    table: "article_comment_translations",
    key: "comment_id",
    uuid: true,
    fields: ["body"],
    what: "a comment posted under a health article in a patient community"
  },
  blog_comments: {
    table: "blog_comment_translations",
    key: "comment_id",
    uuid: true,
    fields: ["body"],
    what: "a comment posted under a blog post in a patient community"
  },
  forum_posts: {
    table: "forum_post_translations",
    key: "post_id",
    uuid: true,
    fields: ["title", "body"],
    what: "a discussion thread started by a patient in a community forum"
  },
  forum_comments: {
    table: "forum_comment_translations",
    key: "comment_id",
    uuid: true,
    fields: ["body"],
    what: "a reply in a patient community forum thread"
  },
  forum_thread_summaries: {
    table: "forum_thread_summary_translations",
    key: "post_id",
    uuid: false,
    fields: ["summary"],
    what: "an AI-written summary of what a community forum thread discussed"
  }
};

const KIND_ORDER = [
  "articles",
  "article_comments",
  "forum_posts",
  "forum_comments",
  "blog_comments",
  "forum_thread_summaries"
];

// Names that must survive untranslated. Drug names are included on purpose:
// a German reader searching for their own prescription needs the same spelling
// their box has, and a "translated" drug name is a safety problem, not a
// localisation win.
const KEEP = "MigraineMe, VertigoMe, Andlane, Apple Health, Health Connect, " +
  "Garmin, Oura, Polar, Fitbit, Withings, Apple Watch, PubMed, NHS, " +
  "CGRP, Botox, HRV, SpO2, REM, and every drug, supplement and brand name " +
  "(sumatriptan, frovatriptan, erenumab, magnesium glycinate, and so on)";

const BATCH = 12;
const MAX_CALLS = 40;
// Real budget. An invocation is killed by the gateway if it runs too long, and
// a killed run returns nothing at all: the work it finished is safely in the
// table, but the caller cannot tell whether to come back. Stopping ourselves
// well inside the limit means every run answers, and the answer says honestly
// whether anything is still owed. Call count alone was the wrong bound —
// batches of long article summaries take three times as long as batches of
// one-line comments.
const BUDGET_MS = 100_000;
const MODEL = "gpt-4o";

function R(b, s = 200) {
  return new Response(JSON.stringify(b), {
    status: s,
    headers: { "Content-Type": "application/json" }
  });
}

function prompt(kind, lang, items) {
  const cfg = KINDS[kind];
  return [
    `Translate each item into ${LANG_NAMES[lang]} (${lang}). Each item is ${cfg.what}.`,
    "",
    `Return ONLY a JSON array. One object per input item, same order, same "id".`,
    `Each object has exactly these keys: "id", ${cfg.fields.map((f) => `"${f}"`).join(", ")}.`,
    "No prose, no code fence, no commentary.",
    "",
    "RULES:",
    `- Never translate: ${KEEP}.`,
    "- A null or empty input field stays null in the output.",
    "- Keep every factual claim, number, dose and unit exactly as written. Do not add, soften, remove or explain anything. This text is read by patients making decisions about their own care.",
    "- Translate the meaning, not the words. Write what a native speaker would actually write.",
    "- Register: informal (du / tu / je / tú). Warm, plain, written for patients, not for clinicians.",
    "- No em dashes.",
    "- If an item ends in a question to the community, keep it a question.",
    "- Keep the length close to the original. These render in fixed cards.",
    "",
    "ITEMS:",
    JSON.stringify(items, null, 1)
  ].join("\n");
}

async function translate(kind, lang, rows, key) {
  const cfg = KINDS[kind];
  const items = rows.map((r) => {
    const o = { id: r.row_id };
    for (const f of cfg.fields) o[f] = r.fields?.[f] ?? null;
    return o;
  });
  const r = await fetch("https://api.openai.com/v1/chat/completions", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer " + key
    },
    body: JSON.stringify({
      model: MODEL,
      temperature: 0.2,
      max_tokens: 8000,
      messages: [
        {
          role: "system",
          content: "You are a professional medical-content localiser. You return JSON and nothing else."
        },
        { role: "user", content: prompt(kind, lang, items) }
      ]
    })
  });
  if (!r.ok) {
    throw new Error("OpenAI " + r.status + ": " + (await r.text()).substring(0, 200));
  }
  const d = await r.json();
  let txt = d.choices?.[0]?.message?.content?.trim() || "";
  txt = txt.replace(/```json|```/g, "").trim();
  const start = txt.indexOf("[");
  const end = txt.lastIndexOf("]");
  if (start < 0 || end < 0) throw new Error("no JSON array in reply");
  const parsed = JSON.parse(txt.substring(start, end + 1));
  if (!Array.isArray(parsed)) throw new Error("reply was not an array");

  // Index by id rather than by position: a model that drops one item must lose
  // only that item, not silently shift every following translation onto the
  // wrong row. Anything unmatched is simply left pending for the next run.
  const byId = new Map();
  for (const p of parsed) {
    if (p && typeof p.id === "string") byId.set(p.id, p);
  }
  const out = [];
  for (const row of rows) {
    const p = byId.get(row.row_id);
    if (!p) continue;
    const rec = {
      [cfg.key]: row.row_id,
      lang,
      source_hash: row.source_hash,
      updated_at: new Date().toISOString()
    };
    let ok = true;
    for (const f of cfg.fields) {
      const src = row.fields?.[f] ?? null;
      let val = p[f];
      if (typeof val === "string") val = val.trim();
      if (src === null || src === "") {
        rec[f] = null;
        continue;
      }
      if (typeof val !== "string" || val === "") {
        ok = false;
        break;
      }
      rec[f] = val;
    }
    // NOT NULL on the primary prose column, so a row the model half-answered
    // is skipped rather than written as an empty card.
    if (ok) out.push(rec);
  }
  return out;
}

serve(async (req) => {
  const cronSecret = Deno.env.get("CRON_SECRET");
  if (!cronSecret) return R({ error: "Server misconfigured" }, 500);
  if (req.headers.get("x-cron-secret") !== cronSecret) return R({ error: "Forbidden" }, 403);

  const SU = Deno.env.get("SUPABASE_URL");
  const SK = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const OK = Deno.env.get("OPENAI_API_KEY");
  if (!SU || !SK) return R({ error: "Missing SUPABASE env" }, 500);
  if (!OK) return R({ error: "Missing OPENAI_API_KEY" }, 500);

  const sb = createClient(SU, SK);
  const body = await req.json().catch(() => ({}));

  const kinds = Array.isArray(body.kinds) && body.kinds.length
    ? body.kinds.filter((k) => KIND_ORDER.includes(k))
    : KIND_ORDER;
  const langs = Array.isArray(body.langs) && body.langs.length
    ? body.langs.filter((l) => LANGS.includes(l))
    : LANGS;
  const batch = Math.min(Math.max(Number(body.batch) || BATCH, 1), 25);
  const maxCalls = Math.min(Math.max(Number(body.max_calls) || MAX_CALLS, 1), 200);
  const budgetMs = Math.min(Math.max(Number(body.budget_ms) || BUDGET_MS, 10_000), 240_000);
  const startedAt = Date.now();
  const outOfTime = () => Date.now() - startedAt > budgetMs;

  let calls = 0;
  let written = 0;
  let pending = false;
  const errs = [];

  outer: for (const kind of kinds) {
    for (const lang of langs) {
      // Keep pulling this (kind, lang) until it is drained or the budget is
      // spent. Draining one language at a time keeps each OpenAI call to a
      // single target language, which measurably beats asking for six at once.
      for (;;) {
        if (calls >= maxCalls || outOfTime()) {
          pending = true;
          break outer;
        }
        const { data: rows, error } = await sb.rpc("community_translation_queue", {
          p_kind: kind,
          p_lang: lang,
          p_limit: batch
        });
        if (error) {
          errs.push(`${kind}/${lang}: queue: ${error.message}`);
          break;
        }
        if (!rows?.length) break;

        calls++;
        try {
          const recs = await translate(kind, lang, rows, OK);
          if (recs.length) {
            const { error: ue } = await sb
              .from(KINDS[kind].table)
              .upsert(recs, { onConflict: `${KINDS[kind].key},lang` });
            if (ue) {
              errs.push(`${kind}/${lang}: upsert: ${ue.message}`);
              pending = true;
              break;
            }
            written += recs.length;
          }
          if (recs.length < rows.length) {
            // The model dropped rows. They stay in the queue; moving on avoids
            // spinning on the same batch for the rest of the budget.
            errs.push(`${kind}/${lang}: ${rows.length - recs.length} of ${rows.length} not returned`);
            pending = true;
            break;
          }
        } catch (e) {
          errs.push(`${kind}/${lang}: ${String(e.message).substring(0, 160)}`);
          pending = true;
          break;
        }
      }
    }
  }

  // Say honestly whether anything is still owed, so the caller (cron, or a
  // backfill loop) knows to come back rather than assuming it is finished.
  if (!pending) {
    for (const kind of kinds) {
      for (const lang of langs) {
        const { data } = await sb.rpc("community_translation_queue", {
          p_kind: kind,
          p_lang: lang,
          p_limit: 1
        });
        if (data?.length) {
          pending = true;
          break;
        }
      }
      if (pending) break;
    }
  }

  const ms = Date.now() - startedAt;
  console.log(`translate-community: calls=${calls} written=${written} pending=${pending} ms=${ms} errs=${errs.length}`);
  return R({ ok: true, calls, written, pending, ms, errors: errs.slice(0, 20) });
});
