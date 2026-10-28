// FILE: app/src/main/java/com/migraineme/widget/MigraineMeWidget.kt
package com.migraineme.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.util.Log
import android.widget.RemoteViews
import com.migraineme.BuildConfig
import com.migraineme.R
import com.migraineme.SessionStore
import com.migraineme.SupabaseDbService
import com.migraineme.tSync
import kotlinx.coroutines.*
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The 2x2 Risk widget: today's gauge, score and zone. Tap opens Home.
 *
 * Logging lives in [MigraineMeLogWidget]. This class keeps its old name so
 * widgets people already placed (the old 4x3 risk + quick-log one) carry on
 * working and simply show the new layout.
 */
class MigraineMeWidget : AppWidgetProvider() {

    companion object {
        private const val TAG = "MigraineMeWidget"

        /** Refreshes every placed MigraineMe widget, Risk and Log alike. */
        fun requestUpdate(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            for (cls in listOf(MigraineMeWidget::class.java, MigraineMeLogWidget::class.java)) {
                val ids = mgr.getAppWidgetIds(ComponentName(context, cls))
                if (ids.isEmpty()) continue
                context.sendBroadcast(Intent(context, cls).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                })
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        for (id in ids) updateWidgetLoading(context, mgr, id)
        scope.launch {
            val data = try {
                fetchWidgetData(context)
            } catch (e: Exception) {
                Log.e(TAG, "Widget update failed", e)
                WidgetData()
            }
            withContext(Dispatchers.Main) {
                for (id in ids) updateWidget(context, mgr, id, data)
            }
            pending.finish()
        }
    }

    // === Data ===

    data class WidgetData(
        val riskScore: Double = 0.0,
        val riskPercent: Int = 0,
        val riskZone: String = "NONE",
        val loggedIn: Boolean = false,
        val hasScore: Boolean = true
    )

    private suspend fun fetchWidgetData(context: Context): WidgetData {
        val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return WidgetData()
        val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        val live = db.getRiskScoreLive(token) ?: return WidgetData(loggedIn = true, hasScore = false)
        return WidgetData(
            riskScore = live.score,
            riskPercent = live.percent.coerceIn(0, 100),
            riskZone = live.zone,
            loggedIn = true
        )
    }

    // === Rendering ===

    private fun updateWidgetLoading(context: Context, mgr: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_migraineme)
        views.setImageViewBitmap(R.id.widget_gauge_image, renderGauge(context, 0, 0.0, "NONE"))
        views.setTextViewText(R.id.widget_score_text, "...")
        views.setTextViewText(R.id.widget_zone_text, tSync("Loading"))
        views.setTextColor(R.id.widget_zone_text, Color.argb(158, 255, 255, 255))
        views.setContentDescription(R.id.widget_gauge_image, tSync("Risk gauge"))
        mgr.updateAppWidget(id, views)
    }

    private fun updateWidget(context: Context, mgr: AppWidgetManager, id: Int, data: WidgetData) {
        val views = RemoteViews(context.packageName, R.layout.widget_migraineme)

        views.setImageViewBitmap(
            R.id.widget_gauge_image,
            renderGauge(context, data.riskPercent, data.riskScore, data.riskZone)
        )
        views.setContentDescription(R.id.widget_gauge_image, tSync("Risk gauge"))

        if (!data.loggedIn) {
            views.setTextViewText(R.id.widget_score_text, "\u2013")
            views.setTextViewText(R.id.widget_zone_text, tSync("Sign in"))
            views.setTextColor(R.id.widget_zone_text, Color.argb(158, 255, 255, 255))
        } else if (!data.hasScore) {
            views.setTextViewText(R.id.widget_score_text, "\u2013")
            views.setTextViewText(R.id.widget_zone_text, tSync("No data"))
            views.setTextColor(R.id.widget_zone_text, Color.argb(158, 255, 255, 255))
        } else {
            views.setTextViewText(R.id.widget_score_text, "%.1f".format(data.riskScore))
            views.setTextViewText(R.id.widget_zone_text, formatZone(data.riskZone))
            views.setTextColor(R.id.widget_zone_text, zoneColor(data.riskZone))
        }

        views.setOnClickPendingIntent(R.id.widget_root, WidgetIntents.openApp(context, 0, null))
        mgr.updateAppWidget(id, views)
    }

    /** Same words as the Home gauge (RiskZone labels): a live row with zone NONE is "Negligible", not missing data. */
    private fun formatZone(zone: String): String = when (zone.uppercase()) {
        "HIGH" -> tSync("High"); "MILD" -> tSync("Mild"); "LOW" -> tSync("Low"); else -> tSync("Negligible")
    }

    /** Same zone colours as the in-app gauge label. */
    private fun zoneColor(zone: String): Int = when (zone.uppercase()) {
        "HIGH" -> Color.parseColor("#E57373")
        "MILD" -> Color.parseColor("#FFB74D")
        "LOW" -> Color.parseColor("#81C784")
        else -> Color.argb(158, 255, 255, 255)
    }

    // === Canvas gauge (matches in-app RiskGauge) ===

    private fun renderGauge(context: Context, percent: Int, score: Double, zone: String): Bitmap {
        val density = context.resources.displayMetrics.density
        val w = (220 * density).toInt()
        val h = (130 * density).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val cx = w / 2f
        val cy = h.toFloat() - 4 * density
        val strokeWidth = 16 * density
        val trackWidth = strokeWidth * 0.72f
        val radius = min(w / 2f, h.toFloat()) - strokeWidth - 8 * density
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        // Track
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; this.strokeWidth = trackWidth; strokeCap = Paint.Cap.ROUND
            color = Color.argb(31, 255, 255, 255)
        }
        canvas.drawArc(rect, 180f, 180f, false, trackPaint)

        // Ticks
        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.strokeWidth = 2 * density; strokeCap = Paint.Cap.ROUND; color = Color.argb(36, 255, 255, 255)
        }
        val tickOuter = radius + trackWidth * 0.10f
        val tickInner = radius - trackWidth * 0.55f
        for (i in 0 until 11) {
            val angle = Math.toRadians(180.0 + (180.0 / 10.0) * i)
            canvas.drawLine(
                cx + cos(angle).toFloat() * tickInner, cy + sin(angle).toFloat() * tickInner,
                cx + cos(angle).toFloat() * tickOuter, cy + sin(angle).toFloat() * tickOuter, tickPaint
            )
        }

        val clamped = percent.coerceIn(0, 100)
        val sweep = 180f * clamped / 100f

        // Glow
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; this.strokeWidth = strokeWidth * 1.75f; strokeCap = Paint.Cap.ROUND
            color = Color.argb(56, 185, 123, 255)
        }
        canvas.drawArc(rect, 180f, sweep, false, glowPaint)

        // Gradient arc (purple -> pink)
        val purple = Color.parseColor("#B97BFF")
        val pink = Color.parseColor("#FF7BB0")
        if (clamped > 0) {
            val segments = 42
            val segSweep = sweep / segments
            for (j in 0 until segments) {
                val t = if (segments == 1) 1f else j / (segments - 1f)
                val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; this.strokeWidth = strokeWidth; strokeCap = Paint.Cap.ROUND
                    color = lerpColor(purple, pink, t)
                }
                canvas.drawArc(rect, 180f + segSweep * j, segSweep.coerceAtLeast(0.5f), false, segPaint)
            }
        }

        // End-cap dot
        if (clamped > 0) {
            val endAngle = Math.toRadians((180.0 + sweep))
            val ex = cx + cos(endAngle).toFloat() * radius
            val ey = cy + sin(endAngle).toFloat() * radius
            canvas.drawCircle(ex, ey, strokeWidth * 0.42f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 255, 255, 255) })
            canvas.drawCircle(ex, ey, strokeWidth * 0.30f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(242, 255, 123, 176) })
        }

        return bitmap
    }

    private fun lerpColor(c1: Int, c2: Int, t: Float): Int {
        val r = (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t).toInt()
        val g = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t).toInt()
        val b = (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t).toInt()
        return Color.rgb(r, g, b)
    }
}
