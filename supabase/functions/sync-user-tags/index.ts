// supabase/functions/sync-user-tags/index.ts
// DETERMINISTIC — no AI. Links user's actual data to tags.
// Reads: trigger_preferences → user_triggers, user_medicines, user_reliefs,
//        user_prodromes, ai_setup_profiles, profiles (migraine_type)
// Writes: user_tags

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

serve(async (req) => {
  try {
    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_KEY);
    const { user_id } = await req.json();

    if (!user_id) {
      return new Response(JSON.stringify({ error: "user_id required" }), { status: 400 });
    }

    // ── Load all tags (name → id) ──
    const { data: allTags } = await supabase.from("tags").select("id, name, category, source_table, source_key");
    const tagByName: Record<string, string> = {};
    const tagBySourceKey: Record<string, string> = {};
    for (const t of allTags ?? []) {
      tagByName[t.name] = t.id;
      if (t.source_key) tagBySourceKey[`${t.source_table}:${t.source_key}`] = t.id;
    }

    const matched: Array<{ tag_id: string; weight: number }> = [];

    function addTag(tagId: string | undefined, weight: number) {
      if (tagId) matched.push({ tag_id: tagId, weight });
    }
    function addByName(name: string, weight: number) {
      addTag(tagByName[name], weight);
    }
    function addBySourceKey(table: string, key: string, weight: number) {
      addTag(tagBySourceKey[`${table}:${key}`], weight);
    }

    // ── 1. Active triggers → tags (weight 1.0) ──
    const { data: triggerPrefs } = await supabase
      .from("trigger_preferences")
      .select("trigger_id, user_triggers(label)")
      .eq("user_id", user_id)
      .eq("status", "active");

    for (const tp of triggerPrefs ?? []) {
      const label = (tp as any).user_triggers?.label;
      if (label) addByName(label, 1.0);
    }

    // ── 2. User medicines → tags (weight 1.0) ──
    const { data: medicines } = await supabase
      .from("user_medicines")
      .select("label")
      .eq("user_id", user_id);

    for (const m of medicines ?? []) {
      if (m.label) addByName(m.label, 1.0);
    }

    // ── 3. User reliefs → tags (weight 0.8) ──
    const { data: reliefs } = await supabase
      .from("user_reliefs")
      .select("label")
      .eq("user_id", user_id);

    for (const r of reliefs ?? []) {
      if (r.label) addByName(r.label, 0.8);
    }

    // ── 4. User prodromes → tags (weight 0.7) ──
    const { data: prodromes } = await supabase
      .from("user_prodromes")
      .select("label")
      .eq("user_id", user_id);

    for (const p of prodromes ?? []) {
      if (p.label) addByName(p.label, 0.7);
    }

    // ── 5. Migraine type from profiles table (weight 1.0) ──
    const { data: profileRow } = await supabase
      .from("profiles")
      .select("migraine_type")
      .eq("user_id", user_id)
      .maybeSingle();

    if (profileRow?.migraine_type) {
      // MigraineType enum: migraine, migraine_with_aura, cluster, tension, hemiplegic, vestibular
      const typeLabels: Record<string, string> = {
        "migraine": "Migraine",
        "migraine_with_aura": "Migraine with aura",
        "cluster": "Cluster",
        "tension": "Tension",
        "hemiplegic": "Hemiplegic",
        "vestibular": "Vestibular",
      };
      const label = typeLabels[profileRow.migraine_type];
      if (label) addByName(label, 1.0);
    }

    // ── 6. AI setup profile answers → tags (weight 0.9) ──
    const { data: aiProfile } = await supabase
      .from("ai_setup_profiles")
      .select("frequency, duration, experience, trajectory, seasonal_pattern, gender, age_range, tracks_cycle, answers")
      .eq("user_id", user_id)
      .maybeSingle();

    if (aiProfile) {
      // Direct field → tag mappings
      if (aiProfile.frequency) addBySourceKey("ai_setup_profiles", `frequency:${aiProfile.frequency}`, 0.9);
      if (aiProfile.duration) addBySourceKey("ai_setup_profiles", `duration:${aiProfile.duration}`, 0.9);
      if (aiProfile.experience) addBySourceKey("ai_setup_profiles", `experience:${aiProfile.experience}`, 0.9);
      if (aiProfile.trajectory) addBySourceKey("ai_setup_profiles", `trajectory:${aiProfile.trajectory}`, 0.9);
      if (aiProfile.seasonal_pattern) addBySourceKey("ai_setup_profiles", `seasonal_pattern:${aiProfile.seasonal_pattern}`, 0.9);
      if (aiProfile.gender && aiProfile.gender !== "Prefer not to say") addBySourceKey("ai_setup_profiles", `gender:${aiProfile.gender}`, 0.5);
      if (aiProfile.age_range) addBySourceKey("ai_setup_profiles", `age_range:${aiProfile.age_range}`, 0.5);
      if (aiProfile.tracks_cycle) addBySourceKey("ai_setup_profiles", "tracks_cycle:true", 0.9);

      // Parse answers JSON for deeper profile data
      const answers = aiProfile.answers;
      if (answers && typeof answers === "object") {
        const a = answers as Record<string, any>;

        // Sleep
        if (a.sleep_hours) addBySourceKey("ai_setup_profiles", `sleep_hours:${a.sleep_hours}`, 0.7);
        if (a.sleep_quality) addBySourceKey("ai_setup_profiles", `sleep_quality:${a.sleep_quality}`, 0.7);
        if (a.sleep_issues && Array.isArray(a.sleep_issues)) {
          for (const issue of a.sleep_issues) addBySourceKey("ai_setup_profiles", `sleep_issue:${issue}`, 0.7);
        }

        // Stress
        if (a.stress_level) addBySourceKey("ai_setup_profiles", `stress_level:${a.stress_level}`, 0.8);

        // Daily routine
        if (a.daily_routine) addBySourceKey("ai_setup_profiles", `daily_routine:${a.daily_routine}`, 0.6);

        // Caffeine
        if (a.caffeine_direction && a.caffeine_direction !== "No") {
          addBySourceKey("ai_setup_profiles", "caffeine_direction:triggers", 0.8);
        }

        // Alcohol
        if (a.alcohol_trigger === true || a.alcohol_frequency === "Weekly" || a.alcohol_frequency === "Daily") {
          addBySourceKey("ai_setup_profiles", "alcohol_trigger:true", 0.8);
        }

        // Gluten
        if (a.gluten_sensitivity === "Yes, diagnosed" || a.gluten_sensitivity === "I suspect so") {
          addBySourceKey("ai_setup_profiles", "gluten:Yes, diagnosed", 0.8);
        }

        // Weather
        if (a.weather_trigger === true || a.weather_trigger === "Yes") {
          addBySourceKey("ai_setup_profiles", "weather_trigger:true", 0.8);
        }

        // Exercise
        if (a.exercise_frequency) addBySourceKey("ai_setup_profiles", `exercise_frequency:${a.exercise_frequency}`, 0.6);
        if (a.exercise_trigger === true) addBySourceKey("ai_setup_profiles", "exercise_trigger:true", 0.8);

        // Cycle patterns
        if (a.cycle_patterns && Array.isArray(a.cycle_patterns)) {
          for (const cp of a.cycle_patterns) {
            addBySourceKey("ai_setup_profiles", `cycle_pattern:${cp}`, 0.9);
          }
        }

        // Contraception
        if (a.uses_contraception === "Yes") {
          addBySourceKey("ai_setup_profiles", "uses_contraception:Yes", 0.7);
        }
        if (a.contraception_effect && a.contraception_effect.startsWith("Worse")) {
          addBySourceKey("ai_setup_profiles", "contraception_effect:Worse", 0.8);
        }

        // Warning before
        if (a.warning_before === "Yes, always" || a.warning_before === "Sometimes") {
          addBySourceKey("ai_setup_profiles", `warning_before:${a.warning_before}`, 0.6);
        }

        // Environment
        if (a.environment_sensitivities && Array.isArray(a.environment_sensitivities)) {
          const envMap: Record<string, string> = {
            "Fluorescent lights": "env:Fluorescent lights",
            "Strong smells": "env:Strong smells",
            "Loud noise": "env:Loud noise",
          };
          for (const env of a.environment_sensitivities) {
            const key = envMap[env];
            if (key) addBySourceKey("ai_setup_profiles", key, 0.8);
          }
        }

        // Eating patterns
        if (a.eating_patterns && Array.isArray(a.eating_patterns)) {
          for (const ep of a.eating_patterns) {
            addBySourceKey("ai_setup_profiles", `eating_pattern:${ep}`, 0.8);
          }
        }
      }
    }

    // ── 7. Deduplicate (keep highest weight per tag) ──
    const best: Record<string, number> = {};
    for (const m of matched) {
      best[m.tag_id] = Math.max(best[m.tag_id] ?? 0, m.weight);
    }

    // ── 8. Write to user_tags ──
    await supabase
      .from("user_tags")
      .delete()
      .eq("user_id", user_id)
      .eq("source", "profile");

    const inserts = Object.entries(best).map(([tag_id, weight]) => ({
      user_id,
      tag_id,
      weight,
      source: "profile",
    }));

    if (inserts.length > 0) {
      await supabase.from("user_tags").upsert(inserts);
    }

    // Build response with tag names for debugging
    const tagIdToName: Record<string, string> = {};
    for (const t of allTags ?? []) tagIdToName[t.id] = t.name;

    return new Response(
      JSON.stringify({
        user_id,
        tags_synced: inserts.length,
        tags: inserts
          .map((i) => ({ name: tagIdToName[i.tag_id], weight: i.weight }))
          .sort((a, b) => b.weight - a.weight),
      }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  } catch (e: any) {
    console.error("sync-user-tags error:", e);
    return new Response(JSON.stringify({ error: e.message }), { status: 500 });
  }
});