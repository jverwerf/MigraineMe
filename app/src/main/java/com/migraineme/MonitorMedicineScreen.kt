package com.migraineme

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ════════════════════════════════════════════════════════════════════════════
// DATA + LOADER
// ════════════════════════════════════════════════════════════════════════════

data class DailyAmount(val date: String, val value: Double, val unit: String)

data class MedicineSummary(
    val all: List<MedicineSummaryEntry> = emptyList(),
    val categories: List<MedicineCategorySummary> = emptyList(),
    val dailyByMedicine: Map<String, List<DailyAmount>> = emptyMap(),
    val dailyByCategory: Map<String, List<DailyAmount>> = emptyMap(),
)

/**
 * Fetches medicine logs for the last 30 days from Supabase and aggregates:
 *  - per-medicine and per-category today/week/month AMOUNT sums
 *  - per-medicine and per-category 14-day daily series (dominant unit per item)
 */
suspend fun loadMedicineSummary(context: Context): MedicineSummary = withContext(Dispatchers.IO) {
    val token = SessionStore.getValidAccessToken(context) ?: return@withContext MedicineSummary()
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val key = BuildConfig.SUPABASE_ANON_KEY

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val monthAgo = today.minusDays(30)
    val monthAgoIso = monthAgo.atStartOfDay(zone).toOffsetDateTime().toString()

    val url = "$base/rest/v1/medicines" +
        "?select=name,category,amount,start_at" +
        "&start_at=gte.${java.net.URLEncoder.encode(monthAgoIso, "UTF-8")}" +
        "&order=start_at.desc&limit=2000"

    val client = OkHttpClient()
    val req = Request.Builder().url(url)
        .addHeader("apikey", key)
        .addHeader("Authorization", "Bearer $token")
        .addHeader("Accept", "application/json").build()
    val arr = try {
        val res = client.newCall(req).execute()
        JSONArray(res.body?.string() ?: "[]")
    } catch (_: Exception) { JSONArray() }

    // Pool lookup so any medicine logged without a category (legacy writes from
    // QuickLogMedicineScreen / LogViewModel) still renders under its pool category.
    val poolUrl = "$base/rest/v1/user_medicines?select=label,category"
    val poolReq = Request.Builder().url(poolUrl)
        .addHeader("apikey", key)
        .addHeader("Authorization", "Bearer $token")
        .addHeader("Accept", "application/json").build()
    val poolCategoryMap: Map<String, String> = try {
        val res = client.newCall(poolReq).execute()
        val pArr = JSONArray(res.body?.string() ?: "[]")
        buildMap {
            for (i in 0 until pArr.length()) {
                val po = pArr.optJSONObject(i) ?: continue
                val label = po.optString("label").takeIf { it.isNotBlank() } ?: continue
                val pcat = po.optString("category").takeIf { it.isNotBlank() && it != "null" } ?: continue
                put(label.lowercase(), pcat)
            }
        }
    } catch (_: Exception) { emptyMap() }

    val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    val sevenAgo = today.minusDays(7)
    val fourteenAgo = today.minusDays(13)

    data class Bucket(
        var category: String? = null,
        var logsToday: Int = 0, var logsWeek: Int = 0, var logsMonth: Int = 0,
        val amtToday: MutableList<Pair<Double, String>> = mutableListOf(),
        val amtWeek: MutableList<Pair<Double, String>> = mutableListOf(),
        val amtMonth: MutableList<Pair<Double, String>> = mutableListOf(),
        var unpToday: Int = 0, var unpWeek: Int = 0, var unpMonth: Int = 0,
    )

    val perName = mutableMapOf<String, Bucket>()
    val perCat = mutableMapOf<String, Bucket>()
    val perNameDailyUnit = mutableMapOf<String, MutableMap<String, MutableMap<String, Double>>>()
    val perCatDailyUnit  = mutableMapOf<String, MutableMap<String, MutableMap<String, Double>>>()

    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val name = o.optString("name").takeIf { it.isNotBlank() } ?: continue
        // Filter out the literal string "null" as well as JSON null / blank.
        val rawCat = o.optString("category").takeIf { it.isNotBlank() && it != "null" }
        val cat = rawCat ?: poolCategoryMap[name.lowercase()] ?: "Other"
        val amt = o.optString("amount").takeIf { it.isNotBlank() }
        val startStr = o.optString("start_at").takeIf { it.isNotBlank() } ?: continue
        val parsed = parseMedicineAmount(amt)
        val instant = try { OffsetDateTime.parse(startStr, fmt) } catch (_: Exception) { continue }
        val date = instant.atZoneSameInstant(zone).toLocalDate()
        val dayKey = date.toString()

        val nb = perName.getOrPut(name) { Bucket() }
        if (nb.category == null) nb.category = cat
        nb.logsMonth += 1
        if (parsed != null) nb.amtMonth.add(parsed) else if (amt != null) nb.unpMonth += 1
        if (!date.isBefore(sevenAgo)) {
            nb.logsWeek += 1
            if (parsed != null) nb.amtWeek.add(parsed) else if (amt != null) nb.unpWeek += 1
        }
        if (date == today) {
            nb.logsToday += 1
            if (parsed != null) nb.amtToday.add(parsed) else if (amt != null) nb.unpToday += 1
        }
        if (cat != null) {
            val cb = perCat.getOrPut(cat) { Bucket() }
            cb.logsMonth += 1
            if (parsed != null) cb.amtMonth.add(parsed) else if (amt != null) cb.unpMonth += 1
            if (!date.isBefore(sevenAgo)) {
                cb.logsWeek += 1
                if (parsed != null) cb.amtWeek.add(parsed) else if (amt != null) cb.unpWeek += 1
            }
            if (date == today) {
                cb.logsToday += 1
                if (parsed != null) cb.amtToday.add(parsed) else if (amt != null) cb.unpToday += 1
            }
        }
        if (parsed != null && !date.isBefore(fourteenAgo)) {
            perNameDailyUnit
                .getOrPut(name) { mutableMapOf() }
                .getOrPut(dayKey) { mutableMapOf() }
                .merge(parsed.second, parsed.first) { a, b -> a + b }
            if (cat != null) {
                perCatDailyUnit
                    .getOrPut(cat) { mutableMapOf() }
                    .getOrPut(dayKey) { mutableMapOf() }
                    .merge(parsed.second, parsed.first) { a, b -> a + b }
            }
        }
    }

    val allEntries = perName.entries.map { (n, b) ->
        MedicineSummaryEntry(
            name = n, category = b.category,
            amountToday = formatAmountSum(b.amtToday, b.unpToday),
            amountWeek  = formatAmountSum(b.amtWeek,  b.unpWeek),
            amountMonth = formatAmountSum(b.amtMonth, b.unpMonth),
            logsToday = b.logsToday, logsWeek = b.logsWeek, logsMonth = b.logsMonth,
        )
    }.sortedWith(compareByDescending<MedicineSummaryEntry> { it.logsMonth }
        .thenByDescending { it.logsWeek }
        .thenByDescending { it.logsToday })

    val allCats = perCat.entries.map { (c, b) ->
        MedicineCategorySummary(
            category = c,
            amountToday = formatAmountSum(b.amtToday, b.unpToday),
            amountWeek  = formatAmountSum(b.amtWeek,  b.unpWeek),
            amountMonth = formatAmountSum(b.amtMonth, b.unpMonth),
            logsToday = b.logsToday, logsWeek = b.logsWeek, logsMonth = b.logsMonth,
        )
    }.sortedWith(compareByDescending<MedicineCategorySummary> { it.logsMonth }
        .thenByDescending { it.logsWeek }
        .thenByDescending { it.logsToday })

    fun buildSeries(src: Map<String, Map<String, Map<String, Double>>>): Map<String, List<DailyAmount>> {
        val out = mutableMapOf<String, List<DailyAmount>>()
        for ((name, byDayUnit) in src) {
            val unitTotals = mutableMapOf<String, Double>()
            for ((_, units) in byDayUnit) for ((u, v) in units) unitTotals.merge(u, v) { a, b -> a + b }
            val dominantUnit = unitTotals.maxByOrNull { it.value }?.key ?: "mg"
            val pts = mutableListOf<DailyAmount>()
            for (i in 13 downTo 0) {
                val d = today.minusDays(i.toLong())
                val s = d.toString()
                val v = byDayUnit[s]?.get(dominantUnit) ?: 0.0
                pts.add(DailyAmount(s, v, dominantUnit))
            }
            out[name] = pts
        }
        return out
    }

    MedicineSummary(
        all = allEntries,
        categories = allCats,
        dailyByMedicine = buildSeries(perNameDailyUnit),
        dailyByCategory = buildSeries(perCatDailyUnit),
    )
}

/**
 * Range-aware medicine loader for the full-screen graph. Returns daily totals
 * (and a flat list of distinct medicine + category names) for the requested
 * inclusive window [endDate - (days-1), endDate].
 */
data class MedicineGraphData(
    val medicineNames: List<String>,
    val categoryNames: List<String>,
    val dailyByMedicine: Map<String, List<DailyAmount>>,
    val dailyByCategory: Map<String, List<DailyAmount>>,
)

suspend fun loadMedicineGraphData(
    context: Context,
    days: Int,
    endDate: LocalDate,
): MedicineGraphData = withContext(Dispatchers.IO) {
    val token = SessionStore.getValidAccessToken(context) ?: return@withContext MedicineGraphData(emptyList(), emptyList(), emptyMap(), emptyMap())
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val key = BuildConfig.SUPABASE_ANON_KEY

    val zone = ZoneId.systemDefault()
    val startDate = endDate.minusDays((days - 1).toLong())
    val startIso = startDate.atStartOfDay(zone).toOffsetDateTime().toString()
    val endIso = endDate.plusDays(1).atStartOfDay(zone).toOffsetDateTime().toString()

    val url = "$base/rest/v1/medicines" +
        "?select=name,category,amount,start_at" +
        "&start_at=gte.${java.net.URLEncoder.encode(startIso, "UTF-8")}" +
        "&start_at=lt.${java.net.URLEncoder.encode(endIso, "UTF-8")}" +
        "&order=start_at.desc&limit=4000"

    val client = OkHttpClient()
    val req = Request.Builder().url(url)
        .addHeader("apikey", key)
        .addHeader("Authorization", "Bearer $token")
        .addHeader("Accept", "application/json").build()
    val arr = try {
        val res = client.newCall(req).execute()
        JSONArray(res.body?.string() ?: "[]")
    } catch (_: Exception) { JSONArray() }

    val poolUrl = "$base/rest/v1/user_medicines?select=label,category"
    val poolReq = Request.Builder().url(poolUrl)
        .addHeader("apikey", key)
        .addHeader("Authorization", "Bearer $token")
        .addHeader("Accept", "application/json").build()
    val poolCategoryMap: Map<String, String> = try {
        val res = client.newCall(poolReq).execute()
        val pArr = JSONArray(res.body?.string() ?: "[]")
        buildMap {
            for (i in 0 until pArr.length()) {
                val po = pArr.optJSONObject(i) ?: continue
                val label = po.optString("label").takeIf { it.isNotBlank() } ?: continue
                val pcat = po.optString("category").takeIf { it.isNotBlank() && it != "null" } ?: continue
                put(label.lowercase(), pcat)
            }
        }
    } catch (_: Exception) { emptyMap() }

    val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    val perNameDailyUnit = mutableMapOf<String, MutableMap<String, MutableMap<String, Double>>>()
    val perCatDailyUnit = mutableMapOf<String, MutableMap<String, MutableMap<String, Double>>>()
    val nameToCat = mutableMapOf<String, String>()
    val catCounts = mutableMapOf<String, Int>()
    val nameCounts = mutableMapOf<String, Int>()

    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val name = o.optString("name").takeIf { it.isNotBlank() } ?: continue
        val rawCat = o.optString("category").takeIf { it.isNotBlank() && it != "null" }
        val cat = rawCat ?: poolCategoryMap[name.lowercase()] ?: "Other"
        val amt = o.optString("amount").takeIf { it.isNotBlank() }
        val startStr = o.optString("start_at").takeIf { it.isNotBlank() } ?: continue
        val parsed = parseMedicineAmount(amt) ?: continue
        val instant = try { OffsetDateTime.parse(startStr, fmt) } catch (_: Exception) { continue }
        val date = instant.atZoneSameInstant(zone).toLocalDate()
        if (date.isBefore(startDate) || date.isAfter(endDate)) continue
        val dayKey = date.toString()

        nameToCat[name] = cat
        nameCounts.merge(name, 1) { a, b -> a + b }
        catCounts.merge(cat, 1) { a, b -> a + b }

        perNameDailyUnit
            .getOrPut(name) { mutableMapOf() }
            .getOrPut(dayKey) { mutableMapOf() }
            .merge(parsed.second, parsed.first) { a, b -> a + b }
        perCatDailyUnit
            .getOrPut(cat) { mutableMapOf() }
            .getOrPut(dayKey) { mutableMapOf() }
            .merge(parsed.second, parsed.first) { a, b -> a + b }
    }

    fun buildSeries(src: Map<String, Map<String, Map<String, Double>>>): Map<String, List<DailyAmount>> {
        val out = mutableMapOf<String, List<DailyAmount>>()
        for ((name, byDayUnit) in src) {
            val unitTotals = mutableMapOf<String, Double>()
            for ((_, units) in byDayUnit) for ((u, v) in units) unitTotals.merge(u, v) { a, b -> a + b }
            val dominantUnit = unitTotals.maxByOrNull { it.value }?.key ?: "mg"
            val pts = mutableListOf<DailyAmount>()
            for (i in (days - 1) downTo 0) {
                val d = endDate.minusDays(i.toLong())
                val s = d.toString()
                val v = byDayUnit[s]?.get(dominantUnit) ?: 0.0
                pts.add(DailyAmount(s, v, dominantUnit))
            }
            out[name] = pts
        }
        return out
    }

    val medicineNames = nameCounts.entries.sortedByDescending { it.value }.map { it.key }
    val categoryNames = catCounts.entries.sortedByDescending { it.value }.map { it.key }

    MedicineGraphData(
        medicineNames = medicineNames,
        categoryNames = categoryNames,
        dailyByMedicine = buildSeries(perNameDailyUnit),
        dailyByCategory = buildSeries(perCatDailyUnit),
    )
}

// ════════════════════════════════════════════════════════════════════════════
// MAIN MONITOR CARD — used in MonitorScreen.kt's card switch
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun MonitorMedicineCard(summary: MedicineSummary, isLoading: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val accent = Color(0xFF4FC3F7)
    var showInfo by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        BaseCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MedicalServices, contentDescription = null,
                    tint = accent, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text("Medicines", color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f))
                Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))

            when {
                isLoading -> Text("Loading…", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
                summary.all.isEmpty() -> Text("No medicines logged in the last 30 days",
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
                else -> {
                    val favs = MedicineCardConfig.loadFavourites(ctx)
                    val slots = resolveMedicineCardSlots(favourites = favs, allEntries = summary.all)
                    MedicineDwmGrid(
                        medicines = slots,
                        categories = summary.categories.take(4),
                    )
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
            Icon(Icons.Outlined.Info, contentDescription = "About Medicines",
                tint = AppTheme.SubtleTextColor, modifier = Modifier.size(20.dp))
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Got it", color = AppTheme.AccentPurple) } },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.MedicalServices, contentDescription = null,
                        tint = accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("About Medicines", color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                }
            },
            text = {
                Text(
                    "Your medicine usage over the last 30 days. Each row shows a medicine name + the " +
                    "category you've set on it (if any), with three numbers on the right: Day (today), " +
                    "Week (last 7d) and Month (last 30d) totals in the units you logged — e.g. 1200mg, " +
                    "2×, or 1200mg + if you mixed units. \"—\" means nothing logged in that window.\n\n" +
                    "Which medicines appear here is driven by what you've favourited in Customize; if " +
                    "you haven't picked favourites, the most-logged medicines from the past week show " +
                    "up. Below those, the most-used categories appear as their own D/W/M totals.\n\n" +
                    "Tap to open the detail screen with: Customize Medicines (pick favourites and " +
                    "highlighted categories), a full breakdown across every medicine and category, " +
                    "and a 14-day stacked-bar history chart you can filter by medicine or category.",
                    color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium
                )
            },
            containerColor = Color(0xFF1E0A2E)
        )
    }
}

// ════════════════════════════════════════════════════════════════════════════
// DETAIL SUB-SCREEN
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun MonitorMedicineScreen(navController: NavController, authVm: AuthViewModel = viewModel()) {
    val ctx = LocalContext.current.applicationContext
    val scrollState = rememberScrollState()
    var summary by remember { mutableStateOf<MedicineSummary?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val chartSelected = remember { mutableStateListOf<String>() }
    var chartInit by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        summary = loadMedicineSummary(ctx)
        isLoading = false
        if (!chartInit && summary != null) {
            val favs = MedicineCardConfig.loadFavourites(ctx)
            val cats = MedicineCardConfig.run { ctx.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
                .getString("medicine_card_config_categories", null)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList() }
            val seed = favs + cats
            chartSelected.addAll(if (seed.isNotEmpty()) seed else summary!!.all.take(3).map { it.name })
            chartInit = true
        }
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            // 1. Customize hero card
            BaseCard(modifier = Modifier.clickable { navController.navigate(Routes.MEDICINE_CONFIG) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, contentDescription = null,
                        tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Customize Medicines", color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Text("Pick which medicines appear as favourites on the card",
                            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    }
                    Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
                }
            }

            val s = summary
            when {
                isLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = AppTheme.AccentPurple)
                }
                s == null || s.all.isEmpty() -> {
                    BaseCard {
                        Text("No medicines logged in the last 30 days",
                            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
                        Text("Log medicines on the Log screen to see them here",
                            color = AppTheme.AccentPurple, style = MaterialTheme.typography.labelMedium)
                    }
                }
                else -> {
                    // 2. Combined breakdown (per medicine + per category in one card)
                    BaseCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Breakdown", color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.weight(1f))
                            val pState by PremiumManager.state.collectAsState()
                            if (pState.isPremium) {
                                Text("History →", color = AppTheme.AccentPurple,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.clickable { navController.navigate(Routes.MEDICINE_DATA_HISTORY) })
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Lock, contentDescription = null,
                                        tint = AppTheme.AccentPurple, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text("History", color = AppTheme.AccentPurple,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        MedicineDwmGrid(
                            medicines = s.all,
                            categories = s.categories,
                            medicineLabel = "By medicine",
                        )
                    }

                    // 3. 14-day stacked histogram with chip selector (chips below graph)
                    BaseCard(modifier = Modifier.clickable { navController.navigate(Routes.FULL_GRAPH_MEDICINES) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("14-Day Medicines History", color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.weight(1f))
                            Text("History →", color = AppTheme.AccentPurple,
                                style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        val merged = s.dailyByMedicine + s.dailyByCategory
                        val medNames = s.all.map { it.name }
                        val catNames = s.categories.map { it.category }
                        MedicineStackedBarGraph(
                            chartSelected.toList(), merged, Modifier.fillMaxWidth().height(160.dp),
                            colorForName = { stableMedicineColor(it, medNames, catNames) }
                        )
                        Spacer(Modifier.height(8.dp))
                        if (s.all.isNotEmpty()) {
                            Text("Medicines", color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelSmall)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                s.all.forEach { m -> ChartChip(m.name, stableMedicineColor(m.name, medNames, catNames), chartSelected) }
                            }
                        }
                        if (s.categories.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("Categories", color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelSmall)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                s.categories.forEach { c -> ChartChip(c.category, stableMedicineColor(c.category, medNames, catNames), chartSelected) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// SHARED ROW + LEGEND (used by main card + sub-screen)
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun MedicineDWMLegend() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.04f))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text("D", color = Color(0xFFFFB74D),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
            Text("W", color = Color(0xFF4FC3F7),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
            Text("M", color = Color(0xFF81C784),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
        }
    }
}

// Fixed column widths so D/W/M values + headers line up regardless of whether
// a category pill is present on a given row.
private val MEDICINE_VALUE_COL_WIDTH = 56.dp

/**
 * Aligned grid: shared column widths across the medicines header+rows and the
 * categories header+rows. Uses a custom Layout so every value column has the same
 * width = max(width of every value+header in that column), and every header letter
 * (D/W/M) sits directly above the right edge of its column values.
 */
@Composable
private fun MedicineDwmGrid(
    medicines: List<MedicineSummaryEntry>,
    categories: List<MedicineCategorySummary>,
    medicineLabel: String? = null,
) {
    val colorD = Color(0xFFFFB74D)
    val colorW = Color(0xFF4FC3F7)
    val colorM = Color(0xFF81C784)
    val colors = listOf(colorD, colorW, colorM)
    val headerStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
    val valueStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
    val nameStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    val sectionStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
    val colGap = 10.dp

    data class Row3(val name: String?, val category: String?, val values: List<String>, val isHeader: Boolean)
    val rows: List<Pair<String?, Row3>> = buildList {
        if (medicineLabel != null) add(medicineLabel to Row3(null, null, listOf("D", "W", "M"), true))
        else add(null to Row3(null, null, listOf("D", "W", "M"), true))
        medicines.forEach { m ->
            add(null to Row3(m.name, m.category, listOf(m.amountToday, m.amountWeek, m.amountMonth), false))
        }
        if (categories.isNotEmpty()) {
            add("By category" to Row3(null, null, listOf("D", "W", "M"), true))
            categories.forEach { c ->
                add(null to Row3(c.category, null, listOf(c.amountToday, c.amountWeek, c.amountMonth), false))
            }
        }
    }

    // Custom Layout: compose every cell, then for each row use a fixed column width
    // equal to the max measured width of that column across all rows.
    Layout(
        content = {
            rows.forEach { (section, row) ->
                // section label cell (one per row; empty if none)
                if (section != null) {
                    Text(section, color = AppTheme.SubtleTextColor, style = sectionStyle,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                } else {
                    Spacer(Modifier.height(0.dp))
                }
                // name + optional category pill, packaged as one box
                androidx.compose.foundation.layout.Box {
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        if (row.name != null) {
                            Text(row.name, color = Color.White, style = nameStyle, maxLines = 1)
                            if (!row.category.isNullOrBlank()) {
                                Spacer(Modifier.width(4.dp))
                                Text(prettyLabel(row.category), color = AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.06f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp))
                            }
                        }
                    }
                }
                // 3 value cells (D, W, M)
                val style = if (row.isHeader) headerStyle else valueStyle
                row.values.forEachIndexed { i, v ->
                    Text(v, color = colors[i], style = style, maxLines = 1)
                }
            }
        }
    ) { measurables, constraints ->
        val cellsPerRow = 5 // section, name, val0, val1, val2
        val rowCount = rows.size
        val gapPx = colGap.roundToPx()
        val rowVSpacing = 2.dp.roundToPx()

        // Measure value cells (cols 2,3,4) at unconstrained width to find natural widths
        val unbounded = Constraints()
        val valueMeasures = Array(rowCount) { r ->
            (0 until 3).map { c ->
                measurables[r * cellsPerRow + 2 + c].measure(unbounded)
            }
        }
        // Per-column max width
        val colWidths = IntArray(3)
        for (r in 0 until rowCount) {
            for (c in 0 until 3) {
                if (valueMeasures[r][c].width > colWidths[c]) colWidths[c] = valueMeasures[r][c].width
            }
        }
        val trailingBlock = colWidths.sum() + gapPx * 2

        // Measure section labels (full width)
        val sectionMeasures = Array(rowCount) { r ->
            measurables[r * cellsPerRow].measure(Constraints(maxWidth = constraints.maxWidth))
        }
        // Measure name+pill: at most (parentWidth - trailingBlock)
        val nameMaxWidth = (constraints.maxWidth - trailingBlock).coerceAtLeast(0)
        val nameMeasures = Array(rowCount) { r ->
            measurables[r * cellsPerRow + 1].measure(
                Constraints(maxWidth = nameMaxWidth)
            )
        }

        // Compute total height
        var totalH = 0
        val rowHeights = IntArray(rowCount)
        for (r in 0 until rowCount) {
            val sectH = sectionMeasures[r].height
            val rowH = maxOf(
                nameMeasures[r].height,
                valueMeasures[r].maxOf { it.height }
            )
            rowHeights[r] = sectH + rowH + rowVSpacing
            totalH += rowHeights[r]
        }

        layout(constraints.maxWidth, totalH) {
            var y = 0
            for (r in 0 until rowCount) {
                // section label at full left
                if (sectionMeasures[r].height > 0) {
                    sectionMeasures[r].place(0, y)
                    y += sectionMeasures[r].height
                }
                val rowH = maxOf(
                    nameMeasures[r].height,
                    valueMeasures[r].maxOf { it.height }
                )
                // name at left, vertically centered in row
                val nameY = y + (rowH - nameMeasures[r].height) / 2
                nameMeasures[r].place(0, nameY)
                // value cells right-aligned within their fixed column widths
                var xRight = constraints.maxWidth
                for (c in 2 downTo 0) {
                    val cellMeasure = valueMeasures[r][c]
                    val colW = colWidths[c]
                    val cellX = xRight - colW + (colW - cellMeasure.width) // right-align in column
                    val cellY = y + (rowH - cellMeasure.height) / 2
                    cellMeasure.place(cellX, cellY)
                    xRight -= colW
                    if (c > 0) xRight -= gapPx
                }
                y += rowH + rowVSpacing
            }
        }
    }
}

@Composable
fun MedicineDWMHeaderRow() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        Text("D", color = Color(0xFFFFB74D),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.End,
            modifier = Modifier.width(MEDICINE_VALUE_COL_WIDTH))
        Text("W", color = Color(0xFF4FC3F7),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.End,
            modifier = Modifier.width(MEDICINE_VALUE_COL_WIDTH))
        Text("M", color = Color(0xFF81C784),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.End,
            modifier = Modifier.width(MEDICINE_VALUE_COL_WIDTH))
    }
}

@Composable
fun MedicineDWMRow(name: String, category: String?, today: String, week: String, month: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        if (!category.isNullOrBlank()) {
            Spacer(Modifier.width(4.dp))
            Text(category, color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 5.dp, vertical = 1.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(today, color = Color(0xFFFFB74D),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.End, maxLines = 1,
            modifier = Modifier.width(MEDICINE_VALUE_COL_WIDTH))
        Text(week, color = Color(0xFF4FC3F7),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.End, maxLines = 1,
            modifier = Modifier.width(MEDICINE_VALUE_COL_WIDTH))
        Text(month, color = Color(0xFF81C784),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.End, maxLines = 1,
            modifier = Modifier.width(MEDICINE_VALUE_COL_WIDTH))
    }
}

// Shared palette so chip + graph use the same colour for each medicine/category.
// Index is stable per label (position in the medicines / categories list), so
// re-ordering or de-selecting in the chart doesn't shuffle the chip colours.
private val MEDICINE_CHART_PALETTE = listOf(
    Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784),
    Color(0xFFBA68C8), Color(0xFFFF8A65), Color(0xFF7986CB),
)

private fun stableMedicineColor(label: String, medicines: List<String>, categories: List<String>): Color {
    val mIdx = medicines.indexOf(label)
    if (mIdx >= 0) return MEDICINE_CHART_PALETTE[mIdx % MEDICINE_CHART_PALETTE.size]
    val cIdx = categories.indexOf(label)
    if (cIdx >= 0) return MEDICINE_CHART_PALETTE[(cIdx + medicines.size) % MEDICINE_CHART_PALETTE.size]
    return Color(0xFF999999)
}

private fun medicineChartColor(label: String, selected: List<String>): Color? {
    val idx = selected.indexOf(label)
    return if (idx < 0) null else MEDICINE_CHART_PALETTE[idx % MEDICINE_CHART_PALETTE.size]
}

@Composable
private fun ChartChip(label: String, color: Color, selected: SnapshotStateList<String>) {
    val isSel = label in selected
    val seriesColor = medicineChartColor(label, selected) ?: color
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (isSel) seriesColor.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.06f))
            .clickable { if (isSel) selected.remove(label) else selected.add(label) }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        if (isSel) {
            Text("✓ ", color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        Text(label,
            color = if (isSel) Color.White else AppTheme.BodyTextColor,
            style = MaterialTheme.typography.labelSmall)
    }
}

// ════════════════════════════════════════════════════════════════════════════
// STACKED BAR GRAPH
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun MedicineStackedBarGraph(
    selected: List<String>,
    seriesPerItem: Map<String, List<DailyAmount>>,
    modifier: Modifier = Modifier,
    colorForName: ((String) -> Color)? = null,
) {
    val palette = MEDICINE_CHART_PALETTE
    val resolved = selected.mapIndexedNotNull { i, name ->
        val pts = seriesPerItem[name] ?: return@mapIndexedNotNull null
        if (pts.isEmpty()) return@mapIndexedNotNull null
        val color = colorForName?.invoke(name) ?: palette[i % palette.size]
        Triple(name, color, pts)
    }
    if (resolved.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Pick a medicine or category to chart",
                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
        }
        return
    }
    val days = resolved.first().third.map { it.date }
    val maxStacked = days.indices.maxOf { i ->
        resolved.sumOf { it.third.getOrNull(i)?.value ?: 0.0 }
    }.coerceAtLeast(1.0)

    Column(modifier) {
        // Legend
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            resolved.forEach { (name, color, pts) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
                    Spacer(Modifier.width(4.dp))
                    Text(name, color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall)
                    pts.firstOrNull()?.unit?.let {
                        Text(" ($it)", color = AppTheme.SubtleTextColor.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.weight(1f).fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            days.forEachIndexed { idx, _ ->
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter) {
                        Column(
                            Modifier.fillMaxWidth().fillMaxHeight(),
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            // Stack from bottom up (last in list is bottom)
                            resolved.asReversed().forEach { (_, color, pts) ->
                                val v = pts.getOrNull(idx)?.value ?: 0.0
                                if (v > 0) {
                                    val frac = (v / maxStacked).toFloat().coerceIn(0f, 1f)
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(frac, fill = false)
                                            .height((frac * 120).dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(color.copy(alpha = 0.85f))
                                    )
                                    Spacer(Modifier.height(1.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        // X-axis: only first + last date labels (MMM d), matching the other
        // history graphs (Physical, Sleep, etc).
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(monthDayLabel(days.first()), color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
            Text(monthDayLabel(days.last()), color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
        }
    }
}

private fun monthDayLabel(iso: String): String = try {
    val d = LocalDate.parse(iso)
    "${d.month.name.lowercase().replaceFirstChar { it.titlecase() }.take(3)} ${d.dayOfMonth}"
} catch (_: Exception) { "" }

private fun dayLabel(iso: String): String = try {
    LocalDate.parse(iso).dayOfMonth.toString()
} catch (_: Exception) { "" }

// ════════════════════════════════════════════════════════════════════════════
// CUSTOMIZE SCREEN
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun MonitorMedicineConfigScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val scrollState = rememberScrollState()
    var pool by remember { mutableStateOf<List<UserMedicineRow>>(emptyList()) }
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    val selectedMeds = remember { mutableStateListOf<String>().apply { addAll(MedicineCardConfig.loadFavourites(ctx)) } }
    val selectedCats = remember {
        val saved = ctx.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
            .getString("medicine_card_config_categories", null)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        mutableStateListOf<String>().apply { addAll(saved) }
    }

    LaunchedEffect(Unit) {
        pool = withContext(Dispatchers.IO) { loadUserMedicinesPool(ctx) }
        categories = pool.mapNotNull { it.category }.distinct().sorted()
    }

    fun persistMeds() {
        MedicineCardConfig.saveFavourites(ctx, selectedMeds.toList())
    }
    fun persistCats() {
        ctx.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE).edit()
            .putString("medicine_card_config_categories", selectedCats.joinToString("\n")).apply()
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
            BaseCard {
                Text("Pick medicines and categories to prioritize on the Monitor card and chart.",
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
            }
            if (pool.isNotEmpty()) {
                BaseCard {
                    Text("Medicines", color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(6.dp))
                    pool.forEach { m ->
                        val isSel = m.label in selectedMeds
                        Row(Modifier.fillMaxWidth()
                            .clickable {
                                if (isSel) selectedMeds.remove(m.label) else selectedMeds.add(m.label)
                                persistMeds()
                            }
                            .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(18.dp).clip(CircleShape)
                                .background(if (isSel) Color(0xFF4FC3F7) else Color.White.copy(alpha = 0.08f)))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.label, color = Color.White,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                                m.category?.let {
                                    Text(it, color = AppTheme.SubtleTextColor,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            if (categories.isNotEmpty()) {
                BaseCard {
                    Text("Categories", color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(6.dp))
                    categories.forEach { c ->
                        val isSel = c in selectedCats
                        Row(Modifier.fillMaxWidth()
                            .clickable {
                                if (isSel) selectedCats.remove(c) else selectedCats.add(c)
                                persistCats()
                            }
                            .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(18.dp).clip(CircleShape)
                                .background(if (isSel) Color(0xFF81C784) else Color.White.copy(alpha = 0.08f)))
                            Spacer(Modifier.width(10.dp))
                            Text(c, color = Color.White,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                        }
                    }
                }
            }
        }
    }
}

private suspend fun loadUserMedicinesPool(context: Context): List<UserMedicineRow> = withContext(Dispatchers.IO) {
    val token = SessionStore.getValidAccessToken(context) ?: return@withContext emptyList()
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val key = BuildConfig.SUPABASE_ANON_KEY
    val url = "$base/rest/v1/user_medicines?select=id,label,category&order=label.asc&limit=500"
    val req = Request.Builder().url(url)
        .addHeader("apikey", key)
        .addHeader("Authorization", "Bearer $token")
        .addHeader("Accept", "application/json").build()
    val arr = try { JSONArray(OkHttpClient().newCall(req).execute().body?.string() ?: "[]") } catch (_: Exception) { JSONArray() }
    val out = mutableListOf<UserMedicineRow>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(UserMedicineRow(
            id = o.optString("id"),
            label = o.optString("label"),
            category = o.optString("category").takeIf { it.isNotBlank() }
        ))
    }
    out
}

data class UserMedicineRow(val id: String, val label: String, val category: String?)

// ════════════════════════════════════════════════════════════════════════════
// PER-DATE DATA HISTORY SCREEN
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun MedicineDataHistoryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val scrollState = rememberScrollState()
    var selectedDate by remember { mutableStateOf(LocalDate.now(ZoneId.systemDefault())) }
    var entries by remember { mutableStateOf<List<MedicineLogRow>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(selectedDate) {
        isLoading = true
        entries = withContext(Dispatchers.IO) { loadMedicinesForDate(ctx, selectedDate) }
        isLoading = false
    }

    val categoryTotals = remember(entries) {
        val byCat = mutableMapOf<String, MutableList<Pair<Double, String>>>()
        val byCatUnp = mutableMapOf<String, Int>()
        for (e in entries) {
            val c = e.category ?: continue
            val p = parseMedicineAmount(e.amount)
            if (p != null) byCat.getOrPut(c) { mutableListOf() }.add(p)
            else if (!e.amount.isNullOrBlank()) byCatUnp.merge(c, 1) { a, b -> a + b }
        }
        (byCat.keys + byCatUnp.keys).toSet().sorted().map { cat ->
            cat to formatAmountSum(byCat[cat] ?: emptyList(), byCatUnp[cat] ?: 0)
        }
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
            BaseCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedDate = selectedDate.minusDays(1) }) {
                        Icon(Icons.Outlined.ChevronLeft, "Previous day", tint = AppTheme.AccentPurple)
                    }
                    Text(selectedDate.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")),
                        color = AppTheme.TitleColor, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = {
                        if (selectedDate < LocalDate.now(ZoneId.systemDefault())) selectedDate = selectedDate.plusDays(1)
                    }) {
                        Icon(Icons.Outlined.ChevronRight, "Next day", tint = AppTheme.AccentPurple)
                    }
                }
            }

            if (categoryTotals.isNotEmpty()) {
                BaseCard {
                    Text("By category", color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(4.dp))
                    categoryTotals.forEach { (cat, total) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(cat, color = Color.White, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f))
                            Text(total, color = Color(0xFF81C784),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                textAlign = TextAlign.End, maxLines = 1,
                                modifier = Modifier.width(MEDICINE_VALUE_COL_WIDTH))
                        }
                    }
                }
            }

            BaseCard {
                Text("Medicines Taken", color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(6.dp))
                when {
                    isLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                            color = AppTheme.AccentPurple)
                    }
                    entries.isEmpty() -> Text("No medicines logged on this date",
                        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
                    else -> entries.forEach { e ->
                        Column(Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.name ?: "Unknown", color = Color.White,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                                e.category?.let {
                                    Spacer(Modifier.width(4.dp))
                                    Text(it, color = AppTheme.SubtleTextColor,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.06f))
                                            .padding(horizontal = 5.dp, vertical = 1.dp))
                                }
                                Spacer(Modifier.weight(1f))
                                Text(e.timeOfDay, color = AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                e.amount?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, color = Color(0xFFFFB74D),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                                    Spacer(Modifier.width(8.dp))
                                }
                                e.reliefScale?.takeIf { it != "NONE" }?.let {
                                    Text("relief: ${it.lowercase().replaceFirstChar { c -> c.uppercase() }}",
                                        color = Color(0xFF81C784), style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.width(8.dp))
                                }
                                e.sideEffectScale?.takeIf { it != "NONE" }?.let {
                                    Text("SE: ${it.lowercase().replaceFirstChar { c -> c.uppercase() }}",
                                        color = Color(0xFFE57373), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            e.sideEffectNotes?.takeIf { it.isNotBlank() }?.let {
                                Text(it, color = AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

data class MedicineLogRow(
    val id: String, val name: String?, val category: String?, val amount: String?,
    val reliefScale: String?, val sideEffectScale: String?, val sideEffectNotes: String?,
    val timeOfDay: String,
)

private suspend fun loadMedicinesForDate(context: Context, date: LocalDate): List<MedicineLogRow> = withContext(Dispatchers.IO) {
    val token = SessionStore.getValidAccessToken(context) ?: return@withContext emptyList()
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val key = BuildConfig.SUPABASE_ANON_KEY
    val zone = ZoneId.systemDefault()
    val start = date.atStartOfDay(zone).toOffsetDateTime().toString()
    val end = date.plusDays(1).atStartOfDay(zone).toOffsetDateTime().toString()
    val url = "$base/rest/v1/medicines" +
        "?select=id,name,category,amount,relief_scale,side_effect_scale,side_effect_notes,start_at" +
        "&start_at=gte.${java.net.URLEncoder.encode(start, "UTF-8")}" +
        "&start_at=lt.${java.net.URLEncoder.encode(end, "UTF-8")}" +
        "&order=start_at.asc&limit=500"
    val req = Request.Builder().url(url)
        .addHeader("apikey", key)
        .addHeader("Authorization", "Bearer $token")
        .addHeader("Accept", "application/json").build()
    val client = OkHttpClient()
    val arr = try { JSONArray(client.newCall(req).execute().body?.string() ?: "[]") } catch (_: Exception) { JSONArray() }

    // Pool lookup so medicines logged with a null/"null" category still render
    // under their pool category in the day-by-day breakdown.
    val poolReq = Request.Builder().url("$base/rest/v1/user_medicines?select=label,category")
        .addHeader("apikey", key)
        .addHeader("Authorization", "Bearer $token")
        .addHeader("Accept", "application/json").build()
    val poolCategoryMap: Map<String, String> = try {
        val pArr = JSONArray(client.newCall(poolReq).execute().body?.string() ?: "[]")
        buildMap {
            for (i in 0 until pArr.length()) {
                val po = pArr.optJSONObject(i) ?: continue
                val label = po.optString("label").takeIf { it.isNotBlank() } ?: continue
                val pcat = po.optString("category").takeIf { it.isNotBlank() && it != "null" } ?: continue
                put(label.lowercase(), pcat)
            }
        }
    } catch (_: Exception) { emptyMap() }

    val out = mutableListOf<MedicineLogRow>()
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val startStr = o.optString("start_at")
        val timeOfDay = try {
            OffsetDateTime.parse(startStr).atZoneSameInstant(zone).toLocalTime().format(timeFmt)
        } catch (_: Exception) { "" }
        // Filter the literal string "null" everywhere — Supabase sometimes stores
        // it instead of a real NULL when the client serialises a null value.
        fun safe(field: String) = o.optString(field).takeIf { it.isNotBlank() && it != "null" }
        val name = safe("name")
        val rawCat = safe("category")
        val category = rawCat ?: name?.let { poolCategoryMap[it.lowercase()] } ?: "Other"
        out.add(MedicineLogRow(
            id = o.optString("id"),
            name = name,
            category = category,
            amount = safe("amount"),
            reliefScale = safe("relief_scale"),
            sideEffectScale = safe("side_effect_scale"),
            sideEffectNotes = safe("side_effect_notes"),
            timeOfDay = timeOfDay,
        ))
    }
    out
}

// ════════════════════════════════════════════════════════════════════════════
// MEDICINE HISTORY GRAPH — used inside FullScreenGraphScreen with range picker
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun MedicineHistoryGraph(
    days: Int = 14,
    endDate: LocalDate = LocalDate.now(),
) {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<MedicineGraphData?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val chartSelected = remember { mutableStateListOf<String>() }
    var lastInitKey by remember { mutableStateOf("") }

    LaunchedEffect(days, endDate) {
        isLoading = true
        data = loadMedicineGraphData(ctx, days, endDate)
        isLoading = false
        // Initialize chip selection once, seeding from user favourites/categories
        val initKey = "init"
        if (lastInitKey != initKey) {
            val favs = MedicineCardConfig.loadFavourites(ctx)
            val cats = ctx.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)
                .getString("medicine_card_config_categories", null)
                ?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
            val seed = (favs + cats).filter { it in (data?.medicineNames ?: emptyList()) || it in (data?.categoryNames ?: emptyList()) }
            chartSelected.clear()
            chartSelected.addAll(if (seed.isNotEmpty()) seed else (data?.medicineNames ?: emptyList()).take(3))
            lastInitKey = initKey
        }
    }

    BaseCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("$days-Day Medicines History", color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f))
            Text("Daily amount, stacked", color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(8.dp))

        if (isLoading) {
            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = AppTheme.AccentPurple)
            }
            return@BaseCard
        }
        val d = data
        if (d == null || (d.medicineNames.isEmpty() && d.categoryNames.isEmpty())) {
            Text("No medicines logged in this range",
                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp), textAlign = TextAlign.Center)
            return@BaseCard
        }

        val merged = d.dailyByMedicine + d.dailyByCategory
        MedicineStackedBarGraph(
            chartSelected.toList(), merged, Modifier.fillMaxWidth().height(180.dp),
            colorForName = { stableMedicineColor(it, d.medicineNames, d.categoryNames) }
        )

        Spacer(Modifier.height(10.dp))
        if (d.medicineNames.isNotEmpty()) {
            Text("Medicines", color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                d.medicineNames.forEach { n -> ChartChip(n, stableMedicineColor(n, d.medicineNames, d.categoryNames), chartSelected) }
            }
        }
        if (d.categoryNames.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Categories", color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                d.categoryNames.forEach { c -> ChartChip(c, stableMedicineColor(c, d.medicineNames, d.categoryNames), chartSelected) }
            }
        }
    }
}
