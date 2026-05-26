package com.migraineme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the user-managed treatment side-effects pool. Mirrors
 * TriggerViewModel / MissedActivityViewModel — exposes the pool + a list
 * of `frequent` prefs, plus add/remove/favourite helpers.
 */
class TreatmentSideEffectViewModel : ViewModel() {
    private val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)

    private val _pool = MutableStateFlow<List<SupabaseDbService.UserTreatmentSideEffectRow>>(emptyList())
    val pool: StateFlow<List<SupabaseDbService.UserTreatmentSideEffectRow>> = _pool

    private val _frequent = MutableStateFlow<List<SupabaseDbService.TreatmentSideEffectPrefRow>>(emptyList())
    val frequent: StateFlow<List<SupabaseDbService.TreatmentSideEffectPrefRow>> = _frequent

    fun loadAll(accessToken: String) {
        viewModelScope.launch {
            try {
                _pool.value = db.getUserTreatmentSideEffects(accessToken)
                _frequent.value = db.getTreatmentSideEffectPrefs(accessToken)
                    .filter { it.status == "frequent" }
            } catch (_: Throwable) {
                _pool.value = emptyList()
                _frequent.value = emptyList()
            }
        }
    }

    fun addNewToPool(accessToken: String, label: String, iconKey: String? = null) {
        viewModelScope.launch {
            runCatching { db.insertTreatmentSideEffectToPool(accessToken, label, iconKey = iconKey) }
            loadAll(accessToken)
        }
    }

    fun removeFromPool(accessToken: String, id: String) {
        viewModelScope.launch {
            runCatching { db.deleteTreatmentSideEffectFromPool(accessToken, id) }
            loadAll(accessToken)
        }
    }

    fun addToFrequent(accessToken: String, sideEffectId: String) {
        viewModelScope.launch {
            val pos = _frequent.value.size
            runCatching { db.insertTreatmentSideEffectPref(accessToken, sideEffectId, pos) }
            loadAll(accessToken)
        }
    }

    fun removeFromFrequent(accessToken: String, sideEffectId: String) {
        viewModelScope.launch {
            runCatching { db.deleteTreatmentSideEffectPref(accessToken, sideEffectId) }
            loadAll(accessToken)
        }
    }
}
