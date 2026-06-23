package com.migraineme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Display-units preferences — temperature (°C/°F) and altitude (m/ft) are
 * INDEPENDENT. DISPLAY ONLY — all canonical data stays metric in Supabase
 * (temp_c_mean, altitude_*_m); we convert at the formatting boundary
 * (MetricFormatter + the weather cards + history list/edit + graphs).
 *
 * Backed by SharedPreferences, each DEFAULTED from the device region on first
 * read (imperial regions US/LR/MM → °F / feet; else °C / metres), via
 * Locale.getDefault().country. Reactive StateFlows drive Compose recomposition
 * so any screen showing temperature/altitude re-renders the moment a unit flips.
 *
 * Mirrors VertigoMe's lib/unitsPrefs.ts + hooks/useUnits.ts.
 *
 * CRITICAL: skin temperature (column value_celsius) is a DEVIATION from baseline,
 * NOT an absolute temperature — it must NEVER be converted (an offset °F formula
 * is meaningless). The column classifiers below deliberately exclude it, and the
 * skin-temp display sites format °C directly without going through here.
 */
enum class TempUnit { C, F }
enum class AltUnit { M, FT }

object UnitsPrefs {
    private const val PREFS = "units_prefs"
    private const val KEY_TEMP = "temp_unit"
    private const val KEY_ALT = "alt_unit"

    /** Regions that default to imperial units (Fahrenheit / feet). */
    private val IMPERIAL_REGIONS = setOf("US", "LR", "MM")

    @Volatile private var appContext: Context? = null

    private fun deviceIsImperial(): Boolean =
        try {
            Locale.getDefault().country.uppercase(Locale.ROOT) in IMPERIAL_REGIONS
        } catch (e: Exception) {
            false
        }

    private fun deviceDefaultTemp(): TempUnit = if (deviceIsImperial()) TempUnit.F else TempUnit.C
    private fun deviceDefaultAlt(): AltUnit = if (deviceIsImperial()) AltUnit.FT else AltUnit.M

    // Seeded from the device default; replaced by the persisted value once init()
    // runs at app start. Pure formatters read .value synchronously.
    private val _tempUnit = MutableStateFlow(deviceDefaultTemp())
    val tempUnit: StateFlow<TempUnit> = _tempUnit.asStateFlow()

    private val _altUnit = MutableStateFlow(deviceDefaultAlt())
    val altUnit: StateFlow<AltUnit> = _altUnit.asStateFlow()

    /** Hydrate both flows from storage. Call once at app start (MigraineMeApp). */
    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _tempUnit.value = when (p.getString(KEY_TEMP, null)) {
            "C" -> TempUnit.C
            "F" -> TempUnit.F
            else -> deviceDefaultTemp()
        }
        _altUnit.value = when (p.getString(KEY_ALT, null)) {
            "M" -> AltUnit.M
            "FT" -> AltUnit.FT
            else -> deviceDefaultAlt()
        }
    }

    // ── Synchronous current preferences (for use inside pure formatters) ──────
    fun getTempUnit(): TempUnit = _tempUnit.value
    fun getAltUnit(): AltUnit = _altUnit.value
    fun isImperialTemp(): Boolean = _tempUnit.value == TempUnit.F
    fun isImperialAlt(): Boolean = _altUnit.value == AltUnit.FT

    fun setTempUnit(next: TempUnit) {
        _tempUnit.value = next
        try {
            appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
                ?.putString(KEY_TEMP, next.name)?.apply()
        } catch (e: Exception) {
            // Non-fatal — the in-memory flow still reflects the choice this session.
        }
    }

    fun setAltUnit(next: AltUnit) {
        _altUnit.value = next
        try {
            appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
                ?.putString(KEY_ALT, next.name)?.apply()
        } catch (e: Exception) {
            // Non-fatal.
        }
    }

    // ── Conversions ──────────────────────────────────────────────────────────
    // canonical metric → display
    fun cToF(c: Double): Double = c * 9.0 / 5.0 + 32.0
    fun mToFt(m: Double): Double = m * 3.28084
    // display → canonical metric (for converting manual entry back before storing)
    fun fToC(f: Double): Double = (f - 32.0) * 5.0 / 9.0
    fun ftToM(ft: Double): Double = ft / 3.28084

    // ── Display-unit labels ──────────────────────────────────────────────────
    fun tempLabel(): String = if (isImperialTemp()) "°F" else "°C"
    fun altLabel(): String = if (isImperialAlt()) "ft" else "m"

    /** Apply the display conversion to a canonical °C weather temperature. */
    fun displayTemp(canonicalC: Double): Double = if (isImperialTemp()) cToF(canonicalC) else canonicalC

    /** Apply the display conversion to a canonical metres altitude value. */
    fun displayAlt(canonicalM: Double): Double = if (isImperialAlt()) mToFt(canonicalM) else canonicalM

    // ── Column classifiers ───────────────────────────────────────────────────
    // Generic history/edit screens are column-driven and have no unit string, so
    // they detect temperature/altitude columns to know what to convert.

    /** Columns whose stored value is an ABSOLUTE temperature in °C (weather).
     *  Deliberately excludes skin temperature (`value_celsius`), a deviation. */
    fun isCelsiusColumn(column: String): Boolean = column.contains("temp_c")

    /** Columns whose stored value is metres. */
    fun isMetreColumn(column: String): Boolean =
        column == "altitude_m" || Regex("^altitude_(max|min|change)_m$").matches(column)

    // ── Insights-series key classifiers ──────────────────────────────────────
    // The insights timeline / report graphs identify metrics by short key, not
    // column. "temp"/"altitude"/"alt_change" are convertible weather/altitude;
    // "skin_temp" is a deviation and must NOT be converted.

    fun isWeatherTempKey(key: String): Boolean = key == "temp"
    fun isAltitudeKey(key: String): Boolean = key == "altitude" || key == "alt_change"

    /** Display value for an insights series, converting weather temp / altitude only. */
    fun displayValueForKey(key: String, value: Double): Double = when {
        isWeatherTempKey(key) -> displayTemp(value)
        isAltitudeKey(key) -> displayAlt(value)
        else -> value
    }

    /** Display unit string for an insights series, flipping weather temp / altitude only. */
    fun displayUnitForKey(key: String, unit: String): String = when {
        isWeatherTempKey(key) -> tempLabel()
        isAltitudeKey(key) -> altLabel()
        else -> unit
    }
}
