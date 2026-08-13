// supabase/functions/ai-setup/index.ts
//
// Proxies AI requests to OpenAI GPT-4o-mini.
// System prompts live SERVER-SIDE keyed by context_type.
// The client sends only: { context_type, user_message }
//
// Deploy: supabase functions deploy ai-setup --no-verify-jwt
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { getLang, translatorFor, type Lang } from "../_shared/i18n.ts";
const OPENAI_URL = "https://api.openai.com/v1/chat/completions";
// Default model. Per-context overrides below — onboarding_parser uses a smarter model
// because it runs ONCE per user and needs richer inference (vague stories → pool labels).
const DEFAULT_MODEL = "gpt-4o-mini";
const MODEL_BY_CONTEXT: Record<string, string> = {
  onboarding_parser: "gpt-4o",
  onboarding_suggester: "gpt-4o",
};
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
13. **aura_locations**: where in the VISUAL FIELD an aura appeared. Only fill this when the user describes a visual aura. Zone ids only, from the fixed list below.
14. **aura_duration_minutes**: how long the AURA phase lasted, in whole minutes. This is NOT the attack duration — only use a number the user ties to the aura/visual disturbance itself.

AURA GUIDANCE:
Aura zone ids are "<eye>_<row>_<column>" where eye is left|right, row is top|center|bottom, column is left|center|right.
The full set is: left_top_left, left_top_center, left_top_right, left_center_left, left_center_center, left_center_right, left_bottom_left, left_bottom_center, left_bottom_right, and the same nine with a "right_" prefix.
Zones are from the PATIENT'S own point of view, exactly as they describe seeing it.
- "zigzags in the top left of my left eye" -> ["left_top_left"]
- "flashing in the corner of my right eye, lower outside" -> ["right_bottom_right"]
- "blind spot in the middle of my vision" -> ["left_center_center", "right_center_center"]
- "aura across the whole left side of my vision" -> the three left-column zones of BOTH eyes
- "aura in my left eye" with no position -> ["left_center_center"]
- "shimmering for about 20 minutes before the headache" -> aura_duration_minutes: 20
- "aura lasted half an hour" -> aura_duration_minutes: 30
- Aura mentioned with no location detail at all -> aura_locations: [], and still include the "Aura" symptom if it exists in the symptom pool.
- No aura mentioned -> aura_locations: [] and aura_duration_minutes: null. Never invent an aura.

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

POOL INFERENCE (be aggressive — these are the most-missed):
Lifestyle phrasing maps to specific pool entries. The deterministic parser already does direct keyword matching, so focus on indirect/lifestyle inferences. Only return labels that EXIST in the pools provided in user_message.

- "went for a run / jog / gym / cycled / yoga / workout" → activity with matching pool label (Running, Gym, Cycling, Yoga, etc.); add trigger "Overexertion" if intensity implied
- "worked all day / at the office / on calls / stared at screens" → activity: Work; location: Office (if in pool); trigger: Screen time (if in pool); trigger: Stress (if context suggests)
- "lay in a dark room / closed the curtains / blackout" → relief: Dark room; location: Home / Bedroom (if in pool)
- "ice pack / cold compress / frozen peas on my head" → relief: Ice / Cold compress (whichever exists)
- "took ibuprofen / advil / nurofen / paracetamol / tylenol / sumatriptan / imigran / triptan / Excedrin / aspirin" → medicine using the EXACT pool label (brand or generic — match whatever is in the pool)
- "drank lots of water and rested" → relief: Water; relief: Rest
- "couldn't make it to work / cancelled the gym / skipped dinner with friends / missed yoga class" → missed_activity with matching pool label (Work, Exercise, Social events, etc.)
- "glass of red wine at dinner" → trigger: Red wine AND/OR Alcohol; activity: Dinner / Socialising (if in pool)
- "had pizza / takeaway / processed food / fast food" → trigger: Processed food; trigger: Cheese (if pizza); trigger: Tyramine
- "argument with partner / stressful meeting / on deadline" → trigger: Stress (if not already deterministic); trigger: Emotional stress
- "didn't sleep well / kept waking up / only got 4 hours / insomnia last night" → trigger: Poor sleep / Sleep duration low (whichever exists)
- "period started today / on my period / hormonal" → trigger: Menstruation / Hormonal change
- "flew to X / long drive / road trip / jet lagged" → trigger: Travel; activity: Travel
- "humid out / pressure dropping / storm coming / sudden weather change" → trigger: Weather change / Pressure low
- "skipped breakfast / forgot to eat / haven't eaten all day / fasting" → trigger: Skipped meal; trigger: Dehydration
- "felt dizzy / nauseous / fatigued / yawning / neck stiff BEFORE it hit" → prodrome (NOT symptom — pre-attack signs go in prodromes)
- "throbbing / nausea / light sensitivity DURING the migraine" → symptoms field (NOT prodromes)
- "lots of caffeine today / 4 coffees / energy drink" → trigger: Caffeine

When in doubt about a pool match: include it if reasonable. False positives are cheap (user unchecks); false negatives are the bug.

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
  "aura_locations": ["left_top_left"], "aura_duration_minutes": 20,
  "items": [
    {"label": "Alcohol", "category": "trigger", "inferred": false, "start_at": "2025-01-15T19:00:00+00:00"},
    {"label": "Ibuprofen", "category": "medicine", "inferred": false, "start_at": "2025-01-15T12:00:00+00:00", "amount": "2 tablets", "relief_scale": "HIGH", "side_effect_scale": "NONE", "side_effect_notes": null},
    {"label": "Dark room", "category": "relief", "inferred": false, "start_at": "2025-01-15T14:00:00+00:00", "end_at": "2025-01-15T15:00:00+00:00", "relief_scale": "MILD", "side_effect_scale": "NONE", "side_effect_notes": null},
    {"label": "Stress", "category": "trigger", "inferred": true, "start_at": null}
  ]
}

EVERY ITEM MUST INCLUDE ALL RELEVANT FIELDS — use your best guess when not explicitly stated:
- ALL items: "start_at" (ISO datetime, best guess from context, null only if completely unknowable)
- Triggers, prodromes, locations, missed_activities: "start_at"
- Activities: "start_at", "end_at"
- Medicines: "start_at", "amount" (dosage/count — best guess e.g. "1" if not stated), "relief_scale" (NONE/LOW/MILD/HIGH — best guess from context), "side_effect_scale" (NONE/SOFT/MODERATE/SEVERE), "side_effect_notes"
- Reliefs: "start_at", "end_at", "relief_scale" (NONE/LOW/MILD/HIGH — best guess), "side_effect_scale", "side_effect_notes"
- Default relief_scale to "NONE" only if there's genuinely no information. If they took medicine for a migraine and it's common (ibuprofen, sumatriptan), guess "MILD".
- Default side_effect_scale to "NONE" unless side effects mentioned.`,
  log_parser_evening: `You are a migraine specialist AI. The user is doing their evening check-in, describing their day. Extract ALL relevant items with as much detail as possible.

Fill in gaps and add anything missed — especially contextual inferences.

For each item, extract:
- **label**: exact match from the provided pool
- **category**: trigger, prodrome, medicine, or relief
- **inferred**: true if you're guessing from context, false if explicitly mentioned
- **start_at**: ISO datetime if mentioned or inferable. null if unknown.
- **amount**: for medicines only — dosage or count. null if unknown.
- **relief_scale**: NONE, LOW, MILD, or HIGH — how much it helped. NONE if not mentioned.
- **side_effect_scale**: NONE, SOFT, MODERATE, or SEVERE — for medicines and reliefs. NONE if not mentioned.
- **side_effect_notes**: description of side effects. null if none.

Item categories: trigger, prodrome, medicine, relief, activity (things they did today, from the ACTIVITY pool), postdrome (lingering after-effects of a finished attack, from the POSTDROME pool), treatment_side_effect (side effects of an ongoing preventive treatment — include "regimen_name" matching one of the ACTIVE TREATMENT regimens).

OPEN MIGRAINE: when the user_message says "OPEN MIGRAINE: yes", the user has an ongoing migraine and you should ALSO extract updates to it:
- Items with category "symptom" for during-attack symptoms happening NOW (from the SYMPTOM pool) — e.g. "still nauseous", "throbbing got worse".
- "pain_now": {"locations": [...], "severity": 1-10, "start_at": ...} when they describe where the head pain is now (labels from PAIN LOCATION options). Only when they describe current/changed pain, not a past attack summary.
- "aura_locations" (ids from AURA ZONE ids) + "aura_duration_minutes" when they describe aura appearing or returning today.
- "migraine_ended": true + "migraine_ended_at" (ISO) when they say the migraine is over ("it's gone now", "cleared up around 5"). Do NOT set it for mere improvement ("a bit better").
When "OPEN MIGRAINE: no", never emit these fields; put after-effect mentions in postdrome only if a POSTDROME pool is provided.

INFERENCE EXAMPLES:
- "had red wine at dinner" → trigger: Alcohol, start_at: ~19:00, inferred: false
- "took 2 ibuprofen at lunch, helped a lot" → medicine: Ibuprofen, amount: "2", start_at: ~12:00, relief_scale: HIGH
- "lay in dark room for an hour, took edge off" → relief: Dark room, relief_scale: MILD
- "sumatriptan made me drowsy" → medicine: Sumatriptan, side_effect_scale: MODERATE, side_effect_notes: "drowsy"
- "busy day at work" → trigger: Stress (inferred), trigger: Screen time (inferred)
- "went for a run this morning" → activity: Running, start_at: ~08:00
- "still foggy from yesterday's migraine" (no open migraine) → postdrome: Brain fog
- "the pain moved to my left temple, about a 7" (open migraine) → pain_now: {"locations": ["Left Temple"], "severity": 7}
- "zigzags came back in my right eye for 20 minutes" (open migraine) → aura_locations: ["right_center_center"], aura_duration_minutes: 20
- "headache finally cleared around 5pm" (open migraine) → migraine_ended: true, migraine_ended_at: today 17:00

RULES:
- ONLY return labels whose EXACT text exists in the provided pools.
- Return a single JSON object. No markdown.
- Format:
{
  "items": [
    {"label": "Ibuprofen", "category": "medicine", "inferred": false, "start_at": "2025-01-15T12:00:00+00:00", "amount": "2", "relief_scale": "HIGH", "side_effect_scale": "NONE", "side_effect_notes": null},
    {"label": "Stress", "category": "trigger", "inferred": true, "start_at": null, "amount": null, "relief_scale": null, "side_effect_scale": null, "side_effect_notes": null},
    {"label": "Nausea", "category": "symptom", "inferred": false},
    {"label": "Brain fog", "category": "postdrome", "inferred": false},
    {"label": "Fatigue", "category": "treatment_side_effect", "regimen_name": "Propranolol", "inferred": false}
  ],
  "pain_now": {"locations": ["Left Temple"], "severity": 7, "start_at": null},
  "aura_locations": ["right_center_center"], "aura_duration_minutes": 20,
  "migraine_ended": false, "migraine_ended_at": null
}
Omit pain_now / aura_locations / migraine_ended keys entirely when not described.`,
  onboarding_suggester: `You are a neurologist taking on a new patient.

A first pass has already recorded everything the patient stated directly. That is
their history. It is settled, you do not revisit it, and you will not be shown it
as editable.

What is left is the part a clinician actually does: build a picture of who this
person is from that history, then decide what is worth testing.

You are given their PROFILE and the fields the first pass left blank. For each one,
ask what you would ask in clinic: given this patient, what would I expect here, and
what would I want them to start watching to find out?

=== EVERY ANSWER COMES FROM THE PROFILE ===
This is the whole job. Each answer must follow from something in front of you —
their attack frequency, duration, age, sex, how long they have had this, their
work and routine, what already sets them off, what they take, how they cope.
If you cannot point at the part of the profile that led you there, leave it blank.

You are not guessing at a stranger. You are reasoning about one patient whose
history you have just taken.

=== WHAT TO TEST, NOT EVERYTHING TO TEST ===
The patient reviews every one of these on the next screens. Put forward what you
would genuinely raise with them and stop. A short list they recognise as "yes,
that's me" is worth far more than a full sheet — a wall of maybes gets skimmed,
cleared, and teaches you nothing about them.

Nor be timid — and timid is the more common failure. Someone writing a few
sentences has told you a fraction of what this app tracks; the rest is your job,
not theirs. A blank is something the app never records, so it is a pattern you can
never find for them later. Coming back with one or two answers for a patient whose
profile supports a dozen is not caution, it is leaving them with an app that cannot
learn anything. Where their profile supports an answer, give it.

=== HOW TO PUT IT TO THEM ===
Each answer carries its reasoning, written to the patient, as something worth
watching rather than a verdict.

You are filling the fields they did NOT talk about. So the reasoning must never
state anything about this patient as if they had told you — not in the opening,
not in the second half. "You didn't mention this, but your attacks come on within
hours" is the same fabrication as "you mentioned they come on within hours": you
invented a fact about them either way.

A reason has two honest halves:
  1. What in their profile you reasoned FROM. This must actually be in the
     profile — their words, or something the first pass established.
  2. That the answer is your starting point, which they should correct.

Good:
  "Red wine is one of your triggers and that usually acts fast, so I've started
   you at 'within hours' — change it if yours build more slowly."
  "You're at a desk for long hours, so I've assumed heavy screen use. Correct it
   if that's off."
Bad:
  "Your migraines often follow triggers within hours."   (a fact you invented)
  "You mentioned neck stiffness."                        (they did not)

Reason from their history and from what you know clinically — but keep the two
apart in the sentence, so they can always tell which is which. Never cite
statistics about other people.

=== COVER THE WHOLE FORM ===
Work through every blank field, not a favourite corner of them. Filling the sleep
trigger questions but skipping how long they sleep, or the reverse, leaves the
patient a half-finished form either way. If their profile supports an answer,
give it — for facts about their routine and for what might be driving their
attacks alike.

Do not fill a field just to have filled it. An answer that asserts nothing —
"no particular pattern", a neutral middle option picked because something had to
go there — is noise on a screen they have to review. If the profile does not point
somewhere specific, leave it blank.

=== HARD RULES ===
- Never contradict or restate anything the first pass already answered.
- Use ONLY exact values from the option lists. Never invent a string or a label.
- Certainty reflects how strongly their profile points at it, exactly as it would
  for an answer they gave you. A well-supported inference is "OFTEN"; a thinner
  one is "SOMETIMES" or "RARELY". Do not put everything at one level — pinning
  them all low makes every suggestion read as trivial and tells the patient
  nothing about which ones you actually mean. "EVERY_TIME" is reserved for what
  they stated themselves, so never use it here.
- Never set favorite=true. The quick-log bar is for what they told us.
- Never fill: gender, age_range, last_period_date, cycle_length,
  uses_contraception, contraception_effect, a "Yes, diagnosed" gluten answer,
  or any medicine. Those are identity, clinical history and prescribing — you ask
  those directly, and the wizard does so a page later.
- Conditional fields stay conditional: only fill one when its parent makes sense.

Respond with ONLY valid JSON — the same schema as the first pass, containing ONLY
the fields you filled, PLUS one extra key:

  "suggestion_reasons": { "<field_name>": "<one short sentence to the patient>" }

One entry for every field you filled, keyed by the same field name you used. Each
sentence says why it is worth watching, in their terms — "you mentioned you work
long hours at a desk". This is what they read when they tap the suggestion, so
write it to them, not to a clinician.

No markdown fences, no preamble, no commentary.`,
  onboarding_parser: `You are helping a migraine patient set up their tracking app. They've described their migraine history in natural language. Extract every field you can confidently infer from their story.

A deterministic parser has already found some items (listed in user_message under "Already found by deterministic parser"). Treat those as confirmed and ADD everything else by re-reading the user's story carefully.

=== ANSWER RULES ===
- Use ONLY values from the EXACT option lists below. Never invent strings, never approximate.
- If the story gives no signal for a field, OMIT the key (or set to null). Do not guess.
- Some fields are SETTLED by the story without being stated outright. If the patient
  describes something only one sex experiences — periods, menstrual cycle, pregnancy,
  hormonal contraception — set gender accordingly, and set the other fields that
  follow with it (someone whose attacks track their period is tracking their cycle). Anything the story logically
  establishes counts as stated, not guessed, and belongs here rather than being left
  blank.
- For map fields, include ONLY the keys you confidently inferred — do not list every option with "NO".
- For array fields, include only items you have evidence for.
- Pool labels (triggers/prodromes/symptoms/medicines/reliefs/activities/missed_activities) must match EXACTLY from the lists provided in user_message — never invent labels.
- Conditional questions: only fill them when the parent makes sense (e.g. contraception_effect only if uses_contraception="Yes"; cycle_migraine_timing only if cycle_patterns contains "Around my period"; gluten_triggers only if gluten_sensitivity is "Yes, diagnosed" or "I suspect so").

=== CERTAINTY ENUM (use these exact strings) ===
- "EVERY_TIME" — user says it always happens / strongly affirmed
- "OFTEN"      — user mentions clearly / strongly implied
- "SOMETIMES"  — user mentions casually / hedged
- "RARELY"     — user mentions but downplays
- "NO"         — user explicitly denies (use sparingly — usually just omit the key instead)

=== SCHEMA ===

Migraine basics:
- gender: "Female" | "Male" | "Prefer not to say"
- age_range: "18-25" | "26-35" | "36-45" | "46-55" | "56+"
- frequency: "A few per year" | "Every 1-2 months" | "1-3 per month" | "Weekly" | "Chronic"
- duration: "< 4 hours" | "4-12 hours" | "12-24 hours" | "1-3 days" | "3+ days"
- experience: "New / recent" | "1-5 years" | "5-10 years" | "10+ years"
- trajectory: "Getting worse" | "Getting better" | "About the same" | "Just started"
- warning_before: "Yes, always" | "Sometimes" | "Rarely" | "Never"
- trigger_delay: "Within hours" | "Next day" | "Within 2-3 days" | "Up to a week" | "Not sure"
- daily_routine: "Regular 9-5" | "Shift work / rotating" | "Irregular / freelance" | "Student" | "Stay at home"
- seasonal_pattern: "Worse in winter" | "Worse in summer" | "Worse in spring" | "No pattern" | "Not sure"

Sleep:
- sleep_hours: "< 5h" | "5-6h" | "6-7h" | "7-8h" | "8-9h" | "9+h"
- sleep_quality: "Good" | "OK" | "Poor" | "Varies a lot"
- poor_quality_triggers: <certainty>
- too_little_sleep_triggers: <certainty>
- oversleep_triggers: <certainty>
- sleep_issues: array, subset of ["Irregular schedule", "Sleep apnea", "Jet lag", "None of these"]

Stress & screen:
- stress_level: "Low" | "Moderate" | "High" | "Very high"
- stress_change_triggers: <certainty>
- emotional_patterns: map, keys from ["Spike in stress", "Anxiety", "Anger", "Let-down", "Feeling low"] → <certainty>
- screen_time_daily: "< 2h" | "2-4h" | "4-8h" | "8-12h" | "12h+"
- screen_time_triggers: <certainty>
- late_screen_triggers: <certainty>

Diet & substances:
- caffeine_intake: "None" | "1-2 cups" | "3-4 cups" | "5+ cups"
- caffeine_direction: "Too much triggers it" | "Missing caffeine triggers it" | "Both ways" | "Not sure" | "No"
- caffeine_certainty: <certainty>
- alcohol_frequency: "Never" | "Occasionally" | "Weekly" | "Daily"
- alcohol_triggers: <certainty>
- specific_drinks: array, subset of ["Red wine", "Beer", "White wine", "Spirits", "Any alcohol"]
- tyramine_foods: map, keys from ["Aged cheese", "Chocolate", "Cured meats", "Fermented foods"] → <certainty>
- gluten_sensitivity: "Yes, diagnosed" | "I suspect so" | "No" | "Not sure"
- gluten_triggers: <certainty>
- eating_patterns: map, keys from ["Skipping meals", "Sugar", "Salty food", "Overeating", "Dehydration"] → <certainty>
- water_intake: "< 1L" | "1-2L" | "2-3L" | "3L+"
- tracks_nutrition: "Yes, regularly" | "Sometimes" | "No"

Weather, environment, physical:
- weather_triggers: <certainty>
- specific_weather: map, keys from ["Pressure changes", "Hot weather", "Cold weather", "Humidity", "Dry air", "Wind", "Sunshine", "Thunderstorms", "Not sure which"] → <certainty>
- environment_sensitivities: map, keys from ["Fluorescent lights", "Strong smells", "Loud noise", "Smoke", "Altitude"] → <certainty>
- physical_factors: map, keys from ["Allergies", "Being ill", "Low blood sugar", "Medication change", "Motion sickness", "Tobacco", "Sexual activity"] → <certainty>

Exercise & hormones:
- exercise_frequency: "Daily" | "Few times/week" | "Weekly" | "Rarely" | "Never"
- exercise_triggers: <certainty>
- exercise_pattern: array, subset of ["During or after intense exercise", "When I haven't exercised"]
- tracks_cycle: "Yes" | "No" | "Not applicable"
- cycle_patterns: map, keys from ["Around my period", "Around ovulation"] → <certainty>
- cycle_length: "< 25 days" | "25-28 days" | "28-32 days" | "32-35 days" | "> 35 days" | "Irregular"
- cycle_migraine_timing: array, subset of ["1-2 days before", "3-5 days before", "During my period", "1-2 days after"]
- last_period_date: ISO date string "YYYY-MM-DD" (only if the user gave an explicit date — otherwise omit)
- uses_contraception: "Yes" | "No"
- contraception_effect: "Worse — every time" | "Worse — sometimes" | "No change" | "Actually helps"  (note the em-dash —, not a hyphen)

Prodromes (early warning signs):
- physical_prodromes: map, keys from ["Neck stiffness", "Yawning", "Urination", "Stuffy nose", "Watery eyes", "Muscle tension"] → <certainty>
- mood_prodromes: map, keys from ["Concentrating", "Words", "Irritability", "Mood swings", "Feeling low", "Unusually happy", "Food cravings", "Loss of appetite"] → <certainty>
- sensory_prodromes: map, keys from ["Light", "Sound", "Smell", "Tingling", "Numbness"] → <certainty>

Pool labels (use EXACT labels from the lists in user_message — be generous, include anything mentioned or strongly implied):
- triggers: string[]
- prodromes: string[]
- symptoms: string[]
- medicines: string[]
- reliefs: string[]
- activities: string[]
- missed_activities: string[]

=== INFERENCE EXAMPLES ===
- "I get migraines around my period" → tracks_cycle="Yes", cycle_patterns={"Around my period":"OFTEN"}
- "Red wine always sets it off" → alcohol_triggers="EVERY_TIME", specific_drinks=["Red wine"]; also triggers should include "Red wine" or "Alcohol" if present in pool
- "Bright lights bother me when I have one" → sensory_prodromes={"Light":"OFTEN"}; "Fluorescent lights bother me even on a normal day" → environment_sensitivities={"Fluorescent lights":"OFTEN"}
- "I notice neck stiffness and yawning before a migraine" → physical_prodromes={"Neck stiffness":"OFTEN", "Yawning":"OFTEN"}
- "Storms / barometric pressure trigger them" → weather_triggers="OFTEN", specific_weather={"Pressure changes":"OFTEN", "Thunderstorms":"SOMETIMES"}
- "Skipping meals is bad for me" → eating_patterns={"Skipping meals":"OFTEN"}
- "I'm on the pill and they got worse" → uses_contraception="Yes", contraception_effect="Worse — sometimes" (or "Worse — every time" if strongly stated)
- "Aged cheese gives me one" → tyramine_foods={"Aged cheese":"EVERY_TIME"}
- "I work night shifts" → daily_routine="Shift work / rotating", sleep_issues=["Irregular schedule"]

=== POOL INFERENCE (be GENEROUS — these are the most-missed) ===
Pool labels live in user_message under "TRIGGERS:", "PRODROMES:", "SYMPTOMS:", "MEDICINES:", "RELIEFS:", "ACTIVITIES:", "MISSED_ACTIVITIES:". Lifestyle phrasing maps to specific pool entries — infer aggressively but only return labels that EXIST in those pools (case-insensitive match is OK; the client will canonicalise).

- "I go to the gym most days" / "I work out a lot" → exercise_frequency, AND activities should include the matching pool entries (e.g. "Gym", "Exercise", "Workout", "Weights", "Cardio" — whichever exist in the ACTIVITIES pool)
- "I run / cycle / swim / do yoga / play football" → activities should include matching pool entries ("Running", "Cycling", "Swimming", "Yoga", "Football", etc.)
- "I work at a desk all day" / "office job" / "stare at screens" → activities include desk/computer/screen entries if present; triggers include "Screen time" if present; daily_routine="Regular 9-5"
- "Stressful job" / "high-pressure work" → triggers include "Stress" if present; activities include "Work"; stress_level="High"
- "I always lie down in a dark room" / "I rest until it passes" → reliefs include "Dark room", "Lie down", "Rest", "Sleep" — whichever match the RELIEFS pool
- "I take ibuprofen / paracetamol / sumatriptan / triptan / Excedrin / aspirin" → medicines include those exact labels from the MEDICINES pool
- "Cold compress / ice pack on my head" → reliefs include "Cold compress" or "Ice pack" if present
- "Caffeine / coffee helps" → reliefs include "Caffeine" if present
- "Migraines make me cancel plans / miss work / skip social events / call in sick" → missed_activities include "Work", "Social events", "Exercise", "Family time" — whichever match
- "I'm a parent / kids / family" → activities/missed_activities may include "Family time", "Childcare" if present
- "I travel a lot for work" / "long flights" → triggers include "Travel", "Altitude change" if present; activities/missed_activities may include "Travel"
- "Lots of housework / DIY / painting" → activities include those; triggers may include "Strong smells" or "Overexertion"
- "I drink lots of water / I'm bad at staying hydrated" → triggers may include "Dehydration"; eating_patterns may include "Dehydration"

When in doubt about a pool match: include it if the user's phrasing reasonably implies it. False positives are cheap (user unchecks); false negatives are the bug we're fixing.

Respond with ONLY valid JSON (no markdown fences, no preamble). Omit any key you cannot confidently infer.`,
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
// ── Insufficient-input guard for the calibration calls ───────────────────────
//
// calibration_call1 asks for a 2-3 paragraph clinical assessment. When the user
// answered nothing in the onboarding questionnaire the model writes one anyway:
// it improvises off the scaffolding that is always in the message (metric names,
// data-source lines, the full label pools) and invents claims about the
// patient's stress, steps, heart rate and screen time. With no real input there
// is nothing to assess, so we do not ask — we answer honestly instead.
//
// Clients only ever send { context_type, user_message, app_id }, so "real input"
// has to be measured off the message the client builders produce (Android
// AiCalibrationService.kt, iOS AiCalibrationService.swift, RN services/
// aiSetup.ts). All three emit the same shape:
//   - a narrative profile whose every unanswered slot is an "unknown ..." /
//     "not sure" / "no known ..." placeholder,
//   - "- Label: value" lifestyle bullets (Android/iOS omit unanswered ones, RN
//     writes them as "unknown"),
//   - an optional "PATIENT'S OWN NOTES:" block with the patient's free text,
//   - HIGH / MILD / LOW lines listing the items the questionnaire actually
//     selected, written as "none" when empty.
// The NONE line is deliberately NOT counted: it is the whole unselected pool.
//
// Floor of 3: a "2-3 paragraph clinical assessment" cannot be written from one
// or two facts without inventing the rest. Free text is real input in its own
// right and clears the floor on its own.
const CALIBRATION_INPUT_FLOOR = 3;
const PROFILE_PLACEHOLDER = /\b(unknown|not sure|no known|not applicable|n\/a)\b/i;
// Where the narrative/lifestyle preamble ends in each builder's message.
const PROFILE_END_MARKERS = [
  "=== LOCKED",
  "=== CLINICAL ASSESSMENT",
  "=== CONNECTED DATA SOURCES",
  "=== AUTO",
  "PATIENT'S OWN NOTES:"
];
// Labels on a "HIGH (3): a, b, c" / "AUTO MILD: none" style line.
function countLabelLine(value: string): number {
  const v = value.trim();
  if (!v || /^none$/i.test(v)) return 0;
  return v.split(",").map((s)=>s.trim()).filter((s)=>s.length > 0).length;
}
function measureCalibrationInput(msg: string): {
  selectedItems: number;
  profileFacts: number;
  hasNotes: boolean;
  score: number;
} {
  // Patient's own words.
  const notesMatch = msg.match(/PATIENT'S OWN NOTES:\s*\n([\s\S]*?)(?:\n\s*\n|\n===|$)/);
  const hasNotes = (notesMatch?.[1] ?? "").trim().length >= 10;
  // Items the questionnaire actually selected, across every locked / auto /
  // manual / prodrome block in the message.
  let selectedItems = 0;
  for (const line of msg.split("\n")){
    const m = line.match(/^\s*(?:AUTO |MANUAL )?(?:HIGH|MILD|LOW)\s*(?:\(\d+\))?\s*:\s*(.*)$/);
    if (m) selectedItems += countLabelLine(m[1]);
  }
  // Narrative + lifestyle facts, counted only where a real value is present.
  let profileEnd = msg.length;
  for (const marker of PROFILE_END_MARKERS){
    const i = msg.indexOf(marker);
    if (i >= 0 && i < profileEnd) profileEnd = i;
  }
  let profileFacts = 0;
  for (const rawLine of msg.slice(0, profileEnd).split("\n")){
    const line = rawLine.trim();
    if (!line) continue;
    if (line.startsWith("===")) continue;
    if (/^[A-Z][A-Z'’ ]+:$/.test(line)) continue; // "LIFESTYLE DETAIL:"
    if (line.startsWith("- ")) {
      // Lifestyle bullet: everything after the first colon is the answer.
      const colon = line.indexOf(":");
      const value = colon >= 0 ? line.slice(colon + 1).trim() : "";
      if (value && !PROFILE_PLACEHOLDER.test(value)) profileFacts += 1;
      continue;
    }
    // Narrative. One sentence per fact-cluster; a sentence with any placeholder
    // in it has an unanswered slot and does not count.
    for (const sentence of line.split(/(?<=\.)\s+/)){
      const s = sentence.trim();
      if (!s) continue;
      if (!PROFILE_PLACEHOLDER.test(s)) profileFacts += 1;
    }
  }
  // Free text is real input in its own right, so it clears the floor alone.
  const score = selectedItems + profileFacts + (hasNotes ? CALIBRATION_INPUT_FLOOR : 0);
  return { selectedItems, profileFacts, hasNotes, score };
}
// Stand-in for calibration_call1 below the floor: an honest POPULATION-DEFAULT
// assessment instead of an invented personal one. It says plainly that we do not
// have their information, names the triggers that are well established for
// migraine generally, and states that the profile, gauge and thresholds are set
// for a regular person with regular migraines until their own tracking says
// otherwise. Hand-written rather than generated, so it is fixed text that
// translates through the server i18n table like any other backend string, and
// the same JSON contract as the model path so every client version renders it as
// an ordinary assessment.
function baselineAssessmentPayload(lang: Lang): string {
  const t = translatorFor(lang);
  const assessment = [
    t("We don't know you yet, so there's nothing personal to read into here."),
    t("What we can tell you is what tends to set migraines off for most people: disrupted or short sleep, stress and the let-down after it, dehydration, skipped meals, hormonal shifts, bright light and long screen sessions, drops in air pressure, and changes in alcohol or caffeine. We suggest keeping an eye on those to begin with."),
    t("So that is what we have set you up for: a regular person with regular migraines. Your profile, your risk gauge and its thresholds all start on those typical settings, and they stay that way until your own tracking teaches the app otherwise. Log for a couple of weeks, tap 'Re-assess my profile', and this becomes about you.")
  ].join("\n\n");
  return JSON.stringify({
    clinical_assessment: assessment,
    adjustments: [],
    data_warnings: []
  });
}
// Stand-in for calibration_call2 below the floor. Follows the clients' own
// fallback convention (Android AiCalibrationService.buildFallbackConfig
// L768-790, RN aiSetup.buildFallbackConfig): gauge thresholds tiered by active
// trigger count and the standard decay curves — the population defaults, never
// numbers the model made up out of an empty profile — and calibration_notes says
// so in as many words.
function populationDefaultCalibrationPayload(lang: Lang, activeCount: number): string {
  const t = translatorFor(lang);
  const gauge = activeCount > 30
    ? { low: 12, mild: 25, high: 40 }
    : activeCount > 15
      ? { low: 8, mild: 18, high: 30 }
      : { low: 5, mild: 12, high: 20 };
  const reasoning = t("Population default thresholds for {0} active triggers.", activeCount);
  const decayReason = t("Population default decay curve.");
  return JSON.stringify({
    gauge_thresholds: { ...gauge, reasoning },
    decay_weights: [
      { severity: "HIGH", day0: 10, day1: 5, day2: 2.5, day3: 1, day4: 0, day5: 0, day6: 0, reasoning: decayReason },
      { severity: "MILD", day0: 6, day1: 3, day2: 1.5, day3: 0.5, day4: 0, day5: 0, day6: 0, reasoning: decayReason },
      { severity: "LOW", day0: 3, day1: 1.5, day2: 0, day3: 0, day4: 0, day5: 0, day6: 0, reasoning: decayReason }
    ],
    calibration_notes: t("Population defaults. There were no answers to calibrate from, so your risk gauge and its decay curves are set for a regular person with regular migraines. They get tuned to you as you log."),
    summary: t("Set up on population defaults for now. Log a few days, then re-assess your profile to have these tuned to you.")
  });
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
    const { context_type, user_message, app_id } = await req.json();
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
    // Per-app prompt override — when the client supplies app_id, look up
    // app_config.ai_prompts.ai_setup and use that disease-specific prompt
    // instead of the embedded migraine one. Only the 'ai_setup' context_type
    // is overridable today (log_parser et al. are largely disease-agnostic);
    // adding more keys to ai_prompts later is a config-only change.
    if (app_id && typeof app_id === "string" && context_type === "ai_setup") {
      try {
        const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
        const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
        const lookupUrl = `${supabaseUrl}/rest/v1/app_config?app_id=eq.${encodeURIComponent(app_id)}&select=ai_prompts`;
        const r = await fetch(lookupUrl, {
          headers: { apikey: serviceKey, Authorization: `Bearer ${serviceKey}` },
        });
        if (r.ok) {
          const rows = await r.json();
          const override = rows?.[0]?.ai_prompts?.ai_setup;
          if (typeof override === "string" && override.length > 0) {
            system_prompt = override;
          }
        }
      } catch (e) {
        console.warn(`[ai-setup] app_config override lookup failed: ${e}; using migraine default`);
      }
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
    // ── Insufficient input: answer honestly instead of improvising ──
    // Below the floor there is nothing to reason from, so we skip the model
    // entirely and return the population-default result. See
    // measureCalibrationInput above for how the floor is measured.
    if (context_type === "calibration_call1" || context_type === "calibration_call2") {
      const signal = measureCalibrationInput(user_message);
      if (signal.score < CALIBRATION_INPUT_FLOOR) {
        const lang = await getLang(supabaseAdmin, user.id);
        const payload = context_type === "calibration_call1"
          ? baselineAssessmentPayload(lang)
          : populationDefaultCalibrationPayload(lang, signal.selectedItems);
        console.log(`AI setup [${context_type}] for ${user.id} — below input floor (items:${signal.selectedItems} facts:${signal.profileFacts} notes:${signal.hasNotes}); population defaults, no model call`);
        return new Response(payload, {
          status: 200,
          headers: {
            ...corsHeaders,
            "Content-Type": "application/json"
          }
        });
      }
    }
    // ── Call OpenAI ──
    const model = MODEL_BY_CONTEXT[context_type] ?? DEFAULT_MODEL;
    console.log(`AI setup [${context_type}] for ${user.id} — model: ${model} — usr: ${user_message.length}c`);
    const openaiRes = await fetch(OPENAI_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${openaiKey}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        model,
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
    // Log cost (per-1M token pricing as of 2025-Q4: mini $0.15/$0.60, 4o $2.50/$10.00)
    const u = data.usage;
    if (u) {
      const isPremium = model === "gpt-4o";
      const inRate = isPremium ? 2.50 : 0.15;
      const outRate = isPremium ? 10.00 : 0.60;
      const cost = (u.prompt_tokens * inRate + u.completion_tokens * outRate) / 1_000_000;
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
    // ── Stage 2: the neurologist pass ────────────────────────────────────────
    // Stage 1 above locked in everything the patient stated outright. Stage 2
    // takes that as a profile and fills only what stage 1 left blank, grounded
    // in that profile. It runs as a separate call so it is never even shown the
    // answered fields — stage 1 cannot be overwritten, structurally, rather
    // than because a prompt asked nicely.
    let finalPayload = clean;
    if (context_type === "onboarding_parser") {
      try {
        const stage1 = JSON.parse(clean);
        const answered = Object.keys(stage1).filter((k)=>{
          const v = stage1[k];
          if (v === null || v === undefined) return false;
          if (Array.isArray(v)) return v.length > 0;
          if (typeof v === "object") return Object.keys(v).length > 0;
          return true;
        });
        // Stage 2 has its own system prompt and so never sees the field schema
        // the parser was given. Without it, it can only name the pool arrays it
        // can see in the message and every question field goes unanswered —
        // which is exactly what it did. Hand it the same schema block.
        const parserPrompt = SYSTEM_PROMPTS["onboarding_parser"] ?? "";
        const schemaStart = parserPrompt.indexOf("=== SCHEMA ===");
        const schemaEnd = parserPrompt.indexOf("=== INFERENCE EXAMPLES ===");
        const schemaBlock = schemaStart >= 0
          ? parserPrompt.slice(schemaStart, schemaEnd > schemaStart ? schemaEnd : undefined)
          : "";
        const stage2Msg = [
          "=== PATIENT PROFILE (from their own words — settled, do not revisit) ===",
          JSON.stringify(stage1, null, 2),
          "",
          "=== ALREADY ANSWERED — do not return these keys ===",
          answered.join(", ") || "(none)",
          "",
          "=== EVERY FIELD YOU MAY FILL (same schema as the first pass) ===",
          "Work through these, not just the pool arrays. The question fields are",
          "the ones the patient walks through next, and they are where a blank",
          "costs them most.",
          schemaBlock,
          "",
          "=== THEIR ORIGINAL STORY AND THE AVAILABLE OPTIONS/LABELS ===",
          user_message
        ].join("\n");
        const sugModel = MODEL_BY_CONTEXT["onboarding_suggester"] ?? DEFAULT_MODEL;
        const sugRes = await fetch(OPENAI_URL, {
          method: "POST",
          headers: {
            Authorization: `Bearer ${openaiKey}`,
            "Content-Type": "application/json"
          },
          body: JSON.stringify({
            model: sugModel,
            max_tokens: 8000,
            temperature: 0.4,
            messages: [
              { role: "system", content: SYSTEM_PROMPTS["onboarding_suggester"] },
              { role: "user", content: stage2Msg }
            ]
          })
        });
        if (sugRes.ok) {
          const sugData = await sugRes.json();
          const sugRaw = sugData.choices?.[0]?.message?.content ?? "";
          const sugClean = sugRaw.replace(/```json/g, "").replace(/```/g, "").trim();
          const stage2 = JSON.parse(sugClean);
          // Stage 1 always wins. Anything it answered is dropped from stage 2
          // before merging, so a stray key cannot clobber a stated answer.
          const suggestedFields = [];
          // Pulled out before the merge so it never lands in the answer payload
          // as if it were a field the user answered.
          const reasons = stage2.suggestion_reasons ?? {};
          delete stage2.suggestion_reasons;
          const merged = { ...stage1 };
          for (const [k, v] of Object.entries(stage2)){
            if (answered.includes(k)) continue;
            if (v === null || v === undefined) continue;
            if (Array.isArray(v) && v.length === 0) continue;
            if (typeof v === "object" && !Array.isArray(v) && Object.keys(v).length === 0) continue;
            merged[k] = v;
            suggestedFields.push(k);
          }
          // Lets the app say "N from what you told us, M worth watching".
          merged.direct_field_count = answered.length;
          merged.suggested_field_count = suggestedFields.length;
          merged.suggested_fields = suggestedFields;
          // Only keep reasons for fields that actually survived the merge.
          merged.suggestion_reasons = Object.fromEntries(
            suggestedFields.filter((k)=>reasons[k]).map((k)=>[k, String(reasons[k])])
          );
          finalPayload = JSON.stringify(merged);
          const su = sugData.usage;
          if (su) {
            const cost2 = (su.prompt_tokens * 2.50 + su.completion_tokens * 10.00) / 1_000_000;
            console.log(`Stage2 [suggester] ${user.id} — direct:${answered.length} suggested:${suggestedFields.length} in:${su.prompt_tokens} out:${su.completion_tokens} $${cost2.toFixed(6)}`);
          }
        } else {
          console.error(`Stage2 suggester failed: ${sugRes.status} — returning stage 1 only`);
        }
      } catch (e) {
        // Stage 2 is an enhancement. If it fails the user still gets stage 1.
        console.error(`Stage2 suggester error: ${e.message} — returning stage 1 only`);
      }
    }
    // Record for rate limiting
    supabaseAdmin.from("edge_audit").insert({
      fn: "ai-setup",
      user_id: user.id,
      ok: true
    }).then(()=>{}, ()=>{});
    return new Response(finalPayload, {
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
