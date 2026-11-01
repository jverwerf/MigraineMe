package com.migraineme

/**
 * Hand-off from a full-screen Monitor graph to the forum composer.
 * "Share to forum" stores which graph + range was on screen, navigates to
 * Community, and CommunityScreen takes it on entry to open New discussion
 * with that graph already in the picker.
 */
object ForumShareDraft {
    private var graph: String? = null
    private var days: Int = 14

    fun set(graph: String, days: Int) { this.graph = graph; this.days = days }

    /** Returns the pending draft once, then clears it. */
    fun take(): Pair<String, Int>? {
        val g = graph ?: return null
        graph = null
        return g to days
    }
}
