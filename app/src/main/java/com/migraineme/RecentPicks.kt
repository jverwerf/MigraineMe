package com.migraineme

/**
 * Recent-log suggestions computed for ONE specific reference date.
 *
 * The reference date travels with the maps on purpose. A wizard step may only
 * auto-select from these when [refDate] equals the draft's current start date,
 * which makes two whole classes of bug impossible:
 *
 *  - a load that is still in flight (the maps still hold the previous date's
 *    rows while a screen-side "loaded for" flag already says otherwise), and
 *  - leftovers from an earlier wizard run on a ViewModel that outlives the
 *    wizard (TriggerViewModel is activity-scoped).
 *
 * [refDate] is a plain `yyyy-MM-dd` day key. A null value means "no reference
 * date", which always means no suggestions — never "today".
 */
data class RecentPicks(
    val refDate: String? = null,
    val daysAgo: Map<String, Int> = emptyMap(),
    val startAts: Map<String, String> = emptyMap()
)

/** Same contract as [RecentPicks], for the forward-looking missed-activity suggestions. */
data class UpcomingPicks(
    val refDate: String? = null,
    val types: List<String> = emptyList(),
    val startAts: Map<String, String> = emptyMap()
)
