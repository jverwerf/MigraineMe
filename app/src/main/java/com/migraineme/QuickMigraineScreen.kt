package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun QuickMigraineScreen(
    navController: NavController,
    authVm: AuthViewModel,
    symptomVm: SymptomViewModel,
    onClose: () -> Unit = {}
) {
    val authState by authVm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    val painCharacter by symptomVm.painCharacter.collectAsState()
    val accompanying by symptomVm.accompanying.collectAsState()
    val favorites by symptomVm.favorites.collectAsState()

    LaunchedEffect(authState.accessToken) {
        authState.accessToken?.let { symptomVm.loadAll(it) }
    }

    var selectedSymptom by rememberSaveable { mutableStateOf<String?>(null) }
    var beganAtIso by rememberSaveable { mutableStateOf<String?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    fun save() {
        val token = authState.accessToken ?: return
        val item = selectedSymptom ?: return
        saving = true
        scope.launch {
            try {
                val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                val nowIso = beganAtIso ?: Instant.now().toString()
                db.insertMigraine(
                    accessToken = token,
                    type = item,
                    severity = null,
                    startAt = nowIso,
                    endAt = nowIso,
                    notes = notes.ifBlank { null },
                    painLocations = null
                )
                launch(Dispatchers.IO) {
                    try { EdgeFunctionsService().triggerCorrelationCompute(context) }
                    catch (e: Exception) { e.printStackTrace() }
                }
                navController.popBackStack()
            } catch (e: Exception) {
                e.printStackTrace()
                saving = false
            }
        }
    }

    // Favorite-label sets per category (matches iOS)
    val favPainLabels = favorites
        .filter { it.symptom?.category == "pain_character" }
        .mapNotNull { it.symptom?.label }.toSet()
    val favAccompLabels = favorites
        .filter {
            val c = it.symptom?.category
            c != null && c != "pain_character" && c != "Postdrome"
        }
        .mapNotNull { it.symptom?.label }.toSet()
    // iOS sections: Frequent (favorites, postdrome excluded) → Pain character (non-fav) →
    // Accompanying (non-fav, sub-grouped by category). Postdrome is intentionally excluded
    // from quick log; users log postdrome via the full wizard.
    val nonPostdromeFavLabels = favPainLabels + favAccompLabels
    val frequentItems = (painCharacter + accompanying).filter { it.label in nonPostdromeFavLabels }
    val painNonFav = painCharacter.filter { it.label !in favPainLabels }
    val accompNonFav = accompanying.filter { it.label !in favAccompLabels }
    val accompGrouped = accompNonFav
        .groupBy { (it.category ?: "").ifEmpty { "Other" } }
        .toSortedMap()

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(48.dp))
                Text("Quick Log", color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, "Close", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            // Hero (matches iOS symptomContent header)
            HeroCard {
                Box(Modifier.size(40.dp).drawBehind { HubIcons.run { drawMigraineStarburst(AppTheme.AccentPink) } })
                Text("Log Migraine", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(
                    "Pain character, symptom",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            // Symptom picker — single-select, matches iOS symptomPoolPicker
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                selectedSymptom?.let { sel ->
                    val allRows = painCharacter + accompanying
                    val iconKey = allRows.firstOrNull { it.label == sel }?.iconKey
                    val icon = SymptomIcons.forLabel(sel, iconKey)
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppTheme.AccentPink.copy(alpha = 0.15f))
                            .border(1.dp, AppTheme.AccentPink.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (icon != null) {
                            Icon(icon, contentDescription = null, tint = AppTheme.AccentPink, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(sel, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { selectedSymptom = null }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Outlined.Close, "Clear", tint = AppTheme.AccentPink.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                        }
                    }
                }

                if (frequentItems.isNotEmpty()) {
                    SectionHeader("Frequent")
                    frequentItems.forEach { s ->
                        SymptomRow(
                            label = s.label,
                            iconKey = s.iconKey,
                            selected = selectedSymptom == s.label,
                            color = AppTheme.AccentPink
                        ) { selectedSymptom = s.label }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                }

                if (painNonFav.isNotEmpty()) {
                    SectionHeader("Pain character")
                    painNonFav.forEach { s ->
                        SymptomRow(s.label, s.iconKey, selectedSymptom == s.label, AppTheme.AccentPink) {
                            selectedSymptom = s.label
                        }
                    }
                }

                if (accompNonFav.isNotEmpty()) {
                    SectionHeader("Accompanying experience")
                    accompGrouped.forEach { (sub, items) ->
                        val subLabel = if (sub == "accompanying") "General" else sub
                        Text(
                            subLabel,
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                        )
                        items.forEach { s ->
                            SymptomRow(s.label, s.iconKey, selectedSymptom == s.label, AppTheme.AccentPink) {
                                selectedSymptom = s.label
                            }
                        }
                    }
                }

                // Postdrome intentionally excluded from quick log — log via the full wizard.
            }

            // Details card (matches iOS detailsCard)
            BaseCard {
                Text("Details", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(8.dp))

                Column(Modifier.fillMaxWidth()) {
                    Text("When did this happen?", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(4.dp))
                    AppDateTimePicker(
                        label = formatQuickTime(beganAtIso) ?: "Now",
                        onDateTimeSelected = { beganAtIso = it }
                    )
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)", color = AppTheme.SubtleTextColor) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AppTheme.AccentPurple,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                    ),
                    minLines = 2
                )
            }

            // Action row (matches iOS saveButton row)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onClose,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = { save() },
                    enabled = !saving && selectedSymptom != null,
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink)
                ) {
                    Text(if (saving) "Saving…" else "Log Migraine", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = AppTheme.SubtleTextColor,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun SymptomRow(
    label: String,
    iconKey: String?,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    val icon = SymptomIcons.forLabel(label, iconKey)
    val bg = if (selected) color.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f)
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) color else AppTheme.SubtleTextColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        if (selected) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        } else {
            Box(
                Modifier.size(22.dp).clip(CircleShape)
                    .border(1.5.dp, Color.White.copy(alpha = 0.2f), CircleShape)
            )
        }
    }
}

private fun formatQuickTime(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return try {
        val odt = runCatching { OffsetDateTime.parse(iso) }.getOrNull()
        val ldt = odt?.toLocalDateTime()
            ?: runCatching { LocalDateTime.parse(iso) }.getOrNull()
            ?: runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull()
            ?: return null
        ldt.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
    } catch (_: Exception) { null }
}
