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

    /** Map of prodrome type → days ago (0=today, 1=yesterday, 2=two days, 3=three days). Lowest value wins if logged multiple times. */
    private val _recentDaysAgo = MutableStateFlow<Map<String, Int>>(emptyMap())
    val recentDaysAgo: StateFlow<Map<String, Int>> = _recentDaysAgo

    /** Map of prodrome type → most recent start_at ISO string. */
    private val _recentStartAts = MutableStateFlow<Map<String, String>>(emptyMap())
    val recentStartAts: StateFlow<Map<String, String>> = _recentStartAts

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
        viewModelScope.launch {
            try {
                val refDate = referenceDate?.let {
                    try { java.time.LocalDate.parse(it.substring(0, 10)) } catch (_: Exception) { null }
                } ?: java.time.LocalDate.now()
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
                _recentDaysAgo.value = map
                _recentStartAts.value = isoMap
            } catch (e: Exception) {
                e.printStackTrace()
                _recentDaysAgo.value = emptyMap()
                _recentStartAts.value = emptyMap()
            }
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
}

