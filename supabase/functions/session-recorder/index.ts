// supabase/functions/session-recorder/index.ts
//
// Recorder to text, part of the AI summary plugin. The practitioner records a
// session in their browser. Each few minutes of sound comes here, goes to OpenAI
// to become text, and is gone: no sound and no text is stored here. At the end the
// text is turned into a draft note for the practitioner to check.
//
// Only for a practitioner who switched the plugin on, about a client actively
// linked to them. Clients are told about recording in the info shown when they
// connect to a practitioner.

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { asLang, languageDirective } from "../_shared/i18n.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const OPENAI_KEY = Deno.env.get("OPENAI_API_KEY");
const MAX_AUDIO_B64 = 30_000_000; // about 22 MB of sound; the page sends about 1 MB at a time
const MAX_TEXT = 120_000;

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, x-app-id, x-practitioner-view",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (o: unknown, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { ...cors, "Content-Type": "application/json" } });

const SYSTEM = `You help a health practitioner write a treatment note. You get the text of a recorded session between the practitioner and a client. It may contain transcription mistakes.

Write a draft note in short plain sentences, under these headings on their own lines: What the client reported / What was done or discussed / What was agreed next.

Rules:
- Use only what was said. Never add a number, cause, diagnosis or fact that was not said.
- If something was not covered, leave that heading with "Not discussed."
- If a word was probably misheard and it matters, for example a medicine name or dose, keep it and mark it [check].
- Neutral clinical tone. No praise. No advice of your own.
- Refer to "the client". Do not include names.`;

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  try {
    const auth = req.headers.get("Authorization");
    if (!auth) return json({ error: "Sign in first." }, 401);
    const asUser = createClient(SUPABASE_URL, ANON_KEY, { global: { headers: { Authorization: auth } } });
    const { data: { user }, error: uerr } = await asUser.auth.getUser();
    if (uerr || !user) return json({ error: "Sign in first." }, 401);

    const body = await req.json().catch(() => ({}));
    if (!body.link_id) return json({ error: "No client." }, 400);

    const db = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });
    const { data: rows } = await db.from("practitioners").select("id,plugins").eq("user_id", user.id);
    const mine = (rows || []) as { id: string; plugins: Record<string, unknown> | null }[];
    if (!mine.length) return json({ error: "That account is not set up as a practitioner." }, 403);
    if (!mine.some((r) => r.plugins && r.plugins.ai_summary === true)) return json({ error: "Switch on the AI summary plugin first." }, 403);
    const { data: link } = await db.from("practitioner_clients").select("id")
      .eq("id", body.link_id).eq("status", "active").in("practitioner_id", mine.map((r) => r.id)).maybeSingle();
    if (!link) return json({ error: "That client is not linked to you." }, 403);
    if (!OPENAI_KEY) return json({ error: "Not available right now." }, 503);

    if (body.action === "transcribe") {
      const b64 = String(body.audio || "");
      if (!b64 || b64.length > MAX_AUDIO_B64) return json({ error: "Bad audio." }, 400);
      const mp4 = String(body.mime || "").includes("mp4");
      const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
      const form = new FormData();
      form.append("model", "gpt-4o-mini-transcribe");
      form.append("file", new Blob([bytes], { type: mp4 ? "audio/mp4" : "audio/webm" }), mp4 ? "part.m4a" : "part.webm");
      const res = await fetch("https://api.openai.com/v1/audio/transcriptions", {
        method: "POST", headers: { Authorization: `Bearer ${OPENAI_KEY}` }, body: form,
      });
      if (!res.ok) {
        console.error("session-recorder transcribe", res.status, (await res.text()).slice(0, 300));
        return json({ error: "Not available right now." }, 502);
      }
      return json({ text: String((await res.json())?.text || "").trim() });
    }

    if (body.action === "note") {
      const text = String(body.text || "").slice(0, MAX_TEXT).trim();
      if (!text) return json({ error: "Nothing to write up." }, 400);
      const res = await fetch("https://api.openai.com/v1/chat/completions", {
        method: "POST",
        headers: { Authorization: `Bearer ${OPENAI_KEY}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          model: "gpt-4o-mini", temperature: 0.2, max_tokens: 900,
          messages: [{ role: "system", content: SYSTEM + languageDirective(asLang(body.lang)) }, { role: "user", content: text }],
        }),
      });
      if (!res.ok) {
        console.error("session-recorder note", res.status, (await res.text()).slice(0, 300));
        return json({ error: "Not available right now." }, 502);
      }
      const note = String((await res.json())?.choices?.[0]?.message?.content || "").trim();
      if (!note) return json({ error: "Not available right now." }, 502);
      return json({ note });
    }
    return json({ error: "unknown action" }, 400);
  } catch (e) {
    console.error("session-recorder", e);
    return json({ error: "Something went wrong" }, 500);
  }
});
