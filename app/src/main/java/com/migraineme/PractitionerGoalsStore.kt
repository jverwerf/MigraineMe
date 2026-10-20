package com.migraineme

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * The client's goals, both those a linked practitioner set and the client's
 * own (practitioner_id NULL), plus 28 days of progress per goal, shared by
 * Monitor, Home and Data settings.
 *
 * refresh() is called on first composition of those screens and after every
 * log, so one tap on Home is reflected on Monitor without a reload.
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

    /** goal id -> rows from goal_daily_progress, oldest first. */
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

    /** Open goal suggestions, newest first. */
    private val _suggestions = MutableStateFlow<List<SupabasePractitionerService.GoalSuggestionRow>>(emptyList())
    val suggestions: StateFlow<List<SupabasePractitionerService.GoalSuggestionRow>> = _suggestions

    /** Weekly review + open suggestions for the Goals page. */
    suspend fun refreshReview(context: Context) = withContext(Dispatchers.IO + NonCancellable) {
        val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return@withContext
        runCatching { svc.latestGoalReport(token) }
            .onFailure { Log.w(TAG, "latestGoalReport failed: ${it.message}") }
            .onSuccess { _report.value = it }
        runCatching { svc.goalSuggestions(token) }
            .onFailure { Log.w(TAG, "goalSuggestions failed: ${it.message}") }
            .onSuccess { _suggestions.value = it }
    }

    /** "Add": the suggestion becomes one of the client's own goals, then reload. */
    suspend fun acceptSuggestion(context: Context, id: String): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return@withContext false
        val ok = runCatching { svc.acceptGoalSuggestion(token, id) }
            .onFailure { Log.w(TAG, "acceptGoalSuggestion failed: ${it.message}") }
            .isSuccess
        if (ok) {
            _suggestions.value = _suggestions.value.filterNot { it.id == id }
            refresh(token)
        }
        ok
    }

    /** "Not now": close the suggestion and drop its card. */
    suspend fun dismissSuggestion(context: Context, id: String): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return@withContext false
        val ok = runCatching { svc.dismissGoalSuggestion(token, id) }
            .onFailure { Log.w(TAG, "dismissGoalSuggestion failed: ${it.message}") }
            .isSuccess
        if (ok) _suggestions.value = _suggestions.value.filterNot { it.id == id }
        ok
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
        refreshHrFeed(context)
        if (MonitorCardConfig.GOALS_ENABLED) refreshGoalsOn(context)
        val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return
        refresh(token)
    }

    // NonCancellable: callers launch this from a screen's LaunchedEffect, and the
    // user navigating away mid-fetch must not leave the store half-filled
    // ("The coroutine scope left the composition" seen on the emulator 10-01).
    suspend fun refresh(token: String) = withContext(Dispatchers.IO + NonCancellable) {
        val rows = runCatching { svc.myGoals(token) }
            .onFailure { Log.w(TAG, "myGoals failed: ${it.message}") }
            .getOrNull() ?: return@withContext
        _goals.value = rows
        loadMetrics(token)
        val today = LocalDate.now()
        val from = today.minusDays(PROGRESS_DAYS - 1).toString()
        val map = LinkedHashMap<String, List<SupabasePractitionerService.GoalProgressRow>>()
        for (g in rows) map[g.id] = fetchProgress(token, g.id, from, today.toString())
        _progress.value = map
    }

    private suspend fun fetchProgress(token: String, goalId: String, from: String, to: String) =
        runCatching { svc.goalProgress(token, goalId, from, to) }
            .onFailure { Log.w(TAG, "goalProgress failed: ${it.message}") }
            .getOrDefault(emptyList())

    /** Log a manual count / minutes for today and refresh that goal's progress. */
    suspend fun log(
        context: Context,
        goal: SupabasePractitionerService.GoalRow,
        countDelta: Int = 0,
        minutesDelta: Double = 0.0,
    ): Boolean = withContext(Dispatchers.IO) {
        val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return@withContext false
        val today = LocalDate.now()
        val ok = runCatching { svc.logGoal(token, goal, today.toString(), countDelta, minutesDelta) }
            .onFailure { Log.w(TAG, "logGoal failed: ${it.message}") }
            .isSuccess
        if (ok) {
            val from = today.minusDays(PROGRESS_DAYS - 1).toString()
            _progress.value = _progress.value + (goal.id to fetchProgress(token, goal.id, from, today.toString()))
        }
        ok
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
