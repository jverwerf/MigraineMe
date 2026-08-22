package com.migraineme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate

/**
 * Log something you did NOT do, without a migraine attached.
 *
 * Missed activities used to be reachable only from inside the migraine wizard,
 * so anything given up on a day with no attack could not be recorded at all.
 *
 * On a day with no migraine the screen also asks why, from the trigger and
 * prodrome pools plus a free line. Those rows are marked `anticipated`: given
 * up because an attack was expected. On a day that did have an attack the
 * reason is already obvious, so the question is not asked and the row is a
 * plain miss.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickLogMissedScreen(
    navController: NavController,
    authVm: AuthViewModel,
    missedVm: MissedActivityViewModel = viewModel(),
    triggerVm: TriggerViewModel = viewModel(),
    prodromeVm: ProdromeViewModel = viewModel(),
) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val authState by authVm.state.collectAsState()
    val pool by missedVm.pool.collectAsState()
    val frequent by missedVm.frequent.collectAsState()
    val triggerPool by triggerVm.pool.collectAsState()
    val triggerFreq by triggerVm.frequent.collectAsState()
    val prodromePool by prodromeVm.pool.collectAsState()
    val prodromeFreq by prodromeVm.frequent.collectAsState()

    var hadMigraineToday by remember { mutableStateOf(false) }

    LaunchedEffect(authState.accessToken) {
        val token = authState.accessToken ?: return@LaunchedEffect
        missedVm.loadAll(token)
        triggerVm.loadAll(token)
        prodromeVm.loadAll(token)
        // Any attack touching today, not just an open one: one that started and
        // ended today would otherwise read as a migraine-free day and get the
        // "why did you skip it?" question on a day she knows the answer to.
        hadMigraineToday = withContext(Dispatchers.IO) {
            val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
            runCatching { db.hasMigraineOnDay(token, LocalDate.now()) }.getOrDefault(false)
        }
    }

    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var startAtIso by rememberSaveable { mutableStateOf<String?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val reasons = remember { mutableStateListOf<String>() }
    var showAllTriggers by rememberSaveable { mutableStateOf(false) }
    var showAllProdromes by rememberSaveable { mutableStateOf(false) }

    val notesSpeechLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                notes = if (notes.isBlank()) spoken else "$notes, $spoken"
            }
        }
    }

    val scrollState = rememberScrollState()

    val frequentLabels = remember(frequent, pool) {
        frequent.mapNotNull { pref -> pool.find { it.id == pref.missedActivityId }?.label }
    }
    val allLabels = remember(pool) { pool.map { it.label } }
    val iconKeyByLabel = remember(pool) { pool.associate { it.label to it.iconKey } }

    val triggerFavLabels = remember(triggerFreq, triggerPool) {
        triggerFreq.mapNotNull { pref -> triggerPool.find { it.id == pref.triggerId }?.label }
    }
    val prodromeFavLabels = remember(prodromeFreq, prodromePool) {
        prodromeFreq.mapNotNull { pref -> prodromePool.find { it.id == pref.prodromeId }?.label }
    }
    val triggerLabelsShown = if (showAllTriggers || triggerFavLabels.isEmpty())
        triggerPool.map { it.label } else triggerFavLabels
    val prodromeLabelsShown = if (showAllProdromes || prodromeFavLabels.isEmpty())
        prodromePool.map { it.label } else prodromeFavLabels

    Box {
        ScrollFadeContainer(scrollState = scrollState) { scroll ->
            ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
                HeroCard {
                    Text(
                        t("Quick Log Missed"),
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        t("Something you didn't do today"),
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(Modifier.height(8.dp))

                    Box(Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = selected ?: t("Select activity..."),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(t("Missed activity"), color = AppTheme.SubtleTextColor) },
                            trailingIcon = {
                                IconButton(onClick = { menuOpen = true }) {
                                    Text("▼", color = Color.White)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = AppTheme.AccentPurple,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                            )
                        )

                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (frequentLabels.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(t("Frequent"), fontWeight = FontWeight.Bold) },
                                    onClick = {}, enabled = false
                                )
                                frequentLabels.forEach { label ->
                                    val icon = MissedActivityIcons.forKey(iconKeyByLabel[label])
                                    val brainyId = MissedActivityIcons.drawableForKey(iconKeyByLabel[label])
                                    DropdownMenuItem(
                                        text = { Text(t(label)) },
                                        leadingIcon = if (brainyId != null || icon != null) {{ LogIconImage(drawableId = brainyId, fallback = icon, size = 20.dp, tint = LocalContentColor.current) }} else null,
                                        onClick = { selected = label; menuOpen = false }
                                    )
                                }
                                Divider()
                            }
                            if (allLabels.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(t("All"), fontWeight = FontWeight.Bold) },
                                    onClick = {}, enabled = false
                                )
                                allLabels.forEach { label ->
                                    val icon = MissedActivityIcons.forKey(iconKeyByLabel[label])
                                    val brainyId = MissedActivityIcons.drawableForKey(iconKeyByLabel[label])
                                    DropdownMenuItem(
                                        text = { Text(t(label)) },
                                        leadingIcon = if (brainyId != null || icon != null) {{ LogIconImage(drawableId = brainyId, fallback = icon, size = 20.dp, tint = LocalContentColor.current) }} else null,
                                        onClick = { selected = label; menuOpen = false }
                                    )
                                }
                            }
                        }
                    }
                }

                // Why — only on a day with no attack. With one, the reason is
                // already recorded by the attack itself.
                if (!hadMigraineToday) {
                    BaseCard {
                        Text(
                            t("Why did you skip that?"),
                            color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            t("Optional"),
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.bodySmall
                        )

                        Spacer(Modifier.height(8.dp))
                        Text(t("Triggers"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            triggerLabelsShown.forEach { label ->
                                ReasonChip(label, label in reasons) {
                                    if (label in reasons) reasons.remove(label) else reasons.add(label)
                                }
                            }
                        }
                        if (triggerFavLabels.isNotEmpty() && triggerFavLabels.size < triggerPool.size) {
                            TextButton(onClick = { showAllTriggers = !showAllTriggers }) {
                                Text(
                                    if (showAllTriggers) t("Show favourites only") else t("Show all"),
                                    color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        Text(t("Warning signs"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            prodromeLabelsShown.forEach { label ->
                                ReasonChip(label, label in reasons) {
                                    if (label in reasons) reasons.remove(label) else reasons.add(label)
                                }
                            }
                        }
                        if (prodromeFavLabels.isNotEmpty() && prodromeFavLabels.size < prodromePool.size) {
                            TextButton(onClick = { showAllProdromes = !showAllProdromes }) {
                                Text(
                                    if (showAllProdromes) t("Show favourites only") else t("Show all"),
                                    color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                BaseCard {
                    Text(
                        t("Details"),
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )

                    Column(Modifier.fillMaxWidth()) {
                        Text(t("When was this?"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        AppDateTimePicker(
                            label = startAtIso?.let { formatMissedIsoForDisplay(it) } ?: t("Select time...")
                        ) { iso -> startAtIso = iso }
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text(t("Notes (optional)"), color = AppTheme.SubtleTextColor) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AppTheme.AccentPurple,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                        ),
                        trailingIcon = {
                            IconButton(onClick = {
                                val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                }
                                try { notesSpeechLauncher.launch(intent) } catch (_: Exception) {
                                    android.widget.Toast.makeText(ctx, tSync("Voice input not available"), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Outlined.Mic, contentDescription = t("Voice input"), tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp))
                            }
                        },
                        minLines = 2
                    )
                }

                BaseCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        OutlinedButton(
                            onClick = { navController.popBackStack() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) { Text(t("Cancel")) }

                        Button(
                            onClick = {
                                val token = authState.accessToken
                                val label = selected
                                if (token.isNullOrBlank() || label.isNullOrBlank()) return@Button

                                saving = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            val db = SupabaseDbService(
                                                BuildConfig.SUPABASE_URL,
                                                BuildConfig.SUPABASE_ANON_KEY
                                            )
                                            db.insertMissedActivity(
                                                accessToken = token,
                                                migraineId = null,
                                                type = label,
                                                startAt = startAtIso ?: Instant.now().toString(),
                                                notes = notes.ifBlank { null },
                                                anticipated = !hadMigraineToday,
                                                reasonLabels = if (hadMigraineToday) null else reasons.toList()
                                            )
                                        }
                                        snackbarHostState.showSnackbar(tSync("Logged!"))
                                        navController.popBackStack()
                                    } catch (e: Exception) {
                                        snackbarHostState.showSnackbar(tSync("Error: %1\$s", e.message ?: ""))
                                    } finally {
                                        saving = false
                                    }
                                }
                            },
                            enabled = !saving && selected != null,
                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                        ) {
                            Text(if (saving) t("Saving...") else t("Log Missed"))
                        }
                    }
                }
            }
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun ReasonChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(t(label), style = MaterialTheme.typography.bodySmall) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.White.copy(alpha = 0.06f),
            labelColor = AppTheme.BodyTextColor,
            selectedContainerColor = AppTheme.AccentPurple.copy(alpha = 0.35f),
            selectedLabelColor = Color.White,
        )
    )
}

private fun formatMissedIsoForDisplay(iso: String): String {
    return try {
        java.time.OffsetDateTime.parse(iso)
            .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy HH:mm"))
    } catch (_: Exception) {
        try {
            java.time.LocalDateTime.parse(iso.removeSuffix("Z"))
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy HH:mm"))
        } catch (_: Exception) {
            iso
        }
    }
}
