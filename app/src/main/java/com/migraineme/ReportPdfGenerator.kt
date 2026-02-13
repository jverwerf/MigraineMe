// FILE: ReportPdfGenerator.kt
package com.migraineme

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class ReportPdfGenerator(private val context: Context) {

    companion object { private const val TAG = "ReportPDF" }

    // ═══════ Theme ═══════
    private val BG     = Color.parseColor("#1A0028")
    private val CARD   = Color.parseColor("#2A0C3C")
    private val ACCENT = Color.parseColor("#B97BFF")
    private val PINK   = Color.parseColor("#FF7BB0")
    private val TITLE  = Color.parseColor("#DCCEFF")
    private val BODY   = Color.parseColor("#D0D0D0")
    private val SUBTLE = Color.parseColor("#9E9E9E")
    private val BORDER = Color.parseColor("#3D1F52")
    private val SM     = Color.parseColor("#81C784")
    private val SMO    = Color.parseColor("#FFB74D")
    private val SS     = Color.parseColor("#E57373")
    private val LOG_COLORS = mapOf(
        "Triggers" to Color.parseColor("#FF8A65"), "Prodromes" to Color.parseColor("#FFD54F"),
        "Symptoms" to Color.parseColor("#E57373"), "Medicines" to Color.parseColor("#4FC3F7"),
        "Reliefs" to Color.parseColor("#81C784"), "Activities" to Color.parseColor("#BA68C8"),
        "Missed Activities" to Color.parseColor("#FF7043"), "Locations" to Color.parseColor("#4DD0E1")
    )
    private val BAR_COLORS = listOf(Color.parseColor("#FF8A65"), Color.parseColor("#BA68C8"),
        Color.parseColor("#4FC3F7"), Color.parseColor("#81C784"))

    // ═══════ Page ═══════
    private val PW = 595; private val PH = 842; private val M = 36f
    private lateinit var doc: PdfDocument
    private var pn = 0; private var page: PdfDocument.Page? = null
    private var cv: Canvas? = null; private var y = 0f

    data class TimelineCapture(
        val migraine: MigraineSpan,
        val autoBitmap: Bitmap,
        val fullBitmap: Bitmap,
        val events: List<LegendEvent> = emptyList(),
        val autoMetricNames: List<LegendMetric> = emptyList(),
        val fullMetricNames: List<LegendMetric> = emptyList()
    )

    data class LegendEvent(
        val index: Int,
        val name: String,
        val category: String,
        val color: Int,
        val isAutomated: Boolean
    )

    data class LegendMetric(
        val name: String,
        val unit: String,
        val color: Int
    )

    data class ReportData(
        val filteredMigraines: List<MigraineSpan>,
        val timeFrameLabel: String,
        val spiders: InsightsViewModel.FilteredSpiders,
        val enabledMetrics: List<MetricSeries>,
        val autoMetricKeys: Set<String>,
        val allDailyMetrics: Map<String, List<InsightsViewModel.DailyValue>>,
        val timelineCaptures: List<TimelineCapture> = emptyList()
    )

    // ═══════ Public ═══════

    fun generate(data: ReportData): File? {
        return try {
            doc = PdfDocument(); pn = 0; newPage()
            drawCover(data)
            drawStats(data)
            drawLogCompact(data)

            // ── Full Symptoms breakdown ──
            if (hasSymptomsData(data)) { newPage(); drawSymptomsBreakdown(data) }

            // ── All other spider types: each starts on a new page ──
            listOf(
                data.spiders.prodromes, data.spiders.triggers,
                data.spiders.medicines, data.spiders.reliefs,
                data.spiders.locations, data.spiders.activities,
                data.spiders.missedActivities
            ).forEach { sp ->
                if (sp != null && sp.totalLogged > 0) {
                    newPage()
                    drawFullBreakdown(sp, data)
                }
            }

            if (data.timelineCaptures.isNotEmpty()) { newPage(); drawTimelines(data) }
            if (data.enabledMetrics.isNotEmpty()) { newPage(); drawMetrics(data) }
            footer(); finishPage()

            val dir = File(context.cacheDir, "reports"); dir.mkdirs()
            val f = File(dir, "MigraineMe_Report_${System.currentTimeMillis()}.pdf")
            FileOutputStream(f).use { doc.writeTo(it) }; doc.close()
            Log.d(TAG, "Saved ${f.length() / 1024}KB"); f
        } catch (e: Exception) { Log.e(TAG, "Generate failed", e); try { doc.close() } catch (_: Exception) {}; null }
    }

    fun share(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) { try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }, "Share Report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Toast.makeText(context, "Cannot share: ${e.message}", Toast.LENGTH_LONG).show() } }
    }

    // ═══════ Page mgmt ═══════
    private fun newPage() { finishPage(); pn++; page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, pn).create()); cv = page!!.canvas; cv!!.drawColor(BG); y = M }
    private fun finishPage() { page?.let { pageNum(); doc.finishPage(it) }; page = null; cv = null }
    private fun need(h: Float) { if (y + h > PH - M - 20f) newPage() }
    private fun pageNum() { cv?.drawText("$pn", PW / 2f, PH - 10f, tp(SUBTLE, 7f, a = Paint.Align.CENTER)) }
    private fun footer() {
        val p = tp(SUBTLE, 7f, a = Paint.Align.CENTER)
        p.letterSpacing = 0.02f
        cv?.drawText("Generated by MigraineMe  ·  For informational purposes only", PW / 2f, PH - 22f, p)
    }

    // ═══════ Text helpers ═══════

    /** Major section heading — full-width banner with colored left accent */
    private fun heading(t: String, col: Int = ACCENT) {
        need(80f); val c = cv ?: return
        val l = M; val r = PW - M; val top = y; val h = 34f
        // Background strip
        c.drawRoundRect(RectF(l, top, r, top + h), 10f, 10f, fp(Color.parseColor("#2E1245")))
        // Colored left accent bar
        c.drawRoundRect(RectF(l, top, l + 4f, top + h), 2f, 2f, fp(col))
        // Subtle colored glow along top
        c.drawLine(l + 12f, top, r - 12f, top, sp(col, 0.5f, 30))
        // Text in the section color
        c.drawText(t, l + 16f, top + 22f, tp(col, 15f, b = true))
        y += h + 12f
    }

    /** Sub-heading — smaller banner tinted with the section color */
    private fun subHead(t: String, col: Int) {
        need(60f); val c = cv ?: return
        val l = M + 6f; val r = PW - M; val top = y; val h = 22f
        // Base dark bg
        c.drawRoundRect(RectF(l, top, r, top + h), 6f, 6f, fp(Color.parseColor("#1E0E30")))
        // Color tint overlay
        c.drawRoundRect(RectF(l, top, r, top + h), 6f, 6f, fp(col, 25))
        // Colored left accent
        c.drawRoundRect(RectF(l, top + 3f, l + 3f, top + h - 3f), 1.5f, 1.5f, fp(col))
        // Text
        c.drawText(t, l + 12f, top + 15f, tp(col, 9.5f, b = true))
        y += h + 6f
    }

    /** Prominent sub-heading — wider banner, stronger color tint */
    private fun prominentSubHead(t: String, col: Int) {
        need(70f); val c = cv ?: return
        val l = M; val r = PW - M; val top = y; val h = 28f
        // Base dark bg
        c.drawRoundRect(RectF(l, top, r, top + h), 8f, 8f, fp(Color.parseColor("#1E0E30")))
        // Stronger color tint overlay
        c.drawRoundRect(RectF(l, top, r, top + h), 8f, 8f, fp(col, 35))
        // Colored left bar (thicker)
        c.drawRoundRect(RectF(l, top, l + 5f, top + h), 3f, 3f, fp(col, 200))
        // Colored dot
        c.drawCircle(l + 18f, top + h / 2f, 3.5f, fp(col))
        // Text in white for contrast on tinted bg
        c.drawText(t, l + 28f, top + 18f, tp(TITLE, 11f, b = true))
        y += h + 8f
    }

    /** Category header card — colored top edge and subtle color tint */
    private fun catHeader(title: String, sub: String, col: Int, badgeText: String? = null, badgeCol: Int? = null) {
        need(50f); val c = cv ?: return
        val l = M; val r = PW - M; val top = y; val h = 44f
        // Card background with color tint
        c.drawRoundRect(RectF(l, top, r, top + h), 12f, 12f, fp(CARD, 180))
        c.drawRoundRect(RectF(l, top, r, top + h), 12f, 12f, fp(col, 15))
        // Colored top edge
        c.drawRoundRect(RectF(l + 8f, top, r - 8f, top + 3f), 1.5f, 1.5f, fp(col, 160))
        // Border
        c.drawRoundRect(RectF(l, top, r, top + h), 12f, 12f, sp(col, 0.6f, 30))
        // Title
        c.drawText(title.take(36), l + 12f, top + 18f, tp(Color.WHITE, 11f, b = true))
        // Subtitle
        c.drawText(sub, l + 12f, top + 32f, tp(SUBTLE, 8f))
        // Badge
        if (badgeText != null && badgeCol != null) {
            val tw = tp(badgeCol, 8f, true).measureText(badgeText)
            c.drawRoundRect(RectF(r - tw - 18f, top + 8f, r - 4f, top + 22f), 5f, 5f, fp(badgeCol, 45))
            c.drawText(badgeText, r - tw / 2f - 11f, top + 18f, tp(badgeCol, 8f, true, Paint.Align.CENTER))
        }
        y += h + 6f
    }

    private fun tp(col: Int, sz: Float, b: Boolean = false, a: Paint.Align = Paint.Align.LEFT) = Paint().apply {
        color = col; textSize = sz; isAntiAlias = true; textAlign = a
        if (sz <= 10f) letterSpacing = 0.02f
        if (b) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private fun fp(col: Int, al: Int = 255) = Paint().apply { color = col; alpha = al; style = Paint.Style.FILL; isAntiAlias = true }
    private fun sp(col: Int, w: Float = 1f, al: Int = 255) = Paint().apply { color = col; alpha = al; style = Paint.Style.STROKE; strokeWidth = w; isAntiAlias = true }

    // ═══════ Card (matches BaseCard: rounded 14pt, semi-transparent bg, subtle white border) ═══════
    private inline fun card(h: Float, draw: (Canvas, Float, Float, Float, Float) -> Unit) {
        need(h + 14f); val c = cv ?: return
        val l = M; val t = y; val r = PW - M; val bo = y + h
        // Card background — semi-transparent like BaseCardContainer
        c.drawRoundRect(RectF(l, t, r, bo), 14f, 14f, fp(CARD, 166)) // 0.65 alpha = ~166
        // Very subtle white border like BaseCardBorder (0.08 opacity)
        c.drawRoundRect(RectF(l, t, r, bo), 14f, 14f, sp(Color.WHITE, 0.8f, 20))
        val pad = if (h <= 50f) 8f else 12f
        draw(c, l + pad, t + pad, r - pad, bo - pad)
        y = bo + 8f
    }

    /** Hero card — for main section content, slightly more prominent border */
    private inline fun heroCard(h: Float, draw: (Canvas, Float, Float, Float, Float) -> Unit) {
        need(h + 14f); val c = cv ?: return
        val l = M; val t = y; val r = PW - M; val bo = y + h
        // Slightly more opaque background
        c.drawRoundRect(RectF(l, t, r, bo), 16f, 16f, fp(CARD, 199)) // 0.78 alpha = ~199
        // Gradient-ish border: purple top/left, pink bottom/right
        c.drawRoundRect(RectF(l, t, r, bo), 16f, 16f, sp(ACCENT, 1.2f, 100))
        // Pink accent on bottom edge
        c.drawLine(l + 20f, bo, r - 20f, bo, sp(PINK, 0.8f, 60))
        val pad = 14f
        draw(c, l + pad, t + pad, r - pad, bo - pad)
        y = bo + 10f
    }

    // ═══════ Cover ═══════
    private fun drawCover(d: ReportData) {
        val c = cv ?: return

        // Decorative top accent line
        val grad = LinearGradient(M, 60f, PW - M, 60f, ACCENT, PINK, Shader.TileMode.CLAMP)
        val gp = Paint().apply { shader = grad; strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true }
        c.drawLine(M + 40f, 80f, PW - M - 40f, 80f, gp)

        y = 140f
        c.drawText("MigraineMe", PW / 2f, y, tp(TITLE, 32f, true, Paint.Align.CENTER)); y += 26f
        c.drawText("Insights Report", PW / 2f, y, tp(ACCENT, 16f, a = Paint.Align.CENTER)); y += 44f

        // Decorative divider
        c.drawLine(PW / 2f - 60f, y, PW / 2f + 60f, y, gp)
        y += 28f

        c.drawText(d.timeFrameLabel, PW / 2f, y, tp(BODY, 12f, a = Paint.Align.CENTER)); y += 18f
        if (d.filteredMigraines.isNotEmpty()) {
            val fmt = DateTimeFormatter.ofPattern("dd MMM yyyy")
            val fr = d.filteredMigraines.last().start.atZone(ZoneId.systemDefault()).format(fmt)
            val to = d.filteredMigraines.first().start.atZone(ZoneId.systemDefault()).format(fmt)
            c.drawText("$fr  –  $to", PW / 2f, y, tp(SUBTLE, 11f, a = Paint.Align.CENTER))
        }
        y += 50f
    }

    // ═══════ Summary stats ═══════
    private fun drawStats(d: ReportData) {
        val m = d.filteredMigraines; if (m.isEmpty()) return
        val avgS = m.mapNotNull { it.severity }.let { if (it.isEmpty()) 0.0 else it.average() }
        val avgD = m.mapNotNull { mg -> mg.end?.let { (it.toEpochMilli() - mg.start.toEpochMilli()) / 3600000.0 } }.let { if (it.isEmpty()) 0.0 else it.average() }
        heroCard(65f) { c, l, t, r, _ ->
            val cw = (r - l) / 3f
            listOf(Triple(m.size.toString(), "Migraines", 0), Triple(String.format("%.1f", avgS), "Avg Severity", 0), Triple(String.format("%.1fh", avgD), "Avg Duration", 0))
                .forEachIndexed { i, (v, lb, _) -> val cx = l + i * cw + cw / 2f; c.drawText(v, cx, t + 26f, tp(Color.WHITE, 20f, true, Paint.Align.CENTER)); c.drawText(lb, cx, t + 40f, tp(SUBTLE, 9f, a = Paint.Align.CENTER)) }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SYMPTOMS BREAKDOWN (matches SymptomsBreakdownContent exactly)
    // ═══════════════════════════════════════════════════════════════

    private fun hasSymptomsData(d: ReportData): Boolean {
        return d.spiders.painChar?.axes?.isNotEmpty() == true ||
            d.spiders.accompanying?.axes?.isNotEmpty() == true ||
            d.spiders.painLocations?.axes?.isNotEmpty() == true ||
            d.spiders.severityCounts.isNotEmpty() ||
            d.spiders.durationStats != null
    }

    private fun drawSymptomsBreakdown(d: ReportData) {
        val hasPC = d.spiders.painChar?.axes?.isNotEmpty() == true
        val hasAC = d.spiders.accompanying?.axes?.isNotEmpty() == true
        val hasPL = d.spiders.painLocations?.axes?.isNotEmpty() == true
        val hasSev = d.spiders.severityCounts.isNotEmpty()
        val hasDur = d.spiders.durationStats != null
        if (!hasPC && !hasAC && !hasPL && !hasSev && !hasDur) return

        heading("Symptoms", Color.parseColor("#E57373"))

        // Pain Character
        if (hasPC) drawSpiderSection("Pain Character", d.spiders.painChar!!, Color.parseColor("#EF5350"))

        // Accompanying Experience
        if (hasAC) drawSpiderSection("Accompanying Experience", d.spiders.accompanying!!, Color.parseColor("#BA68C8"))

        // Pain Locations
        if (hasPL) drawSpiderSection("Pain Locations", d.spiders.painLocations!!, Color.parseColor("#FF8A65"))

        // Severity Distribution
        if (hasSev) drawSeveritySection(d.spiders.severityCounts)

        // Duration
        if (hasDur) drawDurationSection(d.spiders.durationStats!!)
    }

    /** SpiderSection: title, logged count, spider/bar/stat with 1/2/3 logic */
    private fun drawSpiderSection(title: String, spider: SpiderData, col: Int) {
        val axes = spider.axes; if (axes.isEmpty()) return
        subHead("$title — ${spider.totalLogged} logged · ${axes.size} types", col)
        drawAxesVisual(axes, col, null)
    }

    private fun drawSeveritySection(cts: List<Pair<Int, Int>>) {
        val sevCol = Color.parseColor("#4FC3F7")
        val total = cts.sumOf { it.second }; val vals = cts.flatMap { (s, c) -> List(c) { s } }
        if (vals.isEmpty()) return
        val mn = vals.min(); val mx = vals.max(); val avg = vals.average()
        subHead("Severity Distribution — $total migraines rated", sevCol)
        // Stats row
        heroCard(46f) { c, l, t, r, _ ->
            val cw = (r - l) / 3f
            listOf(Triple("$mn", "Lowest", SM), Triple(String.format("%.1f", avg), "Average", sevCol), Triple("$mx", "Highest", SS))
                .forEachIndexed { i, (v, lb, cl) -> val cx = l + i * cw + cw / 2f; c.drawText(v, cx, t + 16f, tp(cl, 16f, true, Paint.Align.CENTER)); c.drawText(lb, cx, t + 28f, tp(SUBTLE, 8f, a = Paint.Align.CENTER)) }
        }
        val sevAxes = cts.map { (s, c) -> SpiderAxis("Level $s", c.toFloat()) }
        drawAxesVisual(sevAxes, sevCol, null)
    }

    private fun drawDurationSection(stats: InsightsViewModel.DurationStats) {
        val durCol = Color.parseColor("#81C784")
        subHead("Duration — ${stats.durations.size} migraines with end time", durCol)
        heroCard(46f) { c, l, t, r, _ ->
            val cw = (r - l) / 3f
            listOf(Triple(fmtDur(stats.minHours), "Shortest", SM), Triple(fmtDur(stats.avgHours), "Average", durCol), Triple(fmtDur(stats.maxHours), "Longest", SS))
                .forEachIndexed { i, (v, lb, cl) -> val cx = l + i * cw + cw / 2f; c.drawText(v, cx, t + 16f, tp(cl, 16f, true, Paint.Align.CENTER)); c.drawText(lb, cx, t + 28f, tp(SUBTLE, 8f, a = Paint.Align.CENTER)) }
        }
        // Buckets
        if (stats.durations.size > 1) {
            val buckets = mutableMapOf<String, Int>()
            for (h in stats.durations) {
                val b = when { h < 1f -> "< 1h"; h < 4f -> "1-4h"; h < 12f -> "4-12h"; h < 24f -> "12-24h"; h < 48f -> "1 day"; h < 72f -> "2 days"; else -> "3+ days" }
                buckets[b] = (buckets[b] ?: 0) + 1
            }
            val ordered = listOf("< 1h", "1-4h", "4-12h", "12-24h", "1 day", "2 days", "3+ days")
            val durAxes = ordered.filter { (buckets[it] ?: 0) > 0 }.map { SpiderAxis(it, (buckets[it] ?: 0).toFloat()) }
            drawAxesVisual(durAxes, durCol, null)
        }
    }

    private fun fmtDur(h: Float) = when { h < 1f -> "${(h * 60).toInt()}m"; h < 24f -> "%.1fh".format(h); else -> "%.1fd".format(h / 24f) }

    // ═══════════════════════════════════════════════════════════════
    //  FULL BREAKDOWN (matches InsightsBreakdownScreen)
    //  Categories Overview → Usage vs Effectiveness → Per-Category cards
    // ═══════════════════════════════════════════════════════════════

    private fun drawFullBreakdown(sp: SpiderData, data: ReportData) {
        val col = LOG_COLORS[sp.logType] ?: ACCENT
        heading("${sp.logType} — ${sp.totalLogged} logged · ${sp.breakdown.size} categories", col)

        // 1. Categories Overview spider
        prominentSubHead("Categories Overview", col)
        drawAxesVisual(sp.axes, col, null)

        // 2. Usage vs Effectiveness (Medicines / Reliefs only)
        if (sp.logType == "Medicines" || sp.logType == "Reliefs") {
            val eff = if (sp.logType == "Medicines") data.spiders.medicineEffectiveness else data.spiders.reliefEffectiveness
            if (eff.isNotEmpty() && eff.size >= 3) {
                prominentSubHead("Usage vs Effectiveness", col)
                val countAxes = eff.map { SpiderAxis(it.category, it.count.toFloat()) }
                val reliefAxes = eff.map { SpiderAxis(it.category, it.avgRelief, 3f) }
                drawDualSpider(countAxes, col, reliefAxes, Color.WHITE)
                // Legend
                need(14f); val c = cv ?: return
                c.drawCircle(M + 20f, y + 5f, 4f, fp(col)); c.drawText("Count", M + 28f, y + 9f, tp(SUBTLE, 8f))
                c.drawCircle(M + 80f, y + 5f, 4f, fp(Color.WHITE, 150)); c.drawText("Avg Relief", M + 88f, y + 9f, tp(SUBTLE, 8f))
                y += 14f
            }
        }

        // 3. Per-category subcategory spider cards
        val isMedRel = sp.logType == "Medicines" || sp.logType == "Reliefs"
        val itemEffMap = when (sp.logType) {
            "Medicines" -> data.spiders.medicineItemEffectiveness
            "Reliefs" -> data.spiders.reliefItemEffectiveness
            else -> emptyMap()
        }
        val catEff = if (isMedRel) {
            if (sp.logType == "Medicines") data.spiders.medicineEffectiveness else data.spiders.reliefEffectiveness
        } else emptyList()

        for (cat in sp.breakdown) {
            val effItem = catEff.find { it.category == cat.categoryName }
            val cardTitle = cat.categoryName
            val cardSub = "${cat.totalCount} logged · ${cat.items.size} types"

            // Effectiveness badge
            val badgeText = effItem?.let {
                when { it.avgRelief >= 2.5f -> "High"; it.avgRelief >= 1.5f -> "Mild"; it.avgRelief >= 0.5f -> "Low"; else -> "None" }
            }
            val badgeCol = effItem?.let {
                when { it.avgRelief >= 2.5f -> SM; it.avgRelief >= 1.5f -> SMO; it.avgRelief >= 0.5f -> SS; else -> Color.parseColor("#666666") }
            }

            catHeader(cardTitle, cardSub, col, badgeText, badgeCol)

            // Sub-axes
            val subAxes = cat.items.map { SpiderAxis(it.first, it.second.toFloat()) }
            val subRelief = if (isMedRel && itemEffMap.isNotEmpty()) {
                cat.items.map { SpiderAxis(it.first, itemEffMap[it.first.lowercase()] ?: 0f, 3f) }
            } else null

            drawAxesVisual(subAxes, col, subRelief)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  VISUAL RENDERERS (1/2/3+ logic)
    // ═══════════════════════════════════════════════════════════════

    private fun drawAxesVisual(axes: List<SpiderAxis>, col: Int, secondAxes: List<SpiderAxis>?) {
        if (axes.isEmpty()) return
        when {
            axes.size >= 3 -> if (secondAxes != null && secondAxes.size == axes.size) drawDualSpider(axes, col, secondAxes, Color.WHITE) else drawSpiderWeb(axes, col)
            axes.size == 2 -> drawProportionalBar(axes, col, secondAxes)
            else -> drawStatCard(axes[0], col, secondAxes?.firstOrNull())
        }
    }

    private fun drawSpiderWeb(axes: List<SpiderAxis>, col: Int) {
        val n = axes.size
        val h = if (n > 6) 260f else 220f
        card(h) { c, l, t, r, b ->
            val cx = (l + r) / 2f; val cy = (t + b) / 2f
            val rad = min((r - l) / 2f - 48f, (b - t) / 2f - 24f)
            drawGrid(c, n, cx, cy, rad)
            val maxV = axes.mapNotNull { it.maxValue }.maxOrNull()?.takeIf { it > 0f } ?: axes.maxOf { it.value }.takeIf { it > 0f } ?: 1f
            drawFill(c, axes, cx, cy, rad, maxV, col, 50, 1.5f)
            drawLabels(c, axes, n, cx, cy, rad)
        }
    }

    private fun drawDualSpider(primary: List<SpiderAxis>, col: Int, secondary: List<SpiderAxis>, secCol: Int) {
        val n = primary.size
        val h = if (n > 6) 260f else 220f
        card(h) { c, l, t, r, b ->
            val cx = (l + r) / 2f; val cy = (t + b) / 2f
            val rad = min((r - l) / 2f - 48f, (b - t) / 2f - 24f)
            drawGrid(c, n, cx, cy, rad)

            // Primary polygon (count)
            val maxV1 = primary.mapNotNull { it.maxValue }.maxOrNull()?.takeIf { it > 0f } ?: primary.maxOf { it.value }.takeIf { it > 0f } ?: 1f
            drawFill(c, primary, cx, cy, rad, maxV1, col, 40, 1.5f)

            // At each vertex, draw effectiveness circle (sized by relief value)
            for (i in 0 until n) {
                val a = Math.PI * 2 * i / n - Math.PI / 2
                val norm = (primary[i].value / maxV1).coerceIn(0f, 1f)
                val frac = if (primary[i].value > 0f && norm < 0.08f) 0.08f else norm
                val px = cx + (frac * rad * cos(a)).toFloat()
                val py = cy + (frac * rad * sin(a)).toFloat()

                // Effectiveness circle at this vertex
                if (i < secondary.size) {
                    val reliefVal = secondary[i].value
                    val maxRelief = secondary[i].maxValue ?: 3f
                    val reliefFrac = (reliefVal / maxRelief).coerceIn(0f, 1f)
                    if (reliefFrac > 0f) {
                        val maxCircleR = rad * 0.12f
                        val circleR = maxCircleR * reliefFrac
                        c.drawCircle(px, py, circleR, fp(secCol, 50))
                        c.drawCircle(px, py, circleR, sp(secCol, 1.5f, 100))
                    }
                }

                // Vertex dot
                c.drawCircle(px, py, 3.5f, fp(col))
                c.drawCircle(px, py, 1.5f, fp(Color.WHITE))
            }

            drawLabels(c, primary, n, cx, cy, rad, secondary)
        }
    }

    private fun drawGrid(c: Canvas, n: Int, cx: Float, cy: Float, rad: Float) {
        val g = sp(Color.WHITE, 0.5f, 20)
        // Circular grid rings
        for (ring in 1..4) {
            val rr = rad * ring / 4f
            c.drawCircle(cx, cy, rr, g)
        }
        // Axis lines from centre to each tip
        for (i in 0 until n) { val a = Math.PI * 2 * i / n - Math.PI / 2; c.drawLine(cx, cy, cx + (rad * cos(a)).toFloat(), cy + (rad * sin(a)).toFloat(), g) }
    }

    private fun drawFill(c: Canvas, axes: List<SpiderAxis>, cx: Float, cy: Float, rad: Float, maxV: Float, col: Int, fa: Int, sw: Float) {
        val n = axes.size; val fill = Path(); val stroke = Path()
        for (i in 0 until n) { val a = Math.PI * 2 * i / n - Math.PI / 2; val v = (axes[i].value / maxV).coerceIn(0f, 1f) * rad; val px = cx + (v * cos(a)).toFloat(); val py = cy + (v * sin(a)).toFloat(); if (i == 0) { fill.moveTo(px, py); stroke.moveTo(px, py) } else { fill.lineTo(px, py); stroke.lineTo(px, py) } }
        fill.close(); stroke.close(); c.drawPath(fill, fp(col, fa)); c.drawPath(stroke, sp(col, sw))
    }

    private fun drawLabels(c: Canvas, axes: List<SpiderAxis>, n: Int, cx: Float, cy: Float, rad: Float, secondAxes: List<SpiderAxis>? = null) {
        val fontSize = if (n > 8) 5.5f else if (n > 5) 6f else 7f
        val maxChars = if (n > 8) 16 else if (n > 5) 18 else 22
        val offset = if (n > 8) 12f else 16f
        val lp = tp(BODY, fontSize)
        lp.letterSpacing = 0.03f
        for (i in 0 until n) {
            val a = Math.PI * 2 * i / n - Math.PI / 2
            val lx = cx + ((rad + offset) * cos(a)).toFloat()
            val ly = cy + ((rad + offset) * sin(a)).toFloat()
            val align = when { cos(a) > 0.3 -> Paint.Align.LEFT; cos(a) < -0.3 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            lp.textAlign = align
            // Label with count: "Analgesic (2)"
            c.drawText("${cleanLabel(axes[i].label).take(maxChars)} (${axes[i].value.toInt()})", lx, ly + 3f, lp)

            // Relief effectiveness label below
            if (secondAxes != null && i < secondAxes.size) {
                val rv = secondAxes[i].value
                val rl = when { rv >= 2.5f -> "High"; rv >= 1.5f -> "Mild"; rv >= 0.5f -> "Low"; else -> "None" }
                val rc = when { rv >= 2.5f -> SM; rv >= 1.5f -> SMO; rv >= 0.5f -> SS; else -> Color.parseColor("#999999") }
                val rp = tp(rc, fontSize - 0.5f)
                rp.textAlign = align
                c.drawText(rl, lx, ly + 3f + fontSize + 2f, rp)
            }
        }
    }

    /** Clean up label text: add space after colons, underscores to spaces, capitalize */
    private fun cleanLabel(raw: String): String {
        return raw
            .replace("_", " ")
            .replace(":", ": ")
            .replace(":  ", ": ")  // avoid double space if already had space
            .replaceFirstChar { it.uppercase() }
    }

    private fun drawProportionalBar(axes: List<SpiderAxis>, col: Int, relief: List<SpiderAxis>? = null) {
        val total = axes.sumOf { it.value.toDouble() }.toFloat().coerceAtLeast(1f)
        val hasRelief = relief != null && relief.isNotEmpty()
        val h = if (hasRelief) 78f else 66f
        card(h) { c, l, t, r, _ ->
            axes.forEachIndexed { i, axis ->
                val ac = BAR_COLORS.getOrElse(i) { col }; val pct = (axis.value / total * 100).toInt()
                val x = if (i == 0) l else r; val align = if (i == 0) Paint.Align.LEFT else Paint.Align.RIGHT
                c.drawText(cleanLabel(axis.label), x, t + 12f, tp(ac, 10f, true, align))
                c.drawText("${axis.value.toInt()} ($pct%)", x, t + 24f, tp(SUBTLE, 8f, a = align))
                // Effectiveness badge under percentage
                if (hasRelief && i < relief!!.size) {
                    val rv = relief[i].value
                    val rl = when { rv >= 2.5f -> "High"; rv >= 1.5f -> "Mild"; rv >= 0.5f -> "Low"; else -> "None" }
                    val rc = when { rv >= 2.5f -> SM; rv >= 1.5f -> SMO; rv >= 0.5f -> SS; else -> Color.parseColor("#666666") }
                    val tw = tp(rc, 6.5f, true).measureText(rl) + 10f
                    val bx = if (i == 0) l else r - tw
                    c.drawRoundRect(RectF(bx, t + 28f, bx + tw, t + 40f), 3f, 3f, fp(rc, 40))
                    c.drawText(rl, bx + tw / 2f, t + 37f, tp(rc, 6.5f, true, Paint.Align.CENTER))
                }
            }
            val by = t + (if (hasRelief) 46f else 32f); var bx = l
            axes.forEachIndexed { i, axis -> val pw = (axis.value / total) * (r - l); c.drawRoundRect(RectF(bx, by, bx + pw, by + 10f), 5f, 5f, fp(BAR_COLORS.getOrElse(i) { col }, 200)); bx += pw }
        }
    }

    private fun drawStatCard(axis: SpiderAxis, col: Int, relief: SpiderAxis? = null) {
        val h = if (relief != null) 68f else 56f
        card(h) { c, l, t, r, _ ->
            val cx = (l + r) / 2f
            c.drawText("${axis.value.toInt()}", cx, t + 22f, tp(col, 24f, true, Paint.Align.CENTER))
            c.drawText(cleanLabel(axis.label), cx, t + 36f, tp(BODY, 10f, a = Paint.Align.CENTER))
            if (relief != null) {
                val rv = relief.value
                val rl = when { rv >= 2.5f -> "High"; rv >= 1.5f -> "Mild"; rv >= 0.5f -> "Low"; else -> "None" }
                val rc = when { rv >= 2.5f -> SM; rv >= 1.5f -> SMO; rv >= 0.5f -> SS; else -> Color.parseColor("#666666") }
                val tw = tp(rc, 7f, true).measureText(rl) + 10f
                c.drawRoundRect(RectF(cx - tw / 2f, t + 42f, cx + tw / 2f, t + 54f), 3f, 3f, fp(rc, 40))
                c.drawText(rl, cx, t + 51f, tp(rc, 7f, true, Paint.Align.CENTER))
            }
        }
    }

    // ═══════ Metrics ═══════
    private fun drawMetrics(d: ReportData) {
        heading("Health Metrics", Color.parseColor("#4DD0E1"))
        val zone = ZoneId.systemDefault()

        // Compute date range from filtered migraines (with padding for window)
        val migDates = d.filteredMigraines.map { mg ->
            val dateStr = mg.start.atZone(zone).toLocalDate().toString()
            Triple(dateStr, mg.severity ?: 0, mg)
        }
        val migLocalDates = d.filteredMigraines.map { it.start.atZone(zone).toLocalDate() }
        val rangeStart = migLocalDates.minOrNull()?.minusDays(7)?.toString()
        val rangeEnd = migLocalDates.maxOrNull()?.let { last ->
            d.filteredMigraines.find { it.start.atZone(zone).toLocalDate() == last }?.end
                ?.let { it.atZone(zone).toLocalDate().plusDays(7).toString() }
                ?: last.plusDays(7).toString()
        }

        d.enabledMetrics.forEach { series ->
            val allValues = d.allDailyMetrics[series.key] ?: return@forEach; if (allValues.isEmpty()) return@forEach
            // Filter to timeframe range
            val values = if (rangeStart != null && rangeEnd != null) {
                allValues.filter { it.date >= rangeStart && it.date <= rangeEnd }
            } else allValues
            if (values.isEmpty()) return@forEach
            val sorted = values.sortedBy { it.date }; val argb = cToA(series.color)
            card(110f) { c, l, t, r, b ->
                // Label line
                c.drawText("${series.label}  (${series.unit})", l, t + 12f, tp(ACCENT, 10f, true))
                if (series.key in d.autoMetricKeys) {
                    val aw = tp(PINK, 6f, true).measureText("AUTO") + 10f
                    val labelW = tp(ACCENT, 10f, true).measureText("${series.label}  (${series.unit})")
                    val ax = l + labelW + 10f
                    c.drawRoundRect(RectF(ax, t + 2f, ax + aw, t + 14f), 4f, 4f, fp(PINK, 30))
                    c.drawText("AUTO", ax + 5f, t + 11f, tp(PINK, 6f, true))
                }
                if (sorted.size < 2) { c.drawText("${sorted.firstOrNull()?.value ?: "N/A"}", l, t + 40f, tp(BODY, 11f)); return@card }
                val minV = sorted.minOf { it.value }; val maxV = sorted.maxOf { it.value }; val rng = if (maxV - minV < 0.001) 1.0 else maxV - minV
                val yAxisW = 30f // space for Y labels
                val cl2 = l + yAxisW; val ct = t + 24f; val cb = b - 18f; val cw = r - cl2; val ch = cb - ct

                // Date range for X mapping
                val firstDate = sorted.first().date; val lastDate = sorted.last().date
                val daySpan = java.time.temporal.ChronoUnit.DAYS.between(
                    java.time.LocalDate.parse(firstDate), java.time.LocalDate.parse(lastDate)
                ).toFloat().coerceAtLeast(1f)
                fun dateX(ds: String): Float {
                    val days = java.time.temporal.ChronoUnit.DAYS.between(
                        java.time.LocalDate.parse(firstDate), java.time.LocalDate.parse(ds)
                    ).toFloat()
                    return cl2 + (days / daySpan) * cw
                }
                fun valY(v: Double): Float = cb - ((v - minV) / rng).toFloat() * ch

                // Y-axis: 3 horizontal gridlines (min, mid, max)
                val gridP = sp(BORDER, 0.5f, 30)
                val yLabelP = tp(SUBTLE, 5.5f, a = Paint.Align.RIGHT)
                val midV = (minV + maxV) / 2.0
                listOf(maxV, midV, minV).forEach { v ->
                    val gy = valY(v)
                    c.drawLine(cl2, gy, r, gy, gridP)
                    c.drawText(String.format("%.1f", v), cl2 - 3f, gy + 3f, yLabelP)
                }

                // X-axis line
                c.drawLine(cl2, cb, r, cb, sp(BORDER, 0.5f, 50))

                // X-axis date labels — show ~4-5 evenly spaced
                val xDateFmt = DateTimeFormatter.ofPattern("dd MMM")
                val labelCount = 5.coerceAtMost(daySpan.toInt() + 1)
                val xLabelP = tp(SUBTLE, 5.5f, a = Paint.Align.CENTER)
                for (i in 0 until labelCount) {
                    val frac = if (labelCount <= 1) 0f else i.toFloat() / (labelCount - 1)
                    val daysOff = (frac * daySpan).toLong()
                    val ld = java.time.LocalDate.parse(firstDate).plusDays(daysOff)
                    val dx = cl2 + frac * cw
                    c.drawText(ld.format(xDateFmt), dx, cb + 10f, xLabelP)
                    // Small tick
                    c.drawLine(dx, cb, dx, cb + 3f, sp(BORDER, 0.5f, 50))
                }

                // Y-axis vertical line
                c.drawLine(cl2, ct, cl2, cb, sp(BORDER, 0.5f, 50))

                // Draw migraine markers (behind the line)
                val dashP = Paint().apply {
                    color = PINK; alpha = 100; style = Paint.Style.STROKE; strokeWidth = 1f; isAntiAlias = true
                    pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
                }
                val sevP = tp(PINK, 6f, true, Paint.Align.CENTER)
                for ((mDate, sev, _) in migDates) {
                    val daysFromStart = java.time.temporal.ChronoUnit.DAYS.between(
                        java.time.LocalDate.parse(firstDate), java.time.LocalDate.parse(mDate)
                    ).toFloat()
                    if (daysFromStart < 0 || daysFromStart > daySpan) continue
                    val mx = cl2 + (daysFromStart / daySpan) * cw
                    val dashPath = Path(); dashPath.moveTo(mx, ct); dashPath.lineTo(mx, cb)
                    c.drawPath(dashPath, dashP)
                    if (sev > 0) {
                        c.drawRoundRect(RectF(mx - 7f, ct - 8f, mx + 7f, ct), 3f, 3f, fp(PINK, 60))
                        c.drawText("$sev", mx, ct - 1.5f, sevP)
                    }
                }

                // Sparkline using date-based X
                val path = Path()
                sorted.forEachIndexed { i, dv ->
                    val x = dateX(dv.date); val py = valY(dv.value)
                    if (i == 0) path.moveTo(x, py) else path.lineTo(x, py)
                }
                c.drawPath(path, sp(argb, 1.5f))
                // Data points
                sorted.forEach { dv -> c.drawCircle(dateX(dv.date), valY(dv.value).toFloat(), 1.5f, fp(argb)) }

                // Avg line
                val avg = sorted.map { it.value }.average()
                val avgY = valY(avg)
                val avgDashP = Paint().apply {
                    color = argb; alpha = 60; style = Paint.Style.STROKE; strokeWidth = 0.5f; isAntiAlias = true
                    pathEffect = DashPathEffect(floatArrayOf(3f, 3f), 0f)
                }
                val avgPath = Path(); avgPath.moveTo(cl2, avgY); avgPath.lineTo(r, avgY)
                c.drawPath(avgPath, avgDashP)
                c.drawText("Avg: ${String.format("%.1f", avg)}", l, cb + 10f, tp(SUBTLE, 5.5f))
            }
        }
    }

    // ═══════ Timelines ═══════
    private fun drawTimelines(d: ReportData) {
        heading("Migraine Timelines", ACCENT)
        val fmt = DateTimeFormatter.ofPattern("dd MMM yyyy  HH:mm")
        val contentWidth = PW - 2 * M

        for (cap in d.timelineCaptures) {
            val mg = cap.migraine
            val dateStr = mg.start.atZone(ZoneId.systemDefault()).format(fmt)
            val sevStr = mg.severity?.let { "Severity $it" } ?: ""
            val durStr = mg.end?.let {
                val h = (it.toEpochMilli() - mg.start.toEpochMilli()) / 3600000.0
                String.format("%.1fh", h)
            } ?: "ongoing"
            subHead("$dateStr  ·  $sevStr  ·  $durStr", ACCENT)

            // Auto-detected metrics graph + legend
            drawTimelineBitmap(cap.autoBitmap, contentWidth, "Auto-detected Metrics")
            drawMetricLegend(cap.autoMetricNames)
            drawEventLegend(cap.events)

            // Full overlay graph + legend (only if different)
            if (cap.fullBitmap !== cap.autoBitmap) {
                drawTimelineBitmap(cap.fullBitmap, contentWidth, "All Metrics Overlay")
                drawMetricLegend(cap.fullMetricNames)
            }
        }
    }

    private fun drawTimelineBitmap(bmp: Bitmap, contentWidth: Float, label: String) {
        val scale = contentWidth / bmp.width.toFloat()
        val scaledH = bmp.height * scale
        need(scaledH + 28f)
        val c = cv ?: return
        c.drawText(label, M + 4f, y + 10f, tp(SUBTLE, 7.5f, b = true))
        y += 14f
        val src = Rect(0, 0, bmp.width, bmp.height)
        val dst = RectF(M, y, M + contentWidth, y + scaledH)
        c.drawRoundRect(RectF(M - 2f, y - 2f, M + contentWidth + 2f, y + scaledH + 2f), 8f, 8f, sp(BORDER, 0.8f, 50))
        c.drawBitmap(bmp, src, dst, Paint().apply { isFilterBitmap = true; isAntiAlias = true })
        y += scaledH + 8f
    }

    /** Draw metric line legend — colored line + label */
    private fun drawMetricLegend(metrics: List<LegendMetric>) {
        if (metrics.isEmpty()) return
        val cols = 3
        val colW = (PW - 2 * M) / cols
        val rows = (metrics.size + cols - 1) / cols
        val rowH = 12f
        need(rows * rowH + 8f)
        val c = cv ?: return

        metrics.forEachIndexed { i, m ->
            val col = i % cols
            val row = i / cols
            val x = M + col * colW
            val ly = y + row * rowH

            // Colored line
            c.drawLine(x, ly + 5f, x + 16f, ly + 5f, sp(m.color, 2f))
            // Diamond point
            c.drawCircle(x + 8f, ly + 5f, 2.5f, fp(m.color))
            // Label
            c.drawText("${m.name} (${m.unit})", x + 20f, ly + 8f, tp(BODY, 6.5f))
        }
        y += rows * rowH + 6f
    }

    /** Draw event legend — colored dot per category + numbered item names matching graph */
    private fun drawEventLegend(events: List<LegendEvent>) {
        if (events.isEmpty()) return
        val byCat = events.groupBy { it.category }
        val c = cv ?: return

        for ((cat, items) in byCat) {
            val catColor = items.first().color

            need(22f)
            // Category dot + name
            c.drawCircle(M + 6f, y + 5f, 3.5f, fp(catColor))
            c.drawText("$cat:", M + 14f, y + 8f, tp(catColor, 7f, b = true))

            // Numbered items matching graph dot numbers
            val textP = tp(BODY, 6.5f)
            val maxW = PW - 2 * M - 20f
            val itemTexts = items.map { "#${it.index} ${it.name}" }
            val joined = itemTexts.joinToString("  ·  ")
            val measured = textP.measureText(joined)

            if (measured <= maxW) {
                y += 11f
                c.drawText(joined, M + 18f, y + 7f, textP)
                y += 10f
            } else {
                // Wrap into lines
                val remaining = itemTexts.toMutableList()
                while (remaining.isNotEmpty()) {
                    var line = remaining.removeFirst()
                    while (remaining.isNotEmpty() && textP.measureText("$line  ·  ${remaining.first()}") <= maxW) {
                        line += "  ·  ${remaining.removeFirst()}"
                    }
                    y += 10f; need(10f)
                    c.drawText(line, M + 18f, y + 7f, textP)
                }
                y += 4f
            }
        }
        y += 4f
    }

    // ═══════ Log (compact, on cover page) ═══════
    private fun drawLogCompact(d: ReportData) {
        val m = d.filteredMigraines; if (m.isEmpty()) return
        subHead("Migraine Log (${m.size})", PINK)
        val fmt = DateTimeFormatter.ofPattern("dd MMM yy  HH:mm")
        card(28f) { c, l, t, r, _ ->
            c.drawText("Date", l, t + 9f, tp(ACCENT, 7f, true))
            c.drawText("Sev", l + (r - l) * 0.40f, t + 9f, tp(ACCENT, 7f, true))
            c.drawText("Duration", l + (r - l) * 0.48f, t + 9f, tp(ACCENT, 7f, true))
            c.drawText("Type", l + (r - l) * 0.65f, t + 9f, tp(ACCENT, 7f, true))
        }
        m.forEach { mg ->
            card(26f) { c, l, t, r, _ ->
                c.drawText(mg.start.atZone(ZoneId.systemDefault()).format(fmt), l, t + 9f, tp(BODY, 7f))
                val sc = when { (mg.severity ?: 0) <= 3 -> SM; (mg.severity ?: 0) <= 6 -> SMO; else -> SS }
                c.drawText(mg.severity?.toString() ?: "–", l + (r - l) * 0.42f, t + 9f, tp(sc, 7.5f, true))
                c.drawText(mg.end?.let { String.format("%.1fh", (it.toEpochMilli() - mg.start.toEpochMilli()) / 3600000.0) } ?: "ongoing", l + (r - l) * 0.48f, t + 9f, tp(BODY, 7f))
                c.drawText(mg.label?.take(30) ?: "–", l + (r - l) * 0.65f, t + 9f, tp(BODY, 6.5f))
            }
        }
    }

    // ═══════ Log (full, kept for reference) ═══════
    private fun drawLog(d: ReportData) {
        val m = d.filteredMigraines; if (m.isEmpty()) return; heading("Migraine Log (${m.size})", PINK)
        val fmt = DateTimeFormatter.ofPattern("dd MMM yy  HH:mm")
        card(32f) { c, l, t, r, _ ->
            c.drawText("Date", l, t + 10f, tp(ACCENT, 8f, true))
            c.drawText("Sev", l + (r - l) * 0.38f, t + 10f, tp(ACCENT, 8f, true))
            c.drawText("Duration", l + (r - l) * 0.48f, t + 10f, tp(ACCENT, 8f, true))
            c.drawText("Type", l + (r - l) * 0.65f, t + 10f, tp(ACCENT, 8f, true))
        }
        m.forEach { mg ->
            card(30f) { c, l, t, r, _ ->
                c.drawText(mg.start.atZone(ZoneId.systemDefault()).format(fmt), l, t + 10f, tp(BODY, 7.5f))
                val sc = when { (mg.severity ?: 0) <= 3 -> SM; (mg.severity ?: 0) <= 6 -> SMO; else -> SS }
                c.drawText(mg.severity?.toString() ?: "–", l + (r - l) * 0.40f, t + 10f, tp(sc, 8f, true))
                c.drawText(mg.end?.let { String.format("%.1fh", (it.toEpochMilli() - mg.start.toEpochMilli()) / 3600000.0) } ?: "ongoing", l + (r - l) * 0.48f, t + 10f, tp(BODY, 7.5f))
                c.drawText(mg.label?.take(30) ?: "–", l + (r - l) * 0.65f, t + 10f, tp(BODY, 7f))
            }
        }
    }

    private fun cToA(c: androidx.compose.ui.graphics.Color): Int = Color.argb((c.alpha * 255).toInt(), (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
}
