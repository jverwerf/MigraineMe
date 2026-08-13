package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Medicine one-unit system (contract 2026-08-13).
 *
 * Every medicine has EXACTLY ONE unit, stored on the pool item
 * (`user_medicines.dose_unit`). Logs stamp `dose_value` + `dose_unit` and keep
 * mirroring the legacy `amount` text so old readers don't break.
 *
 * Unit enum (exact lowercase DB strings):
 *  - "mg"      default; input picker offers mg and g (g ×1000, stored mg)
 *  - "amount"  plain dose count (combos like Excedrin)
 *  - "units"   Botox
 *  - "minutes" Oxygen therapy
 *  - "mcg"     Vitamin D; input offers mcg and IU (IU ÷40, stored mcg)
 */
object DoseUnits {
    const val MG = "mg"
    const val AMOUNT = "amount"
    const val UNITS = "units"
    const val MINUTES = "minutes"
    const val MCG = "mcg"

    /** Reliefs that are simply "taken" — no duration UI at all. Match by label. */
    private val TAKEN_ONLY_RELIEFS = setOf(
        "water", "electrolytes", "caffeine", "ginger tea", "peppermint oil"
    )

    fun isTakenOnlyRelief(label: String?): Boolean =
        label?.trim()?.lowercase() in TAKEN_ONLY_RELIEFS

    /** Input unit options shown next to the number field. First = stored unit. */
    fun inputOptions(doseUnit: String): List<String> = when (doseUnit) {
        MG -> listOf("mg", "g")
        MCG -> listOf("mcg", "IU")
        else -> listOf(suffix(doseUnit))
    }

    /** Converts the typed value in [inputUnit] to the stored canonical value. */
    fun toStored(value: Double, doseUnit: String, inputUnit: String): Double = when {
        doseUnit == MG && inputUnit == "g" -> value * 1000.0
        doseUnit == MCG && inputUnit == "IU" -> value / 40.0
        else -> value
    }

    /** Unit abbreviation shown as the field suffix (never translated). */
    fun suffix(doseUnit: String): String = when (doseUnit) {
        MG -> "mg"
        MCG -> "mcg"
        UNITS -> "units"
        MINUTES -> "min"
        AMOUNT -> "×"
        else -> doseUnit
    }

    /** Parses a typed number; locale comma accepted. */
    fun parseNumber(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

    /** "400" / "2.5" — whole numbers without decimals, otherwise trimmed. */
    fun formatValue(v: Double): String =
        if (v % 1.0 == 0.0 && kotlin.math.abs(v) < 1e15) v.toLong().toString()
        else BigDecimal(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /**
     * Legacy `amount` text mirror written alongside dose_value/dose_unit:
     * mg → "400mg", mcg → "50mcg", units → "155 units", minutes → "30 min",
     * amount → "2". No space before mg/mcg (dominant existing format).
     */
    fun legacyAmount(value: Double, doseUnit: String): String {
        val f = formatValue(value)
        return when (doseUnit) {
            MG -> "${f}mg"
            MCG -> "${f}mcg"
            UNITS -> "$f units"
            MINUTES -> "$f min"
            AMOUNT -> f
            else -> f
        }
    }

    /**
     * Display format for tallies: mg → "1200mg", amount → "3×",
     * units → "155 units", minutes → "45 min", mcg → "100mcg".
     */
    fun formatTotal(value: Double, doseUnit: String): String {
        val f = formatValue(value)
        return when (doseUnit) {
            MG -> "${f}mg"
            MCG -> "${f}mcg"
            UNITS -> "$f units"
            MINUTES -> "$f min"
            AMOUNT -> "$f×"
            else -> "$f$doseUnit"
        }
    }

    /**
     * Legacy free-text parser used ONLY as fallback for rows with no
     * dose_value (and for normalising AI free text at insert). Contract rules:
     * a bare number or tablet/tabs/caps/pill words = 'amount' count, NOT mg.
     * g converts to mg, IU converts to mcg.
     */
    fun parseLegacy(raw: String?): Pair<Double, String>? {
        if (raw.isNullOrBlank()) return null
        val m = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*([a-zA-Zµ×]+)?""").find(raw.trim()) ?: return null
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return when (val unit = m.groupValues.getOrNull(2)?.lowercase()?.takeIf { it.isNotBlank() }) {
            null -> value to AMOUNT
            "mg" -> value to MG
            "g", "gram", "grams" -> value * 1000.0 to MG
            "mcg", "ug", "µg" -> value to MCG
            "iu" -> value / 40.0 to MCG
            "unit", "units" -> value to UNITS
            "min", "mins", "minute", "minutes" -> value to MINUTES
            "tablet", "tablets", "tab", "tabs", "cap", "caps", "capsule", "capsules",
            "pill", "pills", "x", "×", "dose", "doses", "puff", "puffs" -> value to AMOUNT
            else -> value to unit
        }
    }
}

/**
 * Number-only dose entry with the medicine's fixed unit as suffix.
 * mg medicines get an mg/g toggle, Vitamin D (mcg) gets mcg/IU; everything
 * else shows a plain suffix. The typed value is in [inputUnit]; convert with
 * [DoseUnits.toStored] at save.
 */
@Composable
fun DoseAmountInput(
    doseUnit: String,
    valueText: String,
    onValueTextChange: (String) -> Unit,
    inputUnit: String,
    onInputUnitChange: (String) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    label: String? = null,
    colors: TextFieldColors? = null,
) {
    val options = DoseUnits.inputOptions(doseUnit)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = valueText,
            onValueChange = { new ->
                // digits plus one decimal separator (dot or locale comma)
                if (new.isEmpty() || Regex("""^\d*[.,]?\d*$""").matches(new)) onValueTextChange(new)
            },
            label = { Text(label ?: t("Amount")) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
            colors = colors ?: OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = accent,
                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                focusedLabelColor = accent,
                unfocusedLabelColor = AppTheme.SubtleTextColor,
                cursorColor = Color.White
            )
        )
        if (options.size > 1) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                options.forEach { opt ->
                    val active = opt == inputUnit
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (active) accent.copy(alpha = 0.9f) else Color.Transparent)
                            .clickable { onInputUnitChange(opt) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            opt,
                            color = if (active) Color(0xFF06343B) else AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        } else {
            Text(
                DoseUnits.suffix(doseUnit),
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        }
    }
}
