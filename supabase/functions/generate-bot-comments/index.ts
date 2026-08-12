import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";
import { getLangs, t } from "../_shared/i18n.ts";
const BOT_PROFILES = [
  "LUNA (slug: luna) Sleep and Recovery. Matches when article is directly about: sleep, insomnia, circadian rhythm, shift work, jet lag, oversleeping, recovery, rest, let-down migraines, sleep hygiene.",
  "NORA (slug: nora) Stress and Mental Health. Matches when article is directly about: stress, anxiety, depression, burnout, mental health, therapy, CBT, mindfulness, emotional triggers, work-life balance, let-down effect.",
  "LENA (slug: lena) Hormones and Cycle. Matches when article is directly about: hormones, menstrual migraine, period, contraceptives, perimenopause, menopause, pregnancy, estrogen, cycle tracking.",
  "KAI (slug: kai) Food Drink and Supplements. Matches when article is directly about: food triggers, diet, nutrition, recipes discussing migraine trigger ingredients, meal timing, hydration, alcohol, caffeine, supplements, magnesium, riboflavin, CoQ10, elimination diet, histamine, tyramine.",
  "MAYA (slug: maya) Weather and Senses. Matches when article is directly about: weather, barometric pressure, light sensitivity, photophobia, screens, blue light, noise, phonophobia, smells, osmophobia, sensory overload, aura, visual disturbances.",
  "SAM (slug: sam) Movement and Body. Matches when article is directly about: exercise, physical activity, posture, neck tension, physiotherapy, yoga, overexertion, physical triggers, warm up routines, stretching.",
  "PRIYA (slug: priya) Treatments and Medication. Matches when article is directly about: medications, treatments, CGRP, Botox, triptans, gepants, preventive therapy, acute treatment, clinical trials, new research, medication overuse, rebound headache, treatment guidelines, drug side effects."
].join("\n");
const PROMPTS = {};
PROMPTS["luna"] = [
  "You are Luna. Sleep, recovery, circadian rhythm expert.",
  "",
  "BAD (never write like this):",
  "Sleep is really important for migraine management. Getting consistent rest can help reduce the frequency of attacks. If sleep disruption is one of your triggers, it is worth paying attention to your sleep habits. Has anyone found good sleep strategies?",
  "",
  "GOOD (write like this):",
  "If sleep disruption is one of your triggers, the consistency point here matters more than total hours. Shifting your wake time by even 90 minutes on weekends can trigger a Monday attack, sometimes called a let down migraine. Keeping wake time fixed, even on days off, tends to be more protective than sleeping longer. Anyone noticed a pattern between weekend lie ins and Monday migraines?",
  "",
  "BAD is vague filler. GOOD has a specific number (90 min), a named mechanism (let down migraine), a specific tip (fix wake time not bedtime)."
].join("\n");
PROMPTS["nora"] = [
  "You are Nora. Stress, anxiety, mental health, burnout expert.",
  "",
  "BAD (never write like this):",
  "Stress management is really important for migraine sufferers. Finding ways to reduce stress can help prevent attacks. It is worth exploring different relaxation techniques. What stress management strategies have worked for you?",
  "",
  "GOOD (write like this):",
  "If stress is a trigger for you, the timing matters more than the intensity. It is often not the stressful period itself but the drop afterwards that triggers an attack. This is called the let down effect and explains why migraines often hit on Saturday morning or the first day of a holiday. Gradual wind downs instead of sudden relaxation can help. Has anyone noticed attacks clustering after stressful periods rather than during them?",
  "",
  "BAD is generic advice. GOOD names a mechanism (let down effect), a timing pattern (Saturday morning, first day of holiday), a counter strategy (gradual wind down)."
].join("\n");
PROMPTS["lena"] = [
  "You are Lena. Hormones, menstrual cycle, estrogen, perimenopause expert.",
  "",
  "BAD (never write like this):",
  "Hormonal changes can be a major trigger for migraines. It is worth tracking your cycle to see if there is a connection. There are treatment options available for hormonal migraines. Has anyone noticed a hormonal pattern?",
  "",
  "GOOD (write like this):",
  "If your migraines track with your cycle, three months of logging attacks alongside your period can reveal whether they cluster in the 2 days before bleeding. That is the classic estrogen withdrawal window. If they do, there are mini prevention options like timed frovatriptan just for those few days rather than daily medication. Has anyone tried a short course preventive timed to their cycle?",
  "",
  "BAD says track your cycle with no specifics. GOOD gives a window (2 days before bleeding), a mechanism (estrogen withdrawal), a drug (frovatriptan), a strategy (short course timed preventive)."
].join("\n");
PROMPTS["kai"] = [
  "You are Kai. Food triggers, nutrition, supplements, meal timing expert.",
  "",
  "BAD (never write like this):",
  "Diet can play a big role in migraine management. Certain foods may trigger attacks while others can help. It is worth paying attention to what you eat and how it affects your migraines. What dietary changes have helped you?",
  "",
  "GOOD (write like this):",
  "The meal timing angle here is more interesting than any specific food list. Blood sugar drops can lower the threshold for an attack, so going 4 to 5 hours without eating is often a bigger trigger than any single food. For supplements, the evidence is strongest for magnesium glycinate at 400mg daily, but it takes about 3 months to see results. Has anyone tracked whether meal timing or specific foods are the bigger trigger for them?",
  "",
  "BAD says diet plays a role. GOOD gives a threshold (4 to 5 hours), a supplement dose (magnesium glycinate 400mg), a timeframe (3 months)."
].join("\n");
PROMPTS["maya"] = [
  "You are Maya. Weather, barometric pressure, light, sound, sensory triggers expert.",
  "",
  "BAD (never write like this):",
  "Environmental factors like weather and light can trigger migraines. It is worth being aware of your sensory triggers and taking steps to manage them. Wearing sunglasses or avoiding bright lights may help. What environmental triggers affect you most?",
  "",
  "GOOD (write like this):",
  "If light is a trigger for you, not all tinted glasses are equal. FL 41 tinted lenses have the strongest evidence for migraine specifically, and they work better than generic blue light glasses. The rose tint filters the green light wavelength around 520nm which seems to be the most pain triggering. Regular sunglasses can actually make photophobia worse over time by dark adapting your eyes. Has anyone compared FL 41 lenses to regular blue light glasses?",
  "",
  "BAD says wear sunglasses which is obvious and wrong. GOOD names a product (FL 41), a wavelength (520nm), explains why sunglasses backfire."
].join("\n");
PROMPTS["sam"] = [
  "You are Sam. Exercise, posture, neck tension, physical triggers expert.",
  "",
  "BAD (never write like this):",
  "Exercise can be both a trigger and a treatment for migraines. It is important to find the right balance and listen to your body. Starting slowly and building up can help prevent exercise triggered attacks. What exercise routines work for you?",
  "",
  "GOOD (write like this):",
  "If exercise triggers your attacks, avoiding sudden heart rate spikes matters more than overall intensity. A 10 minute graduated warm up before anything intense can make the difference between a safe workout and a triggered attack. Staying in heart rate zone 2 during the warm up before pushing higher is a good rule. Has anyone found a specific warm up routine that reliably prevents exercise triggered attacks?",
  "",
  "BAD says start slowly. GOOD gives a duration (10 minutes), a metric (heart rate zone 2), a mechanism (spikes not intensity)."
].join("\n");
PROMPTS["priya"] = [
  "You are Priya. Medications, CGRP, Botox, triptans, gepants, clinical trials expert.",
  "",
  "BAD (never write like this):",
  "Preventive treatment is vital for those with chronic migraines. There are many newer options available that can help reduce attack frequency. It is worth discussing all options with your doctor. What treatments have worked for you?",
  "",
  "GOOD (write like this):",
  "If you are seeing a GP rather than a neurologist for prevention, most GPs can only prescribe first line preventives like propranolol or amitriptyline. CGRP therapies usually need a specialist referral, and most insurers require documented failure on 2 to 3 older preventives first. Each trial takes about 3 months minimum, so if you think you will need newer treatments, start that process early. Has anyone had a GP willing to start the CGRP referral process without going through every older drug first?",
  "",
  "BAD says newer options without naming any. GOOD names drugs (propranolol, amitriptyline, CGRPs), numbers (2 to 3 failures, 3 months), a real problem (GP prescribing limits)."
].join("\n");
const VALID = [
  "luna",
  "nora",
  "lena",
  "kai",
  "maya",
  "sam",
  "priya"
];
async function notifySubscribers(sb, companionId, companionSlug, article, errs, pending) {
  try {
    const { data: subs, error: subErr } = await sb.from("ai_companion_subscriptions").select("user_id").eq("companion_id", companionId);
    if (subErr || !subs?.length) return;
    const companionName = companionSlug.charAt(0).toUpperCase() + companionSlug.slice(1);
    const shortTitle = article.title.length > 60 ? article.title.substring(0, 57) + "..." : article.title;
    const favoriteRows = subs.map((s)=>({
        user_id: s.user_id,
        article_id: article.id
      }));
    const meTooRows = subs.map((s)=>({
        user_id: s.user_id,
        post_id: article.id
      }));
    // Stored in ENGLISH on purpose. These rows are read back by the app, so the
    // app translates them at render through Strings — which means they follow
    // the user when they switch language, where a row translated at insert time
    // would be frozen in whatever language they had the day it was written.
    // (Today the app only counts unread rows; the title and body are ready for
    // when it lists them.)
    const notificationRows = subs.map((s)=>({
        user_id: s.user_id,
        type: "article_comment",
        reference_id: article.id,
        title: `${companionName} commented`,
        body: shortTitle,
        read: false
      }));
    const { error: favErr } = await sb.from("article_favorites").upsert(favoriteRows, {
      onConflict: "user_id,article_id",
      ignoreDuplicates: true
    });
    if (favErr) errs.push(`fav/${companionSlug}: ${favErr.message}`);
    const { error: mtErr } = await sb.from("forum_me_too").upsert(meTooRows, {
      onConflict: "user_id,post_id",
      ignoreDuplicates: true
    });
    if (mtErr) errs.push(`metoo/${companionSlug}: ${mtErr.message}`);
    const { error: notifErr } = await sb.from("community_notifications").insert(notificationRows);
    if (notifErr) errs.push(`notif/${companionSlug}: ${notifErr.message}`);

    // The in-app badge above is community state and always fires. The push is
    // batched instead of sent here: one run can comment on several articles
    // with several companions, and a push per comment would mean a handful of
    // notifications arriving at once. Record who would be told and what, then
    // flush a single push per user at the end of the run.
    for (const sub of subs){
      let e = pending.get(sub.user_id);
      if (!e) {
        e = {
          count: 0,
          names: new Set(),
          firstTitle: shortTitle
        };
        pending.set(sub.user_id, e);
      }
      e.count++;
      e.names.add(companionName);
    }
  } catch (e) {
    errs.push(`notifySubscribers/${companionSlug}: ${e.message}`);
  }
}
serve(async (req)=>{
  // ── Cron auth ──
  const cronSecret = Deno.env.get("CRON_SECRET");
  if (!cronSecret) return R({
    error: "Server misconfigured"
  }, 500);
  if (req.headers.get("x-cron-secret") !== cronSecret) return R({
    error: "Forbidden"
  }, 403);
  const SU = Deno.env.get("SUPABASE_URL");
  const SK = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const OK = Deno.env.get("OPENAI_API_KEY");
  if (!SU || !SK) return R({
    error: "Missing SUPABASE env"
  }, 500);
  if (!OK) return R({
    error: "Missing OPENAI_API_KEY"
  }, 500);
  const sb = createClient(SU, SK);
  let body = {};
  try {
    body = await req.json().catch(()=>({}));
  } catch (_e) {
    body = {};
  }
  try {
    const { data: comps, error: ce } = await sb.from("ai_companions").select("id,name,slug").eq("is_active", true);
    if (ce || !comps?.length) return R({
      error: "No companions",
      detail: ce?.message
    }, 500);
    const { data: am } = await sb.from("ai_companion_auth").select("companion_id,user_id");
    const CU = {};
    const CI = {};
    for (const r of am ?? [])CU[r.companion_id] = r.user_id;
    for (const c of comps)CI[c.slug] = c.id;
    let arts = [];
    if (body.article_id) {
      const { data, error } = await sb.from("articles").select("id,title,url,ai_summary,source").eq("id", body.article_id).single();
      if (error) return R({
        error: "Fetch failed",
        detail: error.message
      }, 500);
      if (data) arts = [
        data
      ];
    } else {
      const { data: all, error } = await sb.from("articles").select("id,title,url,ai_summary,source").eq("status", "published").order("published_at", {
        ascending: false
      }).limit(50);
      if (error) return R({
        error: "Fetch failed",
        detail: error.message
      }, 500);
      arts = all ?? [];
    }
    if (!arts.length) return R({
      message: "No articles to process"
    });
    const candIds = arts.map((a)=>a.id);
    const { data: existingTracking } = await sb.from("ai_companion_comments").select("article_id,companion_id").in("article_id", candIds);
    const trackedByArt = {};
    for (const r of existingTracking ?? []){
      if (!trackedByArt[r.article_id]) trackedByArt[r.article_id] = new Set();
      trackedByArt[r.article_id].add(r.companion_id);
    }
    if (!body.article_id) {
      arts = arts.filter((a)=>(trackedByArt[a.id]?.size || 0) < comps.length).slice(0, 3);
      if (!arts.length) return R({
        message: "No articles to process"
      });
    }
    const aids = arts.map((a)=>a.id);
    const { data: tr } = await sb.from("article_tags").select("article_id,tag_id").in("article_id", aids);
    const tis = new Set((tr ?? []).map((r)=>r.tag_id));
    const tnm = {};
    if (tis.size > 0) {
      const { data: tn } = await sb.from("tags").select("id,name").in("id", Array.from(tis));
      for (const t of tn ?? [])tnm[t.id] = t.name;
    }
    const atm = {};
    for (const r of tr ?? []){
      if (!atm[r.article_id]) atm[r.article_id] = [];
      const n = tnm[r.tag_id];
      if (n) atm[r.article_id].push(n);
    }
    let tc = 0;
    let tsk = 0;
    const errs = [];
    // user_id -> { count, names, firstTitle } for the end-of-run push.
    const pending = new Map();
    const gen = [];
    for (const art of arts){
      const trackedComps = trackedByArt[art.id] ?? new Set();
      const tags = atm[art.id] ?? [];
      let ac = "";
      try {
        ac = await FC(art.url);
      } catch (_e) {}
      const ctx = "Title: " + art.title + "\nSource: " + (art.source || "unknown") + "\nTags: " + (tags.join(", ") || "none") + "\nSummary: " + (art.ai_summary || "No summary") + "\nContent: " + (ac.substring(0, 2000) || "unavailable");
      let ms = [];
      try {
        ms = await pick(ctx, OK);
      } catch (e) {
        errs.push("pick/" + art.title.substring(0, 40) + ": " + e.message);
        continue;
      }
      ms = ms.filter((sl)=>!trackedComps.has(CI[sl]));
      if (!ms.length) {
        for (const c of comps){
          if (!trackedComps.has(c.id)) await sb.rpc("insert_bot_tracking", {
            p_companion_id: c.id,
            p_article_id: art.id,
            p_relevance: false
          });
        }
        tsk++;
        continue;
      }
      for (const sl of ms){
        const ci = CI[sl];
        if (!ci) continue;
        const bu = CU[ci];
        if (!bu) {
          errs.push(sl + ": no auth");
          continue;
        }
        try {
          const cm = await genComment(sl, ctx, OK);
          if (!cm) {
            await sb.rpc("insert_bot_tracking", {
              p_companion_id: ci,
              p_article_id: art.id,
              p_relevance: false
            });
            continue;
          }
          const { data: cid, error: ie } = await sb.rpc("insert_bot_comment", {
            p_article_id: art.id,
            p_user_id: bu,
            p_body: cm
          });
          if (ie) {
            errs.push(sl + "/" + art.title.substring(0, 40) + ": " + ie.message);
            continue;
          }
          await sb.rpc("insert_bot_tracking", {
            p_companion_id: ci,
            p_article_id: art.id,
            p_relevance: true,
            p_comment_id: cid ? String(cid) : null
          });
          tc++;
          gen.push({
            companion: sl,
            article: art.title.substring(0, 60),
            preview: cm.substring(0, 120)
          });
          await notifySubscribers(sb, ci, sl, art, errs, pending);
        } catch (e) {
          errs.push(sl + "/" + art.title.substring(0, 40) + ": " + e.message);
        }
        await new Promise((r)=>setTimeout(r, 300));
      }
      for (const c of comps){
        if (!ms.includes(c.slug) && !trackedComps.has(c.id)) {
          await sb.rpc("insert_bot_tracking", {
            p_companion_id: c.id,
            p_article_id: art.id,
            p_relevance: false
          });
        }
      }
    }
    await flushCompanionPushes(sb, pending, errs);
    return R({
      articles_processed: arts.length,
      comments_generated: tc,
      articles_skipped: tsk,
      generated: gen,
      errors: errs.length ? errs : undefined
    });
  } catch (e) {
    return R({
      error: e.message,
      stack: e.stack
    }, 500);
  }
});
async function pick(ctx, key) {
  const sysMsg = "Pick which bots comment on this migraine article.\n\n" + BOT_PROFILES + "\n\nRULES:\n" + "Pick bots whose topic is meaningfully covered in the article — it does not need to be the sole focus, but should be more than a passing mention.\n" + "Recipes: Kai can comment if the recipe involves common trigger ingredients (cheese, chocolate, alcohol, citrus, processed meat, aged foods) OR if the recipe is positioned as migraine-friendly.\n" + "Research/treatment articles: Priya should comment on anything involving drugs, clinical trials, or treatment approaches.\n" + "Personal stories or general articles: pick bots whose topic is a meaningful theme in the story.\n" + "Gift guides, contest winners, pure fundraiser news with no health content: zero bots.\n" + "Aim for 1-3 bots per article. Only return [] if truly no bot has anything useful to add.\n" + "Return ONLY JSON array of slugs: [\"priya\"] or [\"luna\",\"nora\"] or []";
  const r = await fetch("https://api.openai.com/v1/chat/completions", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer " + key
    },
    body: JSON.stringify({
      model: "gpt-4o-mini",
      temperature: 0,
      max_tokens: 100,
      messages: [
        {
          role: "system",
          content: sysMsg
        },
        {
          role: "user",
          content: ctx
        }
      ]
    })
  });
  if (!r.ok) throw new Error("OpenAI " + r.status + ": " + (await r.text()).substring(0, 200));
  const d = await r.json();
  const c = d.choices?.[0]?.message?.content?.trim() || "[]";
  try {
    const p = JSON.parse(c.replace(/```json|```/g, "").trim());
    if (Array.isArray(p)) return p.filter((s)=>typeof s === "string" && VALID.includes(s));
  } catch (_e) {}
  return [];
}
async function genComment(slug, ctx, key) {
  const bp = PROMPTS[slug];
  if (!bp) return null;
  const sysMsg = bp + "\n\nWrite like the GOOD example. NEVER like the BAD. Include at least one specific fact, number, drug name, technique, or mechanism the article does not mention.\n\nAUDIENCE: You are talking to migraine patients in a community. Not doctors. Not professionals. Write as if talking to someone who lives with migraines and wants practical knowledge to help them navigate their own care.\n\n" + "BANNED: summarising the article, referencing the app or tracking or logging, " + "words crucial/significant/noteworthy/pertinent/highlights/emphasizes/vital/frequency and intensity, " + "dashes, generic statements, talking to or advising doctors or providers. Cannot add a specific new fact? Respond SKIP.";
  const r = await fetch("https://api.openai.com/v1/chat/completions", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer " + key
    },
    body: JSON.stringify({
      model: "gpt-4o-mini",
      temperature: 0.7,
      max_tokens: 250,
      messages: [
        {
          role: "system",
          content: sysMsg
        },
        {
          role: "user",
          content: ctx
        }
      ]
    })
  });
  if (!r.ok) throw new Error("OpenAI " + r.status + ": " + (await r.text()).substring(0, 200));
  const d = await r.json();
  const c = d.choices?.[0]?.message?.content?.trim();
  if (!c || c.toUpperCase().startsWith("SKIP")) return null;
  return c.replace(/^["']|["']$/g, "");
}
async function FC(url) {
  try {
    // ── SSRF protection: only fetch public HTTPS URLs ──
    let parsed;
    try {
      parsed = new URL(url);
    } catch  {
      return "";
    }
    if (parsed.protocol !== "https:") return "";
    const host = parsed.hostname.toLowerCase();
    if (host === "localhost" || host === "127.0.0.1" || host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.") || host.endsWith(".internal") || host.endsWith(".local") || host.includes("supabase.co") || host.includes("supabase.io")) return "";
    const r = await fetch(url, {
      headers: {
        "User-Agent": "MigraineMe/1.0",
        Accept: "text/html"
      },
      redirect: "follow"
    });
    if (!r.ok) return "";
    const h = await r.text();
    let t = h.replace(/<script[\s\S]*?<\/script>/gi, "").replace(/<style[\s\S]*?<\/style>/gi, "").replace(/<nav[\s\S]*?<\/nav>/gi, "").replace(/<header[\s\S]*?<\/header>/gi, "").replace(/<footer[\s\S]*?<\/footer>/gi, "").replace(/<aside[\s\S]*?<\/aside>/gi, "").replace(/<[^>]+>/g, " ").replace(/&nbsp;/g, " ").replace(/&amp;/g, "&").replace(/&#\d+;/g, " ").replace(/\s+/g, " ").trim();
    return t.length > 3000 ? t.substring(0, 3000) + "..." : t;
  } catch  {
    return "";
  }
}
// One push per user per run, gated by the "AI companion" switch. An absent
// notification_settings row means ON, matching the client default, so only an
// explicit enabled=false mutes a user. The in-app badge is never gated.
async function flushCompanionPushes(sb, pending, errs) {
  if (!pending.size) return;
  try {
    const userIds = Array.from(pending.keys());
    const { data: optOuts } = await sb.from("notification_settings").select("user_id").eq("type", "community").eq("enabled", false).in("user_id", userIds);
    const muted = new Set((optOuts ?? []).map((r)=>r.user_id));

    // Each recipient's display language, in one query rather than one per user.
    // A push is the only user-facing text the device cannot re-render — it sees
    // the finished string and nothing else — so this is the render boundary and
    // the text has to be built per person, in their language.
    //
    // The title is translated as a WHOLE sentence rather than assembled from a
    // translated "and" plus a translated "commented". Verb agreement moves with
    // the subject count ("hat kommentiert" vs "haben kommentiert", "reageerde"
    // vs "reageerden"), and a translator cannot fix that from fragments.
    const langs = await getLangs(sb, userIds);

    for (const [userId, e] of pending){
      if (muted.has(userId)) continue;
      const lang = langs.get(userId) ?? "en";
      const names = Array.from(e.names);
      const title = names.length === 1
        ? t(lang, "%1$s commented", names[0])
        : names.length === 2
          ? t(lang, "%1$s and %2$s commented", names[0], names[1])
          : t(lang, "%1$s and %2$s others commented", names[0], names.length - 1);
      // A single comment shows the article's own headline, which is third-party
      // text in whatever language it was published in. Nothing to translate.
      const body = e.count === 1
        ? e.firstTitle
        : t(lang, "%1$s new comments in your community feed", e.count);
      try {
        await sb.functions.invoke("send-fcm-push", {
          body: {
            type: "community_comment",
            user_ids: [
              userId
            ],
            notification: {
              title,
              body
            }
          }
        });
      } catch (e2) {
        errs.push(`push/${userId}: ${e2.message}`);
      }
    }
  } catch (e) {
    errs.push(`flushCompanionPushes: ${e.message}`);
  }
}
function R(b, s = 200) {
  return new Response(JSON.stringify(b), {
    status: s,
    headers: {
      "Content-Type": "application/json"
    }
  });
}
