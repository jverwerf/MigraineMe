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

    /** Activities scheduled today through today+7 — used by the migraine
     *  wizard's MissedActivitiesStep to auto-suggest as missed. */
    private val _upcoming = MutableStateFlow<List<String>>(emptyList())
    val upcoming: StateFlow<List<String>> = _upcoming
    private val _upcomingStartAts = MutableStateFlow<Map<String, String>>(emptyMap())
    val upcomingStartAts: StateFlow<Map<String, String>> = _upcomingStartAts

    fun loadUpcoming(accessToken: String, referenceDate: String? = null) {
        viewModelScope.launch {
            try {
                val rows = db.getUpcomingActivities(accessToken, daysAhead = 7, referenceDate = referenceDate)
                val seen = linkedMapOf<String, String>()
                for (r in rows) {
                    val t = r.type ?: continue
                    val s = r.startAt ?: continue
                    if (!seen.containsKey(t)) seen[t] = s
                }
                _upcoming.value = seen.keys.toList()
                _upcomingStartAts.value = seen
            } catch (e: Exception) {
                e.printStackTrace()
                _upcoming.value = emptyList()
                _upcomingStartAts.value = emptyMap()
            }
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

