// The translation boundary for anything the BACKEND renders.
//
// Same contract as Strings.kt on the client, deliberately: English text is the
// lookup KEY, translation happens at the point of rendering, and a key with no
// translation falls back to the English that was passed in. That fallback is
// what makes a partially translated language safe to deploy, and it is why a
// new push or message is never blocked on translation.
//
// The rule that matters most here is the one that is easy to break server-side:
// a translated string must NEVER reach a value. Not a column, not a proposal
// label, not a row we insert for the app to read back later. Two reasons.
//
//   1. English is canonical. user_triggers.label, insight groupings and
//      DeterministicMapper all branch on the English text. A German label
//      written into the database does not match anything.
//   2. Language is a live preference. A row translated at INSERT time is frozen
//      in whatever language the user had that day; the same row rendered by the
//      client through Strings.t follows them when they switch. So anything
//      stored stays English and is translated when it is shown.
//
// That leaves exactly two things to translate here: a push notification, which
// the device cannot re-render because it only ever sees the finished text, and
// a message we hand straight back in an HTTP response for immediate display.
//
// Usage:
//
//   import { getLang, getLangs, t } from "../_shared/i18n.ts";
//
//   const lang = await getLang(supabase, userId);
//   const title = t(lang, "%1$s commented", name);

import { LANGS, TABLES, type Lang } from "./translations.ts";

export type { Lang };

/** Narrow an arbitrary string to a supported language, defaulting to English. */
export function toLang(code: unknown): Lang {
  const c = String(code ?? "").toLowerCase();
  return (LANGS as readonly string[]).includes(c) ? (c as Lang) : "en";
}

/**
 * Translate, substituting positional arguments.
 *
 * The English key keeps its placeholders — "%1$s commented" is what gets
 * translated — and the arguments go in afterwards, matching how Strings.kt does
 * it on the client. Placeholders are POSITIONAL because word order moves in
 * translation: several of these languages want the count after the noun, and a
 * translator has to be free to reorder without touching any code.
 *
 * An untranslated key renders its English source, which is why callers pass
 * real English rather than a symbolic key.
 */
export function t(lang: Lang, en: string, ...args: unknown[]): string {
  const pattern = (lang !== "en" && TABLES[lang]?.[en]) || en;
  if (args.length === 0) return pattern;
  return pattern.replace(/%(\d+)\$s/g, (whole, idx) => {
    const arg = args[Number(idx) - 1];
    return arg === undefined ? whole : String(arg);
  });
}

/**
 * One user's display language.
 *
 * Falls back to English on a missing row, a missing column or a failed query.
 * Rendering English is the correct outcome of every one of those: the language
 * is a display preference, and a push that does not go out because a preference
 * lookup failed is a far worse bug than a push in the wrong language.
 */
export async function getLang(supabase: any, userId: string): Promise<Lang> {
  try {
    const { data } = await supabase
      .from("profiles")
      .select("lang")
      .eq("user_id", userId)
      .maybeSingle();
    return toLang(data?.lang);
  } catch {
    return "en";
  }
}

/**
 * Languages for many users at once, as user_id → Lang.
 *
 * The batched senders (one push per user at the end of a run) would otherwise
 * do a round trip per recipient. Users absent from the result are absent from
 * the map; callers should read it with `map.get(id) ?? "en"`.
 */
export async function getLangs(
  supabase: any,
  userIds: string[],
): Promise<Map<string, Lang>> {
  const out = new Map<string, Lang>();
  if (userIds.length === 0) return out;
  try {
    const { data } = await supabase
      .from("profiles")
      .select("user_id, lang")
      .in("user_id", userIds);
    for (const row of data ?? []) out.set(row.user_id, toLang(row.lang));
  } catch {
    // Leave the map empty; every caller falls back to English.
  }
  return out;
}
