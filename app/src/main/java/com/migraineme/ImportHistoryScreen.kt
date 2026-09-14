// FILE: app/src/main/java/com/migraineme/ImportHistoryScreen.kt
//
// Import from another app. Open to everyone for the journal. Counting imported
// data in insights (the "Use for insights" ticks) needs a paid subscription; a
// signup trial does not count. The edge function enforces the same rule.
//
// Flow: pick a file → the `import-history` edge function reads it and returns a
// preview → "Here's what we found" with four click-through rows (Use for
// insights / What we assumed / Check the attacks and days / How we read your
// words) → Import → Done (with Remove). Every attack opens read-only with an
// Edit mode. Nothing is written until the person taps Import.
//
// Spec: migraineme-ios/docs/import-anything-spec.md. Design mock approved by
// Jordy 2026-09-14 (assumptions hold the phrases we could not place; no
// separate question card; two-line rows; explicit Edit mode).
package com.migraineme

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// JSON helpers (the preview is dynamic JSON from the edge function)
// ─────────────────────────────────────────────────────────────────────────────

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
private fun JsonObject.int(key: String): Int? = num(key)?.toInt()
private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray)?.toList() ?: emptyList()
private fun JsonObject.strList(key: String): List<String> = arr(key).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

// ─────────────────────────────────────────────────────────────────────────────
// Editable copies of what the function found
// ─────────────────────────────────────────────────────────────────────────────

private class ImpMed(var name: String, var doseValue: Double?, var doseUnit: String?, var takenLocal: String?, var effect: String?, var sideEffect: String?) {
    fun label(): String {
        val dose = doseValue?.let { v -> val n = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString(); if (doseUnit == "amount") " $n" else " $n ${doseUnit ?: "mg"}" } ?: ""
        return name + dose
    }
    fun extra(): String = listOfNotNull(takenLocal?.let { "at " + it.substring(11, 16) }, effect?.lowercase(), sideEffect?.let { "side effect: $it" }).joinToString(" · ")
    fun toJson(): JsonObject = buildJsonObject { put("name", name); put("dose_value", doseValue); put("dose_unit", doseUnit); put("taken_local", takenLocal); put("effect", effect); put("side_effect", sideEffect) }
}
private class ImpRelief(var name: String, var startLocal: String?, var endLocal: String?, var effect: String?) {
    fun extra(): String = listOfNotNull(startLocal?.let { it.substring(11, 16) + (endLocal?.let { e -> " to " + e.substring(11, 16) } ?: "") }, effect?.lowercase()).joinToString(" · ")
    fun toJson(): JsonObject = buildJsonObject { put("name", name); put("start_local", startLocal); put("end_local", endLocal); put("effect", effect) }
}
private class ImpMissed(var name: String, var reasons: List<String>, var anticipated: Boolean) {
    fun extra(): String = listOfNotNull(if (anticipated) "skipped, felt it coming" else null, reasons.takeIf { it.isNotEmpty() }?.let { "because: " + it.joinToString(", ") }).joinToString(" · ")
    fun toJson(): JsonObject = buildJsonObject { put("name", name); putJsonArray("reasons") { reasons.forEach { add(it) } }; put("anticipated", anticipated) }
}

private class ImpAttack(src: JsonObject) {
    val ref: String = src.str("ref") ?: ""
    var startLocal by mutableStateOf(src.str("start_local") ?: "")
    var endLocal by mutableStateOf(src.str("end_local"))
    var endInferred by mutableStateOf(src.bool("end_inferred"))
    var severity by mutableStateOf(src.int("severity"))
    val types = mutableStateListOf<String>().apply { addAll(src.strList("types")) }
    val symptoms = mutableStateListOf<String>().apply { addAll(src.strList("symptoms")) }
    val symptomSeverity: Map<String, String> = src.obj("symptom_severity")?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap() ?: emptyMap()
    val prodromes = mutableStateListOf<String>().apply { addAll(src.strList("prodromes")) }
    val postdromes = mutableStateListOf<String>().apply { addAll(src.strList("postdromes")) }
    val triggers = mutableStateListOf<String>().apply { addAll(src.strList("triggers")) }
    val foods = mutableStateListOf<String>().apply { addAll(src.strList("foods")) }
    val meds = mutableStateListOf<ImpMed>().apply { addAll(src.arr("meds").mapNotNull { (it as? JsonObject)?.let { m -> ImpMed(m.str("name") ?: return@let null, m.num("dose_value"), m.str("dose_unit"), m.str("taken_local"), m.str("effect"), m.str("side_effect")) } }) }
    val reliefs = mutableStateListOf<ImpRelief>().apply { addAll(src.arr("reliefs").mapNotNull { e -> when (e) { is JsonObject -> e.str("name")?.let { ImpRelief(it, e.str("start_local"), e.str("end_local"), e.str("effect")) }; is JsonPrimitive -> e.contentOrNull?.let { ImpRelief(it, null, null, null) }; else -> null } }) }
    val activities = mutableStateListOf<String>().apply { addAll(src.strList("activities")) }
    val missed = mutableStateListOf<ImpMissed>().apply { addAll(src.arr("missed").mapNotNull { e -> when (e) { is JsonObject -> e.str("name")?.let { ImpMissed(it, e.strList("reasons"), e.bool("anticipated")) }; is JsonPrimitive -> e.contentOrNull?.let { ImpMissed(it, emptyList(), false) }; else -> null } }) }
    var location by mutableStateOf(src.str("location"))
    val painLocations = mutableStateListOf<String>().apply { addAll(src.strList("pain_locations")) }
    var auraPresent by mutableStateOf(src.obj("aura")?.bool("present") ?: false)
    val auraZones: List<String> = src.obj("aura")?.strList("zones") ?: emptyList()
    val auraDuration: Int? = src.obj("aura")?.int("duration_minutes")
    val times: Map<String, String> = src.obj("times")?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap() ?: emptyMap()
    var notes by mutableStateOf(src.str("notes") ?: "")
    var removed by mutableStateOf(false)
    var edited by mutableStateOf(false)

    fun date(): String = startLocal.take(10)
    fun time(): String = if (startLocal.length >= 16) startLocal.substring(11, 16) else ""
    fun summary(): String {
        val parts = symptoms.take(2).toMutableList()
        if (meds.isNotEmpty()) parts += meds.joinToString(", ") { it.name }
        return parts.joinToString(" · ").ifEmpty { "no details" }
    }
    fun whenText(cls: String, name: String): String {
        val t = times["$cls|${name.lowercase()}"] ?: return ""
        return if (t.take(10) < date()) "the day before, " + t.substring(11, 16) else t.substring(11, 16)
    }
    fun editJson(): JsonObject = buildJsonObject {
        put("start_local", startLocal); put("end_local", endLocal); put("severity", severity)
        putJsonArray("symptoms") { symptoms.forEach { add(it) } }; putJsonArray("prodromes") { prodromes.forEach { add(it) } }
        putJsonArray("postdromes") { postdromes.forEach { add(it) } }; putJsonArray("triggers") { triggers.forEach { add(it) } }
        putJsonArray("foods") { foods.forEach { add(it) } }; putJsonArray("activities") { activities.forEach { add(it) } }
        putJsonArray("meds") { meds.forEach { add(it.toJson()) } }; putJsonArray("reliefs") { reliefs.forEach { add(it.toJson()) } }
        putJsonArray("missed_detail") { missed.forEach { add(it.toJson()) } }
        put("location", location); putJsonArray("pain_locations") { painLocations.forEach { add(it) } }
        put("aura", buildJsonObject { put("present", auraPresent); putJsonArray("zones") { auraZones.forEach { add(it) } }; put("duration_minutes", auraDuration) })
        put("notes", notes)
    }
}

private class ImpDay(val src: JsonObject) {
    val date: String = src.str("date") ?: ""
    val metrics: Map<String, Double> = src.obj("metrics")?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }?.toMap() ?: emptyMap()
    val times: Map<String, String> = src.obj("times")?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap() ?: emptyMap()
    val period: String? = src.str("period")
    val foods: List<String> = src.strList("foods")
    val sideEffects: List<Pair<List<String>, String?>> = src.arr("side_effects").mapNotNull { (it as? JsonObject)?.let { o -> o.strList("symptoms") to o.str("regimen") } }
    val regimens: List<String> = src.arr("regimens").mapNotNull { (it as? JsonObject)?.let { o -> (if (o.str("stopped") != null) "Stopped " else "Started ") + (o.str("name") ?: "") + (o.num("dose_value")?.let { v -> " ${v.toInt()} ${o.str("dose_unit") ?: "mg"}" } ?: "") } }
    val missed: List<ImpMissed> = src.arr("missed").mapNotNull { (it as? JsonObject)?.let { o -> o.str("name")?.let { n -> ImpMissed(n, o.strList("reasons"), o.bool("anticipated")) } } }
    fun summary(): String {
        val p = mutableListOf<String>()
        period?.let { p += "period $it" }
        metrics["sleep_hours"]?.let { p += "${trimNum(it)} h sleep" }
        metrics["hydration_ml"]?.let { p += "${trimNum(it)} ml water" }
        metrics["weight_kg"]?.let { p += "${trimNum(it)} kg" }
        metrics["bp_systolic"]?.let { p += "BP ${trimNum(it)}/${trimNum(metrics["bp_diastolic"] ?: 0.0)}" }
        if (sideEffects.isNotEmpty()) p += "treatment side effects"
        regimens.firstOrNull()?.let { p += it.lowercase() }
        return p.joinToString(" · ").ifEmpty { "note" }
    }
}

private fun trimNum(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

private fun fmtDate(iso: String): String = runCatching { LocalDate.parse(iso.take(10)).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())) }.getOrDefault(iso.take(10))

// ─────────────────────────────────────────────────────────────────────────────
// Edge call. Same shape as EdgeFunctionsService, kept local to this feature.
// ─────────────────────────────────────────────────────────────────────────────

private sealed class EdgeResult { data class Ok(val body: JsonObject) : EdgeResult(); data class Err(val status: Int, val message: String) : EdgeResult() }

private suspend fun callImportHistory(context: Context, body: JsonObject): EdgeResult = withContext(Dispatchers.IO) {
    val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return@withContext EdgeResult.Err(401, "Not signed in")
    // Reading a big export takes the server a minute or two (model calls); the default 10 s Android engine timeout gave "Connect timeout has expired".
    val client = HttpClient(Android) { install(HttpTimeout) { requestTimeoutMillis = 300_000; connectTimeoutMillis = 30_000; socketTimeoutMillis = 300_000 } }
    try {
        val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/import-history"
        val res = client.post(url) {
            header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = res.bodyAsText()
        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (res.status.value in 200..299 && json != null) EdgeResult.Ok(json)
        else EdgeResult.Err(res.status.value, json?.str("message") ?: json?.str("error") ?: text.take(200))
    } catch (t: Throwable) {
        Log.e("ImportHistory", "call failed", t); EdgeResult.Err(0, t.message ?: "Network error")
    } finally { client.close() }
}

private const val MAX_FILE_BYTES = 6 * 1024 * 1024
private val Edge = Color.White.copy(alpha = 0.08f)

private fun readPickedFile(context: Context, uri: Uri): Pair<String, String>? {
    val resolver = context.contentResolver
    var name = "upload"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (bytes.size > MAX_FILE_BYTES) return null
    val text = String(bytes, Charsets.UTF_8)
    if (text.count { it == '�' } > text.length / 50) return null // binary (xlsx, pdf): not a text export
    return name to text
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

private sealed class ImpStep {
    object Pick : ImpStep(); data class Busy(val text: String) : ImpStep(); object Found : ImpStep(); object Use : ImpStep(); object Assume : ImpStep()
    object ListAll : ImpStep(); data class Attack(val index: Int) : ImpStep(); data class Day(val index: Int) : ImpStep(); object Words : ImpStep()
    object Done : ImpStep(); data class Failed(val text: String, val back: ImpStep) : ImpStep()
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ImportHistoryScreen(onBack: () -> Unit, onNavigateToPaywall: () -> Unit) {
    val context = LocalContext.current
    val premiumState by PremiumManager.state.collectAsState()
    val paid = premiumState.tier == PremiumTier.PREMIUM
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var step by remember { mutableStateOf<ImpStep>(ImpStep.Pick) }
    var importId by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<JsonObject?>(null) }
    var fileName by remember { mutableStateOf("") }
    val attacks = remember { mutableStateListOf<ImpAttack>() }
    val days = remember { mutableStateListOf<ImpDay>() }
    val engineUse = remember { mutableStateMapOf<String, Boolean>() }
    val answers = remember { mutableStateMapOf<String, String?>() }        // "class|phrase" -> label, null = keep their words
    val regimenSkip = remember { mutableStateMapOf<String, Boolean>() }
    val doses = remember { mutableStateMapOf<String, String>() }             // medicine label -> "40 mg"
    var endFillHours by remember { mutableStateOf<String>("") }
    var noTimeHour by remember { mutableStateOf<Int?>(null) }
    var verify by remember { mutableStateOf<JsonObject?>(null) }
    var editing by remember { mutableStateOf(false) }
    var timezone by remember { mutableStateOf(ZoneId.systemDefault().id) }
    var importOverlap by remember { mutableStateOf(false) }               // false = skip attacks after the first app log (default)

    fun loadPreview(p: JsonObject) {
        preview = p
        attacks.clear(); attacks.addAll(p.arr("attacks").mapNotNull { (it as? JsonObject)?.let { a -> ImpAttack(a) } })
        days.clear(); days.addAll(p.arr("days").mapNotNull { (it as? JsonObject)?.let { d -> ImpDay(d) } })
        engineUse.clear(); p.arr("engine_use").forEach { e -> (e as? JsonObject)?.takeIf { !it.bool("fixed") }?.let { o -> o.str("key")?.let { k -> engineUse[k] = o.bool("recommended") } } }
        answers.clear(); regimenSkip.clear(); doses.clear(); endFillHours = ""; noTimeHour = null
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        step = ImpStep.Busy("Reading your file…")
        scope.launch {
            val read = withContext(Dispatchers.IO) { runCatching { readPickedFile(context, uri) }.getOrNull() }
            if (read == null) { step = ImpStep.Failed("That file could not be read as text. Export as CSV, and keep it under 6 MB.", ImpStep.Pick); return@launch }
            fileName = read.first
            step = ImpStep.Busy("Reading your history. This takes a minute for a big file.")
            val body = buildJsonObject { put("action", "preview"); put("file_name", read.first); put("content_text", read.second); put("timezone", ZoneId.systemDefault().id) }
            when (val r = callImportHistory(context, body)) {
                is EdgeResult.Ok -> { importId = r.body.str("import_id"); r.body.obj("preview")?.let { loadPreview(it) }; step = ImpStep.Found }
                is EdgeResult.Err -> step = ImpStep.Failed(if (r.status == 409) "This file was already imported." else r.message, ImpStep.Pick)
            }
        }
    }

    fun commit() {
        val id = importId ?: return
        step = ImpStep.Busy("Writing your history…")
        scope.launch {
            val body = buildJsonObject {
                put("action", "commit"); put("import_id", id)
                if (importOverlap) put("cutoff_date", JsonNull)
                put("answers", buildJsonObject { answers.forEach { (k, v) -> if (v == null) put(k, JsonNull) else put(k, v) } })
                put("engine_use", buildJsonObject { engineUse.forEach { (k, v) -> put(k, v) } })
                putJsonArray("accept_regimens") { preview?.arr("regimen_proposals")?.forEach { r -> (r as? JsonObject)?.str("name")?.let { n -> if (regimenSkip[n] != true) add(n) } } }
                put("doses", buildJsonObject { doses.forEach { (k, v) -> if (v.isNotBlank()) put(k, v.trim()) } })
                put("assumptions", buildJsonObject { endFillHours.toDoubleOrNull()?.let { put("end_fill_hours", it) }; noTimeHour?.let { put("no_time_hour", it) }; put("timezone", timezone) })
                putJsonArray("exclude_refs") { attacks.filter { it.removed }.forEach { add(it.ref) } }
                put("attack_edits", buildJsonObject { attacks.filter { it.edited && !it.removed }.forEach { put(it.ref, it.editJson()) } })
            }
            when (val r = callImportHistory(context, body)) {
                is EdgeResult.Ok -> { verify = r.body.obj("verify"); step = ImpStep.Done }
                is EdgeResult.Err -> step = ImpStep.Failed(r.message, ImpStep.Found)
            }
        }
    }

    fun undo() {
        val id = importId ?: return
        step = ImpStep.Busy("Removing the import…")
        scope.launch {
            when (val r = callImportHistory(context, buildJsonObject { put("action", "undo"); put("import_id", id) })) {
                is EdgeResult.Ok -> { importId = null; preview = null; step = ImpStep.Pick }
                is EdgeResult.Err -> step = ImpStep.Failed(r.message, ImpStep.Done)
            }
        }
    }

    LaunchedEffect(step) { scrollState.scrollTo(0) }
    val parentStep: ImpStep? = when (val s = step) {
        is ImpStep.Use, is ImpStep.Assume, is ImpStep.ListAll, is ImpStep.Words -> ImpStep.Found
        is ImpStep.Attack, is ImpStep.Day -> ImpStep.ListAll
        is ImpStep.Failed -> s.back
        else -> null
    }
    BackHandler(enabled = parentStep != null) { editing = false; parentStep?.let { step = it } }

    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        when (val s = step) {
            is ImpStep.Pick -> PickScreen(paid, onNavigateToPaywall, onPick = { picker.launch(arrayOf("text/*", "application/json", "application/xml", "text/csv", "text/html", "application/octet-stream")) })
            is ImpStep.Busy -> BusyScreen(s.text)
            is ImpStep.Failed -> FailedScreen(s.text) { step = s.back }
            is ImpStep.Found -> preview?.let { p ->
                FoundScreen(p, fileName, attacks, days, engineUse, paid, importOverlap,
                    onUse = { step = ImpStep.Use }, onAssume = { step = ImpStep.Assume }, onList = { step = ImpStep.ListAll }, onWords = { step = ImpStep.Words }, onImport = { commit() })
            }
            is ImpStep.Use -> preview?.let { p -> UseScreen(p, engineUse, paid, onNavigateToPaywall) { step = ImpStep.Found } }
            is ImpStep.Assume -> preview?.let { p -> AssumeScreen(p, answers, regimenSkip, doses, endFillHours, { endFillHours = it }, noTimeHour, { noTimeHour = it }, timezone, { timezone = it }, importOverlap, { importOverlap = it }) { step = ImpStep.Found } }
            is ImpStep.ListAll -> ListScreen(attacks, days, onAttack = { editing = false; step = ImpStep.Attack(it) }, onDay = { step = ImpStep.Day(it) }) { step = ImpStep.Found }
            is ImpStep.Attack -> attacks.getOrNull(s.index)?.let { a -> AttackScreen(a, editing, { editing = it }) { step = ImpStep.ListAll } }
            is ImpStep.Day -> days.getOrNull(s.index)?.let { d -> DayScreen(d) { step = ImpStep.ListAll } }
            is ImpStep.Words -> preview?.let { p -> WordsScreen(p) { step = ImpStep.Found } }
            is ImpStep.Done -> DoneScreen(verify, attacks.count { !it.removed }, preview, paid, onRemove = { undo() }, onClose = onBack)
        }
        Spacer(Modifier.height(32.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Building blocks
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun Title(text: String, sub: String? = null) {
    Text(text, color = AppTheme.TitleColor, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(horizontal = 4.dp))
    if (sub != null) Text(sub, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 12.dp))
    else Spacer(Modifier.height(12.dp))
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun NavRow(title: String, sub: String, onClick: () -> Unit) {
    BaseCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = AppTheme.TitleColor, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = AppTheme.AccentPurple)
        }
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple, contentColor = Color(0xFF20062F))) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun GhostButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, AppTheme.AccentPink.copy(alpha = 0.5f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPink)) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SevBadge(sev: Int?, size: Int = 26) {
    val bg = when { sev == null -> Color.White.copy(alpha = 0.08f); sev >= 8 -> AppTheme.AccentPink; sev >= 6 -> Color(0xFFFFC978); else -> AppTheme.AccentPurple }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(bg), contentAlignment = Alignment.Center) {
        Text(sev?.toString() ?: "–", color = if (sev == null) AppTheme.SubtleTextColor else Color(0xFF20062F), fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(label: String, items: List<Pair<String, String>>, editing: Boolean, onRemove: ((Int) -> Unit)?, onAdd: ((String) -> Unit)?, warn: Set<Int> = emptySet()) {
    var draft by remember { mutableStateOf("") }
    Column(Modifier.padding(top = 10.dp)) {
        SectionLabel(label)
        if (items.isEmpty()) Text(t("none"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEachIndexed { i, (text, extra) ->
                Row(Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.07f)).border(1.dp, if (i in warn) Color(0xFFFFC978).copy(alpha = 0.45f) else Edge, RoundedCornerShape(999.dp)).padding(start = 9.dp, end = if (editing && onRemove != null) 4.dp else 9.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text, color = AppTheme.BodyTextColor, fontSize = 12.sp)
                    if (extra.isNotEmpty()) Text(" $extra", color = AppTheme.SubtleTextColor, fontSize = 12.sp)
                    if (editing && onRemove != null) {
                        Spacer(Modifier.width(4.dp))
                        Box(Modifier.size(18.dp).clip(CircleShape).background(AppTheme.AccentPink.copy(alpha = 0.22f)).clickable { onRemove(i) }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Close, contentDescription = t("Remove"), tint = AppTheme.AccentPink, modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
        }
        if (editing && onAdd != null) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, placeholder = { Text(t("Add"), color = AppTheme.SubtleTextColor) }, singleLine = true, modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodySmall.copy(color = AppTheme.TitleColor), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = AppTheme.TitleColor, unfocusedTextColor = AppTheme.TitleColor))
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = { if (draft.isNotBlank()) { onAdd(draft.trim()); draft = "" } }) { Text(t("Add"), color = AppTheme.AccentPurple) }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screens
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PickScreen(paid: Boolean, onNavigateToPaywall: () -> Unit, onPick: () -> Unit) {
    Title(t("Import from another app"), t("Bring your history with you."))
    if (!paid) {
        BaseCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onNavigateToPaywall)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = AppTheme.AccentPurple)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t("Free: your history goes into your journal."), color = AppTheme.TitleColor, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(t("Subscribers: it also counts in your insights, triggers, medicines and patterns."), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
                Text(t("Upgrade"), color = AppTheme.AccentPurple, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    HeroCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(AppTheme.AccentPurple.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.UploadFile, contentDescription = null, tint = AppTheme.AccentPurple) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(t("Pick an export file"), color = AppTheme.TitleColor, fontWeight = FontWeight.SemiBold)
                Text(t("CSV, a spreadsheet saved as CSV, Apple Health export, or plain notes"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(t("Choose file"), onClick = onPick)
    }
    Spacer(Modifier.height(12.dp))
    BaseCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(t("How it works"))
        Text(t("We read the file and show you what we found before anything is saved. You can change or remove any attack, and you can remove the whole import afterwards."), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Text(t("Migraine Buddy: Records › export › All period. Apple Health: Profile › Export All Health Data, then pick export.xml."), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BusyScreen(text: String) {
    Spacer(Modifier.height(120.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = AppTheme.AccentPurple, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(16.dp))
            Text(t(text), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun FailedScreen(text: String, onBack: () -> Unit) {
    Title(t("That didn't work"))
    BaseCard(modifier = Modifier.fillMaxWidth()) { Text(text, color = AppTheme.BodyTextColor) }
    Spacer(Modifier.height(12.dp))
    PrimaryButton(t("Back"), onClick = onBack)
}

@Composable
private fun FoundScreen(p: JsonObject, fileName: String, attacks: List<ImpAttack>, days: List<ImpDay>, engineUse: Map<String, Boolean>, paid: Boolean, importOverlap: Boolean, onUse: () -> Unit, onAssume: () -> Unit, onList: () -> Unit, onWords: () -> Unit, onImport: () -> Unit) {
    val cutoff = if (importOverlap) null else p.obj("overlap")?.str("first_app_log")
    val kept = attacks.filter { !it.removed && (cutoff == null || it.date() < cutoff) }
    val live = kept.size
    val meds = kept.sumOf { it.meds.size }
    val syms = kept.sumOf { it.symptoms.size + it.types.size }
    val range = p.obj("range"); val from = range?.str("from"); val to = range?.str("to")
    val assumptions = p.arr("assumptions").size
    val phrases = p.arr("questions").size
    val ticked = engineUse.values.count { it }
    Title(t("Here's what we found"), t("Nothing is saved yet."))
    HeroCard(modifier = Modifier.fillMaxWidth()) {
        Text(fileName, color = AppTheme.TitleColor, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (from != null && to != null) Text("${fmtDate(from)} – ${fmtDate(to)}", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(live to t("attacks"), meds to t("medicines"), syms to t("symptoms")).forEach { (n, l) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.05f)).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(n.toString(), color = AppTheme.TitleColor, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Text(l, color = AppTheme.SubtleTextColor, fontSize = 11.sp)
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    NavRow(t("Use for insights"), if (paid) t("%1\$s of %2\$s ticked, our advice", ticked, engineUse.size) else t("Journal only. Subscribe to count it in insights."), onUse)
    Spacer(Modifier.height(10.dp))
    NavRow(t("What we assumed"), if (phrases > 0) t("%1\$s things, including %2\$s phrases we kept in your words", assumptions, phrases) else t("%1\$s things", assumptions), onAssume)
    Spacer(Modifier.height(10.dp))
    NavRow(t("Check the attacks and days"), t("%1\$s attacks and %2\$s other days", live, days.size), onList)
    Spacer(Modifier.height(10.dp))
    NavRow(t("How we read your words"), t("Your labels next to ours"), onWords)
    Spacer(Modifier.height(16.dp))
    PrimaryButton(t("Import %1\$s attacks", live), enabled = live > 0, onClick = onImport)
}

@Composable
private fun UseScreen(p: JsonObject, engineUse: MutableMap<String, Boolean>, paid: Boolean, onNavigateToPaywall: () -> Unit, onDone: () -> Unit) {
    Title(t("Use for insights"), t("Everything goes in your journal. We decided for each item whether it should count in your insights. Change any tick."))
    if (!paid) {
        BaseCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onNavigateToPaywall)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = AppTheme.AccentPurple)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t("This will not show up in your insights"), color = AppTheme.TitleColor, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(t("Without a subscription, imported data only goes into your journal."), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
                Text(t("Upgrade"), color = AppTheme.AccentPurple, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    val groupTitle = mapOf("attacks" to t("Attacks"), "symptom" to t("Symptoms"), "postdrome" to t("After-effects"), "medicine" to t("Medicines"), "relief" to t("What helped"),
        "trigger" to t("Triggers"), "prodrome" to t("Warning signs before"), "food" to t("Foods"), "location" to t("Places"), "activity" to t("Activities"), "missed" to t("Missed plans"),
        "metric" to t("Body and sleep"), "period" to t("Period"), "regimen" to t("Treatments"))
    val rows = p.arr("engine_use").mapNotNull { it as? JsonObject }
    val groups = rows.groupBy { it.str("group") ?: "other" }
    groups.forEach { (group, items) ->
        // fixed rows can't be counted fairly, so they get no tick and never count toward "x of y"
        val keys = items.filter { !it.bool("fixed") }.mapNotNull { it.str("key") }
        val onCount = keys.count { engineUse[it] ?: false }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((groupTitle[group] ?: group).uppercase(), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            if (keys.isNotEmpty()) Text(t("%1\$s of %2\$s", onCount, keys.size), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            if (keys.size > 1) {
                Spacer(Modifier.width(12.dp))
                Text(if (onCount == keys.size) t("None") else t("All"), color = AppTheme.AccentPurple, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { val v = onCount != keys.size; keys.forEach { engineUse[it] = v } }.padding(4.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        BaseCard(modifier = Modifier.fillMaxWidth()) {
            items.forEachIndexed { i, o ->
                val key = o.str("key") ?: return@forEachIndexed
                val fixed = o.bool("fixed")
                val on = if (fixed) false else engineUse[key] ?: o.bool("recommended")
                val advised = o.bool("recommended")
                if (i > 0) HorizontalDivider(color = Edge)
                Row(Modifier.fillMaxWidth().then(if (fixed) Modifier else Modifier.clickable { engineUse[key] = !on }).padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                    if (!fixed) {
                        Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(if (on) AppTheme.AccentPurple else Color.Transparent).border(1.5.dp, AppTheme.AccentPurple, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                            if (on) Icon(Icons.Outlined.Check, contentDescription = null, tint = Color(0xFF20062F), modifier = Modifier.size(15.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(o.str("label") ?: key, color = AppTheme.TitleColor, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        if (fixed) Text(t("Journal only"), color = AppTheme.AccentPurple, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        Text(o.str("reason") ?: "", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        if (!fixed && on != advised) Text(if (advised) t("We'd count this.") else t("We'd leave this out."), color = Color(0xFFFFC978), style = MaterialTheme.typography.labelSmall)
                    }
                    Text((o.int("count") ?: 0).toString(), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton(t("Done"), onClick = onDone)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssumeScreen(p: JsonObject, answers: MutableMap<String, String?>, regimenSkip: MutableMap<String, Boolean>, doses: MutableMap<String, String>, endFill: String, onEndFill: (String) -> Unit, noTimeHour: Int?, onNoTimeHour: (Int?) -> Unit, timezone: String, onTimezone: (String) -> Unit, importOverlap: Boolean, onImportOverlap: (Boolean) -> Unit, onDone: () -> Unit) {
    val zones = remember(timezone) { (listOf(timezone, ZoneId.systemDefault().id, "Europe/London", "Europe/Amsterdam", "Europe/Brussels", "Europe/Berlin", "Europe/Paris", "Europe/Madrid", "Europe/Rome", "Europe/Lisbon", "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles", "Australia/Sydney")).distinct() }
    Title(t("What we assumed"), t("Change anything that's wrong."))
    BaseCard(modifier = Modifier.fillMaxWidth()) {
        p.arr("assumptions").forEachIndexed { i, e ->
            val o = e as? JsonObject ?: return@forEachIndexed
            val key = o.str("key") ?: return@forEachIndexed
            if (i > 0) HorizontalDivider(color = Edge)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(o.str("text") ?: key, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
                    o.str("why")?.let { Text(it, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall) }
                }
                Spacer(Modifier.width(10.dp))
                when {
                    key == "cutoff" -> { val skipL = t("Skip them"); val impL = t("Import them too"); Picker(listOf(skipL, impL), if (importOverlap) impL else skipL) { onImportOverlap(it == impL) } }
                    key == "timezone" -> Picker(zones.map { it.replace('_', ' ') }, timezone.replace('_', ' ')) { onTimezone(it.replace(' ', '_')) }
                    key == "end_fill_hours" -> OutlinedTextField(value = endFill, onValueChange = onEndFill, placeholder = { Text("h", color = AppTheme.SubtleTextColor) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(72.dp), textStyle = MaterialTheme.typography.bodySmall.copy(color = AppTheme.TitleColor), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = AppTheme.TitleColor, unfocusedTextColor = AppTheme.TitleColor))
                    key.startsWith("dose|") -> OutlinedTextField(value = doses[key.removePrefix("dose|")] ?: "", onValueChange = { doses[key.removePrefix("dose|")] = it }, placeholder = { Text(t("e.g. 40 mg"), color = AppTheme.SubtleTextColor) }, singleLine = true, modifier = Modifier.width(120.dp), textStyle = MaterialTheme.typography.bodySmall.copy(color = AppTheme.TitleColor), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = AppTheme.TitleColor, unfocusedTextColor = AppTheme.TitleColor))
                    key == "no_time_hour" -> Picker(listOf("07:00", "09:00", "12:00", "18:00"), (noTimeHour ?: 12).let { "%02d:00".format(it) }) { onNoTimeHour(it.take(2).toInt()) }
                    key.startsWith("regimen|") -> { val addL = t("Add as a treatment"); val skipL = t("Skip"); val rn = key.removePrefix("regimen|"); Picker(listOf(addL, skipL), if (regimenSkip[rn] == true) skipL else addL) { regimenSkip[rn] = it == skipL } }
                    o["options"] is JsonArray && (o.str("value") == null) -> {
                        val keepL = t("Keep as written"); val otherL = t("Another…")
                        val opts = listOf(keepL) + o.strList("options") + listOf(otherL)
                        val cur = answers[key]?.let { v -> if (v in o.strList("options")) v else otherL } ?: keepL
                        var custom by remember(key) { mutableStateOf(answers[key]?.takeIf { it !in o.strList("options") } ?: "") }
                        Column(horizontalAlignment = Alignment.End) {
                            Picker(opts, cur) { sel -> answers[key] = when (sel) { keepL -> null; otherL -> custom.ifBlank { null }; else -> sel } }
                            if (cur == otherL) OutlinedTextField(value = custom, onValueChange = { custom = it; answers[key] = it.ifBlank { null } }, singleLine = true, modifier = Modifier.width(160.dp), textStyle = MaterialTheme.typography.bodySmall.copy(color = AppTheme.TitleColor), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = AppTheme.TitleColor, unfocusedTextColor = AppTheme.TitleColor), placeholder = { Text(t("Name"), color = AppTheme.SubtleTextColor) })
                        }
                    }
                    else -> Text(o.str("value") ?: "", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    Text(t("A phrase that names nothing specific is never guessed: it stays in your words unless you pick what it was."), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp))
    Spacer(Modifier.height(16.dp))
    PrimaryButton(t("Done"), onClick = onDone)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(options: List<String>, value: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.07f)).border(1.dp, Edge, RoundedCornerShape(10.dp)).clickable { open = true }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = AppTheme.TitleColor, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
            Text(" ▾", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { opt -> DropdownMenuItem(text = { Text(opt) }, onClick = { open = false; onPick(opt) }) }
        }
    }
}

@Composable
private fun ListScreen(attacks: List<ImpAttack>, days: List<ImpDay>, onAttack: (Int) -> Unit, onDay: (Int) -> Unit, onDone: () -> Unit) {
    Title(t("Attacks (%1\$s)", attacks.count { !it.removed }), t("Tap one to see everything we read from it."))
    BaseCard(modifier = Modifier.fillMaxWidth()) {
        attacks.forEachIndexed { i, a ->
            if (i > 0) HorizontalDivider(color = Edge)
            Row(Modifier.fillMaxWidth().clickable { onAttack(i) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(66.dp)) { Text(fmtDate(a.date()), color = AppTheme.TitleColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(a.time(), color = AppTheme.SubtleTextColor, fontSize = 12.sp) }
                Spacer(Modifier.width(8.dp))
                SevBadge(a.severity)
                Spacer(Modifier.width(6.dp))
                Text(if (a.removed) t("Removed") else a.summary(), color = if (a.removed) AppTheme.SubtleTextColor else AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = AppTheme.SubtleTextColor)
            }
        }
    }
    if (days.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        BaseCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel(t("Days without an attack"))
            days.forEachIndexed { i, d ->
                if (i > 0) HorizontalDivider(color = Edge)
                Row(Modifier.fillMaxWidth().clickable { onDay(i) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtDate(d.date), color = AppTheme.TitleColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(74.dp))
                    Text(d.summary(), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = AppTheme.SubtleTextColor)
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton(t("Done"), onClick = onDone)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttackScreen(a: ImpAttack, editing: Boolean, setEditing: (Boolean) -> Unit, onDone: () -> Unit) {
    Title(fmtDate(a.date()), t("Everything we read from this entry."))
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (editing) t("Tap a × to remove an item, type to add one.") else t("Looks wrong? Tap Edit."), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (!a.removed) TextButton(onClick = { setEditing(!editing) }, colors = ButtonDefaults.textButtonColors(contentColor = if (editing) Color(0xFF20062F) else AppTheme.AccentPurple, containerColor = if (editing) AppTheme.AccentPurple else Color.White.copy(alpha = 0.07f)), shape = RoundedCornerShape(999.dp)) {
            Text(if (editing) t("Done editing") else t("Edit"), fontWeight = FontWeight.SemiBold)
        }
    }
    if (a.removed) {
        BaseCard(modifier = Modifier.fillMaxWidth()) {
            Text(t("This attack will not be imported."), color = AppTheme.SubtleTextColor)
            TextButton(onClick = { a.removed = false }) { Text(t("Put it back"), color = AppTheme.AccentPurple) }
        }
    } else {
        val mark = { a.edited = true }
        BaseCard(modifier = Modifier.fillMaxWidth().then(if (editing) Modifier.border(1.dp, AppTheme.AccentPurple.copy(alpha = 0.45f), AppTheme.BaseCardShape) else Modifier)) {
            SectionLabel(t("Time"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField(t("Start"), a.startLocal, editing, Modifier.weight(1f)) { a.startLocal = it; mark() }
                TimeField(if (a.endInferred) t("End (assumed)") else t("End"), a.endLocal ?: "", editing, Modifier.weight(1f)) { a.endLocal = it.ifBlank { null }; a.endInferred = false; mark() }
            }
            Spacer(Modifier.height(10.dp))
            SectionLabel(t("Pain"))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (editing) StepBtn("−") { a.severity = ((a.severity ?: 5) - 1).coerceIn(1, 10); mark() }
                SevBadge(a.severity, 34)
                if (editing) StepBtn("+") { a.severity = ((a.severity ?: 5) + 1).coerceIn(1, 10); mark() }
                Text(t("out of 10"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
            if (a.types.isNotEmpty()) ChipGroup(t("Type"), a.types.map { it to "" }, editing, { a.types.removeAt(it); mark() }, { a.types.add(it); mark() })
            ChipGroup(t("Symptoms"), a.symptoms.map { it to (a.symptomSeverity[it.lowercase()]?.lowercase() ?: "") }, editing, { a.symptoms.removeAt(it); mark() }, { a.symptoms.add(it); mark() })
            ChipGroup(t("Warning signs before"), a.prodromes.map { it to a.whenText("prodrome", it) }, editing, { a.prodromes.removeAt(it); mark() }, { a.prodromes.add(it); mark() })
            ChipGroup(t("After-effects"), a.postdromes.map { it to "" }, editing, { a.postdromes.removeAt(it); mark() }, { a.postdromes.add(it); mark() })
            ChipGroup(t("Triggers"), a.triggers.map { it to a.whenText("trigger", it) }, editing, { a.triggers.removeAt(it); mark() }, { a.triggers.add(it); mark() })
            ChipGroup(t("Foods"), a.foods.map { it to a.whenText("food", it) }, editing, { a.foods.removeAt(it); mark() }, { a.foods.add(it); mark() })
            ChipGroup(t("Medicines"), a.meds.map { it.label() to it.extra() }, editing, { a.meds.removeAt(it); mark() }, { txt ->
                val m = Regex("(\\d+(?:[.,]\\d+)?)\\s*(mg|mcg)", RegexOption.IGNORE_CASE).find(txt)
                a.meds.add(ImpMed(m?.let { txt.replace(it.value, "").trim() } ?: txt, m?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull(), m?.groupValues?.get(2)?.lowercase(), null, null, null)); mark()
            })
            ChipGroup(t("What helped"), a.reliefs.map { it.name to it.extra() }, editing, { a.reliefs.removeAt(it); mark() }, { a.reliefs.add(ImpRelief(it, null, null, null)); mark() })
            ChipGroup(t("Activities"), a.activities.map { it to (a.times["activity|${it.lowercase()}"]?.substring(11, 16) ?: "") }, editing, { a.activities.removeAt(it); mark() }, { a.activities.add(it); mark() })
            ChipGroup(t("Missed plans"), a.missed.map { it.name to it.extra() }, editing, { a.missed.removeAt(it); mark() }, { a.missed.add(ImpMissed(it, emptyList(), false)); mark() }, warn = a.missed.withIndex().filter { it.value.anticipated }.map { it.index }.toSet())
            ChipGroup(t("Pain positions"), a.painLocations.map { it.replace('_', ' ') to "" }, editing, { a.painLocations.removeAt(it); mark() }, null)
            if (a.auraPresent) ChipGroup(t("Aura"), listOf("Aura" to listOfNotNull(a.auraDuration?.let { "$it min" }, a.auraZones.takeIf { it.isNotEmpty() }?.joinToString(", ") { z -> z.replace('_', ' ') }).joinToString(" · ")), editing, { a.auraPresent = false; mark() }, null)
            a.location?.let { loc -> ChipGroup(t("Where"), listOf(loc to ""), editing, { a.location = null; mark() }, null) }
            Spacer(Modifier.height(10.dp))
            SectionLabel(t("Your note"))
            if (editing) OutlinedTextField(value = a.notes, onValueChange = { a.notes = it; mark() }, modifier = Modifier.fillMaxWidth(), minLines = 2, textStyle = MaterialTheme.typography.bodySmall.copy(color = AppTheme.TitleColor), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = AppTheme.TitleColor, unfocusedTextColor = AppTheme.TitleColor))
            else Text(a.notes.ifBlank { t("none") }, color = if (a.notes.isBlank()) AppTheme.SubtleTextColor else AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
        }
        if (editing) { Spacer(Modifier.height(12.dp)); GhostButton(t("Remove this attack")) { a.removed = true; setEditing(false) } }
    }
    Spacer(Modifier.height(12.dp))
    PrimaryButton(t("Done"), onClick = onDone)
}

@Composable
private fun StepBtn(label: String, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.07f)).border(1.dp, Edge, RoundedCornerShape(10.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = AppTheme.TitleColor, fontSize = 18.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(label: String, value: String, editing: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    Column(modifier) {
        Text(label, color = AppTheme.SubtleTextColor, fontSize = 11.sp)
        if (editing) OutlinedTextField(value = value.take(16), onValueChange = { onChange(it) }, singleLine = true, placeholder = { Text("YYYY-MM-DDTHH:MM", color = AppTheme.SubtleTextColor, fontSize = 12.sp) }, textStyle = MaterialTheme.typography.bodySmall.copy(color = AppTheme.TitleColor), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = AppTheme.TitleColor, unfocusedTextColor = AppTheme.TitleColor), modifier = Modifier.fillMaxWidth())
        else Text(if (value.length >= 16) "${fmtDate(value)} ${value.substring(11, 16)}" else "–", color = AppTheme.TitleColor, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DayScreen(d: ImpDay, onDone: () -> Unit) {
    Title(fmtDate(d.date), t("A day without an attack."))
    BaseCard(modifier = Modifier.fillMaxWidth()) {
        val body = mutableListOf<Pair<String, String>>()
        d.metrics["sleep_hours"]?.let { body += t("Sleep") to "${trimNum(it)} h" }
        d.times["bedtime"]?.let { body += t("Bedtime") to it.substring(11, 16) }
        d.times["wake_time"]?.let { body += t("Woke up") to it.substring(11, 16) }
        d.metrics["hydration_ml"]?.let { body += t("Water") to "${trimNum(it)} ml" }
        d.metrics["weight_kg"]?.let { body += t("Weight") to "${trimNum(it)} kg" }
        d.metrics["steps"]?.let { body += t("Steps") to trimNum(it) }
        d.metrics["bp_systolic"]?.let { body += t("Blood pressure") to "${trimNum(it)}/${trimNum(d.metrics["bp_diastolic"] ?: 0.0)}" }
        ChipGroup(t("Body and sleep"), body.map { "${it.first} ${it.second}" to "" }, false, null, null)
        d.period?.let { ChipGroup(t("Period"), listOf("${t("Period")} $it" to ""), false, null, null) }
        if (d.sideEffects.isNotEmpty()) ChipGroup(t("Treatment side effects"), d.sideEffects.map { it.first.joinToString(", ") to (it.second?.let { r -> "from $r" } ?: "") }, false, null, null)
        if (d.regimens.isNotEmpty()) ChipGroup(t("Treatment"), d.regimens.map { it to "" }, false, null, null)
        if (d.foods.isNotEmpty()) ChipGroup(t("Foods"), d.foods.map { it to "" }, false, null, null)
        if (d.missed.isNotEmpty()) ChipGroup(t("Missed plans"), d.missed.map { it.name to it.extra() }, false, null, null)
    }
    Text(t("Days without an attack are kept as they are. Untick their category under Use for insights if you don't want them counted."), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp))
    Spacer(Modifier.height(16.dp))
    PrimaryButton(t("Done"), onClick = onDone)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordsScreen(p: JsonObject, onDone: () -> Unit) {
    Title(t("How we read your words"), t("Your labels, next to the ones MigraineMe uses."))
    fun rows(key: String, showWhy: Boolean, newColor: Boolean = false) = p.arr(key).mapNotNull { it as? JsonObject }
    val likely = rows("likely", true); val newItems = rows("new_items", false, true); val certain = rows("certain", false); val foods = rows("foods", false); val pain = rows("pain_locations", false)
    if (likely.isNotEmpty()) BaseCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(t("Read as likely, with the reason"))
        likely.forEachIndexed { i, o -> if (i > 0) HorizontalDivider(color = Edge); Column(Modifier.padding(vertical = 6.dp)) { PairRow(o.str("phrase") ?: "", o.str("label") ?: ""); Text(o.str("reasoning") ?: "", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall) } }
    }
    if (newItems.isNotEmpty()) { Spacer(Modifier.height(10.dp)); BaseCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(t("Added to your lists"))
        newItems.forEachIndexed { i, o -> if (i > 0) HorizontalDivider(color = Edge); Column(Modifier.padding(vertical = 6.dp)) { PairRow(o.str("phrase") ?: "", o.str("label") ?: "", newItem = true); Text(listOfNotNull(t("New"), o.str("class")?.replace('_', ' '), o.str("category")).joinToString(" · ") + " · " + t("seen %1\$s×", o.int("count") ?: 0), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall) } }
    } }
    if (foods.isNotEmpty()) { Spacer(Modifier.height(10.dp)); BaseCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(t("Foods, not triggers"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { foods.forEach { o -> PlainChip(o.str("phrase") ?: "") } }
        Text(t("Foods stay as the trigger you tagged, and can be scored for histamine, tyramine, alcohol and gluten when ticked under Use for insights."), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    } }
    if (pain.isNotEmpty()) { Spacer(Modifier.height(10.dp)); BaseCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(t("Pain positions"))
        pain.forEach { o -> PairRow(o.str("phrase") ?: "", o.strList("ids").joinToString(", ") { it.replace('_', ' ') }) }
    } }
    if (certain.isNotEmpty()) { Spacer(Modifier.height(10.dp)); BaseCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(t("Matched exactly"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { certain.forEach { o -> val ph = o.str("phrase") ?: ""; val lb = o.str("label") ?: ""; PlainChip(if (ph.equals(lb, ignoreCase = true)) ph else "$ph → $lb") } }
    } }
    Spacer(Modifier.height(16.dp))
    PrimaryButton(t("Done"), onClick = onDone)
}

@Composable
private fun PairRow(src: String, dst: String, newItem: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(src, color = AppTheme.TitleColor, style = MaterialTheme.typography.bodyMedium)
        Text("  →  ", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
        Text(dst, color = if (newItem) Color(0xFF7FE0B0) else AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PlainChip(text: String) {
    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(Color.White.copy(alpha = 0.07f)).border(1.dp, Edge, RoundedCornerShape(999.dp)).padding(horizontal = 9.dp, vertical = 3.dp)) { Text(text, color = AppTheme.BodyTextColor, fontSize = 12.sp) }
}

@Composable
private fun DoneScreen(verify: JsonObject?, attacks: Int, p: JsonObject?, paid: Boolean, onRemove: () -> Unit, onClose: () -> Unit) {
    val rows = verify?.obj("rows_per_table")
    val range = p?.obj("range")
    Title(t("Imported"), t("Marked as imported, so you can always tell it apart."))
    HeroCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF7FE0B0).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Check, contentDescription = null, tint = Color(0xFF7FE0B0)) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(t("%1\$s attacks are in your journal", verify?.int("attacks_written") ?: attacks), color = AppTheme.TitleColor, fontWeight = FontWeight.SemiBold)
                if (range?.str("from") != null) Text("${fmtDate(range.str("from")!!)} – ${fmtDate(range.str("to") ?: "")}", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    if (rows != null) BaseCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(t("What was written"))
        val labels = listOf("migraines" to t("Attacks"), "migraine_pain_points" to t("Pain positions"), "migraine_aura_zones" to t("Aura"), "prodromes" to t("Warning signs before"), "symptoms" to t("After-effects"), "triggers" to t("Triggers"), "medicines" to t("Medicines"), "reliefs" to t("Reliefs"), "locations" to t("Places"), "activities" to t("Activities"), "missed_activities" to t("Missed plans"), "nutrition_records" to t("Foods"), "sleep_duration_daily" to t("Sleep"), "treatment_regimens" to t("Treatments"), "treatment_side_effect_logs" to t("Treatment side effects"))
        labels.forEach { (k, l) -> rows.int(k)?.let { n -> Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) { Text(l, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)); Text(n.toString(), color = AppTheme.TitleColor, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall) } } }
    }
    Spacer(Modifier.height(12.dp))
    BaseCard(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(t("What changes"))
        Text("• " + t("Journal, calendar and the doctor report show these attacks."), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
        Text("• " + (if (paid) t("Insights use what you ticked under Use for insights. The rest stays in the journal only.") else t("Insights leave imported data out. Subscribe and import again to count it.")), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
        Text("• " + t("Your imported medicines and reliefs are now favourites in the log wizard."), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton(t("Done"), onClick = onClose)
    Spacer(Modifier.height(10.dp))
    GhostButton(t("Remove this import"), onClick = onRemove)
}
