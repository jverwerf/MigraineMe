package com.migraineme

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DataSettingsViewModel(
    private val context: Context,
    private val edge: EdgeFunctionsService = EdgeFunctionsService()
) : ViewModel() {

    private val _metricSettings = MutableStateFlow<Map<String, EdgeFunctionsService.MetricSettingResponse>>(emptyMap())
    val metricSettings: StateFlow<Map<String, EdgeFunctionsService.MetricSettingResponse>> = _metricSettings.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        loadSettings()
    }

    fun loadSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            try {
                val settings = edge.getMetricSettings(context)
                Log.d(TAG, "Loaded ${settings.size} settings from Supabase")

                _metricSettings.value = settings.associateBy { setting ->
                    if (setting.preferredSource != null) {
                        "${setting.metric}_${setting.preferredSource}"
                    } else {
                        "${setting.metric}_null"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load settings: ${e.message}", e)
            } finally {
                _loading.value = false
            }
        }
    }

    suspend fun toggleMetric(metric: String, enabled: Boolean, source: String?): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Toggling $metric to $enabled (source: $source)")
                val success = edge.upsertMetricSetting(
                    context = context,
                    metric = metric,
                    enabled = enabled,
                    preferredSource = source
                )

                if (success) {
                    loadSettings() // Refresh
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Failed to update setting"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Toggle failed: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    companion object {
        private const val TAG = "DataSettingsVM"
    }
}