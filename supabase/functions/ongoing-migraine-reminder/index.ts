// supabase/functions/ongoing-migraine-reminder/index.ts
//
// Hourly sweep. Reminds a user every N days that a migraine they logged still
// has no end time, so they go back and close it.
//
// Runs hourly rather than daily because "N days" is measured from the last
// reminder (or the migraine's start), and the push has to land at a civil local
// hour. An hourly pass lets each user get theirs when it is daytime for them.
//
// Two guards keep this from becoming spam:
//   - public.ongoing_migraine_reminders.silenced — the whole pre-existing
//     backlog was seeded as silenced by the migration, and a migraine the user
//     has ignored MAX_REMINDERS times silences itself.
//   - REMIND_LOCAL_HOUR_MIN/MAX — nothing outside daytime local.

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

/** Days between reminders when the user has not chosen a number. */
const DEFAULT_INTERVAL_DAYS = 3;

/** Give up on a migraine after this many unanswered nudges. */
const MAX_REMINDERS = 3;

/** Only push between these local hours. */
const REMIND_LOCAL_HOUR_MIN = 9;
const REMIND_LOCAL_HOUR_MAX = 21;

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

function getLocalHourIn(timeZone: string, now: Date): number {
  try {
    const parts = new Intl.DateTimeFormat("en-US", {
      timeZone, hour: "numeric", hour12: false,
    }).formatToParts(now);
    const h = parts.find((p) => p.type === "hour")?.value;
    return h === undefined ? -1 : Number(h);
  } catch {
    return -1;
  }
}

function daysBetween(from: Date, to: Date): number {
  return (to.getTime() - from.getTime()) / 86_400_000;
}

serve(async (req) => {
  const t0 = Date.now();
  console.log("[ongoing-migraine-reminder] === START ===");

  try {
    if (req.method !== "POST" && req.method !== "GET") {
      return jsonResponse({ ok: false, error: "Method not allowed" }, 405);
    }

    const supabase = createClient(
      requireEnv("SUPABASE_URL"),
      requireEnv("SUPABASE_SERVICE_ROLE_KEY"),
      { auth: { persistSession: false } },
    );

    const now = new Date();

    // Every still-open migraine. The reminder state row (if any) rides along so
    // we can decide without a second round trip per migraine.
    const { data: openMigraines, error: openErr } = await supabase
      .from("migraines")
      .select("id, user_id, start_at, ongoing_migraine_reminders(last_sent_at, sent_count, silenced)")
      .is("ended_at", null);

    if (openErr) throw new Error(`open migraine query failed: ${openErr.message}`);
    if (!openMigraines?.length) {
      return jsonResponse({ ok: true, considered: 0, sent: 0 });
    }

    // Drop the silenced ones before doing any per-user work.
    type Candidate = {
      id: string;
      user_id: string;
      start_at: string;
      last_sent_at: string | null;
      sent_count: number;
    };
    const candidates: Candidate[] = [];
    for (const m of openMigraines as any[]) {
      const state = Array.isArray(m.ongoing_migraine_reminders)
        ? m.ongoing_migraine_reminders[0]
        : m.ongoing_migraine_reminders;
      if (state?.silenced) continue;
      if ((state?.sent_count ?? 0) >= MAX_REMINDERS) continue;
      candidates.push({
        id: m.id,
        user_id: m.user_id,
        start_at: m.start_at,
        last_sent_at: state?.last_sent_at ?? null,
        sent_count: state?.sent_count ?? 0,
      });
    }

    if (!candidates.length) {
      return jsonResponse({ ok: true, considered: openMigraines.length, sent: 0 });
    }

    // Resolve per-user context once, not once per migraine.
    const userIds = Array.from(new Set(candidates.map((c) => c.user_id)));

    const { data: prefRows } = await supabase
      .from("notification_settings")
      .select("user_id, enabled, interval_days")
      .eq("type", "ongoing_migraine")
      .in("user_id", userIds);
    const prefByUser = new Map(
      (prefRows ?? []).map((r: any) => [r.user_id, r]),
    );

    const { data: profRows } = await supabase
      .from("profiles")
      .select("user_id, fcm_token")
      .in("user_id", userIds)
      .not("fcm_token", "is", null);
    const tokenByUser = new Map(
      (profRows ?? []).map((r: any) => [r.user_id, r.fcm_token as string]),
    );

    // Most recent known timezone per user.
    const { data: locRows } = await supabase
      .from("user_location_daily")
      .select("user_id, timezone, date")
      .in("user_id", userIds)
      .not("timezone", "is", null)
      .order("date", { ascending: false });
    const tzByUser = new Map<string, string>();
    for (const r of locRows ?? []) {
      if (!tzByUser.has((r as any).user_id)) {
        tzByUser.set((r as any).user_id, (r as any).timezone);
      }
    }

    let sent = 0;
    let skipped = 0;

    for (const c of candidates) {
      try {
        const pref = prefByUser.get(c.user_id);
        if (pref && pref.enabled === false) { skipped++; continue; }

        const token = tokenByUser.get(c.user_id);
        if (!token) { skipped++; continue; }

        const tz = tzByUser.get(c.user_id);
        if (!tz) { skipped++; continue; }

        const localHour = getLocalHourIn(tz, now);
        if (localHour < REMIND_LOCAL_HOUR_MIN || localHour > REMIND_LOCAL_HOUR_MAX) {
          skipped++;
          continue;
        }

        const intervalDays = Number(pref?.interval_days ?? DEFAULT_INTERVAL_DAYS);

        // Count from the last reminder, or from the migraine's start if this is
        // the first one.
        const since = c.last_sent_at ? new Date(c.last_sent_at) : new Date(c.start_at);
        if (daysBetween(since, now) < intervalDays) { skipped++; continue; }

        const daysOpen = Math.floor(daysBetween(new Date(c.start_at), now));
        const nextCount = c.sent_count + 1;

        // Claim before sending: a failed push costs one reminder, a retry loop
        // would cost the user's patience.
        const { error: markErr } = await supabase
          .from("ongoing_migraine_reminders")
          .upsert({
            migraine_id: c.id,
            user_id: c.user_id,
            last_sent_at: now.toISOString(),
            sent_count: nextCount,
            // Last nudge — stop asking after this one.
            silenced: nextCount >= MAX_REMINDERS,
          }, { onConflict: "migraine_id" });

        if (markErr) { skipped++; continue; }

        await supabase.functions.invoke("send-fcm-push", {
          body: {
            type: "ongoing_migraine",
            tokens: [token],
            // FCM data values must be strings.
            data: {
              migraine_id: c.id,
              days_open: String(daysOpen),
              final_reminder: nextCount >= MAX_REMINDERS ? "1" : "0",
            },
          },
        });

        sent++;
      } catch (e) {
        console.error(`[ongoing-migraine-reminder] ${c.id}: ${(e as Error).message}`);
        skipped++;
      }
    }

    const elapsed = Date.now() - t0;
    console.log(
      `[ongoing-migraine-reminder] === DONE ${elapsed}ms, considered=${candidates.length}, sent=${sent}, skipped=${skipped} ===`,
    );
    return jsonResponse({ ok: true, considered: candidates.length, sent, skipped, elapsedMs: elapsed });
  } catch (e) {
    console.error("[ongoing-migraine-reminder] FATAL:", e);
    return jsonResponse({ ok: false, error: (e as Error).message }, 500);
  }
});
