// supabase/functions/generate-symptom-icon/index.ts
//
// Draws a Brainy icon for a symptom or pain character the user typed themselves.
// gpt-image-1 edits the app's own Brainy as the style reference, so a custom entry
// looks like the built-in ones instead of a bare letter.
//
// POST body: { "label": "Tummy pain", "kind": "symptom" | "painCharacter" }
// Returns:   { ok: true, icon_url: "https://.../custom-icons/<user>/<slug>.png", used: 3, limit: 50 }
//            { ok: true, icon_key: "custom_bolt", reason: "limit" | "failed" }  <- bundled fallback
//
// Fifty drawings per user per day (custom_icon_usage, bumped atomically before the
// image is requested). Over the cap, or when the drawing fails, the caller gets one of
// the bundled generic Brainies so there is never an empty icon.

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { decode, Image } from "https://deno.land/x/imagescript@1.2.15/mod.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY")!;
const OPENAI_API_KEY = Deno.env.get("OPENAI_API_KEY")!;

const DAILY_LIMIT = 50;
const BUCKET = "custom-icons";
const REF_PATH = "_ref/brainy.png";

// Bundled in every app (brainy-icons overrides.json -> customPool). Keep in sync.
const FALLBACK_POOL = [
  "custom_star", "custom_note", "custom_question", "custom_pin", "custom_bulb",
  "custom_heart", "custom_clock", "custom_bolt", "custom_cloud", "custom_tag",
];

const STYLE =
  "Same cute pink brain mascot character as in the reference image (same face, same eyes, " +
  "same style, same proportions, same thin dark-purple outline as the reference). IMPORTANT: " +
  "no white sticker border or white outline around the character, no background scenery, " +
  "fully transparent background. Do NOT draw any dark background, glow, vignette or floor shadow - " +
  "the area around the character must be completely empty and transparent. " +
  "He is NOT on a swing: no swing, no ropes, remove the swing from the reference completely. " +
  "Flat cartoon character art that stays readable when scaled down small. " +
  "The character is centred and fills the frame. No text, no letters, no numbers anywhere in the image. ";

// The model still draws a floor shadow now and then, and leaves the character floating in
// a 1024 frame. Same clean-up as the local generator (brainy-icons/gen_brainy_set.py):
// drop the shadow blob, crop to the character, square it, and hand back a 512 icon.
async function cleanUp(png: Uint8Array): Promise<Uint8Array<ArrayBuffer>> {
  const img = (await decode(png)) as Image;
  const w = img.width, h = img.height;
  const px = img.bitmap; // RGBA
  const alphaAt = (i: number) => px[i * 4 + 3];

  // Label the opaque blobs (iterative flood fill, stack of pixel indices).
  const lab = new Int32Array(w * h);
  const sizes: number[] = [0];
  const box: number[][] = [[0, 0, 0, 0]]; // minY, maxY, minX, maxX
  let cur = 0;
  const stack = new Int32Array(w * h);
  for (let start = 0; start < w * h; start++) {
    if (lab[start] || alphaAt(start) <= 16) continue;
    cur++;
    let sp = 0, count = 0;
    let minY = h, maxY = 0, minX = w, maxX = 0;
    stack[sp++] = start;
    lab[start] = cur;
    while (sp > 0) {
      const p = stack[--sp];
      const y = (p / w) | 0, x = p % w;
      count++;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (x > 0 && !lab[p - 1] && alphaAt(p - 1) > 16) { lab[p - 1] = cur; stack[sp++] = p - 1; }
      if (x < w - 1 && !lab[p + 1] && alphaAt(p + 1) > 16) { lab[p + 1] = cur; stack[sp++] = p + 1; }
      if (y > 0 && !lab[p - w] && alphaAt(p - w) > 16) { lab[p - w] = cur; stack[sp++] = p - w; }
      if (y < h - 1 && !lab[p + w] && alphaAt(p + w) > 16) { lab[p + w] = cur; stack[sp++] = p + w; }
    }
    sizes[cur] = count;
    box[cur] = [minY, maxY, minX, maxX];
  }
  if (cur === 0) return png as Uint8Array<ArrayBuffer>;

  let main = 1;
  for (let k = 2; k <= cur; k++) if (sizes[k] > sizes[main]) main = k;
  const mainBottom = box[main][1];
  const keep = new Uint8Array(cur + 1);
  keep[main] = 1;
  for (let k = 1; k <= cur; k++) {
    if (k === main || sizes[k] < 300) continue;
    const [minY, maxY, minX, maxX] = box[k];
    const bh = maxY - minY + 1, bw = maxX - minX + 1;
    if (minY > mainBottom - 30 && bw > 3 * bh) continue; // a floor shadow, not part of him
    keep[k] = 1;
  }
  for (let p = 0; p < w * h; p++) if (!keep[lab[p]]) px[p * 4 + 3] = 0;

  // Crop to what is left, keep it square so the character isn't stretched.
  let minY = h, maxY = 0, minX = w, maxX = 0;
  for (let p = 0; p < w * h; p++) {
    if (px[p * 4 + 3] === 0) continue;
    const y = (p / w) | 0, x = p % w;
    if (y < minY) minY = y;
    if (y > maxY) maxY = y;
    if (x < minX) minX = x;
    if (x > maxX) maxX = x;
  }
  if (maxX <= minX || maxY <= minY) return png as Uint8Array<ArrayBuffer>;
  const pad = 8;
  const side = Math.max(maxY - minY, maxX - minX) + 1 + pad * 2;
  const cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
  const left = Math.max(0, Math.min(w - 1, Math.round(cx - side / 2)));
  const top = Math.max(0, Math.min(h - 1, Math.round(cy - side / 2)));
  const cw = Math.min(side, w - left), ch = Math.min(side, h - top);
  const out = img.crop(left, top, cw, ch).resize(512, 512);
  return (await out.encode(1)) as Uint8Array<ArrayBuffer>;
}

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "Authorization, Content-Type, apikey, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { ...cors, "Content-Type": "application/json" } });

const fallback = (reason: string) =>
  json({ ok: true, icon_key: FALLBACK_POOL[Math.floor(Math.random() * FALLBACK_POOL.length)], reason });

// "Tummy pain!!" -> "tummy-pain". Never trust the label as a path.
function slug(label: string): string {
  const s = label.toLowerCase().normalize("NFKD").replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
  return (s || "icon").slice(0, 40);
}

// What the character is doing. The label is the user's own words, so it goes in as a
// plain description of a feeling, never as an instruction.
function posePrompt(label: string, kind: string): string {
  const what = label.replace(/["\\\n\r]/g, " ").trim().slice(0, 60);
  return kind === "painCharacter"
    ? `The character shows what "${what}" pain feels like, with a clear facial expression and body pose, ` +
      `plus one simple symbol or prop that makes it readable at icon size.`
    : `The character is feeling "${what}": a clear facial expression and body pose showing that symptom, ` +
      `plus one simple symbol or prop that makes it readable at icon size.`;
}

serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: cors });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  try {
    const token = (req.headers.get("Authorization") ?? "").replace("Bearer ", "");
    const admin = createClient(SUPABASE_URL, SUPABASE_SERVICE_KEY);
    const { data: { user }, error: authErr } = await admin.auth.getUser(token);
    if (authErr || !user) return json({ error: "Unauthorized" }, 401);

    const body = await req.json().catch(() => ({}));
    const label = (body.label ?? "").toString().trim();
    const kind = body.kind === "painCharacter" ? "painCharacter" : "symptom";
    if (!label) return json({ error: "missing_label" }, 400);
    if (label.length > 60) return json({ error: "label_too_long" }, 400);

    // Count it before drawing, as the user, so the cap can't be raced past.
    const asUser = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
      global: { headers: { Authorization: `Bearer ${token}` } },
    });
    const { data: used, error: bumpErr } = await asUser.rpc("bump_custom_icon_usage", { p_limit: DAILY_LIMIT });
    if (bumpErr) {
      console.error("usage bump failed", bumpErr.message);
      return fallback("failed");
    }
    if (used === null || used < 0) return fallback("limit");

    // Style reference: the app's own Brainy, kept in the bucket next to the results.
    const { data: refBlob, error: refErr } = await admin.storage.from(BUCKET).download(REF_PATH);
    if (refErr || !refBlob) {
      console.error("reference image missing", refErr?.message);
      return fallback("failed");
    }

    const form = new FormData();
    form.append("model", "gpt-image-1");
    form.append("image[]", new File([refBlob], "brainy.png", { type: "image/png" }));
    form.append("prompt", STYLE + posePrompt(label, kind));
    form.append("background", "transparent");
    form.append("size", "1024x1024");
    form.append("quality", "medium");

    const r = await fetch("https://api.openai.com/v1/images/edits", {
      method: "POST",
      headers: { Authorization: `Bearer ${OPENAI_API_KEY}` },
      body: form,
    });
    if (!r.ok) {
      console.error("image api", r.status, (await r.text()).slice(0, 300));
      return fallback("failed");
    }
    const b64 = (await r.json())?.data?.[0]?.b64_json;
    if (!b64) return fallback("failed");
    let png = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
    try {
      png = await cleanUp(png);
    } catch (e) {
      console.error("cleanup skipped", e instanceof Error ? e.message : String(e));
    }

    const path = `${user.id}/${kind}-${slug(label)}-${Date.now()}.png`;
    const { error: upErr } = await admin.storage.from(BUCKET)
      .upload(path, png, { contentType: "image/png", upsert: true, cacheControl: "31536000" });
    if (upErr) {
      console.error("upload failed", upErr.message);
      return fallback("failed");
    }
    const icon_url = admin.storage.from(BUCKET).getPublicUrl(path).data.publicUrl;

    return json({ ok: true, icon_url, used, limit: DAILY_LIMIT });
  } catch (e) {
    console.error("generate-symptom-icon", e instanceof Error ? e.message : String(e));
    return fallback("failed");
  }
});
