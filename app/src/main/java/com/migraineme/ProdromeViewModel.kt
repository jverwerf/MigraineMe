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

    private val _pool = MutableStateFlow<List<SupabaseDbService.AllProdromeRow>>(emptyList())
    val pool: StateFlow<List<SupabaseDbService.AllProdromeRow>> = _pool

    private val _frequent = MutableStateFlow<List<SupabaseDbService.ProdromePrefRow>>(emptyList())
    val frequent: StateFlow<List<SupabaseDbService.ProdromePrefRow>> = _frequent

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
