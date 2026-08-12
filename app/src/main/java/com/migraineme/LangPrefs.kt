package com.migraineme

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Display-language preference. DISPLAY ONLY — exactly like UnitsPrefs, which
 * keeps canonical data metric and converts at the formatting boundary, this
 * keeps every canonical value in ENGLISH and translates at the display
 * boundary (see Strings.t / Strings.tSync).
 *
 * CRITICAL: English is not merely the default language, it is the KEY. Trigger,
 * symptom and relief names are written to Supabase in English, DeterministicMapper
 * branches on the English text ("Skipping meals", "Red wine", …), and the
 * insights engine groups on it. A translated string must therefore NEVER reach a
 * value: not a DB write, not a map key, not an analytics event. Translate at the
 * point of rendering and nowhere else. The upside is that a user who switches
 * language keeps their entire history intact, because nothing about their data
 * changed.
 *
 * Defaulted from the device language on first read, then persisted. Unlike
 * Android's own per-app locale, the StateFlow means a language change recomposes
 * live — no activity recreate, no relaunch.
 */
enum class Lang(val code: String, val endonym: String, val flag: String) {
    EN("en", "English", "\uD83C\uDDEC\uD83C\uDDE7"),
    DE("de", "Deutsch", "\uD83C\uDDE9\uD83C\uDDEA"),
    ES("es", "Español", "\uD83C\uDDEA\uD83C\uDDF8"),
    NL("nl", "Nederlands", "\uD83C\uDDF3\uD83C\uDDF1"),
    FR("fr", "Français", "\uD83C\uDDEB\uD83C\uDDF7"),
    IT("it", "Italiano", "\uD83C\uDDEE\uD83C\uDDF9"),
    PT("pt", "Português", "\uD83C\uDDF5\uD83C\uDDF9");

    companion object {
        fun fromCode(code: String?): Lang? =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

object LangPrefs {
    private const val PREFS = "lang_prefs"
    private const val KEY_LANG = "display_lang"

    @Volatile private var appContext: Context? = null

    /**
     * English, always, until the user picks otherwise.
     *
     * Deliberately NOT the device language. Following the device would put a
     * German phone straight into German, which sounds helpful but means the
     * app's language changes based on a setting the user never associated with
     * us — and every screenshot, support request and store listing then has to
     * account for a starting state we did not choose. English is the written
     * source of truth here (it is also the translation key), so it is the one
     * state we can reason about. The picker sits on the sign-in screen for
     * anyone who wants something else.
     */
    private fun deviceDefault(): Lang = Lang.EN

    // Seeded from the device default; replaced by the persisted value once
    // init() runs at app start. tSync() reads .value synchronously.
    private val _lang = MutableStateFlow(deviceDefault())
    val lang: StateFlow<Lang> = _lang.asStateFlow()

    /** Hydrate from storage. Call once at app start (MigraineMeApp). */
    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _lang.value = Lang.fromCode(p.getString(KEY_LANG, null)) ?: deviceDefault()
    }

    /** Current language, for use outside composition. */
    fun get(): Lang = _lang.value

    /**
     * True once the user has actively chosen, as opposed to us guessing from the
     * device. The onboarding picker runs before an account exists, so this is how
     * we know whether a later profile sync should overwrite the local choice.
     */
    fun hasExplicitChoice(): Boolean =
        try {
            appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.getString(KEY_LANG, null) != null
        } catch (e: Exception) {
            false
        }

    fun set(next: Lang) {
        _lang.value = next
        try {
            appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
                ?.putString(KEY_LANG, next.code)?.apply()
        } catch (e: Exception) {
            // Non-fatal — the in-memory flow still reflects the choice this session.
        }
        pushToProfile()
    }

    /**
     * Mirror the choice onto profiles.lang, for the text the app never gets to
     * render: push notification titles and bodies, and the messages edge
     * functions hand back. Everything the app draws itself is already handled
     * by Strings, on device, with no server involved.
     *
     * Fire-and-forget on IO, following MigraineMeFirebaseService.onNewToken —
     * the same shape of problem (a device-local value the backend needs) solved
     * the same way, so there is one pattern rather than two. Nothing waits on
     * it: the language has already changed on screen by the time this starts,
     * and a failed write costs an English push, not a wrong UI.
     *
     * Signed out, this is a no-op. The picker is on the sign-in screen, so the
     * common case is a choice made before an account exists; syncLang() finds
     * no session and returns, and the sign-in paths push the stored choice once
     * a session exists. That is what hasExplicitChoice() gates.
     */
    private fun pushToProfile() {
        val ctx = appContext ?: return
        CoroutineScope(Dispatchers.IO).launch {
            SupabaseProfileService.syncLang(ctx)
        }
    }

    /**
     * Push the stored choice to the server once a session exists.
     *
     * Called after sign-in and sign-up. Gated on hasExplicitChoice() so we only
     * ever write a language the user actually picked: an untouched install sits
     * on the English default, and writing that would overwrite a choice they
     * made on another device with a value they never chose.
     */
    suspend fun syncAfterSignIn(context: Context) {
        if (!hasExplicitChoice()) return
        SupabaseProfileService.syncLang(context)
    }
}
