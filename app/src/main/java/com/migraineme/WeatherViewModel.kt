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

class WeatherViewModel : ViewModel() {
    private val _state = MutableStateFlow(WeatherUiState())
    val state: StateFlow<WeatherUiState> = _state

    fun load(lat: Double, lon: Double, zoneId: String = ZoneId.systemDefault().id) {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val s = WeatherService.getSummary(lat, lon, zoneId)
                _state.value = WeatherUiState(loading = false, summary = s)
            } catch (e: Exception) {
                _state.value = WeatherUiState(loading = false, error = e.message ?: "Weather fetch failed")
            }
        }
    }
}
