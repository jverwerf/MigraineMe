// supabase/functions/classify-food-risks/index.ts
//
// Classifies tyramine, alcohol, and gluten exposure for a food item using GPT-4o-mini.
// Checks cache first; on miss, calls OpenAI for ALL THREE at once and caches results.
//
// POST body: { "food_name": "aged cheddar" }
// Response:  { "tyramine": "high", "alcohol": "none", "gluten": "low", "cached": true }

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const OPENAI_API_KEY = Deno.env.get("OPENAI_API_KEY")!
const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!

const SYSTEM_PROMPT = `You are a food risk classifier for a migraine tracking app.
Given a food name, classify THREE risk dimensions. Respond with EXACTLY three words separated by commas: tyramine,alcohol,gluten

Each must be one of: none, low, medium, high

TYRAMINE guidelines:
- high: aged/mature cheeses (cheddar, parmesan, brie, camembert, blue cheese, gouda, gruyère, stilton), cured/fermented meats (salami, pepperoni, chorizo, prosciutto, soppressata, nduja, summer sausage, dried sausage, jerky), fermented foods (kimchi, sauerkraut, miso, tempeh, natto, fish sauce, soy sauce, teriyaki), red wine, draft/tap beer, kombucha, aged/smoked fish, liver (not fresh)
- medium: chocolate, cocoa, avocado, banana, overripe fruit, citrus, yogurt, sour cream, buttermilk, bottled beer, white wine, champagne, sourdough bread, olives, pickles, vinegar, peanuts, broad/fava beans, spinach, tomato (especially canned/paste), eggplant, raspberries
- low: fresh mozzarella, ricotta, cream cheese, cottage cheese, fresh milk, butter, eggs, fresh bread, most fresh fruits, most fresh vegetables, rice, pasta, oats, fresh meat (chicken, beef, pork, fish — not aged/cured/smoked), tofu (not fermented), most nuts, honey, sugar, cooking oils
- none: water, plain rice, plain pasta (no sauce), salt, pepper, herbs, spices, plain flour, plain sugar

ALCOHOL guidelines:
- high: spirits/liquor (whisky, vodka, rum, gin, tequila), cocktails, fortified wine (port, sherry, marsala), liqueurs
- medium: red wine, white wine, champagne, full-strength beer, hard cider, hard seltzer, sake
- low: light beer, low-alcohol beer, cooking wine (small amount in dish), dishes flambéed or cooked with alcohol (most evaporates), tiramisu, beer-battered foods, rum cake, wine sauce
- none: non-alcoholic beer, water, juice, soft drinks, tea, coffee, milk, most foods without alcohol as ingredient

GLUTEN guidelines:
- high: bread, pasta, pizza, wheat flour, barley, rye, couscous, crackers, cookies, cake, pastries, beer (barley-based), seitan, wheat tortillas, breadcrumbs, pancakes, waffles, most breakfast cereals, pretzels, bagels
- medium: soy sauce (contains wheat), teriyaki sauce, oats (often cross-contaminated), malt/malt vinegar, some processed meats (fillers), gravy, breaded/battered foods, croutons, some soups (thickened with flour)
- low: foods with trace gluten or minor wheat-based additives, some condiments, some chips/crisps (malt flavoring), corn tortillas (if made in shared facility), naturally gluten-free grains that may have cross-contamination
- none: rice, corn, potatoes, quinoa, fresh meat/fish/poultry, eggs, dairy, fruits, vegetables, nuts, seeds, legumes, buckwheat, gluten-free labeled products, wine, spirits

If a food contains multiple components (e.g. "pepperoni pizza"), classify based on the highest-risk component for each dimension.
If unsure, lean towards the higher risk level.
Respond with ONLY three words separated by commas, e.g.: high,none,high`

const VALID_LEVELS = ["none", "low", "medium", "high"]

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

    // ── Premium check (requires valid user) ──
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
      .from("food_risk_cache")
      .select("tyramine, alcohol, gluten")
      .eq("food_name_lower", normalised)
      .maybeSingle()

    if (cached) {
      return new Response(
        JSON.stringify({
          tyramine: cached.tyramine,
          alcohol: cached.alcohol,
          gluten: cached.gluten,
          cached: true
        }),
        { headers: { "Content-Type": "application/json" } }
      )
    }

    // 2. Cache miss — call GPT-4o-mini (one call classifies all three)
    const openaiRes = await fetch("https://api.openai.com/v1/chat/completions", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${OPENAI_API_KEY}`,
      },
      body: JSON.stringify({
        model: "gpt-4o-mini",
        max_tokens: 10,
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
        JSON.stringify({ tyramine: "none", alcohol: "none", gluten: "none", cached: false, error: "classification_failed" }),
        { headers: { "Content-Type": "application/json" } }
      )
    }

    const openaiData = await openaiRes.json()
    const raw = openaiData.choices?.[0]?.message?.content?.trim()?.toLowerCase() ?? "none,none,none"

    // Parse "high,none,medium" format
    const parts = raw.split(",").map((s: string) => s.trim())
    const tyramine = VALID_LEVELS.includes(parts[0]) ? parts[0] : "none"
    const alcohol = VALID_LEVELS.includes(parts[1]) ? parts[1] : "none"
    const gluten = VALID_LEVELS.includes(parts[2]) ? parts[2] : "none"

    // 3. Write to cache (fire and forget)
    supabase
      .from("food_risk_cache")
      .upsert(
        { food_name_lower: normalised, tyramine, alcohol, gluten },
        { onConflict: "food_name_lower" }
      )
      .then(({ error }) => {
        if (error) console.error("Cache write error:", error.message)
      })

    return new Response(
      JSON.stringify({ tyramine, alcohol, gluten, cached: false }),
      { headers: { "Content-Type": "application/json" } }
    )
  } catch (e) {
    console.error("classify-food-risks error:", e)
    return new Response(
      JSON.stringify({ tyramine: "none", alcohol: "none", gluten: "none", cached: false, error: e.message }),
      { status: 500, headers: { "Content-Type": "application/json" } }
    )
  }
})