package com.migraineme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import java.util.Collections

/**
 * The translation boundary. English text is the lookup key, so a call site reads
 * as plain English and a missing translation degrades to that same English
 * rather than to a raw key or a crash. That fallback is what lets a language
 * ship partially translated and improve later, and it is why a new feature is
 * never blocked on translation.
 *
 * Two entry points, because not every display site is composable:
 *   t(…)     — @Composable. Subscribes to LangPrefs, so switching language
 *              recomposes every visible screen live.
 *   tSync(…) — plain function, for viewmodels, notification builders and
 *              anywhere outside composition. Reads the current value once.
 *
 * Never pass the result of either into a stored value. See the contract on
 * LangPrefs: translated text must not reach Supabase, DeterministicMapper, or
 * the insights engine.
 */
object Strings {

    private val TABLES: Map<Lang, Map<String, String>> = mapOf(
        Lang.DE to DE_STRINGS,
        Lang.ES to ES_STRINGS,
        Lang.NL to NL_STRINGS,
        Lang.FR to FR_STRINGS,
        Lang.IT to IT_STRINGS,
        Lang.PT to PT_STRINGS,
    )

    /**
     * English keys we were asked for but do not have a translation for, in the
     * language that was active at the time. Debug aid only: it is what turns
     * "how much is actually covered" into a number instead of an impression.
     * Never read on a UI path.
     */
    private val missing: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    fun missingKeys(): List<String> = synchronized(missing) { missing.sorted() }

    /**
     * A key may carry a disambiguating suffix after "##" when the same English
     * word means two different things: the body-map toggle's "Back##bodymap" is
     * the back of the head, not the "Back" that walks a wizard backwards, and
     * German wants "Hinten" for one and "Zurück" for the other. The suffix is
     * part of the key and never reaches the screen — English falls back to the
     * text in front of it.
     */
    private fun englishOf(en: String): String = en.substringBefore("##")

    fun resolve(lang: Lang, en: String): String {
        if (lang == Lang.EN) return englishOf(en)
        val hit = TABLES[lang]?.get(en)
        if (hit != null) return hit
        missing.add("${lang.code} $en")
        return englishOf(en)
    }

    /**
     * The reverse of [resolve], scoped to a known option list: given a string
     * that may have come back translated, return the English option it is a
     * translation of, or null if it is not one of them.
     *
     * This exists because the ai-setup parser is handed the user's story in
     * their own language and sometimes answers in it, returning "Nee" or
     * "Nein" where the schema says "Yes"/"No". Every consumer of those answers
     * compares against the English verbatim, so a translated value is not
     * wrong-but-usable, it is silently dropped: a Dutch user answering "Nee"
     * to tracks_cycle lost the whole menstruation subsystem and never saw the
     * follow-up questions. The prompt now tells the parser to answer in
     * English; this is the safety net for when it does not.
     *
     * SCOPED, not global. A table-wide reverse map has real collisions —
     * German "Mittel" is both "Drug" and "Moderate", Spanish "Ninguno" is both
     * "None" and "None of these". Restricted to the option list of the field
     * being parsed, those collisions cannot arise, because no single option
     * list contains two options that translate alike.
     *
     * Derived from the tables rather than a hand-written word list, so it
     * cannot drift out of step with them. Reads TABLES directly rather than
     * going through resolve(), so a lookup never pollutes the missing-key set.
     *
     * English is tried first: an option that is already correct must never be
     * re-interpreted as some other language's translation of a different one.
     *
     * Note the direction. This turns translated text back INTO the English the
     * rest of the app stores and matches on — it is the one place that is
     * allowed to consume translated text, and it exists precisely so that
     * translated text does not reach Supabase or DeterministicMapper. It does
     * not weaken the contract on LangPrefs; it enforces it.
     */
    fun canonicalEnglish(raw: String?, candidates: Collection<String>): String? {
        val needle = raw?.trim().orEmpty()
        if (needle.isEmpty()) return null
        for (c in candidates) if (englishOf(c).equals(needle, ignoreCase = true)) return c
        for (lang in Lang.entries) {
            val table = TABLES[lang] ?: continue
            for (c in candidates) {
                if (table[c]?.equals(needle, ignoreCase = true) == true) return c
            }
        }
        return null
    }

    /** Display translation, live across language changes. */
    @Composable
    fun t(en: String): String {
        val lang by LangPrefs.lang.collectAsState()
        return resolve(lang, en)
    }

    /**
     * Display translation for a string with runtime values. The English key
     * keeps its placeholders, so "Logged %s attacks" is what gets translated and
     * the arguments are substituted afterwards.
     *
     * Placeholder ORDER is not guaranteed to survive translation — several of
     * these languages want the number after the noun. Translations therefore use
     * positional forms (%1$s, %2$s) wherever a string takes more than one
     * argument, so a translator can reorder them freely.
     */
    @Composable
    fun t(en: String, vararg args: Any?): String {
        val lang by LangPrefs.lang.collectAsState()
        return format(resolve(lang, en), args)
    }

    /** Non-composable equivalent of t(). */
    fun tSync(en: String): String = resolve(LangPrefs.get(), en)

    /** Non-composable equivalent of t() with arguments. */
    fun tSync(en: String, vararg args: Any?): String =
        format(resolve(LangPrefs.get(), en), args)

    /**
     * A malformed placeholder in a translation must never take down a screen, so
     * a formatting failure falls back to the English source formatted with the
     * same arguments, and to the raw pattern if even that fails.
     */
    private fun format(pattern: String, args: Array<out Any?>): String =
        try {
            String.format(pattern, *args)
        } catch (e: Exception) {
            try {
                String.format(pattern.replace(Regex("%\\d+\\$"), "%"), *args)
            } catch (e2: Exception) {
                pattern
            }
        }
}

/** Import-free shorthand, so a call site reads Text(t("Save")) rather than Text(Strings.t("Save")). */
@Composable
fun t(en: String): String = Strings.t(en)

@Composable
fun t(en: String, vararg args: Any?): String = Strings.t(en, *args)

fun tSync(en: String): String = Strings.tSync(en)

fun tSync(en: String, vararg args: Any?): String = Strings.tSync(en, *args)

/**
 * The java.util.Locale for the active app language. Day and month names are
 * what locales are for — prefer date formatters built with this over
 * translation-table entries for "Mon"/"Tue"/….
 */
fun appLocale(): java.util.Locale = java.util.Locale.forLanguageTag(LangPrefs.get().code)

/** Composable variant: recomposes the caller when the language changes. */
@Composable
fun rememberAppLocale(): java.util.Locale {
    val lang by LangPrefs.lang.collectAsState()
    return java.util.Locale.forLanguageTag(lang.code)
}
