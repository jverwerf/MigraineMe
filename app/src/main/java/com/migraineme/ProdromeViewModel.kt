package com.migraineme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ProdromeViewModel : ViewModel() {

    private val db = SupabaseDbService(
        BuildConfig.SUPABASE_URL,
        BuildConfig.SUPABASE_ANON_KEY
    )

    private val _pool = MutableStateFlow<List<SupabaseDbService.UserProdromeRow>>(emptyList())
    val pool: StateFlow<List<SupabaseDbService.UserProdromeRow>> = _pool

    private val _frequent = MutableStateFlow<List<SupabaseDbService.ProdromePrefRow>>(emptyList())
    val frequent: StateFlow<List<SupabaseDbService.ProdromePrefRow>> = _frequent

    /**
     * Prodrome suggestions for the reference date they were computed for.
     * Days ago is 0=same day … 3=three days out; lowest value wins per type.
     */
    private val _recent = MutableStateFlow(RecentPicks())
    val recent: StateFlow<RecentPicks> = _recent

    /** Bumped per load so a slow response can never overwrite a newer one. */
    private var recentLoadId = 0

    /** Clear cached recent prodrome data (call on wizard exit / draft clear). */
    fun clearRecent() {
        recentLoadId++
        _recent.value = RecentPicks()
    }

    private fun safeSortPrefs(prefs: List<SupabaseDbService.ProdromePrefRow>) =
        prefs.sortedBy { it.position }

    fun loadAll(accessToken: String) {
        viewModelScope.launch {
            try {
                val p = db.getAllProdromePool(accessToken)
                val prefs = db.getProdromePrefs(accessToken)
                _pool.value = p
                _frequent.value = safeSortPrefs(prefs.filter { it.status == "frequent" })
            } catch (e: Exception) {
                e.printStackTrace()
                _pool.value = emptyList()
                _frequent.value = emptyList()
            }
        }
    }

    fun loadRecent(accessToken: String, referenceDate: String? = null) {
        val loadId = ++recentLoadId
        // Drop the previous date's results up front. Until this load lands there
        // are no suggestions, which is the only honest answer while in flight.
        _recent.value = RecentPicks()
        viewModelScope.launch {
            // No migraine date set → don't suggest prodromes from any date.
            // Never fall back to today: that is how a backdated log ends up
            // carrying this week's entries.
            val refDate = referenceDate?.take(10)?.let {
                try { java.time.LocalDate.parse(it) } catch (_: Exception) { null }
            } ?: return@launch
            val picks = try {
                val rows = db.getRecentProdromes(accessToken, daysBack = 3, referenceDate = refDate.toString())
                val map = mutableMapOf<String, Int>()
                val isoMap = mutableMapOf<String, String>()
                for (row in rows) {
                    val type = row.type ?: continue
                    val startAt = row.startAt ?: continue
                    val date = try {
                        java.time.LocalDate.parse(startAt.substring(0, 10))
                    } catch (_: Exception) { continue }
                    val daysAgo = kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(date, refDate).toInt())
                    if (daysAgo in 0..3) {
                        val existing = map[type]
                        if (existing == null || daysAgo < existing) {
                            map[type] = daysAgo
                            isoMap[type] = startAt
                        }
                    }
                }
                RecentPicks(refDate.toString(), map, isoMap)
            } catch (e: Exception) {
                e.printStackTrace()
                RecentPicks()
            }
            if (loadId == recentLoadId) _recent.value = picks
        }
    }

    fun addNewToPool(accessToken: String, label: String, category: String? = null, predictionValue: String? = "NONE") {
        viewModelScope.launch {
            try {
                db.upsertProdromeToPool(accessToken, label.trim(), category, predictionValue)
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeFromPool(accessToken: String, prodromeId: String) {
        viewModelScope.launch {
            try {
                db.deleteProdromeFromPool(accessToken, prodromeId)
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addToFrequent(accessToken: String, prodromeId: String) {
        viewModelScope.launch {
            try {
                val pos = _frequent.value.size
                db.insertProdromePref(accessToken, prodromeId, pos, status = "frequent")
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeFromFrequent(accessToken: String, prefId: String) {
        viewModelScope.launch {
            try {
                db.deleteProdromePref(accessToken, prefId)
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setPrediction(accessToken: String, prodromeId: String, value: String) {
        viewModelScope.launch {
            try {
                db.updateProdromePoolItem(accessToken, prodromeId, predictionValue = value)
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setCategory(accessToken: String, prodromeId: String, category: String?) {
        viewModelScope.launch {
            try {
                db.updateProdromePoolItem(accessToken, prodromeId, category = category)
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setAlertEnabled(accessToken: String, prodromeId: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                db.updateProdromePoolItem(accessToken, prodromeId, alertEnabled = enabled)
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

