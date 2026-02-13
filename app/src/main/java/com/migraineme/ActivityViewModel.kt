package com.migraineme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ActivityViewModel : ViewModel() {

    private val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)

    private val _pool = MutableStateFlow<List<SupabaseDbService.UserActivityRow>>(emptyList())
    val pool: StateFlow<List<SupabaseDbService.UserActivityRow>> = _pool

    private val _frequent = MutableStateFlow<List<SupabaseDbService.ActivityPrefRow>>(emptyList())
    val frequent: StateFlow<List<SupabaseDbService.ActivityPrefRow>> = _frequent

    /** Map of activity type (lowercase) → days ago. */
    private val _recentDaysAgo = MutableStateFlow<Map<String, Int>>(emptyMap())
    val recentDaysAgo: StateFlow<Map<String, Int>> = _recentDaysAgo

    /** Map of activity type (lowercase) → most recent start_at ISO. */
    private val _recentStartAts = MutableStateFlow<Map<String, String>>(emptyMap())
    val recentStartAts: StateFlow<Map<String, String>> = _recentStartAts

    fun loadAll(accessToken: String) {
        viewModelScope.launch {
            runCatching {
                _pool.value = db.getAllActivityPool(accessToken)
                _frequent.value = db.getActivityPrefs(accessToken).sortedBy { it.position }
            }.onFailure { it.printStackTrace() }
        }
    }

    fun loadRecent(accessToken: String, referenceDate: String? = null) {
        viewModelScope.launch {
            try {
                val refDate = referenceDate?.let {
                    try { java.time.LocalDate.parse(it.substring(0, 10)) } catch (_: Exception) { null }
                } ?: java.time.LocalDate.now()
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
                _recentDaysAgo.value = daysMap
                _recentStartAts.value = isoMap
            } catch (e: Exception) {
                e.printStackTrace()
                _recentDaysAgo.value = emptyMap()
                _recentStartAts.value = emptyMap()
            }
        }
    }

    fun addNewToPool(accessToken: String, label: String, category: String? = null) {
        viewModelScope.launch {
            runCatching { db.upsertActivityToPool(accessToken, label, category); loadAll(accessToken) }
                .onFailure { it.printStackTrace() }
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

