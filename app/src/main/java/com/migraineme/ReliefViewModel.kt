package com.migraineme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ReliefViewModel : PoolViewModel() {

    private val db = SupabaseDbService(
        BuildConfig.SUPABASE_URL,
        BuildConfig.SUPABASE_ANON_KEY
    )

    private val _pool = MutableStateFlow<List<SupabaseDbService.UserReliefRow>>(emptyList())
    val pool: StateFlow<List<SupabaseDbService.UserReliefRow>> = _pool

    private val _frequent = MutableStateFlow<List<SupabaseDbService.ReliefPrefRow>>(emptyList())
    val frequent: StateFlow<List<SupabaseDbService.ReliefPrefRow>> = _frequent

    /**
     * The global relief library — every row of relief_templates, including the
     * Device entries (CEFALY, Nerivio, Apollo Neuro, …). Onboarding only ever
     * seeded these once, so without a way to reach them here an existing user
     * could never add a device and the 2h "did it help?" follow-up stayed dark.
     */
    private val _library = MutableStateFlow<List<SupabaseDbService.ReliefTemplateRow>>(emptyList())
    val library: StateFlow<List<SupabaseDbService.ReliefTemplateRow>> = _library

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
        viewModelScope.launch {
            // Separate launch: the library is additive. A template read that
            // fails must not blank the pool the user already has.
            runCatching { db.getReliefTemplates(accessToken) }
                .onSuccess { _library.value = it }
                .onFailure { it.printStackTrace() }
        }
    }

    fun addNewToPool(accessToken: String, label: String, category: String? = null) {
        viewModelScope.launch {
            runCatching {
                db.upsertReliefToPool(accessToken, label, category)
                loadAll(accessToken)
            }.onFailure { reportError(it) }
        }
    }

    /**
     * Adds a library row to the pool with the template's own metadata, which is
     * the same column set seed_pools_for_new_user() copies. Category rides along
     * unchanged, so a Device template lands as category = 'Device' and
     * DeviceCatalog.isDeviceRelief picks it up on the next log.
     */
    fun addFromLibrary(accessToken: String, template: SupabaseDbService.ReliefTemplateRow) {
        viewModelScope.launch {
            runCatching {
                db.upsertReliefToPool(
                    accessToken = accessToken,
                    label = template.label,
                    category = template.category,
                    iconKey = template.iconKey,
                    isAutomatable = template.isAutomatable,
                    isAutomated = template.isAutomated
                )
                loadAll(accessToken)
            }.onFailure { reportError(it) }
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

