package com.migraineme

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.ZoneId

private val Context.dataStore by preferencesDataStore("location_prefs")

object LocationPrefs {
    private val KEY_LAT = doublePreferencesKey("lat")
    private val KEY_LON = doublePreferencesKey("lon")
    private val KEY_ZONE = stringPreferencesKey("zone_id")

    data class Loc(val lat: Double, val lon: Double, val zoneId: String)

    fun flow(context: Context): Flow<Loc?> =
        context.dataStore.data.map { p ->
            val lat = p[KEY_LAT] ?: return@map null
            val lon = p[KEY_LON] ?: return@map null
            val zone = p[KEY_ZONE] ?: ZoneId.systemDefault().id
            Loc(lat, lon, zone)
        }

    suspend fun save(context: Context, lat: Double, lon: Double, zoneId: String = ZoneId.systemDefault().id) {
        context.dataStore.edit { e ->
            e[KEY_LAT] = lat
            e[KEY_LON] = lon
            e[KEY_ZONE] = zoneId
        }
    }
}
