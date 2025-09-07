// app/src/main/java/com/migraineme/WeatherViewModel.kt
package com.migraineme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId

data class WeatherUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val summary: WeatherService.Summary? = null
)

/**
 * Minimal WeatherViewModel:
 * - Call load(lat, lon, zoneId) to fetch a live summary from WeatherService.
 * - Exposes StateFlow<WeatherUiState> for UI to observe.
 * - No @Composable code in here (fixes those compile errors).
 */
class WeatherViewModel : ViewModel() {

    private val _state = MutableStateFlow(WeatherUiState())
    val state: StateFlow<WeatherUiState> = _state

    fun load(
        lat: Double,
        lon: Double,
        zoneId: String = ZoneId.systemDefault().id
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching {
                WeatherService.getSummary(lat, lon, zoneId)
            }.onSuccess { summary ->
                _state.value = WeatherUiState(loading = false, summary = summary)
            }.onFailure { e ->
                _state.value = WeatherUiState(loading = false, error = e.message ?: "Unknown error")
            }
        }
    }
}
