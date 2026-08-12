// Daily winback: emails users whose trial ended ~3 days ago and didn't subscribe.
// Cohort from public.winback_due(); each send logged to public.winback_emails_sent.
// New branded template + per-user Android Play code (claim_winback_android_code) + iOS
// Apple offer-code redeem link. Sends via Resend from help@.
//
// Rendered in the recipient's display language (profiles.lang via winback_due,
// English fallback). An email is like a push: the finished text is all the
// reader ever sees, so this send IS the render boundary. The copy is translated
// as the same fragments the HTML styles bold, because the styling spans have to
// survive translation; the plain-text body recomposes the identical fragments.
import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";
import { t, toLang, type Lang } from "../_shared/i18n.ts";

const TOKEN = "wb-2026-migraineme-x7q9";
const FROM = "Jordy at MigraineMe <help@migraineme.app>";
const REPLY_TO = "help@migraineme.app";
const SUBJECT = "A free month on us, and a quick question";
const IOS_LINK = "https://apps.apple.com/redeem?ctx=offercodes&id=6760654324&code=WELCOMEBACK";
const HEADER = "https://qykflarpibofvffmzghi.supabase.co/storage/v1/object/public/public-assets/winback/header4.png";
const BANNER = "#2E1065", DPURPLE = "#5B21B6", PINK = "#E879A0";
const FONT = "-apple-system,Segoe UI,Roboto,Helvetica,sans-serif";

function greeting(lang: Lang, name: string | null): string {
  return name && name.trim() ? t(lang, "Hi %1$s,", name.trim()) : t(lang, "Hi there,");
}
async function claimAndroidCode(admin: any, email: string): Promise<string | null> {
  const { data, error } = await admin.rpc("claim_winback_android_code", { p_email: email.toLowerCase() });
  if (error) { console.error("claim code error:", error.message); return null; }
  return (data as string) ?? null;
}
const BTN = (href: string, label: string) =>
  `<a href="${href}" style="display:inline-block;width:140px;background:${DPURPLE};color:#ffffff;text-decoration:none;padding:13px 0;text-align:center;border-radius:10px;font-weight:700;font-family:${FONT};font-size:15px;">${label}</a>`;
function ctaHtml(lang: Lang, code: string | null): string {
  const head = `<p style="margin:20px 0 12px;text-align:center;"><strong>${t(lang, "Claim your free month:")}</strong></p>`;
  // "iPhone" and "Android" are product names, the same in every language.
  if (code) return `${head}<p style="margin:0;text-align:center;">${BTN(IOS_LINK, "iPhone")}&nbsp;&nbsp;${BTN(`https://play.google.com/redeem?code=${code}`, "Android")}</p>`;
  return `${head}<p style="margin:0 0 10px;text-align:center;">${BTN(IOS_LINK, "iPhone")}</p><p style="margin:0;text-align:center;">${t(lang, "On Android: open MigraineMe, head to the upgrade screen, and enter the code %1$s.", `<strong style="color:${DPURPLE};">WELCOMEBACK</strong>`)}</p>`;
}
function ctaText(lang: Lang, code: string | null): string {
  const a = code
    ? `Android: https://play.google.com/redeem?code=${code}`
    : t(lang, "On Android: open MigraineMe, head to the upgrade screen, and enter the code %1$s.", "WELCOMEBACK");
  return `${t(lang, "Claim your free month:")}\niPhone: ${IOS_LINK}\n${a}`;
}
function textBody(lang: Lang, name: string | null, code: string | null): string {
  const p1 = t(lang, "I noticed your MigraineMe trial wrapped up a little while back and you didn't carry on, which is totally fine. I just wanted to reach out before we lose touch.");
  const p2 = `${t(lang, "If you've got a second, I'd really like to know what made you stop.")} ${t(lang, "Was it the price, something missing, or just not the right time?")} ${t(lang, "Even a one-line reply genuinely helps shape what we build next.")}`;
  const p3 = `${t(lang, "And if it was timing or cost, I'd love to give you")} ${t(lang, "another month, free, cancel anytime.")} ${t(lang, "MigraineMe's insights get better the more you log.")} ${t(lang, "Triggers, patterns and risk forecasts need a real stretch of data before they show up, and one month often isn't long enough to see them. So here's another, to give your data a proper chance.")} ${t(lang, "Everything's still there, right where you left it.")}`;
  const p4 = `${t(lang, "We're building MigraineMe into a place where")} ${t(lang, "data and evidence come first")} ${t(lang, "for people living with migraine. It's still early, and I'd love to have you along.")}`;
  const p5 = t(lang, "Either way, thanks for giving MigraineMe a go.");
  return `${greeting(lang, name)}\n\n${p1}\n\n${p2}\n\n${p3}\n\n${ctaText(lang, code)}\n\n${p4}\n\n${p5}\n\nJordy\nMigraineMe`;
}
function htmlBody(lang: Lang, name: string | null, code: string | null): string {
  const bv = (s: string) => `<strong style="color:${DPURPLE};">${s}</strong>`;
  const bk = (s: string) => `<strong style="color:${PINK};">${s}</strong>`;
  return `<div style="margin:0;padding:24px 12px;background:#f4f2fb;font-family:${FONT};">
  <div style="max-width:600px;margin:0 auto;background:#ffffff;border-radius:16px;overflow:hidden;border:1px solid #ece8fb;color:#1a1a1a;">
    <div style="background:${BANNER};padding:15px 24px;text-align:center;">
      <span style="font-size:22px;font-weight:800;letter-spacing:0.6px;"><span style="color:#ffffff;">Migraine</span><span style="color:${PINK};">Me</span></span>
    </div>
    <img src="${HEADER}" alt="where did we go wrong?" width="600" style="width:100%;max-width:600px;height:auto;display:block;border:0;">
    <div style="padding:22px 28px 26px;font-size:15px;line-height:1.55;">
      <p style="margin-top:0;">${greeting(lang, name)}</p>
      <p>${t(lang, "I noticed your MigraineMe trial wrapped up a little while back and you didn't carry on, which is totally fine. I just wanted to reach out before we lose touch.")}</p>
      <p>${t(lang, "If you've got a second, I'd really like to know what made you stop.")} ${bv(t(lang, "Was it the price, something missing, or just not the right time?"))} ${t(lang, "Even a one-line reply genuinely helps shape what we build next.")}</p>
      <p>${t(lang, "And if it was timing or cost, I'd love to give you")} ${bk(t(lang, "another month, free, cancel anytime."))} ${bv(t(lang, "MigraineMe's insights get better the more you log."))} ${t(lang, "Triggers, patterns and risk forecasts need a real stretch of data before they show up, and one month often isn't long enough to see them. So here's another, to give your data a proper chance.")} ${bk(t(lang, "Everything's still there, right where you left it."))}</p>
      ${ctaHtml(lang, code)}
      <p style="margin-top:20px;">${t(lang, "We're building MigraineMe into a place where")} ${bv(t(lang, "data and evidence come first"))} ${t(lang, "for people living with migraine. It's still early, and I'd love to have you along.")}</p>
      <p>${t(lang, "Either way, thanks for giving MigraineMe a go.")}</p>
      <p style="margin-bottom:0;">Jordy<br><span style="color:${DPURPLE};font-weight:700;">MigraineMe</span></p>
    </div>
    <div style="padding:16px 28px;background:#faf9ff;border-top:1px solid #ece8fb;text-align:center;color:#8a86a0;font-size:12px;">
      <a href="https://migraineme.app" style="color:${DPURPLE};text-decoration:none;">migraineme.app</a>
    </div>
  </div>
</div>`;
}

async function sendOne(resendKey: string, admin: any, to: string, name: string | null, lang: Lang) {
  const code = await claimAndroidCode(admin, to);
  const res = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${resendKey}` },
    body: JSON.stringify({ from: FROM, to: [to], reply_to: REPLY_TO, subject: t(lang, SUBJECT), text: textBody(lang, name, code), html: htmlBody(lang, name, code) }),
  });
  const data = await res.json();
  return { ok: res.ok, id: data?.id ?? null, error: res.ok ? null : data };
}
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

serve(async (req) => {
  try {
    const body = await req.json().catch(() => ({}));
    if (body?.token !== TOKEN) return new Response(JSON.stringify({ error: "Unauthorized" }), { status: 401, headers: { "Content-Type": "application/json" } });
    const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const resendKey = Deno.env.get("RESEND_API_KEY");
    if (!resendKey) return new Response(JSON.stringify({ error: "Missing RESEND_API_KEY" }), { status: 500, headers: { "Content-Type": "application/json" } });
    const admin = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false } });
    const { data: due, error: dueErr } = await admin.rpc("winback_due");
    if (dueErr) return new Response(JSON.stringify({ error: dueErr.message }), { status: 500, headers: { "Content-Type": "application/json" } });
    const rows = (due ?? []) as { email: string; first_name: string | null; lang?: string | null }[];
    if (body?.dryRun === true) return new Response(JSON.stringify({ dryRun: true, due: rows.length, recipients: rows }), { status: 200, headers: { "Content-Type": "application/json" } });
    const results: any[] = [];
    for (const row of rows) {
      const r = await sendOne(resendKey, admin, row.email, row.first_name, toLang(row.lang));
      if (r.ok) await admin.from("winback_emails_sent").insert({ email: row.email.toLowerCase(), source: "daily-cron" });
      results.push({ to: row.email, ok: r.ok, id: r.id, error: r.error });
      await sleep(600);
    }
    const sent = results.filter((x) => x.ok).length;
    return new Response(JSON.stringify({ due: rows.length, sent, failed: rows.length - sent, results }), { status: 200, headers: { "Content-Type": "application/json" } });
  } catch (err) {
    return new Response(JSON.stringify({ error: String((err as any)?.message ?? err) }), { status: 500, headers: { "Content-Type": "application/json" } });
  }
});
