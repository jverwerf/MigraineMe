// FILE: app/src/main/java/com/migraineme/widget/MigraineMeWidget.kt
package com.migraineme.widget

import android.app.PendingIntent
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
import com.migraineme.MainActivity
import com.migraineme.SessionStore
import com.migraineme.SupabaseDbService
import kotlinx.coroutines.*
import java.time.Instant
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MigraineMeWidget : AppWidgetProvider() {

    companion object {
        private const val TAG = "MigraineMeWidget"
        const val ACTION_QUICK_LOG = "com.migraineme.widget.ACTION_QUICK_LOG"
        const val EXTRA_CATEGORY = "extra_category"

        fun requestUpdate(context: Context) {
            val intent = Intent(context, MigraineMeWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, MigraineMeWidget::class.java))
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            context.sendBroadcast(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            updateWidgetLoading(context, mgr, id)
            scope.launch {
                try {
                    val data = fetchWidgetData(context)
                    withContext(Dispatchers.Main) { updateWidget(context, mgr, id, data) }
                } catch (e: Exception) {
                    Log.e(TAG, "Widget update failed", e)
                    withContext(Dispatchers.Main) { updateWidget(context, mgr, id, WidgetData()) }
                }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_QUICK_LOG) {
            val category = intent.getStringExtra(EXTRA_CATEGORY) ?: return
            Log.d(TAG, "Quick log: $category")
            scope.launch {
                try {
                    performQuickLog(context, category)
                    withContext(Dispatchers.Main) { requestUpdate(context) }
                } catch (e: Exception) {
                    Log.e(TAG, "Quick log failed for $category", e)
                }
            }
        }
    }

    override fun onDisabled(context: Context) { scope.cancel() }

    // === Data ===

    data class WidgetData(
        val riskScore: Double = 0.0,
        val riskPercent: Int = 0,
        val riskZone: String = "NONE",
        val loggedIn: Boolean = false
    )

    private suspend fun fetchWidgetData(context: Context): WidgetData {
        val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return WidgetData()
        val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        val live = db.getRiskScoreLive(token) ?: return WidgetData(loggedIn = true)
        return WidgetData(
            riskScore = live.score,
            riskPercent = live.percent.coerceIn(0, 100),
            riskZone = live.zone,
            loggedIn = true
        )
    }

    private suspend fun performQuickLog(context: Context, category: String) {
        val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return
        val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        val now = Instant.now().toString()
        when (category) {
            "Migraine" -> db.insertMigraine(token, "Migraine", 5, now, null, null)
            "Trigger"  -> db.insertTrigger(token, null, "Unknown", now, null)
            "Prodrome" -> db.insertProdrome(token, null, "Unknown", now, null)
            "Medicine" -> db.insertMedicine(token, null, "Unknown", null, now, null)
            "Relief"   -> db.insertRelief(token, null, "Unknown", now, null)
        }
    }

    // === Rendering ===

    private fun updateWidgetLoading(context: Context, mgr: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_migraineme)
        views.setImageViewBitmap(R.id.widget_gauge_image, renderGauge(context, 0, 0.0, "NONE"))
        views.setTextViewText(R.id.widget_score_text, "...")
        views.setTextViewText(R.id.widget_zone_text, "Loading")
        mgr.updateAppWidget(id, views)
    }

    private fun updateWidget(context: Context, mgr: AppWidgetManager, id: Int, data: WidgetData) {
        val views = RemoteViews(context.packageName, R.layout.widget_migraineme)

        views.setImageViewBitmap(
            R.id.widget_gauge_image,
            renderGauge(context, data.riskPercent, data.riskScore, data.riskZone)
        )

        if (!data.loggedIn) {
            views.setTextViewText(R.id.widget_score_text, "\u2013")
            views.setTextViewText(R.id.widget_zone_text, "Sign in")
        } else {
            views.setTextViewText(R.id.widget_score_text, "%.1f".format(data.riskScore))
            views.setTextViewText(R.id.widget_zone_text, formatZone(data.riskZone))
        }

        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        views.setOnClickPendingIntent(
            R.id.widget_gauge_container,
            PendingIntent.getActivity(context, 0, openApp, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        )

        val categories = listOf("Migraine", "Trigger", "Prodrome", "Medicine", "Relief")
        val buttonIds = listOf(R.id.widget_btn_migraine, R.id.widget_btn_trigger, R.id.widget_btn_prodrome, R.id.widget_btn_medicine, R.id.widget_btn_relief)
        val iconIds = listOf(R.id.widget_icon_migraine, R.id.widget_icon_trigger, R.id.widget_icon_prodrome, R.id.widget_icon_medicine, R.id.widget_icon_relief)
        val drawableIds = listOf(R.drawable.ic_widget_migraine, R.drawable.ic_widget_trigger, R.drawable.ic_widget_prodrome, R.drawable.ic_widget_medicine, R.drawable.ic_widget_relief)

        for (i in categories.indices) {
            val pi = PendingIntent.getBroadcast(
                context, 100 + i,
                Intent(context, MigraineMeWidget::class.java).apply {
                    action = ACTION_QUICK_LOG
                    putExtra(EXTRA_CATEGORY, categories[i])
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(buttonIds[i], pi)
            views.setImageViewResource(iconIds[i], drawableIds[i])
        }

        mgr.updateAppWidget(id, views)
    }

    private fun formatZone(zone: String): String = when (zone.uppercase()) {
        "HIGH" -> "High risk"; "MILD" -> "Mild risk"; "LOW" -> "Low risk"; else -> "No data"
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
