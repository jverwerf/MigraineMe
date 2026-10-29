package com.migraineme.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.migraineme.BuildConfig
import com.migraineme.R
import com.migraineme.SessionStore
import com.migraineme.SupabaseDbService
import com.migraineme.appLocale
import com.migraineme.tSync
import kotlinx.coroutines.*
import org.json.JSONArray
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The 4x2 Forecast widget (mock option A): today's gauge, score and zone, the
 * two triggers behind it, and the 7-day mini gauges from the same risk_score_live row
 * the Home gauge reads. Tap anywhere opens Home. No logging here.
 */
class MigraineMeForecastWidget : AppWidgetProvider() {

    companion object {
        private const val TAG = "MigraineMeForecast"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Day(val date: LocalDate?, val score: Double, val percent: Int, val zone: String)

    data class ForecastData(
        val loggedIn: Boolean = false,
        val hasScore: Boolean = false,
        val score: Double = 0.0,
        val zone: String = "NONE",
        val percent: Int = 0,
        val triggers: List<String> = emptyList(),
        val days: List<Day> = emptyList()
    )

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            val data = try {
                fetch(context)
            } catch (e: Exception) {
                Log.e(TAG, "Forecast widget update failed", e)
                ForecastData()
            }
            withContext(Dispatchers.Main) {
                for (id in ids) render(context, mgr, id, data)
            }
            pending.finish()
        }
    }

    private suspend fun fetch(context: Context): ForecastData {
        val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return ForecastData()
        if (!WidgetPremium.isEntitled(context)) return ForecastData(loggedIn = true)
        val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        val live = db.getRiskScoreLive(token) ?: return ForecastData(loggedIn = true)
        return ForecastData(
            loggedIn = true,
            hasScore = true,
            score = live.score,
            zone = live.zone,
            percent = live.percent.coerceIn(0, 100),
            triggers = parseTriggerNames(live.topTriggers).take(2),
            days = parseDays(live.dayRisks).take(7)
        )
    }

    private fun parseTriggerNames(raw: String?): List<String> = runCatching {
        val arr = JSONArray(raw ?: return emptyList())
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
    }.getOrDefault(emptyList())

    private fun parseDays(raw: String?): List<Day> = runCatching {
        val arr = JSONArray(raw ?: return emptyList())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Day(
                date = runCatching { LocalDate.parse(o.optString("date")) }.getOrNull(),
                score = o.optDouble("score", 0.0),
                percent = o.optInt("percent", 0),
                zone = o.optString("zone", "NONE")
            )
        }
    }.getOrDefault(emptyList())

    private fun render(context: Context, mgr: AppWidgetManager, id: Int, data: ForecastData) {
        if (data.loggedIn && !WidgetPremium.isEntitled(context)) {
            mgr.updateAppWidget(id, WidgetIntents.lockedViews(context, 402))
            return
        }
        val views = RemoteViews(context.packageName, R.layout.widget_migraineme_forecast)
        views.setTextViewText(R.id.widget_fc_today, tSync("Today"))

        when {
            !data.loggedIn -> {
                views.setTextViewText(R.id.widget_fc_score, "–")
                views.setTextViewText(R.id.widget_fc_zone, tSync("Sign in"))
                views.setTextColor(R.id.widget_fc_zone, Color.argb(158, 255, 255, 255))
            }
            !data.hasScore -> {
                views.setTextViewText(R.id.widget_fc_score, "–")
                views.setTextViewText(R.id.widget_fc_zone, tSync("No data"))
                views.setTextColor(R.id.widget_fc_zone, Color.argb(158, 255, 255, 255))
            }
            else -> {
                views.setTextViewText(R.id.widget_fc_score, "%.1f".format(data.score))
                views.setTextViewText(R.id.widget_fc_zone, formatZone(data.zone))
                views.setTextColor(R.id.widget_fc_zone, zoneColor(data.zone))
            }
        }

        val lines = listOf(R.id.widget_fc_trigger1, R.id.widget_fc_trigger2)
        lines.forEachIndexed { i, viewId ->
            val name = data.triggers.getOrNull(i)
            views.setTextViewText(viewId, if (name != null) "• ${tSync(name)}" else "")
            views.setViewVisibility(viewId, if (name != null) View.VISIBLE else View.GONE)
        }

        views.setImageViewBitmap(R.id.widget_fc_gauge,
            renderWidgetGauge(context, if (data.hasScore) data.percent else 0, data.score, data.zone))
        views.setImageViewBitmap(R.id.widget_fc_bars, renderDayGauges(context, data.days))
        views.setContentDescription(R.id.widget_fc_bars, tSync("7-day outlook"))
        views.setOnClickPendingIntent(R.id.widget_fc_root, WidgetIntents.openApp(context, 300, null))
        mgr.updateAppWidget(id, views)
    }

    /** Same words as the Risk widget and the Home gauge. */
    private fun formatZone(zone: String): String = when (zone.uppercase()) {
        "HIGH" -> tSync("High"); "MILD" -> tSync("Mild"); "LOW" -> tSync("Low"); else -> tSync("Negligible")
    }

    private fun zoneColor(zone: String): Int = when (zone.uppercase()) {
        "HIGH" -> Color.parseColor("#E57373")
        "MILD" -> Color.parseColor("#FFB74D")
        "LOW" -> Color.parseColor("#81C784")
        else -> Color.argb(158, 255, 255, 255)
    }

    // === 7-day outlook: a mini gauge per day, like the row under the Home gauge ===

    private fun renderDayGauges(context: Context, days: List<Day>): Bitmap {
        val density = context.resources.displayMetrics.density
        val w = (300 * density).toInt()
        // ~4x2 cell ratio, drawn with fitCenter so labels are never stretched.
        val h = (72 * density).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(158, 255, 255, 255)
            textSize = 11 * density
            textAlign = Paint.Align.CENTER
        }
        val todayPaint = Paint(labelPaint).apply {
            color = Color.parseColor("#DCCEFF")
            isFakeBoldText = true
        }
        val stroke = 5 * density
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
            color = Color.argb(36, 255, 255, 255)
        }
        val todayDisc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 185, 123, 255) }
        val purple = Color.parseColor("#B97BFF")
        val pink = Color.parseColor("#FF7BB0")

        val count = 7
        val slot = w / count.toFloat()
        val radius = slot * 0.30f
        val cy = 8 * density + radius + stroke
        val today = LocalDate.now()
        val dayFmt = DateTimeFormatter.ofPattern("EEE", appLocale())

        for (i in 0 until count) {
            val cx = slot * i + slot / 2f
            val d = days.getOrNull(i)
            val date = d?.date ?: today.plusDays(i.toLong())
            val isToday = date == today
            if (isToday) canvas.drawCircle(cx, cy - radius * 0.35f, radius + stroke * 1.6f, todayDisc)

            val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(rect, 180f, 180f, false, trackPaint)
            val pct = d?.percent?.coerceIn(0, 100) ?: 0
            if (pct > 0) {
                val sweep = 180f * pct / 100f
                val segments = 16
                val seg = sweep / segments
                for (j in 0 until segments) {
                    val t = j / (segments - 1f)
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
                        color = blend(purple, pink, t)
                    }
                    canvas.drawArc(rect, 180f + seg * j, seg.coerceAtLeast(0.5f), false, p)
                }
            }

            val label = if (isToday) tSync("Today") else date.format(dayFmt)
            canvas.drawText(label, cx, h - 4 * density, if (isToday) todayPaint else labelPaint)
        }
        return bitmap
    }

    private fun blend(c1: Int, c2: Int, t: Float): Int = Color.rgb(
        (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t).toInt(),
        (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t).toInt(),
        (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t).toInt()
    )
}
