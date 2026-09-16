package com.migraineme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SymptomViewModel : PoolViewModel() {

    private val db = SupabaseDbService(
        BuildConfig.SUPABASE_URL,
        BuildConfig.SUPABASE_ANON_KEY
    )

    private val _painCharacter = MutableStateFlow<List<SupabaseDbService.UserSymptomRow>>(emptyList())
    val painCharacter: StateFlow<List<SupabaseDbService.UserSymptomRow>> = _painCharacter

    private val _accompanying = MutableStateFlow<List<SupabaseDbService.UserSymptomRow>>(emptyList())
    val accompanying: StateFlow<List<SupabaseDbService.UserSymptomRow>> = _accompanying

    private val _postdrome = MutableStateFlow<List<SupabaseDbService.UserSymptomRow>>(emptyList())
    val postdrome: StateFlow<List<SupabaseDbService.UserSymptomRow>> = _postdrome

    private val _pool = MutableStateFlow<List<SupabaseDbService.UserSymptomRow>>(emptyList())
    val pool: StateFlow<List<SupabaseDbService.UserSymptomRow>> = _pool

    private val _favorites = MutableStateFlow<List<SupabaseDbService.SymptomPrefRow>>(emptyList())
    val favorites: StateFlow<List<SupabaseDbService.SymptomPrefRow>> = _favorites

    /** Set of symptom IDs that are favorited, for quick lookup */
    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds

    fun loadAll(accessToken: String) {
        viewModelScope.launch {
            try {
                val all = db.getAllSymptomPool(accessToken)
                val prefs = db.getSymptomPrefs(accessToken)
                _pool.value = all
                _painCharacter.value = all.filter { it.category == "pain_character" }
                _accompanying.value = all.filter { it.category != "pain_character" && it.category != "Postdrome" }
                _postdrome.value = all.filter { it.category == "Postdrome" }
                _favorites.value = prefs.filter { it.status == "frequent" }.sortedBy { it.position }
                _favoriteIds.value = _favorites.value.map { it.symptomId }.toSet()
            } catch (e: Exception) {
                e.printStackTrace()
                _pool.value = emptyList()
                _painCharacter.value = emptyList()
                _accompanying.value = emptyList()
                _postdrome.value = emptyList()
                _favorites.value = emptyList()
                _favoriteIds.value = emptySet()
            }
        }
    }

    fun addNewToPool(accessToken: String, label: String, category: String, iconKey: String? = null) {
        viewModelScope.launch {
            try {
                val clean = label.trim()
                // A label we have no art for would sit there as two grey letters while the
                // drawing is made, so give it a generic Brainy up front and swap it for the
                // drawn one when that arrives.
                val placeholder = iconKey
                    ?: if (BrainyLogManifest.drawableFor(clean, null, category, kindFor(category)) == null)
                        CustomBrainy.randomKey(clean) else null
                val row = db.upsertSymptomToPool(accessToken, clean, category, placeholder)
                loadAll(accessToken)
                if (iconKey == null) drawBrainy(accessToken, row.id, clean, category)
            } catch (e: Exception) {
                reportError(e)
            }
        }
    }

    private fun kindFor(category: String?) =
        if (category == "pain_character") "painCharacter" else "symptom"

    /** Asks the server for a Brainy drawn from the user's own words (~20 s), then reloads. */
    private fun drawBrainy(accessToken: String, symptomId: String, label: String, category: String?) {
        viewModelScope.launch {
            val drawn = EdgeFunctionsService().generateSymptomIcon(accessToken, label, kindFor(category)) ?: return@launch
            if (drawn.iconUrl == null && drawn.iconKey == null) return@launch
            try {
                db.setSymptomIcon(accessToken, symptomId, drawn.iconKey, drawn.iconUrl)
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeFromPool(accessToken: String, symptomId: String) {
        viewModelScope.launch {
            try {
                db.deleteSymptomFromPool(accessToken, symptomId)
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addToFavorites(accessToken: String, symptomId: String) {
        viewModelScope.launch {
            try {
                val pos = _favorites.value.size
                db.insertSymptomPref(accessToken, symptomId, pos, status = "frequent")
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeFromFavorites(accessToken: String, prefId: String) {
        viewModelScope.launch {
            try {
                db.deleteSymptomPref(accessToken, prefId)
                loadAll(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

