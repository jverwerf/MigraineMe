package com.migraineme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ReliefViewModel : ViewModel() {

    private val db = SupabaseDbService(
        BuildConfig.SUPABASE_URL,
        BuildConfig.SUPABASE_ANON_KEY
    )

    private val _pool = MutableStateFlow<List<SupabaseDbService.AllReliefRow>>(emptyList())
    val pool: StateFlow<List<SupabaseDbService.AllReliefRow>> = _pool

    private val _frequent = MutableStateFlow<List<SupabaseDbService.ReliefPrefRow>>(emptyList())
    val frequent: StateFlow<List<SupabaseDbService.ReliefPrefRow>> = _frequent

    private fun sortPrefs(prefs: List<SupabaseDbService.ReliefPrefRow>) =
        prefs.sortedBy { it.position }

    fun loadAll(accessToken: String) {
        viewModelScope.launch {
            runCatching {
                val p = db.getAllReliefPool(accessToken)
                val f = db.getReliefPrefs(accessToken)
                _pool.value = p
                _frequent.value = sortPrefs(f)
            }.onFailure { it.printStackTrace() }
        }
    }

    fun addNewToPool(accessToken: String, label: String) {
        viewModelScope.launch {
            runCatching {
                db.upsertReliefToPool(accessToken, label)
                _pool.value = db.getAllReliefPool(accessToken)
            }.onFailure { it.printStackTrace() }
        }
    }

    fun removeFromPool(accessToken: String, reliefId: String) {
        viewModelScope.launch {
            runCatching {
                db.deleteReliefFromPool(accessToken, reliefId)
                _pool.value = db.getAllReliefPool(accessToken)
                _frequent.value = sortPrefs(db.getReliefPrefs(accessToken))
            }.onFailure { it.printStackTrace() }
        }
    }

    fun addToFrequent(accessToken: String, reliefId: String) {
        viewModelScope.launch {
            runCatching {
                val pos = (_frequent.value.maxOfOrNull { it.position } ?: -1) + 1
                db.insertReliefPref(accessToken, reliefId, pos, "frequent")
                _frequent.value = sortPrefs(db.getReliefPrefs(accessToken))
            }.onFailure { it.printStackTrace() }
        }
    }

    fun removeFromFrequent(accessToken: String, prefId: String) {
        viewModelScope.launch {
            runCatching {
                db.deleteReliefPref(accessToken, prefId)
                _frequent.value = sortPrefs(db.getReliefPrefs(accessToken))
            }.onFailure { it.printStackTrace() }
        }
    }
}
