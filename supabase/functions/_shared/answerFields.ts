// _shared/answerFields.ts
//
// The questionnaire answer registry: every field a user answers during AI
// setup, with the question they were asked, the closed option set, and the
// page ("entry") it lives on. Shared by recalibrate (which builds the
// questionnaire block of the prompt from it and validates the model's
// answer_updates against it) and apply-recalibration (which merges an
// accepted "answer" proposal back into ai_setup_profiles.answers).
//
// Two storage facts this module absorbs so callers never have to:
//   * KEY CASING — Android writes snake_case keys (AiSetupProfileStore),
//     iOS writes camelCase (AiSetupFlow.saveAiSetupProfile) into the same
//     jsonb column. Read with readAnswer(); write with answerKeyFor(), which
//     follows whatever casing the row already uses.
//   * CERTAINTY VOCAB — Android stores EVERY_TIME/OFTEN/SOMETIMES/RARELY/NO,
//     iOS stores High/Mild/Low/None. normalizeCertainty() folds both onto the
//     Android enum, which is what the option lists and proposals use.
//
// `proposable` marks the answers the weekly recalibration may propose to
// change from data. Identity, clinical history, prescribing and the user's
// own pool picks are never proposed: the model has no data that could
// contradict them and the user did not ask to be second-guessed on them.

export type AnswerKind = "single" | "certainty" | "multi" | "map";

export interface AnswerField {
  /** Canonical snake_case key (Android storage form, and the proposal label). */
  field: string;
  /** iOS storage key for the same answer. */
  camel: string;
  /** Questionnaire page the answer lives on; matches the client entry keys. */
  entry: string;
  /** The question as the user saw it. "{noun}" is replaced with the app's episode noun. */
  question: string;
  kind: AnswerKind;
  /** Allowed values (single / certainty) or allowed keys (multi / map), in chip order. */
  options: string[];
  proposable: boolean;
}

export const CERTAINTY = ["EVERY_TIME", "OFTEN", "SOMETIMES", "RARELY", "NO"] as const;

const IOS_CERTAINTY: Record<string, string> = {
  high: "OFTEN", mild: "SOMETIMES", low: "RARELY", none: "NO",
};

/** Folds either app's certainty vocabulary onto the Android enum. Unknown → null. */
export function normalizeCertainty(v: unknown): string | null {
  if (v == null) return null;
  const s = String(v).trim();
  if (!s) return null;
  const up = s.toUpperCase();
  if ((CERTAINTY as readonly string[]).includes(up)) return up;
  return IOS_CERTAINTY[s.toLowerCase()] ?? null;
}

function f(
  field: string, camel: string, entry: string, question: string,
  kind: AnswerKind, options: string[], proposable = true,
): AnswerField {
  return { field, camel, entry, question, kind, options, proposable };
}
const S = (field: string, camel: string, entry: string, q: string, options: string[], proposable = true) =>
  f(field, camel, entry, q, "single", options, proposable);
const C = (field: string, camel: string, entry: string, q: string, proposable = true) =>
  f(field, camel, entry, q, "certainty", [...CERTAINTY], proposable);
const M = (field: string, camel: string, entry: string, q: string, keys: string[]) =>
  f(field, camel, entry, q, "multi", keys, false);
const MAP = (field: string, camel: string, entry: string, q: string, keys: string[]) =>
  f(field, camel, entry, q, "map", keys, false);

export const ANSWER_FIELDS: AnswerField[] = [
  // ── about ──
  S("gender", "gender", "about", "What is your gender?", ["Female", "Male", "Prefer not to say"], false),
  S("age_range", "ageRange", "about", "What is your age range?", ["18-25", "26-35", "36-45", "46-55", "56+"], false),
  S("frequency", "frequency", "about", "How often do you get {noun}?", ["A few per year", "Every 1-2 months", "1-3 per month", "Weekly", "Chronic"]),
  S("duration", "duration", "about", "How long do they usually last?", ["< 4 hours", "4-12 hours", "12-24 hours", "1-3 days", "3+ days"]),
  S("experience", "experience", "about", "How long have you been getting {noun}?", ["New / recent", "1-5 years", "5-10 years", "10+ years"], false),
  S("trajectory", "trajectory", "about", "Have they been getting better, worse, or the same?", ["Getting worse", "Getting better", "About the same", "Just started"]),
  S("warning_signs_before", "warningBefore", "about", "Do you get warning signs before a {noun_singular}?", ["Yes, always", "Sometimes", "Rarely", "Never"]),
  S("trigger_delay", "triggerDelay", "about", "After a trigger, how quickly does the {noun_singular} come?", ["Within hours", "Next day", "Within 2-3 days", "Up to a week", "Not sure"]),
  S("daily_routine", "dailyRoutine", "about", "What best describes your daily routine?", ["Regular 9-5", "Shift work / rotating", "Irregular / freelance", "Student", "Stay at home"], false),
  S("seasonal_pattern", "seasonalPattern", "about", "Do your {noun} follow a seasonal pattern?", ["Worse in winter", "Worse in summer", "Worse in spring", "No pattern", "Not sure"]),
  // ── sleep ──
  S("sleep_hours", "sleepHours", "sleep", "How many hours do you usually sleep?", ["< 5h", "5-6h", "6-7h", "7-8h", "8-9h", "9+h"]),
  S("sleep_quality", "sleepQuality", "sleep", "How would you rate your sleep quality?", ["Good", "OK", "Poor", "Varies a lot"]),
  C("poor_sleep_quality_triggers", "poorQualityTriggers", "sleep", "Does POOR QUALITY sleep trigger a {noun_singular}?"),
  C("too_little_sleep_triggers", "tooLittleSleepTriggers", "sleep", "Does TOO LITTLE sleep trigger a {noun_singular}?"),
  C("oversleep_triggers", "oversleepTriggers", "sleep", "Does TOO MUCH sleep trigger a {noun_singular}?"),
  M("sleep_issues", "sleepIssues", "sleep", "Any specific sleep issues?", ["Irregular schedule", "Sleep apnea", "Jet lag", "None of these"]),
  // ── stress ──
  S("stress_level", "stressLevel", "stress", "How would you describe your general stress level?", ["Low", "Moderate", "High", "Very high"]),
  C("stress_change_triggers", "stressChangeTriggers", "stress", "Does a CHANGE in your stress level trigger {noun}?"),
  MAP("emotional_patterns", "emotionalPatterns", "stress", "Which emotional patterns?", ["Spike in stress", "Anxiety", "Anger", "Let-down", "Feeling low"]),
  S("screen_time_daily", "screenTimeDaily", "stress", "How much screen time do you have daily?", ["< 2h", "2-4h", "4-8h", "8-12h", "12h+"]),
  C("screen_time_triggers", "screenTimeTriggers", "stress", "Does screen time trigger {noun}?"),
  C("late_screen_triggers", "lateScreenTriggers", "stress", "Does late-night screen use make it worse?"),
  // ── diet ──
  S("caffeine_intake", "caffeineIntake", "diet", "How much caffeine do you have daily?", ["None", "1-2 cups", "3-4 cups", "5+ cups"]),
  S("caffeine_direction", "caffeineDirection", "diet", "Does caffeine affect your {noun}?", ["Too much triggers it", "Missing caffeine triggers it", "Both ways", "Not sure", "No"]),
  C("caffeine_certainty", "caffeineCertainty", "diet", "How certain about the caffeine link?"),
  S("alcohol_frequency", "alcoholFrequency", "diet", "How often do you drink alcohol?", ["Never", "Occasionally", "Weekly", "Daily"]),
  C("alcohol_triggers", "alcoholTriggers", "diet", "Does alcohol trigger {noun}?"),
  M("specific_drinks", "specificDrinks", "diet", "Are specific drinks worse?", ["Red wine", "Beer", "White wine", "Spirits", "Any alcohol"]),
  MAP("tyramine_foods", "tyramineFoods", "diet", "Do any of these foods trigger {noun}?", ["Aged cheese", "Chocolate", "Cured meats", "Fermented foods"]),
  MAP("histamine_foods", "histamineFoods", "diet", "Any high-histamine foods trigger {noun}?", ["Aged cheese", "Fermented foods", "Processed meat", "Shellfish", "Tomatoes", "Spinach", "Citrus", "Vinegar"]),
  S("gluten_sensitivity", "glutenSensitivity", "diet", "Are you sensitive to gluten?", ["Yes, diagnosed", "I suspect so", "No", "Not sure"], false),
  C("gluten_triggers", "glutenTriggers", "diet", "Does eating gluten trigger {noun}?"),
  MAP("eating_patterns", "eatingPatterns", "diet", "Do any eating patterns trigger {noun}?", ["Skipping meals", "Sugar", "Salty food", "Overeating", "Dehydration"]),
  S("water_intake", "waterIntake", "diet", "How much water do you drink daily?", ["< 1L", "1-2L", "2-3L", "3L+"]),
  S("tracks_nutrition", "tracksNutrition", "diet", "Do you track your nutrition?", ["Yes, regularly", "Sometimes", "No"], false),
  // ── weather ──
  C("weather_triggers", "weatherTriggers", "weather", "Does weather affect your {noun}?"),
  MAP("specific_weather", "specificWeather", "weather", "Which weather changes?", ["Pressure changes", "Hot weather", "Cold weather", "Humidity", "Dry air", "Wind", "Sunshine", "Thunderstorms", "Not sure which"]),
  MAP("environment_sensitivities", "environmentSensitivities", "weather", "Are you sensitive to any of these?", ["Fluorescent lights", "Strong smells", "Loud noise", "Smoke", "Visual motion", "Altitude"]),
  MAP("physical_factors", "physicalFactors", "weather", "Do any physical factors trigger {noun}?", ["Allergies", "Being ill", "Low blood sugar", "Medication change", "Motion sickness", "Tobacco", "Sexual activity"]),
  // ── exercise (and cycle) ──
  S("exercise_frequency", "exerciseFrequency", "exercise", "How often do you exercise?", ["Daily", "Few times/week", "Weekly", "Rarely", "Never"]),
  C("exercise_triggers", "exerciseTriggers", "exercise", "Does exercise trigger {noun}?"),
  M("exercise_pattern", "exercisePattern", "exercise", "Which pattern?", ["During or after intense exercise", "When I haven't exercised"]),
  S("tracks_cycle", "tracksCycle", "exercise", "Do you track your menstrual cycle?", ["Yes", "No", "Not applicable"], false),
  MAP("cycle_patterns", "cyclePatterns", "exercise", "Do {noun} relate to your cycle?", ["Around my period", "Around ovulation"]),
  S("cycle_length", "cycleLength", "exercise", "How long is your average cycle?", ["< 25 days", "25-28 days", "28-32 days", "32-35 days", "> 35 days", "Irregular"], false),
  M("cycle_migraine_timing", "cycleMigraineTiming", "exercise", "When relative to your period?", ["1-2 days before", "3-5 days before", "During my period", "1-2 days after"]),
  S("uses_contraception", "usesContraception", "exercise", "Do you use hormonal contraception?", ["Yes", "No"], false),
  S("contraception_effect", "contraceptionEffect", "exercise", "Has contraception affected your {noun}?", ["Worse — every time", "Worse — sometimes", "No change", "Actually helps"], false),
  // ── warning signs ──
  MAP("physical_prodromes", "physicalProdromes", "warning_signs", "Before a {noun_singular}, do you notice physical changes?", ["Neck stiffness", "Yawning", "Urination", "Stuffy nose", "Watery eyes", "Muscle tension"]),
  MAP("mood_prodromes", "moodProdromes", "warning_signs", "Mood or thinking changes?", ["Concentrating", "Words", "Irritability", "Mood swings", "Feeling low", "Unusually happy", "Food cravings", "Loss of appetite"]),
  MAP("sensory_prodromes", "sensoryProdromes", "warning_signs", "Sensory changes?", ["Light", "Sound", "Smell", "Tingling", "Numbness"]),
  // ── lists (the user's own picks; never proposed) ──
  M("selected_triggers", "selectedTriggers", "triggers", "Which triggers apply to you?", []),
  M("selected_prodromes", "selectedProdromes", "prodromes", "Which warning signs apply to you?", []),
  M("selected_symptoms", "selectedSymptoms", "symptoms", "What symptoms do you experience?", []),
  M("selected_postdromes", "selectedPostdromes", "postdromes", "What do you feel after a {noun_singular}?", []),
  M("selected_locations", "selectedLocations", "locations", "Where are you usually when {noun} hit?", []),
  M("selected_medicines", "selectedMedicines", "medicines", "What medicines do you take?", []),
  M("selected_reliefs", "selectedReliefs", "reliefs", "What helps relieve your {noun}?", []),
  M("selected_activities", "selectedActivities", "activities", "What are you usually doing when {noun} hit?", []),
  M("selected_missed_activities", "selectedMissedActivities", "missed_activities", "What do you miss because of {noun}?", []),
];

/** Some fields also live as their own column on ai_setup_profiles. Kept in sync on apply. */
export const ANSWER_COLUMN_FIELDS = new Set(["frequency", "duration", "experience", "trajectory", "seasonal_pattern"]);

const BY_FIELD = new Map(ANSWER_FIELDS.map((a) => [a.field, a]));
const BY_CAMEL = new Map(ANSWER_FIELDS.map((a) => [a.camel, a]));

export function answerField(nameOrCamel: string): AnswerField | undefined {
  return BY_FIELD.get(nameOrCamel) ?? BY_CAMEL.get(nameOrCamel);
}

/** The stored value for a field under either key casing. */
export function readAnswer(answers: Record<string, unknown> | null | undefined, a: AnswerField): unknown {
  if (!answers) return undefined;
  const v = answers[a.field];
  return v !== undefined && v !== null ? v : answers[a.camel];
}

/** "snake" or "camel": whichever casing the row's existing keys use (snake when empty or tied). */
export function answerCasing(answers: Record<string, unknown> | null | undefined): "snake" | "camel" {
  if (!answers) return "snake";
  let snake = 0, camel = 0;
  for (const a of ANSWER_FIELDS) {
    if (a.field === a.camel) continue;
    if (a.field in answers) snake++;
    if (a.camel in answers) camel++;
  }
  return camel > snake ? "camel" : "snake";
}

/** The key to write this field under so the row stays in one casing. */
export function answerKeyFor(answers: Record<string, unknown> | null | undefined, a: AnswerField): string {
  return answerCasing(answers) === "camel" ? a.camel : a.field;
}

/** Display form of a stored value: certainty folded to the enum, arrays/maps flattened. */
export function displayAnswer(a: AnswerField, v: unknown): string | null {
  if (v == null) return null;
  if (a.kind === "certainty") return normalizeCertainty(v);
  if (a.kind === "multi") {
    const arr = Array.isArray(v) ? v : [];
    return arr.length ? arr.map(String).join(", ") : null;
  }
  if (a.kind === "map") {
    if (typeof v !== "object" || Array.isArray(v)) return null;
    const parts = Object.entries(v as Record<string, unknown>)
      .map(([k, c]) => `${k}: ${normalizeCertainty(c) ?? String(c)}`)
      .filter((s) => !s.endsWith(": NO"));
    return parts.length ? parts.join(", ") : null;
  }
  const s = String(v).trim();
  return s ? s : null;
}

/** Substitutes the app's episode noun into a question. */
export function questionText(a: AnswerField, nounPlural: string, nounSingular: string): string {
  return a.question.replaceAll("{noun_singular}", nounSingular).replaceAll("{noun}", nounPlural);
}

/**
 * The questionnaire block of the recalibration prompt: every answered field,
 * grouped by page, in canonical form. Replaces a hand-written list that read
 * keys the apps never wrote (typical_sleep_hours, known_trigger_areas…), so
 * half the questionnaire never reached the model.
 */
export function formatAnswersBlock(
  answers: Record<string, unknown> | null | undefined,
  nounPlural: string, nounSingular: string,
): string[] {
  const L: string[] = [];
  let currentEntry = "";
  let any = false;
  for (const a of ANSWER_FIELDS) {
    const shown = displayAnswer(a, readAnswer(answers, a));
    if (shown == null) continue;
    if (a.entry !== currentEntry) {
      currentEntry = a.entry;
      L.push(`[${currentEntry}]`);
    }
    L.push(`- ${a.field} — "${questionText(a, nounPlural, nounSingular)}": ${shown}`);
    any = true;
  }
  if (!any) L.push("(no questionnaire answers on file — this user skipped setup; treat every field as unknown)");
  return L;
}

/** The fields the model may propose, with their option sets, for the prompt. */
export function formatProposableFields(nounPlural: string, nounSingular: string): string[] {
  return ANSWER_FIELDS
    .filter((a) => a.proposable && (a.kind === "single" || a.kind === "certainty"))
    .map((a) => `- ${a.field} ("${questionText(a, nounPlural, nounSingular)}"): ${a.options.map((o) => `"${o}"`).join(" | ")}`);
}

/**
 * Validates one model-proposed answer change. Returns the proposal payload
 * (minus user/app ids) or null with a reason when it must be dropped. The
 * closed sets here are the last word: a value outside them never reaches the
 * review screen, because apply could never write it.
 */
export function validateAnswerUpdate(
  upd: { field?: unknown; current_value?: unknown; suggested_value?: unknown; reasoning?: unknown },
  answers: Record<string, unknown> | null | undefined,
  nounPlural: string, nounSingular: string,
): { ok: true; label: string; from_value: string | null; to_value: string; metadata: Record<string, unknown> } | { ok: false; reason: string } {
  const a = answerField(String(upd.field ?? ""));
  if (!a) return { ok: false, reason: `unknown field ${JSON.stringify(upd.field)}` };
  if (!a.proposable || (a.kind !== "single" && a.kind !== "certainty")) {
    return { ok: false, reason: `${a.field} is not proposable` };
  }
  let to = String(upd.suggested_value ?? "").trim();
  if (a.kind === "certainty") to = normalizeCertainty(to) ?? "";
  if (!a.options.includes(to)) return { ok: false, reason: `${a.field}: ${JSON.stringify(upd.suggested_value)} not in options` };
  const current = displayAnswer(a, readAnswer(answers, a));
  if (current === to) return { ok: false, reason: `${a.field}: already ${to}` };
  return {
    ok: true,
    label: a.field,
    from_value: current,
    to_value: to,
    metadata: {
      entry: a.entry,
      question: questionText(a, nounPlural, nounSingular),
      options: a.options,
      kind: a.kind,
    },
  };
}
