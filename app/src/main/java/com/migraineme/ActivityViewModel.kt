package com.migraineme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ActivityViewModel : ViewModel() {

    private val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)

    private val _pool = MutableStateFlow<List<SupabaseDbService.AllActivityRow>>(emptyList())
    val pool: StateFlow<List<SupabaseDbService.AllActivityRow>> = _pool

    private val _frequent = MutableStateFlow<List<SupabaseDbService.ActivityPrefRow>>(emptyList())
    val frequent: StateFlow<List<SupabaseDbService.ActivityPrefRow>> = _frequent

    fun loadAll(accessToken: String) {
        viewModelScope.launch {
            runCatching {
                _pool.value = db.getAllActivityPool(accessToken)
                _frequent.value = db.getActivityPrefs(accessToken).sortedBy { it.position }
            }.onFailure { it.printStackTrace() }
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
