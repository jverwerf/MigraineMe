import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

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

// Send FCM message to a single device
async function sendFcmMessage(
  accessToken: string,
  fcmToken: string,
  messageType: string
): Promise<boolean> {
  const projectId = FIREBASE_SERVICE_ACCOUNT.project_id;
  const url = `https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`;

  const message = {
    message: {
      token: fcmToken,
      data: {
        type: messageType,
      },
      android: {
        priority: "high",
      },
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

async function getEveningCheckinTokens(
  supabase: any
): Promise<string[]> {
  // Get all profiles with FCM tokens
  const { data: profiles, error } = await supabase
    .from("profiles")
    .select("user_id, fcm_token")
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
  const tokens: string[] = [];

  for (const profile of profiles) {
    const tz = tzMap[profile.user_id];
    if (!tz || !profile.fcm_token) continue;

    try {
      const localTime = new Date(
        now.toLocaleString("en-US", { timeZone: tz })
      );
      if (localTime.getHours() === 20) {
        tokens.push(profile.fcm_token);
      }
    } catch {
      // Invalid timezone, skip
    }
  }

  return tokens;
}

// ─── Main handler ───────────────────────────────────────────────────

serve(async (req) => {
  try {
    const url = new URL(req.url);
    let messageType = "sync_hourly";
    let directTokens: string[] | null = null;

    if (req.method === "POST") {
      const body = await req.json();
      messageType = body.type || "sync_hourly";

      // Direct token send — used by recalibration and other server-side features
      // that already know which specific tokens to target
      if (body.tokens && Array.isArray(body.tokens)) {
        directTokens = body.tokens;
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

    } else if (messageType === "evening_checkin") {
      // ── Evening check-in: only users where it's 8pm local ──
      tokensToSend = await getEveningCheckinTokens(supabase);
      console.log(`Evening check-in: ${tokensToSend.length} users at 8pm`);

    } else {
      // ── Standard metric-based push (unchanged) ──
      const metricMap: Record<string, string> = {
        sync_hourly: "user_location_daily",
        sync_location: "user_location_daily",
        sync_screen_time: "screen_time_daily",
        sync_weather: "weather",
        sync_noise: "ambient_noise_samples",
        sync_health_connect: "hrv_daily",
      };

      const metric = metricMap[messageType] || "user_location_daily";
      let enabledUserIds: Set<string>;

      if (messageType === "sync_health_connect") {
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
        const { data: enabledUsers, error: settingsError } = await supabase
          .from("metric_settings")
          .select("user_id")
          .eq("metric", metric)
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

    for (const fcmToken of tokensToSend) {
      const success = await sendFcmMessage(firebaseToken, fcmToken, messageType);
      if (success) sent++;
      else failed++;
    }

    // ── Piggyback: send evening_checkin to users at 8pm local ──
    let checkinSent = 0;
    if (messageType === "sync_hourly") {
      const checkinTokens = await getEveningCheckinTokens(supabase);
      for (const fcmToken of checkinTokens) {
        const ok = await sendFcmMessage(firebaseToken, fcmToken, "evening_checkin");
        if (ok) checkinSent++;
      }
      if (checkinTokens.length > 0) {
        console.log(`Evening check-in: sent=${checkinSent}/${checkinTokens.length}`);
      }
    }

    console.log(`FCM push complete: sent=${sent}, failed=${failed}${checkinSent > 0 ? `, checkin=${checkinSent}` : ""}`);

    return new Response(
      JSON.stringify({ success: true, sent, failed, type: messageType, checkinSent }),
      { status: 200 }
    );
  } catch (error) {
    console.error("FCM push error:", error);
    return new Response(JSON.stringify({ error: String(error) }), { status: 500 });
  }
});