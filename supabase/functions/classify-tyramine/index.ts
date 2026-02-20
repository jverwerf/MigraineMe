// supabase/functions/classify-tyramine/index.ts
//
// Classifies tyramine exposure risk for a food item using GPT-4o-mini.
// Checks cache first; on miss, calls OpenAI and caches the result.
//
// POST body: { "food_name": "aged cheddar" }
// Response:  { "risk": "high", "cached": true }

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const OPENAI_API_KEY = Deno.env.get("OPENAI_API_KEY")!
const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!

const SYSTEM_PROMPT = `You are a tyramine exposure classifier for a migraine tracking app.
Given a food name, classify its tyramine exposure risk as exactly one word: none, low, medium, or high.

Guidelines:
- high: aged/mature cheeses (cheddar, parmesan, brie, camembert, blue cheese, gouda, gruyère, stilton), cured/fermented meats (salami, pepperoni, chorizo, prosciutto, soppressata, nduja, summer sausage, dried sausage, jerky), fermented foods (kimchi, sauerkraut, miso, tempeh, natto, fish sauce, soy sauce, teriyaki), red wine, draft/tap beer, kombucha, aged/smoked fish, liver (not fresh)
- medium: chocolate, cocoa, avocado, banana, overripe fruit, citrus, yogurt, sour cream, buttermilk, bottled beer, white wine, champagne, sourdough bread, olives, pickles, vinegar, peanuts, broad/fava beans, spinach, tomato (especially canned/paste), eggplant, raspberries
- low: fresh mozzarella, ricotta, cream cheese, cottage cheese, fresh milk, butter, eggs, fresh bread, most fresh fruits, most fresh vegetables, rice, pasta, oats, fresh meat (chicken, beef, pork, fish — not aged/cured/smoked), tofu (not fermented), most nuts, honey, sugar, cooking oils
- none: water, plain rice, plain pasta (no sauce), salt, pepper, herbs, spices, plain flour, plain sugar

If a food item contains multiple components (e.g. "pepperoni pizza"), classify based on the highest-risk component.
If unsure, lean towards the higher risk level.
Respond with ONLY one word: none, low, medium, or high.`

serve(async (req) => {
  try {
    // ── Auth ──
    const authHeader = req.headers.get("Authorization") ?? "";
    const jwt = authHeader.replace("Bearer ", "").trim();
    let userId: string | null = null;

    if (jwt) {
      try {
        const payload = JSON.parse(atob(jwt.split(".")[1]));
        userId = payload.sub ?? null;
      } catch { /* invalid JWT */ }
    }

    // ── Premium check ──
    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY)

    if (userId) {
      try {
        const { data: premiumRow, error: premiumErr } = await supabase
          .from("premium_status")
          .select("trial_end, rc_subscription_status")
          .eq("user_id", userId)
          .maybeSingle();

        if (!premiumErr) {
          const trialActive = premiumRow?.trial_end && new Date(premiumRow.trial_end) > new Date();
          const subActive = premiumRow?.rc_subscription_status === "active" || premiumRow?.rc_subscription_status === "grace_period";
          if (!trialActive && !subActive) {
            return new Response(
              JSON.stringify({ ok: false, error: "premium_required", message: "This feature requires a premium subscription." }),
              { status: 403, headers: { "Content-Type": "application/json" } },
            );
          }
        }
      } catch {
        // fail open
      }
    }

    const { food_name } = await req.json()

    if (!food_name || typeof food_name !== "string" || food_name.trim().length === 0) {
      return new Response(
        JSON.stringify({ error: "food_name is required" }),
        { status: 400, headers: { "Content-Type": "application/json" } }
      )
    }

    const normalised = food_name.trim().toLowerCase()

    // 1. Check cache
    const { data: cached } = await supabase
      .from("tyramine_food_cache")
      .select("risk_level")
      .eq("food_name_lower", normalised)
      .maybeSingle()

    if (cached) {
      return new Response(
        JSON.stringify({ risk: cached.risk_level, cached: true }),
        { headers: { "Content-Type": "application/json" } }
      )
    }

    // 2. Cache miss — call GPT-4o-mini
    const openaiRes = await fetch("https://api.openai.com/v1/chat/completions", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${OPENAI_API_KEY}`,
      },
      body: JSON.stringify({
        model: "gpt-4o-mini",
        max_tokens: 5,
        temperature: 0,
        messages: [
          { role: "system", content: SYSTEM_PROMPT },
          { role: "user", content: normalised },
        ],
      }),
    })

    if (!openaiRes.ok) {
      const err = await openaiRes.text()
      console.error("OpenAI error:", err)
      return new Response(
        JSON.stringify({ risk: "none", cached: false, error: "classification_failed" }),
        { headers: { "Content-Type": "application/json" } }
      )
    }

    const openaiData = await openaiRes.json()
    const raw = openaiData.choices?.[0]?.message?.content?.trim()?.toLowerCase() ?? "none"

    // Validate response
    const validLevels = ["none", "low", "medium", "high"]
    const risk = validLevels.includes(raw) ? raw : "none"

    // 3. Write to cache (fire and forget, don't block response)
    supabase
      .from("tyramine_food_cache")
      .upsert({ food_name_lower: normalised, risk_level: risk }, { onConflict: "food_name_lower" })
      .then(({ error }) => {
        if (error) console.error("Cache write error:", error.message)
      })

    return new Response(
      JSON.stringify({ risk, cached: false }),
      { headers: { "Content-Type": "application/json" } }
    )
  } catch (e) {
    console.error("classify-tyramine error:", e)
    return new Response(
      JSON.stringify({ risk: "none", cached: false, error: e.message }),
      { status: 500, headers: { "Content-Type": "application/json" } }
    )
  }
})