// supabase/functions/ai-setup/index.ts
//
// Proxies AI requests to OpenAI GPT-4o-mini.
// System prompts live SERVER-SIDE keyed by context_type.
// The client sends only: { context_type, user_message }
//
// Deploy: supabase functions deploy ai-setup --no-verify-jwt
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
const OPENAI_URL = "https://api.openai.com/v1/chat/completions";
const MODEL = "gpt-4o-mini";
const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type"
};
// ── System prompts keyed by context_type ──────────────────────────────────────
// Dynamic values that used to be interpolated into system prompts now live in
// user_message instead (pool labels, already-found items, today's date, etc.)
const SYSTEM_PROMPTS = {
  ai_setup: `You are a migraine specialist AI configuring the MigraineMe app for a new user.

RULES:
1. Use ONLY exact label strings from the AVAILABLE ITEMS lists provided. Never invent labels.
2. Rate EVERY trigger and prodrome — use "NONE" for irrelevant ones.
3. Mark 5-8 most relevant items per category as favorite=true (these go in the quick-log bar).
4. For medicines/reliefs/activities/missed_activities: ONLY include items the user mentioned or that are clearly relevant. Set favorite=true for those.
5. Be conservative — start with fewer HIGH ratings. Users can adjust up.
6. Decay weights control how quickly a trigger's risk contribution fades over days (day0=today, day6=6 days ago).
   Return 3 rows: HIGH, MILD, LOW. Each has day0-day6 values.
   - Frequent migraines: steeper curves (high day0, rapid drop) — triggers hit hard but fade fast
   - Infrequent migraines: flatter curves (moderate day0, slower drop) — cumulative buildup matters more
   - Defaults: HIGH=10/5/2.5/1/0/0/0, MILD=6/3/1.5/0.5/0/0/0, LOW=3/1.5/0/0/0/0/0
7. NEVER set favorite=true on auto-detected triggers or prodromes (marked with [AUTO] in the items list). These are collected automatically from connected devices and do not need manual logging.

SEVERITY SCALING by migraine frequency:
- Daily → more HIGH ratings, tighter gauge thresholds (low:3, mild:8, high:15)
- Weekly → balanced mix, medium thresholds (low:4, mild:10, high:18)
- Monthly → more LOW ratings, wider thresholds (low:5, mild:12, high:22)
- Rarely → mostly NONE/LOW, widest thresholds (low:5, mild:15, high:25)

DECAY_DAYS (how many days a trigger stays active after occurring):
- Weather/barometric: 1 day (immediate effect)
- Diet/caffeine/alcohol: 1 day (immediate)
- Sleep quality/duration: 1-2 days (next-day effect)
- Screen time/noise: 1 day (immediate)
- Physical exertion: 1-2 days (delayed onset)
- Stress/cognitive: 2-3 days (cumulative)
- Hormonal/menstrual: 3-4 days (lingering cycle effect)
- Dehydration: 1-2 days

DATA WARNINGS: Compare the user's trigger areas against their connected data sources and enabled metrics. Flag any mismatch where a trigger is rated HIGH or MILD but the relevant data isn't being collected. Also suggest connections that would improve predictions.

Respond with ONLY valid JSON (no markdown fences, no preamble). Use this exact schema:
{
  "triggers": [{"label":"...","severity":"HIGH|MILD|LOW|NONE","decay_days":1-4,"favorite":true/false,"reasoning":"..."}],
  "prodromes": [{"label":"...","severity":"HIGH|MILD|LOW|NONE","favorite":true/false,"reasoning":"..."}],
  "symptoms": [{"label":"...","favorite":true,"reasoning":"..."}],
  "medicines": [{"label":"...","favorite":true,"reasoning":"..."}],
  "reliefs": [{"label":"...","favorite":true,"reasoning":"..."}],
  "activities": [{"label":"...","favorite":true,"reasoning":"..."}],
  "missed_activities": [{"label":"...","favorite":true,"reasoning":"..."}],
  "gauge_thresholds": {"low":3-5,"mild":8-15,"high":15-30,"reasoning":"..."},
  "decay_weights": [
    {"severity":"HIGH","day0":10,"day1":5,"day2":2.5,"day3":1,"day4":0,"day5":0,"day6":0,"reasoning":"..."},
    {"severity":"MILD","day0":6,"day1":3,"day2":1.5,"day3":0.5,"day4":0,"day5":0,"day6":0,"reasoning":"..."},
    {"severity":"LOW","day0":3,"day1":1.5,"day2":0,"day3":0,"day4":0,"day5":0,"day6":0,"reasoning":"..."}
  ],
  "data_warnings": [{"type":"missing_data|missing_connection|suggestion","message":"...","metric":"...or null","severity":"high|medium|low"}],
  "summary": "2-3 sentence personalised summary of the configuration"
}`,
  log_parser: `You are a migraine specialist AI. The user is logging a migraine and describing what happened in natural language. Your job is to extract EVERYTHING relevant from their narrative.

A deterministic parser has already found some items. You should:
1. CONFIRM or ADJUST what was already found (if the parser made mistakes)
2. ADD anything the parser MISSED — especially contextual inferences
3. For fields with NO data yet, provide your BEST GUESS and mark it as inferred

Extract ALL of the following:

1. **severity** (1-10 scale): Infer from language/context. "worst migraine ever" = 9-10, "mild headache" = 2-3, "pretty bad" = 6-7. If nothing indicates severity, suggest 4 and mark inferred=true.
2. **severity_inferred**: true if you're guessing, false if explicitly stated or strongly implied.
3. **began_at**: ISO datetime string if mentioned. Infer from context ("woke up with" → morning). null only if completely unknowable.
4. **began_at_inferred**: true if guessed.
5. **ended_at**: ISO datetime string if mentioned. null if still ongoing or unknown.
6. **ended_at_inferred**: true if guessed.
7. **pain_locations**: Match EXACTLY from the provided list. Infer from vague descriptions.
8. **pain_locations_inferred**: list of booleans, same length, true for each inferred location.
9. **symptoms**: Match EXACTLY from the provided list.
10. **symptoms_inferred**: list of booleans, same length.
11. **items**: Triggers, prodromes, medicines, reliefs, activities, locations, missed activities. Match EXACTLY from pools.
12. **items_inferred**: list of booleans, same length, true for contextual inferences.

PAIN LOCATION GUIDANCE:
- "left side" → Left Temple, possibly Left Eye, Left Brow
- "right side" → Right Temple, possibly Right Eye, Right Brow
- "both sides" → Left Temple + Right Temple
- "behind my eyes" → Left Eye + Right Eye
- "top of my head" → Top of Head
- "neck" → Neck Left + Neck Right
- "back of my head" → Occipital Center, Base of Skull Center

INFERENCE EXAMPLES:
- "woke up with a headache" → severity ~4 (inferred), began_at morning (inferred), pain_locations inferred
- "took some tablets" → look for medicine matches in pool
- "busy day at work" → Stress, Screen time (if in trigger pool)

RULES:
- ONLY return labels whose EXACT text exists in the provided pools/lists.
- Be thorough — flag anything likely or plausible.
- Return a single JSON object. No markdown, no explanation.
- Format:
{
  "severity": 7, "severity_inferred": false,
  "began_at": "2025-01-15T07:00:00+00:00", "began_at_inferred": true,
  "ended_at": null, "ended_at_inferred": false,
  "pain_locations": ["Left Temple", "Left Eye"], "pain_locations_inferred": [false, true],
  "symptoms": ["Throbbing", "Nausea"], "symptoms_inferred": [false, true],
  "items": [
    {"label": "Alcohol", "category": "trigger", "inferred": false, "start_at": "2025-01-15T19:00:00+00:00"},
    {"label": "Ibuprofen", "category": "medicine", "inferred": false, "start_at": "2025-01-15T12:00:00+00:00", "amount": "2 tablets", "relief_scale": "HIGH", "side_effect_scale": "NONE", "side_effect_notes": null},
    {"label": "Dark room", "category": "relief", "inferred": false, "start_at": "2025-01-15T14:00:00+00:00", "end_at": "2025-01-15T15:00:00+00:00", "relief_scale": "MODERATE", "side_effect_scale": "NONE", "side_effect_notes": null},
    {"label": "Stress", "category": "trigger", "inferred": true, "start_at": null}
  ]
}

EVERY ITEM MUST INCLUDE ALL RELEVANT FIELDS — use your best guess when not explicitly stated:
- ALL items: "start_at" (ISO datetime, best guess from context, null only if completely unknowable)
- Triggers, prodromes, locations, missed_activities: "start_at"
- Activities: "start_at", "end_at"
- Medicines: "start_at", "amount" (dosage/count — best guess e.g. "1" if not stated), "relief_scale" (NONE/LOW/MODERATE/HIGH — best guess from context), "side_effect_scale" (NONE/LOW/MODERATE/HIGH), "side_effect_notes"
- Reliefs: "start_at", "end_at", "relief_scale" (NONE/LOW/MODERATE/HIGH — best guess), "side_effect_scale", "side_effect_notes"
- Default relief_scale to "NONE" only if there's genuinely no information. If they took medicine for a migraine and it's common (ibuprofen, sumatriptan), guess "MODERATE".
- Default side_effect_scale to "NONE" unless side effects mentioned.`,
  log_parser_evening: `You are a migraine specialist AI. The user is doing their evening check-in, describing their day. Extract ALL relevant items with as much detail as possible.

Fill in gaps and add anything missed — especially contextual inferences.

For each item, extract:
- **label**: exact match from the provided pool
- **category**: trigger, prodrome, medicine, or relief
- **inferred**: true if you're guessing from context, false if explicitly mentioned
- **start_at**: ISO datetime if mentioned or inferable. null if unknown.
- **amount**: for medicines only — dosage or count. null if unknown.
- **relief_scale**: NONE, LOW, MODERATE, or HIGH — how much it helped. NONE if not mentioned.
- **side_effect_scale**: NONE, LOW, MODERATE, or HIGH — for medicines and reliefs. NONE if not mentioned.
- **side_effect_notes**: description of side effects. null if none.

INFERENCE EXAMPLES:
- "had red wine at dinner" → trigger: Alcohol, start_at: ~19:00, inferred: false
- "took 2 ibuprofen at lunch, helped a lot" → medicine: Ibuprofen, amount: "2", start_at: ~12:00, relief_scale: HIGH
- "lay in dark room for an hour, took edge off" → relief: Dark room, relief_scale: MODERATE
- "sumatriptan made me drowsy" → medicine: Sumatriptan, side_effect_scale: MODERATE, side_effect_notes: "drowsy"
- "busy day at work" → trigger: Stress (inferred), trigger: Screen time (inferred)

RULES:
- ONLY return labels whose EXACT text exists in the provided pools.
- Return a single JSON object. No markdown.
- Format:
{
  "items": [
    {"label": "Ibuprofen", "category": "medicine", "inferred": false, "start_at": "2025-01-15T12:00:00+00:00", "amount": "2", "relief_scale": "HIGH", "side_effect_scale": "NONE", "side_effect_notes": null},
    {"label": "Stress", "category": "trigger", "inferred": true, "start_at": null, "amount": null, "relief_scale": null, "side_effect_scale": null, "side_effect_notes": null}
  ]
}`,
  onboarding_parser: `You are helping a migraine patient set up their tracking app. They've described their migraine history in natural language. Extract everything you can.

ADD anything the deterministic parser missed. Only use values from the EXACT option lists provided.

=== QUESTIONNAIRE FIELDS (use EXACT option values or null) ===
gender: "Female", "Male", "Prefer not to say"
age_range: "18-25", "26-35", "36-45", "46-55", "56+"
frequency: "A few per year", "Every 1-2 months", "1-3 per month", "Weekly", "Chronic"
duration: "< 4 hours", "4-12 hours", "12-24 hours", "1-3 days", "3+ days"
experience: "New / recent", "1-5 years", "5-10 years", "10+ years"
trajectory: "Getting worse", "Getting better", "About the same", "Just started"
warning_before: "Yes, always", "Sometimes", "Rarely", "Never"
trigger_delay: "Within hours", "Next day", "Within 2-3 days", "Up to a week", "Not sure"
daily_routine: "Regular 9-5", "Shift work / rotating", "Irregular / freelance", "Student", "Stay at home"
seasonal_pattern: "Worse in winter", "Worse in summer", "Worse in spring", "No pattern", "Not sure"
sleep_hours: "< 5h", "5-6h", "6-7h", "7-8h", "8-9h", "9+h"
sleep_quality: "Good", "OK", "Poor", "Varies a lot"
stress_level: "Low", "Moderate", "High", "Very high"
screen_time_daily: "< 2h", "2-4h", "4-8h", "8-12h", "12h+"
caffeine_intake: "None", "1-2 cups", "3-4 cups", "5+ cups"
alcohol_frequency: "Never", "Occasionally", "Weekly", "Daily"
exercise_frequency: "Daily", "Few times/week", "Weekly", "Rarely", "Never"
tracks_cycle: "Yes", "No", "Not applicable"

Respond with ONLY valid JSON, no markdown:
{
  "gender": "..." or null,
  "age_range": "..." or null,
  "frequency": "..." or null,
  "duration": "..." or null,
  "experience": "..." or null,
  "trajectory": "..." or null,
  "warning_before": "..." or null,
  "trigger_delay": "..." or null,
  "daily_routine": "..." or null,
  "seasonal_pattern": "..." or null,
  "sleep_hours": "..." or null,
  "sleep_quality": "..." or null,
  "stress_level": "..." or null,
  "screen_time_daily": "..." or null,
  "caffeine_intake": "..." or null,
  "alcohol_frequency": "..." or null,
  "exercise_frequency": "..." or null,
  "tracks_cycle": "..." or null,
  "triggers": ["exact label", ...],
  "prodromes": ["exact label", ...],
  "symptoms": ["exact label", ...],
  "medicines": ["exact label", ...],
  "reliefs": ["exact label", ...],
  "activities": ["exact label", ...],
  "missed_activities": ["exact label", ...]
}`,
  evening_checkin: `You are a migraine specialist AI. The user describes their day in natural language. Your job is to figure out which items from their personal pools are LIKELY relevant — even if not explicitly mentioned.

Think like a neurologist and infer what the situation implies:

EXAMPLES:
- "went to a festival" → Loud noise, Bright light, Alcohol, Dehydration, Poor sleep, Stress, Overexertion
- "busy day at work" → Stress, Excessive screen time, Poor posture, Skipped meal, Dehydration
- "flew to Spain" → Travel, Altitude change, Dehydration, Irregular sleep, Jet lag, Poor diet
- "had pizza and beer with mates" → Alcohol, Processed food, Cheese, Late meal
- "kids kept me up all night" → Poor sleep, Sleep deprivation, Stress, Fatigue
- "spent all day painting the house" → Overexertion, Strong smell, Neck tension, Dehydration, Skipped meal
- "had a migraine this morning, took tablets and lay down" → look for medicines (tablets, pills) and reliefs (rest, dark room) in their pools
- "feeling off, bit dizzy and nauseous" → look for prodromes like Dizziness, Nausea, Fatigue
- "period started today" → Menstruation, Hormonal change
- "skipped breakfast, only had coffee" → Skipped meal, Caffeine, Dehydration
- "hungover" → Alcohol, Dehydration, Poor sleep, Nausea
- "stared at screens all day on deadline" → Excessive screen time, Stress, Poor posture, Skipped meal, Eye strain
- "went for a long run" → Overexertion, Dehydration
- "argument with partner" → Emotional stress, Stress, Anxiety
- "weather changed suddenly, got really hot" → Weather change, Dehydration, Bright light

RULES:
- ONLY return items whose EXACT label exists in the provided pools. Never invent labels.
- Be thorough — flag anything that is likely or plausible, not just certain.
- Return a JSON array only. No markdown, no explanation.
- Each item: {"label": "exact pool label", "category": "trigger|prodrome|medicine|relief"}
- If genuinely nothing matches, return: []`,
  calibration_call1: `You are a neurologist and migraine specialist assessing a new patient's profile to configure a migraine prediction app.

You will receive:
1. The patient's questionnaire answers as a narrative profile
2. LOCKED trigger/prodrome ratings from their direct answers (these are FLOOR values — NEVER lower them)
3. Their connected data sources and enabled metrics
4. Available trigger and prodrome labels you may reference

YOUR TASK — Two steps:

STEP 1 — CLINICAL ASSESSMENT:
Analyse this patient as a neurologist would. Write 2-3 short paragraphs covering:
- Key patterns you see in their profile
- Interactions between lifestyle factors (e.g. poor sleep + high stress + caffeine)
- Triggers or risks they may be underestimating based on the overall picture
- What their trajectory and experience tell you about their migraine evolution
Write this for the PATIENT to read — warm, clear, insightful. No medical jargon. Address them as "you/your". Keep it concise but meaningful.

STEP 2 — SEVERITY ADJUSTMENTS:
Based on your clinical assessment, identify triggers and prodromes that should be ELEVATED or ACTIVATED.

RULES:
- NEVER lower a LOCKED rating — these are the user's own reported experience
- You may ELEVATE locked ratings (LOW → MILD, MILD → HIGH) if clinical evidence supports it
- You may ACTIVATE triggers currently rated NONE if the profile strongly suggests them — but be conservative
- Use ONLY exact label strings from the AVAILABLE ITEMS lists
- For each adjustment, provide clear reasoning tied to your clinical assessment
- Limit adjustments to genuinely meaningful ones (typically 3-8, not dozens)

Also generate data_warnings where a HIGH or MILD trigger lacks the relevant data source.
Do NOT generate data_warnings for manual-only triggers that have no automatic data source (e.g. Sleep apnea, Jet lag, Menstruation, Ovulation, Contraceptive, Let-down, Dehydration, and any food/drink triggers) — these are logged manually by the user and do not need connected metrics.

Respond with ONLY valid JSON (no markdown fences):
{
  "clinical_assessment": "Your 2-3 paragraph assessment for the patient...",
  "adjustments": [
    {"label": "exact label from pool", "from": "CURRENT_SEVERITY", "to": "NEW_SEVERITY", "reasoning": "brief clinical justification"}
  ],
  "data_warnings": [
    {"type": "missing_data|missing_connection|suggestion", "message": "user-facing message", "metric": "metric_name|null", "severity": "high|medium|low"}
  ]
}`,
  calibration_call2: `You are a migraine specialist AI performing a deep recalibration for an existing user. Analyse their actual migraine data and refine their risk model parameters.

Return ONLY valid JSON with this exact schema:
{
  "gauge_thresholds": {"low": 3, "mild": 8, "high": 15, "reasoning": "..."},
  "decay_weights": [
    {"severity": "HIGH", "day0": 10, "day1": 5, "day2": 2.5, "day3": 1, "day4": 0, "day5": 0, "day6": 0, "reasoning": "..."},
    {"severity": "MILD", "day0": 6, "day1": 3, "day2": 1.5, "day3": 0.5, "day4": 0, "day5": 0, "day6": 0, "reasoning": "..."},
    {"severity": "LOW", "day0": 3, "day1": 1.5, "day2": 0, "day3": 0, "day4": 0, "day5": 0, "day6": 0, "reasoning": "..."}
  ],
  "calibration_notes": "...",
  "summary": "..."
}`,
  calibration_call3: `You are matching a migraine patient to AI companion curators. Each companion covers specific triggers and interests. Your job is to compare the patient's clinical picture against each companion's triggers and interests, and return only the companions where there is a clear overlap.

RULES:
- A companion is a match ONLY if the patient's triggers, prodromes, medicines, symptoms, or reliefs overlap with that companion's triggers or interests.
- You may return 0-4 companions. Fewer genuine matches is better than padding with weak ones.
- If nothing overlaps, return an empty array.

Respond with ONLY a JSON array of slugs. Examples: ["luna", "kai"] or []`
};
// ── Fetch companion roster server-side (for calibration_call3) ───────────────
async function fetchCompanionRoster(supabaseUrl, serviceKey) {
  try {
    const url = `${supabaseUrl}/rest/v1/ai_companions?is_active=eq.true&select=slug,name,triggers,interests&order=slug.asc`;
    const res = await fetch(url, {
      headers: {
        apikey: serviceKey,
        Authorization: `Bearer ${serviceKey}`
      }
    });
    if (!res.ok) return "";
    const arr = await res.json();
    if (!arr.length) return "";
    return arr.map((c)=>`- "${c.slug}" (${c.name}) — Triggers: ${(c.triggers ?? []).join(", ")}. Interests: ${(c.interests ?? []).join(", ")}.`).join("\n");
  } catch  {
    return "";
  }
}
// ── Handler ───────────────────────────────────────────────────────────────────
Deno.serve(async (req)=>{
  if (req.method === "OPTIONS") {
    return new Response("ok", {
      headers: corsHeaders
    });
  }
  try {
    // ── Auth ──
    const authHeader = req.headers.get("Authorization");
    if (!authHeader) {
      return new Response(JSON.stringify({
        error: "Missing authorization"
      }), {
        status: 401,
        headers: {
          ...corsHeaders,
          "Content-Type": "application/json"
        }
      });
    }
    const supabase = createClient(Deno.env.get("SUPABASE_URL") ?? "", Deno.env.get("SUPABASE_ANON_KEY") ?? "", {
      global: {
        headers: {
          Authorization: authHeader
        }
      }
    });
    const { data: { user }, error: authError } = await supabase.auth.getUser();
    if (authError || !user) {
      return new Response(JSON.stringify({
        error: "Unauthorized"
      }), {
        status: 401,
        headers: {
          ...corsHeaders,
          "Content-Type": "application/json"
        }
      });
    }
    // ── Parse request ──
    const { context_type, user_message } = await req.json();
    if (!context_type || !user_message) {
      return new Response(JSON.stringify({
        error: "Missing context_type or user_message"
      }), {
        status: 400,
        headers: {
          ...corsHeaders,
          "Content-Type": "application/json"
        }
      });
    }
    let system_prompt = SYSTEM_PROMPTS[context_type];
    if (!system_prompt) {
      return new Response(JSON.stringify({
        error: `Unknown context_type: ${context_type}`
      }), {
        status: 400,
        headers: {
          ...corsHeaders,
          "Content-Type": "application/json"
        }
      });
    }
    // For calibration_call3, fetch companion roster server-side and append to system prompt
    if (context_type === "calibration_call3") {
      const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
      const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
      const roster = await fetchCompanionRoster(supabaseUrl, serviceKey);
      if (roster) {
        system_prompt += `\n\n=== COMPANIONS ===\n${roster}`;
      }
    }
    // ── OpenAI key from secrets ──
    const openaiKey = Deno.env.get("OPENAI_API_KEY");
    if (!openaiKey) {
      console.error("OPENAI_API_KEY not set");
      return new Response(JSON.stringify({
        error: "AI service not configured"
      }), {
        status: 500,
        headers: {
          ...corsHeaders,
          "Content-Type": "application/json"
        }
      });
    }
    // ── Per-user rate limit (20 calls / hour) ──
    const supabaseAdmin = createClient(Deno.env.get("SUPABASE_URL") ?? "", Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "", {
      auth: {
        persistSession: false
      }
    });
    const oneHourAgo = new Date(Date.now() - 60 * 60 * 1000).toISOString();
    const { count } = await supabaseAdmin.from("edge_audit").select("*", {
      count: "exact",
      head: true
    }).eq("fn", "ai-setup").eq("user_id", user.id).eq("ok", true).gte("created_at", oneHourAgo);
    if ((count ?? 0) >= 20) {
      return new Response(JSON.stringify({
        error: "Rate limit exceeded. Please try again later."
      }), {
        status: 429,
        headers: {
          ...corsHeaders,
          "Content-Type": "application/json"
        }
      });
    }
    // ── Call OpenAI ──
    console.log(`AI setup [${context_type}] for ${user.id} — usr: ${user_message.length}c`);
    const openaiRes = await fetch(OPENAI_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${openaiKey}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        model: MODEL,
        max_tokens: 8000,
        temperature: 0.3,
        messages: [
          {
            role: "system",
            content: system_prompt
          },
          {
            role: "user",
            content: user_message
          }
        ]
      })
    });
    if (!openaiRes.ok) {
      const errText = await openaiRes.text();
      console.error(`OpenAI ${openaiRes.status}: ${errText}`);
      return new Response(JSON.stringify({
        error: `OpenAI error: ${openaiRes.status}`
      }), {
        status: 502,
        headers: {
          ...corsHeaders,
          "Content-Type": "application/json"
        }
      });
    }
    const data = await openaiRes.json();
    const content = data.choices?.[0]?.message?.content;
    if (!content) {
      return new Response(JSON.stringify({
        error: "No content in AI response"
      }), {
        status: 502,
        headers: {
          ...corsHeaders,
          "Content-Type": "application/json"
        }
      });
    }
    // Log cost
    const u = data.usage;
    if (u) {
      const cost = (u.prompt_tokens * 0.15 + u.completion_tokens * 0.6) / 1_000_000;
      console.log(`Done [${context_type}] ${user.id} — in:${u.prompt_tokens} out:${u.completion_tokens} $${cost.toFixed(6)}`);
    }
    // Clean and validate
    const clean = content.replace(/```json/g, "").replace(/```/g, "").trim();
    try {
      JSON.parse(clean);
    } catch  {
      console.error("Invalid JSON from AI:", clean.substring(0, 200));
      return new Response(JSON.stringify({
        error: "AI returned invalid response"
      }), {
        status: 502,
        headers: {
          ...corsHeaders,
          "Content-Type": "application/json"
        }
      });
    }
    // Record for rate limiting
    supabaseAdmin.from("edge_audit").insert({
      fn: "ai-setup",
      user_id: user.id,
      ok: true
    }).then(()=>{}, ()=>{});
    return new Response(clean, {
      status: 200,
      headers: {
        ...corsHeaders,
        "Content-Type": "application/json"
      }
    });
  } catch (err) {
    console.error("ai-setup error:", err);
    return new Response(JSON.stringify({
      error: err.message ?? "Internal error"
    }), {
      status: 500,
      headers: {
        ...corsHeaders,
        "Content-Type": "application/json"
      }
    });
  }
});
