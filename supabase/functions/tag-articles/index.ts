// supabase/functions/tag-articles/index.ts
// Tags pending articles using GPT-4o-mini. Loads relevant tags from DB.

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const OPENAI_API_KEY = Deno.env.get("OPENAI_API_KEY")!;

const RELEVANT_CATEGORIES = [
  "trigger", "medicine", "relief", "prodrome", "symptom",
];

async function callGPT(title: string, text: string, tagList: string): Promise<{
  summary: string;
  tags: Array<{ name: string; confidence: number }>;
  relevance: number;
} | null> {
  const content = text ? `${title}\n\n${text.substring(0, 4000)}` : title;

  try {
    const resp = await fetch("https://api.openai.com/v1/chat/completions", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${OPENAI_API_KEY}`,
      },
      body: JSON.stringify({
        model: "gpt-4o-mini",
        temperature: 0.3,
        max_tokens: 1024,
        messages: [
          {
            role: "system",
            content: `You tag migraine articles for a health app.

Given an article, output ONLY valid JSON:
{
  "summary": "2-3 sentence plain-language summary",
  "tags": [{"name": "exact tag name from the list", "confidence": 0.0-1.0}],
  "relevance": 1-10
}

Rules:
- ONLY use tag names from the list below. Copy them EXACTLY as written.
- Pick 3-10 tags that the article is genuinely about.
- relevance = how useful for migraine sufferers (1=not at all, 10=very)
- JSON only, no markdown, no explanation.

TAG LIST:
${tagList}`
          },
          { role: "user", content: content }
        ],
      }),
    });

    if (!resp.ok) {
      console.error(`OpenAI error: ${resp.status} ${await resp.text()}`);
      return null;
    }

    const data = await resp.json();
    const raw = data.choices?.[0]?.message?.content ?? "";
    return JSON.parse(raw.replace(/```json|```/g, "").trim());
  } catch (e: any) {
    console.error(`GPT error: ${e.message}`);
    return null;
  }
}

serve(async (req) => {
  try {
    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_KEY);
    const body = await req.json().catch(() => ({}));
    const limit = body.limit ?? 10;

    // Load relevant tags
    const { data: allTags, error: tagErr } = await supabase
      .from("tags")
      .select("id, name, category")
      .in("category", RELEVANT_CATEGORIES)
      .order("name");

    if (tagErr) throw tagErr;

    // Build lookup: exact name → id + case-insensitive fallback
    const tagMap: Record<string, string> = {};
    const tagMapLower: Record<string, { id: string; name: string }> = {};
    const tagNames: string[] = [];

    for (const t of allTags ?? []) {
      tagMap[t.name] = t.id;
      tagMapLower[t.name.toLowerCase()] = { id: t.id, name: t.name };
      tagNames.push(t.name);
    }

    // Flat list — no category headers to confuse the model
    const tagList = tagNames.join(", ");

    console.log(`Loaded ${tagNames.length} tags`);

    // Get pending articles
    const { data: pending, error: pendErr } = await supabase
      .from("articles")
      .select("id, title, full_text")
      .eq("status", "pending")
      .order("ingested_at", { ascending: true })
      .limit(limit);

    if (pendErr) throw pendErr;
    if (!pending || pending.length === 0) {
      return new Response(JSON.stringify({ message: "No pending articles", tagged: 0 }), {
        status: 200, headers: { "Content-Type": "application/json" },
      });
    }

    let tagged = 0;
    let rejected = 0;
    const errors: string[] = [];

    for (const article of pending) {
      console.log(`Tagging: ${article.title.substring(0, 60)}...`);

      const result = await callGPT(article.title, article.full_text ?? "", tagList);

      if (!result) {
        errors.push(article.title.substring(0, 40));
        continue;
      }

      // Match GPT tags — try exact first, then case-insensitive fallback
      const matchedTags: Array<{ id: string; name: string; confidence: number }> = [];
      const unmatchedNames: string[] = [];

      for (const t of result.tags) {
        if (tagMap[t.name]) {
          matchedTags.push({ id: tagMap[t.name], name: t.name, confidence: t.confidence });
        } else if (tagMapLower[t.name.toLowerCase()]) {
          const found = tagMapLower[t.name.toLowerCase()];
          matchedTags.push({ id: found.id, name: found.name, confidence: t.confidence });
        } else {
          unmatchedNames.push(t.name);
        }
      }

      console.log(`  GPT tags: ${result.tags.map(t => t.name).join(", ")}`);
      console.log(`  Matched: ${matchedTags.map(t => t.name).join(", ")} (${matchedTags.length}/${result.tags.length})`);
      if (unmatchedNames.length > 0) console.log(`  Unmatched: ${unmatchedNames.join(", ")}`);
      console.log(`  Relevance: ${result.relevance}`);

      // Publish if relevance >= 4 AND at least 1 matched tag
      const newStatus = (result.relevance >= 4 && matchedTags.length >= 1) ? "published" : "rejected";

      await supabase.from("articles").update({
        ai_summary: result.summary,
        relevance_score: result.relevance,
        status: newStatus,
      }).eq("id", article.id);

      if (newStatus === "published") {
        tagged++;

        const tagInserts = matchedTags.map(t => ({
          article_id: article.id,
          tag_id: t.id,
          confidence: t.confidence,
        }));

        if (tagInserts.length > 0) {
          await supabase.from("article_tags").upsert(tagInserts);
        }

        await supabase.from("article_stats").upsert({
          article_id: article.id,
          favorite_count: 0, comment_count: 0, view_count: 0,
        });
      } else {
        rejected++;
        console.log(`  REJECTED: relevance=${result.relevance}, matched=${matchedTags.length}`);
      }
    }

    return new Response(
      JSON.stringify({ processed: pending.length, tagged, rejected, errors: errors.length > 0 ? errors : undefined }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  } catch (e: any) {
    console.error("tag-articles error:", e);
    return new Response(JSON.stringify({ error: e.message }), { status: 500 });
  }
});