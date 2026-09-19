package com.migraineme

/**
 * The exercise routines, embedded as code (source: vrt3d/mm3d/app_assets/catalogue.json).
 *
 * Every string here is the ENGLISH literal and doubles as the translation key:
 * display sites wrap it in t(…) at render (see Strings.kt). Nothing in this file
 * is ever translated in place or stored. Arguments are positional on purpose, so
 * the i18n codemod's `title =` / `text =` display anchors never match a literal
 * in this non-composable table and wrap it.
 *
 * Films live in public storage under "{lang}/{film}". Only English films exist
 * today; [filmUrl] takes the app language code so other languages light up when
 * they are uploaded, and the player falls back to "en" when a language file
 * fails to load.
 */
enum class ExerciseWhen { PREVENT, ATTACK }

class ExerciseSection(
    val heading: String,
    val paragraphs: List<String>
)

class ExerciseRoutine(
    /** Stable id, used in the route. Never shown. */
    val id: String,
    val title: String,
    val level: String,
    val minutes: Int,
    val whenToUse: ExerciseWhen,
    val props: String,
    val film: String,
    val poster: String,
    val sections: List<ExerciseSection>
)

object ExerciseCatalogue {
    const val BASE_URL = "https://qykflarpibofvffmzghi.supabase.co/storage/v1/object/public/exercises"
    const val FALLBACK_LANG = "en"

    /** "{base}/{lang}/{film}" */
    fun filmUrl(routine: ExerciseRoutine, langCode: String): String =
        "$BASE_URL/${langCode.lowercase()}/${routine.film}"

    /** "{base}/posters/{poster}" */
    fun posterUrl(routine: ExerciseRoutine): String =
        "$BASE_URL/posters/${routine.poster}"

    fun byId(id: String?): ExerciseRoutine? = ROUTINES.firstOrNull { it.id == id }

    fun forWhen(whenToUse: ExerciseWhen): List<ExerciseRoutine> =
        ROUTINES.filter { it.whenToUse == whenToUse }

    val ROUTINES: List<ExerciseRoutine> = listOf(
        ExerciseRoutine(
            "neck",
            "Neck reset",
            "Easy",
            6,
            ExerciseWhen.PREVENT,
            "A bed edge or a chair",
            "neck.mp4",
            "neck.jpg",
            listOf(
                ExerciseSection(
                    "Why this helps",
                    listOf(
                        "The muscles at the top of your neck and the nerves that carry migraine pain meet in the same part of the brainstem. When those muscles are tight and sore, the brain can read it as head pain, and a neck that is already irritated makes an attack easier to set off.",
                        "Gentle movement through the full range, held for a few breaths, eases that stiffness and reminds the muscles how to let go. It is the simplest thing in the set and the one to do most often."
                    )
                ),
                ExerciseSection(
                    "What to expect",
                    listOf(
                        "Most people feel looser straight away. The effect on attacks comes from doing it daily for a few weeks, ideally at the same time, so it becomes a habit rather than a rescue."
                    )
                ),
                ExerciseSection(
                    "When to do it",
                    listOf(
                        "Any day, including the start of an attack if moving does not make the pain worse. A break from the desk is the natural moment.",
                        "Go gently. Move only as far as feels easy, never into pain. Check with a clinician first if your neck pain started after an injury, or comes with dizziness or tingling in your arms."
                    )
                )
            )
        ),
        ExerciseRoutine(
            "physio",
            "Neck stretches",
            "Medium",
            8,
            ExerciseWhen.PREVENT,
            "A bed edge or a chair",
            "physio.mp4",
            "physio.jpg",
            listOf(
                ExerciseSection(
                    "Why this helps",
                    listOf(
                        "Two muscles run from the neck to the shoulder blade and take the strain when you sit forward over a screen: one down the side of the neck, one across the back corner. When they are tight and tender, that tenderness feeds into the same brainstem nerve cells that carry migraine pain, and the head feels it.",
                        "These are the two stretches a physiotherapist gives for exactly that. The hand is there to add a little weight, never to pull. Held long enough, the muscle lets go rather than fighting back."
                    )
                ),
                ExerciseSection(
                    "What to expect",
                    listOf(
                        "A clear pull along the side or back of the neck, easing as you hold. Most days for a few weeks is where the benefit comes from. Never stretch into sharp pain."
                    )
                ),
                ExerciseSection(
                    "When to do it",
                    listOf(
                        "On good days, or when the neck feels tight after a long stretch at the desk. Not during an attack if moving the head makes the pain worse.",
                        "Go gently. Let the hand rest, not pull. Check with a clinician first if your neck pain started after an injury, or comes with dizziness or tingling in your arms."
                    )
                )
            )
        ),
        ExerciseRoutine(
            "posture",
            "Posture and deep neck",
            "Hard",
            8,
            ExerciseWhen.PREVENT,
            "A clear wall, a bed or a mat, a tennis ball",
            "posture.mp4",
            "posture.jpg",
            listOf(
                ExerciseSection(
                    "Why this helps",
                    listOf(
                        "A day at a desk leaves the chest tight and the shoulders rounded forward. Your head follows them, and once it sits forward of your shoulders it hangs off the muscles at the back of your neck.",
                        "Those muscles share a junction in the brainstem with the nerves that carry migraine pain, so their ache gets felt in your head. The small muscles at the front of the neck, whose job is to hold the head up, test weak in people whose headaches come with neck pain.",
                        "The wall part opens the chest and gives you something honest to measure against. If your elbows and wrists cannot stay near it, that is the tightness. The bed part trains the small front muscles: lifting your head a finger's width with the chin tucked works them without letting the big ones cheat. Of everything in these routines, that lift has the best trial results behind it."
                    )
                ),
                ExerciseSection(
                    "What to expect",
                    listOf(
                        "Strength takes weeks, not days. Two or three times a week is plenty. The wall part should feel like opening something up, and a few good lifts beat a lot of sloppy ones."
                    )
                ),
                ExerciseSection(
                    "When to do it",
                    listOf(
                        "On good days only. You need a clear bit of wall, a bed or a mat, and a tennis ball for the release at the end.",
                        "Go gently. Let your arms slide only as far as they go without your back arching off the wall. If your neck shakes or your chin pokes forward, the lift is too high. Lower it and do fewer. Check with a clinician first if your neck pain started after an injury, or comes with dizziness or tingling in your arms."
                    )
                )
            )
        ),
        ExerciseRoutine(
            "relax",
            "Relaxation",
            "Easy",
            3,
            ExerciseWhen.PREVENT,
            "A bed or a mat",
            "relax.mp4",
            "relax.jpg",
            listOf(
                ExerciseSection(
                    "Why this helps",
                    listOf(
                        "Muscles that stay a little tense all day stop feeling tense. You only notice them when the ache arrives. Squeezing a muscle hard and then letting it go teaches you what the difference feels like, and it is easier to release something once you can feel it.",
                        "Relaxation training of this kind has been used against migraine for decades. In trials it reduces how often attacks come by roughly the same amount as some preventive tablets, when it is practised regularly."
                    )
                ),
                ExerciseSection(
                    "What to expect",
                    listOf(
                        "The first few times feel like a lot of effort for nothing. The benefit comes from doing it most days for a few weeks, ideally at the same time, so the body learns the release."
                    )
                ),
                ExerciseSection(
                    "When to do it",
                    listOf(
                        "Any day. It suits the evening, and it is a reasonable thing to do when you feel an attack building, as long as tensing does not make the pain worse.",
                        "Go gently. Tense firmly, never to the point of cramping or shaking. Skip a body part that hurts."
                    )
                )
            )
        ),
        ExerciseRoutine(
            "breathing",
            "Slow breathing",
            "Easy",
            6,
            ExerciseWhen.ATTACK,
            "A bed and a dim room",
            "breathing.mp4",
            "breathing.jpg",
            listOf(
                ExerciseSection(
                    "Why this helps",
                    listOf(
                        "During an attack the body is in alarm mode: fast shallow breathing, tight shoulders, a racing pulse. Slowing the breath to about six a minute is the quickest lever you have on that. It nudges the nervous system toward its calm setting, and the pain is easier to sit with.",
                        "The hand on the belly is there so you can feel that the breath is going low and slow, not high into the chest."
                    )
                ),
                ExerciseSection(
                    "What to expect",
                    listOf(
                        "It will not stop an attack. It can take the edge off, and it gives you something to do while your medicine works. Most people feel calmer within a minute or two."
                    )
                ),
                ExerciseSection(
                    "When to do it",
                    listOf(
                        "At the first sign of an attack, and whenever the pain is at its worst. Dark room, eyes closed, sound low.",
                        "Go gently. If slow breathing makes you feel light-headed, breathe at whatever pace is comfortable instead."
                    )
                )
            )
        ),
        ExerciseRoutine(
            "liedown",
            "Lie-down release",
            "Easy",
            4,
            ExerciseWhen.ATTACK,
            "A bed, a cold pack and a dim room",
            "liedown.mp4",
            "liedown.jpg",
            listOf(
                ExerciseSection(
                    "Why this helps",
                    listOf(
                        "During an attack the neck and shoulders brace without you asking them to, the jaw clenches and the eyes screw up against the light. All of that feeds back into the same nerve centre that is already firing, and keeps it going.",
                        "Cold at the back of the neck calms the nerves that run from there up into the head. Letting each of those places go, one at a time, takes some of the fuel away from the attack."
                    )
                ),
                ExerciseSection(
                    "What to expect",
                    listOf(
                        "Not a cure. A little less pressure, a little less noise. Do it alongside your medicine, not instead of it."
                    )
                ),
                ExerciseSection(
                    "When to do it",
                    listOf(
                        "Once an attack has started. A dim, quiet room, sound low, and a cold pack wrapped in a cloth.",
                        "Go gently. Everything here is small. If the chin nod or the shoulder lift makes the pain worse, leave it out and just lie with the cold."
                    )
                )
            )
        )
    )
}
