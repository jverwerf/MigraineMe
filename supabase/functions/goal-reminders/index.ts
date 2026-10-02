// supabase/functions/goal-reminders/index.ts
//
// Hourly sweep. Pushes the reminders a linked practitioner attached to a goal
// ("Chin tucks, 3 times a day" at 08:00, 13:00 and 19:00 local), at the
// client's local hour, once per goal per reminder time per local day.
//
// Everything that decides WHO gets one lives in SQL (goal_reminder_targets):
// active goal, reminders not muted by the client, a push token, a known
// timezone, this local hour, not yet sent today. This function only sends and
// records. Cron: every hour at :05, see cron.job 'goal-reminders'.
//
// The copy is English keys translated per recipient by send-fcm-push
// (notification_key), the same path the ongoing-migraine reminder uses.

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function requireEnv(name: string): string {
  const v = Deno.env.get(name);
  if (!v) throw new Error(`Missing env var: ${name}`);
  return v;
}

type Target = {
  goal_id: string;
  user_id: string;
  token: string;
  lang: string | null;
  kind: "hr_threshold" | "exercise_count" | "mindfulness_minutes" | "daily_count";
  title: string;
  target_count: number | null;
  target_minutes: number | null;
  local_date: string;
  reminder_time: string;
  from_practitioner: boolean;
};

serve(async (req) => {
  const t0 = Date.now();
  console.log("[goal-reminders] === START ===");
  try {
    if (req.method !== "POST" && req.method !== "GET") {
      return jsonResponse({ ok: false, error: "Method not allowed" }, 405);
    }
    const supabase = createClient(
      requireEnv("SUPABASE_URL"),
      requireEnv("SUPABASE_SERVICE_ROLE_KEY"),
      { auth: { persistSession: false } },
    );

    const { data, error } = await supabase.rpc("goal_reminder_targets");
    if (error) throw new Error(`goal_reminder_targets: ${error.message}`);
    const targets = (data ?? []) as Target[];
    if (!targets.length) {
      return jsonResponse({ ok: true, considered: 0, sent: 0 });
    }

    let sent = 0, skipped = 0;
    for (const g of targets) {
      try {
        // Claim before sending, so a retry cannot double-push.
        const { error: markErr } = await supabase
          .from("practitioner_goal_reminders_sent")
          .insert({ goal_id: g.goal_id, local_date: g.local_date, reminder_time: g.reminder_time });
        if (markErr) { skipped++; continue; }

        // Body: what the goal asks for today. The title the practitioner typed
        // is shown as typed (it is already in the client's language when it is
        // one of the three presets, because the presets are English keys).
        const body = (g.kind === "exercise_count" || g.kind === "daily_count")
          ? { key: "Time for {0}. Today's goal: {1} times.", args: [g.title, String(g.target_count ?? 1)] }
          : g.kind === "mindfulness_minutes"
          ? { key: "Time for {0}. Today's goal: {1} minutes.", args: [g.title, String(g.target_minutes ?? 5)] }
          : { key: "Time for {0}.", args: [g.title] };

        await supabase.functions.invoke("send-fcm-push", {
          body: {
            type: "practitioner_goal",
            user_ids: [g.user_id],
            ios_alert: true,
            notification_key: {
              title: g.from_practitioner ? "A reminder from your practitioner" : "Your goal reminder",
              body_parts: [body],
            },
            // FCM data values must be strings.
            data: { goal_id: g.goal_id, kind: g.kind },
          },
        });
        sent++;
      } catch (e) {
        console.error(`[goal-reminders] ${g.goal_id}: ${(e as Error).message}`);
        skipped++;
      }
    }
    const elapsed = Date.now() - t0;
    console.log(`[goal-reminders] === DONE ${elapsed}ms considered=${targets.length} sent=${sent} skipped=${skipped} ===`);
    return jsonResponse({ ok: true, considered: targets.length, sent, skipped, elapsedMs: elapsed });
  } catch (e) {
    console.error("[goal-reminders] FATAL:", e);
    return jsonResponse({ ok: false, error: (e as Error).message }, 500);
  }
});
