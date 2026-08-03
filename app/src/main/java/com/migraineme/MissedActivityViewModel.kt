package com.migraineme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MissedActivityViewModel : ViewModel() {

    private val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)

    private val _pool = MutableStateFlow<List<SupabaseDbService.UserMissedActivityRow>>(emptyList())
    val pool: StateFlow<List<SupabaseDbService.UserMissedActivityRow>> = _pool

    private val _frequent = MutableStateFlow<List<SupabaseDbService.MissedActivityPrefRow>>(emptyList())
    val frequent: StateFlow<List<SupabaseDbService.MissedActivityPrefRow>> = _frequent

    /** Activities scheduled on the migraine's start date through +7 days, tagged
     *  with the reference date they were computed for — used by the migraine
     *  wizard's MissedActivitiesStep to auto-suggest as missed. */
    private val _upcoming = MutableStateFlow(UpcomingPicks())
    val upcoming: StateFlow<UpcomingPicks> = _upcoming

    /** Bumped per load so a slow response can never overwrite a newer one. */
    private var upcomingLoadId = 0

    /** Clear cached upcoming data (call on wizard exit / draft clear). */
    fun clearUpcoming() {
        upcomingLoadId++
        _upcoming.value = UpcomingPicks()
    }

    fun loadUpcoming(accessToken: String, referenceDate: String? = null) {
        val loadId = ++upcomingLoadId
        _upcoming.value = UpcomingPicks()
        viewModelScope.launch {
            // No migraine date set → no suggestions. Never fall back to today.
            val refDate = referenceDate?.take(10)?.let {
                try { java.time.LocalDate.parse(it) } catch (_: Exception) { null }
            } ?: return@launch
            val picks = try {
                val rows = db.getUpcomingActivities(accessToken, daysAhead = 7, referenceDate = refDate.toString())
                val seen = linkedMapOf<String, String>()
                for (r in rows) {
                    val t = r.type ?: continue
                    val s = r.startAt ?: continue
                    if (!seen.containsKey(t)) seen[t] = s
                }
                UpcomingPicks(refDate.toString(), seen.keys.toList(), seen)
            } catch (e: Exception) {
                e.printStackTrace()
                UpcomingPicks()
            }
            if (loadId == upcomingLoadId) _upcoming.value = picks
        }
    }

    fun loadAll(accessToken: String) {
        viewModelScope.launch {
            runCatching {
                _pool.value = db.getAllMissedActivityPool(accessToken)
                _frequent.value = db.getMissedActivityPrefs(accessToken).sortedBy { it.position }
            }.onFailure { it.printStackTrace() }
        }
    }

    fun addNewToPool(accessToken: String, label: String, category: String? = null) {
        viewModelScope.launch {
            runCatching { db.upsertMissedActivityToPool(accessToken, label, category); loadAll(accessToken) }
                .onFailure { it.printStackTrace() }
        }
    }

    fun removeFromPool(accessToken: String, id: String) {
        viewModelScope.launch {
            runCatching { db.deleteMissedActivityFromPool(accessToken, id); loadAll(accessToken) }
                .onFailure { it.printStackTrace() }
        }
    }

    fun addToFrequent(accessToken: String, id: String) {
        viewModelScope.launch {
            runCatching {
                val pos = (_frequent.value.maxOfOrNull { it.position } ?: -1) + 1
                db.insertMissedActivityPref(accessToken, id, pos, "frequent"); loadAll(accessToken)
            }.onFailure { it.printStackTrace() }
        }
    }

    fun removeFromFrequent(accessToken: String, prefId: String) {
        viewModelScope.launch {
            runCatching { db.deleteMissedActivityPref(accessToken, prefId); loadAll(accessToken) }
                .onFailure { it.printStackTrace() }
        }
    }

    fun setCategory(accessToken: String, id: String, category: String?) {
        viewModelScope.launch {
            runCatching { db.setMissedActivityCategory(accessToken, id, category); loadAll(accessToken) }
                .onFailure { it.printStackTrace() }
        }
    }

    fun setAutomation(accessToken: String, id: String, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { db.setMissedActivityAutomation(accessToken, id, enabled); loadAll(accessToken) }
                .onFailure { it.printStackTrace() }
        }
    }
}

