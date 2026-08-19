package com.migraineme

/**
 * Reads a certainty answer that came back in the user's language and returns
 * the wire level it means, or null.
 *
 * WHY THIS EXISTS
 * The ai-setup parser is handed the user's story in their own language and is
 * told to answer in the English schema values. Usually it does. When it does
 * not, the answer is not wrong-but-usable, it is silently dropped: production
 * has `tracks_cycle = "Nee"` and `uses_contraception = "Nein"` on real
 * profiles, and those users lost the whole menstruation subsystem without
 * anything looking broken. The option lists got a safety net for that. The
 * certainty levels did not, so an answer of "Oft" or "Selten" still fell
 * through and left the question looking untouched.
 *
 * TWO ROUTES, DERIVED FIRST
 * [TABLE_WORDS] is derived: it names English words and lets [Strings
 * .canonicalEnglish] do the reverse lookup through the same six tables that
 * produced the translation, so it cannot drift out of step with them. That
 * covers the four chip labels the wizard actually draws (None / Low / Mild /
 * High) plus the frequency words the tables happen to carry as keys in their
 * own right ("Never", "Rarely", "Sometimes", "No"). A key absent from a table
 * simply does not match — the route degrades to "not recognised", never to a
 * wrong answer.
 *
 * [LOCALISED_WORDS] is hand-written, and only because it has to be. The wire
 * values (EVERY_TIME, OFTEN, ...) are identifiers, not display copy, so no
 * table contains them; "Often" and "Every time" are in no table either — those
 * keys were deleted when the chip row went back to four labels. iOS carries a
 * shorter table than this one and has none of the frequency keys at all, so
 * the hand-written list covers all five levels rather than just the two
 * missing here: the same list then behaves identically on all four platforms,
 * which is the point.
 *
 * SCOPE
 * Certainty only. A table-wide reverse lookup genuinely collides — German
 * "Mittel" is both "Drug" and "Moderate" — so nothing here is applied
 * globally. Both maps are read only from the certainty path in
 * [AiOnboardingParser], and no two entries in either map point at different
 * levels, so within this scope there is nothing to disambiguate.
 *
 * SAFETY
 * This can only ever repair a value, never change one. The caller checks the
 * exact wire enum first and comes here only when that failed, so a correct
 * answer never reaches this code. Anything still unrecognised returns null and
 * is discarded, exactly as before — an unreadable answer is dropped, not
 * guessed at. Matching is case- and whitespace-insensitive, matching the
 * normalisation the caller already did.
 *
 * Kept in step with iOS CertaintyWords.swift and the web lib/certaintyWords.ts
 * in MeSeries / VertigoMe. Change all four together.
 */
object CertaintyWords {

    private val NO = DeterministicMapper.Certainty.NO
    private val RARELY = DeterministicMapper.Certainty.RARELY
    private val SOMETIMES = DeterministicMapper.Certainty.SOMETIMES
    private val OFTEN = DeterministicMapper.Certainty.OFTEN
    private val EVERY_TIME = DeterministicMapper.Certainty.EVERY_TIME

    /** English words the six translation tables carry, resolved through them. */
    private val TABLE_WORDS: Map<String, DeterministicMapper.Certainty> = mapOf(
        "None" to NO,
        "No" to NO,
        "Never" to NO,
        "Low" to SOMETIMES,
        "Sometimes" to SOMETIMES,
        "Rarely" to RARELY,
        "Mild" to OFTEN,
        "High" to EVERY_TIME,
    )

    /**
     * The frequency vocabulary no table carries, in the six shipped languages.
     * Recognition only: adding a spelling here can rescue a level, it can never
     * re-point one, because every spelling under a level means that level in
     * every language it appears in.
     */
    private val LOCALISED_WORDS: Map<String, DeterministicMapper.Certainty> = buildMap {
        fun add(c: DeterministicMapper.Certainty, vararg words: String) {
            for (w in words) put(w.lowercase(), c)
        }
        // EVERY_TIME
        add(
            EVERY_TIME,
            "Every time", "Everytime", "Always",                       // en
            "Jedes Mal", "Jedesmal", "Immer",                          // de
            "Cada vez", "Todas las veces", "Siempre",                  // es
            "Elke keer", "Iedere keer", "Altijd",                      // nl
            "À chaque fois", "A chaque fois", "Chaque fois", "Toujours", // fr
            "Ogni volta", "Tutte le volte", "Sempre",                  // it
            "Todas as vezes", "De cada vez",                           // pt
        )
        // OFTEN
        add(
            OFTEN,
            "Often", "Frequently",                                     // en
            "Oft", "Häufig",                                           // de
            "A menudo", "Con frecuencia", "Frecuentemente",            // es
            "Vaak", "Dikwijls",                                        // nl
            "Souvent", "Fréquemment",                                  // fr
            "Spesso", "Frequentemente",                                // it
            "Muitas vezes", "Com frequência",                          // pt
        )
        // SOMETIMES
        add(
            SOMETIMES,
            "Sometimes", "Occasionally",                               // en
            "Manchmal", "Gelegentlich",                                // de
            "A veces", "Algunas veces",                                // es
            "Soms",                                                    // nl
            "Parfois", "Quelquefois",                                  // fr
            "A volte", "Talvolta",                                     // it
            "Às vezes", "As vezes", "Por vezes",                       // pt
        )
        // RARELY
        add(
            RARELY,
            "Rarely", "Seldom",                                        // en
            "Selten",                                                  // de
            "Rara vez", "Raras veces",                                 // es
            "Zelden",                                                  // nl
            "Rarement",                                                // fr
            "Raramente", "Di rado",                                    // it
            "Raras vezes",                                             // pt
        )
        // NO
        add(
            NO,
            "No", "Never", "None",                                     // en
            "Nie", "Niemals", "Nein",                                  // de
            "Nunca", "Jamás",                                          // es
            "Nooit", "Nee",                                            // nl
            "Jamais", "Non",                                           // fr
            "Mai",                                                     // it
            "Não", "Nao",                                              // pt
        )
    }

    /** Null for anything not recognised, so a hallucination is still discarded. */
    fun fromLocalisedWord(raw: String?): DeterministicMapper.Certainty? {
        val needle = raw?.trim().orEmpty()
        if (needle.isEmpty()) return null
        Strings.canonicalEnglish(needle, TABLE_WORDS.keys)?.let { return TABLE_WORDS[it] }
        return LOCALISED_WORDS[needle.lowercase()]
    }
}
