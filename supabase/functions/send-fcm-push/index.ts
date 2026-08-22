import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

import { asLang, translatorFor } from "../_shared/i18n.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const FIREBASE_SERVICE_ACCOUNT = JSON.parse(
  Deno.env.get("FIREBASE_SERVICE_ACCOUNT")!
);

// Get Firebase access token using service account
async function getFirebaseAccessToken(): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const expiry = now + 3600;

  const header = { alg: "RS256", typ: "JWT" };
  const payload = {
    iss: FIREBASE_SERVICE_ACCOUNT.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: expiry,
  };

  const encoder = new TextEncoder();
  const headerB64 = btoa(JSON.stringify(header))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=/g, "");
  const payloadB64 = btoa(JSON.stringify(payload))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=/g, "");

  const signatureInput = `${headerB64}.${payloadB64}`;

  const pemContents = FIREBASE_SERVICE_ACCOUNT.private_key
    .replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "")
    .replace(/\s/g, "");

  const binaryKey = Uint8Array.from(atob(pemContents), (c) => c.charCodeAt(0));

  const cryptoKey = await crypto.subtle.importKey(
    "pkcs8",
    binaryKey,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );

  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    cryptoKey,
    encoder.encode(signatureInput)
  );

  const signatureB64 = btoa(String.fromCharCode(...new Uint8Array(signature)))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=/g, "");

  const jwt = `${signatureInput}.${signatureB64}`;

  const tokenResponse = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${jwt}`,
  });

  const tokenData = await tokenResponse.json();
  return tokenData.access_token;
}

// Send FCM message to a single device.
//
// `notification` is optional and, when present, is ALREADY TRANSLATED by the
// caller. A push is the one piece of user-facing text the device cannot render
// itself — it only ever sees the finished string — so the caller looks up the
// recipient's profiles.lang and renders before handing the text over. Data-only
// pushes stay data-only: the app builds their text on-device through Strings,
// which is better, because that text then follows a language change.
async function sendFcmMessage(
  accessToken: string,
  fcmToken: string,
  messageType: string,
  extraData: Record<string, string> = {},
  notification?: { title: string; body: string },
  // Put the copy in the APNs alert but NOT in the top-level `notification`.
  // See the block below for why the two are not the same thing.
  iosAlertOnly = false
): Promise<boolean> {
  const projectId = FIREBASE_SERVICE_ACCOUNT.project_id;
  const url = `https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`;

  // iOS needs an explicit APNs block; Android needs it left alone.
  //
  // Without one, a data-only message carries nothing APNs will deliver for: it
  // is discarded unless the app is already in the foreground, so on-device
  // rendering never ran and NOTHING appeared in the shade — trigger bells,
  // daily_gauge, ongoing_migraine, new_insight, recalibration_ready, all of it.
  // `content-available: 1` (plus the `remote-notification` background mode in
  // Info.plist) is what wakes the app so it can render its own copy.
  //
  // Deliberately iOS-only. Putting a top-level `notification` on the message
  // instead would make Android's system tray render it and SUPPRESS
  // onMessageReceived while backgrounded, which is where the Android app sets
  // its has_new_insight / has_proposals flags and builds the bell copy. The
  // `apns` key is ignored by Android, so this fixes iOS without touching it.
  const apns = notification
    ? {
        // Visible push: state the alert rather than leaving FCM to synthesise
        // it, and ask for a sound — FCM never adds one.
        headers: { "apns-push-type": "alert", "apns-priority": "10" },
        payload: {
          aps: {
            alert: { title: notification.title, body: notification.body },
            sound: "default",
          },
        },
      }
    : {
        // No copy to show: a silent wake-up so the app can render its own.
        // Weak on iOS (throttled, and never delivered to a force-quit app), so
        // anything the user is meant to SEE sends copy and takes the branch
        // above. This one is for the sync workers.
        headers: { "apns-push-type": "background", "apns-priority": "5" },
        payload: { aps: { "content-available": 1 } },
      };

  const message: Record<string, unknown> = {
    message: {
      token: fcmToken,
      data: {
        // Caller-supplied fields first so `type` can never be overwritten.
        ...extraData,
        type: messageType,
      },
      // `iosAlertOnly` keeps the copy out of the top-level notification, which
      // is an ANDROID concern: a message carrying one is rendered by Android's
      // system tray and onMessageReceived is then SKIPPED while the app is
      // backgrounded — and that callback is where the Android app sets its
      // has_new_insight / has_proposals flags and builds the bell copy. The
      // `apns` key is ignored by Android, so iOS gets a real alert (which is
      // the only thing it delivers reliably) with Android's behaviour intact.
      ...(notification && !iosAlertOnly ? { notification } : {}),
      android: {
        priority: "high",
      },
      apns,
    },
  };

  const response = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(message),
  });

  if (!response.ok) {
    const error = await response.text();
    console.error(`FCM send failed for token ${fcmToken.slice(0, 20)}...: ${error}`);
    return false;
  }

  return true;
}

// ─── Evening check-in: get tokens for users at 8pm local ───────────

type CheckinTarget = { token: string; lang: unknown };

async function getEveningCheckinTargets(
  supabase: any
): Promise<CheckinTarget[]> {
  // Get all profiles with FCM tokens (+ language, so the copy renders per recipient)
  const { data: profiles, error } = await supabase
    .from("profiles")
    .select("user_id, fcm_token, lang")
    .not("fcm_token", "is", null);

  if (error || !profiles || profiles.length === 0) return [];

  const userIds = profiles.map((p: any) => p.user_id);

  // Get most recent timezone per user from user_location_daily
  const { data: locData } = await supabase
    .from("user_location_daily")
    .select("user_id, timezone, date")
    .in("user_id", userIds)
    .not("timezone", "is", null)
    .order("date", { ascending: false });

  // Build timezone map (most recent per user)
  const tzMap: Record<string, string> = {};
  if (locData) {
    for (const row of locData) {
      if (!tzMap[row.user_id]) {
        tzMap[row.user_id] = row.timezone;
      }
    }
  }

  // Filter to users where local hour = 20 (8pm)
  const now = new Date();
  const tokens: CheckinTarget[] = [];

  for (const profile of profiles) {
    const tz = tzMap[profile.user_id];
    if (!tz || !profile.fcm_token) continue;

    try {
      const localTime = new Date(
        now.toLocaleString("en-US", { timeZone: tz })
      );
      if (localTime.getHours() === 20) {
        tokens.push({ token: profile.fcm_token, lang: profile.lang });
      }
    } catch {
      // Invalid timezone, skip
    }
  }

  return tokens;
}

// The evening check-in was a data-only push: Android rendered it locally,
// iOS got a silent background wake that is throttled and never reaches a
// force-quit app, so most evenings nothing appeared. It now carries copy in
// the recipient's language. Android stays data-only (ios_alert semantics)
// so onMessageReceived still renders on the user's own "evening_checkin"
// channel, which the settings toggle can switch off.
function eveningCheckinCopy(lang: unknown): { title: string; body: string } {
  const t = translatorFor(asLang(lang));
  return { title: t("How was today?"), body: t("Take 15 seconds to log your day") };
}

// A body sent as sentence parts is translated part by part and joined; a
// body sent whole is translated as one key. Same output either way, so
// callers pick whichever matches how their copy is actually written.
type NotificationKey = {
  title: string;
  body: string;
  title_args?: unknown[];
  body_args?: unknown[];
  body_parts?: Array<{ key: string; args?: unknown[] }>;
};

function renderBody(
  t: (en: string, ...args: unknown[]) => string,
  key: NotificationKey,
): string {
  if (key.body_parts && key.body_parts.length > 0) {
    return key.body_parts
      .map((p) => t(p.key, ...(p.args ?? [])))
      .join(" ");
  }
  return t(key.body, ...(key.body_args ?? []));
}

// ─── Main handler ───────────────────────────────────────────────────

serve(async (req) => {
  try {
    const url = new URL(req.url);
    let messageType = "sync_hourly";
    let directTokens: string[] | null = null;
    let directUserIds: string[] | null = null;
    // Extra payload fields, e.g. the gauge alert's zone and percent, so the app
    // can name the actual number instead of a generic "something is ready".
    let extraData: Record<string, string> = {};
    // Pre-rendered, already-translated notification text. See sendFcmMessage.
    let notification: { title: string; body: string } | undefined;
    // Set by callers whose push must render on iOS but must stay data-only on
    // Android, i.e. every type the Android app renders itself.
    let iosAlertOnly = false;
    // Per-token copy, used when the caller asked for the push to be rendered
    // in each recipient's own language (see `notification_key` below). Falls
    // back to `notification` for any token not in the map.
    const notificationByToken = new Map<string, { title: string; body: string }>();
    // English key + args for per-recipient rendering; resolved against each
    // target user's profiles.lang in the user_ids branch below.
    let notificationKey: NotificationKey | null = null;

    if (req.method === "POST") {
      const body = await req.json();
      messageType = body.type || "sync_hourly";

      // Direct token send — used by recalibration and other server-side features
      // that already know which specific tokens to target
      if (body.tokens && Array.isArray(body.tokens)) {
        directTokens = body.tokens;
      }

      // Direct user send. Callers that work in user_ids (the community push
      // batches per user, and has to look each user's language up anyway)
      // should not have to duplicate the token lookup. This used to be sent
      // and silently ignored, which dropped the caller through to the
      // metric-based branch below and fanned the push out to every user with
      // location enabled — the opposite of targeting one person.
      if (body.user_ids && Array.isArray(body.user_ids)) {
        directUserIds = body.user_ids;
      }

      if (
        body.notification &&
        typeof body.notification === "object" &&
        typeof body.notification.title === "string" &&
        typeof body.notification.body === "string"
      ) {
        notification = {
          title: body.notification.title,
          body: body.notification.body,
        };
      }

      // ── Recipient-language rendering ────────────────────────────────
      //
      // `notification` carries copy the caller already rendered, which is
      // correct when the caller knows the recipient. It is WRONG for a
      // fan-out to many users: one pre-rendered string puts the composer's
      // language on every recipient's lock screen. So a caller can instead
      // send `notification_key`: English strings, which ARE the translation
      // keys, plus their arguments. We resolve each recipient's own
      // profiles.lang below — the only place that knows which users these
      // tokens belong to — and render per token.
      if (
        body.notification_key &&
        typeof body.notification_key === "object" &&
        typeof body.notification_key.title === "string" &&
        (typeof body.notification_key.body === "string" ||
          Array.isArray(body.notification_key.body_parts))
      ) {
        notificationKey = {
          title: body.notification_key.title,
          body: typeof body.notification_key.body === "string" ? body.notification_key.body : "",
          // Whole sentences, each its own key, joined after translation —
          // the same rule the devices use, and the reason a language is free
          // to reorder the words inside a sentence without the caller caring.
          // It also means a push reuses the sentence keys the app already has
          // translated instead of minting a combined string nobody has seen.
          body_parts: Array.isArray(body.notification_key.body_parts)
            ? (body.notification_key.body_parts as Array<Record<string, unknown>>)
              .filter((p) => p && typeof p.key === "string")
              .map((p) => ({
                key: p.key as string,
                args: Array.isArray(p.args) ? p.args : undefined,
              }))
            : undefined,
          title_args: Array.isArray(body.notification_key.title_args)
            ? body.notification_key.title_args
            : undefined,
          body_args: Array.isArray(body.notification_key.body_args)
            ? body.notification_key.body_args
            : undefined,
        };
      }

      if (body.ios_alert === true) iosAlertOnly = true;

      // FCM only accepts string values in the data map.
      if (body.data && typeof body.data === "object") {
        for (const [k, v] of Object.entries(body.data)) {
          if (v !== null && v !== undefined) extraData[k] = String(v);
        }
      }
    } else {
      messageType = url.searchParams.get("type") || "sync_hourly";
    }

    console.log(`Sending FCM push: type=${messageType}${directTokens ? ` (direct: ${directTokens.length} tokens)` : ""}`);

    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);

    let tokensToSend: string[] = [];

    // ── Direct token mode (recalibration, etc.) ──
    if (directTokens && directTokens.length > 0) {
      tokensToSend = directTokens;

    } else if (directUserIds && directUserIds.length > 0) {
      // ── Direct user mode: resolve tokens for exactly these users ──
      const { data: targets, error: targetsError } = await supabase
        .from("profiles")
        .select("user_id, fcm_token, lang")
        .in("user_id", directUserIds)
        .not("fcm_token", "is", null);

      if (targetsError) {
        return new Response(JSON.stringify({ error: targetsError.message }), { status: 500 });
      }
      tokensToSend = (targets || []).map((p: any) => p.fcm_token).filter(Boolean);

      // Render notification_key copy once per recipient, in that recipient's
      // own language. This branch is the only one that knows whose token is
      // whose, so it is the only place per-recipient rendering can happen.
      if (notificationKey) {
        for (const p of (targets || []) as Array<{ fcm_token: string | null; lang?: unknown }>) {
          if (!p.fcm_token) continue;
          const t = translatorFor(asLang(p.lang));
          notificationByToken.set(p.fcm_token, {
            title: t(notificationKey.title, ...(notificationKey.title_args ?? [])),
            body: renderBody(t, notificationKey),
          });
        }
      }

    } else if (messageType === "evening_checkin") {
      // ── Evening check-in: only users where it's 8pm local ──
      const targets = await getEveningCheckinTargets(supabase);
      tokensToSend = targets.map((c) => c.token);
      for (const c of targets) notificationByToken.set(c.token, eveningCheckinCopy(c.lang));
      notification = eveningCheckinCopy("en");
      iosAlertOnly = true;
      console.log(`Evening check-in: ${tokensToSend.length} users at 8pm`);

    } else {
      // ── Standard metric-based push ──
      let enabledUserIds: Set<string>;

      if (messageType === "sync_hourly") {
        // sync_hourly triggers multiple on-device workers — send to any user
        // with at least one syncable metric enabled
        const syncableMetrics = [
          "user_location_daily",
          "screen_time_daily", "screen_time_late_night",
          "phone_brightness_daily", "phone_volume_daily",
          "phone_dark_mode_daily", "phone_unlock_daily",
          "sleep_duration_daily", "fell_asleep_time_daily", "woke_up_time_daily",
          "ambient_noise_samples",
          // Health Connect metrics
          "hrv_daily", "resting_hr_daily", "steps_daily", "weight_daily",
          "body_fat_daily", "hydration_daily", "blood_pressure_daily",
          "blood_glucose_daily", "spo2_daily", "respiratory_rate_daily", "skin_temp_daily",
        ];

        const { data: enabledUsers, error: settingsError } = await supabase
          .from("metric_settings")
          .select("user_id")
          .in("metric", syncableMetrics)
          .eq("enabled", true);

        if (settingsError) {
          return new Response(JSON.stringify({ error: settingsError.message }), { status: 500 });
        }
        enabledUserIds = new Set((enabledUsers || []).map((u: any) => u.user_id));

      } else if (messageType === "sync_health_connect") {
        const healthConnectMetrics = [
          "sleep_duration_daily", "hrv_daily", "resting_hr_daily", "steps_daily",
          "weight_daily", "body_fat_daily", "hydration_daily", "blood_pressure_daily",
          "blood_glucose_daily", "spo2_daily", "respiratory_rate_daily", "skin_temp_daily",
        ];

        const { data: enabledUsers, error: settingsError } = await supabase
          .from("metric_settings")
          .select("user_id")
          .in("metric", healthConnectMetrics)
          .eq("enabled", true)
          .eq("preferred_source", "health_connect");

        if (settingsError) {
          return new Response(JSON.stringify({ error: settingsError.message }), { status: 500 });
        }
        enabledUserIds = new Set((enabledUsers || []).map((u: any) => u.user_id));
      } else {
        // Single-metric push types
        const metricMap: Record<string, string[]> = {
          sync_location: ["user_location_daily"],
          sync_screen_time: ["screen_time_daily", "screen_time_late_night"],
          sync_weather: [
            "temperature_daily", "pressure_daily", "humidity_daily",
            "wind_daily", "uv_daily", "thunderstorm_daily"
          ],
          sync_noise: ["ambient_noise_samples"],
        };

        const metrics = metricMap[messageType] || ["user_location_daily"];

        const { data: enabledUsers, error: settingsError } = await supabase
          .from("metric_settings")
          .select("user_id")
          .in("metric", metrics)
          .eq("enabled", true);

        if (settingsError) {
          return new Response(JSON.stringify({ error: settingsError.message }), { status: 500 });
        }
        enabledUserIds = new Set((enabledUsers || []).map((u: any) => u.user_id));
      }

      console.log(`Found ${enabledUserIds.size} users with ${messageType} enabled`);

      if (enabledUserIds.size === 0) {
        return new Response(
          JSON.stringify({ success: true, sent: 0, message: "No users with metric enabled" }),
          { status: 200 }
        );
      }

      const { data: profiles, error: profilesError } = await supabase
        .from("profiles")
        .select("user_id, fcm_token")
        .not("fcm_token", "is", null);

      if (profilesError) {
        return new Response(JSON.stringify({ error: profilesError.message }), { status: 500 });
      }

      tokensToSend = (profiles || [])
        .filter((p: any) => p.fcm_token && enabledUserIds.has(p.user_id))
        .map((p: any) => p.fcm_token);
    }

    if (tokensToSend.length === 0) {
      return new Response(
        JSON.stringify({ success: true, sent: 0, message: "No FCM tokens to send to" }),
        { status: 200 }
      );
    }

    const firebaseToken = await getFirebaseAccessToken();

    let sent = 0;
    let failed = 0;

    // A caller that sent only notification_key (no pre-rendered notification)
    // still gets a visible push for tokens outside the per-token map — the
    // English key is real copy, so it degrades to English, never to silence.
    if (!notification && notificationKey) {
      const t = translatorFor("en");
      notification = {
        title: t(notificationKey.title, ...(notificationKey.title_args ?? [])),
        body: renderBody(t, notificationKey),
      };
    }

    for (const fcmToken of tokensToSend) {
      const copyForToken = notificationByToken.get(fcmToken) ?? notification;
      const success = await sendFcmMessage(firebaseToken, fcmToken, messageType, extraData, copyForToken, iosAlertOnly);
      if (success) sent++;
      else failed++;
    }

    // ── Piggyback: send evening_checkin to users at 8pm local ──
    let checkinSent = 0;
    let checkinFailed = 0;
    if (messageType === "sync_hourly") {
      const targets = await getEveningCheckinTargets(supabase);
      for (const c of targets) {
        const ok = await sendFcmMessage(firebaseToken, c.token, "evening_checkin", {}, eveningCheckinCopy(c.lang), true);
        if (ok) checkinSent++;
        else checkinFailed++;
      }
      if (targets.length > 0) {
        console.log(`Evening check-in: sent=${checkinSent}/${targets.length}`);
      }
    }

    console.log(`FCM push complete: sent=${sent}, failed=${failed}${checkinSent > 0 ? `, checkin=${checkinSent}` : ""}`);

    // Record the outcome so pipeline-watchdog can see a credential failure the
    // day it starts. The APNs key was Sandbox-only from launch until 2026-08-06
    // and every iOS push failed that whole time with nobody watching the logs.
    try {
      await supabase.from("push_delivery_log").insert({
        push_type: messageType,
        sent,
        failed,
      });
      // The check-in rode inside the sync_hourly count and was invisible;
      // its own row makes "did anyone get the 8pm reminder" answerable.
      if (checkinSent + checkinFailed > 0) {
        await supabase.from("push_delivery_log").insert({
          push_type: "evening_checkin",
          sent: checkinSent,
          failed: checkinFailed,
        });
      }
      // Keep the table small; it exists only for a 24h rolling ratio.
      await supabase
        .from("push_delivery_log")
        .delete()
        .lt("created_at", new Date(Date.now() - 14 * 86400000).toISOString());
    } catch (logErr) {
      console.error("push_delivery_log write failed:", String(logErr));
    }

    return new Response(
      JSON.stringify({ success: true, sent, failed, type: messageType, checkinSent }),
      { status: 200 }
    );
  } catch (error) {
    console.error("FCM push error:", error);
    return new Response(JSON.stringify({ error: String(error) }), { status: 500 });
  }
});