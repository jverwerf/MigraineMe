package com.migraineme

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/**
 * The client's goals, both those a linked practitioner set and the client's
 * own (practitioner_id NULL), plus 28 days of progress per goal, shared by
 * Monitor, Home and Data settings.
 *
 * refresh() is called on first composition of those screens and after every
 * log, so one tap on Home is reflected on Monitor without a reload.
 *
 * Offline: the last goals and days stay on the phone, and a done that cannot
 * reach the server is saved here, shown in the counts straight away and sent
 * on the next foreground or refresh, against the day it was done.
 */
object PractitionerGoalsStore {
    private const val TAG = "PractitionerGoals"
    /** metric_settings key of the Data settings "Goals" switch. */
    const val GOALS_METRIC = "goals"
    /** Four Mon–Sun weeks is what the detail grid shows; the week strip reads from the same rows. */
    const val PROGRESS_DAYS = 28L

    private val svc = SupabasePractitionerService

    private val _goals = MutableStateFlow<List<SupabasePractitionerService.GoalRow>>(emptyList())
    val goals: StateFlow<List<SupabasePractitionerService.GoalRow>> = _goals

    /** goal id -> rows from goal_daily_progress plus the dones still waiting to be sent, oldest first. */
    private val _progress = MutableStateFlow<Map<String, List<SupabasePractitionerService.GoalProgressRow>>>(emptyMap())
    val progress: StateFlow<Map<String, List<SupabasePractitionerService.GoalProgressRow>>> = _progress

    /** goal_metric_catalog, in display order. Fetched once; it only changes with a server release. */
    private val _metrics = MutableStateFlow<List<SupabasePractitionerService.GoalMetric>>(emptyList())
    val metrics: StateFlow<List<SupabasePractitionerService.GoalMetric>> = _metrics

    /**
     * Whether heart rate goals can fill in by themselves; null until first checked.
     * Local reads only, refreshed with every goals refresh.
     */
    private val _hrFeed = MutableStateFlow<HrFeed?>(null)
    val hrFeed: StateFlow<HrFeed?> = _hrFeed

    suspend fun refreshHrFeed(context: Context) = withContext(Dispatchers.IO + NonCancellable) {
        _hrFeed.value = hrGoalFeed(context)
    }

    /**
     * The Data settings "Goals" switch (metric_settings row 'goals'; no row = on).
     * Cached in prefs so a switched-off user does not see goals flash in on launch.
     */
    private val _goalsOn = MutableStateFlow(true)
    val goalsOn: StateFlow<Boolean> = _goalsOn
    private var goalsOnCacheRead = false
    private const val PREFS = "goals_v2"
    private const val KEY_GOALS_ON = "goals_on"

    private fun readCachedGoalsOn(context: Context) {
        if (goalsOnCacheRead) return
        goalsOnCacheRead = true
        _goalsOn.value = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_GOALS_ON, true)
    }

    private fun storeGoalsOn(context: Context, on: Boolean) {
        _goalsOn.value = on
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_GOALS_ON, on).apply()
    }

    /** Read the switch from metric_settings. A failed read (empty list) keeps the cached value. */
    suspend fun refreshGoalsOn(context: Context) = withContext(Dispatchers.IO + NonCancellable) {
        readCachedGoalsOn(context)
        val rows = runCatching { EdgeFunctionsService().getMetricSettings(context) }.getOrDefault(emptyList())
        if (rows.isEmpty()) return@withContext
        storeGoalsOn(context, rows.firstOrNull { it.metric == GOALS_METRIC }?.enabled ?: true)
    }

    /** Flip the switch through the same upsert as every other Data settings toggle. */
    suspend fun setGoalsOn(context: Context, on: Boolean): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        val before = _goalsOn.value
        storeGoalsOn(context, on)
        val result = DataSettingsToggleHandler.toggleMetric(
            context = context, metric = GOALS_METRIC, enabled = on,
            preferredSource = null, metricSettingsMap = emptyMap()
        )
        val ok = result is DataSettingsToggleHandler.ToggleResult.Success
        if (!ok) storeGoalsOn(context, before)
        ok
    }

    /** The latest weekly review; null until the first one exists. */
    private val _report = MutableStateFlow<SupabasePractitionerService.GoalReportRow?>(null)
    val report: StateFlow<SupabasePractitionerService.GoalReportRow?> = _report

    /** The weekly review for the Goals page. Goal ideas live in the recalibration review. */
    suspend fun refreshReview(context: Context) = withContext(Dispatchers.IO + NonCancellable) {
        val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return@withContext
        runCatching { svc.latestGoalReport(token) }
            .onFailure { Log.w(TAG, "latestGoalReport failed: ${it.message}") }
            .onSuccess { _report.value = it }
    }

    /** Open goal ideas (status 'proposed'), newest first; empty on a failed read. */
    suspend fun openSuggestions(context: Context): List<SupabasePractitionerService.GoalSuggestionRow> =
        withContext(Dispatchers.IO) {
            val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return@withContext emptyList()
            runCatching { svc.goalSuggestions(token) }
                .onFailure { Log.w(TAG, "goalSuggestions failed: ${it.message}") }
                .getOrDefault(emptyList())
        }

    /**
     * Recalibration review "New goals": [accept] become the client's own goals,
     * [dismiss] are closed. Every id is tried; then one reload. False if any failed.
     */
    suspend fun decideSuggestions(context: Context, accept: List<String>, dismiss: List<String>): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return@withContext false
            var allOk = true
            for (id in accept) {
                runCatching { svc.acceptGoalSuggestion(token, id) }
                    .onFailure { allOk = false; Log.w(TAG, "acceptGoalSuggestion failed: ${it.message}") }
            }
            for (id in dismiss) {
                runCatching { svc.dismissGoalSuggestion(token, id) }
                    .onFailure { allOk = false; Log.w(TAG, "dismissGoalSuggestion failed: ${it.message}") }
            }
            if (accept.isNotEmpty()) refresh(token)
            allOk
        }

    fun metricFor(list: List<SupabasePractitionerService.GoalMetric>, key: String?) =
        key?.let { k -> list.firstOrNull { it.key == k } }

    private suspend fun loadMetrics(token: String) {
        if (_metrics.value.isNotEmpty()) return
        runCatching { svc.goalMetrics(token) }
            .onFailure { Log.w(TAG, "goalMetrics failed: ${it.message}") }
            .getOrNull()?.let { _metrics.value = it }
    }

    /** For the editor, which can open before any refresh has run. */
    suspend fun ensureMetrics(context: Context) = withContext(Dispatchers.IO + NonCancellable) {
        refreshHrFeed(context)
        val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return@withContext
        loadMetrics(token)
    }

    suspend fun refresh(context: Context) {
        loadLocalCopy()
        refreshHrFeed(context)
        if (MonitorCardConfig.GOALS_ENABLED) refreshGoalsOn(context)
        val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return
        refresh(token)
    }

    // NonCancellable: callers launch this from a screen's LaunchedEffect, and the
    // user navigating away mid-fetch must not leave the store half-filled
    // ("The coroutine scope left the composition" seen on the emulator 10-01).
    suspend fun refresh(token: String) = withContext(Dispatchers.IO + NonCancellable) {
        flushPending(token)
        val rows = runCatching { svc.myGoals(token) }
            .onFailure { Log.w(TAG, "myGoals failed: ${it.message}") }
            .getOrNull() ?: return@withContext
        _goals.value = rows
        readFromServer = true
        registerPractitionerFilms(token, rows)
        loadMetrics(token)
        val today = LocalDate.now()
        val from = today.minusDays(PROGRESS_DAYS - 1).toString()
        val map = LinkedHashMap<String, List<SupabasePractitionerService.GoalProgressRow>>()
        // A failed read keeps what is already known for that goal.
        for (g in rows) map[g.id] = fetchProgress(token, g.id, from, today.toString()) ?: serverProgress[g.id].orEmpty()
        serverProgress = map
        publishProgress()
        saveLocalCopy(rows.firstOrNull()?.user_id ?: JwtUtils.extractUserIdFromAccessToken(token))
    }

    /** Goals that name a practitioner's own film ("pr:<id>") need that film in the
     *  catalogue before the Exercises screen, the Goals card or the player look it up. */
    private suspend fun registerPractitionerFilms(token: String, rows: List<SupabasePractitionerService.GoalRow>) {
        val ids = rows.mapNotNull { it.exercise_id?.takeIf { id -> id.startsWith(ExerciseCatalogue.PRACTITIONER_PREFIX) } }
            .map { it.removePrefix(ExerciseCatalogue.PRACTITIONER_PREFIX) }.distinct()
        if (ids.isEmpty()) {
            localFilms = emptyList()
            return
        }
        val films = runCatching { svc.practitionerExercises(token, ids) }
            .onFailure { Log.w(TAG, "practitionerExercises failed: ${it.message}") }
            .getOrNull() ?: return
        localFilms = films
        registerFilms(films)
    }

    private fun registerFilms(films: List<SupabasePractitionerService.PractitionerExerciseRow>) {
        ExerciseCatalogue.register(films.map { f ->
            ExerciseRoutine(
                ExerciseCatalogue.PRACTITIONER_PREFIX + f.id,
                f.title,
                "",
                f.minutes?.toInt() ?: 0,
                ExerciseWhen.PREVENT,
                "",
                "",
                "",
                f.description?.takeIf { it.isNotBlank() }?.let { listOf(ExerciseSection("How to do it", listOf(it))) } ?: emptyList(),
                practitionerOnly = true,
                filmUrlAbs = svc.practitionerFilmUrl(f.video_path),
                posterUrlAbs = f.poster_path?.let { svc.practitionerFilmUrl(it) },
            )
        })
    }

    /** Null when the read failed, so the caller can keep what it has. */
    private suspend fun fetchProgress(token: String, goalId: String, from: String, to: String) =
        runCatching { svc.goalProgress(token, goalId, from, to) }
            .onFailure { Log.w(TAG, "goalProgress failed: ${it.message}") }
            .getOrNull()

    /**
     * Log a manual count / minutes for today and refresh that goal's progress.
     * If the server cannot be reached the done is saved on the phone and counts
     * as logged. False only when nobody it belongs to is signed in, or the
     * server refused it.
     */
    suspend fun log(
        context: Context,
        goal: SupabasePractitionerService.GoalRow,
        countDelta: Int = 0,
        minutesDelta: Double = 0.0,
    ): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        val ctx = context.applicationContext
        val today = LocalDate.now()
        val done = PendingDone(
            id = UUID.randomUUID().toString(),
            goal_id = goal.id,
            user_id = goal.user_id,
            date = today.toString(),
            count = countDelta,
            minutes = minutesDelta,
        )
        val token = SessionStore.getValidAccessToken(ctx)
        var failure: Throwable? = null
        if (token != null) {
            failure = runCatching { svc.logGoal(token, goal, done.date, countDelta, minutesDelta) }
                .onFailure { Log.w(TAG, "logGoal failed: ${it.message}") }
                .exceptionOrNull()
            if (failure == null) {
                // Counted even if the read below fails.
                addToServerProgress(done)
                if (flushPending(token)) {
                    refresh(token)
                } else {
                    val from = today.minusDays(PROGRESS_DAYS - 1).toString()
                    fetchProgress(token, goal.id, from, today.toString())?.let { serverProgress = serverProgress + (goal.id to it) }
                    publishProgress()
                    saveLocalCopy(goal.user_id)
                }
                return@withContext true
            }
            if (isRefusal(failure)) return@withContext false
        }
        if (SessionStore.readUserId(ctx) != goal.user_id) return@withContext false
        val unconfirmed = failure as? GoalWriteUnconfirmed
        writePending(ctx, readPending(ctx) + done.copy(
            manual_count_if_landed = unconfirmed?.count,
            manual_minutes_if_landed = unconfirmed?.minutes,
        ))
        publishProgress()
        true
    }

    // ---- offline: the local copy of the goals, and dones waiting for a connection ----

    /** One done the server has not confirmed. Its id is the row's source, so a resend cannot add it twice. */
    @Serializable
    private data class PendingDone(
        val id: String,
        val goal_id: String,
        val user_id: String,
        val date: String,
        val count: Int = 0,
        val minutes: Double = 0.0,
        /** Set when the manual total was sent and no answer came: the manual row holding this means it landed. */
        val manual_count_if_landed: Int? = null,
        val manual_minutes_if_landed: Double? = null,
    )

    @Serializable
    private data class LocalCopy(
        val user_id: String,
        val goals: List<SupabasePractitionerService.GoalRow> = emptyList(),
        val progress: Map<String, List<SupabasePractitionerService.GoalProgressRow>> = emptyMap(),
        val films: List<SupabasePractitionerService.PractitionerExerciseRow> = emptyList(),
    )

    private const val KEY_PENDING = "pending_dones"
    private const val KEY_LOCAL_COPY = "local_copy"
    private const val OFFLINE_SOURCE_PREFIX = "offline:"
    private val localJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pendingLock = Any()
    private val flushMutex = Mutex()

    @Volatile private var appCtx: Context? = null
    /** What the server last said, before the waiting dones are added on top for [progress]. */
    @Volatile private var serverProgress: Map<String, List<SupabasePractitionerService.GoalProgressRow>> = emptyMap()
    @Volatile private var localFilms: List<SupabasePractitionerService.PractitionerExerciseRow> = emptyList()
    @Volatile private var localCopyReadFor: String? = null

    /** The goals have been read from the server since launch (the list may otherwise be the local copy). */
    @Volatile var readFromServer = false
        private set

    fun init(context: Context) {
        appCtx = context.applicationContext
        loadLocalCopy()
    }

    /** Fill an empty store from the phone, for whoever is signed in. Cheap after the first call. */
    fun loadLocalCopy() {
        val ctx = appCtx ?: return
        val uid = SessionStore.readUserId(ctx) ?: return
        synchronized(pendingLock) {
            if (localCopyReadFor == uid) return
            localCopyReadFor = uid
        }
        if (_goals.value.isNotEmpty()) return
        val copy = prefs(ctx).getString(KEY_LOCAL_COPY, null)
            ?.let { raw -> runCatching { localJson.decodeFromString<LocalCopy>(raw) }.getOrNull() }
            ?.takeIf { it.user_id == uid }
            ?: return
        localFilms = copy.films
        registerFilms(copy.films)
        serverProgress = copy.progress
        _goals.value = copy.goals
        publishProgress()
    }

    /** Signed out: the goals on the phone go with the session. Waiting dones stay, tied to their user. */
    fun forgetLocalCopy(context: Context) {
        prefs(context).edit().remove(KEY_LOCAL_COPY).apply()
        localCopyReadFor = null
        localFilms = emptyList()
        serverProgress = emptyMap()
        readFromServer = false
        _goals.value = emptyList()
        _progress.value = emptyMap()
    }

    /** App opened or back in the foreground: send what is waiting, then read the result. */
    suspend fun syncOnForeground(context: Context) = withContext(Dispatchers.IO + NonCancellable) {
        loadLocalCopy()
        if (readPending(context.applicationContext).isEmpty()) return@withContext
        val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return@withContext
        if (flushPending(token)) refresh(token)
    }

    /**
     * Send the waiting dones, oldest first, each under its own source on its
     * original date. Stops at the first failed send and keeps the rest. True if any went out.
     */
    private suspend fun flushPending(token: String): Boolean = flushMutex.withLock {
        val ctx = appCtx ?: return@withLock false
        val uid = JwtUtils.extractUserIdFromAccessToken(token) ?: return@withLock false
        var sent = false
        for (done in readPending(ctx).filter { it.user_id == uid }) {
            val failure = runCatching {
                if (!alreadyLanded(token, done)) {
                    svc.writeGoalLog(token, SupabasePractitionerService.GoalLogRow(
                        goal_id = done.goal_id,
                        user_id = done.user_id,
                        date = done.date,
                        count = done.count,
                        minutes = done.minutes,
                        source = OFFLINE_SOURCE_PREFIX + done.id,
                    ))
                }
            }.onFailure { Log.w(TAG, "queued done not sent: ${it.message}") }.exceptionOrNull()
            if (failure != null && !isRefusal(failure)) break
            // Only drop it once the server has it (or never will, e.g. the goal was deleted).
            writePending(ctx, readPending(ctx).filter { it.id != done.id })
            if (failure == null) {
                addToServerProgress(done)
                sent = true
            }
        }
        if (sent) {
            publishProgress()
            saveLocalCopy(uid)
        }
        sent
    }

    private suspend fun alreadyLanded(token: String, done: PendingDone): Boolean {
        val count = done.manual_count_if_landed ?: return false
        val minutes = done.manual_minutes_if_landed ?: return false
        val row = svc.manualGoalLog(token, done.goal_id, done.date) ?: return false
        return row.count >= count && row.minutes >= minutes
    }

    /** The server looked at it and said no: resending the same row would get the same answer. */
    private fun isRefusal(e: Throwable?) = e is GoalServerAnswer && e.status in setOf(400, 403, 404, 409)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readPending(ctx: Context): List<PendingDone> = synchronized(pendingLock) {
        prefs(ctx).getString(KEY_PENDING, null)
            ?.let { raw -> runCatching { localJson.decodeFromString<List<PendingDone>>(raw) }.getOrNull() }
            .orEmpty()
    }

    private fun writePending(ctx: Context, list: List<PendingDone>) = synchronized(pendingLock) {
        // commit, not apply: a done must survive the app being closed right after the film.
        prefs(ctx).edit().putString(KEY_PENDING, localJson.encodeToString(list)).commit()
    }

    private fun saveLocalCopy(userId: String?) {
        val ctx = appCtx ?: return
        if (userId == null) return
        val copy = LocalCopy(userId, _goals.value, serverProgress, localFilms)
        prefs(ctx).edit().putString(KEY_LOCAL_COPY, localJson.encodeToString(copy)).apply()
    }

    private fun addToServerProgress(done: PendingDone) {
        serverProgress = withDone(serverProgress, done)
    }

    private fun publishProgress() {
        val pending = appCtx?.let { readPending(it) }.orEmpty()
        _progress.value = pending.fold(serverProgress) { map, done -> withDone(map, done) }
    }

    /** The same arithmetic as goal_daily_progress, for one done on top of the rows. */
    private fun withDone(
        map: Map<String, List<SupabasePractitionerService.GoalProgressRow>>,
        done: PendingDone,
    ): Map<String, List<SupabasePractitionerService.GoalProgressRow>> {
        val goal = _goals.value.firstOrNull { it.id == done.goal_id } ?: return map
        if (done.date < LocalDate.now().minusDays(PROGRESS_DAYS - 1).toString()) return map
        val rows = map[goal.id].orEmpty()
        val old = rows.firstOrNull { it.day == done.date }
        val row = when {
            goal.isCounter -> {
                val value = (old?.value ?: 0.0) + done.count
                SupabasePractitionerService.GoalProgressRow(done.date, value, value >= (goal.target_count ?: 1))
            }
            goal.isMindfulness -> {
                val value = (old?.value ?: 0.0) + done.minutes
                SupabasePractitionerService.GoalProgressRow(
                    done.date, value, old?.achieved == true || value >= (goal.target_minutes ?: Double.MAX_VALUE),
                )
            }
            // Heart rate and metric goals: a hand tick marks the day met and invents no reading.
            else -> SupabasePractitionerService.GoalProgressRow(
                done.date, old?.value, old?.achieved == true || done.count > 0, old?.is_estimate == true,
            )
        }
        return map + (goal.id to (rows.filter { it.day != done.date } + row).sortedBy { it.day })
    }

    suspend fun setReminders(context: Context, goalId: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return@withContext false
        val ok = runCatching { svc.setGoalReminders(token, goalId, enabled) }
            .onFailure { Log.w(TAG, "setGoalReminders failed: ${it.message}") }
            .isSuccess
        if (ok) _goals.value = _goals.value.map { if (it.id == goalId) it.copy(reminders_enabled = enabled) else it }
        ok
    }

    /** Save a goal's reminder times ("HH:mm:ss"); none also turns its reminders off. Then reload. */
    suspend fun setReminderTimes(context: Context, goalId: String, times: List<String>): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return@withContext false
            val ok = runCatching {
                svc.setGoalReminderTimes(token, goalId, times)
                if (times.isEmpty()) svc.setGoalReminders(token, goalId, false)
            }.onFailure { Log.w(TAG, "setGoalReminderTimes failed: ${it.message}") }.isSuccess
            if (ok) refresh(token)
            ok
        }

    /** Add (goalId null) or change one of the client's own goals, then reload. */
    suspend fun save(context: Context, goalId: String?, draft: SupabasePractitionerService.GoalDraft): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return@withContext false
            val ok = runCatching {
                if (goalId == null) svc.insertGoal(token, draft) else svc.updateGoal(token, goalId, draft)
            }.onFailure { Log.w(TAG, "save goal failed: ${it.message}") }.isSuccess
            if (ok) refresh(token)
            ok
        }

    /** Delete one of the client's own goals, then reload. */
    suspend fun delete(context: Context, goalId: String): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return@withContext false
        val ok = runCatching { svc.deleteGoal(token, goalId) }
            .onFailure { Log.w(TAG, "deleteGoal failed: ${it.message}") }
            .isSuccess
        if (ok) refresh(token)
        ok
    }

    // ---- read helpers (pure; UI calls them with the collected state) ----

    fun rowFor(rows: List<SupabasePractitionerService.GoalProgressRow>?, day: LocalDate) =
        rows?.firstOrNull { it.day == day.toString() }

    fun todayValue(rows: List<SupabasePractitionerService.GoalProgressRow>?): Double =
        rowFor(rows, LocalDate.now())?.value ?: 0.0

    /** Monday to Sunday of the week holding [anchor]. */
    fun weekDays(anchor: LocalDate = LocalDate.now()): List<LocalDate> {
        val monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0..6).map { monday.plusDays(it.toLong()) }
    }

    fun achievedThisWeek(rows: List<SupabasePractitionerService.GoalProgressRow>?): Int =
        weekDays().count { rowFor(rows, it)?.achieved == true }
}
