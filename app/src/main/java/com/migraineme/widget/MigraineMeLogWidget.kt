package com.migraineme.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.migraineme.BuildConfig
import com.migraineme.R
import com.migraineme.Routes
import com.migraineme.SessionStore
import com.migraineme.SupabaseDbService
import com.migraineme.appLocale
import com.migraineme.tSync
import kotlinx.coroutines.*
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The 2x2 Log widget. Normally one big Migraine button that opens the full
 * log wizard. While a migraine is open it turns into the Home "in progress"
 * card: Pain / Medicine / Relief for that attack, and End now.
 *
 * Nothing here writes a log row itself. Every log button opens the app on
 * the right screen; only End now touches the database, the same update the
 * Home card's End now makes.
 */
class MigraineMeLogWidget : AppWidgetProvider() {

    companion object {
        private const val TAG = "MigraineMeLogWidget"
        const val ACTION_END_MIGRAINE = "com.migraineme.widget.ACTION_END_MIGRAINE"
        const val EXTRA_MIGRAINE_ID = "extra_migraine_id"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class LogData(
        val loggedIn: Boolean = false,
        val open: SupabaseDbService.MigraineRow? = null,
        val lastStart: String? = null
    )

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            val data = try {
                fetch(context)
            } catch (e: Exception) {
                Log.e(TAG, "Log widget update failed", e)
                LogData()
            }
            withContext(Dispatchers.Main) {
                for (id in ids) render(context, mgr, id, data)
            }
            pending.finish()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_END_MIGRAINE) return
        val id = intent.getStringExtra(EXTRA_MIGRAINE_ID) ?: return
        val pending = goAsync()
        scope.launch {
            try {
                val token = SessionStore.getValidAccessToken(context.applicationContext)
                if (token != null) {
                    SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                        .updateMigraine(token, id, endAt = Instant.now().toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "End migraine failed for $id", e)
            }
            withContext(Dispatchers.Main) { MigraineMeWidget.requestUpdate(context) }
            pending.finish()
        }
    }

    private suspend fun fetch(context: Context): LogData {
        val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return LogData()
        val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        val open = db.getOpenMigraine(token)
        val last = if (open == null) db.getLatestMigraineStart(token) else null
        return LogData(loggedIn = true, open = open, lastStart = last)
    }

    private fun render(context: Context, mgr: AppWidgetManager, id: Int, data: LogData) {
        val views = RemoteViews(context.packageName, R.layout.widget_migraineme_log)
        val open = data.open
        views.setViewVisibility(R.id.widget_log_normal, if (open == null) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_log_attack, if (open == null) View.GONE else View.VISIBLE)
        if (open == null) renderNormal(context, views, data) else renderAttack(context, views, open)
        mgr.updateAppWidget(id, views)
    }

    private fun renderNormal(context: Context, views: RemoteViews, data: LogData) {
        views.setTextViewText(R.id.widget_log_title, tSync("Log"))
        views.setTextViewText(R.id.widget_log_migraine_label, tSync("Migraine"))
        views.setContentDescription(R.id.widget_log_migraine_btn, tSync("Log migraine"))
        val last = when {
            !data.loggedIn -> tSync("Sign in")
            data.lastStart != null -> "${tSync("Last migraine")} ${formatDay(data.lastStart)}"
            else -> ""
        }
        views.setTextViewText(R.id.widget_log_last, last)
        views.setViewVisibility(R.id.widget_log_last, if (last.isEmpty()) View.GONE else View.VISIBLE)
        // Signed out: open the app (it lands on sign-in) instead of the wizard.
        val route = if (data.loggedIn) Routes.LOG_MIGRAINE else null
        views.setOnClickPendingIntent(R.id.widget_log_normal, WidgetIntents.openApp(context, 200, route))
    }

    private fun renderAttack(context: Context, views: RemoteViews, row: SupabaseDbService.MigraineRow) {
        views.setTextViewText(R.id.widget_attack_title, tSync("Migraine in progress"))
        views.setTextViewText(R.id.widget_attack_started, tSync("Started %s", formatStart(row.startAt)))
        views.setTextViewText(R.id.widget_attack_label_pain, tSync("Pain"))
        views.setTextViewText(R.id.widget_attack_label_medicine, tSync("Medicine"))
        views.setTextViewText(R.id.widget_attack_label_relief, tSync("Relief"))
        views.setTextViewText(R.id.widget_attack_end, tSync("End now"))

        // Same routes the Home in-progress card opens for these kinds.
        val start = URLEncoder.encode(row.startAt, "UTF-8")
        views.setOnClickPendingIntent(R.id.widget_attack_header,
            WidgetIntents.openApp(context, 210, "${Routes.EDIT_MIGRAINE}/${row.id}"))
        views.setOnClickPendingIntent(R.id.widget_attack_pain,
            WidgetIntents.openApp(context, 211, "${Routes.JOURNAL_ADD_PAIN}/${row.id}/$start"))
        views.setOnClickPendingIntent(R.id.widget_attack_medicine,
            WidgetIntents.openApp(context, 212, "${Routes.JOURNAL_ADD}/medicine/${row.id}/$start"))
        views.setOnClickPendingIntent(R.id.widget_attack_relief,
            WidgetIntents.openApp(context, 213, "${Routes.JOURNAL_ADD}/relief/${row.id}/$start"))

        val end = PendingIntent.getBroadcast(
            context, 220,
            Intent(context, MigraineMeLogWidget::class.java).apply {
                action = ACTION_END_MIGRAINE
                putExtra(EXTRA_MIGRAINE_ID, row.id)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_attack_end, end)
    }

    /** "13:05" today, "Mon 13:05" otherwise. Same rule as the Home card. */
    private fun formatStart(iso: String): String = try {
        val zoned = ZonedDateTime.parse(iso).withZoneSameInstant(ZoneId.systemDefault())
        val pattern = if (zoned.toLocalDate() == LocalDate.now()) "HH:mm" else "EEE HH:mm"
        zoned.format(DateTimeFormatter.ofPattern(pattern, appLocale()))
    } catch (_: Exception) {
        iso.take(16).replace("T", " ")
    }

    /** Day of the last migraine: "Today", "Yesterday", or "Mon 26 Oct". */
    private fun formatDay(iso: String): String = try {
        val day = ZonedDateTime.parse(iso).withZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now()
        when (day) {
            today -> tSync("Today")
            today.minusDays(1) -> tSync("Yesterday")
            else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM", appLocale()))
        }
    } catch (_: Exception) {
        ""
    }
}
