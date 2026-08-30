// FILE: app/src/main/java/com/migraineme/PractitionerScreen.kt
package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.OffsetDateTime

/**
 * The patient's side of the practitioner programme.
 *
 * Everything here is about one question: who can see my diary, and what of it.
 * Connecting, narrowing and revoking all live in the app rather than on the
 * web, because this is the moment a person decides to hand over their health
 * record, and because in six months they will look for the off switch here.
 */

private typealias Scope = SupabasePractitionerService.Scope

/** What each consent covers, in the patient's words. English is the lookup
 *  key, so an untranslated language degrades to this rather than to a blank. */
@Composable
private fun scopeLabel(s: Scope): String = when (s) {
    Scope.ATTACKS -> t("Attacks")
    Scope.SYMPTOMS -> t("Symptoms")
    Scope.PRODROMES -> t("Warning signs")
    Scope.PAIN_LOCATIONS -> t("Where the pain sat")
    Scope.AURA -> t("Aura")
    Scope.ATTACK_NOTES -> t("Your notes")
    Scope.CONTEXT -> t("What you were doing, and missed")
    Scope.TRIGGERS -> t("Triggers")
    Scope.FOOD -> t("Food and drink")
    Scope.MEDICATION -> t("What you took, and whether it helped")
    Scope.SIDE_EFFECTS -> t("Side effects")
    Scope.REGIMENS -> t("Ongoing treatment")
    Scope.NARRATIVE -> t("Your treatment story")
    Scope.SLEEP -> t("Sleep")
    Scope.HEART -> t("Heart rate, HRV and breathing")
    Scope.ACTIVITY -> t("Steps, strain and recovery")
    Scope.BODY_MEASURES -> t("Weight, temperature and glucose")
    Scope.STRESS -> t("Stress")
    Scope.PHONE_USE -> t("Screen time and phone use")
    Scope.WEATHER -> t("Weather where you were")
    Scope.AIR_QUALITY -> t("Pollen and air quality")
    Scope.CYCLE -> t("Menstrual cycle")
    Scope.INSIGHTS -> t("Patterns the app found")
    Scope.SETUP_PROFILE -> t("What you said at setup")
    Scope.RISK -> t("Daily risk score")
}

@Composable
private fun scopeDetail(s: Scope): String = when (s) {
    Scope.ATTACKS -> t("When they happened, how severe, how long.")
    Scope.SYMPTOMS -> t("Nausea, light sensitivity and the rest.")
    Scope.PRODROMES -> t("What you noticed before one started.")
    Scope.PAIN_LOCATIONS -> t("The head diagram, and how the pain moved.")
    Scope.AURA -> t("Which parts of your vision, and for how long.")
    Scope.ATTACK_NOTES -> t("Anything you typed in your own words.")
    Scope.CONTEXT -> t("Where you were, what you were doing, what you had to miss.")
    Scope.TRIGGERS -> t("Everything you logged as a possible trigger.")
    Scope.FOOD -> t("Meals, caffeine, alcohol and flagged ingredients.")
    Scope.MEDICATION -> t("Medicines and reliefs, and how much they helped.")
    Scope.SIDE_EFFECTS -> t("Anything you reported as a side effect.")
    Scope.REGIMENS -> t("Preventives and ongoing treatment, with doses.")
    Scope.NARRATIVE -> t("The written summary of how treatment has gone.")
    Scope.SLEEP -> t("Hours, score, efficiency and disturbances.")
    Scope.HEART -> t("Resting heart rate, HRV, oxygen, breathing rate.")
    Scope.ACTIVITY -> t("Steps, strain, recovery and time in high heart rate.")
    Scope.BODY_MEASURES -> t("Weight, body fat, skin temperature, blood glucose.")
    Scope.STRESS -> t("The stress index built from your heart data.")
    Scope.PHONE_USE -> t("Screen time, unlocks, brightness and volume.")
    Scope.WEATHER -> t("Pressure, temperature and humidity where you were.")
    Scope.AIR_QUALITY -> t("Pollen counts and air pollution.")
    Scope.CYCLE -> t("Period dates and cycle settings.")
    Scope.INSIGHTS -> t("Correlations, symptom profile and recommendations.")
    Scope.SETUP_PROFILE -> t("Your answers when you first set the app up.")
    Scope.RISK -> t("The risk percentage the app showed you each day.")
}

@Composable
private fun scopeList(scopes: Collection<Scope>): String =
    scopes.map { scopeLabel(it).lowercase() }.joinToString(", ")

@Composable
private fun agoText(iso: String?): String {
    if (iso.isNullOrBlank()) return t("never")
    return runCatching {
        val days = Duration.between(OffsetDateTime.parse(iso), OffsetDateTime.now()).toDays()
        when {
            days <= 0L -> t("today")
            days == 1L -> t("yesterday")
            else -> t("%1\$s days ago", days)
        }
    }.getOrDefault(t("never"))
}

@Composable
fun PractitionerScreen(
    onBack: () -> Unit,
    authVm: AuthViewModel = viewModel(),
) {
    // Standalone, nothing above it scrolls, so it provides its own.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PractitionerPanel(authVm, scrolls = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PractitionerPanel(
    authVm: AuthViewModel = viewModel(),
    scrolls: Boolean = false,
    onUpgrade: () -> Unit = {},
) {
    val auth by authVm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var links by remember { mutableStateOf<List<SupabasePractitionerService.LinkRow>>(emptyList()) }
    var directory by remember { mutableStateOf<List<SupabasePractitionerService.PractitionerRow>>(emptyList()) }
    var lastViewed by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<SupabasePractitionerService.LinkRow?>(null) }
    var confirmRevoke by remember { mutableStateOf<SupabasePractitionerService.LinkRow?>(null) }
    var reload by remember { mutableStateOf(0) }
    var connecting by remember { mutableStateOf<SupabasePractitionerService.PractitionerRow?>(null) }
    var requesting by remember { mutableStateOf<SupabasePractitionerService.PractitionerRow?>(null) }
    var viewing by remember { mutableStateOf<SupabasePractitionerService.PractitionerRow?>(null) }
    val uriHandler = LocalUriHandler.current
    val premiumState by PremiumManager.state.collectAsState()

    // Sharing a diary with a practitioner is a premium feature. LOADING is not
    // FREE: sending someone who pays to the paywall is the worse error, so
    // only a settled NOT_ENTITLED turns them away.
    val shareOrUpgrade: (SupabasePractitionerService.PractitionerRow) -> Unit = { p ->
        if (premiumState.access == PremiumAccess.NOT_ENTITLED) {
            onUpgrade()
        } else {
            val existing = links.firstOrNull { it.practitioner_id == p.id }
            when {
                // Already offered and waiting on her. Re-opening the consent
                // sheet here would try to accept on her behalf, which the
                // database refuses; there is nothing to decide until she answers.
                existing?.isOffered == true -> Unit
                existing != null -> editing = existing
                else -> connecting = p
            }
        }
    }

    // A practitioner who already runs her own booking tool gets the button
    // sent straight there. Filing an in-app request would mean asking her to
    // watch a second inbox, which is how a booking goes unanswered.
    val bookOrRequest: (SupabasePractitionerService.PractitionerRow) -> Unit = { p ->
        val booking = p.booking_url?.trim()
        if (!booking.isNullOrEmpty()) uriHandler.openUri(booking) else requesting = p
    }

    LaunchedEffect(auth.accessToken, reload) {
        val token = authVm.getValidAccessToken(context)
        if (token.isNullOrBlank()) { loading = false; return@LaunchedEffect }
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                val l = SupabasePractitionerService.myLinks(token)
                val v = SupabasePractitionerService.lastViewed(token)
                val d = SupabasePractitionerService.directory(token)
                Triple(l, v, d)
            }
        }.onSuccess { (l, v, d) -> links = l; lastViewed = v; directory = d }
            .onFailure { error = it.message }
        loading = false
    }

    val active = links.filter { it.isActive }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))

            if (loading) {
                Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
            }

            error?.let {
                Text(it, color = AppTheme.AccentPink, style = MaterialTheme.typography.bodySmall)
            }

            if (active.isNotEmpty()) {
                SectionLabel(t("Sharing with"))
                active.forEach { link ->
                    ActiveCard(
                        link = link,
                        lastViewedIso = lastViewed[link.practitioner_id] ?: link.last_viewed_at,
                        onChange = { editing = link },
                        onRevoke = { confirmRevoke = link },
                    )
                }
            }

            // The directory, as cards. A practitioner already connected keeps
            // her card too: it is where the patient goes to change what she
            // sees, and it is the only place her own words are.
            if (directory.isNotEmpty()) {
                SectionLabel(t("Practitioners"))
                directory.forEach { p ->
                    val link = links.firstOrNull { it.practitioner_id == p.id }
                    Box(Modifier.clickable { viewing = p }) {
                        PractitionerCard(
                            p = p,
                            link = link,
                            lang = LangPrefs.get().code,
                            onRequestPlace = { bookOrRequest(p) },
                            onShareData = { shareOrUpgrade(p) },
                            onOpenWebsite = { viewing = p },
                        )
                    }
                }
            }


        Spacer(Modifier.height(28.dp))
    }

    editing?.let { link ->
        ConsentSheet(
            practitionerName = link.practitioners?.display_name ?: t("This practitioner"),
            discipline = link.practitioners?.discipline,
            asked = if (link.requested.isNotEmpty()) link.requested else Scope.entries.toSet(),
            alreadyGranted = link.granted,
            isFirstAnswer = false,
            onDismiss = { editing = null },
            onConfirm = { chosen ->
                editing = null
                scope.launch {
                    val token = authVm.getValidAccessToken(context) ?: return@launch
                    runCatching {
                        withContext(Dispatchers.IO) {
                            SupabasePractitionerService.updateScopes(token, link, chosen)
                        }
                    }.onFailure { error = it.message }
                    reload++
                }
            },
        )
    }

    viewing?.let { p ->
        PractitionerDetailSheet(
            p = p,
            link = links.firstOrNull { it.practitioner_id == p.id },
            lang = LangPrefs.get().code,
            onDismiss = { viewing = null },
            onRequestPlace = { viewing = null; bookOrRequest(p) },
            onShareData = { viewing = null; shareOrUpgrade(p) },
        )
    }

    // Connecting for the first time is the same consent question as changing
    // it later, so it is the same sheet; only the row it writes differs.
    connecting?.let { p ->
        val target = p
        ConsentSheet(
            practitionerName = target.display_name,
            discipline = target.discipline,
            asked = Scope.entries.toSet(),
            alreadyGranted = emptySet(),
            isFirstAnswer = true,
            onDismiss = { connecting = null },
            onConfirm = { chosen ->
                connecting = null
                scope.launch {
                    val token = authVm.getValidAccessToken(context) ?: return@launch
                    val uid = auth.userId ?: return@launch
                    runCatching {
                        withContext(Dispatchers.IO) {
                            SupabasePractitionerService.connect(token, uid, target.id, chosen)
                        }
                    }.onFailure { error = it.message }
                    reload++
                }
            },
        )
    }

    requesting?.let { p ->
        AskForPlaceDialog(
            practitionerName = p.display_name,
            onDismiss = { requesting = null },
            onSend = { note ->
                requesting = null
                scope.launch {
                    val token = authVm.getValidAccessToken(context) ?: return@launch
                    val uid = auth.userId ?: return@launch
                    runCatching {
                        withContext(Dispatchers.IO) {
                            SupabasePractitionerService.requestAppointment(token, uid, p.id, note, null)
                        }
                    }.onFailure { error = it.message }
                    reload++
                }
            },
        )
    }

    confirmRevoke?.let { link ->
        val name = link.practitioners?.display_name ?: t("this practitioner")
        AlertDialog(
            onDismissRequest = { confirmRevoke = null },
            containerColor = Color(0xFF2A0C3C),
            titleContentColor = AppTheme.TitleColor,
            textContentColor = AppTheme.BodyTextColor,
            title = { Text(t("Stop sharing with %1\$s?", name)) },
            text = {
                Text(t("They will not be able to see any of your diary from now on. Anything they already wrote down stays with them."))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRevoke = null
                    scope.launch {
                        val token = authVm.getValidAccessToken(context) ?: return@launch
                        runCatching { withContext(Dispatchers.IO) { SupabasePractitionerService.revoke(token, link) } }
                            .onFailure { error = it.message }
                        reload++
                    }
                }, colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.AccentPink)) { Text(t("Stop sharing")) }
            },
            dismissButton = { TextButton(onClick = { confirmRevoke = null }, colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.SubtleTextColor)) { Text(t("Cancel")) } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = AppTheme.SubtleTextColor,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ActiveCard(
    link: SupabasePractitionerService.LinkRow,
    lastViewedIso: String?,
    onChange: () -> Unit,
    onRevoke: () -> Unit,
) {
    val p = link.practitioners
    val name = p?.display_name ?: t("Practitioner")
    val granted = Scope.entries.filter { it in link.granted }
    val refused = link.requested.filter { it !in link.granted }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
        shape = AppTheme.BaseCardShape,
        border = AppTheme.BaseCardBorder,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, fontWeight = FontWeight.SemiBold, color = AppTheme.TitleColor)
            p?.practice_name?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = AppTheme.SubtleTextColor)
            }

            // Twelve ticked rows is a column of the same word over and over.
            // A count with the names beside it says the same thing in two lines.
            val shown = SupabasePractitionerService.SCOPE_GROUPS.mapNotNull { g ->
                val on = g.scopes.count { it in link.granted }
                when {
                    on == 0 -> null
                    on == g.scopes.size -> t(g.title)
                    else -> t("%1\$s (%2\$s/%3\$s)", t(g.title), on, g.scopes.size)
                }
            }
            if (shown.isEmpty()) {
                Text(t("Sharing nothing"), style = MaterialTheme.typography.bodySmall, color = AppTheme.SubtleTextColor)
            } else {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = AppTheme.AccentPurple,
                        modifier = Modifier.size(15.dp).padding(top = 2.dp),
                    )
                    Text(
                        shown.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.BodyTextColor,
                        lineHeight = 17.sp,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Outlined.Visibility,
                    contentDescription = null,
                    tint = AppTheme.SubtleTextColor,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    t("Last looked %1\$s", agoText(lastViewedIso)),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.SubtleTextColor,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onChange, modifier = Modifier.weight(1f)) { Text(t("Change")) }
                TextButton(onClick = onRevoke) { Text(t("Stop sharing")) }
            }
        }
    }
}

/**
 * The consent step.
 *
 * One tap for the fast path — Share everything they asked for — with choosing
 * underneath. Not a list of pre-ticked switches: pre-ticked consent for health
 * data is not consent, and an explicit tap is no slower.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConsentSheet(
    practitionerName: String,
    discipline: String?,
    asked: Set<Scope>,
    alreadyGranted: Set<Scope>,
    isFirstAnswer: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Set<Scope>) -> Unit,
) {
    var choosing by remember { mutableStateOf(!isFirstAnswer) }
    // Everything they asked for is ticked when the sheet opens, so the
    // question is what to hold back rather than starting from nothing and
    // rebuilding the whole list. An existing link opens on what is already
    // shared, because that is the thing being edited.
    var picked by remember {
        mutableStateOf(if (alreadyGranted.isEmpty()) asked else alreadyGranted)
    }

    val role = disciplineLabel(discipline)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF2A0C3C),
        titleContentColor = AppTheme.TitleColor,
        textContentColor = AppTheme.BodyTextColor,
        title = { Text(if (isFirstAnswer) t("Share your diary?") else t("What %1\$s can see", practitionerName)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (isFirstAnswer) {
                    Text(
                        if (role != null)
                            t("%1\$s is a %2\$s. They have asked to see:", practitionerName, role.lowercase())
                        else t("%1\$s has asked to see:", practitionerName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.BodyTextColor,
                    )

                    // The categories, not the twenty five things inside them.
                    // Spelling every leaf out made a paragraph nobody would
                    // read, which is the opposite of informed consent.
                    if (!choosing) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            SupabasePractitionerService.SCOPE_GROUPS
                                .filter { g -> g.scopes.any { it in asked } }
                                .forEach { g ->
                                    Text(
                                        t(g.title),
                                        fontSize = 11.5.sp,
                                        color = Color(0xFFE3CEFF),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(AppTheme.AccentPurple.copy(alpha = 0.16f))
                                            .padding(horizontal = 9.dp, vertical = 4.dp),
                                    )
                                }
                        }
                    }
                }

                if (choosing) {
                    SupabasePractitionerService.SCOPE_GROUPS.forEach { g ->
                        ScopeGroupRow(
                            group = g,
                            asked = asked,
                            picked = picked,
                            onToggleGroup = { on ->
                                picked = if (on) picked + g.scopes else picked - g.scopes.toSet()
                            },
                            onToggleScope = { sc ->
                                picked = if (sc in picked) picked - sc else picked + sc
                            },
                        )
                    }
                } else {
                    TextButton(onClick = { choosing = true }, colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.AccentPurple)) { Text(t("Choose what to share")) }
                }

}
        },
        confirmButton = {
            if (choosing) {
                TextButton(onClick = { onConfirm(picked) }, colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.AccentPurple)) {
                    Text(if (picked.isEmpty() && isFirstAnswer) t("Share nothing") else t("Save"))
                }
            } else {
                Button(onClick = { onConfirm(asked) }, colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple, contentColor = Color.White)) { Text(t("Share all of it")) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.SubtleTextColor)) { Text(t("Not now")) } },
    )
}

/**
 * One category, as a row you can open.
 *
 * The group's own tick is three-state in effect: ticking it takes everything
 * inside, unticking drops everything, and a partial selection is shown as a
 * count rather than pretending to be either.
 */
@Composable
private fun groupDetail(title: String): String = when (title) {
    "Migraines" -> t("Attacks, symptoms, warning signs, pain, aura, your notes")
    "Triggers" -> t("Everything you logged as a possible trigger")
    "Diet" -> t("Meals, caffeine, alcohol and flagged ingredients")
    "Medicines" -> t("What you took, whether it helped, side effects")
    "Treatments" -> t("Ongoing treatment and your treatment story")
    "Sleep" -> t("Hours, score, efficiency and disturbances")
    "Physical Health" -> t("Heart, movement and body measurements")
    "Cognitive" -> t("Stress, screen time and phone use")
    "Environment" -> t("Weather, pollen and air quality where you were")
    "Menstruation" -> t("Period dates and cycle settings")
    "Risk" -> t("The daily risk score the app showed you")
    "Insights" -> t("Patterns the app found, and your setup answers")
    else -> ""
}


@Composable
private fun ScopeGroupRow(
    group: SupabasePractitionerService.ScopeGroup,
    asked: Set<Scope>,
    picked: Set<Scope>,
    onToggleGroup: (Boolean) -> Unit,
    onToggleScope: (Scope) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val inGroup = group.scopes
    val on = inGroup.count { it in picked }
    val all = on == inGroup.size && on > 0
    val some = on in 1 until inGroup.size
    val wasAsked = inGroup.any { it in asked }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { if (inGroup.size == 1) onToggleScope(inGroup.first()) else open = !open }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = all,
                onCheckedChange = { onToggleGroup(!all) },
                colors = CheckboxDefaults.colors(
                    checkedColor = AppTheme.AccentPurple,
                    uncheckedColor = if (some) AppTheme.AccentPurple else AppTheme.SubtleTextColor,
                    checkmarkColor = Color.White,
                ),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    t(group.title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.BodyTextColor,
                    fontWeight = if (wasAsked) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (inGroup.size > 1) {
                    Text(
                        groupDetail(group.title).ifBlank {
                            if (some) t("%1\$s of %2\$s shared", on, inGroup.size) else ""
                        } + if (some) "  ·  " + t("%1\$s of %2\$s", on, inGroup.size) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.SubtleTextColor,
                    )
                } else {
                    Text(
                        groupDetail(group.title).ifBlank { scopeDetail(inGroup.first()) },
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.SubtleTextColor,
                    )
                }
            }
            if (inGroup.size > 1) {
                Icon(
                    if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (open) t("Close") else t("Open"),
                    tint = AppTheme.SubtleTextColor,
                )
            }
        }

        if (open && inGroup.size > 1) {
            Column(Modifier.padding(start = 34.dp, bottom = 4.dp)) {
                inGroup.forEach { sc ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onToggleScope(sc) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = sc in picked,
                            onCheckedChange = { onToggleScope(sc) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = AppTheme.AccentPurple,
                                uncheckedColor = AppTheme.SubtleTextColor,
                                checkmarkColor = Color.White,
                            ),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(scopeLabel(sc), style = MaterialTheme.typography.bodyMedium, color = AppTheme.BodyTextColor)
                            Text(
                                scopeDetail(sc),
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTheme.SubtleTextColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun disciplineLabel(d: String?): String? = when (d) {
    "nutritional_therapist" -> t("nutritional therapist")
    "psychologist" -> t("psychologist")
    "physiotherapist" -> t("physiotherapist")
    "osteopath" -> t("osteopath")
    "naturopath" -> t("naturopath")
    "homeopath" -> t("homeopath")
    "dietitian" -> t("dietitian")
    "neurologist" -> t("neurologist")
    "coach" -> t("migraine coach")
    else -> null
}

/**
 * The way in, sitting at the head of Community's tab row.
 *
 * Not a tab: it navigates rather than selecting, so it never takes the pill.
 * A request waiting on the patient turns the icon pink, which is the only
 * thing on this screen that is asking something of them.
 */
@Composable
fun RowScope.PractitionerTab(
    selected: Boolean,
    onOpen: () -> Unit,
) {
    val pillShape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .padding(4.dp)
            .clip(pillShape)
            .then(
                if (selected) Modifier
                    .background(
                        Brush.linearGradient(
                            listOf(
                                AppTheme.AccentPurple.copy(alpha = 0.45f),
                                AppTheme.AccentPink.copy(alpha = 0.20f),
                            )
                        ),
                        pillShape
                    )
                    .border(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f), pillShape)
                else Modifier
            )
            .clickable { onOpen() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                t("Guidance"),
                color = if (selected) Color.White else AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

/**
 * The practitioner card.
 *
 * Designed with Stephanie and approved by her: a landscape from her practice,
 * her portrait over it, her own line about her work, and the things she
 * treats. A directory of names is not what anyone picks a therapist from.
 *
 * The three actions sit in the order she asked for them: ask for a place,
 * see what she offers, and release your data.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PractitionerCard(
    p: SupabasePractitionerService.PractitionerRow,
    link: SupabasePractitionerService.LinkRow?,
    lang: String,
    onRequestPlace: () -> Unit,
    onShareData: () -> Unit,
    onOpenWebsite: () -> Unit,
    showActions: Boolean = true,
) {
    val bio = p.bioFor(lang)
    val granted = link?.granted.orEmpty()
    // The stat boxes are words, so they belong to a language. The row on the
    // practitioner is the fallback for a translation that has not set its own.
    val offers = p.offersFor(lang)
    val main = offers.firstOrNull { it.kind == "service" }
    val intro = offers.firstOrNull { it.kind == "intro" }
    val facts = listOfNotNull(
        main?.price?.let { SupabasePractitionerService.Fact(t("From"), it) },
        main?.subtitle?.substringBefore(" · ")?.takeIf { it.isNotBlank() }
            ?.let { SupabasePractitionerService.Fact(t("Length"), it) },
        SupabasePractitionerService.Fact(
            t("Intro call"),
            intro?.price ?: t("ask"),
            if (intro != null) "good" else null,
        ),
    ).ifEmpty { bio?.facts?.takeIf { it.isNotEmpty() } ?: p.facts }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF220C33)),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFF3E1D55)),
    ) {
        Column {
            // Banner and head share a box so the portrait genuinely overlaps
            // the photo. offset() would move it visually and still reserve its
            // old space, which leaves a hole under the card.
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(92.dp)) {
                    if (!p.banner_url.isNullOrBlank()) {
                        AsyncImage(
                            model = p.banner_url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.35f to Color.Transparent,
                                    1f to Color(0xFF220C33).copy(alpha = 0.85f),
                                )
                            )
                    )

                    // Which languages she actually works in. Not the languages
                    // the card has been translated into: a card you can read
                    // is no use if you cannot hold the session.
                    if (p.languages.isNotEmpty()) {
                        Row(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color(0xCC160523))
                                .border(1.dp, Color(0x593E1D55), RoundedCornerShape(9.dp))
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                t("Speaks"),
                                fontSize = 9.sp,
                                letterSpacing = 0.6.sp,
                                color = Color(0xFFA991C4),
                            )
                            Text(
                                p.languages.joinToString(" · ") { c ->
                                    Lang.fromCode(c)?.code?.uppercase() ?: c.uppercase()
                                },
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE3CEFF),
                            )
                        }
                    }
                }

                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 18.dp, end = 18.dp)
                        .offset(y = 44.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier
                            .size(width = 78.dp, height = 96.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFF301244))
                            .border(2.dp, Color(0xFF220C33), RoundedCornerShape(18.dp)),
                    ) {
                        if (!p.photo_url.isNullOrBlank()) {
                            AsyncImage(
                                model = p.photo_url,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Column(Modifier.weight(1f).padding(bottom = 6.dp)) {
                        Text(
                            p.display_name,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.BodyTextColor,
                            lineHeight = 23.sp,
                        )
                        bio?.headline?.takeIf { it.isNotBlank() }?.let {
                            Text(it, fontSize = 12.sp, color = Color(0xFFA991C4), lineHeight = 15.sp)
                        }
                    }
                }
            }

            // the height the portrait hangs below the banner
            Spacer(Modifier.height(50.dp))

            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                bio?.quote?.takeIf { it.isNotBlank() }?.let { q ->
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(Modifier.width(2.dp).fillMaxHeight().background(AppTheme.AccentPurple))
                        Text(
                            q,
                            fontSize = 13.5.sp,
                            lineHeight = 19.sp,
                            color = Color(0xFFE4D6F5),
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }

                val treats = bio?.treats.orEmpty()
                if (treats.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        treats.forEachIndexed { i, label ->
                            val lead = i == 0
                            Text(
                                label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (lead) Color(0xFFE3CEFF) else Color(0xFFD8C6EE),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (lead) AppTheme.AccentPurple.copy(alpha = 0.16f) else Color(0xFF301244))
                                    .border(
                                        1.dp,
                                        if (lead) AppTheme.AccentPurple.copy(alpha = 0.45f) else Color(0xFF3E1D55),
                                        RoundedCornerShape(8.dp),
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }

                val meta = bio?.meta.orEmpty()
                if (meta.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        meta.forEach { Text(it, fontSize = 11.5.sp, color = Color(0xFFA991C4), lineHeight = 15.sp) }
                    }
                }

                if (facts.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFF3E1D55), RoundedCornerShape(12.dp)),
                    ) {
                        facts.take(3).forEachIndexed { i, f ->
                            Column(
                                Modifier
                                    .weight(1f)
                                    .background(Color(0xFF220C33))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    f.k.uppercase(),
                                    fontSize = 9.sp,
                                    letterSpacing = 0.8.sp,
                                    color = Color(0xFFA991C4),
                                )
                                Text(
                                    f.v,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when (f.tone) {
                                        "good" -> Color(0xFF81C784)
                                        "warm" -> Color(0xFFE5A80C)
                                        else -> AppTheme.BodyTextColor
                                    },
                                )
                            }
                            if (i < facts.size - 1) {
                                Box(Modifier.width(1.dp).height(46.dp).background(Color(0xFF3E1D55)))
                            }
                        }
                    }
                }

                if (showActions) Button(
                    onClick = onRequestPlace,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppTheme.AccentPurple,
                        contentColor = Color(0xFF20062F),
                    ),
                ) { Text(t("Book an intro call"), fontWeight = FontWeight.Bold, fontSize = 13.5.sp) }

                // Tapping the card already opens her page, so a button that
                // does the same is one decision too many.
                // Sharing is premium: free users see the button blurred under a
                // lock; the tap still lands on the paywall via onShareData.
                if (showActions) PremiumGate(compact = true, message = t("Premium"), onUpgrade = onShareData) {
                    OutlinedButton(
                        onClick = onShareData,
                        modifier = Modifier.fillMaxWidth().height(38.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFF3E1D55)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD9C7F0)),
                    ) {
                        Text(
                            if (granted.isEmpty()) t("Share your data") else t("%1\$s shared", granted.size),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

}
        }
    }
}

/**
 * The practitioner's own page.
 *
 * Everything on it comes from Supabase: her card, her words, her offers and
 * her prices. Nothing about any practitioner is compiled into the app, so
 * adding one is an insert and never a release.
 */
@Composable
fun PractitionerDetailSheet(
    p: SupabasePractitionerService.PractitionerRow,
    link: SupabasePractitionerService.LinkRow?,
    lang: String,
    onDismiss: () -> Unit,
    onRequestPlace: () -> Unit,
    onShareData: () -> Unit,
) {
    val bio = p.bioFor(lang)
    val offers = p.offersFor(lang)
    val uriHandler = LocalUriHandler.current

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = AppTheme.FadeColor,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = t("Back"),
                            tint = AppTheme.SubtleTextColor,
                        )
                    }
                    Text(
                        t("Practitioner"),
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.TitleColor,
                    )
                }

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    PractitionerCard(
                        p = p,
                        link = link,
                        lang = lang,
                        onRequestPlace = onRequestPlace,
                        onShareData = onShareData,
                        onOpenWebsite = { p.website?.let { uriHandler.openUri(it) } },
                        showActions = false,
                    )

                    // How she describes her own work. On the card there is only
                    // room for the quote; this is the page where it belongs.
                    bio?.bio?.takeIf { it.isNotBlank() }?.let { about ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
                            shape = AppTheme.BaseCardShape,
                            border = AppTheme.BaseCardBorder,
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                // A short accent rule, the way her own site
                                // opens a section, so this reads as a statement
                                // rather than a paragraph of small print.
                                Box(
                                    Modifier
                                        .width(34.dp)
                                        .height(2.dp)
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(AppTheme.AccentPurple, AppTheme.AccentPink)
                                            )
                                        )
                                )
                                Text(
                                    t("In her words"),
                                    fontSize = 9.5.sp,
                                    letterSpacing = 1.4.sp,
                                    color = AppTheme.SubtleTextColor,
                                )
                                Text(
                                    about,
                                    fontSize = 14.sp,
                                    lineHeight = 23.sp,
                                    color = AppTheme.BodyTextColor,
                                )
                            }
                        }
                    }

                    p.sectionsFor(lang).forEach { SectionCard(it) }

                    if (offers.isNotEmpty()) {
                        Text(
                            t("Ways of working together"),
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.TitleColor,
                            fontSize = 15.sp,
                        )
                        offers.forEach { OfferCard(it) }
                    }

                    val practical = buildList {
                        p.languages.takeIf { it.isNotEmpty() }?.let {
                            add(t("Languages") to it.joinToString(", ") { c -> Lang.fromCode(c)?.endonym ?: c })
                        }
                        listOfNotNull(p.city, p.country).takeIf { it.isNotEmpty() }?.let {
                            add(t("Where") to it.joinToString(", "))
                        }
                        add(t("Appointments") to when (p.consult_mode) {
                            "in_person" -> t("In person")
                            "online" -> t("Online")
                            else -> t("In person and online")
                        })
                        if (!p.registration_body.isNullOrBlank() && !p.registration_number.isNullOrBlank()) {
                            add(t("Registered with") to "${p.registration_body} · ${p.registration_number}")
                        }
                    }
                    if (practical.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
                            shape = AppTheme.BaseCardShape,
                            border = AppTheme.BaseCardBorder,
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                practical.forEach { (k, v) ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            k,
                                            fontSize = 11.5.sp,
                                            color = AppTheme.SubtleTextColor,
                                            modifier = Modifier.width(112.dp),
                                        )
                                        Text(v, fontSize = 12.5.sp, color = AppTheme.BodyTextColor)
                                    }
                                }
                            }
                        }
                    }

                    p.website?.takeIf { it.isNotBlank() }?.let { site ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { uriHandler.openUri(site) },
                            colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
                            shape = AppTheme.BaseCardShape,
                            border = AppTheme.BaseCardBorder,
                        ) {
                            Row(
                                Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Language,
                                    contentDescription = null,
                                    tint = AppTheme.AccentPurple,
                                    modifier = Modifier.size(18.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        t("Her own site"),
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AppTheme.BodyTextColor,
                                    )
                                    Text(
                                        site.removePrefix("https://").removePrefix("http://"),
                                        fontSize = 11.5.sp,
                                        color = AppTheme.SubtleTextColor,
                                    )
                                }
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = AppTheme.SubtleTextColor,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
                        shape = AppTheme.BaseCardShape,
                        border = AppTheme.BaseCardBorder,
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = null,
                                tint = AppTheme.SubtleTextColor,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                t("Practitioners listed here are independent. MigraineMe does not employ them and takes no part in what you agree with them."),
                                fontSize = 11.5.sp,
                                color = AppTheme.SubtleTextColor,
                                lineHeight = 16.sp,
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                }

                // The actions stay on screen rather than scrolling away with
                // the offers, since deciding is what this page is for.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(AppTheme.FadeColor)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onRequestPlace,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppTheme.AccentPurple,
                            contentColor = Color(0xFF20062F),
                        ),
                    ) { Text(t("Book an intro call"), fontWeight = FontWeight.Bold) }
                    PremiumGate(compact = true, message = t("Premium"), onUpgrade = onShareData) {
                        OutlinedButton(
                            onClick = onShareData,
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF3E1D55)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD9C7F0)),
                        ) {
                            Text(
                                if (link?.granted.isNullOrEmpty()) t("Share your data")
                                else t("Change what they see"),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One of her formats, drawn the way her own site draws them: a photograph, the
 * name, her one line about it, and the practical detail underneath. A plain
 * bordered rectangle does not do the work justice.
 */
@Composable
private fun OfferCard(o: SupabasePractitionerService.Offer) {
    val free = o.kind == "intro"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF220C33)),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0xFF3E1D55)),
    ) {
        Column {
            if (!o.image_url.isNullOrBlank()) {
                Box(Modifier.fillMaxWidth().height(104.dp)) {
                    AsyncImage(
                        model = o.image_url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0.3f to Color.Transparent,
                                1f to Color(0xFF220C33).copy(alpha = 0.92f),
                            )
                        )
                    )
                }
            }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        o.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.BodyTextColor,
                        modifier = Modifier.weight(1f),
                    )
                    o.price?.let {
                        Text(
                            it,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (free) Color(0xFF81C784) else AppTheme.AccentPurple,
                        )
                    }
                }
                // The first bullet is her own line about this format; the rest
                // are the practical points.
                o.bullets.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xFFD5C6E8))
                }
                o.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 11.5.sp, color = AppTheme.SubtleTextColor)
                }
                o.bullets.drop(1).takeIf { it.isNotEmpty() }?.let { rest ->
                    Spacer(Modifier.height(3.dp))
                    rest.forEach { b ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier
                                    .padding(top = 6.dp)
                                    .size(4.dp)
                                    .background(AppTheme.AccentPurple, CircleShape)
                            )
                            Text(b, fontSize = 11.5.sp, color = AppTheme.SubtleTextColor, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

/** A block of her own writing, such as how the work unfolds. */
@Composable
private fun SectionCard(sec: SupabasePractitionerService.Section) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
        shape = AppTheme.BaseCardShape,
        border = AppTheme.BaseCardBorder,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(sec.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.TitleColor)
            sec.body?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 12.sp, color = AppTheme.SubtleTextColor, lineHeight = 17.sp)
            }
            sec.items.forEachIndexed { i, item ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier
                            .padding(top = 2.dp)
                            .size(20.dp)
                            .background(AppTheme.AccentPurple.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${i + 1}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AppTheme.AccentPurple)
                    }
                    Text(item, fontSize = 12.5.sp, color = AppTheme.BodyTextColor, lineHeight = 18.sp)
                }
            }
        }
    }
}

/**
 * Asking for a place.
 *
 * No slot picker: a practitioner has no way to release times yet, so offering
 * a calendar would be showing something that does not exist. It is a request
 * with a short note, which she accepts or declines — which is what was agreed
 * anyway: the yes and the no stay with her.
 */
@Composable
fun AskForPlaceDialog(
    practitionerName: String,
    onDismiss: () -> Unit,
    onSend: (String?) -> Unit,
) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF2A0C3C),
        titleContentColor = AppTheme.TitleColor,
        textContentColor = AppTheme.BodyTextColor,
        title = { Text(t("Ask %1\$s for an intro call", practitionerName.substringBefore(' '))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    t("A first conversation, free and with no obligation. They will see your note and either accept or decline. Nothing is booked, and none of your diary is shared by asking."),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.SubtleTextColor,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(t("What it is about"), color = AppTheme.SubtleTextColor) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.AccentPurple,
                        unfocusedBorderColor = Color(0xFF3E1D55),
                        focusedTextColor = AppTheme.BodyTextColor,
                        unfocusedTextColor = AppTheme.BodyTextColor,
                        cursorColor = AppTheme.AccentPurple,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(note.trim().takeIf { it.isNotEmpty() }) },
                colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.AccentPurple),
            ) { Text(t("Send request")) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.SubtleTextColor),
            ) { Text(t("Cancel")) }
        },
    )
}
