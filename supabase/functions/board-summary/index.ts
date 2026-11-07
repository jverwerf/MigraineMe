// supabase/functions/board-summary/index.ts
//
// The AI summary plugin. The practitioner taps a button on a client's board and
// gets a short summary of what they have ticked there, to read, edit and put in
// their note. The text comes from the board in the practitioner's browser; the
// client's name is taken out before it is sent. Nothing is stored.
//
// Only for a practitioner who switched the plugin on, about a client actively
// linked to them who has said yes to AI summaries in their app.

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { asLang, languageDirective } from "../_shared/i18n.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const OPENAI_KEY = Deno.env.get("OPENAI_API_KEY");
const MAX_TEXT = 30000;

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, x-app-id, x-practitioner-view",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (o: unknown, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { ...cors, "Content-Type": "application/json" } });

const SYSTEM = `You help a health practitioner prepare a treatment note. You get the parts of one client's dashboard the practitioner selected.

Write a summary of 4 to 7 short, plain sentences: how things have been going, what stands out, what changed, how treatments and goals are doing.

Rules:
- Use only what is in the text. Never add a number, cause or fact that is not there.
- A pattern in the data is a pattern, not a cause. Say "goes with" or "happened alongside". Never say something works, is effective, helped, contributed to or led to a result, even if the text you were given says so. Where a client rated a treatment, say they rated it.
- Neutral clinical tone. No praise and no words like remarkable, success or great.
- No diagnosis and no advice on medication or treatment.
- If something important is missing or the data is thin, say so in one sentence.
- Refer to "the client". Plain text only, no headings or lists.`;

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  try {
    const auth = req.headers.get("Authorization");
    if (!auth) return json({ error: "Sign in first." }, 401);
    const asUser = createClient(SUPABASE_URL, ANON_KEY, { global: { headers: { Authorization: auth } } });
    const { data: { user }, error: uerr } = await asUser.auth.getUser();
    if (uerr || !user) return json({ error: "Sign in first." }, 401);

    const body = await req.json().catch(() => ({}));
    const text = String(body.text || "").slice(0, MAX_TEXT).trim();
    if (!body.link_id || !text) return json({ error: "Nothing selected to summarise." }, 400);

    const db = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });
    const { data: rows } = await db.from("practitioners").select("id,plugins").eq("user_id", user.id);
    const mine = (rows || []) as { id: string; plugins: Record<string, unknown> | null }[];
    if (!mine.length) return json({ error: "That account is not set up as a practitioner." }, 403);
    if (!mine.some((r) => r.plugins && r.plugins.ai_summary === true)) {
      return json({ error: "Switch on the AI summary plugin first." }, 403);
    }
    const { data: link } = await db.from("practitioner_clients").select("id,ai_summary_allowed")
      .eq("id", body.link_id).eq("status", "active").in("practitioner_id", mine.map((r) => r.id)).maybeSingle();
    if (!link) return json({ error: "That client is not linked to you." }, 403);
    if (link.ai_summary_allowed !== true) return json({ error: "This client has not allowed AI summaries." }, 403);

    if (!OPENAI_KEY) return json({ error: "AI summary is not available right now." }, 503);
    const res = await fetch("https://api.openai.com/v1/chat/completions", {
      method: "POST",
      headers: { Authorization: `Bearer ${OPENAI_KEY}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        model: "gpt-4o-mini",
        temperature: 0.2,
        max_tokens: 400,
        messages: [
          { role: "system", content: SYSTEM + languageDirective(asLang(body.lang)) },
          { role: "user", content: text },
        ],
      }),
    });
    if (!res.ok) {
      console.error("board-summary openai", res.status, (await res.text()).slice(0, 300));
      return json({ error: "AI summary is not available right now." }, 502);
    }
    const out = await res.json();
    const summary = String(out?.choices?.[0]?.message?.content || "").trim();
    if (!summary) return json({ error: "AI summary is not available right now." }, 502);
    return json({ summary });
  } catch (e) {
    console.error("board-summary", e);
    return json({ error: "Something went wrong" }, 500);
  }
});
