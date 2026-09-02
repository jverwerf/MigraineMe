// FILE: app/src/main/java/com/migraineme/NearbyCard.kt
package com.migraineme

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A practice we found near the patient.
 *
 * Same card as a practitioner's, deliberately: this is one list, not two
 * directories. It simply has less in it, because nobody has written anything
 * about themselves yet. Every element is present only when the row actually
 * carries it, so a listing with no website shows no website button rather than
 * a dead one.
 */

/** The same lowercase discipline keys the practitioner cards already
 *  translate, capitalised for a chip. Reused rather than duplicated: a second
 *  set of strings for the same nine words is how two lists start disagreeing
 *  about what a physiotherapist is called. */
@Composable
private fun disciplineChipLabel(key: String): String {
    val word = when (key) {
        "migraine" -> t("migraine clinic")
        "specialist" -> t("migraine specialist")
        "neurologist" -> t("neurologist")
        "headache" -> t("headache clinic")
        "physio" -> t("physiotherapist")
        "osteopath" -> t("osteopath")
        "nutrition" -> t("nutritional therapist")
        "psychologist" -> t("psychologist")
        "acupuncture" -> t("acupuncturist")
        else -> key
    }
    return word.replaceFirstChar { it.uppercase() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NearbyCard(place: SupabaseNearbyService.Place) {
    val context = LocalContext.current

    val subtitle = listOfNotNull(
        place.disciplines.firstOrNull()?.let { disciplineChipLabel(it) },
        place.city,
        place.distance_km?.let { t("%1\$s km", formatKm(it)) },
    ).joinToString(" · ")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF220C33)),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFF3E1D55)),
    ) {
        Column {
            // A short flat band where a practitioner's photo banner would be,
            // so the card keeps the same silhouette in the list without
            // pretending to a picture we do not have.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(Brush.horizontalGradient(listOf(Color(0xFF2A1140), Color(0xFF351A4C))))
            )

            Row(
                Modifier
                    .padding(start = 18.dp, end = 18.dp)
                    .offset(y = (-34).dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF301244))
                        .border(2.dp, Color(0xFF220C33), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        place.initials,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.AccentPurple,
                    )
                }
                Column(Modifier.weight(1f).padding(bottom = 6.dp)) {
                    Text(
                        place.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.BodyTextColor,
                        lineHeight = 21.sp,
                    )
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, fontSize = 12.sp, color = Color(0xFFA991C4), lineHeight = 15.sp)
                    }
                }
            }

            Column(
                Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                    .offset(y = (-22).dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                place.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xFFC9B6E0))
                }

                if (place.disciplines.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        place.disciplines.take(3).forEachIndexed { i, key ->
                            val lead = i == 0
                            Text(
                                disciplineChipLabel(key),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (lead) Color(0xFFE3CEFF) else Color(0xFFD8C6EE),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (lead) AppTheme.AccentPurple.copy(alpha = 0.16f)
                                        else Color(0xFF301244)
                                    )
                                    .border(
                                        1.dp,
                                        if (lead) AppTheme.AccentPurple.copy(alpha = 0.45f)
                                        else Color(0xFF3E1D55),
                                        RoundedCornerShape(8.dp),
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }

                val meta = listOfNotNull(place.address, place.phone)
                if (meta.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        meta.forEach {
                            Text(it, fontSize = 11.5.sp, color = Color(0xFFA991C4), lineHeight = 15.sp)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    place.phone?.let { number ->
                        NearbyAction(t("Call"), Modifier.weight(1f)) {
                            context.startActivity(
                                Intent(Intent.ACTION_DIAL, Uri.parse("tel:${number.filter { c -> c.isDigit() || c == '+' }}"))
                            )
                        }
                    }
                    place.website?.let { site ->
                        NearbyAction(t("Website"), Modifier.weight(1f)) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site)))
                        }
                    }
                    NearbyAction(t("Directions"), Modifier.weight(1f)) {
                        // The Google listing, by id, which is the one field
                        // their terms let us keep. Hours, photos and reviews
                        // are all there, so we never have to buy them. A
                        // listing the model found has no id, so it goes by
                        // name and address.
                        val query = Uri.encode(listOfNotNull(place.name, place.address).joinToString(", "))
                        val byId = if (place.source == "google") "&query_place_id=${place.place_id}" else ""
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://www.google.com/maps/search/?api=1&query=$query$byId")
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF3E1D55)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD9C7F0)),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** One decimal, and no trailing ".0" on a round number. */
private fun formatKm(km: Double): String =
    if (km >= 10) km.toInt().toString() else String.format("%.1f", km)
