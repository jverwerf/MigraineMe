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

    fun loadAll(token: String) {
        viewModelScope.launch {
            try {
                val p = db.getAllTriggerPool(token)
                val prefs = db.getTriggerPrefs(token)

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

    fun addNewToPool(token: String, label: String) {
        viewModelScope.launch {
            try {
                db.upsertTriggerToPool(token, label.trim())
                loadAll(token)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** ✅ Needed by AdjustTriggersScreen: add a trigger to Frequent (appends at end). */
    fun addToFrequent(token: String, triggerId: String) {
        viewModelScope.launch {
            try {
                val pos = _frequent.value.size
                db.insertTriggerPref(token, triggerId, pos, status = "frequent")
                loadAll(token)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** Used by AdjustTriggersScreen to remove from Frequent. */
    fun removeFromFrequent(token: String, prefId: String) {
        viewModelScope.launch {
            try {
                db.deleteTriggerPref(token, prefId)
                loadAll(token)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** Used by AdjustTriggersScreen to persist order after local moves. */
    fun persistFrequentOrder(token: String, current: List<SupabaseDbService.TriggerPrefRow>) {
        viewModelScope.launch {
            try {
                current.forEachIndexed { idx, pref ->
                    db.updateTriggerPrefPosition(token, pref.id, idx)
                }
                _frequent.value = safeSortPrefs(current)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** Local reorder used for instant UI feedback before persisting. */
    fun localReorder(fromIndex: Int, toIndex: Int) {
        val list = _frequent.value.toMutableList()
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        _frequent.value = list
    }
}
