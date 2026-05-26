package com.migraineme

import android.content.Context
import android.widget.DatePicker
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.draw.alpha
import org.json.JSONArray
import org.json.JSONException
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ════════════════════════════════════════════════════════════════════════════
// Treatment card config (Monitor home tile favourites). Max 3.
// ════════════════════════════════════════════════════════════════════════════

private const val TREATMENT_CARD_PREFS = "treatment_card_config_v1"
private const val TREATMENT_CARD_KEY = "favourite_ids"
const val TREATMENT_CARD_MAX_FAVOURITES = 3

fun loadTreatmentCardFavourites(context: android.content.Context): List<String> {
    val sp = context.getSharedPreferences(TREATMENT_CARD_PREFS, android.content.Context.MODE_PRIVATE)
    val raw = sp.getString(TREATMENT_CARD_KEY, null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        List(arr.length()) { arr.getString(it) }
    } catch (_: JSONException) { emptyList() }
}
fun saveTreatmentCardFavourites(context: android.content.Context, ids: List<String>) {
    val sp = context.getSharedPreferences(TREATMENT_CARD_PREFS, android.content.Context.MODE_PRIVATE)
    sp.edit().putString(TREATMENT_CARD_KEY, JSONArray(ids).toString()).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorTreatmentsConfigScreen(navController: NavController) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var regimens by remember { mutableStateOf<List<SupabaseDbService.TreatmentRegimenRow>>(emptyList()) }
    var favourites by remember { mutableStateOf(loadTreatmentCardFavourites(context)) }

    LaunchedEffect(Unit) {
        loading = true
        val rows = withContext(Dispatchers.IO) {
            try {
                val token = SessionStore.getValidAccessToken(context) ?: return@withContext emptyList()
                val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                db.getTreatmentRegimens(token)
            } catch (_: Throwable) { emptyList() }
        }
        regimens = rows
        // Prune stale favourites
        val validIds = rows.map { it.id }.toSet()
        favourites = favourites.filter { it in validIds }
        saveTreatmentCardFavourites(context, favourites)
        loading = false
    }

    val active = regimens.filter { it.stopDate == null }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
            Text(
                "Pick up to $TREATMENT_CARD_MAX_FAVOURITES treatments to show on the Monitor home tile. Leave empty to auto-pick by best % change.",
                color = Color.White.copy(alpha = 0.62f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(top = 30.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFB97BFF))
                }
            } else if (active.isEmpty()) {
                Text(
                    "No active treatments yet. Add one to customize the card.",
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 30.dp)
                )
            } else {
                active.forEach { r ->
                    val isSelected = favourites.contains(r.id)
                    val atMax = favourites.size >= TREATMENT_CARD_MAX_FAVOURITES
                    val orderIndex = favourites.indexOf(r.id)
                    val opacity = if (!isSelected && atMax) 0.4f else 1f
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFF2A0C3C).copy(alpha = 0.65f))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                            .clickable(enabled = isSelected || !atMax) {
                                val next = if (isSelected) favourites.filterNot { it == r.id }
                                else favourites + r.id
                                favourites = next
                                saveTreatmentCardFavourites(context, next)
                            }
                            .padding(12.dp)
                            .alpha(opacity),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (isSelected) "●" else "○",
                            color = if (isSelected) Color(0xFFB97BFF) else Color.White.copy(alpha = 0.40f),
                            fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(r.name, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            val dose = listOfNotNull(r.amount, r.frequency).joinToString(" · ")
                            val sub = if (dose.isEmpty()) "since ${r.startDate}" else "$dose · since ${r.startDate}"
                            Text(sub, color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall)
                        }
                        if (isSelected && orderIndex >= 0) {
                            Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFB97BFF).copy(alpha = 0.30f)) {
                                Text(
                                    "${orderIndex + 1}",
                                    color = Color.White, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorTreatmentsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var active by remember { mutableStateOf<List<TreatmentListRowItem>>(emptyList()) }
    var past by remember { mutableStateOf<List<TreatmentListRowItem>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading = true
        val (regimens, lb) = withContext(Dispatchers.IO) {
            try {
                val token = SessionStore.getValidAccessToken(context)
                    ?: return@withContext emptyList<SupabaseDbService.TreatmentRegimenRow>() to emptyList<SupabaseDbService.TreatmentLeaderboardRow>()
                val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                db.getTreatmentRegimens(token) to runCatching { db.getTreatmentLeaderboard(token) }.getOrDefault(emptyList())
            } catch (_: Throwable) {
                emptyList<SupabaseDbService.TreatmentRegimenRow>() to emptyList<SupabaseDbService.TreatmentLeaderboardRow>()
            }
        }
        val lbById = lb.associateBy { it.regimenId }

        // Group by groupId; null → solo
        val grouped = regimens.groupBy { it.groupId }
        val solos = grouped[null] ?: emptyList()
        val groupedKeys = grouped.keys.filterNotNull()

        // Fetch group efficacy in parallel
        val groupEffById = withContext(Dispatchers.IO) {
            val token = SessionStore.getValidAccessToken(context)
            if (token == null) emptyMap<String, SupabaseDbService.TreatmentGroupEfficacyRow>() else {
                val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                groupedKeys.associateWith { gid ->
                    runCatching { db.getTreatmentGroupEfficacy(token, gid) }.getOrNull()
                }.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
            }
        }

        val items = mutableListOf<TreatmentListRowItem>()

        // Solos
        for (r in solos) {
            val l = lbById[r.id]
            items.add(TreatmentListRowItem(
                id = r.id, isGroup = false,
                displayName = r.name,
                metaLine = soloMeta(r),
                isActive = r.stopDate == null,
                pctChange = l?.pctChangeMmd, band = l?.band ?: "not_enough_data",
                navigateRegimenId = r.id
            ))
        }
        // Groups
        for ((gid, members) in grouped) {
            if (gid == null) continue
            val sorted = members.sortedByDescending { it.startDate }
            val navTarget = sorted.firstOrNull()?.id ?: gid
            val anyActive = members.any { it.stopDate == null }
            val ge = groupEffById[gid]
            val names = members.map { it.name }.distinct()
            val displayName = if (names.size == 1) names.first() else names.joinToString(" + ")
            items.add(TreatmentListRowItem(
                id = "group:$gid", isGroup = true,
                displayName = displayName,
                metaLine = groupMeta(members, ge),
                isActive = anyActive,
                pctChange = ge?.pctChangeMmd, band = ge?.band ?: "not_enough_data",
                navigateRegimenId = navTarget
            ))
        }

        active = items.filter { it.isActive }
        past = items.filter { !it.isActive }
        loading = false
    }

    val premiumState by PremiumManager.state.collectAsState()
    LaunchedEffect(premiumState.isPremium) { if (premiumState.isPremium) reload() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PremiumGate(
            message = "Unlock Treatments",
            subtitle = "Track how well each drug or lifestyle change reduces your migraine days",
            onUpgrade = { navController.navigate(Routes.PAYWALL) },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CustomizeTreatmentsHero(onClick = { navController.navigate("monitor_treatments_config") })
                AddTreatmentButton(onClick = { showAdd = true })

                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFFB97BFF))
                    }
                } else {
                    if (active.isNotEmpty()) {
                        SectionHeader("Active")
                        active.forEach { ListRow(it, navController) }
                    }
                    if (past.isNotEmpty()) {
                        SectionHeader("Past")
                        past.forEach { ListRow(it, navController) }
                    }
                    if (active.isEmpty() && past.isEmpty()) EmptyState()
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showAdd) {
        AddTreatmentRegimenDialog(
            onDismiss = { showAdd = false },
            onSaved = {
                showAdd = false
                scope.launch { reload() }
            }
        )
    }
}

@Composable
private fun CustomizeTreatmentsHero(onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF2A0C3C).copy(alpha = 0.78f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .clickable { onClick() }
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Tune, contentDescription = null, tint = Color(0xFFB97BFF), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Customize Treatments", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text("Pick up to 3 that appear on the Monitor card.", color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall)
        }
        Text("→", color = Color(0xFFB97BFF))
    }
}

@Composable
private fun AddTreatmentButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF2A0C3C).copy(alpha = 0.78f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .clickable { onClick() }
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, tint = Color(0xFFB97BFF), modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Add Treatment", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            Text("Drug or lifestyle.", color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall)
            Text("Track its effect on your migraines.", color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, color = Color(0xFFDCCEFF), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
}

data class TreatmentListRowItem(
    val id: String,
    val isGroup: Boolean,
    val displayName: String,
    val metaLine: String,
    val isActive: Boolean,
    val pctChange: Double?,
    val band: String,
    val navigateRegimenId: String
)

private fun soloMeta(r: SupabaseDbService.TreatmentRegimenRow): String {
    val parts = mutableListOf<String>()
    if (!r.amount.isNullOrEmpty()) parts.add(r.amount!!)
    if (!r.frequency.isNullOrEmpty()) parts.add(r.frequency!!)
    parts.add(r.stopDate?.let { "${r.startDate} → $it" } ?: "since ${r.startDate}")
    return parts.joinToString(" · ")
}

private fun groupMeta(
    members: List<SupabaseDbService.TreatmentRegimenRow>,
    ge: SupabaseDbService.TreatmentGroupEfficacyRow?
): String {
    val parts = mutableListOf<String>()
    val names = members.map { it.name }.distinct()
    if (names.size == 1) {
        val amounts = members.mapNotNull { it.amount?.takeIf { a -> a.isNotEmpty() } }.distinct()
        if (amounts.isNotEmpty()) parts.add(amounts.joinToString(" + "))
    } else {
        parts.add("${members.size} linked")
    }
    if (ge != null) {
        parts.add(ge.latestStop?.let { "${ge.earliestStart} → $it" } ?: "since ${ge.earliestStart}")
    }
    return parts.joinToString(" · ")
}

@Composable
private fun ListRow(item: TreatmentListRowItem, navController: NavController) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF2A0C3C).copy(alpha = 0.65f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .clickable { navController.navigate("monitor_treatment_detail/${item.navigateRegimenId}") }
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            if (item.isGroup) Icons.Outlined.Link else Icons.Outlined.MedicalServices,
            contentDescription = null,
            tint = Color(0xFFB97BFF),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.displayName, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(item.metaLine, color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            val pctText = item.pctChange?.let { String.format("%+.0f%%", it) } ?: "N/A"
            val color = bandPctColor(item.band)
            Text(pctText, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = bandPillBg(item.band),
            ) {
                Text(bandPillLabel(item.band),
                    color = color, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun bandPillLabel(band: String): String = when (band) {
    "working_well" -> "working well"
    "showing_progress" -> "showing progress"
    "some_effect" -> "some effect"
    "not_noticeable" -> "not noticeable yet"
    else -> "not enough data"
}
private fun bandPctColor(band: String): Color = when (band) {
    "working_well" -> Color(0xFF6ED69E)
    "showing_progress" -> Color(0xFFFFB454)
    "some_effect" -> Color.White.copy(alpha = 0.86f)
    "not_noticeable" -> Color(0xFFE0492B)
    else -> Color.White.copy(alpha = 0.62f)
}
private fun bandPillBg(band: String): Color = when (band) {
    "working_well" -> Color(0xFF6ED69E).copy(alpha = 0.18f)
    "showing_progress" -> Color(0xFFFFB454).copy(alpha = 0.18f)
    "some_effect" -> Color.White.copy(alpha = 0.06f)
    "not_noticeable" -> Color(0xFFE0492B).copy(alpha = 0.18f)
    else -> Color.White.copy(alpha = 0.06f)
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No treatments yet", color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Text("Tap Add Treatment to start tracking a drug or lifestyle change.", color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall)
    }
}

private fun doseLabel(r: SupabaseDbService.TreatmentRegimenRow): String? {
    val parts = listOfNotNull(r.amount?.takeIf { it.isNotBlank() }, r.frequency?.takeIf { it.isNotBlank() })
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

private fun periodLabel(r: SupabaseDbService.TreatmentRegimenRow): String {
    val start = r.startDate.take(10)
    return r.stopDate?.let { "$start → ${it.take(10)}" } ?: "Since $start"
}

// ════════════════════════════════════════════════════════════════════════════
// Entry card for Monitor home — shows active regimen list with bars & %
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun TreatmentsMonitorCard(onClick: () -> Unit) {
    val context = LocalContext.current
    val pState by PremiumManager.state.collectAsState()
    val isPremium = pState.isPremium
    var loading by remember { mutableStateOf(true) }
    var rows by remember { mutableStateOf<List<SupabaseDbService.TreatmentLeaderboardRow>>(emptyList()) }
    var activeCount by remember { mutableStateOf(0) }
    var showInfo by remember { mutableStateOf(false) }
    LaunchedEffect(isPremium) {
        if (!isPremium) { loading = false; return@LaunchedEffect }
        loading = true
        val data = withContext(Dispatchers.IO) {
            try {
                val token = SessionStore.getValidAccessToken(context) ?: return@withContext emptyList()
                val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                db.getTreatmentLeaderboard(token)
            } catch (_: Throwable) { emptyList() }
        }
        val active = data.filter { it.stopDate == null }
        activeCount = active.size
        val favourites = loadTreatmentCardFavourites(context)
        val picked: List<SupabaseDbService.TreatmentLeaderboardRow> = if (favourites.isNotEmpty()) {
            val byId = active.associateBy { it.regimenId }
            favourites.mapNotNull { byId[it] }
        } else {
            active.sortedBy { it.pctChangeMmd ?: Double.POSITIVE_INFINITY }
        }
        rows = picked.take(TREATMENT_CARD_MAX_FAVOURITES)
        loading = false
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF2A0C3C).copy(alpha = 0.65f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
                .clickable(onClick = onClick)
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isPremium) Icons.Outlined.TrackChanges else Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = Color(0xFF4DD0E1),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text("Treatments", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                if (isPremium) {
                    Text("$activeCount active · →", color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.labelSmall)
                } else {
                    Text("Premium · →", color = Color(0xFFB97BFF), style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(10.dp))
            if (!isPremium) {
                Text("Track how well each drug or lifestyle change reduces your migraine days. Upgrade to unlock.",
                    color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall)
            } else if (loading) {
                CircularProgressIndicator(color = Color(0xFFB97BFF), modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            } else if (rows.isEmpty()) {
                Text("Add a drug or lifestyle change to track effectiveness.",
                    color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall)
            } else {
                rows.forEachIndexed { i, row ->
                    HomeRegimenRow(row)
                    if (i != rows.size - 1) Divider(color = Color.White.copy(alpha = 0.08f))
                }
            }
        }
        IconButton(
            onClick = { showInfo = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 10.dp, y = (-14).dp)
                .size(34.dp)
        ) {
            Icon(Icons.Outlined.Info, contentDescription = "About Treatments",
                tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(20.dp))
        }
    }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            containerColor = Color(0xFF2A0C3C),
            title = { Text("Treatments", color = Color(0xFFDCCEFF), fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "Track how well each drug or lifestyle change is reducing your migraine days. " +
                    "Every regimen is compared against your migraine days in the 28 days BEFORE it started, vs the average across all weeks on treatment after the initial ramp.\n\n" +
                    "The four bands (Not noticeable · Some effect · Showing progress · Working well) follow the clinical responder definition: " +
                    "a 50% or greater drop is the gold standard.\n\n" +
                    "Ramp-in (when the drug isn't fully active yet) is excluded so the reading is honest: 8 weeks for oral preventives, 4 weeks for CGRP mAbs or gepants, none for lifestyle changes. " +
                    "A secondary \"Last 4 weeks\" number on the detail screen shows whether things are trending up or down recently.\n\n" +
                    "A note on Link: linking treatments combines them into one shared % change. " +
                    "It's designed for CONSECUTIVE treatments or DOSE CHANGES of the same drug. " +
                    "If you link two you took at the same time, both rows will read the same number because the math compares against time, not which drug caused the change.\n\n" +
                    "Pick up to 3 to feature on this Monitor tile via Customize on the Treatments page.",
                    color = Color.White.copy(alpha = 0.86f),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Got it", color = Color(0xFFB97BFF)) } }
        )
    }
}

@Composable
private fun HomeRegimenRow(r: SupabaseDbService.TreatmentLeaderboardRow) {
    val weeks = try {
        val start = LocalDate.parse(r.startDate)
        java.time.temporal.ChronoUnit.WEEKS.between(start, LocalDate.now()).coerceAtLeast(0L).toInt()
    } catch (_: Throwable) { 0 }
    val band = r.band
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(r.name, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            val sub = listOfNotNull(r.amount, "$weeks wks").joinToString(" · ")
            Text(sub, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
        }
        if (band == "not_enough_data") {
            Text("not enough data", color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
        } else {
            val mag = r.pctChangeMmd?.let { minOf(Math.abs(it), 100.0) / 100.0 } ?: 0.0
            val color = bandPctColor(band)
            Box(modifier = Modifier.width(54.dp).height(5.dp)
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(3.dp))) {
                Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(mag.toFloat())
                    .background(color, RoundedCornerShape(3.dp)))
            }
            Spacer(Modifier.width(8.dp))
            Text(r.pctChangeMmd?.let { String.format("%+.0f%%", it) } ?: "—",
                color = color, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// Add dialog
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun AddTreatmentRegimenDialog(
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf("drug") }
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf(LocalDate.now()) }
    var notes by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        if (saving) return
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) { error = "Name is required"; return }
        saving = true; error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val token = SessionStore.getValidAccessToken(context) ?: error("Not signed in")
                    val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                    db.insertTreatmentRegimen(
                        accessToken = token,
                        kind = kind,
                        name = trimmedName,
                        amount = amount.ifBlank { null },
                        frequency = frequency.ifBlank { null },
                        startDate = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        notes = notes.ifBlank { null }
                    )
                    Result.success(Unit)
                } catch (t: Throwable) { Result.failure(t) }
            }
            saving = false
            result.onSuccess { onSaved() }
                .onFailure { error = it.message ?: "Save failed" }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = Color(0xFF2A0C3C),
        title = { Text("Add Treatment", color = Color(0xFFDCCEFF)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = kind == "drug", onClick = { kind = "drug" },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)) { Text("Drug") }
                    SegmentedButton(selected = kind == "lifestyle", onClick = { kind = "lifestyle" },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)) { Text("Lifestyle") }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text(if (kind == "drug") "e.g. Topiramate" else "e.g. Meditation") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it },
                    label = { Text("Amount") },
                    placeholder = { Text(if (kind == "drug") "e.g. 50mg" else "e.g. 20 min") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = frequency, onValueChange = { frequency = it },
                    label = { Text("Frequency") },
                    placeholder = { Text("e.g. daily, 2x per week") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("Start date", color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall)
                StartDatePicker(date = startDate, onChange = { startDate = it })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = !saving && name.trim().isNotEmpty()) {
                Text(if (saving) "Saving..." else "Save", color = Color(0xFFB97BFF))
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!saving) onDismiss() }) { Text("Cancel", color = Color.White.copy(alpha = 0.82f)) }
        }
    )
}

@Composable
private fun StartDatePicker(date: LocalDate, onChange: (LocalDate) -> Unit) {
    AndroidView(
        factory = { ctx ->
            DatePicker(ctx).apply {
                init(date.year, date.monthValue - 1, date.dayOfMonth) { _, y, m, d ->
                    onChange(LocalDate.of(y, m + 1, d))
                }
            }
        },
        update = { it.updateDate(date.year, date.monthValue - 1, date.dayOfMonth) },
        modifier = Modifier.fillMaxWidth()
    )
}
