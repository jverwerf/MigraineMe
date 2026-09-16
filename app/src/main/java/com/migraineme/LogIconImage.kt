package com.migraineme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Key/label-based lookup used when the caller cannot name a kind: per-drug
 * medicine rows whose stroke set has no entry, and every Insights row, where all
 * we have is a correlation factor name.
 *
 * Each set's own `drawableForKey` is just `BrainyLogManifest.drawableFor(..., its
 * kind)`, so walking the eight sets by hand and then looping the manifest per
 * kind was doing the same work twice — and doing it wrongly, because the manifest
 * applies the global keyword rules at the end of *every* call. Looping meant the
 * first kind fell through to the globals before any later kind's own rules ran,
 * so "Fatigue" resolved through the medicine pass and never reached the symptom
 * key map. The manifest now walks KIND_ORDER itself and applies the globals once.
 */
fun brainyForLogKey(iconKey: String?, label: String?, category: String? = null): Int? =
    BrainyLogManifest.drawableFor(label, iconKey, category, null)

/**
 * Leading Brainy icon for an Insights row, sized to sit beside body text.
 *
 * Draws nothing at all when no art resolves — no placeholder, no reserved gap —
 * so a row that cannot be illustrated still reads as a normal text row rather
 * than a broken one. Emits its own trailing spacer for the same reason: callers
 * that add one unconditionally would leave a hanging indent on unresolved rows.
 */
@Composable
fun BrainyRowIcon(
    label: String?,
    iconKey: String? = null,
    category: String? = null,
    size: Dp = 18.dp,
    gap: Dp = 6.dp,
) {
    val res = brainyForLogKey(iconKey, label, category) ?: return
    // Same soft organic blob as BrainyBlobIcon, scaled down to row size
    // (58x54 box around a 44 image → 1.32x/1.23x of the art).
    Box(
        modifier = Modifier
            .size(width = size * 58f / 44f, height = size * 54f / 44f)
            .background(
                brush = Brush.linearGradient(
                    listOf(Color(0x57CE93D8), Color(0x24B388FF))
                ),
                shape = RoundedCornerShape(
                    topStartPercent = 46, topEndPercent = 54,
                    bottomEndPercent = 42, bottomStartPercent = 58
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(painterResource(res), contentDescription = null, modifier = Modifier.size(size))
    }
    Spacer(Modifier.width(gap))
}

/**
 * Reverse lookup across every log icon set: the Brainy drawable whose stroke
 * vector (identity match) is [v], or null. Lets generic sites that only hold a
 * resolved ImageVector upgrade to Brainy art without knowing the source set.
 */
fun brainyForLogVector(v: ImageVector?): Int? {
    if (v == null) return null
    return TriggerIcons.drawableForVector(v)
        ?: ProdromeIcons.drawableForVector(v)
        ?: SymptomIcons.drawableForVector(v)
        ?: ReliefIcons.drawableForVector(v)
        ?: MedicineIcons.drawableForVector(v)
        ?: ActivityIcons.drawableForVector(v)
        ?: LocationIcons.drawableForVector(v)
        ?: MissedActivityIcons.drawableForVector(v)
}

/**
 * The generic Brainies a user-typed symptom or pain character wears until the
 * server has drawn one from their own words. Same ten keys as the edge function's
 * FALLBACK_POOL, which also answers with one of these when the daily cap is hit.
 */
object CustomBrainy {
    val POOL = listOf(
        "custom_star", "custom_note", "custom_question", "custom_pin", "custom_bulb",
        "custom_heart", "custom_clock", "custom_bolt", "custom_cloud", "custom_tag"
    )

    /** Picked from the label so the same entry keeps the same stand-in across reloads. */
    fun randomKey(label: String): String =
        POOL[(label.hashCode().toUInt() % POOL.size.toUInt()).toInt()]
}

/**
 * Brainy that was drawn for this row on the server. Falls back to the built-in art
 * while the image loads or if it cannot be fetched, so a row is never blank.
 */
@Composable
fun CustomBrainyImage(iconUrl: String, size: Dp, modifier: Modifier = Modifier) {
    AsyncImage(
        model = iconUrl,
        contentDescription = null,
        modifier = modifier.size(size)
    )
}

/**
 * A custom pool item whose icon_key holds the URL of a Brainy the server drew for it
 * (generate-symptom-icon). Pool tables other than user_symptoms have no icon_url column,
 * and icon_key is unconstrained text, so the link lives there.
 */
fun isDrawnIconKey(iconKey: String?): Boolean = iconKey?.startsWith("http") == true

/**
 * Pool item icon: the server-drawn Brainy when icon_key is a link, otherwise the usual
 * bundled Brainy / stroke-glyph pair via [LogIconImage].
 */
@Composable
fun BrainyOrDrawnIcon(
    iconKey: String?,
    drawableId: Int?,
    fallback: ImageVector?,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier
) {
    if (isDrawnIconKey(iconKey)) CustomBrainyImage(iconKey!!, size, modifier)
    else LogIconImage(drawableId, fallback, size, tint, modifier)
}

/**
 * Log icon renderer: full-colour Brainy drawable when one exists for the key,
 * otherwise the legacy stroke ImageVector with the caller's tint.
 * Brainy art is never tinted; selection states style the container instead.
 */
@Composable
fun LogIconImage(
    drawableId: Int?,
    fallback: ImageVector?,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier
) {
    when {
        drawableId != null -> Image(
            painter = painterResource(drawableId),
            contentDescription = null,
            modifier = modifier.size(size)
        )
        fallback != null -> Icon(
            imageVector = fallback,
            contentDescription = null,
            modifier = modifier.size(size),
            tint = tint
        )
    }
}
