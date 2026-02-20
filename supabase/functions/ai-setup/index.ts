// supabase/functions/ai-setup/index.ts
//
// Proxies AI setup requests to OpenAI GPT-4o-mini.
// OpenAI API key is stored in Supabase secrets — never exposed to client.
//
// Deploy: supabase functions deploy ai-setup --no-verify-jwt

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const OPENAI_URL = "https://api.openai.com/v1/chat/completions";
const MODEL = "gpt-4o-mini";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
};

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    // ── Auth ──
    const authHeader = req.headers.get("Authorization");
    if (!authHeader) {
      return new Response(JSON.stringify({ error: "Missing authorization" }), {
        status: 401,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const supabase = createClient(
      Deno.env.get("SUPABASE_URL") ?? "",
      Deno.env.get("SUPABASE_ANON_KEY") ?? "",
      { global: { headers: { Authorization: authHeader } } }
    );

    const {
      data: { user },
      error: authError,
    } = await supabase.auth.getUser();
    if (authError || !user) {
      return new Response(JSON.stringify({ error: "Unauthorized" }), {
        status: 401,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // ── Parse request ──
    const { system_prompt, user_message } = await req.json();
    if (!system_prompt || !user_message) {
      return new Response(
        JSON.stringify({ error: "Missing system_prompt or user_message" }),
        {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        }
      );
    }

    // ── OpenAI key from secrets ──
    const openaiKey = Deno.env.get("OPENAI_API_KEY");
    if (!openaiKey) {
      console.error("OPENAI_API_KEY not set");
      return new Response(
        JSON.stringify({ error: "AI service not configured" }),
        {
          status: 500,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        }
      );
    }

    // ── Call OpenAI ──
    console.log(
      `AI setup for ${user.id} — sys: ${system_prompt.length}c, usr: ${user_message.length}c`
    );

    const openaiRes = await fetch(OPENAI_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${openaiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: MODEL,
        max_tokens: 8000,
        temperature: 0.3,
        messages: [
          { role: "system", content: system_prompt },
          { role: "user", content: user_message },
        ],
      }),
    });

    if (!openaiRes.ok) {
      const errText = await openaiRes.text();
      console.error(`OpenAI ${openaiRes.status}: ${errText}`);
      return new Response(
        JSON.stringify({ error: `OpenAI error: ${openaiRes.status}` }),
        {
          status: 502,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        }
      );
    }

    const data = await openaiRes.json();
    const content = data.choices?.[0]?.message?.content;
    if (!content) {
      return new Response(
        JSON.stringify({ error: "No content in AI response" }),
        {
          status: 502,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        }
      );
    }

    // Log cost
    const u = data.usage;
    if (u) {
      const cost =
        (u.prompt_tokens * 0.15 + u.completion_tokens * 0.6) / 1_000_000;
      console.log(
        `Done ${user.id} — in:${u.prompt_tokens} out:${u.completion_tokens} $${cost.toFixed(6)}`
      );
    }

    // Clean and validate
    const clean = content
      .replace(/```json/g, "")
      .replace(/```/g, "")
      .trim();

    try {
      JSON.parse(clean);
    } catch {
      console.error("Invalid JSON from AI:", clean.substring(0, 200));
      return new Response(
        JSON.stringify({ error: "AI returned invalid response" }),
        {
          status: 502,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        }
      );
    }

    return new Response(clean, {
      status: 200,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  } catch (err) {
    console.error("ai-setup error:", err);
    return new Response(
      JSON.stringify({ error: (err as Error).message ?? "Internal error" }),
      {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      }
    );
  }
});