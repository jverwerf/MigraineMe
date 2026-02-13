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

    private val _pool = MutableStateFlow<List<SupabaseDbService.UserReliefRow>>(emptyList())
    val pool: StateFlow<List<SupabaseDbService.UserReliefRow>> = _pool

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

    fun addNewToPool(accessToken: String, label: String, category: String? = null) {
        viewModelScope.launch {
            runCatching {
                db.upsertReliefToPool(accessToken, label, category)
                loadAll(accessToken)
            }.onFailure { it.printStackTrace() }
        }
    }

    fun removeFromPool(accessToken: String, reliefId: String) {
        viewModelScope.launch {
            runCatching {
                db.deleteReliefFromPool(accessToken, reliefId)
                loadAll(accessToken)
            }.onFailure { it.printStackTrace() }
        }
    }

    fun addToFrequent(accessToken: String, reliefId: String) {
        viewModelScope.launch {
            runCatching {
                val pos = (_frequent.value.maxOfOrNull { it.position } ?: -1) + 1
                db.insertReliefPref(accessToken, reliefId, pos, "frequent")
                loadAll(accessToken)
            }.onFailure { it.printStackTrace() }
        }
    }

    fun removeFromFrequent(accessToken: String, prefId: String) {
        viewModelScope.launch {
            runCatching {
                db.deleteReliefPref(accessToken, prefId)
                loadAll(accessToken)
            }.onFailure { it.printStackTrace() }
        }
    }

    fun setCategory(accessToken: String, reliefId: String, category: String?) {
        viewModelScope.launch {
            runCatching {
                db.setReliefCategory(accessToken, reliefId, category)
                loadAll(accessToken)
            }.onFailure { it.printStackTrace() }
        }
    }

    fun setAutomation(accessToken: String, reliefId: String, enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                db.setReliefAutomation(accessToken, reliefId, enabled)
                loadAll(accessToken)
            }.onFailure { it.printStackTrace() }
        }
    }
}

