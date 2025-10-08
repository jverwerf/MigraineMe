package com.migraineme

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.weatherStore by preferencesDataStore("weather_latest")

object WeatherCache {
    private val KEY_TS   = longPreferencesKey("ts_epoch_ms")
    private val KEY_T    = doublePreferencesKey("temp_c")
    private val KEY_TD   = doublePreferencesKey("temp_delta_24h")
    private val KEY_P    = doublePreferencesKey("pressure_hpa")
    private val KEY_PD   = doublePreferencesKey("pressure_delta_24h")
    private val KEY_H    = doublePreferencesKey("humidity_pct")
    private val KEY_HMIN = doublePreferencesKey("humidity_min_24h")
    private val KEY_HMAX = doublePreferencesKey("humidity_max_24h")

    data class Latest(
        val tsEpochMs: Long,
        val tempC: Double?, val tempDelta24h: Double?,
        val pressureHpa: Double?, val pressureDelta24h: Double?,
        val humidityPct: Double?, val humidityMin24h: Double?, val humidityMax24h: Double?
    )

    fun flow(context: Context): Flow<Latest?> =
        context.weatherStore.data.map { p ->
            val ts = p[KEY_TS] ?: return@map null
            Latest(
                ts,
                p[KEY_T], p[KEY_TD],
                p[KEY_P], p[KEY_PD],
                p[KEY_H], p[KEY_HMIN], p[KEY_HMAX]
            )
        }

    suspend fun save(context: Context, s: WeatherService.Summary, tsEpochMs: Long) {
        context.weatherStore.edit { e ->
            e[KEY_TS] = tsEpochMs
            s.tempC?.let { e[KEY_T] = it }
            s.tempDelta24hC?.let { e[KEY_TD] = it }
            s.pressureHpa?.let { e[KEY_P] = it }
            s.pressureDelta24hHpa?.let { e[KEY_PD] = it }
            s.humidityPct?.let { e[KEY_H] = it }
            s.humidityMin24hPct?.let { e[KEY_HMIN] = it }
            s.humidityMax24hPct?.let { e[KEY_HMAX] = it }
        }
    }
}
