// app/src/main/java/com/migraineme/CommunityTranslations.kt
package com.migraineme

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Translation for Community CONTENT, as opposed to Community COPY.
 *
 * Strings.t() translates the words we wrote, keyed on the English string. The
 * Community feed is not that: ingested article headlines, AI-companion
 * comments, forum threads and blog posts are unbounded prose that arrives
 * every day, far too long and too changeable for a lookup table. Those are
 * translated once server-side (the translate-community edge function) into a
 * sibling table per source table, and read back here.
 *
 * The read is an embed rather than a second request, so a translated feed
 * still costs exactly one round trip:
 *
 *   articles?select=…,article_translations(title,ai_summary)
 *           &article_translations.lang=eq.de
 *
 * It is a LEFT join, so an article that has not been translated yet still
 * comes back and simply renders in English. That is the whole fallback story:
 * a new article is visible the moment it is ingested and quietly becomes
 * German within the quarter hour, rather than being hidden until it is ready.
 *
 * English asks for nothing extra. The query for an English user is byte for
 * byte the one that has always run.
 *
 * NOTE the direction of travel. This translates content on the way IN, for
 * display only. Nothing here may ever be written back: the rule in LangPrefs
 * still holds, canonical text stays English.
 */
object CommunityI18n {

    /** The language to fetch content in, or null when the user is on English. */
    fun lang(): String? = LangPrefs.get().code.takeIf { it != Lang.EN.code }

    /**
     * The fragment to append to a PostgREST `select`, or "" in English.
     * Always call [translationLang] with the same relation name.
     */
    fun embed(relation: String, columns: String): String =
        if (lang() != null) ",$relation($columns)" else ""
}

/** Restrict an embedded translation relation to the active language. */
fun HttpRequestBuilder.translationLang(relation: String) {
    CommunityI18n.lang()?.let { parameter("$relation.lang", "eq.$it") }
}

/**
 * One permissive row shape for every translation table.
 *
 * Each table supplies only its own columns (article_translations has
 * title + ai_summary, forum_comment_translations only body, and so on), and
 * the Json config already ignores unknown keys and omits nulls. A class per
 * table would be six near-identical files whose only job is to be deserialised
 * and immediately discarded.
 */
@Serializable
data class CommunityTranslationRow(
    val title: String? = null,
    val body: String? = null,
    val summary: String? = null,
    val excerpt: String? = null,
    val tag: String? = null,
    @SerialName("ai_summary") val aiSummary: String? = null,
    @SerialName("body_html") val bodyHtml: String? = null,
    val faq: List<BlogFaq>? = null,
    @SerialName("seo_description") val seoDescription: String? = null
)

/** The single translation row for the active language, if the server had one. */
fun List<CommunityTranslationRow>?.one(): CommunityTranslationRow? = this?.firstOrNull()

/** Prefer a translated value, fall back to the English source. */
fun String?.orEnglish(english: String): String = this?.takeIf { it.isNotBlank() } ?: english
