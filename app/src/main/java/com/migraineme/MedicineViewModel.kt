package com.migraineme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MedicineViewModel : ViewModel() {

    private val db = SupabaseDbService(
        BuildConfig.SUPABASE_URL,
        BuildConfig.SUPABASE_ANON_KEY
    )

    private val _pool = MutableStateFlow<List<SupabaseDbService.UserMedicineRow>>(emptyList())
    val pool: StateFlow<List<SupabaseDbService.UserMedicineRow>> = _pool

    private val _frequent = MutableStateFlow<List<SupabaseDbService.MedicinePrefRow>>(emptyList())
    val frequent: StateFlow<List<SupabaseDbService.MedicinePrefRow>> = _frequent

    private fun sortPrefs(prefs: List<SupabaseDbService.MedicinePrefRow>) =
        prefs.sortedBy { it.position }

    fun loadAll(accessToken: String) {
        viewModelScope.launch {
            try {
                val p = db.getAllMedicinePool(accessToken)
                val prefs = db.getMedicinePrefs(accessToken)
                _pool.value = p
                _frequent.value = sortPrefs(prefs.filter { it.status == "frequent" })
            } catch (e: Exception) {
                e.printStackTrace()
                _pool.value = emptyList()
                _frequent.value = emptyList()
            }
        }
    }

    fun addNewToPool(accessToken: String, label: String, category: String? = null) {
        viewModelScope.launch {
            try {
                db.upsertMedicineToPool(accessToken, label.trim(), category)
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addNewToPoolAndFrequent(accessToken: String, label: String) {
        viewModelScope.launch {
            try {
                val added = db.upsertMedicineToPool(accessToken, label.trim())
                val pos = _frequent.value.size
                db.insertMedicinePref(accessToken, added.id, pos, status = "frequent")
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addToFrequent(accessToken: String, medicineId: String) {
        viewModelScope.launch {
            try {
                val pos = _frequent.value.size
                db.insertMedicinePref(accessToken, medicineId, pos, status = "frequent")
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeFromFrequent(accessToken: String, prefId: String) {
        viewModelScope.launch {
            try {
                db.deleteMedicinePref(accessToken, prefId)
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeFromPool(accessToken: String, medicineId: String) {
        viewModelScope.launch {
            try {
                db.deleteMedicineFromPool(accessToken, medicineId)
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setCategory(accessToken: String, medicineId: String, category: String?) {
        viewModelScope.launch {
            try {
                db.setMedicineCategory(accessToken, medicineId, category)
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

