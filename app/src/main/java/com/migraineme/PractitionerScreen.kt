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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
) {
    val auth by authVm.state.collectAsState()
    val scope = rememberCoroutineScope()

    var links by remember { mutableStateOf<List<SupabasePractitionerService.LinkRow>>(emptyList()) }
    var lastViewed by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<SupabasePractitionerService.LinkRow?>(null) }
    var confirmRevoke by remember { mutableStateOf<SupabasePractitionerService.LinkRow?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(auth.accessToken, reload) {
        val token = auth.accessToken
        if (token.isNullOrBlank()) { loading = false; return@LaunchedEffect }
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                val l = SupabasePractitionerService.myLinks(token)
                val v = SupabasePractitionerService.lastViewed(token)
                l to v
            }
        }.onSuccess { (l, v) -> links = l; lastViewed = v }
            .onFailure { error = it.message }
        loading = false
    }

    val pending = links.filter { it.isPending }
    val active = links.filter { it.isActive }
    val past = links.filter { !it.isPending && !it.isActive }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))

            Text(
                t("A practitioner can only ever see what you tick, and you can change it or stop sharing at any time."),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.SubtleTextColor,
            )

            if (loading) {
                Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
            }

            error?.let {
                Text(it, color = AppTheme.AccentPink, style = MaterialTheme.typography.bodySmall)
            }

            // A request waiting on the patient comes first: it is the only
            // thing on this screen that is asking something of them.
            if (pending.isNotEmpty()) {
                SectionLabel(t("Waiting on you"))
                pending.forEach { link ->
                    PendingCard(link = link, onRespond = { editing = link })
                }
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

            if (!loading && active.isEmpty() && pending.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
                    shape = AppTheme.BaseCardShape,
                    border = AppTheme.BaseCardBorder,
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(t("Nobody can see your diary"), fontWeight = FontWeight.SemiBold, color = AppTheme.TitleColor)
                        Text(
                            t("If you work with a nutritional therapist, physiotherapist or psychologist, they can ask to see the parts of your diary you choose. Nothing is shared until you say yes."),
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.SubtleTextColor,
                        )
                    }
                }
            }

            if (past.isNotEmpty()) {
                SectionLabel(t("Stopped"))
                past.forEach { link ->
                    val name = link.practitioners?.display_name ?: t("Practitioner")
                    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
        shape = AppTheme.BaseCardShape,
        border = AppTheme.BaseCardBorder,
    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(name, fontWeight = FontWeight.SemiBold, color = AppTheme.TitleColor)
                            Text(
                                if (link.status == "revoked")
                                    t("You stopped sharing %1\$s", agoText(link.revoked_at))
                                else t("You declined this request"),
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTheme.SubtleTextColor,
                            )
                        }
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
            isFirstAnswer = link.isPending,
            onDismiss = { editing = null },
            onConfirm = { chosen ->
                val token = auth.accessToken
                editing = null
                if (token != null) scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            if (link.isPending) SupabasePractitionerService.respondToRequest(token, link, chosen)
                            else SupabasePractitionerService.updateScopes(token, link, chosen)
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
                    val token = auth.accessToken
                    confirmRevoke = null
                    if (token != null) scope.launch {
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
private fun PendingCard(
    link: SupabasePractitionerService.LinkRow,
    onRespond: () -> Unit,
) {
    val name = link.practitioners?.display_name ?: t("A practitioner")
    val asked = scopeList(link.requested)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppTheme.HeroCardContainer),
        shape = AppTheme.BaseCardShape,
        border = AppTheme.BaseCardBorder,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("%1\$s would like to see your diary", name), fontWeight = FontWeight.SemiBold, color = AppTheme.TitleColor)
            if (asked.isNotBlank()) {
                Text(
                    t("They have asked for: %1\$s", asked),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.SubtleTextColor,
                )
            }
            Text(
                t("Nothing of yours is visible until you answer."),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.SubtleTextColor,
            )
            Button(onClick = onRespond, modifier = Modifier.fillMaxWidth()) { Text(t("Choose what to share")) }
        }
    }
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(name, fontWeight = FontWeight.SemiBold, color = AppTheme.TitleColor)
            p?.practice_name?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = AppTheme.SubtleTextColor)
            }

            Text(t("They can see"), style = MaterialTheme.typography.labelSmall, color = AppTheme.SubtleTextColor)
            if (granted.isEmpty()) {
                Text(t("Nothing"), style = MaterialTheme.typography.bodySmall, color = AppTheme.BodyTextColor)
            } else {
                // Summarised by category, with a count where it is partial, so
                // the card stays readable at twenty five separate consents.
                SupabasePractitionerService.SCOPE_GROUPS.forEach { g ->
                    val on = g.scopes.count { it in link.granted }
                    if (on == 0) return@forEach
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = AppTheme.AccentPurple,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            if (on == g.scopes.size) t(g.title) else t("%1\$s (%2\$s of %3\$s)", t(g.title), on, g.scopes.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTheme.BodyTextColor,
                        )
                    }
                }
            }

            // Naming what was refused, to the patient, is the counterpart of
            // the dashboard telling the practitioner it was refused rather
            // than showing her an empty chart.
            if (refused.isNotEmpty()) {
                Text(
                    t("You are not sharing: %1\$s", scopeList(refused)),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.SubtleTextColor,
                )
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
    var picked by remember { mutableStateOf(if (isFirstAnswer) asked else alreadyGranted) }

    val role = disciplineLabel(discipline)
    val askedList = scopeList(asked)

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
                            t("%1\$s is a %2\$s. They have asked to see: %3\$s.", practitionerName, role.lowercase(), askedList)
                        else t("%1\$s has asked to see: %2\$s.", practitionerName, askedList),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.BodyTextColor,
                    )
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

                Text(
                    t("You can change this or stop sharing at any time, and you will be able to see when they last looked."),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.SubtleTextColor,
                )
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
                        if (some) t("%1\$s of %2\$s shared", on, inGroup.size)
                        else if (all) t("All %1\$s shared", inGroup.size)
                        else t("Nothing shared"),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.SubtleTextColor,
                    )
                } else {
                    Text(
                        scopeDetail(inGroup.first()),
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
    authVm: AuthViewModel,
    selected: Boolean,
    onOpen: () -> Unit,
) {
    val auth by authVm.state.collectAsState()
    var pending by remember { mutableStateOf(0) }

    LaunchedEffect(auth.accessToken) {
        val token = auth.accessToken ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { SupabasePractitionerService.myLinks(token) } }
            .onSuccess { links -> pending = links.count { it.isPending } }
    }

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
                color = if (selected) Color.White else if (pending > 0) AppTheme.AccentPink else AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected || pending > 0) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
            // A request waiting on the patient is the only thing in this row
            // that asks something of them, so it gets the one dot.
            if (pending > 0) {
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(6.dp).background(AppTheme.AccentPink, CircleShape))
            }
        }
    }
}


