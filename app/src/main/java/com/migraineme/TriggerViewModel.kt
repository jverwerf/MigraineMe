package com.migraineme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TriggerViewModel : ViewModel() {

    private val db = SupabaseDbService(
        BuildConfig.SUPABASE_URL,
        BuildConfig.SUPABASE_ANON_KEY
    )

    private val _pool = MutableStateFlow<List<SupabaseDbService.AllTriggerRow>>(emptyList())
    val pool: StateFlow<List<SupabaseDbService.AllTriggerRow>> = _pool

    private val _frequent = MutableStateFlow<List<SupabaseDbService.TriggerPrefRow>>(emptyList())
    val frequent: StateFlow<List<SupabaseDbService.TriggerPrefRow>> = _frequent

    private val _hidden = MutableStateFlow<List<SupabaseDbService.TriggerPrefRow>>(emptyList())
    val hidden: StateFlow<List<SupabaseDbService.TriggerPrefRow>> = _hidden

    private fun safeSortPrefs(prefs: List<SupabaseDbService.TriggerPrefRow>) =
        prefs.sortedBy { it.position }

    fun loadAll(accessToken: String) {
        viewModelScope.launch {
            try {
                val p = db.getAllTriggerPool(accessToken)
                val prefs = db.getTriggerPrefs(accessToken)
                _pool.value = p
                _frequent.value = safeSortPrefs(prefs.filter { it.status == "frequent" })
                _hidden.value = prefs.filter { it.status == "hidden" }
            } catch (e: Exception) {
                e.printStackTrace()
                _pool.value = emptyList()
                _frequent.value = emptyList()
                _hidden.value = emptyList()
            }
        }
    }

    fun addNewToPool(accessToken: String, label: String) {
        viewModelScope.launch {
            try {
                db.upsertTriggerToPool(accessToken, label.trim())
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeFromPool(accessToken: String, triggerId: String) {
        viewModelScope.launch {
            try {
                db.deleteTriggerFromPool(accessToken, triggerId)
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addToFrequent(accessToken: String, triggerId: String) {
        viewModelScope.launch {
            try {
                val pos = _frequent.value.size
                db.insertTriggerPref(accessToken, triggerId, pos, status = "frequent")
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeFromFrequent(accessToken: String, prefId: String) {
        viewModelScope.launch {
            try {
                db.deleteTriggerPref(accessToken, prefId)
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
