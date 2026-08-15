package com.migraineme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ActivityViewModel : PoolViewModel() {

    private val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)

    private val _pool = MutableStateFlow<List<SupabaseDbService.UserActivityRow>>(emptyList())
    val pool: StateFlow<List<SupabaseDbService.UserActivityRow>> = _pool

    private val _frequent = MutableStateFlow<List<SupabaseDbService.ActivityPrefRow>>(emptyList())
    val frequent: StateFlow<List<SupabaseDbService.ActivityPrefRow>> = _frequent

    /**
     * Activity suggestions (type lowercase) for the reference date they were
     * computed for. Days ago is 0=same day … 3=three days out.
     */
    private val _recent = MutableStateFlow(RecentPicks())
    val recent: StateFlow<RecentPicks> = _recent

    /** Bumped per load so a slow response can never overwrite a newer one. */
    private var recentLoadId = 0

    /** Clear cached recent activity data (call on wizard exit / draft clear). */
    fun clearRecent() {
        recentLoadId++
        _recent.value = RecentPicks()
    }

    fun loadAll(accessToken: String) {
        viewModelScope.launch {
            runCatching {
                _pool.value = db.getAllActivityPool(accessToken)
                _frequent.value = db.getActivityPrefs(accessToken).sortedBy { it.position }
            }.onFailure { it.printStackTrace() }
        }
    }

    fun loadRecent(accessToken: String, referenceDate: String? = null) {
        val loadId = ++recentLoadId
        // Drop the previous date's results up front. Until this load lands there
        // are no suggestions, which is the only honest answer while in flight.
        _recent.value = RecentPicks()
        viewModelScope.launch {
            // No migraine date set → don't suggest activities from any date.
            // Never fall back to today: that is how a backdated log ends up
            // carrying this week's entries.
            val refDate = referenceDate?.take(10)?.let {
                try { java.time.LocalDate.parse(it) } catch (_: Exception) { null }
            } ?: return@launch
            val picks = try {
                val rows = db.getRecentActivities(accessToken, daysBack = 3, referenceDate = refDate.toString())
                val daysMap = mutableMapOf<String, Int>()
                val isoMap = mutableMapOf<String, String>()
                for (row in rows) {
                    val type = row.activityType ?: continue
                    val dateStr = row.date ?: row.startAt?.substring(0, 10) ?: continue
                    val date = try {
                        java.time.LocalDate.parse(dateStr.substring(0, 10))
                    } catch (_: Exception) { continue }
                    val daysAgo = kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(date, refDate).toInt())
                    if (daysAgo in 0..3) {
                        val existing = daysMap[type]
                        if (existing == null || daysAgo < existing) {
                            daysMap[type] = daysAgo
                            isoMap[type] = row.startAt ?: (dateStr + "T00:00:00Z")
                        }
                    }
                }
                RecentPicks(refDate.toString(), daysMap, isoMap)
            } catch (e: Exception) {
                e.printStackTrace()
                RecentPicks()
            }
            if (loadId == recentLoadId) _recent.value = picks
        }
    }

    fun addNewToPool(accessToken: String, label: String, category: String? = null) {
        viewModelScope.launch {
            runCatching { db.upsertActivityToPool(accessToken, label, category); loadAll(accessToken) }
                .onFailure { reportError(it) }
        }
    }

    fun removeFromPool(accessToken: String, id: String) {
        viewModelScope.launch {
            runCatching { db.deleteActivityFromPool(accessToken, id); loadAll(accessToken) }
                .onFailure { it.printStackTrace() }
        }
    }

    fun addToFrequent(accessToken: String, id: String) {
        viewModelScope.launch {
            runCatching {
                val pos = (_frequent.value.maxOfOrNull { it.position } ?: -1) + 1
                db.insertActivityPref(accessToken, id, pos, "frequent"); loadAll(accessToken)
            }.onFailure { it.printStackTrace() }
        }
    }

    fun removeFromFrequent(accessToken: String, prefId: String) {
        viewModelScope.launch {
            runCatching { db.deleteActivityPref(accessToken, prefId); loadAll(accessToken) }
                .onFailure { it.printStackTrace() }
        }
    }

    fun setCategory(accessToken: String, id: String, category: String?) {
        viewModelScope.launch {
            runCatching { db.setActivityCategory(accessToken, id, category); loadAll(accessToken) }
                .onFailure { it.printStackTrace() }
        }
    }

    fun setAutomation(accessToken: String, id: String, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { db.setActivityAutomation(accessToken, id, enabled); loadAll(accessToken) }
                .onFailure { it.printStackTrace() }
        }
    }
}

