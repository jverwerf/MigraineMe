// supabase/functions/ingest-articles/index.ts
// Cron: runs daily (or manually triggered)
// Fetches RSS feeds, inserts new articles

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

// ── Simple RSS XML parser ──
function parseRSS(xml: string): Array<{
  title: string;
  url: string;
  published: string | null;
  description: string | null;
  imageUrl: string | null;
}> {
  const items: Array<any> = [];

  // Handle both RSS 2.0 (<item>) and Atom (<entry>)
  const itemRegex = /<item[\s>]([\s\S]*?)<\/item>|<entry[\s>]([\s\S]*?)<\/entry>/gi;
  let match;

  while ((match = itemRegex.exec(xml)) !== null) {
    const block = match[1] || match[2];

    const title = extractTag(block, "title");
    const link = extractTag(block, "link") || extractAttr(block, "link", "href");
    const pubDate = extractTag(block, "pubDate") || extractTag(block, "published") || extractTag(block, "dc:date");
    const description = extractTag(block, "description") || extractTag(block, "summary") || extractTag(block, "content:encoded");
    const imageUrl = extractAttr(block, "media:content", "url") || extractAttr(block, "enclosure", "url") || extractImageFromContent(description);

    if (title && link) {
      items.push({
        title: stripHtml(title).trim(),
        url: link.trim(),
        published: pubDate ? new Date(pubDate).toISOString() : null,
        description: description ? stripHtml(description).substring(0, 2000) : null,
        imageUrl,
      });
    }
  }

  return items;
}

function extractTag(xml: string, tag: string): string | null {
  // Handle CDATA
  const cdataRegex = new RegExp(`<${tag}[^>]*><!\\[CDATA\\[([\\s\\S]*?)\\]\\]></${tag}>`, "i");
  const cdataMatch = cdataRegex.exec(xml);
  if (cdataMatch) return cdataMatch[1];

  const regex = new RegExp(`<${tag}[^>]*>([\\s\\S]*?)</${tag}>`, "i");
  const match = regex.exec(xml);
  return match ? match[1] : null;
}

function extractAttr(xml: string, tag: string, attr: string): string | null {
  const regex = new RegExp(`<${tag}[^>]*${attr}="([^"]*)"`, "i");
  const match = regex.exec(xml);
  return match ? match[1] : null;
}

function extractImageFromContent(html: string | null): string | null {
  if (!html) return null;
  const match = /<img[^>]+src="([^"]+)"/i.exec(html);
  return match ? match[1] : null;
}

function stripHtml(html: string): string {
  return html.replace(/<[^>]*>/g, "").replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&nbsp;/g, " ");
}

serve(async (req) => {
  try {
    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_KEY);

    // Get active feeds
    const { data: feeds, error: feedErr } = await supabase
      .from("rss_feeds")
      .select("*")
      .eq("active", true);

    if (feedErr) throw feedErr;
    if (!feeds || feeds.length === 0) {
      return new Response(JSON.stringify({ message: "No active feeds" }), { status: 200 });
    }

    let totalIngested = 0;
    const errors: string[] = [];

    for (const feed of feeds) {
      try {
        console.log(`Fetching feed: ${feed.name} (${feed.url})`);

        const resp = await fetch(feed.url, {
          headers: { "User-Agent": "MigraineMe/1.0 RSS Reader" },
        });

        if (!resp.ok) {
          errors.push(`${feed.name}: HTTP ${resp.status}`);
          continue;
        }

        const xml = await resp.text();
        const items = parseRSS(xml);
        console.log(`  Parsed ${items.length} items from ${feed.name}`);

        for (const item of items) {
          // Skip if URL already exists
          const { data: existing } = await supabase
            .from("articles")
            .select("id")
            .eq("url", item.url)
            .limit(1)
            .maybeSingle();

          if (existing) continue;

          // Scrape full article text
          let fullText = item.description ?? "";
          try {
            const pageResp = await fetch(item.url, {
              headers: { "User-Agent": "MigraineMe/1.0 Article Reader" },
              redirect: "follow",
            });
            if (pageResp.ok) {
              const html = await pageResp.text();
              // Extract text from article body — strip all HTML tags
              // Try to find article/main content first
              const articleMatch = html.match(/<article[^>]*>([\s\S]*?)<\/article>/i)
                || html.match(/<main[^>]*>([\s\S]*?)<\/main>/i)
                || html.match(/<div[^>]*class="[^"]*(?:content|entry|post|article)[^"]*"[^>]*>([\s\S]*?)<\/div>/i);
              
              const rawHtml = articleMatch ? articleMatch[1] : html;
              
              // Strip scripts, styles, then all tags
              const cleaned = rawHtml
                .replace(/<script[\s\S]*?<\/script>/gi, "")
                .replace(/<style[\s\S]*?<\/style>/gi, "")
                .replace(/<nav[\s\S]*?<\/nav>/gi, "")
                .replace(/<header[\s\S]*?<\/header>/gi, "")
                .replace(/<footer[\s\S]*?<\/footer>/gi, "")
                .replace(/<[^>]*>/g, " ")
                .replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">")
                .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&nbsp;/g, " ")
                .replace(/\s+/g, " ")
                .trim();

              // Keep first 6000 chars (tag-articles will cap at 4000 for the AI call)
              if (cleaned.length > 100) {
                fullText = cleaned.substring(0, 6000);
                console.log(`    Scraped ${cleaned.length} chars from ${item.url}`);
              }
            }
          } catch (scrapeErr: any) {
            console.log(`    Scrape failed for ${item.url}: ${scrapeErr.message}`);
            // Fall back to RSS description — that's fine
          }

          // Insert new article
          const { error: insertErr } = await supabase.from("articles").insert({
            title: item.title,
            url: item.url,
            image_url: item.imageUrl,
            source: feed.source_key,
            source_feed: feed.url,
            published_at: item.published,
            status: "pending",
            full_text: fullText,
            metadata: {
              feed_name: feed.name,
            },
          });

          if (insertErr) {
            // Likely duplicate URL race condition — skip
            if (insertErr.code === "23505") continue;
            console.error(`  Insert error: ${insertErr.message}`);
          } else {
            totalIngested++;
          }
        }

        // Update last_fetched_at
        await supabase
          .from("rss_feeds")
          .update({ last_fetched_at: new Date().toISOString() })
          .eq("id", feed.id);

      } catch (e: any) {
        errors.push(`${feed.name}: ${e.message}`);
        console.error(`  Feed error: ${e.message}`);
      }
    }

    return new Response(
      JSON.stringify({
        ingested: totalIngested,
        feeds_processed: feeds.length,
        errors: errors.length > 0 ? errors : undefined,
      }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    );
  } catch (e: any) {
    console.error("ingest-articles error:", e);
    return new Response(JSON.stringify({ error: e.message }), { status: 500 });
  }
});