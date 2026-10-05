// supabase/functions/partner-onboarding-link/index.ts
//
// Gives an affiliate partner a stable URL for setting up how they get paid.
//
// WHY THIS EXISTS: a Stripe onboarding link expires five minutes after it is
// minted, so one cannot be pasted into an email — by the time the partner
// reads it, it is dead. Instead each partner has one permanent secret URL
// (migraineme.app/payouts/<token>); every visit mints a fresh link and
// redirects to it. The same URL keeps working if they abandon halfway, come
// back a week later, or need to change their bank details.
//
// The token in the URL is the only credential, so it is compared against the
// partners table server-side and nothing about the partner is echoed back.
//
// Auth: deliberately public (no JWT) — the token IS the auth. Deployed with
// --no-verify-jwt.

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const STRIPE_KEY = Deno.env.get("STRIPE_SECRET_KEY")!;

const SITE = "https://migraineme.app";

// Same guard as partner-payout: a test key would make Stripe return success
// while nothing real is created, and the partner would onboard into a
// sandbox account that can never be paid.
const liveKey = () => STRIPE_KEY && !STRIPE_KEY.includes("_test_");

const page = (title: string, body: string, status = 200) =>
  new Response(
    `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">` +
      `<title>${title}</title>` +
      `<style>body{font:16px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;` +
      `margin:0;padding:48px 20px;background:#faf8fc;color:#1b1023}` +
      `.w{max-width:560px;margin:0 auto}h1{font-size:22px;margin:0 0 12px}` +
      `p{margin:0 0 12px;color:#4a3a56}</style>` +
      `<div class="w"><h1>${title}</h1>${body}</div>`,
    { status, headers: { "content-type": "text/html; charset=utf-8" } },
  );

// The partner's own words for our three small pages; Stripe translates its
// own screens from the same browser setting.
type Copy = { badT: string; bad: string; errT: string; err: string; doneT: string; done1: string; done2: string };
const COPY: Record<string, Copy> = {
  en: {
    badT: "Link not recognised", bad: "This payout link is not valid. Please use the link we sent you.",
    errT: "Something went wrong", err: "We could not open the payout setup. Please try again shortly, or let us know.",
    doneT: "Thank you",
    done1: "Your details are with Stripe. If anything else is needed they will ask you for it, and you can come back to this same link at any time to check or change your details.",
    done2: "Commission is worked out monthly and paid to the account you just set up.",
  },
  de: {
    badT: "Link nicht erkannt", bad: "Dieser Auszahlungslink ist nicht gültig. Bitte nutze den Link, den wir dir geschickt haben.",
    errT: "Etwas ist schiefgelaufen", err: "Wir konnten die Einrichtung der Auszahlung nicht öffnen. Bitte versuche es gleich noch einmal oder gib uns Bescheid.",
    doneT: "Vielen Dank",
    done1: "Deine Angaben sind bei Stripe. Falls noch etwas fehlt, fragt Stripe bei dir nach. Über denselben Link kannst du deine Angaben jederzeit prüfen oder ändern.",
    done2: "Die Provision wird monatlich berechnet und auf das Konto ausbezahlt, das du gerade hinterlegt hast.",
  },
  es: {
    badT: "Enlace no reconocido", bad: "Este enlace de pagos no es válido. Usa el enlace que te enviamos.",
    errT: "Algo ha fallado", err: "No hemos podido abrir la configuración de pagos. Inténtalo de nuevo en un momento o avísanos.",
    doneT: "Gracias",
    done1: "Tus datos están en Stripe. Si falta algo, te lo pedirán, y puedes volver a este mismo enlace cuando quieras para revisar o cambiar tus datos.",
    done2: "La comisión se calcula cada mes y se paga en la cuenta que acabas de configurar.",
  },
  fr: {
    badT: "Lien non reconnu", bad: "Ce lien de paiement n'est pas valide. Utilise le lien que nous t'avons envoyé.",
    errT: "Un problème est survenu", err: "Nous n'avons pas pu ouvrir la configuration des paiements. Réessaie dans un instant ou préviens-nous.",
    doneT: "Merci",
    done1: "Tes informations sont chez Stripe. S'il manque quelque chose, ils te le demanderont, et tu peux revenir sur ce même lien à tout moment pour vérifier ou modifier tes informations.",
    done2: "La commission est calculée chaque mois et versée sur le compte que tu viens de configurer.",
  },
  it: {
    badT: "Link non riconosciuto", bad: "Questo link per i pagamenti non è valido. Usa il link che ti abbiamo inviato.",
    errT: "Qualcosa è andato storto", err: "Non siamo riusciti ad aprire la configurazione dei pagamenti. Riprova tra poco o faccelo sapere.",
    doneT: "Grazie",
    done1: "I tuoi dati sono presso Stripe. Se serve altro te lo chiederanno, e puoi tornare su questo stesso link in qualsiasi momento per controllare o modificare i tuoi dati.",
    done2: "La commissione viene calcolata ogni mese e pagata sul conto che hai appena configurato.",
  },
  nl: {
    badT: "Link niet herkend", bad: "Deze uitbetalingslink is niet geldig. Gebruik de link die we je hebben gestuurd.",
    errT: "Er ging iets mis", err: "We konden de uitbetalingsinstellingen niet openen. Probeer het zo opnieuw of laat het ons weten.",
    doneT: "Dank je wel",
    done1: "Je gegevens staan bij Stripe. Als er nog iets nodig is, vragen zij je daarom, en je kunt altijd naar dezelfde link terugkomen om je gegevens te bekijken of te wijzigen.",
    done2: "De commissie wordt maandelijks berekend en uitbetaald op de rekening die je net hebt ingesteld.",
  },
  pt: {
    badT: "Link não reconhecido", bad: "Este link de pagamentos não é válido. Usa o link que te enviámos.",
    errT: "Algo correu mal", err: "Não conseguimos abrir a configuração de pagamentos. Tenta de novo daqui a pouco ou avisa-nos.",
    doneT: "Obrigado",
    done1: "Os teus dados estão na Stripe. Se faltar alguma coisa, eles pedem-ta, e podes voltar a este mesmo link sempre que quiseres para ver ou alterar os teus dados.",
    done2: "A comissão é calculada todos os meses e paga na conta que acabaste de configurar.",
  },
};

const copyFor = (req: Request): Copy => {
  for (const part of (req.headers.get("accept-language") ?? "").split(",")) {
    const l = part.trim().slice(0, 2).toLowerCase();
    if (COPY[l]) return COPY[l];
  }
  return COPY.en;
};

const stripe = async (path: string, form: Record<string, string>) => {
  const body = new URLSearchParams(form);
  const r = await fetch("https://api.stripe.com/v1/" + path, {
    method: "POST",
    headers: {
      Authorization: "Bearer " + STRIPE_KEY,
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body,
  });
  return { ok: r.ok, data: await r.json() };
};

serve(async (req) => {
  const url = new URL(req.url);
  const token = (url.searchParams.get("t") || "").trim();
  const c = copyFor(req);

  if (!liveKey()) {
    return page("Not available", "<p>Payout setup is not configured. Please contact us.</p>", 503);
  }
  if (!/^[a-f0-9]{32}$/.test(token)) {
    return page(c.badT, `<p>${c.bad}</p>`, 404);
  }

  const db = createClient(SUPABASE_URL, SUPABASE_SERVICE_KEY);
  const { data: partner, error } = await db
    .from("partners")
    .select("id, name, contact_email, stripe_connect_account_id, stripe_country")
    .eq("onboarding_token", token)
    .maybeSingle();

  if (error) return page(c.errT, `<p>${c.err}</p>`, 500);
  if (!partner) {
    return page(c.badT, `<p>${c.bad}</p>`, 404);
  }

  // The partner has come back from Stripe. Stripe does not tell us here
  // whether they finished, so say what is true and let the accrual/payout
  // job be the judge.
  if (url.searchParams.get("done")) {
    return page(c.doneT, `<p>${c.done1}</p><p>${c.done2}</p>`);
  }

  let account = partner.stripe_connect_account_id;
  if (!account) {
    // The account must be opened in the country the partner banks in, and
    // Stripe never lets it be changed afterwards. Transfers from our UK
    // platform reach the UK, EEA, Switzerland, US and Canada.
    const country = (partner.stripe_country || "GB").toUpperCase();
    const base = {
      type: "express",
      country,
      email: partner.contact_email || "",
      business_type: "individual",
      "capabilities[transfers][requested]": "true",
      "metadata[partner_id]": partner.id,
    };
    let created = await stripe("accounts", base);
    if (!created.ok && country !== "GB") {
      // Outside the platform's own country Stripe can refuse an account that
      // asks for transfers alone; it then wants card_payments alongside.
      console.error(`[partner-onboarding-link] transfers-only refused for ${country}: ${JSON.stringify(created.data?.error ?? created.data)}`);
      created = await stripe("accounts", { ...base, "capabilities[card_payments][requested]": "true" });
    }
    if (!created.ok || !created.data?.id) {
      console.error(`[partner-onboarding-link] account create failed for partner ${partner.id}: ${JSON.stringify(created.data?.error ?? created.data)}`);
      return page(c.errT, `<p>${c.err}</p>`, 502);
    }
    account = created.data.id as string;
    // Store it before redirecting: if the partner abandons onboarding, the
    // next visit must reuse this account rather than orphan it and make a
    // second one.
    await db.from("partners").update({ stripe_connect_account_id: account }).eq("id", partner.id);
  }

  const back = `${SITE}/payouts/${token}`;
  const link = await stripe("account_links", {
    account,
    refresh_url: back,
    return_url: back + "?done=1",
    type: "account_onboarding",
  });
  if (!link.ok || !link.data?.url) {
    console.error(`[partner-onboarding-link] account link failed for partner ${partner.id}: ${JSON.stringify(link.data?.error ?? link.data)}`);
    return page(c.errT, `<p>${c.err}</p>`, 502);
  }

  return Response.redirect(link.data.url as string, 302);
});
