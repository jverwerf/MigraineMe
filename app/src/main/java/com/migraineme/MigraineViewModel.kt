package com.migraineme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MigraineViewModel : ViewModel() {

    private val db = SupabaseDbService(
        BuildConfig.SUPABASE_URL,
        BuildConfig.SUPABASE_ANON_KEY
    )

    private val _pool = MutableStateFlow<List<SupabaseDbService.AllMigraineRow>>(emptyList())
    val pool: StateFlow<List<SupabaseDbService.AllMigraineRow>> = _pool

    private val _frequent = MutableStateFlow<List<SupabaseDbService.MigrainePrefRow>>(emptyList())
    val frequent: StateFlow<List<SupabaseDbService.MigrainePrefRow>> = _frequent

    private val _hidden = MutableStateFlow<List<SupabaseDbService.MigrainePrefRow>>(emptyList())
    val hidden: StateFlow<List<SupabaseDbService.MigrainePrefRow>> = _hidden

    private fun safeSortPrefs(prefs: List<SupabaseDbService.MigrainePrefRow>) =
        prefs.sortedBy { it.position }

    fun loadAll(accessToken: String) {
        viewModelScope.launch {
            try {
                val p = db.getAllMigrainePool(accessToken)
                val prefs = db.getMigrainePrefs(accessToken)
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
                db.upsertMigraineToPool(accessToken, label.trim())
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeFromPool(accessToken: String, migraineId: String) {
        viewModelScope.launch {
            try {
                db.deleteMigraineFromPool(accessToken, migraineId)
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addToFrequent(accessToken: String, migraineId: String) {
        viewModelScope.launch {
            try {
                val pos = _frequent.value.size
                db.insertMigrainePref(accessToken, migraineId, pos, status = "frequent")
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeFromFrequent(accessToken: String, prefId: String) {
        viewModelScope.launch {
            try {
                db.deleteMigrainePref(accessToken, prefId)
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
